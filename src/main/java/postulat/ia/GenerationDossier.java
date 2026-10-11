package postulat.ia;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import postulat.ia.ErreurModele.Nature;
import postulat.production.Assertion;
import postulat.production.Ats;
import postulat.production.AvisRecruteur;
import postulat.production.Critere;
import postulat.production.Cv;
import postulat.production.Dossier;
import postulat.production.Evaluation;
import postulat.production.Exigence;
import postulat.production.Identite;
import postulat.production.Lettre;
import postulat.production.NoteAts;
import postulat.production.OffreAnalysee;
import postulat.production.Origine;
import postulat.production.Profil;
import postulat.production.Rapprochement;
import postulat.production.ReglesGeneration;
import postulat.production.Tracabilite;
import postulat.production.Violation;
import postulat.rendu.ErreurRendu;
import postulat.rendu.Rendu;

/**
 * P5, P6 et P7 : générer le dossier d'une candidature — CV ciblé, lettre —, l'évaluer, et le réviser tant
 * qu'il reste sous le seuil, dans la limite des révisions permises (UC-07, CA-5.4). Gamme capable :
 * c'est de la rédaction et du jugement.
 *
 * <p>Le modèle rédige, le code vérifie et tranche :
 *
 * <ul>
 *   <li>chaque document passe la vérification de traçabilité (RG-01 à RG-04) et ses contraintes de
 *       structure ; refusé, il est redemandé une fois avec ses défauts, et un second refus fait échouer
 *       toute la génération (UC-07 E2). Rien n'est alors enregistré, sauf le journal des appels
 *       (lacune L-07) ;
 *   <li>le CV est rendu sur une page, au besoin privé de ses puces les moins pertinentes (RG-26), puis
 *       relu : c'est ce texte que la note ATS et le recruteur examinent (CA-5.1) ;
 *   <li>les corrections du recruteur qui nomment une lacune sont écartées (RG-07) ;
 *   <li>sous le seuil, une révision repart de l'avis ; au bout des révisions, la meilleure version est
 *       gardée, et le rapport dit que le seuil n'est pas atteint (UC-07 A2).
 * </ul>
 *
 * <p>Le modèle n'écrit jamais une phrase d'origine « auteur » : la forme de ses réponses ne le permet pas.
 */
public final class GenerationDossier {

    // --- formes imposées aux réponses ---

    /** P5. */
    public record CvGenere(
            @JsonPropertyDescription("Les sections, dans l'ordre du CV") List<SectionGeneree> sections) {}

    public record SectionGeneree(
            @JsonPropertyDescription("Titre de la section : Expériences, Formation, Compétences…") String titre,
            @JsonPropertyDescription("Expériences ou formations, chacune avec ses puces ; vide pour une section de lignes")
                    List<BlocGenere> blocs,
            @JsonPropertyDescription("Lignes simples, comme une liste d'outils ; vide pour une section de blocs")
                    List<PhraseGeneree> lignes) {}

    public record BlocGenere(
            @JsonPropertyDescription("Poste et organisation, ou diplôme et établissement") PhraseGeneree intitule,
            @JsonPropertyDescription("Dates, durée, lieu ; un texte vide s'il n'y en a pas") PhraseGeneree precision,
            @JsonPropertyDescription("Une puce par réalisation") List<PuceGeneree> puces) {}

    public record PuceGeneree(
            @JsonPropertyDescription("La phrase de la puce") String texte,
            @JsonPropertyDescription("Identifiants des faits dont elle dérive") List<String> faits,
            @JsonPropertyDescription("Rang de pertinence pour l'offre, unique dans le CV : 1 pour la plus pertinente") int rang,
            @JsonPropertyDescription("Référence d'une phrase de l'auteur à placer ici telle quelle, A1, A2… ; vide sinon")
                    String reprise) {}

    public record PhraseGeneree(
            @JsonPropertyDescription("Le texte") String texte,
            @JsonPropertyDescription("Identifiants des faits dont il dérive") List<String> faits,
            @JsonPropertyDescription("Référence d'une phrase de l'auteur à placer ici telle quelle, A1, A2… ; vide sinon")
                    String reprise) {}

    /** P6. */
    public record LettreGeneree(
            @JsonPropertyDescription("Vous : l'entreprise et le poste, d'après l'offre") List<PhraseLettre> vous,
            @JsonPropertyDescription("Moi : ce que le candidat apporte, prouvé par ses faits") List<PhraseLettre> moi,
            @JsonPropertyDescription("Nous : ce que nous ferions ensemble, et la demande d'entretien") List<PhraseLettre> nous) {}

