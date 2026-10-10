package postulat.ia;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.NoCredentialsException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredOutputConfig;
import com.anthropic.models.messages.StructuredTextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Usage;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import postulat.ia.ErreurModele.Nature;

/**
 * Le modèle de langage derrière l'API Anthropic, par le SDK Java officiel (pile technique § 6).
 *
 * <p>Ce qui est fixé ici, et pourquoi :
 *
 * <ul>
 *   <li><b>Sorties structurées.</b> La forme de la réponse est un {@code record} dont le SDK dérive le
 *       schéma JSON : la réponse le respecte par construction, sans analyse de texte.
 *   <li><b>Cache.</b> Les consignes, partie stable de chaque demande, portent un point de cache : d'une
 *       demande à l'autre, elles se relisent à une fraction du prix.
 *   <li><b>Effort.</b> Bas en gamme économique — de l'extraction —, haut en gamme capable — de la
 *       rédaction et du jugement. La réflexion adaptative reste celle par défaut des modèles.
 *   <li><b>Repli sur refus.</b> Quand le modèle le permet, un refus des classifieurs de sûreté est
 *       rejoué côté serveur sur le modèle de repli recommandé par Anthropic. Claude Haiku 5.5 n'en a
 *       pas : un refus y est signalé tel quel.
 *   <li><b>Nouvelles tentatives.</b> Celles du SDK : deux, sur limite de débit, surcharge, erreur
 *       serveur et coupure réseau. Au-delà, l'appel échoue et le dit.
 * </ul>
 */
public final class ModeleAnthropic implements Modele {

    /** Repli côté serveur, forme « default » : routé par catégorie de refus (API Claude). */
    static final String BETA_REPLI = "server-side-fallback-2026-07-01";

    /** Sans streaming, une réponse plus longue risquerait le délai d'attente HTTP du SDK. */
    static final long JETONS_MAX = 16_000L;

    /** Modèles qui acceptent le repli « default » ; Claude Haiku 5.5 n'en fait pas partie. */
    private static final Set<String> AVEC_REPLI = Set.of("claude-opus-5-5", "claude-opus-5", "claude-fable-5-1", "claude-sonnet-5-5");

    private final AnthropicClient client;
    private final Map<Gamme, String> modeles = new EnumMap<>(Gamme.class);

    public ModeleAnthropic(AnthropicClient client, String modeleEconomique, String modeleCapable) {
        this.client = client;
        modeles.put(Gamme.ECONOMIQUE, exigerTarif(modeleEconomique));
        modeles.put(Gamme.CAPABLE, exigerTarif(modeleCapable));
    }

    /** La clé vient de l'environnement — ANTHROPIC_API_KEY, ou un profil « ant auth login » —, jamais d'un fichier du projet. */
    public static ModeleAnthropic depuisEnvironnement(String modeleEconomique, String modeleCapable) {
        try {
            return new ModeleAnthropic(AnthropicOkHttpClient.fromEnv(), modeleEconomique, modeleCapable);
        } catch (NoCredentialsException e) {
            throw sansCle(e);
        }
    }

    public String modele(Gamme gamme) {
        return modeles.get(gamme);
    }

    @Override
    public <T> Reponse<T> demander(Demande demande, Class<T> forme) {
        String modele = modeles.get(demande.gamme());
        StructuredMessageCreateParams<T> parametres = parametres(demande, forme, modele);
        long debut = System.nanoTime();
        StructuredMessage<T> message = appeler(parametres, modele);
        Duration duree = Duration.ofNanos(System.nanoTime() - debut);
        Consommation consommation = consommation(message.usage(), message.model().asString(), modele, duree);
        verifierFin(message, consommation);
        return lire(message, consommation);
    }

    // --- requête ---

