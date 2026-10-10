package postulat.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static postulat.production.Exemples.complet;
import static postulat.production.Exemples.dashboard;
import static postulat.production.Exemples.identite;
import static postulat.production.Exemples.master;
import static postulat.production.Exemples.sql;
import static postulat.production.Exemples.stage;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ProfilTest {

    @Nested
    class Faits {

        @Test
        void les_faits_sont_listes_realisations_comprises_dans_l_ordre_du_profil() {
            assertThat(complet().faits()).extracting(Fait::id)
                    .containsExactly("exp-acme", "exp-acme-dashboard", "form-master", "comp-sql");
        }

        @Test
        void un_fait_se_retrouve_par_son_identifiant() {
            assertThat(complet().fait("exp-acme-dashboard")).contains(dashboard());
            assertThat(complet().fait("exp-inconnue")).isEmpty();
        }

        @Test
        void la_consigne_de_presentation_est_conservee_mais_n_est_pas_un_fait() {
            Fait f = complet().fait("comp-sql").orElseThrow();
            assertThat(f.consigne()).isEqualTo("toujours citer avec un exemple, 2 lignes au plus");
            assertThat(f.texte()).doesNotContain("exemple");
        }

        @Test
        void le_texte_d_un_fait_reunit_tous_ses_champs() {
            assertThat(stage().texte()).contains("Stagiaire data analyst", "Acme", "2025-01 à 2025-06", "6 mois", "Lyon");
            assertThat(dashboard().texte()).contains("Suivre", "Concevoir", "5 KPI", "Power BI, SQL");
            assertThat(master().texte()).contains("Master data science", "Université Lumière", "spécialité");
            assertThat(sql().texte()).contains("SQL", "avancé");
        }

        @Test
        void un_profil_ne_se_modifie_pas_de_l_exterieur() {
            assertThatThrownBy(() -> complet().experiences().add(stage()))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> dashboard().competences().add("Excel"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class Anomalies {

        @Test
        void un_profil_complet_n_en_a_aucune() {
            assertThat(complet().anomalies()).isEmpty();
            assertThat(complet().estComplet()).isTrue();
        }

        @Test
        void un_champ_obligatoire_vide_est_signale_avec_son_chemin() {
            Profil p = new Profil(new Identite("", "Étudiante", "camille@exemple.test", ""),
                    List.of(stage()), List.of(), List.of());

            assertThat(p.anomalies()).containsExactly(new Anomalie("identite.nom", "obligatoire"));
            assertThat(p.estComplet()).isFalse();
        }

        @Test
        void a_completer_est_signale_meme_dans_un_champ_facultatif() {
            Profil p = new Profil(new Identite("Camille", "Étudiante", "c@exemple.test", "À compléter…"),
                    List.of(stage()), List.of(), List.of());

            assertThat(p.anomalies()).containsExactly(new Anomalie("identite.lieu", "à compléter"));
        }

        @Test
        void tous_les_manques_sont_dits_d_un_coup_chacun_a_sa_place() {
            Realisation incomplete = new Realisation("exp-1-r1", Profil.A_COMPLETER, "", "", List.of(), "");
            Experience exp = new Experience("exp-1", Profil.A_COMPLETER, "Acme", "", "", "", "", List.of(incomplete));
            Formation form = new Formation("form-1", "Master", "", "2024", "", "", List.of());
            Competence comp = new Competence("comp-1", "", "", "");
            Profil p = new Profil(identite(), List.of(exp), List.of(form), List.of(comp));

            assertThat(p.anomalies()).containsExactly(
                    new Anomalie("experiences[0].poste", "à compléter"),
                    new Anomalie("experiences[0].periode", "obligatoire"),
                    new Anomalie("experiences[0].realisations[0].objectif", "à compléter"),
                    new Anomalie("experiences[0].realisations[0].tache", "obligatoire"),
                    new Anomalie("formations[0].etablissement", "obligatoire"),
                    new Anomalie("competences[0].nom", "obligatoire"));
        }

        @Test
        void le_resultat_d_une_realisation_est_facultatif() {
            Realisation sansResultat = new Realisation("r1", "Objectif", "Tâche", "", List.of(), "");
            Experience exp = new Experience("exp-1", "Poste", "Org", "2025", "", "", "", List.of(sansResultat));
            Profil p = new Profil(identite(), List.of(exp), List.of(), List.of(sql()));

            assertThat(p.anomalies()).isEmpty();
        }

        @Test
        void deux_faits_ne_partagent_jamais_un_identifiant() {
            Competence doublon = new Competence("exp-acme-dashboard", "Excel", "", "");
            Profil p = new Profil(identite(), List.of(stage()), List.of(), List.of(sql(), doublon));

            assertThat(p.anomalies()).singleElement().satisfies(a -> {
                assertThat(a.chemin()).isEqualTo("competences[1].id");
                assertThat(a.message()).contains("déjà utilisé par experiences[0].realisations[0]").contains("RG-25");
            });
        }

        @Test
        void un_identifiant_s_ecrit_en_minuscules_chiffres_et_tirets() {
            Profil p = new Profil(identite(), List.of(), List.of(), List.of(new Competence("Comp SQL", "SQL", "", "")));

            assertThat(p.anomalies()).singleElement().satisfies(a -> {
                assertThat(a.chemin()).isEqualTo("competences[0].id");
                assertThat(a.message()).contains("invalide");
            });
        }

        @Test
        void un_identifiant_vide_ou_a_completer_est_un_manque() {
            Profil p = new Profil(identite(), List.of(), List.of(),
                    List.of(new Competence("", "SQL", "", ""), new Competence(Profil.A_COMPLETER, "R", "", "")));

            assertThat(p.anomalies()).containsExactly(
                    new Anomalie("competences[0].id", "obligatoire"),
                    new Anomalie("competences[1].id", "à compléter"));
        }

        @Test
        void un_profil_sans_aucun_fait_est_inutilisable() {
            Profil p = new Profil(identite(), List.of(), List.of(), List.of());

            assertThat(p.anomalies()).singleElement().extracting(Anomalie::chemin).isEqualTo("");
        }

        @Test
        void les_realisations_d_une_formation_sont_controlees_aussi() {
            Realisation projet = new Realisation("form-projet", "", "Tâche", "", List.of(), "");
            Profil p = new Profil(identite(), List.of(), List.of(new Formation("form-1", "M", "U", "2024", "", "", List.of(projet))), List.of());

            assertThat(p.anomalies()).containsExactly(new Anomalie("formations[0].realisations[0].objectif", "obligatoire"));
            assertThat(p.fait("form-projet")).isPresent();
        }

        @Test
        void a_completer_se_reconnait_malgre_casse_accents_et_ponctuation() {
            assertThat(Profil.estACompleter("à compléter")).isTrue();
            assertThat(Profil.estACompleter("  A COMPLETER. ")).isTrue();
            assertThat(Profil.estACompleter("À  compléter…")).isTrue();
            assertThat(Profil.estACompleter("compléter le tableau")).isFalse();
        }
    }
}
