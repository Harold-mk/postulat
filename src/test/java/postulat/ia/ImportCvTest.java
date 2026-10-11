package postulat.ia;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import postulat.ia.ImportCv.CompetenceLue;
import postulat.ia.ImportCv.ExperienceLue;
import postulat.ia.ImportCv.FormationLue;
import postulat.ia.ImportCv.IdentiteLue;
import postulat.ia.ImportCv.ProfilLu;
import postulat.ia.ImportCv.RealisationLue;
import postulat.production.Competence;
import postulat.production.Experience;
import postulat.production.Formation;
import postulat.production.Identite;
import postulat.production.Profil;
import postulat.production.Realisation;

/** UC-02 : un profil pré-rempli d'après un CV, où rien n'est deviné (CA-1.3). */
class ImportCvTest {

    static final String CV = """
            Camille Martin
            camille@exemple.test · Lyon

            EXPÉRIENCES
            Stagiaire data analyst — Acme, Lyon · 2025-01 à 2025-06 (6 mois)
            • Tableau de bord Power BI de 5 KPI, utilisé par 12 commerciaux
            • Contrôles SQL quotidiens
            Assistante — Acme · 2024

            FORMATION
            Master data — Université Lumière · 2024 à 2026

            COMPÉTENCES
            SQL (avancé), Python, Anglais courant
            """;

    static final String A_COMPLETER = Profil.A_COMPLETER;

    static RealisationLue realisation(String tache, String resultat, String... competences) {
        return new RealisationLue("", tache, resultat, List.of(competences));
    }

    static ProfilLu fidele() {
        return new ProfilLu(new IdentiteLue("Camille Martin", "", "camille@exemple.test", "Lyon"),
                List.of(new ExperienceLue("Stagiaire data analyst", "Acme", "2025-01 à 2025-06", "6 mois", "Lyon", List.of(
                                realisation("Tableau de bord Power BI", "5 KPI, utilisé par 12 commerciaux", "Power BI"),
                                realisation("Contrôles SQL quotidiens", "", "SQL"))),
                        new ExperienceLue("Assistante", "Acme", "2024", "", "", null)),
                List.of(new FormationLue("Master data", "Université Lumière", "2024 à 2026", "", List.of())),
                List.of(new CompetenceLue("SQL", "avancé"), new CompetenceLue("Python", null), new CompetenceLue("Anglais", "courant")));
    }

    @Test
    void ce_que_dit_le_cv_est_range_avec_des_identifiants_du_code_et_le_reste_a_completer() {
        FauxModele faux = new FauxModele().repondre(fidele());

        ImportCv.Import i = ImportCv.importer(faux, CV);

        Profil p = i.profil();
        assertThat(p.identite()).isEqualTo(new Identite("Camille Martin", A_COMPLETER, "camille@exemple.test", "Lyon"));
        assertThat(p.experiences()).containsExactly(
                new Experience("exp-acme", "Stagiaire data analyst", "Acme", "2025-01 à 2025-06", "6 mois", "Lyon", "", List.of(
                        new Realisation("exp-acme-r1", A_COMPLETER, "Tableau de bord Power BI", "5 KPI, utilisé par 12 commerciaux",
                                List.of("Power BI"), ""),
                        new Realisation("exp-acme-r2", A_COMPLETER, "Contrôles SQL quotidiens", "", List.of("SQL"), ""))),
                new Experience("exp-acme-2", "Assistante", "Acme", "2024", "", "", "", List.of()));
        assertThat(p.formations()).containsExactly(
                new Formation("form-master-data", "Master data", "Université Lumière", "2024 à 2026", "", "", List.of()));
        assertThat(p.competences()).containsExactly(new Competence("comp-sql", "SQL", "avancé", ""),
                new Competence("comp-python", "Python", "", ""), new Competence("comp-anglais", "Anglais", "courant", ""));
        assertThat(i.remplacements()).isEmpty();
        assertThat(i.consommation()).isEqualTo(FauxModele.CONSOMMATION);
        assertThat(p.anomalies()).extracting(a -> a.chemin())
                .containsExactly("identite.titre", "experiences[0].realisations[0].objectif", "experiences[0].realisations[1].objectif");
    }

    @Test
    void c_est_une_extraction_economique_hors_candidature_et_le_cv_en_est_le_contenu() {
        FauxModele faux = new FauxModele().repondre(fidele());

        ImportCv.importer(faux, CV);

        Demande d = faux.demandes().get(0);
        assertThat(d.tache()).isEqualTo("import-cv");
        assertThat(d.gamme()).isEqualTo(Gamme.ECONOMIQUE);
        assertThat(d.candidature()).isEmpty();
        assertThat(d.consignes()).contains("tu n'écris que ce que le CV dit").contains("ne la calcule pas à partir des dates");
        assertThat(d.contenu()).startsWith("Texte du CV :\n<<<\nCamille Martin").endsWith("Anglais courant\n>>>");
    }

