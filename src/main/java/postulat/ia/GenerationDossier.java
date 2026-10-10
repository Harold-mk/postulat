package postulat.ia;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
            @JsonPropertyDescription("Rang de pertinence pour l'offre, unique dans le CV : 1 pour la plus pertinente") int rang) {}

    public record PhraseGeneree(
            @JsonPropertyDescription("Le texte") String texte,
            @JsonPropertyDescription("Identifiants des faits dont il dérive") List<String> faits) {}

    /** P6. */
    public record LettreGeneree(
            @JsonPropertyDescription("Vous : l'entreprise et le poste, d'après l'offre") List<PhraseLettre> vous,
            @JsonPropertyDescription("Moi : ce que le candidat apporte, prouvé par ses faits") List<PhraseLettre> moi,
            @JsonPropertyDescription("Nous : ce que nous ferions ensemble, et la demande d'entretien") List<PhraseLettre> nous) {}

    public record PhraseLettre(
            @JsonPropertyDescription("Une phrase") String texte,
            @JsonPropertyDescription("« faits » si elle s'appuie sur le profil, « offre » si elle ne dit que ce que l'offre dit")
                    String origine,
            @JsonPropertyDescription("Identifiants des faits dont elle dérive ; vide pour l'origine « offre »") List<String> faits) {}

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
     * @param aujourdhui la date de la lettre : le domaine ne lit pas l'horloge
     * @param progression reçoit une ligne à chaque étape : la génération prend du temps
     * @throws ErreurModele si le modèle est indisponible, ou si un document reste refusé après une reprise
     * @throws IllegalArgumentException si le profil est incomplet : refusé avant tout appel (UC-07 E1)
     */
    public static Dossier generer(Modele modele, Profil profil, Cible cible, ReglesGeneration regles, LocalDate aujourdhui,
            Consumer<String> progression) {
        if (!profil.estComplet()) {
            throw new IllegalArgumentException("Le profil est incomplet : la génération est refusée avant tout appel au "
                    + "modèle (UC-07 E1).");
        }
        String profilLu = "\n\nProfil du candidat :\n" + TexteProfil.de(profil, true);
        Version meilleure = null;
        String retour = "";
        int passes = 0;
        while (passes <= regles.revisionsMax()) {
            passes++;
            String passe = passes == 1 ? "Première passe" : "Révision " + (passes - 1);

            progression.accept(passe + " : CV ciblé…");
            Demande demandeCv = new Demande("cv-cible", Gamme.CAPABLE, CONSIGNES_CV + profilLu,
                    offre(cible) + "\n\n" + exigences(cible) + retour, Optional.of(cible.candidature()));
            Cv cv = produire(modele, demandeCv, CvGenere.class, g -> cv(g, profil, cible), "le CV");
            Rendu.Resultat cvRendu = Rendu.cv(cv);
            for (Cv.Puce retiree : cvRendu.retirees()) {
                cv = cv.sans(retiree);
            }

            progression.accept(passe + " : lettre…");
            Demande demandeLettre = new Demande("lettre", Gamme.CAPABLE,
                    CONSIGNES_LETTRE + "\nLongueur : entre " + regles.motsMin() + " et " + regles.motsMax()
                            + " mots pour les trois paragraphes ensemble." + profilLu,
                    offre(cible) + "\n\nTexte de l'offre :\n<<<\n" + cible.texteBrut().strip() + "\n>>>\n\n" + exigences(cible) + retour,
                    Optional.of(cible.candidature()));
            Lettre lettre = produire(modele, demandeLettre, LettreGeneree.class,
                    g -> lettre(g, profil, cible, regles, aujourdhui), "la lettre");
            Rendu.Resultat lettreRendue = Rendu.lettre(lettre);

            Optional<NoteAts> ats = Ats.noter(cvRendu.texte(), cible.analyse().exigences());
            progression.accept(passe + " : avis du recruteur…");
            Demande demandeAvis = new Demande("avis-recruteur", Gamme.CAPABLE, CONSIGNES_AVIS + profilLu,
                    offre(cible) + "\n\n" + exigences(cible) + "\n\nCV :\n<<<\n" + cvRendu.texte().strip() + "\n>>>\n\nLettre :\n<<<\n"
                            + lettreRendue.texte().strip() + "\n>>>",
                    Optional.of(cible.candidature()));
            AvisRecruteur avisComplet = produire(modele, demandeAvis, AvisLu.class, GenerationDossier::avis, "l'avis du recruteur");
            AvisRecruteur avis = avisComplet.sansCorrectionsSur(cible.lacunes());
            Evaluation evaluation = new Evaluation(ats, avis, regles);

            List<String> avertissements = new ArrayList<>(cvRendu.avertissements().stream().map(a -> "CV : " + a).toList());
            lettreRendue.avertissements().forEach(a -> avertissements.add("Lettre : " + a));
            Version v = new Version(cv, lettre, evaluation, cvRendu.retirees(), avertissements,
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

    private static Tentative<Cv> cv(CvGenere g, Profil profil, Cible cible) {
        Cv cv;
        try {
            List<Cv.Section> sections = new ArrayList<>();
            for (SectionGeneree s : liste(g.sections())) {
                List<Cv.Contenu> contenus = new ArrayList<>();
                for (BlocGenere b : liste(s.blocs())) {
                    List<Cv.Puce> puces = liste(b.puces()).stream()
                            .map(p -> new Cv.Puce(new Assertion(net(p.texte()), new Origine.Faits(ids(p.faits()))), p.rang()))
                            .toList();
                    Optional<Assertion> precision = b.precision() == null || net(b.precision().texte()).isEmpty()
                            ? Optional.empty()
                            : Optional.of(assertion(b.precision()));
                    contenus.add(new Cv.Bloc(assertion(b.intitule()), precision, puces));
                }
                liste(s.lignes()).forEach(l -> contenus.add(new Cv.Ligne(assertion(l))));
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
        return new Tentative<>(cv, violations(cv.assertions(), Tracabilite.verifier(cv.assertions(), profil, cible.lacunes())));
    }

    private static Assertion assertion(PhraseGeneree p) {
        if (p == null) {
            throw new IllegalArgumentException("un intitulé manque.");
        }
        return new Assertion(net(p.texte()), new Origine.Faits(ids(p.faits())));
    }

    // --- P6 : la lettre ---

    private static Tentative<Lettre> lettre(LettreGeneree g, Profil profil, Cible cible, ReglesGeneration regles, LocalDate aujourdhui) {
        List<String> problemes = new ArrayList<>();
        List<Lettre.Paragraphe> paragraphes = new ArrayList<>();
        List<List<PhraseLettre>> parties = Arrays.asList(g.vous(), g.moi(), g.nous());
        String[] noms = {"Vous", "Moi", "Nous"};
        for (int i = 0; i < parties.size(); i++) {
            List<Assertion> phrases = new ArrayList<>();
            for (PhraseLettre p : liste(parties.get(i))) {
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
            Cv cv, Lettre lettre, Evaluation evaluation, List<Cv.Puce> retirees, List<String> avertissements,
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
                    .append(v.retirees().stream().map(p -> p.assertion().texte()).collect(Collectors.joining("\n- ")));
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
