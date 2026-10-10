package postulat.stockage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import postulat.ia.AppelModele;
import postulat.ia.Consommation;
import postulat.ia.Demande;
import postulat.ia.Gamme;
import postulat.suivi.Candidature;

/** Le journal des appels au modèle (EN-5, EN-12) : reconstituable, cumulable, jamais réécrit. */
class JournalModeleTest {

    static final Clock HORLOGE = Clock.fixed(Instant.parse("2026-10-10T08:30:00Z"), ZoneOffset.UTC);
    static final Consommation CONSOMMATION =
            new Consommation("claude-opus-5-5", 1200, 300, 800, 0, 10_960, Duration.ofMillis(1500));

    @TempDir
    Path dossier;

    Base base;
    Candidature candidature;

    @BeforeEach
    void ouvrir() {
        base = Base.ouvrir(dossier.resolve("postulat.db"), new Sauvegardes(dossier.resolve("sauvegardes"), HORLOGE), HORLOGE);
        candidature = BaseTest.envoyee("Acme");
        base.creer(candidature);
    }

    @AfterEach
    void fermer() {
        base.close();
    }

    AppelModele appel(String instant, Optional<UUID> pour, Optional<Consommation> consommation) {
        Demande d = new Demande("analyse-offre", Gamme.CAPABLE, "Consignes stables.", "Texte de l'offre.", pour);
        return new AppelModele(Instant.parse(instant), d, consommation.isPresent() ? "{\"poste\":\"Stage\"}" : "",
                consommation.isPresent() ? AppelModele.REUSSI : "échec INDISPONIBLE : API injoignable.", consommation);
    }

    String requete(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dossier.resolve("postulat.db"));
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getString(1);
        }
    }

    @Test
    void un_appel_se_reconstitue_depuis_le_journal() throws SQLException {
        base.journaliser(appel("2026-10-10T08:30:00Z", Optional.of(candidature.id()), Optional.of(CONSOMMATION)));

        assertThat(requete("SELECT tache || '|' || gamme || '|' || modele || '|' || consignes || '|' || contenu || '|' "
                + "|| reponse || '|' || issue || '|' || duree_ms FROM appel_modele"))
                .isEqualTo("analyse-offre|CAPABLE|claude-opus-5-5|Consignes stables.|Texte de l'offre.|{\"poste\":\"Stage\"}|réussi|1500");
        assertThat(requete("SELECT jetons_entree || ',' || jetons_sortie || ',' || jetons_cache_lus || ',' || "
                + "jetons_cache_ecrits || ',' || cout_micro_dollars FROM appel_modele")).isEqualTo("1200,300,800,0,10960");
    }

    @Test
    void le_cout_d_une_candidature_reunit_ses_appels_et_eux_seuls() {
        base.journaliser(appel("2026-10-10T08:30:00Z", Optional.of(candidature.id()), Optional.of(CONSOMMATION)));
        base.journaliser(appel("2026-10-10T09:00:00Z", Optional.of(candidature.id()), Optional.empty()));
        base.journaliser(appel("2026-10-10T09:30:00Z", Optional.empty(), Optional.of(CONSOMMATION)));

        assertThat(base.coutModele(candidature.id())).isEqualTo(new CoutModele(2, 10_960));
        assertThat(base.coutModele(UUID.randomUUID())).isEqualTo(CoutModele.AUCUN);
    }

    @Test
    void les_couts_se_cumulent_mois_par_mois_du_plus_ancien_au_plus_recent() {
        base.journaliser(appel("2026-10-01T10:00:00Z", Optional.empty(), Optional.of(CONSOMMATION)));
        base.journaliser(appel("2026-09-30T23:00:00Z", Optional.empty(), Optional.of(CONSOMMATION)));
        base.journaliser(appel("2026-10-05T10:00:00Z", Optional.empty(), Optional.of(CONSOMMATION)));

        assertThat(base.coutsModeleParMois()).containsExactly(
                java.util.Map.entry(YearMonth.of(2026, 9), new CoutModele(1, 10_960)),
                java.util.Map.entry(YearMonth.of(2026, 10), new CoutModele(2, 21_920)));
        assertThat(new CoutModele(2, 21_920).coutDollars()).hasToString("0.021920");
    }

    @Test
    void un_appel_ne_se_rattache_pas_a_une_candidature_inconnue() {
        assertThatThrownBy(() -> base.journaliser(appel("2026-10-10T08:30:00Z", Optional.of(UUID.randomUUID()), Optional.of(CONSOMMATION))))
                .isInstanceOf(ErreurStockage.class);
    }

    @Test
    void un_appel_journalise_ne_se_modifie_ni_ne_se_supprime() {
        base.journaliser(appel("2026-10-10T08:30:00Z", Optional.empty(), Optional.of(CONSOMMATION)));

        assertThatThrownBy(() -> requete("UPDATE appel_modele SET cout_micro_dollars = 0 RETURNING numero"))
                .hasMessageContaining("ne se modifie pas");
        assertThatThrownBy(() -> requete("DELETE FROM appel_modele RETURNING numero"))
                .hasMessageContaining("ne se supprime pas");
    }
}
