package postulat.stockage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Migrations de schéma versionnées (ADR-0004), écrites à la main plutôt qu'avec Flyway : une
 * cinquantaine de lignes contre une dépendance qui en tire d'autres.
 *
 * <p>Les principes d'ADR-0004 sont tenus : fichiers SQL numérotés appliqués une fois et dans l'ordre ;
 * empreinte de chaque migration appliquée, pour refuser de démarrer si son fichier a changé ;
 * sauvegarde automatique avant toute migration d'une base qui contient des données ; retour arrière
 * par restauration de cette sauvegarde.
 */
final class Migrations {

    /** Ordre d'application. Une migration ajoutée ici ne se modifie plus jamais : on en ajoute une suivante. */
    static final List<String> FICHIERS = List.of("V1__suivi.sql", "V2__journal_modele.sql", "V3__offre.sql");

    static final String REPERTOIRE = "/db/migration/";

    private static final Pattern NOM = Pattern.compile("V(\\d+)__\\w+\\.sql");

    record Migration(int version, String nom, String sql) {

        String empreinte() {
            try {
                byte[] hache = MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(hache);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 est garanti par toute JVM.", e);
            }
        }
    }

    private final List<Migration> migrations;

    Migrations(List<Migration> migrations) {
        for (int i = 0; i < migrations.size(); i++) {
            if (migrations.get(i).version() != i + 1) {
                throw new IllegalArgumentException("Les migrations se numérotent 1, 2, 3… sans trou : "
                        + migrations.get(i).nom() + " est en position " + (i + 1) + ".");
            }
        }
        this.migrations = List.copyOf(migrations);
    }

    static Migrations duProjet() {
        return new Migrations(FICHIERS.stream().map(Migrations::lire).toList());
    }

    static Migration lire(String fichier) {
        Matcher m = NOM.matcher(fichier);
        if (!m.matches()) {
            throw new IllegalArgumentException("Nom de migration invalide : " + fichier + " (attendu : V<n>__<nom>.sql).");
        }
        try (InputStream in = Migrations.class.getResourceAsStream(REPERTOIRE + fichier)) {
            if (in == null) {
                throw new IllegalStateException("Migration introuvable dans le jar : " + fichier);
            }
            return new Migration(Integer.parseInt(m.group(1)), fichier, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ErreurStockage("Lecture de la migration " + fichier + " impossible.", e);
        }
    }

    int versionCible() {
        return migrations.size();
    }

    /**
     * Met le schéma à jour. Vérifie d'abord que la base est bien une base postulat dont les
     * migrations déjà appliquées n'ont pas changé ; sauvegarde ensuite la base si elle contient des
     * données — pas de sauvegarde, pas de migration (UC-17 E2) ; applique enfin chaque migration en
     * attente dans sa propre transaction.
     */
    void appliquer(Connection connexion, Clock horloge, Runnable sauvegarde) throws SQLException {
        boolean neuve = !tableExiste(connexion, "migration");
        if (neuve && tableExiste(connexion, null)) {
            throw new ErreurStockage("Ce fichier n'est pas une base postulat : il contient des tables "
                    + "mais aucun historique de migrations. Il n'a pas été modifié.");
        }
        if (neuve) {
            try (Statement s = connexion.createStatement()) {
                s.executeUpdate("CREATE TABLE migration (version INTEGER PRIMARY KEY, nom TEXT NOT NULL, "
                        + "empreinte TEXT NOT NULL, appliquee_le TEXT NOT NULL)");
            }
        }
        int appliquees = verifierHistorique(connexion);
        if (appliquees == migrations.size()) {
            return;
        }
        if (appliquees > 0) {
            sauvegarde.run();
        }
        for (Migration m : migrations.subList(appliquees, migrations.size())) {
            executer(connexion, m, horloge.instant());
        }
    }

    // --- interne ---

    /** Renvoie le nombre de migrations appliquées, après avoir vérifié qu'elles sont toutes connues et intactes. */
    private int verifierHistorique(Connection connexion) throws SQLException {
        Map<Integer, String> empreintes = new HashMap<>();
        try (Statement s = connexion.createStatement();
                ResultSet r = s.executeQuery("SELECT version, empreinte FROM migration ORDER BY version")) {
            while (r.next()) {
                empreintes.put(r.getInt(1), r.getString(2));
            }
        }
        for (var appliquee : empreintes.entrySet()) {
            int version = appliquee.getKey();
            if (version > migrations.size()) {
                throw new ErreurStockage("La base est au schéma v" + version + ", plus récent que celui de ce "
                        + "programme (v" + migrations.size() + ") : utilisez une version plus récente de postulat.");
            }
            Migration connue = migrations.get(version - 1);
            if (!connue.empreinte().equals(appliquee.getValue())) {
                throw new ErreurStockage("La migration " + connue.nom() + " a été modifiée après avoir été "
                        + "appliquée. Une migration appliquée ne se modifie jamais : ajoutez-en une suivante.");
            }
        }
        return empreintes.size();
    }

    private static void executer(Connection connexion, Migration m, Instant maintenant) throws SQLException {
        connexion.setAutoCommit(false);
        try (Statement s = connexion.createStatement();
                PreparedStatement trace = connexion.prepareStatement(
                        "INSERT INTO migration (version, nom, empreinte, appliquee_le) VALUES (?, ?, ?, ?)")) {
            s.executeUpdate(m.sql());
            trace.setInt(1, m.version());
            trace.setString(2, m.nom());
            trace.setString(3, m.empreinte());
            trace.setString(4, maintenant.toString());
            trace.executeUpdate();
            connexion.commit();
        } catch (SQLException e) {
            connexion.rollback();
            throw new ErreurStockage("La migration " + m.nom() + " a échoué et a été annulée : "
                    + e.getMessage(), e);
        } finally {
            connexion.setAutoCommit(true);
        }
    }

    /** Vrai si la table nommée existe ; avec {@code null}, si une table quelconque existe. */
    private static boolean tableExiste(Connection connexion, String nom) throws SQLException {
        String sql = "SELECT count(*) FROM sqlite_master WHERE type = 'table'" + (nom == null ? "" : " AND name = ?");
        try (PreparedStatement s = connexion.prepareStatement(sql)) {
            if (nom != null) {
                s.setString(1, nom);
            }
            try (ResultSet r = s.executeQuery()) {
                return r.next() && r.getInt(1) > 0;
            }
        }
    }
}
