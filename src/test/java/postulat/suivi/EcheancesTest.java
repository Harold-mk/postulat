package postulat.suivi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import postulat.suivi.Echeance.Motif;

class EcheancesTest {

    static final LocalDate ENVOI = LocalDate.of(2026, 10, 1);
    static final LocalDate LOIN = LocalDate.of(2027, 1, 1);
    static final ReglesRelance REGLES = ReglesRelance.PAR_DEFAUT;

    static Candidature envoyee(String entreprise, LocalDate date) {
        return Candidature.creer(entreprise, "Stage data", Etape.ENVOYEE, date, LOIN);
    }

    static Echeance seuleActionDue(Candidature c, LocalDate jour) {
        List<Echeance> dues = Echeances.actionsDues(c, jour, REGLES);
        assertThat(dues).hasSize(1);
        return dues.get(0);
    }

    static List<Motif> motifsDus(Candidature c, LocalDate jour) {
        return Echeances.actionsDues(c, jour, REGLES).stream().map(Echeance::motif).toList();
    }

    @Nested
    class Relances {

        @Test
        void rien_n_est_du_avant_dix_jours() {
            assertThat(Echeances.actionsDues(envoyee("Acme", ENVOI), ENVOI.plusDays(9), REGLES)).isEmpty();
        }

        @Test
        void la_premiere_relance_est_due_a_j_plus_10() {
            Echeance a = seuleActionDue(envoyee("Acme", ENVOI), ENVOI.plusDays(10));

            assertThat(a.motif()).isEqualTo(new Motif.Relancer(1));
            assertThat(a.date()).isEqualTo(ENVOI.plusDays(10));
            assertThat(a.joursDeRetard(ENVOI.plusDays(10))).isZero();
            assertThat(a.entreprise()).isEqualTo("Acme");
            assertThat(a.depuis()).isEqualTo(ENVOI);
        }

        @Test
        void le_retard_se_compte_en_jours_depuis_l_echeance() {
            Echeance a = seuleActionDue(envoyee("Acme", ENVOI), ENVOI.plusDays(13));
            assertThat(a.joursDeRetard(ENVOI.plusDays(13))).isEqualTo(3);
        }

        @Test
        void une_relance_effectuee_repousse_l_echeance_de_dix_jours() {
            Candidature c = envoyee("Acme", ENVOI).relancer(ENVOI.plusDays(12), LOIN);

            assertThat(Echeances.actionsDues(c, ENVOI.plusDays(21), REGLES)).isEmpty();
            assertThat(seuleActionDue(c, ENVOI.plusDays(22)).motif()).isEqualTo(new Motif.Relancer(2));
        }

        @Test
        void apres_deux_relances_et_dix_jours_de_silence_sans_suite_est_propose() {
            Candidature c = envoyee("Acme", ENVOI)
                    .relancer(ENVOI.plusDays(10), LOIN)
                    .relancer(ENVOI.plusDays(20), LOIN);

            assertThat(seuleActionDue(c, ENVOI.plusDays(30)).motif()).isEqualTo(new Motif.ProposerSansSuite());
            // proposé, jamais appliqué (CA-7.3)
            assertThat(c.etape()).isEqualTo(Etape.ENVOYEE);
        }

        @Test
        void une_etape_autre_qu_envoyee_n_a_pas_de_relance() {
            Candidature c = envoyee("Acme", ENVOI).avancer(Etape.ENTRETIEN, ENVOI.plusDays(3), LOIN);
            assertThat(Echeances.actionsDues(c, ENVOI.plusDays(40), REGLES)).isEmpty();
        }

        @Test
        void une_candidature_terminale_n_a_plus_d_echeance() {
            Candidature c = envoyee("Acme", ENVOI).avancer(Etape.REFUSEE, ENVOI.plusDays(3), LOIN);
            assertThat(Echeances.echeances(c, ENVOI.plusDays(40), REGLES)).isEmpty();
        }

        @Test
        void les_delais_sont_configurables() {
            ReglesRelance regles = new ReglesRelance(7, 1);
            Candidature c = envoyee("Acme", ENVOI).relancer(ENVOI.plusDays(7), LOIN);

            List<Echeance> dues = Echeances.actionsDues(c, ENVOI.plusDays(14), regles);
            assertThat(dues).extracting(Echeance::motif).containsExactly(new Motif.ProposerSansSuite());
        }