    /** CA-1.3 : un nombre absent du CV, un nom qu'on n'y lit pas, ne passent pas ; l'auteur saura pourquoi. */
    @Test
    void ce_qui_ne_se_lit_pas_dans_le_cv_devient_a_completer_et_se_signale() {
        ProfilLu invente = new ProfilLu(new IdentiteLue("Camille Dupont", "Data analyst confirmée", "camille@exemple.test", ""),
                List.of(new ExperienceLue("Stagiaire data analyst", "Globex", "2025", "8 mois", "", List.of(
                                realisation("Tableau de bord Power BI", "15 commerciaux formés", "Power BI", "Docker"))),
                        new ExperienceLue("Application de suivi des dépenses", "Projet personnel", "2023", "", "", List.of())),
                List.of(), List.of(new CompetenceLue("Kubernetes", "")));

        ImportCv.Import i = ImportCv.importer(new FauxModele().repondre(invente), CV);

        Profil p = i.profil();
        assertThat(p.identite().nom()).isEqualTo(A_COMPLETER);
        assertThat(p.identite().titre()).isEqualTo("Data analyst confirmée");
        Experience globex = p.experiences().get(0);
        assertThat(globex.id()).isEqualTo("exp-stagiaire-data-analyst");
        assertThat(globex.organisation()).isEqualTo(A_COMPLETER);
        assertThat(globex.duree()).isEqualTo(A_COMPLETER);
        assertThat(globex.realisations().get(0).resultat()).isEqualTo(A_COMPLETER);
        assertThat(globex.realisations().get(0).competences()).containsExactly("Power BI");
        assertThat(p.experiences().get(1).organisation()).isEqualTo("Projet personnel");
        assertThat(p.competences()).containsExactly(new Competence("comp-1", A_COMPLETER, "", ""));
        assertThat(i.remplacements()).containsExactly(
                "identite.nom : « Camille Dupont » ne se lit pas dans le CV.",
                "expérience « Stagiaire data analyst », organisation : « Globex » ne se lit pas dans le CV.",
                "expérience exp-stagiaire-data-analyst, durée : « 8 mois » contenait 8, absent(s) du CV.",
                "réalisation exp-stagiaire-data-analyst-r1, résultat : « 15 commerciaux formés » contenait 15, absent(s) du CV.",
                "réalisation exp-stagiaire-data-analyst-r1, compétence : « Docker » ne se lit pas dans le CV ; retirée.",
                "expérience exp-projet-personnel, période : « 2023 » contenait 2023, absent(s) du CV.",
                "compétence : « Kubernetes » ne se lit pas dans le CV.");
    }

    @Test
    void une_reponse_vide_donne_un_profil_entierement_a_completer() {
        ImportCv.Import i = ImportCv.importer(new FauxModele().repondre(new ProfilLu(null, null, null, null)), CV);

        assertThat(i.profil().identite()).isEqualTo(new Identite(A_COMPLETER, A_COMPLETER, A_COMPLETER, ""));
        assertThat(i.profil().faits()).isEmpty();
    }

    @Test
    void la_forme_imposee_passe_par_le_sdk() throws IOException {
        try (FausseApi api = new FausseApi()) {
            api.repondre(200, FausseApi.message("claude-haiku-5-5", """
                    {"identite":{"nom":"Camille Martin","titre":"","contact":"camille@exemple.test","lieu":"Lyon"},
                     "experiences":[{"poste":"Stagiaire","organisation":"Acme","periode":"2025","duree":"","lieu":"",
                       "realisations":[{"objectif":"","tache":"Contrôles SQL quotidiens","resultat":"","competences":["SQL"]}]}],
                     "formations":[],"competences":[{"nom":"SQL","niveau":""}]}""", "end_turn", 900, 300, 0, 0));
            ModeleAnthropic modele = new ModeleAnthropic(api.client(), "claude-haiku-5-5", "claude-opus-5-5");

            ImportCv.Import i = ImportCv.importer(modele, CV);

            assertThat(api.derniere().corps()).contains("json_schema", "realisations", "etablissement", "claude-haiku-5-5");
            assertThat(i.profil().realisationsDe("exp-acme")).extracting(Realisation::tache).containsExactly("Contrôles SQL quotidiens");
            assertThat(Optional.of(i.profil().identite().lieu())).contains("Lyon");
        }
    }
}
