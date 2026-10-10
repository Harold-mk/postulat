package postulat.production;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MentionsTest {

    @Test
    void une_mention_se_cherche_en_mots_entiers() {
        assertThat(Mentions.mentionne("Bases NoSQL", "SQL")).isFalse();
        assertThat(Mentions.mentionne("Requêtes SQL avancées", "sql")).isTrue();
    }

    @Test
    void casse_accents_et_separateurs_ne_comptent_pas() {
        assertThat(Mentions.mentionne("Notions de MACHINE-LEARNING", "Machine learning")).isTrue();
        assertThat(Mentions.mentionne("Modélisation des données", "modelisation")).isTrue();
    }

    @Test
    void les_langages_a_symboles_restent_distincts() {
        assertThat(Mentions.mentionne("Développement en C++", "C++")).isTrue();
        assertThat(Mentions.mentionne("Développement en C#", "C++")).isFalse();
    }

    @Test
    void un_terme_vide_n_est_jamais_mentionne() {
        assertThat(Mentions.mentionne("Tout texte", " - ")).isFalse();
    }

    /** Ce qu'une extraction de PDF produit couramment : ligature, espace insécable, césure conditionnelle. */
    @Test
    void les_artefacts_d_extraction_de_pdf_sont_neutralises() {
        assertThat(Mentions.mentionne("Conﬁguration d'un serveur", "configuration")).isTrue();
        assertThat(Mentions.mentionne("Power BI", "Power BI")).isTrue();
        assertThat(Mentions.mentionne("dévelop­pement web", "développement")).isTrue();
    }
}