    public record PhraseLettre(
            @JsonPropertyDescription("Une phrase") String texte,
            @JsonPropertyDescription("« faits » si elle s'appuie sur le profil, « offre » si elle ne dit que ce que l'offre dit")
                    String origine,
            @JsonPropertyDescription("Identifiants des faits dont elle dérive ; vide pour l'origine « offre »") List<String> faits,
            @JsonPropertyDescription("Référence d'une phrase de l'auteur à placer ici telle quelle, A1, A2… ; vide sinon")
                    String reprise) {}

    /** P7. */
    public record AvisLu(
            @JsonPropertyDescription("Une note par critère de la grille") List<CritereLu> criteres,
            @JsonPropertyDescription("Les forces du dossier") List<String> forces,
            @JsonPropertyDescription("Les faiblesses du dossier") List<String> faiblesses,
            @JsonPropertyDescription("Corrections concrètes, chacune faisable avec les seuls faits du profil") List<String> corrections) {}

    public record CritereLu(
            @JsonPropertyDescription("Le critère : ADEQUATION, PREUVES, CIBLAGE, CLARTE ou LANGUE") String critere,
            @JsonPropertyDescription("Note de 0 à 5") int note,
            @JsonPropertyDescription("Une phrase qui justifie la note") String justification) {}

    // --- l'offre visée ---

    /**
     * L'offre visée, telle que la base la conserve.
     *
     * @param entreprise et {@code poste} : ceux de la candidature, que le candidat a pu corriger
     * @param rapprochement exigé dès que l'offre a des exigences : sans lui, les lacunes sont inconnues et
     *     RG-04 invérifiable
     */
    public record Cible(
            UUID candidature, String entreprise, String poste, OffreAnalysee analyse, String texteBrut,
            Optional<Rapprochement> rapprochement) {

        public Cible {
            Objects.requireNonNull(candidature);
            Objects.requireNonNull(entreprise);
            Objects.requireNonNull(poste);
            Objects.requireNonNull(analyse);
            Objects.requireNonNull(texteBrut);
            Objects.requireNonNull(rapprochement);
            if (!analyse.exigences().isEmpty() && rapprochement.isEmpty()) {
                throw new IllegalArgumentException("L'offre n'est pas rapprochée du profil : ses lacunes sont inconnues.");
            }
        }

        public List<Exigence> lacunes() {
            return rapprochement.map(Rapprochement::lacunes).orElse(List.of());
        }
    }

    // --- les phrases de l'auteur ---

    /**
     * Une phrase de l'auteur à reporter telle quelle dans une régénération (UC-08 A1, RG-27). Le modèle
     * n'en choisit que la place, par sa référence ; le texte, c'est le programme qui l'écrit : il ne peut
     * donc pas être réécrit.
     *
     * @param reference A1, A2… dans l'ordre de lecture du dossier
     * @param dansLeCv faux pour une phrase de la lettre : chacune revient dans son document
     * @param contexte où elle était, pour que le modèle la replace à propos
     */
    public record Reprise(String reference, boolean dansLeCv, String contexte, String texte) {}

    /**
     * Les phrases de l'auteur d'un dossier, à reporter dans le suivant. Une phrase versée au profil n'y est
     * plus : devenue un fait, elle suit le régime ordinaire (UC-08 A1, RG-27).
     */
    public static List<Reprise> reprises(Dossier precedent) {
        List<Reprise> reprises = new ArrayList<>();
        List<Assertion> phrases = precedent.assertions();
        int nCv = precedent.cv().assertions().size();
        for (int i = 0; i < phrases.size(); i++) {
            if (phrases.get(i).origine() instanceof Origine.Auteur auteur && auteur.verseeDans().isEmpty()) {
                reprises.add(new Reprise("A" + (reprises.size() + 1), i < nCv,
                        i < nCv ? contexte(precedent.cv(), i) : contexte(precedent.lettre(), i - nCv), phrases.get(i).texte()));
            }
        }
        return reprises;
    }

    private static String contexte(Cv cv, int index) {
        Cv.Place p = cv.place(index);
        Cv.Section section = cv.sections().get(p.section());
        String ou = "section « " + section.titre() + " »";
        if (section.contenus().get(p.contenu()) instanceof Cv.Bloc b && p.role() != Cv.Role.INTITULE) {
            ou += ", bloc « " + b.intitule().texte() + " »";
        }
        return ou + (p.role() == Cv.Role.INTITULE ? ", intitulé de bloc" : p.role() == Cv.Role.PRECISION ? ", précision" : "");
    }

    private static String contexte(Lettre lettre, int index) {
        String[] parties = {"Vous", "Moi", "Nous"};
        int debut = 0;
        for (int p = 0; p < lettre.paragraphes().size(); p++) {
            debut += lettre.paragraphes().get(p).phrases().size();
            if (index < debut) {
                return "partie « " + (p < parties.length ? parties[p] : String.valueOf(p + 1)) + " »";
            }
        }
        return "lettre";
    }