    static <T> StructuredMessageCreateParams<T> parametres(Demande demande, Class<T> forme, String modele) {
        StructuredMessageCreateParams.Builder<T> parametres = MessageCreateParams.builder()
                .model(modele)
                .maxTokens(JETONS_MAX)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(demande.consignes())
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .addUserMessage(demande.contenu())
                .outputConfig(forme)
                .outputConfig(StructuredOutputConfig.<T>builder()
                        .effort(demande.gamme() == Gamme.CAPABLE ? OutputConfig.Effort.HIGH : OutputConfig.Effort.LOW)
                        .format(forme)
                        .build());
        if (AVEC_REPLI.contains(modele)) {
            parametres.putAdditionalHeader("anthropic-beta", BETA_REPLI)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return parametres.build();
    }

    /** Les erreurs du SDK, de la plus précise à la plus générale, traduites en ce qu'il faut faire. */
    private <T> StructuredMessage<T> appeler(StructuredMessageCreateParams<T> parametres, String modele) {
        try {
            return client.messages().create(parametres);
        } catch (NoCredentialsException e) {
            throw sansCle(e);
        } catch (UnauthorizedException e) {
            throw echec(Nature.CONFIGURATION, "Clé d'API refusée (401) : vérifiez la variable ANTHROPIC_API_KEY.", e);
        } catch (PermissionDeniedException e) {
            throw echec(Nature.CONFIGURATION, "Accès refusé par l'API (403) : " + e.getMessage(), e);
        } catch (NotFoundException e) {
            throw echec(Nature.CONFIGURATION, "Modèle « " + modele + " » inconnu de l'API (404) : vérifiez "
                    + "modele.economique et modele.capable dans postulat.properties.", e);
        } catch (RateLimitException e) {
            throw echec(Nature.INDISPONIBLE, "Limite de débit atteinte (429) malgré les nouvelles tentatives : "
                    + "réessayez dans quelques minutes.", e);
        } catch (InternalServerException e) {
            throw echec(Nature.INDISPONIBLE, "API momentanément indisponible (" + e.statusCode()
                    + ") : réessayez plus tard, rien n'est perdu.", e);
        } catch (BadRequestException e) {
            throw echec(Nature.INVALIDE, "Demande rejetée par l'API (400) : " + e.getMessage(), e);
        } catch (AnthropicServiceException e) {
            if (e.statusCode() == 402) {
                throw echec(Nature.CONFIGURATION, "Crédit épuisé : approvisionnez le compte dans la Console Anthropic.", e);
            }
            throw echec(Nature.INVALIDE, "Erreur de l'API (" + e.statusCode() + ") : " + e.getMessage(), e);
        } catch (AnthropicIoException | AnthropicRetryableException e) {
            throw echec(Nature.INDISPONIBLE, "API injoignable (réseau) : réessayez plus tard, rien n'est perdu.", e);
        } catch (AnthropicException e) {
            throw echec(Nature.INVALIDE, "Appel au modèle impossible : " + e.getMessage(), e);
        }
    }

    // --- réponse ---

    /**
     * Le coût se calcule au tarif du modèle qui a répondu — le modèle de repli, le cas échéant —, et à
     * défaut à celui du modèle demandé.
     */
    static Consommation consommation(Usage usage, String modeleRepondu, String modeleDemande, Duration duree) {
        long lus = usage.cacheReadInputTokens().orElse(0L);
        long ecrits = usage.cacheCreationInputTokens().orElse(0L);
        Tarif tarif = Tarif.de(modeleRepondu).or(() -> Tarif.de(modeleDemande)).orElseThrow();
        long cout = tarif.microDollars(usage.inputTokens(), usage.outputTokens(), lus, ecrits);
        return new Consommation(modeleRepondu, usage.inputTokens(), usage.outputTokens(), lus, ecrits, cout, duree);
    }

    /** Un refus et une réponse coupée ne sont pas des réponses : ils se paient, mais ne s'utilisent pas. */
    private static void verifierFin(StructuredMessage<?> message, Consommation consommation) {
        Optional<StopReason> fin = message.stopReason();
        if (fin.equals(Optional.of(StopReason.REFUSAL))) {
            String categorie = message.stopDetails().flatMap(d -> d.category()).map(Object::toString).orElse("non précisée");
            throw new ErreurModele(Nature.REFUS, "Le modèle a décliné la demande (catégorie : " + categorie
                    + "). Reformulez le contenu, ou écrivez ce passage vous-même.", consommation, null);
        }
        if (fin.equals(Optional.of(StopReason.MAX_TOKENS))) {
            throw new ErreurModele(Nature.TRONQUEE, "Réponse coupée à " + JETONS_MAX
                    + " jetons : la demande est trop vaste pour une seule réponse.", consommation, null);
        }
        if (fin.equals(Optional.of(StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED))) {
            throw new ErreurModele(Nature.TRONQUEE, "Demande trop longue pour la fenêtre du modèle.", consommation, null);
        }
    }

    private static <T> Reponse<T> lire(StructuredMessage<T> message, Consommation consommation) {
        try {
            StructuredTextBlock<T> bloc = message.content().stream()
                    .flatMap(c -> c.text().stream())
                    .findFirst()
                    .orElseThrow(() -> new ErreurModele(Nature.INVALIDE, "Réponse sans texte.", consommation, null));
            return new Reponse<>(bloc.text(), bloc.rawTextBlock().text(), consommation);
        } catch (AnthropicException | IllegalArgumentException e) {
            throw new ErreurModele(Nature.INVALIDE, "Réponse hors de la forme demandée : " + e.getMessage(), consommation, e);
        }
    }

    // --- interne ---

    private static String exigerTarif(String modele) {
        if (Tarif.de(modele).isEmpty()) {
            throw new IllegalArgumentException("Modèle « " + modele + " » sans tarif connu : son coût ne pourrait pas "
                    + "être mesuré (EN-5). Modèles possibles : " + String.join(", ", Tarif.modelesConnus()) + ".");
        }
        return modele;
    }

    private static ErreurModele sansCle(Exception cause) {
        return echec(Nature.CONFIGURATION, "Aucune clé d'API. Créez-en une dans la Console Anthropic, puis définissez "
                + "la variable d'environnement ANTHROPIC_API_KEY — jamais dans un fichier du projet.", cause);
    }

    private static ErreurModele echec(Nature nature, String message, Exception cause) {
        return new ErreurModele(nature, message, null, cause);
    }
}
