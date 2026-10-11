package postulat.stockage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static postulat.production.Exigence.obligatoire;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import postulat.stockage.Migrations.Migration;
import postulat.production.Assertion;
import postulat.production.Critere;
import postulat.production.Instantane;
import postulat.production.Origine;
import postulat.suivi.Candidature;
import postulat.suivi.Etape;
import postulat.suivi.Evenement;

/** Les versions validées du dossier (UC-09) : figées en base, avec leurs instantanés, et immuables (RG-15, RG-16). */
class ValidationStockageTest {

    static final Clock HORLOGE = Clock.fixed(Instant.parse("2026-10-11T08:30:00Z"), ZoneOffset.UTC);
    static final LocalDate JOUR = LocalDate.of(2026, 10, 11);
    static final Instant GENERE = Instant.parse("2026-10-11T09:00:00Z");
    static final Instant VALIDE = Instant.parse("2026-10-11T10:00:00Z");

    @TempDir
    Path dossier;

    Path fichier() {
        return dossier.resolve("postulat.db");
    }

    Base ouvrir() {
        return Base.ouvrir(fichier(), Migrations.duProjet(), HORLOGE, copie -> { });
    }

    static DossierGenere genere(String empreinte) {
        return DossierGenere.nouveau(DossierStockageTest.dossier(Optional.of(DossierStockageTest.ATS)), GENERE, empreinte, "claude-opus-5-5");
    }

    static VersionValidee version(int numero, DossierGenere g) {
        return new VersionValidee(numero, g, VALIDE, "candidatures/1 Globex — Stage data/version " + numero,
                List.of(new Instantane("exp-acme", "Stagiaire data analyst\nAcme\n2025"), new Instantane("comp-sql", "SQL\navancé")));
    }

    @Test
    void une_version_validee_se_relit_a_l_identique_et_la_candidature_est_prete() {
        try (Base base = ouvrir()) {
            Candidature c = Candidature.creer("Globex", "Stage data", Etape.A_PREPARER, JOUR, JOUR);
            base.creer(c);
            DossierGenere g = genere("ab12");
            base.enregistrerDossier(c.id(), g);

            base.validerDossier(c.valider(1, JOUR, JOUR), version(1, g));

            assertThat(base.versionsValidees(c.id())).containsExactly(version(1, g));
            assertThat(base.aUnDossierValide(c.id())).isTrue();
            Candidature relue = base.trouver(1).orElseThrow().candidature();
            assertThat(relue.etape()).isEqualTo(Etape.PRETE);
            assertThat(relue.journal()).last().isEqualTo(new Evenement.DossierValide(JOUR, 1));
            assertThat(base.dossier(c.id())).contains(g);
            assertThat(base.verifierCoherence()).isEmpty();
        }
    }

    @Test
    void le_brouillon_se_remplace_encore_et_une_seconde_version_s_ajoute_sans_toucher_la_premiere() {
        try (Base base = ouvrir()) {
            Candidature c = Candidature.creer("Globex", "Stage data", Etape.A_PREPARER, JOUR, JOUR);
            base.creer(c);
            DossierGenere v1 = genere("ab12");
            base.enregistrerDossier(c.id(), v1);
            Candidature prete = c.valider(1, JOUR, JOUR);
            base.validerDossier(prete, version(1, v1));

            DossierGenere v2 = genere("cd34").corrige(v1.dossier().corriger(1, "Data analyst — Acme"), VALIDE);
            base.enregistrerDossier(c.id(), v2);
            base.validerDossier(prete.valider(2, JOUR, JOUR), version(2, v2));

            assertThat(base.versionsValidees(c.id())).containsExactly(version(1, v1), version(2, v2));
            assertThat(base.dossier(c.id())).contains(v2);
        }
    }

    @Test
    void sans_l_evenement_qui_la_date_aucune_version_n_est_figee() {
        try (Base base = ouvrir()) {
            Candidature c = Candidature.creer("Globex", "Stage data", Etape.A_PREPARER, JOUR, JOUR);
            base.creer(c);
            DossierGenere g = genere("ab12");
            base.enregistrerDossier(c.id(), g);
            Candidature reecrite = new Candidature(c.id(), "Globex", "Stage data", List.of(new Evenement.Creation(JOUR.minusDays(1),
                    Etape.A_PREPARER))).valider(1, JOUR, JOUR);

            assertThatThrownBy(() -> base.validerDossier(reecrite, version(1, g))).isInstanceOf(ErreurStockage.class);
            assertThat(base.versionsValidees(c.id())).isEmpty();
            assertThat(base.trouver(1).orElseThrow().candidature().etape()).isEqualTo(Etape.A_PREPARER);
        }
    }

