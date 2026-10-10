package postulat.ia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static postulat.production.Exigence.obligatoire;
import static postulat.production.Exigence.souhaitee;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import postulat.ia.AnalyseOffre.ExigenceLue;
import postulat.ia.AnalyseOffre.FicheOffre;
import postulat.ia.ErreurModele.Nature;
import postulat.ia.RapprochementOffre.CouvertureLue;
import postulat.ia.RapprochementOffre.RapprochementLu;
import postulat.production.Competence;
import postulat.production.Exigence;
import postulat.production.Experience;
import postulat.production.Formation;
import postulat.production.Identite;
import postulat.production.OffreAnalysee;
import postulat.production.Profil;
import postulat.production.Rapprochement;
import postulat.production.Realisation;

class AnalyseOffreTest {

    static final String TEXTE = "Acme recrute un stagiaire data analyst à Lyon pour six mois. Maîtrise de SQL exigée ; "
            + "une connaissance de Tableau serait un plus.";

    static FicheOffre fiche(ExigenceLue... exigences) {
        return new FicheOffre(" Stagiaire data analyst ", "Acme", "Lyon", "stage", "", "6 mois", "",
                List.of("Construire des tableaux de bord", " "), Arrays.asList(exigences));
    }

    @Nested
    class Lecture {

        @Test
        void la_fiche_du_modele_devient_une_offre_analysee_nettoyee() {
            FauxModele faux = new FauxModele().repondre(fiche(new ExigenceLue("SQL", true), new ExigenceLue("Tableau", false),
                    new ExigenceLue(" ", true), null));

            OffreAnalysee o = AnalyseOffre.analyser(faux, TEXTE, Optional.empty());

            assertThat(o.poste()).isEqualTo("Stagiaire data analyst");
            assertThat(o.debut()).isEmpty();
            assertThat(o.missions()).containsExactly("Construire des tableaux de bord");
            assertThat(o.exigences()).containsExactly(obligatoire("SQL"), souhaitee("Tableau"));
        }

        @Test
        void c_est_une_tache_economique_dont_le_texte_brut_est_le_contenu() {
            FauxModele faux = new FauxModele().repondre(fiche());
            UUID candidature = UUID.randomUUID();

            AnalyseOffre.analyser(faux, TEXTE, Optional.of(candidature));

            Demande d = faux.demandes().get(0);
            assertThat(d.gamme()).isEqualTo(Gamme.ECONOMIQUE);
            assertThat(d.tache()).isEqualTo("analyse-offre");
            assertThat(d.consignes()).contains("ne la devine jamais").contains("mots exacts de l'offre");
            assertThat(d.contenu()).contains(TEXTE);
            assertThat(d.candidature()).contains(candidature);
        }

        @Test
        void des_listes_absentes_donnent_une_offre_sans_mission_ni_exigence() {
            FauxModele faux = new FauxModele().repondre(new FicheOffre(null, null, "", "", "", "", "", null, null));

            OffreAnalysee o = AnalyseOffre.analyser(faux, TEXTE, Optional.empty());

            assertThat(o.poste()).isEmpty();
            assertThat(o.missions()).isEmpty();
            assertThat(o.exigences()).isEmpty();
        }

        /** De bout en bout par le SDK : la forme imposée a un schéma accepté, et la réponse JSON s'y lit. */
        @Test
        void la_forme_imposee_passe_par_le_sdk_et_se_relit() throws IOException {
            try (FausseApi api = new FausseApi()) {
                api.repondre(200, FausseApi.message("claude-haiku-5-5", """
                        {"poste":"Stagiaire data","entreprise":"Acme","lieu":"Lyon","contrat":"stage","debut":"","duree":"6 mois",
                         "niveau":"bac+5","missions":["Tableaux de bord"],"exigences":[{"libelle":"SQL","obligatoire":true}]}""",
                        "end_turn", 900, 120, 0, 0));
                ModeleAnthropic modele = new ModeleAnthropic(api.client(), "claude-haiku-5-5", "claude-opus-5-5");

                OffreAnalysee o = AnalyseOffre.analyser(modele, TEXTE, Optional.empty());

                assertThat(o.exigences()).containsExactly(obligatoire("SQL"));
                assertThat(api.derniere().corps()).contains("json_schema", "exigences", "obligatoire",
                        "mots exacts de l'offre");
            }
        }
    }

    @Nested
    class Rapprochements {

        final Profil profil = new Profil(
                new Identite("Camille Martin", "Étudiante", "camille@exemple.test", ""),
                List.of(new Experience("exp-acme", "Stagiaire data analyst", "Acme", "2025-01 à 2025-06", "6 mois", "Lyon", "",
                        List.of(new Realisation("exp-acme-r1", "Suivre les ventes", "Tableau de bord Power BI", "5 KPI",
                                List.of("Power BI", "SQL"), "")))),
                List.of(new Formation("form-master", "Master data", "Université Lumière", "2024 à 2026", "", "", List.of())),
                List.of(new Competence("comp-sql", "SQL", "avancé", "")));
        final List<Exigence> exigences = List.of(obligatoire("SQL"), souhaitee("Tableau"));
        final UUID candidature = UUID.randomUUID();

