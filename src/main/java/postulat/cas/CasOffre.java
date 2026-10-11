package postulat.cas;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import postulat.ia.AnalyseOffre;
import postulat.ia.AppelModele;
import postulat.ia.Demande;
import postulat.ia.ErreurModele;
import postulat.ia.Modele;
import postulat.ia.ModeleJournalise;
import postulat.ia.RapprochementOffre;
import postulat.production.OffreAnalysee;
import postulat.production.Profil;
import postulat.production.Rapprochement;
import postulat.stockage.Base;
import postulat.stockage.LectureProfil;
import postulat.stockage.Numerotee;
import postulat.stockage.Offre;
import postulat.stockage.Reglages;
import postulat.suivi.Candidature;
import postulat.suivi.Etape;

/**
 * Soumettre une offre et la comprendre avant d'y investir du temps (UC-05) : analyse, rapprochement avec
 * le profil, note d'adéquation. Écrit une fois pour la ligne de commande et pour la fenêtre ; la décision
 * — poursuivre ou écarter — reste au candidat (UC-06).
 */
public final class CasOffre {

    /** En deçà, ce n'est pas une offre : jamais d'analyse d'une page vide (UC-05 E1). */
    public static final int LONGUEUR_MINIMALE = 150;

    private final Contexte ctx;

    public CasOffre(Contexte ctx) {
        this.ctx = Objects.requireNonNull(ctx);
    }

    /** L'issue d'une soumission. */
    public sealed interface Soumission permits DejaSuivie, Ajoutee {}

    /**
     * La même offre est déjà suivie (RG-18) : rien n'a été créé.
     *
     * @param memeTexte vrai si c'est exactement le même texte ; faux si c'est la même entreprise et le même poste
     */
    public record DejaSuivie(Numerotee existante, boolean memeTexte) implements Soumission {}

    /** La candidature est créée, à l'étape « repérée » ; l'analyse est absente si le modèle était indisponible (E2). */
    public record Ajoutee(Numerotee numerotee, Optional<OffreAnalysee> analyse) implements Soumission {}

    /**
     * Le texte d'une offre, tel qu'il s'analyse ; un texte trop court est refusé avant tout appel (UC-05 E1).
     *
     * @param conseil ce qu'il faut faire, dit dans les mots de l'interface
     */
    public static String exigerTexte(String texte, String conseil) {
        String net = texte.strip();
        if (net.length() < LONGUEUR_MINIMALE) {
            throw new Refus(("Texte trop court pour une offre (" + net.length() + " caractères) : rien n'a été analysé. " + conseil).strip());
        }
        return net;
    }

    /**
     * Soumet une offre (UC-05) : analyse (P2), puis création de la candidature à l'étape « repérée ». Le
     * rapprochement se demande ensuite, par {@link #rapprocher}.
     *
     * @param origine d'où vient le texte : un chemin de fichier, « entrée standard », « collé »
     * @param demander pose la question d'une valeur que ni l'option ni l'analyse ne donnent — « Entreprise »,
     *     féminin ou non — et rend la réponse, ou rien si l'on ne peut pas demander
     * @param autreOffre appelé quand une candidature porte déjà la même entreprise et le même poste : vrai
     *     pour ajouter quand même, l'offre étant une autre (RG-18)
     * @param info reçoit au fil de l'eau ce qui mérite d'être dit : une analyse impossible
     */
    public Soumission soumettre(String texte, String origine, Optional<String> entreprise, Optional<String> poste, LocalDate date,
            Predicate<Numerotee> autreOffre, BiFunction<String, Boolean, Optional<String>> demander, Consumer<String> info) {
        String net = exigerTexte(texte, "");
        Reglages reglages = ctx.donnees().reglages();
        // L'analyse précède la candidature, qui lui doit son entreprise et son poste : ses appels attendent ici,
        // et s'inscrivent au journal rattachés à la candidature dès qu'elle existe (EN-5).
        List<AppelModele> enAttente = new ArrayList<>();
        try (Base base = ctx.donnees().ouvrirBase()) {
            Optional<Numerotee> memeTexte = base.candidatureDeLOffre(net);
            if (memeTexte.isPresent()) {
                return new DejaSuivie(memeTexte.get(), true);
            }
            Optional<OffreAnalysee> analyse;
            try {
                Modele modele = new ModeleJournalise(ctx.fabriqueModele().apply(reglages), enAttente::add, ctx.horloge());
                analyse = Optional.of(AnalyseOffre.analyser(modele, net, Optional.empty()));
            } catch (ErreurModele e) {
                info.accept("Analyse impossible : " + e.getMessage());
                analyse = Optional.empty();
            }
            Optional<Numerotee> creee = Optional.empty();
            try {
                String nomEntreprise = choisir(entreprise, analyse.map(OffreAnalysee::entreprise), "Entreprise", true, demander);
                String intitule = choisir(poste, analyse.map(OffreAnalysee::poste), "Poste", false, demander);
                Optional<Numerotee> existante = base.toutes().stream()
                        .filter(n -> n.candidature().memeOffre(nomEntreprise, intitule)).findFirst();
                if (existante.isPresent() && !autreOffre.test(existante.get())) {
                    return new DejaSuivie(existante.get(), false);
                }
                Candidature c = Candidature.creer(nomEntreprise, intitule, Etape.REPEREE, date, ctx.aujourdhui());
                creee = Optional.of(base.creerDepuisOffre(c, origine, net, ctx.horloge().instant(), analyse));
                return new Ajoutee(creee.get(), analyse);
            } finally {
                Optional<UUID> rattachement = creee.map(n -> n.candidature().id());
                enAttente.forEach(a -> base.journaliser(rattacher(a, rattachement)));
            }
        }
    }

