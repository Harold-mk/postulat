package postulat.production;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** CA-1.3 : ce qu'un modèle lit d'un CV se confronte au CV. */
class LectureFideleTest {

    static final String CV = "Stagiaire chez Acme (Lyon), 2025-01 à 2025-06 : tableau de bord Power BI pour douze commerciaux.";

    @Test
    void un_nombre_doit_se_lire_dans_la_source_une_date_par_son_annee() {
        assertThat(LectureFidele.nombresAbsents("12 commerciaux en 2025", CV)).isEmpty();
        assertThat(LectureFidele.nombresAbsents("15 commerciaux, 30 % de gain, en 2024", CV)).containsExactly("15", "2024", "30");
    }

    @Test
    void un_nom_doit_se_lire_dans_la_source_casse_et_accents_mis_a_part() {
        assertThat(LectureFidele.figure("ACME", CV)).isTrue();
        assertThat(LectureFidele.figure("power bi", CV)).isTrue();
        assertThat(LectureFidele.figure("Globex", CV)).isFalse();
        assertThat(LectureFidele.figure("Power", "PowerBI")).isFalse();
        assertThat(LectureFidele.figure("—", CV)).isTrue();
    }
}