    /** Ce que la demande dit des phrases de l'auteur d'un document : rien s'il n'y en a pas. */
    private static String reprisesDemandees(List<Reprise> reprises) {
        return reprises.isEmpty() ? "" : "\n\nPhrases de l'auteur, à reprendre telles quelles :\n" + reprises.stream()
                .map(x -> "- " + x.reference() + " (" + x.contexte() + ") : « " + x.texte() + " »")
                .collect(Collectors.joining("\n"));
    }

    /**
     * Les phrases de l'auteur qu'un document doit reprendre, et l'usage que la réponse en fait : chacune
     * une fois, aucune autre (RG-27).
     */
    private static final class Placement {

        private final Map<String, Reprise> attendues = new LinkedHashMap<>();
        private final Map<String, Integer> placees = new LinkedHashMap<>();

        Placement(List<Reprise> reprises) {
            reprises.forEach(x -> attendues.put(x.reference(), x));
        }

        boolean demande(String reference) {
            return !net(reference).isEmpty();
        }

        /** La phrase de l'auteur désignée, au mot près ; une référence inconnue est notée, et refusée plus tard. */
        Assertion phrase(String reference) {
            String ref = net(reference).toUpperCase(Locale.ROOT);
            placees.merge(ref, 1, Integer::sum);
            Reprise x = attendues.get(ref);
            return new Assertion(x == null ? ref : x.texte(), new Origine.Auteur());
        }

        Assertion ou(String reference, java.util.function.Supplier<Assertion> sinon) {
            return demande(reference) ? phrase(reference) : sinon.get();
        }

        List<String> problemes() {
            List<String> problemes = new ArrayList<>();
            placees.forEach((ref, fois) -> {
                if (!attendues.containsKey(ref)) {
                    problemes.add("La référence « " + ref + " » ne désigne aucune phrase de l'auteur de ce document.");
                } else if (fois > 1) {
                    problemes.add("La phrase de l'auteur " + ref + " est placée " + fois + " fois ; une seule suffit.");
                }
            });
            attendues.keySet().stream().filter(ref -> !placees.containsKey(ref)).forEach(ref -> problemes.add(
                    "La phrase de l'auteur " + ref + " manque : elle se reporte telle quelle (RG-27)."));
            return problemes;
        }
    }

    // --- consignes : la partie stable des demandes ---

    static final String CONSIGNES_CV = """
            Tu rédiges en français le CV d'un candidat à un stage, ciblé sur une offre.

            Le profil ci-dessous est la seule source de vérité sur le candidat. Tu choisis, ordonnes et reformules
            ses faits ; tu n'en crées aucun. Un programme vérifie ton CV et le refuse à la moindre faute :
            - chaque élément cite dans « faits » les identifiants, écrits entre crochets dans le profil, des faits
              dont il dérive : au moins un, et seulement des identifiants du profil ;
            - tout nombre, date, durée ou pourcentage figure dans les faits cités ;
            - une exigence marquée LACUNE n'apparaît nulle part, sous aucune forme, pas même pour dire qu'elle manque.

            Structure :
            - les sections, dans cet ordre quand le profil les remplit : « Expériences », « Formation », « Compétences » ;
            - une expérience ou une formation est un bloc : un intitulé (poste — organisation, ou diplôme —
              établissement) qui cite son fait, une précision (dates, durée, lieu) qui le cite aussi, ou un texte
              vide, puis ses puces ;
            - une puce par réalisation : un verbe d'action, ce qui a été fait, le résultat quand il est connu. Une
              puce ne fusionne jamais deux réalisations ;
            - « Compétences » est faite de lignes, sans blocs : par exemple une ligne par famille d'outils ;
            - chaque puce reçoit un rang de pertinence pour l'offre, unique dans tout le CV, 1 pour la plus
              pertinente. Si le CV dépasse une page, le programme retirera les puces aux rangs les plus élevés.

            Ciblage :
            - pour une exigence couverte, reprends ses mots exacts, tels que l'offre les écrit : un filtre de
              recrutement les cherchera tels quels ;
            - mets en avant ce qui sert l'offre ; une réalisation sans rapport peut être omise ;
            - respecte la consigne de présentation d'un fait quand il en a une.

            Phrases de l'auteur : quand le contenu en donne, chacune se place une fois, dans ce document, en
            écrivant sa référence dans « reprise » ; le programme y met le texte exact. Tu ne les réécris jamais.
            Hors de ce cas, « reprise » reste vide.
            """;