    /** Entreprise et poste viennent de l'option, de l'analyse, ou de la question ; sinon, rien n'est créé. */
    private static String choisir(Optional<String> option, Optional<String> analyse, String nom, boolean feminin,
            BiFunction<String, Boolean, Optional<String>> demander) {
        if (option.isPresent() && !option.get().isBlank()) {
            return option.get().strip();
        }
        if (analyse.isPresent() && !analyse.get().isBlank()) {
            return analyse.get();
        }
        Optional<String> saisi = demander.apply(nom, feminin).filter(s -> !s.isBlank()).map(String::strip);
        if (saisi.isPresent()) {
            return saisi.get();
        }
        throw new Refus(nom + (feminin ? " inconnue : précisez-la" : " inconnu : précisez-le") + " avec --"
                + nom.toLowerCase(Locale.ROOT) + ". Rien n'a été enregistré.");
    }

    private static AppelModele rattacher(AppelModele a, Optional<UUID> candidature) {
        Demande d = a.demande();
        return new AppelModele(a.survenuLe(), new Demande(d.tache(), d.gamme(), d.consignes(), d.contenu(), candidature),
                a.reponse(), a.issue(), a.consommation());
    }

    /**
     * Analyse de nouveau une offre conservée — quand le modèle était indisponible, ou entièrement avec
     * {@code tout} —, puis la rapproche du profil. Refusé si un dossier a été validé : l'offre est figée
     * avec lui (CA-6.8).
     */
    public void analyser(long numero, boolean tout, Consumer<String> info) {
        Reglages reglages = ctx.donnees().reglages();
        try (Base base = ctx.donnees().ouvrirBase()) {
            Numerotee n = CasDossier.trouver(base, numero);
            UUID id = n.candidature().id();
            if (base.aUnDossierValide(id)) {
                throw new Refus("Un dossier de la candidature n° " + numero + " a été validé : son offre, son analyse et son "
                        + "rapprochement sont figés avec lui (CA-6.8). Rien n'a été refait.");
            }
            Offre offre = base.offre(id).orElseThrow(() -> new Refus("La candidature n° " + numero
                    + " a été saisie sans offre : il n'y a rien à analyser."));
            OffreAnalysee analyse;
            if (offre.analyse().isEmpty() || tout) {
                analyse = AnalyseOffre.analyser(ctx.modele(base, reglages), offre.texteBrut(), Optional.of(id));
                base.enregistrerAnalyse(id, analyse, ctx.horloge().instant());
                info.accept("Offre analysée.");
            } else {
                analyse = offre.analyse().get();
            }
            rapprocher(base, reglages, n, analyse, info);
        }
    }

    /** P3 et P4 sur une offre déjà analysée ; ce qui l'empêche est dit, pas tu (UC-05 A3). */
    public void rapprocher(long numero, Consumer<String> info) {
        try (Base base = ctx.donnees().ouvrirBase()) {
            Numerotee n = CasDossier.trouver(base, numero);
            Optional<OffreAnalysee> analyse = base.offre(n.candidature().id()).flatMap(Offre::analyse);
            if (analyse.isPresent()) {
                rapprocher(base, ctx.donnees().reglages(), n, analyse.get(), info);
            }
        }
    }

    /**
     * P3 et P4, si le profil le permet (UC-05 A3) : sans profil utilisable, l'offre reste analysée mais non
     * rapprochée, et le système le dit.
     */
    private void rapprocher(Base base, Reglages reglages, Numerotee n, OffreAnalysee analyse, Consumer<String> info) {
        if (analyse.exigences().isEmpty()) {
            info.accept("Aucune exigence relevée dans l'offre : rapprochement sans objet.");
            return;
        }
        Optional<Profil> profil = profilUtilisable(n.numero(), info);
        if (profil.isEmpty()) {
            return;
        }
        UUID id = n.candidature().id();
        try {
            Rapprochement r = RapprochementOffre.rapprocher(ctx.modele(base, reglages), profil.get(), analyse.exigences(), id);
            base.enregistrerRapprochement(id, r, ctx.horloge().instant());
        } catch (ErreurModele e) {
            info.accept("Rapprochement impossible : " + e.getMessage() + " Plus tard : postulat offre analyser " + n.numero());
        }
    }

    private Optional<Profil> profilUtilisable(long numero, Consumer<String> info) {
        Path fichier = ctx.donnees().fichierProfil();
        if (!Files.exists(fichier)) {
            info.accept("Rapprochement impossible : aucun profil (postulat profil initialiser). L'offre reste analysée ; "
                    + "ensuite : postulat offre analyser " + numero);
            return Optional.empty();
        }
        LectureProfil lecture = ctx.donnees().lireProfil(fichier);
        if (!lecture.estUtilisable()) {
            info.accept("Rapprochement impossible : le profil a " + lecture.problemes().size()
                    + " problème(s) (postulat profil verifier). Ensuite : postulat offre analyser " + numero);
            return Optional.empty();
        }
        return lecture.profil();
    }
}
