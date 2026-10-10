package postulat.stockage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Archives datées de la base (UC-17). Une archive est complète ou n'existe pas : elle s'écrit sous un
 * nom provisoire et ne prend son nom définitif qu'une fois entièrement écrite sur le disque, parce
 * qu'une archive tronquée est pire qu'aucune archive (UC-17 E1).
 */
public final class Sauvegardes {

    /** Nom de la base dans l'archive. Les répertoires de candidature la rejoindront à l'itération 8. */
    static final String ENTREE_BASE = "postulat.db";

    private static final DateTimeFormatter HORODATAGE = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");

    /** Produit à l'emplacement donné une copie cohérente de la base. */
    @FunctionalInterface
    interface CopieBase {
        void copierVers(Path cible) throws SQLException;
    }

    private final Path repertoire;
    private final Clock horloge;

    public Sauvegardes(Path repertoire, Clock horloge) {
        this.repertoire = repertoire;
        this.horloge = horloge;
    }

    public Path repertoire() {
        return repertoire;
    }

    Path archiver(CopieBase copie) {
        String nom = "postulat-" + HORODATAGE.format(LocalDateTime.now(horloge));
        Path copieProvisoire = repertoire.resolve(nom + ".db.provisoire");
        Path archiveProvisoire = repertoire.resolve(nom + ".zip.provisoire");
        try {
            Files.createDirectories(repertoire);
            Path archive = nomLibre(nom);
            Files.deleteIfExists(copieProvisoire);
            Files.deleteIfExists(archiveProvisoire);
            copie.copierVers(copieProvisoire);
            try (FileChannel canal = FileChannel.open(archiveProvisoire,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                OutputStream sortie = Channels.newOutputStream(canal);
                ZipOutputStream zip = new ZipOutputStream(sortie);
                zip.putNextEntry(new ZipEntry(ENTREE_BASE));
                Files.copy(copieProvisoire, zip);
                zip.closeEntry();
                zip.finish();
                canal.force(true);
            }
            Files.move(archiveProvisoire, archive, StandardCopyOption.ATOMIC_MOVE);
            return archive;
        } catch (IOException | SQLException e) {
            throw new ErreurStockage("Sauvegarde impossible dans " + repertoire + " : " + e.getMessage()
                    + ". Aucune archive incomplète n'a été laissée.", e);
        } finally {
            supprimerSansEchec(copieProvisoire);
            supprimerSansEchec(archiveProvisoire);
        }
    }

    // --- interne ---

    /** Deux sauvegardes dans la même seconde, par exemple avant une migration puis à la main, ne s'écrasent pas. */
    private Path nomLibre(String nom) {
        Path archive = repertoire.resolve(nom + ".zip");
        for (int i = 2; Files.exists(archive); i++) {
            archive = repertoire.resolve(nom + "-" + i + ".zip");
        }
        return archive;
    }

    private static void supprimerSansEchec(Path fichier) {
        try {
            Files.deleteIfExists(fichier);
        } catch (IOException ignoree) {
            // un fichier provisoire resté en place ne porte pas de nom d'archive : il ne trompe personne
        }
    }
}