    /** RG-15, au plus bas niveau : même hors de l'application, une version validée ne se modifie ni ne se supprime. */
    @Test
    void des_triggers_refusent_de_toucher_une_version_validee() throws SQLException {
        try (Base base = ouvrir()) {
            Candidature c = Candidature.creer("Globex", "Stage data", Etape.A_PREPARER, JOUR, JOUR);
            base.creer(c);
            DossierGenere g = genere("ab12");
            base.enregistrerDossier(c.id(), g);
            base.validerDossier(c.valider(1, JOUR, JOUR), version(1, g));
        }
        try (Connection cx = DriverManager.getConnection("jdbc:sqlite:" + fichier()); Statement s = cx.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("UPDATE dossier_phrase SET texte = 'inventé' WHERE version = 1"))
                    .hasMessageContaining("Un dossier validé ne se modifie pas");
            assertThatThrownBy(() -> s.executeUpdate("DELETE FROM dossier_critere WHERE version = 1"))
                    .hasMessageContaining("Un dossier validé ne se supprime pas");
            assertThatThrownBy(() -> s.executeUpdate("UPDATE dossier SET objet = 'autre' WHERE version = 1"))
                    .hasMessageContaining("ne se modifie pas");
            assertThatThrownBy(() -> s.executeUpdate("UPDATE dossier_fait SET contenu = 'autre'"))
                    .hasMessageContaining("Un instantané de fait ne se modifie pas");
            assertThatThrownBy(() -> s.executeUpdate("DELETE FROM dossier_fait"))
                    .hasMessageContaining("ne se supprime pas");
            assertThat(s.executeUpdate("UPDATE dossier SET objet = 'Brouillon retouché' WHERE version = 0")).isEqualTo(1);
        }
    }

    /** La migration V6 recrée les tables du dossier : un brouillon écrit sous V5 doit en sortir intact, en version 0. */
    @Test
    void un_brouillon_ecrit_avant_la_v6_y_survit_en_version_0() throws SQLException {
        List<Migration> jusquaV5 = Migrations.FICHIERS.subList(0, 5).stream().map(Migrations::lire).toList();
        Base.ouvrir(fichier(), new Migrations(jusquaV5), HORLOGE, copie -> { }).close();
        String id = UUID.randomUUID().toString();
        try (Connection cx = DriverManager.getConnection("jdbc:sqlite:" + fichier()); Statement s = cx.createStatement()) {
            s.executeUpdate("INSERT INTO candidature (id, entreprise, poste, etape) VALUES ('" + id + "', 'Acme', 'Stage', 'A_PREPARER')");
            s.executeUpdate("INSERT INTO dossier (candidature_id, genere_le, empreinte_profil, passes, nom, titre, contact, lieu, "
                    + "destinataire, lieu_et_date, objet, paragraphes, note_ats, ats_lisible, seuil, poids_ats, revisions_max, mots_min, "
                    + "mots_max, corrige_le, avis_perime) VALUES ('" + id + "', '2026-10-10T09:00:00Z', 'ab12', 1, 'Camille Martin', "
                    + "'Étudiante', 'camille@exemple.test', 'Lyon', 'Acme', 'Le 10 octobre 2026', 'Candidature', 1, 100, 1, 75, 40, 2, "
                    + "250, 350, '2026-10-10T10:00:00Z', 1)");
            s.executeUpdate("INSERT INTO dossier_section VALUES ('" + id + "', 0, 'Expériences')");
            s.executeUpdate("INSERT INTO dossier_phrase VALUES ('" + id + "', 0, 'CV', 0, 0, 'INTITULE', NULL, 'Stagiaire — Acme', 'FAITS')");
            s.executeUpdate("INSERT INTO dossier_phrase_fait VALUES ('" + id + "', 0, 0, 'exp-acme')");
            s.executeUpdate("INSERT INTO dossier_phrase VALUES ('" + id + "', 1, 'LETTRE', 0, 0, 'PHRASE', NULL, 'Acme recrute.', 'OFFRE')");
            s.executeUpdate("INSERT INTO dossier_exigence VALUES ('" + id + "', 0, 'SQL', 1, 1)");
            for (Critere k : Critere.values()) {
                s.executeUpdate("INSERT INTO dossier_critere VALUES ('" + id + "', " + k.ordinal() + ", '" + k.name() + "', 4, 'Solide.')");
            }
            s.executeUpdate("INSERT INTO dossier_remarque VALUES ('" + id + "', 'FORCE', 0, 'Clair')");
        }

        try (Base base = ouvrir()) {
            DossierGenere g = base.dossier(UUID.fromString(id)).orElseThrow();

            assertThat(g.modele()).isEmpty();
            assertThat(g.avisPerime()).isTrue();
            assertThat(g.corrigeLe()).contains(Instant.parse("2026-10-10T10:00:00Z"));
            assertThat(g.dossier().cv().assertions()).containsExactly(new Assertion("Stagiaire — Acme", Origine.Faits.de("exp-acme")));
            assertThat(g.dossier().lettre().assertions()).containsExactly(new Assertion("Acme recrute.", new Origine.Offre()));
            assertThat(g.dossier().evaluation().ats()).hasValueSatisfying(a -> assertThat(a.presentes()).containsExactly(obligatoire("SQL")));
            assertThat(g.dossier().evaluation().avis().note()).isEqualTo(80);
            assertThat(g.dossier().evaluation().avis().forces()).containsExactly("Clair");
            assertThat(base.versionsValidees(UUID.fromString(id))).isEmpty();
        }
    }
}
