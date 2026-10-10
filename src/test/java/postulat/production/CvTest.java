package postulat.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CvTest {

    static Assertion faits(String texte, String id) {
        return new Assertion(texte, Origine.Faits.de(id));
    }

    static final Cv.Puce DASHBOARD = new Cv.Puce(faits("Tableau de bord Power BI : 5 KPI", "exp-acme-dashboard"), 1);
    static final Cv.Puce SQL = new Cv.Puce(faits("Requêtes SQL quotidiennes", "comp-sql"), 3);
    static final Cv.Puce AUTEUR = new Cv.Puce(new Assertion("Goût du travail en équipe", new Origine.Auteur()), 2);

    static Cv cv() {
        Cv.Bloc stage = new Cv.Bloc(faits("Stagiaire data analyst — Acme", "exp-acme"),
                Optional.of(faits("2025-01 à 2025-06 · 6 mois", "exp-acme")), List.of(DASHBOARD, SQL, AUTEUR));
        return new Cv(Exemples.identite(), List.of(
                new Cv.Section("Expériences", List.of(stage)),
                new Cv.Section("Compétences", List.of(new Cv.Ligne(faits("SQL (avancé)", "comp-sql"))))));
    }

    @Test
    void les_assertions_se_lisent_dans_l_ordre_du_document() {
        assertThat(cv().assertions()).extracting(Assertion::texte).containsExactly(
                "Stagiaire data analyst — Acme", "2025-01 à 2025-06 · 6 mois", "Tableau de bord Power BI : 5 KPI",
                "Requêtes SQL quotidiennes", "Goût du travail en équipe", "SQL (avancé)");
    }

    @Test
    void les_puces_se_classent_par_pertinence_et_la_derniere_se_retire_la_premiere() {
        assertThat(cv().puces()).containsExactly(DASHBOARD, AUTEUR, SQL);
        assertThat(cv().moinsPertinente()).contains(SQL);
    }

    @Test
    void retirer_une_puce_garde_le_reste_intact() {
        Cv sans = cv().sans(SQL);

        assertThat(sans.puces()).containsExactly(DASHBOARD, AUTEUR);
        assertThat(sans.assertions()).hasSize(5).extracting(Assertion::texte).doesNotContain("Requêtes SQL quotidiennes");
        assertThat(sans.sections()).hasSize(2);
        assertThat(sans.sans(DASHBOARD).sans(AUTEUR).moinsPertinente()).isEmpty();
    }

    @Test
    void deux_puces_ne_partagent_pas_un_rang_de_pertinence() {
        Cv.Bloc b = new Cv.Bloc(faits("Stage", "exp-acme"), Optional.empty(),
                List.of(DASHBOARD, new Cv.Puce(faits("Autre", "exp-acme"), 1)));

        assertThatThrownBy(() -> new Cv(Exemples.identite(), List.of(new Cv.Section("Expériences", List.of(b)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Cv.Puce(faits("x", "exp-acme"), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void l_apercu_tire_du_profil_cite_chaque_fait_et_passe_la_verification() {
        Profil profil = Exemples.complet();

        Cv apercu = Apercu.de(profil);

        assertThat(apercu.sections()).extracting(Cv.Section::titre).containsExactly("Expériences", "Formation", "Compétences");
        Cv.Bloc stage = (Cv.Bloc) apercu.sections().get(0).contenus().get(0);
        assertThat(stage.intitule()).isEqualTo(faits("Stagiaire data analyst — Acme", "exp-acme"));
        assertThat(stage.precision()).contains(faits("2025-01 à 2025-06 · 6 mois · Lyon", "exp-acme"));
        assertThat(stage.puces()).containsExactly(new Cv.Puce(faits(
                "Concevoir un tableau de bord Power BI : 5 KPI, utilisé par 12 commerciaux", "exp-acme-dashboard"), 1));
        assertThat(apercu.sections().get(2).contenus()).containsExactly(
                new Cv.Ligne(new Assertion("SQL (avancé)", new Origine.Faits(List.of("comp-sql")))));
        assertThat(Tracabilite.verifier(apercu.assertions(), profil, List.of())).isEmpty();
    }

    @Test
    void un_profil_sans_formation_ni_competence_n_a_que_ses_experiences() {
        Profil p = new Profil(Exemples.identite(), List.of(Exemples.stage()), List.of(), List.of());

        assertThat(Apercu.de(p).sections()).extracting(Cv.Section::titre).containsExactly("Expériences");
    }

    @Test
    void une_lettre_se_lit_paragraphe_par_paragraphe() {
        Lettre l = new Lettre(Exemples.identite(), "Acme", "Lyon, le 10 octobre 2026", "Candidature au stage data",
                List.of(new Lettre.Paragraphe(List.of(faits("Votre équipe data grandit.", "exp-acme"))),
                        new Lettre.Paragraphe(List.of(faits("J'ai conçu un tableau de bord de 5 KPI.", "exp-acme-dashboard")))));

        assertThat(l.assertions()).extracting(Assertion::texte)
                .containsExactly("Votre équipe data grandit.", "J'ai conçu un tableau de bord de 5 KPI.");
    }
}
