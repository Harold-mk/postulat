package postulat.suivi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class EtapeTest {

    @Test
    void la_liste_est_fermee_a_onze_etapes() {
        assertThat(Etape.values()).hasSize(11);
    }

    @Test
    void les_cinq_terminales_sont_exactement_celles_du_cadrage() {
        assertThat(EnumSet.allOf(Etape.class).stream().filter(Etape::estTerminale))
                .containsExactlyInAnyOrder(
                        Etape.ECARTEE, Etape.ACCEPTEE, Etape.REFUSEE, Etape.SANS_SUITE, Etape.RETIREE);
    }

    @Test
    void une_candidature_validee_ne_saute_pas_directement_a_entretien() {
        assertThat(Etape.PRETE.suivantes()).containsExactlyInAnyOrder(Etape.ENVOYEE, Etape.RETIREE);
    }

    @Test
    void depuis_envoyee_on_peut_aller_directement_a_une_offre() {
        assertThat(Etape.ENVOYEE.peutAllerVers(Etape.OFFRE_RECUE)).isTrue();
    }

    @Test
    void aucune_etape_ne_mene_a_elle_meme() {
        for (Etape e : Etape.values()) {
            assertThat(e.suivantes()).doesNotContain(e);
        }
    }

    @Test
    void toute_etape_ouverte_peut_etre_abandonnee() {
        for (Etape e : Etape.values()) {
            if (!e.estTerminale() && e != Etape.REPEREE) {
                assertThat(e.suivantes()).as(e.libelle()).containsAnyOf(Etape.RETIREE, Etape.ECARTEE);
            }
        }
    }

    @Test
    void le_libelle_est_celui_du_cadrage() {
        assertThat(Etape.A_PREPARER).hasToString("à_préparer");
    }
}
