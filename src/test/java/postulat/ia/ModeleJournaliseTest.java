package postulat.ia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import postulat.ia.ErreurModele.Nature;

class ModeleJournaliseTest {

    static final Instant MAINTENANT = Instant.parse("2026-10-10T08:30:00Z");

    record Salut(String message) {}

    final List<AppelModele> journal = new ArrayList<>();
    final FauxModele faux = new FauxModele();
    final Modele modele = new ModeleJournalise(faux, journal::add, Clock.fixed(MAINTENANT, ZoneOffset.UTC));

    final Demande demande = new Demande("essai", Gamme.ECONOMIQUE, "Consignes.", "Contenu.", Optional.of(UUID.randomUUID()));

    @Test
    void un_appel_reussi_est_journalise_avec_sa_reponse_et_son_cout() {
        faux.repondre(new Salut("Prêt"));

        Reponse<Salut> r = modele.demander(demande, Salut.class);

        assertThat(r.valeur()).isEqualTo(new Salut("Prêt"));
        assertThat(journal).singleElement().satisfies(a -> {
            assertThat(a.survenuLe()).isEqualTo(MAINTENANT);
            assertThat(a.demande()).isEqualTo(demande);
            assertThat(a.issue()).isEqualTo(AppelModele.REUSSI);
            assertThat(a.reponse()).contains("Prêt");
            assertThat(a.consommation()).contains(FauxModele.CONSOMMATION);
        });
    }

    @Test
    void un_appel_qui_echoue_est_journalise_aussi_puis_l_echec_remonte() {
        Consommation refus = new Consommation("claude-opus-5-5", 50, 0, 0, 0, 200, java.time.Duration.ZERO);
        faux.echouer(new ErreurModele(Nature.REFUS, "Le modèle a décliné la demande.", refus, null));

        assertThatThrownBy(() -> modele.demander(demande, Salut.class)).isInstanceOf(ErreurModele.class);

        assertThat(journal).singleElement().satisfies(a -> {
            assertThat(a.issue()).startsWith("échec REFUS").contains("décliné");
            assertThat(a.reponse()).isEmpty();
            assertThat(a.consommation()).contains(refus);
        });
    }

    @Test
    void le_faux_modele_retient_les_demandes_et_signale_une_reponse_non_preparee() {
        faux.repondre(new Salut("un"));
        modele.demander(demande, Salut.class);

        assertThat(faux.demandes()).containsExactly(demande);
        assertThatThrownBy(() -> faux.demander(demande, Salut.class)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void une_demande_exige_tache_consignes_et_contenu() {
        assertThatThrownBy(() -> Demande.horsCandidature(" ", Gamme.CAPABLE, "c", "c")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Demande.horsCandidature("t", Gamme.CAPABLE, "", "c")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Demande.horsCandidature("t", Gamme.CAPABLE, "c", " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void un_tarif_donne_des_millioniemes_de_dollar_par_categorie_de_jetons() {
        Tarif haiku = Tarif.de("claude-haiku-5-5").orElseThrow();

        // 10 000 × 0,10 + 2 000 × 0,50 + 5 000 × 0,01 + 1 000 × 0,125 = 2 175
        assertThat(haiku.microDollars(10_000, 2_000, 5_000, 1_000)).isEqualTo(2_175);
        assertThat(Tarif.de("modele-inconnu")).isEmpty();
        assertThat(Tarif.modelesConnus()).contains("claude-haiku-5-5", "claude-opus-5-5");
        assertThat(FauxModele.CONSOMMATION.coutDollars()).isEqualByComparingTo(new BigDecimal("0.0015"));
    }
}