    static final String CONSIGNES_LETTRE = """
            Tu rédiges en français soigné la lettre de motivation d'un candidat à un stage, pour une offre précise.

            Le profil ci-dessous est la seule source de vérité sur le candidat ; le texte de l'offre, la seule sur
            l'entreprise. Un programme vérifie ta lettre et la refuse à la moindre faute.

            Structure Vous – Moi – Nous, un paragraphe chacun :
            - vous : l'entreprise et le poste, et ce qui y attire le candidat ;
            - moi : ce que le candidat apporte, preuves à l'appui ;
            - nous : ce que nous ferions ensemble, puis la demande d'un entretien.
            N'écris ni appel (« Madame, Monsieur »), ni formule de politesse, ni signature : le programme les ajoute.

            Chaque phrase porte son origine :
            - « faits » : elle s'appuie sur le profil et cite dans « faits » les identifiants, écrits entre crochets,
              des faits dont elle dérive ; tout nombre ou date qu'elle contient y figure ;
            - « offre » : elle ne dit que ce que le texte de l'offre dit, et n'affirme rien sur le candidat ; tout
              nombre qu'elle contient figure dans l'offre.
            Toutes les phrases de « moi » sont d'origine « faits ».
            Une exigence marquée LACUNE n'apparaît nulle part, sous aucune forme, pas même pour dire qu'elle manque.
            Respecte la consigne de présentation d'un fait quand il en a une.

            Phrases de l'auteur : quand le contenu en donne, chacune se place une fois, dans ce document, en
            écrivant sa référence dans « reprise » ; le programme y met le texte exact. Tu ne les réécris jamais.
            Hors de ce cas, « reprise » reste vide.
            """;

    static final String CONSIGNES_AVIS = """
            Tu es un recruteur exigeant. Tu lis le dossier d'un candidat à un stage — son CV, tel qu'un logiciel
            l'a extrait du PDF, et sa lettre — et tu le juges face à l'offre, selon cette grille, chaque critère
            noté de 0 à 5 :
            %s

            Puis donne les forces, les faiblesses, et des corrections concrètes.

            Une correction ne s'appuie que sur le profil ci-dessous : reformuler, réordonner, mettre en avant un
            fait qui existe, reprendre le mot exact de l'offre, resserrer. Ne propose jamais d'ajouter une
            compétence, une expérience ou un résultat que le profil ne contient pas, ni d'écrire une exigence
            marquée LACUNE. Sois franc : un 5 se mérite.
            """.formatted(Arrays.stream(Critere.values())
                    .map(c -> "- " + c.name() + " (" + c.libelle() + ") : " + c.question())
                    .collect(Collectors.joining("\n")));

    private GenerationDossier() {}

