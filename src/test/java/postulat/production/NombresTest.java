package postulat.production;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NombresTest {

    @Test
    void un_texte_sans_nombre_n_en_donne_aucun() {
        assertThat(Nombres.extraire("Conception d'un tableau de bord")).isEmpty();
    }

    @Test
    void les_ecritures_d_un_meme_nombre_se_confondent() {
        assertThat(Nombres.extraire("1 500 utilisateurs")).containsExactly("1500");
        assertThat(Nombres.extraire("1500 utilisateurs")).containsExactly("1500");
        assertThat(Nombres.extraire("1 500 utilisateurs")).containsExactly("1500");
        assertThat(Nombres.extraire("1,50 jour")).containsExactly("1.5");
        assertThat(Nombres.extraire("1.5 jour")).containsExactly("1.5");
        assertThat(Nombres.extraire("06 projets")).containsExactly("6");
    }

    @Test
    void les_unites_et_pourcentages_ne_changent_pas_le_nombre() {
        assertThat(Nombres.extraire("-30 % de délai, 12k€, 1er prix")).containsExactlyInAnyOrder("30", "12", "1");
        assertThat(Nombres.extraire("trente pour cent")).containsExactly("30");
    }

    @Test
    void des_nombres_separes_restent_separes() {
        assertThat(Nombres.extraire("5 KPI, 12 commerciaux")).containsExactly("5", "12");
        assertThat(Nombres.extraire("de 2025 2026")).containsExactly("2025", "2026");
    }

    @Test
    void une_date_ne_compte_que_par_son_annee() {
        assertThat(Nombres.extraire("2025-01 à 2025-06")).containsExactly("2025");
        assertThat(Nombres.extraire("le 15/03/2025")).containsExactly("2025");
        assertThat(Nombres.extraire("depuis 09/2024")).containsExactly("2024");
        assertThat(Nombres.extraire("2025-01-15")).containsExactly("2025");
    }

    @Test
    void les_nombres_simples_en_lettres_valent_leur_chiffre() {
        assertThat(Nombres.extraire("cinq indicateurs et douze commerciaux")).containsExactly("5", "12");
        assertThat(Nombres.extraire("Deux mille")).containsExactly("2", "1000");
    }

    @Test
    void les_autres_quantites_en_lettres_restent_des_mots() {
        assertThat(Nombres.extraire("vingt-cinq clients")).containsExactly("vingt-cinq");
        assertThat(Nombres.extraire("vingt-et-un jours")).containsExactly("vingt-et-un");
        assertThat(Nombres.extraire("une centaine de tickets")).containsExactly("centaine");
        assertThat(Nombres.extraire("trois millions")).containsExactly("3", "millions");
    }

    @Test
    void un_et_une_sont_des_articles_pas_des_nombres() {
        assertThat(Nombres.extraire("un tableau, une base, sous-traitance")).isEmpty();
    }
}
