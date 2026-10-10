package postulat.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static postulat.production.Exigence.obligatoire;
import static postulat.production.Exigence.souhaitee;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AtsTest {

    static final String CV = """
            Camille Martin — Étudiante en master data
            Expérience
            Stagiaire data analyst, Acme, 2025
            Tableau de bord Power BI de 5 KPI, alimenté par des requêtes SQL
            Compétences
            SQL, Python, Power BI, anglais courant
            """;

    static NoteAts noter(String texte, Exigence... exigences) {
        return Ats.noter(texte, List.of(exigences)).orElseThrow();
    }

    @Nested
    class Calcul {

        @Test
        void toutes_les_exigences_retrouvees_donnent_cent() {
            NoteAts n = noter(CV, obligatoire("SQL"), obligatoire("Power BI"), souhaitee("Python"));

            assertThat(n.note()).isEqualTo(100);
            assertThat(n.absentes()).isEmpty();
            assertThat(n.lisible()).isTrue();
        }

        @Test
        void une_obligatoire_pese_deux_fois_une_souhaitee() {
            // retrouvé : SQL (2) + Python (1) + anglais (1) = 4 sur 2 + 2 + 1 + 1 = 6
            NoteAts n = noter(CV, obligatoire("SQL"), obligatoire("dbt"), souhaitee("Python"), souhaitee("anglais"));

            assertThat(n.note()).isEqualTo(67);
            assertThat(n.presentes()).extracting(Exigence::libelle).containsExactly("SQL", "Python", "anglais");
            assertThat(n.absentes()).containsExactly(obligatoire("dbt"));
        }

        @Test
        void aucune_exigence_retrouvee_donne_zero() {
            assertThat(noter(CV, obligatoire("Kubernetes"), souhaitee("Go")).note()).isZero();
        }

        @Test
        void les_obligatoires_absentes_sont_celles_qu_un_filtre_strict_eliminerait() {
            NoteAts n = noter(CV, obligatoire("SQL"), obligatoire("dbt"), souhaitee("Airflow"));

            assertThat(n.obligatoiresAbsentes()).containsExactly(obligatoire("dbt"));
        }

        @Test
        void sans_exigence_il_n_y_a_pas_de_note_plutot_qu_une_note_trompeuse() {
            assertThat(Ats.noter(CV, List.of())).isEmpty();
            assertThat(Ats.noter(CV, List.of(souhaitee("—")))).isEmpty();
        }

        @Test
        void une_exigence_repetee_ne_compte_qu_une_fois_et_obligatoire_l_emporte() {
            NoteAts n = noter(CV, souhaitee("sql"), obligatoire("SQL"), obligatoire("dbt"));

            assertThat(n.presentes()).containsExactly(obligatoire("sql"));
            assertThat(n.note()).isEqualTo(50);
        }

        @Test
        void un_texte_vide_est_illisible_et_ne_retrouve_rien() {
            NoteAts n = noter("  ", obligatoire("SQL"));

            assertThat(n.lisible()).isFalse();
            assertThat(n.note()).isZero();
        }

        @Test
        void le_meme_texte_donne_toujours_la_meme_note() {
            Exigence[] exigences = {obligatoire("SQL"), obligatoire("dbt"), souhaitee("Python")};
            assertThat(noter(CV, exigences)).isEqualTo(noter(CV, exigences));
        }
    }

    /** Ce que « délibérément stupide » veut dire, cas par cas : c'est le comportement d'un vrai filtre. */
    @Nested
    class Litteralite {

        @Test
        void une_variante_d_ecriture_n_est_pas_reconnue() {
            assertThat(noter(CV, obligatoire("PowerBI")).note()).isZero();
        }

        @Test
        void une_abreviation_n_est_pas_reconnue() {
            assertThat(noter("Projet de machine learning", obligatoire("ML")).note()).isZero();
            assertThat(noter("Projet de ML", obligatoire("machine learning")).note()).isZero();
        }

        @Test
        void un_mot_contenu_dans_un_autre_ne_compte_pas() {
            assertThat(noter("Bases NoSQL", obligatoire("SQL")).note()).isZero();
        }

        @Test
        void casse_et_accents_ne_comptent_pas() {
            assertThat(noter("MODELISATION de données", obligatoire("modélisation")).note()).isEqualTo(100);
        }
    }

    @Test
    void une_note_hors_bornes_n_existe_pas() {
        assertThatThrownBy(() -> new NoteAts(101, true, List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NoteAts(-1, true, List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void une_exigence_a_un_libelle() {
        assertThatThrownBy(() -> obligatoire(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(souhaitee(" Python ")).isEqualTo(new Exigence("Python", false));
    }
}
