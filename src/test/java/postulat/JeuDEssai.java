package postulat;

import java.util.Arrays;
import java.util.List;
import postulat.ia.AnalyseOffre.ExigenceLue;
import postulat.ia.AnalyseOffre.FicheOffre;
import postulat.ia.GenerationDossier.AvisLu;
import postulat.ia.GenerationDossier.BlocGenere;
import postulat.ia.GenerationDossier.CritereLu;
import postulat.ia.GenerationDossier.CvGenere;
import postulat.ia.GenerationDossier.LettreGeneree;
import postulat.ia.GenerationDossier.PhraseGeneree;
import postulat.ia.GenerationDossier.PhraseLettre;
import postulat.ia.GenerationDossier.PuceGeneree;
import postulat.ia.GenerationDossier.SectionGeneree;
import postulat.ia.RapprochementOffre.CouvertureLue;
import postulat.ia.RapprochementOffre.RapprochementLu;
import postulat.production.Critere;

/**
 * Une candidate fictive, une offre fictive, et ce qu'un faux modèle en dirait : de quoi conduire une
 * candidature de l'offre au dossier, en ligne de commande comme dans la fenêtre.
 */
public final class JeuDEssai {

    private JeuDEssai() {}

    public static final String PROFIL = """
            identite: {nom: Camille Martin, titre: Étudiante en master data, contact: camille@exemple.test}
            experiences:
              - id: exp-acme
                poste: Stagiaire data analyst
                organisation: Acme
                periode: 2025-01 à 2025-06
                realisations:
                  - {id: exp-acme-r1, objectif: Suivre les ventes, tache: Tableau de bord Power BI}
                  - {id: exp-acme-r2, objectif: Fiabiliser les données, tache: Contrôles SQL quotidiens}
            formations:
              - {id: form-master, diplome: Master data, etablissement: Université Lumière, periode: 2024 à 2026}
            competences:
              - {id: comp-sql, nom: SQL}
            """;

    public static final String OFFRE = "Acme, éditeur lyonnais de logiciels de gestion, recrute un stagiaire data analyst pour six "
            + "mois à partir de mars 2027. Missions : construire des tableaux de bord et fiabiliser les données. "
            + "Profil : maîtrise de SQL exigée ; une connaissance de Tableau serait un plus.";

    /** L'analyse de {@link #OFFRE}. */
    public static final FicheOffre FICHE = new FicheOffre("Stagiaire data analyst", "Acme", "Lyon", "stage", "mars 2027", "6 mois", "",
            List.of("Construire des tableaux de bord", "Fiabiliser les données"),
            List.of(new ExigenceLue("SQL", true), new ExigenceLue("Tableau", false)));

    /** Son rapprochement avec {@link #PROFIL} : SQL couvert, Tableau en lacune. */
    public static final RapprochementLu RAPPROCHEMENT = new RapprochementLu(List.of(
            new CouvertureLue(1, List.of("comp-sql", "exp-acme-r1"), "SQL avancé."),
            new CouvertureLue(2, List.of(), "Seul Power BI est pratiqué.")), 72, "Bonne base.");

    public static CvGenere cv(String puce) {
        return new CvGenere(List.of(
                new SectionGeneree("Expériences", List.of(new BlocGenere(new PhraseGeneree("Stagiaire data analyst — Acme",
                        List.of("exp-acme"), ""), new PhraseGeneree("2025", List.of("exp-acme"), ""),
                        List.of(new PuceGeneree(puce, List.of("exp-acme-r1"), 1, ""),
                                new PuceGeneree("Mis en place des contrôles SQL quotidiens", List.of("exp-acme-r2"), 2, "")))), List.of()),
                new SectionGeneree("Compétences", List.of(), List.of(new PhraseGeneree("SQL", List.of("comp-sql"), "")))));
    }

    public static final CvGenere CV = cv("Suivi des ventes sous Power BI");

    public static final LettreGeneree LETTRE = new LettreGeneree(
            List.of(new PhraseLettre("Acme, éditeur lyonnais de logiciels de gestion, cherche un stagiaire data analyst pour six "
                    + "mois à partir de mars 2027.", "offre", List.of(), "")),
            List.of(new PhraseLettre("Lors de mon stage chez Acme, j'ai suivi les ventes sous Power BI et mis en place des "
                    + "contrôles SQL quotidiens.", "faits", List.of("exp-acme-r1", "exp-acme-r2"), ""),
                    new PhraseLettre("Mon master data à l'Université Lumière complète cette pratique.", "faits",
                            List.of("form-master"), "")),
            List.of(new PhraseLettre("Fiabiliser vos données avec vous serait pour moi une suite naturelle, et j'aimerais en "
                    + "parler lors d'un entretien.", "faits", List.of("exp-acme-r2"), "")));

    /** L'avis du recruteur, chaque critère noté {@code note} sur 5. */
    public static AvisLu avis(int note) {
        return new AvisLu(Arrays.stream(Critere.values()).map(c -> new CritereLu(c.name(), note, "Solide.")).toList(),
                List.of("Preuves précises"), List.of(), List.of("Citez Tableau", "Resserrez l'objet"));
    }
}
