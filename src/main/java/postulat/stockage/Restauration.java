package postulat.stockage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Restauration d'une archive (UC-18), en deux temps. {@link Donnees#preparerRestauration} vérifie
 * tout sur une copie de travail — lisibilité de l'archive, intégrité de la base, version du schéma,
 * concordance de chaque étape avec son journal — avant la moindre écriture dans les données
 * courantes (E1). L'état courant n'est remplacé qu'ensuite, sur confirmation (étapes 3 et 4).
 */
public final class Restauration implements AutoCloseable {

    private final Path archive;
    private final Path baseVerifiee;
    private final int candidatures;
    private final int evenements;
    private boolean appliquee;

    private Restauration(Path archive, Path baseVerifiee, int candidatures, int evenements) {
        this.archive = archive;
        this.baseVerifiee = baseVerifiee;
        this.candidatures = candidatures;
        this.evenements = evenements;
    }

    static Restauration preparer(Path archive, Path repertoireDeTravail, Migrations migrations, Clock horloge) {
        Path copie = repertoireDeTravail.resolve("restauration.db.provisoire");
        try {
            Files.createDirectories(repertoireDeTravail);
            Files.deleteIfExists(copie);
            extraire(archive, copie);
            verifierIntegrite(copie);
            // Une archive d'un schéma antérieur est migrée ici, sur la copie (E2). Pas de sauvegarde
            // avant cette migration : l'archive elle-même en tient lieu.
            try (Base base = Base.ouvrir(copie, migrations, horloge, copieBase -> {})) {
                List<Base.Divergence> divergences = base.verifierCoherence();
                if (!divergences.isEmpty()) {
                    throw new ErreurStockage("étape stockée différente du rejeu de son journal (RG-10) pour "
                            + divergences.stream().map(d -> "n° " + d.numero()).collect(Collectors.joining(", ")) + ".");
                }
                return new Restauration(archive, copie, base.toutes().size(), base.nombreEvenements());
            }
        } catch (IOException | SQLException | RuntimeException e) {
            supprimerSansEchec(copie);
            throw new ErreurStockage("Archive refusée, rien n'a été modifié. " + archive + " : " + e.getMessage(), e);
        }
    }

    public Path archive() {
        return archive;
    }

    public int candidatures() {
        return candidatures;
    }

    public int evenements() {
        return evenements;
    }

    /** Remplace la base par la copie vérifiée, d'un seul mouvement de fichier. */
    void appliquer(Path fichierBase) {
        try {
            Files.move(baseVerifiee, fichierBase, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            appliquee = true;
        } catch (IOException e) {
            throw new ErreurStockage("Remplacement de " + fichierBase + " impossible, la base courante est "
                    + "intacte (la base est-elle ouverte dans un autre programme ?) : " + e, e);
        }
    }

    /** Sans confirmation, la copie de travail est supprimée et rien n'a été modifié (A1). */
    @Override
    public void close() {
        if (!appliquee) {
            supprimerSansEchec(baseVerifiee);
        }
    }

    // --- interne ---

    /** Lire l'entrée jusqu'au bout fait vérifier sa somme de contrôle par le format zip. */
    private static void extraire(Path archive, Path cible) throws IOException {
        try (InputStream fichier = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(fichier)) {
            for (ZipEntry entree = zip.getNextEntry(); entree != null; entree = zip.getNextEntry()) {
                if (entree.getName().equals(Sauvegardes.ENTREE_BASE)) {
                    Files.copy(zip, cible);
                    return;
                }
            }
        }
        throw new ErreurStockage("ce fichier n'est pas une sauvegarde postulat (aucune entrée "
                + Sauvegardes.ENTREE_BASE + ").");
    }

    private static void verifierIntegrite(Path base) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + base.toAbsolutePath());
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("PRAGMA integrity_check")) {
            String resultat = r.next() ? r.getString(1) : "aucun résultat";
            if (!"ok".equals(resultat)) {
                throw new ErreurStockage("base corrompue (" + resultat + ").");
            }
        }
    }

    private static void supprimerSansEchec(Path fichier) {
        try {
            Files.deleteIfExists(fichier);
        } catch (IOException ignoree) {
            // fichier de travail au nom explicite, écrasé à la prochaine restauration
        }
    }
}