        @Test
        void des_regles_absurdes_sont_refusees() {
            assertThatThrownBy(() -> new ReglesRelance(0, 2)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ReglesRelance(10, -1)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class EntretiensPlanifies {

        final LocalDate le = ENVOI.plusDays(20);
        final Candidature planifiee = envoyee("Acme", ENVOI)
                .avancer(Etape.ENTRETIEN, ENVOI.plusDays(5), LOIN)
                .planifierEntretien(TypeEntretien.TECHNIQUE, le, ENVOI.plusDays(6), LOIN);

        @Test
        void la_preparation_est_due_la_veille() {
            assertThat(Echeances.actionsDues(planifiee, le.minusDays(2), REGLES)).isEmpty();
            assertThat(motifsDus(planifiee, le.minusDays(1)))
                    .containsExactly(new Motif.PreparerEntretien(TypeEntretien.TECHNIQUE, le));
        }

        @Test
        void le_jour_meme_preparer_reste_du_et_remercier_le_devient() {
            assertThat(motifsDus(planifiee, le)).containsExactly(
                    new Motif.PreparerEntretien(TypeEntretien.TECHNIQUE, le),
                    new Motif.Remercier(TypeEntretien.TECHNIQUE, le));
        }

        @Test
        void passe_l_entretien_seul_le_remerciement_subsiste_un_jour() {
            assertThat(motifsDus(planifiee, le.plusDays(1)))
                    .containsExactly(new Motif.Remercier(TypeEntretien.TECHNIQUE, le));
            assertThat(Echeances.echeances(planifiee, le.plusDays(2), REGLES)).isEmpty();
        }

        @Test
        void replanifier_un_meme_type_remplace_la_date() {
            LocalDate nouvelle = le.plusDays(5);
            Candidature c = planifiee.planifierEntretien(TypeEntretien.TECHNIQUE, nouvelle, ENVOI.plusDays(8), LOIN);

            assertThat(Echeances.actionsDues(c, le, REGLES)).isEmpty();
            assertThat(motifsDus(c, nouvelle.minusDays(1)))
                    .containsExactly(new Motif.PreparerEntretien(TypeEntretien.TECHNIQUE, nouvelle));
        }

        @Test
        void deux_types_d_entretien_ont_chacun_leurs_echeances() {
            Candidature c = planifiee.planifierEntretien(TypeEntretien.RH, le, ENVOI.plusDays(7), LOIN);
            assertThat(Echeances.actionsDues(c, le, REGLES)).hasSize(4);
        }

        @Test
        void quitter_l_etape_entretien_eteint_ses_echeances() {
            Candidature c = planifiee.avancer(Etape.OFFRE_RECUE, ENVOI.plusDays(9), LOIN);
            assertThat(Echeances.echeances(c, le, REGLES)).isEmpty();
        }

        @Test
        void l_anciennete_d_une_candidature_non_envoyee_est_sa_date_de_creation() {
            assertThat(seuleActionDue(planifiee, le.minusDays(1)).depuis()).isEqualTo(ENVOI);
        }
    }

    @Nested
    class RetoursPromis {

        final LocalDate pour = ENVOI.plusDays(6);
        final Candidature promise = envoyee("Acme", ENVOI).noterRetourPromis(pour, ENVOI.plusDays(2), LOIN);

        @Test
        void le_retour_promis_devient_du_a_sa_date() {
            assertThat(Echeances.actionsDues(promise, pour.minusDays(1), REGLES)).isEmpty();
            assertThat(motifsDus(promise, pour)).containsExactly(new Motif.RetourPromis());
        }

        @Test
        void il_reste_du_tant_que_rien_de_nouveau_n_arrive() {
            assertThat(seuleActionDue(promise, pour.plusDays(2)).joursDeRetard(pour.plusDays(2))).isEqualTo(2);
        }

        @Test
        void une_note_ne_l_eteint_pas() {
            Candidature c = promise.noter("Compte rendu de l'appel", ENVOI.plusDays(3), LOIN);
            assertThat(motifsDus(c, pour)).containsExactly(new Motif.RetourPromis());
        }

        @Test
        void un_echange_ulterieur_l_eteint() {
            Candidature c = promise.interagir(Canal.COURRIEL, "Réponse reçue", ENVOI.plusDays(5), LOIN);
            assertThat(Echeances.echeances(c, pour, REGLES))
                    .extracting(Echeance::motif)
                    .doesNotContain(new Motif.RetourPromis());
        }

        @Test
        void il_coexiste_avec_la_relance() {
            assertThat(motifsDus(promise, ENVOI.plusDays(10)))
                    .containsExactlyInAnyOrder(new Motif.RetourPromis(), new Motif.Relancer(1));
        }
    }

    @Nested
    class ListeDeTravail {

        @Test
        void la_liste_de_travail_est_triee_par_retard_puis_anciennete() {
            LocalDate aujourdhui = ENVOI.plusDays(20);
            Candidature tresEnRetard = envoyee("A", ENVOI);                  // échéance J+10 : 10 j de retard
            Candidature peuEnRetard = envoyee("B", ENVOI.plusDays(8));       // échéance J+18 : 2 j
            Candidature relanceeLeJourMeme =
                    envoyee("C", ENVOI.plusDays(8)).relancer(ENVOI.plusDays(8), LOIN); // J+18 : 2 j
            Candidature pasEncore = envoyee("D", ENVOI.plusDays(15));        // échéance J+25 : rien

            List<Echeance> liste = Echeances.listeDeTravail(
                    List.of(pasEncore, peuEnRetard, relanceeLeJourMeme, tresEnRetard), aujourdhui, REGLES);

            assertThat(liste).extracting(Echeance::entreprise).startsWith("A").hasSize(3).doesNotContain("D");
        }

        @Test
        void a_retard_egal_la_candidature_la_plus_ancienne_passe_d_abord() {
            LocalDate aujourdhui = ENVOI.plusDays(30);
            Candidature recente = envoyee("Récente", ENVOI.plusDays(5)).relancer(ENVOI.plusDays(20), LOIN);
            Candidature ancienne = envoyee("Ancienne", ENVOI).relancer(ENVOI.plusDays(20), LOIN);

            List<Echeance> liste = Echeances.listeDeTravail(List.of(recente, ancienne), aujourdhui, REGLES);

            assertThat(liste).extracting(Echeance::entreprise).containsExactly("Ancienne", "Récente");
        }

        @Test
        void a_retard_egal_l_etape_la_plus_avancee_passe_d_abord() {
            LocalDate le = ENVOI.plusDays(11);
            Candidature enEntretien = Candidature.creer("Entretien", "Stage", Etape.ENTRETIEN, ENVOI, LOIN)
                    .planifierEntretien(TypeEntretien.RH, le, ENVOI, LOIN);  // préparation due à J+10
            Candidature envoyee = envoyee("Envoyée", ENVOI);                  // relance due à J+10

            List<Echeance> liste = Echeances.listeDeTravail(List.of(envoyee, enEntretien), ENVOI.plusDays(10), REGLES);

            assertThat(liste).extracting(Echeance::entreprise).containsExactly("Entretien", "Envoyée");
        }

        @Test
        void sans_action_due_la_liste_est_vide() {
            assertThat(Echeances.listeDeTravail(List.of(envoyee("A", ENVOI)), ENVOI, REGLES)).isEmpty();
        }

        @Test
        void les_echeances_a_venir_couvrent_les_jours_suivants_sans_aujourd_hui() {
            Candidature c = envoyee("A", ENVOI);  // relance due à J+10

            assertThat(Echeances.aVenir(List.of(c), ENVOI.plusDays(2), 7, REGLES)).isEmpty();
            assertThat(Echeances.aVenir(List.of(c), ENVOI.plusDays(3), 7, REGLES)).hasSize(1);
            assertThat(Echeances.aVenir(List.of(c), ENVOI.plusDays(10), 7, REGLES)).isEmpty();
        }

        @Test
        void une_echeance_a_venir_a_un_retard_negatif() {
            Echeance e = Echeances.echeances(envoyee("A", ENVOI), ENVOI.plusDays(4), REGLES).get(0);
            assertThat(e.estAtteinte(ENVOI.plusDays(4))).isFalse();
            assertThat(e.joursDeRetard(ENVOI.plusDays(4))).isEqualTo(-6);
        }
    }
}