    /**
     * Génère, évalue et révise le dossier.
     *
     * @param reprises les phrases de l'auteur du dossier précédent, reportées telles quelles (RG-27)
     * @param aujourdhui la date de la lettre : le domaine ne lit pas l'horloge
     * @param progression reçoit une ligne à chaque étape : la génération prend du temps
     * @throws ErreurModele si le modèle est indisponible, ou si un document reste refusé après une reprise
     * @throws IllegalArgumentException si le profil est incomplet : refusé avant tout appel (UC-07 E1)
     */
    public static Dossier generer(Modele modele, Profil profil, Cible cible, List<Reprise> reprises, ReglesGeneration regles,
            LocalDate aujourdhui, Consumer<String> progression) {
        if (!profil.estComplet()) {
            throw new IllegalArgumentException("Le profil est incomplet : la génération est refusée avant tout appel au "
                    + "modèle (UC-07 E1).");
        }
        String profilLu = profilLu(profil);
        List<Reprise> duCv = reprises.stream().filter(Reprise::dansLeCv).toList();
        List<Reprise> deLaLettre = reprises.stream().filter(x -> !x.dansLeCv()).toList();
        Version meilleure = null;
        String retour = "";
        int passes = 0;
        while (passes <= regles.revisionsMax()) {
            passes++;
            String passe = passes == 1 ? "Première passe" : "Révision " + (passes - 1);

            progression.accept(passe + " : CV ciblé…");
            Demande demandeCv = new Demande("cv-cible", Gamme.CAPABLE, CONSIGNES_CV + profilLu,
                    offre(cible) + "\n\n" + exigences(cible) + reprisesDemandees(duCv) + retour, Optional.of(cible.candidature()));
            Cv complet = produire(modele, demandeCv, CvGenere.class, g -> cv(g, profil, cible, duCv), "le CV");
            Rendu.Resultat cvRendu = Rendu.cv(complet);
            Cv cv = complet;
            List<Dossier.Retiree> retirees = new ArrayList<>();
            for (Cv.Puce p : cvRendu.retirees()) {
                Cv.Place place = complet.place(p).orElseThrow();
                retirees.add(new Dossier.Retiree(p, place.section(), place.contenu()));
                cv = cv.sans(p);
            }

            progression.accept(passe + " : lettre…");
            Demande demandeLettre = new Demande("lettre", Gamme.CAPABLE,
                    CONSIGNES_LETTRE + "\nLongueur : entre " + regles.motsMin() + " et " + regles.motsMax()
                            + " mots pour les trois paragraphes ensemble." + profilLu,
                    offre(cible) + "\n\nTexte de l'offre :\n<<<\n" + cible.texteBrut().strip() + "\n>>>\n\n" + exigences(cible)
                            + reprisesDemandees(deLaLettre) + retour,
                    Optional.of(cible.candidature()));
            Lettre lettre = produire(modele, demandeLettre, LettreGeneree.class,
                    g -> lettre(g, profil, cible, deLaLettre, regles, aujourdhui), "la lettre");
            Rendu.Resultat lettreRendue = Rendu.lettre(lettre);

            Optional<NoteAts> ats = Ats.noter(cvRendu.texte(), cible.analyse().exigences());
            progression.accept(passe + " : avis du recruteur…");
            AvisRecruteur avisComplet = juger(modele, profilLu, cible, cvRendu.texte(), lettreRendue.texte());
            AvisRecruteur avis = avisComplet.sansCorrectionsSur(cible.lacunes());
            Evaluation evaluation = new Evaluation(ats, avis, regles);

            List<String> avertissements = new ArrayList<>(cvRendu.avertissements().stream().map(a -> "CV : " + a).toList());
            lettreRendue.avertissements().forEach(a -> avertissements.add("Lettre : " + a));
            Version v = new Version(cv, lettre, evaluation, retirees, avertissements,
                    avisComplet.correctionsSur(cible.lacunes()), cvRendu.texte(), lettreRendue.texte());
            progression.accept(passe + " : note globale " + evaluation.noteGlobale() + "/100, seuil " + regles.seuil() + ".");
            if (meilleure == null || evaluation.noteGlobale() > meilleure.evaluation().noteGlobale()) {
                meilleure = v;
            }
            if (evaluation.seuilAtteint()) {
                break;
            }
            retour = retour(v, cible);
        }
        return dossier(meilleure, passes);
    }

    /**
     * P7 seul, sur le dossier tel qu'il est : après des corrections à la main, un nouvel avis du recruteur
     * (UC-08, étape 7). La note ATS est recalculée sur le texte relu ; le CV et la lettre ne changent pas.
     */
    public static Dossier reevaluer(Modele modele, Profil profil, Cible cible, Dossier dossier) {
        if (!profil.estComplet()) {
            throw new IllegalArgumentException("Le profil est incomplet : l'avis est refusé avant tout appel au modèle.");
        }
        Rendu.Resultat cv = Rendu.cvTelQuel(dossier.cv());
        Rendu.Resultat lettre = Rendu.lettre(dossier.lettre());
        AvisRecruteur complet = juger(modele, profilLu(profil), cible, cv.texte(), lettre.texte());
        Evaluation evaluation = new Evaluation(Ats.noter(cv.texte(), cible.analyse().exigences()),
                complet.sansCorrectionsSur(cible.lacunes()), dossier.evaluation().regles());
        Dossier.Rapport r = dossier.rapport();
        return new Dossier(dossier.cv(), dossier.lettre(), evaluation,
                new Dossier.Rapport(r.passes(), r.retirees(), r.avertissements(), complet.correctionsSur(cible.lacunes())));
    }

    private static String profilLu(Profil profil) {
        return "\n\nProfil du candidat :\n" + TexteProfil.de(profil, true);
    }

    /** P7 : l'avis complet, corrections écartables comprises ; à l'appelant de les écarter (RG-07). */
    private static AvisRecruteur juger(Modele modele, String profilLu, Cible cible, String texteCv, String texteLettre) {
        Demande demande = new Demande("avis-recruteur", Gamme.CAPABLE, CONSIGNES_AVIS + profilLu,
                offre(cible) + "\n\n" + exigences(cible) + "\n\nCV :\n<<<\n" + texteCv.strip() + "\n>>>\n\nLettre :\n<<<\n"
                        + texteLettre.strip() + "\n>>>",
                Optional.of(cible.candidature()));
        return produire(modele, demande, AvisLu.class, GenerationDossier::avis, "l'avis du recruteur");
    }

    // --- une demande, une reprise ---

    /** Ce qu'une réponse a donné : le document, et tout ce qui le rend irrecevable. */
    private record Tentative<T>(T document, List<String> problemes) {

        static <T> Tentative<T> refusee(String probleme) {
            return new Tentative<>(null, List.of(probleme));
        }
    }