        Rapprochement rapprocher(RapprochementLu lu) {
            return RapprochementOffre.rapprocher(new FauxModele().repondre(lu), profil, exigences, candidature);
        }

        ErreurModele refus(RapprochementLu lu) {
            try {
                rapprocher(lu);
            } catch (ErreurModele e) {
                return e;
            }
            throw new AssertionError("un refus était attendu");
        }

        @Test
        void chaque_exigence_recoit_ses_faits_et_sa_justification_dans_l_ordre_de_l_offre() {
            Rapprochement r = rapprocher(new RapprochementLu(List.of(
                    new CouvertureLue(2, List.of(), "Seul Power BI est pratiqué."),
                    new CouvertureLue(1, List.of(" comp-sql ", "exp-acme-r1", "comp-sql"), "SQL avancé.")), 72, " Bonne base. "));

            assertThat(r.couvertures()).extracting(c -> c.exigence().libelle()).containsExactly("SQL", "Tableau");
            assertThat(r.couvertures().get(0).faits()).containsExactly("comp-sql", "exp-acme-r1");
            assertThat(r.lacunes()).containsExactly(souhaitee("Tableau"));
            assertThat(r.note()).isEqualTo(72);
            assertThat(r.justification()).isEqualTo("Bonne base.");
        }

        @Test
        void c_est_une_tache_capable_dont_le_profil_est_la_partie_stable() {
            FauxModele faux = new FauxModele().repondre(new RapprochementLu(List.of(
                    new CouvertureLue(1, List.of("comp-sql"), ""), new CouvertureLue(2, List.of(), "")), 60, ""));

            RapprochementOffre.rapprocher(faux, profil, exigences, candidature);

            Demande d = faux.demandes().get(0);
            assertThat(d.gamme()).isEqualTo(Gamme.CAPABLE);
            assertThat(d.candidature()).contains(candidature);
            assertThat(d.consignes()).contains("« Tableau » n'est pas un « tableau de bord »")
                    .contains("[exp-acme] Expérience : Stagiaire data analyst — Acme — 2025-01 à 2025-06 — 6 mois — Lyon")
                    .contains("[exp-acme-r1] Réalisation (dans exp-acme) : objectif : Suivre les ventes ; tâche : Tableau de bord "
                            + "Power BI ; résultat : 5 KPI ; compétences : Power BI, SQL")
                    .contains("[form-master] Formation : Master data — Université Lumière — 2024 à 2026")
                    .contains("[comp-sql] Compétence : SQL — avancé");
            assertThat(d.contenu()).isEqualTo("Exigences de l'offre :\n1. [obligatoire] SQL\n2. [souhaitée] Tableau");
        }

        @Test
        void citer_un_fait_absent_du_profil_fait_refuser_tout_le_rapprochement() {
            ErreurModele e = refus(new RapprochementLu(List.of(
                    new CouvertureLue(1, List.of("comp-sql"), ""), new CouvertureLue(2, List.of("comp-tableau"), "")), 80, ""));

            assertThat(e.nature()).isEqualTo(Nature.INVALIDE);
            assertThat(e.getMessage()).contains("RG-01").contains("comp-tableau").contains("rien n'a été enregistré");
        }

        @Test
        void une_exigence_oubliee_ou_traitee_deux_fois_fait_refuser() {
            assertThat(refus(new RapprochementLu(List.of(new CouvertureLue(1, List.of(), "")), 50, "")).getMessage())
                    .contains("exigence n° 2");
            assertThat(refus(new RapprochementLu(List.of(new CouvertureLue(1, List.of(), ""), new CouvertureLue(1, List.of(), ""),
                    new CouvertureLue(2, List.of(), "")), 50, "")).getMessage()).contains("2 fois");
            assertThat(refus(new RapprochementLu(List.of(new CouvertureLue(1, List.of(), ""), new CouvertureLue(2, List.of(), ""),
                    new CouvertureLue(3, List.of(), "")), 50, "")).getMessage()).contains("3 exigences");
        }

        @Test
        void une_note_hors_bornes_fait_refuser() {
            assertThat(refus(new RapprochementLu(List.of(new CouvertureLue(1, List.of(), null),
                    new CouvertureLue(2, null, null)), 140, null)).getMessage()).contains("140");
        }

        @Test
        void sans_exigence_il_n_y_a_rien_a_rapprocher() {
            assertThatThrownBy(() -> RapprochementOffre.rapprocher(new FauxModele(), profil, List.of(), candidature))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
