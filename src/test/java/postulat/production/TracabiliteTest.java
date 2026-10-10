package postulat.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TracabiliteTest {

    static final Profil PROFIL = Exemples.complet();
    static final Origine STAGE_ET_DASHBOARD = Origine.Faits.de("exp-acme", "exp-acme-dashboard");

    static List<Violation> verifier(Assertion... assertions) {
        return Tracabilite.verifier(List.of(assertions), PROFIL, List.of());
    }

    static List<Violation> verifier(String texte, Origine origine) {
        return verifier(new Assertion(texte, origine));
    }

    static List<String> regles(List<Violation> violations) {
        return violations.stream().map(Violation::regle).toList();
    }

    static List<Exigence> lacunes(String... libelles) {
        return java.util.Arrays.stream(libelles).map(Exigence::obligatoire).toList();
    }

    @Nested
    class Recevables {

        @Test
        void une_assertion_fidele_a_ses_faits_passe() {
            assertThat(verifier("Stage de 6 mois chez Acme : tableau de bord Power BI de 5 KPI pour 12 commerciaux",
                    STAGE_ET_DASHBOARD)).isEmpty();
        }

        @Test
        void un_nombre_peut_s_ecrire_en_lettres_ou_avec_d_autres_separateurs() {
            assertThat(verifier("Tableau de bord de cinq KPI utilisé par douze commerciaux",
                    Origine.Faits.de("exp-acme-dashboard"))).isEmpty();
        }

        @Test
        void l_annee_d_une_periode_justifie_une_date() {
            assertThat(verifier("Stage data chez Acme en 2025", Origine.Faits.de("exp-acme"))).isEmpty();
        }

        @Test
        void une_assertion_de_l_auteur_n_est_jamais_rejetee() {
            List<Violation> v = Tracabilite.verifier(
                    List.of(new Assertion("J'ai formé 40 personnes à Looker", new Origine.Auteur())),
                    PROFIL, lacunes("Looker"));

            assertThat(v).isEmpty();
        }

        @Test
        void un_document_vide_est_recevable() {
            assertThat(Tracabilite.verifier(List.of(), PROFIL, lacunes("Tableau"))).isEmpty();
        }
    }

    @Nested
    class Refusees {

        @Test
        void citer_un_fait_absent_du_profil_est_refuse() {
            List<Violation> v = verifier("Projet personnel en Rust", Origine.Faits.de("proj-rust"));

            assertThat(v).singleElement().satisfies(x -> {
                assertThat(x.regle()).isEqualTo("RG-01");
                assertThat(x.message()).contains("« proj-rust »");
                assertThat(x.assertion()).isZero();
            });
        }

        @Test
        void une_assertion_d_origine_faits_qui_ne_cite_rien_est_refusee() {
            assertThat(regles(verifier("Esprit d'équipe", new Origine.Faits(List.of())))).containsExactly("CA-3.1");
        }

        @Test
        void un_nombre_absent_des_faits_cites_est_refuse() {
            List<Violation> v = verifier("Tableau de bord de 7 KPI", Origine.Faits.de("exp-acme-dashboard"));

            assertThat(v).singleElement().satisfies(x -> {
                assertThat(x.regle()).isEqualTo("RG-03");
                assertThat(x.message()).contains("« 7 »");
            });
        }

        @Test
        void un_nombre_d_un_fait_non_cite_ne_justifie_rien() {
            // « 6 mois » est la durée du stage, mais seule la réalisation est citée.
            assertThat(regles(verifier("Tableau de bord livré en 6 mois", Origine.Faits.de("exp-acme-dashboard"))))
                    .containsExactly("RG-03");
        }

        @Test
        void la_consigne_d_un_fait_ne_justifie_aucun_nombre() {
            // La consigne de comp-sql dit « 2 lignes au plus » : ce n'est pas un fait.
            assertThat(regles(verifier("SQL avancé, 2 bases migrées", Origine.Faits.de("comp-sql"))))
                    .containsExactly("RG-03");
        }

        @Test
        void une_exigence_non_couverte_ne_s_ecrit_jamais() {
            List<Violation> v = Tracabilite.verifier(
                    List.of(new Assertion("Tableau de bord Power BI, puis migration vers Looker", STAGE_ET_DASHBOARD)),
                    PROFIL, lacunes("Looker", "Kubernetes"));

            assertThat(v).singleElement().satisfies(x -> {
                assertThat(x.regle()).isEqualTo("RG-04");
                assertThat(x.message()).contains("« Looker »");
            });
        }

        /**
         * Limite assumée : la mention se cherche sur les mots, pas sur le sens. Une lacune homonyme
         * d'un mot courant — l'outil « Tableau » face à « tableau de bord » — fait refuser la phrase.
         * Le coût est un refus, jamais une invention ; le rapprochement sémantique est la lacune L-01
         * du cahier d'analyse, à trancher avec l'analyse d'offre (itération 6).
         */
        @Test
        void une_lacune_homonyme_d_un_mot_courant_fait_refuser_par_prudence() {
            List<Violation> v = Tracabilite.verifier(
                    List.of(new Assertion("Tableau de bord Power BI", STAGE_ET_DASHBOARD)), PROFIL, lacunes("Tableau"));

            assertThat(regles(v)).containsExactly("RG-04");
        }

        @Test
        void toutes_les_violations_sont_rapportees_avec_leur_assertion() {
            List<Violation> v = Tracabilite.verifier(List.of(
                            new Assertion("Stage chez Acme", Origine.Faits.de("exp-acme")),
                            new Assertion("Tableau de bord de 9 KPI sous Looker", Origine.Faits.de("exp-acme-dashboard", "exp-x"))),
                    PROFIL, lacunes("Looker"));

            assertThat(v).extracting(Violation::assertion, Violation::regle)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(1, "RG-01"),
                            org.assertj.core.groups.Tuple.tuple(1, "RG-03"),
                            org.assertj.core.groups.Tuple.tuple(1, "RG-04"));
        }

        @Test
        void une_assertion_sans_texte_n_existe_pas() {
            assertThatThrownBy(() -> new Assertion("  ", new Origine.Auteur())).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * EN-1 : tests adversariaux. Chacun reproduit une invention typique d'un modèle à qui l'on demande
     * de « coller à l'offre », et vérifie qu'elle est attrapée.
     */
    @Nested
    class Adversaires {

        @Test
        void un_chiffre_gonfle() {
            assertThat(regles(verifier("Tableau de bord utilisé par 15 commerciaux", STAGE_ET_DASHBOARD)))
                    .containsExactly("RG-03");
        }

        @Test
        void un_pourcentage_d_impact_invente() {
            assertThat(regles(verifier("Tableau de bord de 5 KPI qui a réduit le temps de reporting de 30 %",
                    STAGE_ET_DASHBOARD))).containsExactly("RG-03");
        }

        @Test
        void un_nombre_invente_ecrit_en_lettres() {
            assertThat(regles(verifier("Tableau de bord de sept KPI", STAGE_ET_DASHBOARD))).containsExactly("RG-03");
        }

        @Test
        void une_approximation_flatteuse() {
            assertThat(regles(verifier("Tableau de bord utilisé par une vingtaine de commerciaux", STAGE_ET_DASHBOARD)))
                    .containsExactly("RG-03");
        }

        @Test
        void une_duree_allongee() {
            assertThat(regles(verifier("Stage de 8 mois chez Acme", Origine.Faits.de("exp-acme")))).containsExactly("RG-03");
        }

        @Test
        void un_identifiant_presque_juste() {
            assertThat(regles(verifier("Tableau de bord Power BI", Origine.Faits.de("exp-acme-dashbord"))))
                    .containsExactly("RG-01");
        }

        @Test
        void un_outil_de_l_offre_glisse_dans_une_phrase_vraie() {
            List<Violation> v = Tracabilite.verifier(
                    List.of(new Assertion("Tableau de bord Power BI alimenté par dbt et SQL", STAGE_ET_DASHBOARD)),
                    PROFIL, lacunes("dbt"));

            assertThat(regles(v)).containsExactly("RG-04");
        }

        @Test
        void une_lacune_ecrite_autrement() {
            List<Violation> v = Tracabilite.verifier(
                    List.of(new Assertion("Notions de MACHINE-LEARNING appliquées aux ventes", STAGE_ET_DASHBOARD)),
                    PROFIL, lacunes("Machine learning"));

            assertThat(regles(v)).containsExactly("RG-04");
        }
    }
}