    /**
     * Pose la demande ; si la réponse est refusée, la repose une fois en disant pourquoi. Un second refus
     * fait échouer la génération : le modèle n'est pas relancé sans fin, à nos frais.
     */
    private static <F, T> T produire(Modele modele, Demande demande, Class<F> forme, Function<F, Tentative<T>> lire, String quoi) {
        Tentative<T> premiere = lire.apply(modele.demander(demande, forme).valeur());
        if (premiere.problemes().isEmpty()) {
            return premiere.document();
        }
        Demande reprise = new Demande(demande.tache(), demande.gamme(), demande.consignes(),
                demande.contenu() + "\n\nTa réponse précédente a été refusée par la vérification, pour ces raisons :\n- "
                        + String.join("\n- ", premiere.problemes()) + "\nRédige-la de nouveau en entier, sans ces défauts.",
                demande.candidature());
        Tentative<T> seconde = lire.apply(modele.demander(reprise, forme).valeur());
        if (seconde.problemes().isEmpty()) {
            return seconde.document();
        }
        throw new ErreurModele(Nature.INVALIDE, "Génération échouée, rien n'a été enregistré : " + quoi
                + " ne passe toujours pas la vérification après une reprise (UC-07 E2).\n  - "
                + String.join("\n  - ", seconde.problemes()), null, null);
    }

    // --- P5 : le CV ---

    private static Tentative<Cv> cv(CvGenere g, Profil profil, Cible cible, List<Reprise> reprises) {
        Placement placement = new Placement(reprises);
        Cv cv;
        try {
            List<Cv.Section> sections = new ArrayList<>();
            for (SectionGeneree s : liste(g.sections())) {
                List<Cv.Contenu> contenus = new ArrayList<>();
                for (BlocGenere b : liste(s.blocs())) {
                    List<Cv.Puce> puces = liste(b.puces()).stream()
                            .map(p -> new Cv.Puce(placement.ou(p.reprise(),
                                    () -> new Assertion(net(p.texte()), new Origine.Faits(ids(p.faits())))), p.rang()))
                            .toList();
                    Optional<Assertion> precision = b.precision() == null
                            || net(b.precision().texte()).isEmpty() && !placement.demande(b.precision().reprise())
                            ? Optional.empty()
                            : Optional.of(assertion(b.precision(), placement));
                    contenus.add(new Cv.Bloc(assertion(b.intitule(), placement), precision, puces));
                }
                liste(s.lignes()).forEach(l -> contenus.add(new Cv.Ligne(assertion(l, placement))));
                if (net(s.titre()).isEmpty()) {
                    return Tentative.refusee("Une section n'a pas de titre.");
                }
                if (!contenus.isEmpty()) {
                    sections.add(new Cv.Section(net(s.titre()), contenus));
                }
            }
            cv = new Cv(profil.identite(), sections);
        } catch (IllegalArgumentException e) {
            return Tentative.refusee("CV mal formé : " + e.getMessage());
        }
        if (cv.assertions().isEmpty()) {
            return Tentative.refusee("Le CV est vide.");
        }
        List<String> problemes = new ArrayList<>(placement.problemes());
        problemes.addAll(violations(cv.assertions(), Tracabilite.verifier(cv.assertions(), profil, cible.lacunes())));
        return new Tentative<>(cv, problemes);
    }

    private static Assertion assertion(PhraseGeneree p, Placement placement) {
        if (p == null) {
            throw new IllegalArgumentException("un intitulé manque.");
        }
        return placement.ou(p.reprise(), () -> new Assertion(net(p.texte()), new Origine.Faits(ids(p.faits()))));
    }

    // --- P6 : la lettre ---

