package postulat.bureau;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import postulat.ia.FauxModele;
import postulat.stockage.Donnees;
import postulat.stockage.Numerotee;
import postulat.suivi.Canal;
import postulat.suivi.Contact;
import postulat.suivi.Echeance;
import postulat.suivi.Etape;
import postulat.suivi.Evenement;
import postulat.suivi.RefusMetier;
import postulat.suivi.TypeEntretien;

/** Ce que l'interface lit et fait, sans fenêtre : les mêmes règles que la ligne de commande. */
class SessionTest {

    static final Clock HORLOGE = Clock.fixed(Instant.parse("2026-10-11T09:00:00Z"), ZoneOffset.UTC);
    static final LocalDate J = LocalDate.of(2026, 10, 11);

    @TempDir
    Path dossier;

    Session s;

    @BeforeEach
    void ouvrir() {
        s = new Session(new Donnees(dossier, HORLOGE), HORLOGE, r -> new FauxModele());
    }

    @Test
    void une_candidature_s_ajoute_et_un_doublon_demande_confirmation() {
        Numerotee n = s.ajouter(" Acme ", " Stage data ", Etape.ENVOYEE, J.minusDays(12), false);

        assertThat(n.numero()).isEqualTo(1);
        assertThat(n.candidature().entreprise()).isEqualTo("Acme");
        assertThatThrownBy(() -> s.ajouter("ACME", "stage  data", Etape.REPEREE, J, false))
                .isInstanceOfSatisfying(Session.Doublon.class, d -> assertThat(d.existante().numero()).isEqualTo(1))
                .hasMessageContaining("déjà suivie : #1 Acme — Stage data, à l'étape « envoyée »");
        assertThat(s.ajouter("ACME", "stage  data", Etape.REPEREE, J, true).numero()).isEqualTo(2);
        assertThat(s.candidatures()).hasSize(2);
        assertThat(s.numeros()).containsEntry(n.candidature().id(), 1L);
    }

    @Test
    void chaque_saisie_passe_par_le_domaine_et_s_enregistre() {
        long n = s.ajouter("Initech", "Data analyst", Etape.ENVOYEE, J.minusDays(20), false).numero();

        s.relancer(n, J.minusDays(9));
        s.avancer(n, Etape.ENTRETIEN, J.minusDays(5));
        s.planifier(n, TypeEntretien.TECHNIQUE, J.plusDays(2), J.minusDays(5));
        s.entretien(n, TypeEntretien.RH, "Bon contact, suite la semaine prochaine.", J.minusDays(4));
        s.entretien(n, TypeEntretien.RH, " ", J.minusDays(4));
        s.retourPromis(n, J.plusDays(3), J.minusDays(3));
        s.interaction(n, Canal.COURRIEL, null, J.minusDays(2));
        s.noter(n, " À relire avant l'entretien. ", J.minusDays(1));
        s.ajouterContact(n, new Contact("Alex Morel", "recrutement", "alex.morel@exemple.test"));

        Session.Fiche fiche = s.fiche(n);
        assertThat(fiche.numerotee().candidature().journal()).extracting(e -> e.getClass().getSimpleName()).containsExactly(
                "Creation", "RelanceEffectuee", "ChangementEtape", "EntretienPlanifie", "EntretienPasse", "Note", "EntretienPasse",
                "RetourPromis", "Interaction", "Note");
        assertThat(fiche.numerotee().candidature().journal().get(5))
                .isEqualTo(new Evenement.Note(J.minusDays(4), "Compte rendu de l'entretien RH : Bon contact, suite la semaine prochaine."));
        assertThat(fiche.numerotee().candidature().journal().getLast()).isEqualTo(new Evenement.Note(J.minusDays(1), "À relire avant l'entretien."));
        assertThat(fiche.contacts()).containsExactly(new Contact("Alex Morel", "recrutement", "alex.morel@exemple.test"));
        assertThat(fiche.echeances()).extracting(Echeance::date).contains(J.plusDays(1), J.plusDays(2));
        assertThat(fiche.offre()).isEmpty();
        assertThat(fiche.dossier()).isEmpty();
        assertThat(fiche.versions()).isEmpty();
        assertThat(fiche.cout().appels()).isZero();
    }

    @Test
    void ecarter_et_rouvrir_suivent_les_regles_du_suivi() {
        long n = s.ajouter("Umbrella", "Stage", Etape.REPEREE, J.minusDays(3), false).numero();

        assertThat(s.ecarter(n, " Trop loin. ", J).candidature().etape()).isEqualTo(Etape.ECARTEE);
        assertThat(s.rouvrir(n, Etape.REPEREE, J).candidature().etape()).isEqualTo(Etape.REPEREE);
        assertThatThrownBy(() -> s.avancer(n, Etape.ENVOYEE, J)).isInstanceOf(RefusMetier.class).hasMessageContaining("non autorisée");
        assertThatThrownBy(() -> s.relancer(n, J)).isInstanceOf(RefusMetier.class);
        assertThatThrownBy(() -> s.noter(9, "x", J)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("n° 9");
    }

    @Test
    void le_tableau_et_les_actions_sont_ceux_de_la_ligne_de_commande() {
        long globex = s.ajouter("Globex", "Stage BI", Etape.ENVOYEE, J.minusDays(21), false).numero();
        s.ajouter("Acme", "Stage data", Etape.REPEREE, J.minusDays(5), false);

        assertThat(s.tableau().enAttente()).hasSize(1);
        assertThat(s.tableau().brouillons()).hasSize(1);
        assertThat(s.actions()).singleElement().satisfies(e -> {
            assertThat(e.motif()).isInstanceOf(Echeance.Motif.Relancer.class);
            assertThat(e.candidatureId()).isEqualTo(s.fiche(globex).numerotee().candidature().id());
        });

        s.relancer(globex, J);

        assertThat(s.actions()).isEmpty();
        assertThat(s.prochaine()).hasValueSatisfying(e -> assertThat(e.date()).isEqualTo(J.plusDays(10)));
        assertThat(s.aujourdhui()).isEqualTo(J);
    }

    @Test
    void la_liste_se_filtre_par_etape_et_par_texte_sans_casse_ni_accents() {
        Numerotee acme = s.ajouter("Société Générale", "Stage data", Etape.ENVOYEE, J, false);
        Numerotee ecartee = s.ajouter("Umbrella", "Stage", Etape.REPEREE, J, false);
        Numerotee terminee = s.ecarter(ecartee.numero(), "Non", J);

        assertThat(VueCandidatures.retenue(acme, "En cours", "societe")).isTrue();
        assertThat(VueCandidatures.retenue(acme, "En cours", "STAGE DATA")).isTrue();
        assertThat(VueCandidatures.retenue(acme, "En cours", "Umbrella")).isFalse();
        assertThat(VueCandidatures.retenue(terminee, "En cours", "")).isFalse();
        assertThat(VueCandidatures.retenue(terminee, "Toutes", null)).isTrue();
        assertThat(VueCandidatures.retenue(terminee, "écartée", "")).isTrue();
        assertThat(VueCandidatures.retenue(acme, "écartée", "")).isFalse();
        assertThat(Ui.etape(Etape.A_PREPARER)).isEqualTo("à préparer");
        assertThat(Ui.jour(J)).isEqualTo("dimanche 11 octobre 2026");
    }
}
