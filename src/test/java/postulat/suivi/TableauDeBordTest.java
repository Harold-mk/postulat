package postulat.suivi;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import postulat.suivi.TableauDeBord.Semaine;

class TableauDeBordTest {

    /** Un jeudi : la semaine commence le lundi 28 septembre. */
    static final LocalDate JEUDI_1ER = LocalDate.of(2026, 10, 1);
    static final LocalDate LUNDI_28 = LocalDate.of(2026, 9, 28);
    static final LocalDate LOIN = LocalDate.of(2027, 1, 1);
    static final ReglesRelance REGLES = ReglesRelance.PAR_DEFAUT;

    static Candidature creee(String entreprise, Etape etape, LocalDate date) {
        return Candidature.creer(entreprise, "Stage data", etape, date, LOIN);
    }

    static TableauDeBord calculer(List<Candidature> candidatures, LocalDate aujourdhui) {
        return TableauDeBord.calculer(candidatures, aujourdhui, REGLES, TableauDeBord.OBJECTIF_HEBDOMADAIRE);
    }

    @Test
    void la_repartition_couvre_toutes_les_etapes_y_compris_vides() {
        TableauDeBord t = calculer(List.of(
                creee("A", Etape.ENVOYEE, JEUDI_1ER),
                creee("B", Etape.ENVOYEE, JEUDI_1ER),
                creee("C", Etape.REPEREE, JEUDI_1ER)), JEUDI_1ER);

        assertThat(t.repartition()).hasSize(Etape.values().length);
        assertThat(t.repartition()).containsEntry(Etape.ENVOYEE, 2).containsEntry(Etape.REPEREE, 1)
                .containsEntry(Etape.ACCEPTEE, 0);
        assertThat(t.repartition().keySet()).first().isEqualTo(Etape.REPEREE);
    }

    @Test
    void une_campagne_vide_est_juste_des_le_depart() {
        TableauDeBord t = calculer(List.of(), JEUDI_1ER);

        assertThat(t.repartition().values()).containsOnly(0);
        assertThat(t.semaines()).containsExactly(new Semaine(LUNDI_28, 0, true, true));
        assertThat(t.enAttente()).isEmpty();
        assertThat(t.actionsDues()).isEmpty();
        assertThat(t.brouillons()).isEmpty();
        assertThat(t.objectifHebdomadaire()).isEqualTo(30);
    }

    @Test
    void la_semaine_ou_le_suivi_commence_est_partielle() {
        TableauDeBord t = calculer(List.of(creee("A", Etape.ENVOYEE, JEUDI_1ER)), JEUDI_1ER.plusDays(7));

        assertThat(t.semaines()).containsExactly(
                new Semaine(LUNDI_28, 1, true, false),
                new Semaine(LUNDI_28.plusWeeks(1), 0, false, true));
    }

    @Test
    void seules_les_quatre_dernieres_semaines_sont_affichees() {
        TableauDeBord t = calculer(List.of(creee("A", Etape.ENVOYEE, JEUDI_1ER)), JEUDI_1ER.plusWeeks(6));

        assertThat(t.semaines()).hasSize(4);
        assertThat(t.semaines()).extracting(Semaine::partielle).containsOnly(false);
        assertThat(t.semaines()).last().extracting(Semaine::enCours).isEqualTo(true);
    }

    @Test
    void un_envoi_est_une_creation_ou_un_passage_a_envoyee_jamais_une_reouverture() {
        LocalDate jeudi8 = JEUDI_1ER.plusDays(7);
        Candidature creeeEnvoyee = creee("A", Etape.ENVOYEE, JEUDI_1ER);
        Candidature passeeAEnvoyee = creee("B", Etape.REPEREE, JEUDI_1ER)
                .avancer(Etape.A_PREPARER, JEUDI_1ER, LOIN)
                .avancer(Etape.PRETE, JEUDI_1ER, LOIN)
                .avancer(Etape.ENVOYEE, jeudi8.minusDays(2), LOIN);
        Candidature externeEnEntretien = creee("C", Etape.ENTRETIEN, jeudi8.minusDays(2));
        Candidature rouverte = creee("D", Etape.ENVOYEE, JEUDI_1ER)
                .avancer(Etape.REFUSEE, JEUDI_1ER.plusDays(1), LOIN)
                .reouvrir(Etape.ENVOYEE, jeudi8.minusDays(1), LOIN);

        TableauDeBord t = calculer(List.of(creeeEnvoyee, passeeAEnvoyee, externeEnEntretien, rouverte), jeudi8);

        assertThat(t.semaines()).extracting(Semaine::envoyees).containsExactly(2, 1);
    }

    @Test
    void les_candidatures_en_attente_et_les_brouillons_sont_tries_par_anciennete() {
        Candidature recente = creee("Récente", Etape.ENVOYEE, JEUDI_1ER.plusDays(3));
        Candidature ancienne = creee("Ancienne", Etape.ENVOYEE, JEUDI_1ER);
        Candidature brouillonRecent = creee("B2", Etape.REPEREE, JEUDI_1ER.plusDays(2));
        Candidature brouillonAncien = creee("B1", Etape.REPEREE, JEUDI_1ER);

        TableauDeBord t = calculer(List.of(recente, brouillonRecent, ancienne, brouillonAncien), JEUDI_1ER.plusDays(4));

        assertThat(t.enAttente()).extracting(Candidature::entreprise).containsExactly("Ancienne", "Récente");
        assertThat(t.brouillons()).extracting(Candidature::entreprise).containsExactly("B1", "B2");
    }

    @Test
    void actions_dues_et_echeances_a_venir_sont_separees() {
        Candidature due = creee("Due", Etape.ENVOYEE, JEUDI_1ER);                      // relance à J+10
        Candidature bientot = creee("Bientôt", Etape.ENVOYEE, JEUDI_1ER.plusDays(5));  // relance à J+15
        Candidature lointaine = creee("Lointaine", Etape.ENVOYEE, JEUDI_1ER.plusDays(9)); // J+19

        TableauDeBord t = calculer(List.of(due, bientot, lointaine), JEUDI_1ER.plusDays(10));

        assertThat(t.actionsDues()).extracting(Echeance::entreprise).containsExactly("Due");
        assertThat(t.aVenir()).extracting(Echeance::entreprise).containsExactly("Bientôt");
    }
}