    private static Tentative<Lettre> lettre(LettreGeneree g, Profil profil, Cible cible, List<Reprise> reprises, ReglesGeneration regles,
            LocalDate aujourdhui) {
        Placement placement = new Placement(reprises);
        List<String> problemes = new ArrayList<>();
        List<Lettre.Paragraphe> paragraphes = new ArrayList<>();
        List<List<PhraseLettre>> parties = Arrays.asList(g.vous(), g.moi(), g.nous());
        String[] noms = {"Vous", "Moi", "Nous"};
        for (int i = 0; i < parties.size(); i++) {
            List<Assertion> phrases = new ArrayList<>();
            for (PhraseLettre p : liste(parties.get(i))) {
                if (placement.demande(p.reprise())) {
                    phrases.add(placement.phrase(p.reprise()));
                    continue;
                }
                String texte = net(p.texte());
                if (texte.isEmpty()) {
                    continue;
                }
                switch (net(p.origine()).toLowerCase(Locale.ROOT)) {
                    case "faits" -> phrases.add(new Assertion(texte, new Origine.Faits(ids(p.faits()))));
                    case "offre" -> {
                        if (i == 1) {
                            problemes.add("« " + texte + " » : la partie « Moi » ne parle que du candidat, chaque phrase y "
                                    + "cite ses faits (CA-4.2).");
                        }
                        phrases.add(new Assertion(texte, new Origine.Offre()));
                    }
                    default -> problemes.add("« " + texte + " » : origine « " + p.origine() + " » inconnue ; « faits » ou "
                            + "« offre » attendue.");
                }
            }
            if (phrases.isEmpty()) {
                problemes.add("La partie « " + noms[i] + " » est vide : la lettre suit la structure Vous – Moi – Nous (CA-4.1).");
            }
            paragraphes.add(new Lettre.Paragraphe(phrases));
        }
        Lettre lettre = new Lettre(profil.identite(), destinataire(cible), lieuEtDate(profil.identite(), aujourdhui),
                objet(cible), paragraphes);
        problemes.addAll(placement.problemes());
        problemes.addAll(violations(lettre.assertions(),
                Tracabilite.verifier(lettre.assertions(), profil, cible.lacunes(), cible.texteBrut())));
        int mots = lettre.mots();
        if (mots < regles.motsMin() || mots > regles.motsMax()) {
            problemes.add("La lettre compte " + mots + " mots, hors de la fourchette de " + regles.motsMin() + " à "
                    + regles.motsMax() + " (CA-4.4).");
        }
        if (problemes.isEmpty()) {
            try {
                Rendu.lettre(lettre);
            } catch (ErreurRendu e) {
                problemes.add(e.getMessage());
            }
        }
        return new Tentative<>(lettre, problemes);
    }

    static String destinataire(Cible cible) {
        return cible.entreprise().isBlank() ? "Service du recrutement" : cible.entreprise() + " — Service du recrutement";
    }

