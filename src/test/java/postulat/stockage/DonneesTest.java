package postulat.stockage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import postulat.suivi.Candidature;
import postulat.suivi.Etape;
import postulat.suivi.ReglesRelance;
import postulat.suivi.TableauDeBord;

class DonneesTest {

    static final Clock HORLOGE = Clock.fixed(Instant.parse("2026-10-10T08:30:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dossier;

    Donnees donnees() {
        return new Donnees(dossier, HORLOGE);
    }

    Candidature creer(String entreprise) {
        Candidature c = BaseTest.envoyee(entreprise);
        try (Base base = donnees().ouvrirBase()) {
            base.creer(c);
        }
        return c;
    }

    List<Candidature> contenu() {
        try (Base base = donnees().ouvrirBase()) {
            return base.toutes().stream().map(Numerotee::candidature).toList();
        }
    }

    List<String> fichiers(Path repertoire) throws IOException {
        try (Stream<Path> liste = Files.list(repertoire)) {
            return liste.map(f -> f.getFileName().toString()).sorted().toList();
        }
    }

    @Nested
    class Sauvegarder {

        @Test
        void l_archive_est_datee_et_contient_la_base() throws IOException {
            creer("Acme");

            Path archive = donnees().sauvegarder();

            assertThat(archive.getFileName()).hasToString("postulat-2026-10-10-083000.zip");
            assertThat(archive.getParent()).isEqualTo(donnees().repertoireSauvegardes());
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                assertThat(zip.getEntry(Sauvegardes.ENTREE_BASE)).isNotNull();
            }
        }

        @Test
        void deux_sauvegardes_dans_la_meme_seconde_ne_s_ecrasent_pas() throws IOException {
            donnees().sauvegarder();
            donnees().sauvegarder();

            assertThat(fichiers(donnees().repertoireSauvegardes()))
                    .containsExactly("postulat-2026-10-10-083000-2.zip", "postulat-2026-10-10-083000.zip");
        }

        @Test
        void une_sauvegarde_qui_echoue_ne_laisse_aucune_archive() throws IOException {
            Sauvegardes sauvegardes = new Sauvegardes(dossier.resolve("sauvegardes"), HORLOGE);

            assertThatThrownBy(() -> sauvegardes.archiver(cible -> {
                        try {
                            Files.writeString(cible, "début de copie");
                        } catch (IOException e) {
                            throw new SQLException(e);
                        }
                        throw new SQLException("disque plein");
                    }))
                    .isInstanceOf(ErreurStockage.class)
                    .hasMessageContaining("disque plein")
                    .hasMessageContaining("Aucune archive incomplète");
            assertThat(fichiers(sauvegardes.repertoire())).isEmpty();
        }
    }

    @Nested
    class Restaurer {

        @Test
        void restaurer_rend_l_etat_sauvegarde_et_sauvegarde_l_etat_remplace() {
            Candidature avant = creer("Avant");
            Path archive = donnees().sauvegarder();
            creer("Après");

            Optional<Path> etatRemplace;
            try (Restauration r = donnees().preparerRestauration(archive)) {
                assertThat(r.candidatures()).isEqualTo(1);
                assertThat(r.evenements()).isEqualTo(1);
                assertThat(r.archive()).isEqualTo(archive);
                etatRemplace = donnees().restaurer(r);
            }

            assertThat(contenu()).containsExactly(avant);
            assertThat(etatRemplace).isPresent().get().isNotEqualTo(archive);
            assertThat(etatRemplace.get()).exists();
        }

        @Test
        void sans_etat_courant_rien_n_est_a_sauvegarder() {
            creer("Acme");
            Path archive = donnees().sauvegarder();
            Donnees ailleurs = new Donnees(dossier.resolve("autre-machine"), HORLOGE);

            try (Restauration r = ailleurs.preparerRestauration(archive)) {
                assertThat(ailleurs.restaurer(r)).isEmpty();
            }
            try (Base base = ailleurs.ouvrirBase()) {
                assertThat(base.toutes()).hasSize(1);
            }
        }

        @Test
        void sans_confirmation_rien_n_est_modifie() throws IOException {
            creer("Avant");
            Path archive = donnees().sauvegarder();
            Candidature apres = creer("Après");

            try (Restauration r = donnees().preparerRestauration(archive)) {
                assertThat(r.candidatures()).isEqualTo(1);
            }

            assertThat(contenu()).hasSize(2).contains(apres);
            assertThat(fichiers(dossier)).doesNotContain("restauration.db.provisoire");
        }

        @Test
        void une_archive_tronquee_est_refusee_avant_toute_ecriture() throws IOException {
            creer("Acme");
            Path archive = donnees().sauvegarder();
            byte[] octets = Files.readAllBytes(archive);
            Path tronquee = dossier.resolve("tronquee.zip");
            Files.write(tronquee, Arrays.copyOf(octets, octets.length / 2));

            assertThatThrownBy(() -> donnees().preparerRestauration(tronquee))
                    .isInstanceOf(ErreurStockage.class)
                    .hasMessageContaining("rien n'a été modifié");
            assertThat(contenu()).hasSize(1);
            assertThat(fichiers(dossier)).doesNotContain("restauration.db.provisoire");
        }

        @Test
        void un_fichier_qui_n_est_pas_une_sauvegarde_est_refuse() throws IOException {
            Path quelconque = dossier.resolve("photo.zip");
            Files.writeString(quelconque, "pas une archive");

            assertThatThrownBy(() -> donnees().preparerRestauration(quelconque))
                    .isInstanceOf(ErreurStockage.class)
                    .hasMessageContaining("pas une sauvegarde postulat");
        }

        @Test
        void une_base_corrompue_dans_l_archive_est_refusee() throws IOException {
            Path archive = dossier.resolve("corrompue.zip");
            try (OutputStream sortie = Files.newOutputStream(archive); ZipOutputStream zip = new ZipOutputStream(sortie)) {
                zip.putNextEntry(new ZipEntry(Sauvegardes.ENTREE_BASE));
                zip.write("SQLite format 3\0 mais la suite est du bruit".repeat(50).getBytes());
                zip.closeEntry();
            }

            assertThatThrownBy(() -> donnees().preparerRestauration(archive)).isInstanceOf(ErreurStockage.class);
        }

        @Test
        void une_archive_dont_une_etape_contredit_son_journal_est_refusee() throws SQLException {
            creer("Acme");
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + donnees().fichierBase());
                    Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE candidature SET etape = 'ACCEPTEE'");
            }
            Path archive = donnees().sauvegarder();

            assertThatThrownBy(() -> donnees().preparerRestauration(archive))
                    .isInstanceOf(ErreurStockage.class)
                    .hasMessageContaining("RG-10");
        }
    }

    @Nested
    class Emplacement {

        @Test
        void sous_windows_les_donnees_vont_dans_localappdata() {
            Path maison = Path.of("maison");
            assertThat(Donnees.repertoireParDefaut(Map.of("LOCALAPPDATA", "C:/Users/x/AppData/Local"), "Windows 11", maison))
                    .isEqualTo(Path.of("C:/Users/x/AppData/Local", "postulat"));
            assertThat(Donnees.repertoireParDefaut(Map.of(), "Windows 11", maison))
                    .isEqualTo(maison.resolve("AppData").resolve("Local").resolve("postulat"));
        }

        @Test
        void ailleurs_elles_suivent_xdg() {
            Path maison = Path.of("maison");
            assertThat(Donnees.repertoireParDefaut(Map.of("XDG_DATA_HOME", "/data"), "Linux", maison))
                    .isEqualTo(Path.of("/data", "postulat"));
            assertThat(Donnees.repertoireParDefaut(Map.of(), "Linux", maison))
                    .isEqualTo(maison.resolve(".local").resolve("share").resolve("postulat"));
        }
    }

    @Nested
    class LesReglages {

        @Test
        void sans_fichier_ce_sont_ceux_du_cadrage() {
            assertThat(donnees().reglages()).isEqualTo(Reglages.PAR_DEFAUT);
            assertThat(Reglages.PAR_DEFAUT.relance()).isEqualTo(ReglesRelance.PAR_DEFAUT);
            assertThat(Reglages.PAR_DEFAUT.objectifHebdomadaire()).isEqualTo(TableauDeBord.OBJECTIF_HEBDOMADAIRE);
        }

        @Test
        void un_reglage_absent_garde_sa_valeur_par_defaut() throws IOException {
            Files.writeString(dossier.resolve("postulat.properties"), "relance.delai.jours = 7\nobjectif.hebdomadaire=20\n");

            assertThat(donnees().reglages()).isEqualTo(new Reglages(new ReglesRelance(7, 2), 20));
        }

        @Test
        void un_reglage_invalide_est_signale_avec_sa_cle() throws IOException {
            Files.writeString(dossier.resolve("postulat.properties"), "relance.nombre = deux\n");

            assertThatThrownBy(() -> donnees().reglages())
                    .isInstanceOf(ErreurStockage.class)
                    .hasMessageContaining("relance.nombre")
                    .hasMessageContaining("deux");
        }

        @Test
        void un_reglage_absurde_est_refuse() throws IOException {
            Files.writeString(dossier.resolve("postulat.properties"), "objectif.hebdomadaire = 0\n");

            assertThatThrownBy(() -> donnees().reglages()).isInstanceOf(ErreurStockage.class);
        }
    }

    /** CA-9.6, EN-6 : un an de campagne se lit et se résume en moins d'une seconde. */
    @Test
    void un_an_de_campagne_se_charge_et_se_resume_en_moins_d_une_seconde() throws SQLException {
        LocalDate j0 = LocalDate.of(2025, 10, 1);
        try (Base base = donnees().ouvrirBase()) {
            base.creer(BaseTest.envoyee("Amorce"));
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + donnees().fichierBase())) {
            c.setAutoCommit(false);
            try (var candidature = c.prepareStatement("INSERT INTO candidature (id, entreprise, poste, etape) VALUES (?, ?, 'Stage', ?)");
                    var evenement = c.prepareStatement(
                            "INSERT INTO evenement (candidature_id, rang, type, survenu_le, etape, texte) VALUES (?, ?, ?, ?, ?, ?)")) {
                for (int n = 0; n < 1500; n++) {
                    String id = java.util.UUID.randomUUID().toString();
                    LocalDate jour = j0.plusDays(n / 5);
                    candidature.setString(1, id);
                    candidature.setString(2, "Entreprise " + n);
                    candidature.setString(3, Etape.ENVOYEE.name());
                    candidature.addBatch();
                    for (int rang = 0; rang < 7; rang++) {
                        evenement.setString(1, id);
                        evenement.setInt(2, rang);
                        evenement.setString(3, rang == 0 ? "CREATION" : "NOTE");
                        evenement.setString(4, jour.toString());
                        evenement.setString(5, rang == 0 ? Etape.ENVOYEE.name() : null);
                        evenement.setString(6, rang == 0 ? null : "note");
                        evenement.addBatch();
                    }
                }
                candidature.executeBatch();
                evenement.executeBatch();
            }
            c.commit();
        }

        long debut = System.nanoTime();
        TableauDeBord tableau;
        try (Base base = donnees().ouvrirBase()) {
            List<Candidature> toutes = base.toutes().stream().map(Numerotee::candidature).toList();
            tableau = TableauDeBord.calculer(toutes, LocalDate.of(2026, 10, 10), ReglesRelance.PAR_DEFAUT, 30);
        }
        long millisecondes = (System.nanoTime() - debut) / 1_000_000;

        assertThat(tableau.repartition()).containsEntry(Etape.ENVOYEE, 1501);
        assertThat(millisecondes).as("durée en ms").isLessThan(1000);
    }
}
