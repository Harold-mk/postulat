package postulat.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static postulat.production.Exigence.obligatoire;
import static postulat.production.Exigence.souhaitee;

import java.util.List;
import org.junit.jupiter.api.Test;

class RapprochementTest {

    static final Couverture SQL = new Couverture(obligatoire("SQL"), List.of("comp-sql", "exp-acme-dashboard"), "Requêtes au quotidien.");
    static final Couverture TABLEAU = new Couverture(souhaitee("Tableau"), List.of(), "Seul Power BI est pratiqué.");

    @Test
    void une_exigence_sans_fait_est_une_lacune() {
        Rapprochement r = new Rapprochement(List.of(SQL, TABLEAU), 70, "Bonne base technique.");

        assertThat(SQL.couverte()).isTrue();
        assertThat(TABLEAU.couverte()).isFalse();
        assertThat(r.lacunes()).containsExactly(souhaitee("Tableau"));
    }

    @Test
    void les_faits_cites_sont_verifies_contre_le_profil() {
        Couverture inventee = new Couverture(obligatoire("Python"), List.of("comp-python", "comp-sql", "comp-python"), "");
        Rapprochement r = new Rapprochement(List.of(SQL, inventee), 50, "");

        assertThat(r.faitsInconnus(Exemples.complet())).containsExactly("comp-python");
        assertThat(new Rapprochement(List.of(SQL, TABLEAU), 70, "").faitsInconnus(Exemples.complet())).isEmpty();
    }

    @Test
    void une_note_d_adequation_va_de_0_a_100() {
        assertThatThrownBy(() -> new Rapprochement(List.of(), 101, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Rapprochement(List.of(), -1, "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void une_offre_analysee_ne_se_modifie_pas_de_l_exterieur() {
        OffreAnalysee o = new OffreAnalysee("Stage data", "Acme", "Lyon", "stage", "mars 2027", "6 mois", "bac+5",
                List.of("Construire des tableaux de bord"), List.of(obligatoire("SQL")));

        assertThatThrownBy(() -> o.exigences().add(souhaitee("Excel"))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> o.missions().add("Autre")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> SQL.faits().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }
}
