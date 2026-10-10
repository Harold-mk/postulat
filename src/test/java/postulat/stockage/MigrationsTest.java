package postulat.stockage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import postulat.stockage.Migrations.Migration;
import postulat.suivi.Candidature;

class MigrationsTest {

    static final Clock HORLOGE = Clock.fixed(Instant.parse("2026-10-10T08:30:00Z"), ZoneOffset.UTC);

    static final Migration V1 = Migrations.lire("V1__suivi.sql");
    static final Migration V2 = new Migration(2, "V2__essai.sql",
            "ALTER TABLE candidature ADD COLUMN source TEXT; CREATE TABLE offre (id TEXT PRIMARY KEY);");

    @TempDir
    Path dossier;

    final List<Sauvegardes.CopieBase> sauvegardesDemandees = new ArrayList<>();

    Path fichier() {
        return dossier.resolve("postulat.db");
    }

    Base ouvrir(Migration... migrations) {
        return Base.ouvrir(fichier(), new Migrations(List.of(migrations)), HORLOGE, sauvegardesDemandees::add);
    }

    List<String> versionsAppliquees() throws SQLException {
        List<String> noms = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + fichier());
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT nom FROM migration ORDER BY version")) {
            while (r.next()) {
                noms.add(r.getString(1));
            }
        }
        return noms;
    }

    boolean tableExiste(String nom) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + fichier());
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT count(*) FROM sqlite_master WHERE name = '" + nom + "'")) {
            return r.next() && r.getInt(1) == 1;
        }
    }

    @Test
    void une_base_neuve_recoit_toutes_les_migrations_sans_sauvegarde() throws SQLException {
        ouvrir(V1, V2).close();

        assertThat(versionsAppliquees()).containsExactly("V1__suivi.sql", "V2__essai.sql");
        assertThat(sauvegardesDemandees).isEmpty();
    }

    @Test
    void une_base_a_jour_s_ouvre_sans_rien_appliquer_ni_sauvegarder() throws SQLException {
        ouvrir(V1).close();
        ouvrir(V1).close();

        assertThat(versionsAppliquees()).containsExactly("V1__suivi.sql");
        assertThat(sauvegardesDemandees).isEmpty();
    }

    @Test
    void une_migration_en_attente_est_precedee_d_une_sauvegarde() throws SQLException {
        ouvrir(V1).close();
        ouvrir(V1, V2).close();

        assertThat(sauvegardesDemandees).hasSize(1);
        assertThat(versionsAppliquees()).containsExactly("V1__suivi.sql", "V2__essai.sql");
    }

    @Test
    void pas_de_sauvegarde_pas_de_migration() throws SQLException {
        ouvrir(V1).close();
        Migrations avecV2 = new Migrations(List.of(V1, V2));

        assertThatThrownBy(() -> Base.ouvrir(fichier(), avecV2, HORLOGE, copie -> {
                    throw new ErreurStockage("disque plein");
                }))
                .hasMessageContaining("disque plein");
        assertThat(versionsAppliquees()).containsExactly("V1__suivi.sql");
        assertThat(tableExiste("offre")).isFalse();
    }

    @Test
    void une_migration_qui_echoue_est_annulee_entierement() throws SQLException {
        ouvrir(V1).close();
        Migration fautive = new Migration(2, "V2__fautive.sql", "CREATE TABLE offre (id TEXT); INSERT INTO inexistante VALUES (1);");

        assertThatThrownBy(() -> ouvrir(V1, fautive))
                .isInstanceOf(ErreurStockage.class)
                .hasMessageContaining("V2__fautive.sql")
                .hasMessageContaining("annulée");
        assertThat(tableExiste("offre")).isFalse();
        assertThat(versionsAppliquees()).containsExactly("V1__suivi.sql");
    }

    @Test
    void une_migration_modifiee_apres_application_est_refusee() {
        ouvrir(V1).close();
        Migration retouchee = new Migration(1, "V1__suivi.sql", V1.sql() + "\n-- retouche");

        assertThatThrownBy(() -> ouvrir(retouchee))
                .isInstanceOf(ErreurStockage.class)
                .hasMessageContaining("modifiée après avoir été appliquée");
    }

    @Test
    void une_base_d_un_schema_plus_recent_est_refusee() {
        ouvrir(V1, V2).close();

        assertThatThrownBy(() -> ouvrir(V1))
                .isInstanceOf(ErreurStockage.class)
                .hasMessageContaining("plus récent");
    }

    @Test
    void une_base_sqlite_etrangere_est_refusee() throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + fichier());
                Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE autre (x INTEGER)");
        }

        assertThatThrownBy(() -> ouvrir(V1))
                .isInstanceOf(ErreurStockage.class)
                .hasMessageContaining("pas une base postulat");
    }

    @Test
    void les_migrations_se_numerotent_sans_trou() {
        assertThatThrownBy(() -> new Migrations(List.of(V2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Migrations.lire("suivi.sql")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chaque_fichier_de_migration_est_declare_dans_l_ordre() throws IOException {
        Path repertoire = Path.of("src/main/resources/db/migration");
        try (Stream<Path> fichiers = Files.list(repertoire)) {
            assertThat(fichiers.map(f -> f.getFileName().toString())
                            .sorted(Comparator.comparingInt(nom -> Integer.parseInt(nom.substring(1, nom.indexOf("__"))))))
                    .containsExactlyElementsOf(Migrations.FICHIERS);
        }
        assertThat(Migrations.duProjet().versionCible()).isEqualTo(Migrations.FICHIERS.size());
    }

    /**
     * Le retour arrière d'ADR-0004, point 5 : appliquer une migration, restaurer la sauvegarde prise
     * automatiquement avant elle, et retrouver l'état d'origine, journal compris.
     */
    @Test
    void restaurer_la_sauvegarde_d_avant_migration_rend_l_etat_d_origine() {
        Candidature c = BaseTest.completeLe(BaseTest.J0);
        Sauvegardes sauvegardes = new Sauvegardes(dossier.resolve("sauvegardes"), HORLOGE);
        Migrations avecV1 = new Migrations(List.of(V1));
        try (Base base = Base.ouvrir(fichier(), avecV1, HORLOGE, sauvegardes::archiver)) {
            base.creer(c);
        }
        try (Base base = Base.ouvrir(fichier(), new Migrations(List.of(V1, V2)), HORLOGE, sauvegardes::archiver)) {
            base.creer(BaseTest.envoyee("Après la migration"));
        }
        Path archive = sauvegardes.repertoire().resolve("postulat-2026-10-10-083000.zip");
        assertThat(archive).exists();

        try (Restauration r = Restauration.preparer(archive, dossier, avecV1, HORLOGE)) {
            r.appliquer(fichier());
        }

        try (Base base = Base.ouvrir(fichier(), avecV1, HORLOGE, sauvegardes::archiver)) {
            assertThat(base.toutes()).extracting(Numerotee::candidature).containsExactly(c);
        }
    }
}
