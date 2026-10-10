package postulat.stockage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static postulat.production.Exigence.obligatoire;
import static postulat.production.Exigence.souhaitee;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import postulat.production.Couverture;
import postulat.production.OffreAnalysee;
import postulat.production.Rapprochement;
import postulat.suivi.Candidature;
import postulat.suivi.Etape;

/** L'offre d'une candidature en base : texte brut, analyse, rapprochement (UC-05, RG-17, CA-2.5). */
class OffreStockageTest {

    static final Clock HORLOGE = Clock.fixed(Instant.parse("2026-10-10T08:30:00Z"), ZoneOffset.UTC);
    static final Instant RECUE = Instant.parse("2026-10-10T08:30:00Z");
    static final Instant PLUS_TARD = Instant.parse("2026-10-11T09:00:00Z");
    static final String TEXTE = "Acme recrute un stagiaire data analyst à Lyon. SQL exigé, Tableau apprécié.";

    static final OffreAnalysee ANALYSE = new OffreAnalysee("Stagiaire data analyst", "Acme", "Lyon", "stage", "", "6 mois", "",
            List.of("Construire des tableaux de bord", "Fiabiliser les données"), List.of(obligatoire("SQL"), souhaitee("Tableau")));
    static final Rapprochement RAPPROCHEMENT = new Rapprochement(List.of(
            new Couverture(obligatoire("SQL"), List.of("exp-acme-r1", "comp-sql"), "SQL avancé."),
            new Couverture(souhaitee("Tableau"), List.of(), "Seul Power BI est pratiqué.")), 72, "Bonne base.");

    @TempDir
    Path dossier;

    Base base;
    Candidature candidature;

    @BeforeEach
    void ouvrir() {
        base = Base.ouvrir(dossier.resolve("postulat.db"), new Sauvegardes(dossier.resolve("sauvegardes"), HORLOGE), HORLOGE);
        candidature = Candidature.creer("Acme", "Stagiaire data analyst", Etape.REPEREE, LocalDate.of(2026, 10, 10),
                LocalDate.of(2026, 10, 10));
    }

    @AfterEach
    void fermer() {
        base.close();
    }

    @Test
    void la_candidature_et_son_offre_naissent_ensemble_avec_le_texte_brut_et_l_analyse() {
        Numerotee n = base.creerDepuisOffre(candidature, "C:/offres/acme.txt", TEXTE, RECUE, Optional.of(ANALYSE));

        Offre o = base.offre(candidature.id()).orElseThrow();
        assertThat(n.candidature()).isEqualTo(candidature);
        assertThat(o.source()).isEqualTo("C:/offres/acme.txt");
        assertThat(o.texteBrut()).isEqualTo(TEXTE);
        assertThat(o.recueLe()).isEqualTo(RECUE);
        assertThat(o.analyse()).contains(ANALYSE);
        assertThat(o.analyseeLe()).contains(RECUE);
        assertThat(o.rapprochement()).isEmpty();
    }

    @Test
    void sans_modele_l_offre_est_conservee_non_analysee_puis_analysable_plus_tard() {
        base.creerDepuisOffre(candidature, "entrée standard", TEXTE, RECUE, Optional.empty());
        assertThat(base.offre(candidature.id()).orElseThrow().analyse()).isEmpty();

        base.enregistrerAnalyse(candidature.id(), ANALYSE, PLUS_TARD);

        assertThat(base.offre(candidature.id()).orElseThrow().analyseeLe()).contains(PLUS_TARD);
    }

    @Test
    void le_rapprochement_se_relit_exigence_par_exigence_faits_compris() {
        base.creerDepuisOffre(candidature, "fichier", TEXTE, RECUE, Optional.of(ANALYSE));

        base.enregistrerRapprochement(candidature.id(), RAPPROCHEMENT, PLUS_TARD);

        Offre o = base.offre(candidature.id()).orElseThrow();
        assertThat(o.rapprochement()).contains(RAPPROCHEMENT);
        assertThat(o.rapprocheeLe()).contains(PLUS_TARD);
    }

    @Test
    void refaire_le_rapprochement_le_remplace() {
        base.creerDepuisOffre(candidature, "fichier", TEXTE, RECUE, Optional.of(ANALYSE));
        base.enregistrerRapprochement(candidature.id(), RAPPROCHEMENT, RECUE);
        Rapprochement nouveau = new Rapprochement(List.of(
                new Couverture(obligatoire("SQL"), List.of("comp-sql"), "SQL."),
                new Couverture(souhaitee("Tableau"), List.of("comp-tableau"), "Tableau appris depuis.")), 90, "Profil enrichi.");

        base.enregistrerRapprochement(candidature.id(), nouveau, PLUS_TARD);

        assertThat(base.offre(candidature.id()).orElseThrow().rapprochement()).contains(nouveau);
    }

    @Test
    void refaire_l_analyse_efface_le_rapprochement_qui_reposait_sur_l_ancienne() {
        base.creerDepuisOffre(candidature, "fichier", TEXTE, RECUE, Optional.of(ANALYSE));
        base.enregistrerRapprochement(candidature.id(), RAPPROCHEMENT, RECUE);
        OffreAnalysee autre = new OffreAnalysee("Stage BI", "Acme", "", "", "", "", "", List.of(), List.of(obligatoire("Python")));

        base.enregistrerAnalyse(candidature.id(), autre, PLUS_TARD);

        Offre o = base.offre(candidature.id()).orElseThrow();
        assertThat(o.analyse()).contains(autre);
        assertThat(o.rapprochement()).isEmpty();
    }

    @Test
    void un_rapprochement_d_autres_exigences_que_l_analyse_est_refuse() {
        base.creerDepuisOffre(candidature, "fichier", TEXTE, RECUE, Optional.of(ANALYSE));
        Rapprochement decale = new Rapprochement(List.of(new Couverture(obligatoire("SQL"), List.of(), "")), 10, "");

        assertThatThrownBy(() -> base.enregistrerRapprochement(candidature.id(), decale, PLUS_TARD))
                .isInstanceOf(ErreurStockage.class)
                .hasMessageContaining("rien n'a été enregistré");
        assertThat(base.offre(candidature.id()).orElseThrow().rapprochement()).isEmpty();
    }

    @Test
    void le_meme_texte_designe_la_candidature_existante() {
        Numerotee n = base.creerDepuisOffre(candidature, "fichier", TEXTE, RECUE, Optional.empty());

        assertThat(base.candidatureDeLOffre(TEXTE)).contains(n);
        assertThat(base.candidatureDeLOffre(TEXTE + " autre")).isEmpty();
    }

    @Test
    void une_candidature_saisie_a_la_main_n_a_pas_d_offre() {
        base.creer(candidature);

        assertThat(base.offre(candidature.id())).isEmpty();
        assertThatThrownBy(() -> base.enregistrerAnalyse(candidature.id(), ANALYSE, RECUE)).isInstanceOf(ErreurStockage.class);
    }

    @Test
    void l_offre_d_une_candidature_ecartee_reste_conservee() {
        base.creerDepuisOffre(candidature, "fichier", TEXTE, RECUE, Optional.of(ANALYSE));

        base.enregistrer(candidature.ecarter("Trop loin", LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 10)));

        assertThat(base.trouver(1).orElseThrow().candidature().etape()).isEqualTo(Etape.ECARTEE);
        assertThat(base.offre(candidature.id()).orElseThrow().analyse()).contains(ANALYSE);
    }
}