    /** « Lyon, le 1er octobre 2026 » : en français, le premier du mois s'écrit en ordinal. */
    static String lieuEtDate(Identite identite, LocalDate jour) {
        String date = (jour.getDayOfMonth() == 1 ? "1er" : String.valueOf(jour.getDayOfMonth())) + " "
                + jour.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRENCH));
        return identite.lieu().isBlank() ? "Le " + date : identite.lieu() + ", le " + date;
    }

    static String objet(Cible cible) {
        return cible.poste().isBlank() ? "Candidature" : "Candidature au poste « " + cible.poste() + " »";
    }

    // --- P7 : l'avis du recruteur ---

    private static Tentative<AvisRecruteur> avis(AvisLu lu) {
        List<AvisRecruteur.NoteCritere> notes = new ArrayList<>();
        for (CritereLu c : liste(lu.criteres())) {
            Optional<Critere> critere = critere(c.critere());
            if (critere.isEmpty()) {
                return Tentative.refusee("Critère « " + c.critere() + " » hors de la grille ; attendus : "
                        + Arrays.stream(Critere.values()).map(Critere::name).collect(Collectors.joining(", ")) + ".");
            }
            try {
                notes.add(new AvisRecruteur.NoteCritere(critere.get(), c.note(), net(c.justification())));
            } catch (IllegalArgumentException e) {
                return Tentative.refusee(e.getMessage());
            }
        }
        try {
            return new Tentative<>(new AvisRecruteur(notes, textes(lu.forces()), textes(lu.faiblesses()), textes(lu.corrections())),
                    List.of());
        } catch (IllegalArgumentException e) {
            return Tentative.refusee(e.getMessage());
        }
    }

    /** « CLARTÉ », « clarte » ou « Clarté » désignent le même critère. */
    private static Optional<Critere> critere(String nom) {
        String cle = Normalizer.normalize(net(nom), Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
        return Arrays.stream(Critere.values())
                .filter(c -> c.name().equals(cle) || Normalizer.normalize(c.libelle(), Normalizer.Form.NFD)
                        .replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT).equals(cle))
                .findFirst();
    }

    // --- la révision ---

    private record Version(
            Cv cv, Lettre lettre, Evaluation evaluation, List<Dossier.Retiree> retirees, List<String> avertissements,
            List<String> correctionsEcartees, String texteCv, String texteLettre) {}

    /** Ce que la passe suivante reçoit : la version précédente telle que relue, et ce qu'on lui reproche. */
    private static String retour(Version v, Cible cible) {
        StringBuilder r = new StringBuilder("\n\nVersion précédente, notée " + v.evaluation().noteGlobale() + "/100, sous le seuil de "
                + v.evaluation().regles().seuil() + ". Révise-la.\n\nCV relu :\n<<<\n" + v.texteCv().strip()
                + "\n>>>\n\nLettre relue :\n<<<\n" + v.texteLettre().strip() + "\n>>>");
        v.evaluation().ats().ifPresent(ats -> {
            List<Exigence> aReprendre = Ats.aReprendre(ats, cible.lacunes());
            if (!aReprendre.isEmpty()) {
                r.append("\n\nCes exigences sont couvertes par des faits, mais leurs mots exacts manquent au CV ; reprends-les "
                        + "tels quels : ").append(aReprendre.stream().map(Exigence::libelle).collect(Collectors.joining(", "))).append('.');
            }
        });
        if (!v.retirees().isEmpty()) {
            r.append("\n\nCes puces n'ont pas tenu sur la page :\n- ")
                    .append(v.retirees().stream().map(x -> x.puce().assertion().texte()).collect(Collectors.joining("\n- ")));
        }
        AvisRecruteur avis = v.evaluation().avis();
        r.append("\n\nAvis du recruteur :\n").append(avis.criteres().stream()
                .map(n -> "- " + n.critere().libelle() + " " + n.note() + "/5 : " + n.justification())
                .collect(Collectors.joining("\n")));
        if (!avis.faiblesses().isEmpty()) {
            r.append("\nFaiblesses :\n- ").append(String.join("\n- ", avis.faiblesses()));
        }
        if (!avis.corrections().isEmpty()) {
            r.append("\nCorrections à appliquer :\n- ").append(String.join("\n- ", avis.corrections()));
        }
        return r.toString();
    }

    private static Dossier dossier(Version v, int passes) {
        List<String> avertissements = new ArrayList<>();
        if (!v.evaluation().seuilAtteint()) {
            avertissements.add("Seuil non atteint après " + passes + " passe(s) : la meilleure version est conservée, notée "
                    + v.evaluation().noteGlobale() + "/100 pour un seuil de " + v.evaluation().regles().seuil() + " (UC-07 A2).");
        }
        avertissements.addAll(v.avertissements());
        return new Dossier(v.cv(), v.lettre(), v.evaluation(),
                new Dossier.Rapport(passes, v.retirees(), avertissements, v.correctionsEcartees()));
    }

    // --- contenu commun ---

    static String offre(Cible cible) {
        OffreAnalysee a = cible.analyse();
        List<String> lignes = new ArrayList<>();
        lignes.add("Offre : " + cible.poste() + (cible.entreprise().isBlank() ? "" : " — " + cible.entreprise()));
        ajouter(lignes, "Lieu", a.lieu());
        ajouter(lignes, "Contrat", a.contrat());
        ajouter(lignes, "Début", a.debut());
        ajouter(lignes, "Durée", a.duree());
        ajouter(lignes, "Niveau", a.niveau());
        if (!a.missions().isEmpty()) {
            lignes.add("Missions :\n- " + String.join("\n- ", a.missions()));
        }
        return String.join("\n", lignes);
    }

    /** Les exigences, et ce qui couvre chacune : le modèle sait d'emblée ce qu'il peut écrire, et ce qu'il doit taire. */
    static String exigences(Cible cible) {
        return cible.rapprochement()
                .map(r -> "Exigences de l'offre et faits du profil qui les couvrent :\n" + r.couvertures().stream()
                        .map(c -> "- [" + (c.exigence().obligatoire() ? "obligatoire" : "souhaitée") + "] " + c.exigence().libelle()
                                + (c.couverte() ? " — couverte par " + String.join(", ", c.faits()) : " — LACUNE : ne l'écris pas"))
                        .collect(Collectors.joining("\n")))
                .orElse("L'offre n'énonce aucune exigence.");
    }

    private static void ajouter(List<String> lignes, String nom, String valeur) {
        if (!valeur.isBlank()) {
            lignes.add(nom + " : " + valeur);
        }
    }

    private static List<String> violations(List<Assertion> assertions, List<Violation> violations) {
        return violations.stream()
                .map(v -> "« " + assertions.get(v.assertion()).texte() + " » — " + v.regle() + " : " + v.message())
                .toList();
    }

    private static <E> List<E> liste(List<E> elements) {
        return elements == null ? List.of() : elements.stream().filter(Objects::nonNull).toList();
    }

    /** Le profil écrit les identifiants entre crochets ; le modèle les reprend parfois avec. */
    private static List<String> ids(List<String> ids) {
        return liste(ids).stream().map(id -> id.strip().replaceAll("^\\[|\\]$", "").strip())
                .filter(id -> !id.isEmpty()).distinct().toList();
    }

    private static List<String> textes(List<String> textes) {
        return liste(textes).stream().map(String::strip).filter(t -> !t.isEmpty()).toList();
    }

    private static String net(String texte) {
        return texte == null ? "" : texte.strip();
    }
}
