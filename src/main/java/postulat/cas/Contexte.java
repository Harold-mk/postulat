package postulat.cas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.function.Function;
import postulat.ia.Modele;
import postulat.ia.ModeleJournalise;
import postulat.stockage.Base;
import postulat.stockage.Donnees;
import postulat.stockage.LectureProfil;
import postulat.stockage.Reglages;

/**
 * Ce dont un cas d'utilisation a besoin, quelle que soit l'interface qui le déclenche : les données,
 * l'horloge, et de quoi parler au modèle.
 *
 * @param fabriqueModele les tests y substituent un faux modèle : aucun appel réel, aucun coût
 */
public record Contexte(Donnees donnees, Clock horloge, Function<Reglages, Modele> fabriqueModele) {

    public Contexte {
        Objects.requireNonNull(donnees);
        Objects.requireNonNull(horloge);
        Objects.requireNonNull(fabriqueModele);
    }

    public LocalDate aujourdhui() {
        return LocalDate.now(horloge);
    }

    /** Le modèle des réglages, dont chaque appel s'inscrit au journal de la base (EN-5, EN-12). */
    public Modele modele(Base base, Reglages reglages) {
        return new ModeleJournalise(fabriqueModele.apply(reglages), base::journaliser, horloge);
    }

    /**
     * Le profil, s'il peut servir à produire un document ; sinon un refus qui dit quoi faire, avant tout
     * appel au modèle (UC-07 E1).
     */
    public LectureProfil profilUtilisable() {
        Path fichier = donnees.fichierProfil();
        if (!Files.exists(fichier)) {
            throw new Refus("Aucun profil : " + fichier + ". Pour en créer un : postulat profil initialiser");
        }
        LectureProfil lecture = donnees.lireProfil(fichier);
        if (!lecture.estUtilisable()) {
            throw new Refus("Le profil a " + lecture.problemes().size() + " problème(s) : rien n'est produit tant qu'ils "
                    + "demeurent. Pour les voir : postulat profil verifier");
        }
        return lecture;
    }

    /** Écrit d'un seul mouvement : un PDF ouvert ailleurs n'est pas laissé à moitié remplacé. */
    public static Path ecrirePdf(Path cible, byte[] pdf) throws IOException {
        Files.createDirectories(cible.getParent());
        Path provisoire = cible.resolveSibling(cible.getFileName() + ".provisoire");
        Files.write(provisoire, pdf);
        try {
            Files.move(provisoire, cible, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(provisoire);
            throw new Refus("Impossible de remplacer " + cible + " : le PDF est-il ouvert dans un lecteur ? Fermez-le, puis relancez.");
        }
        return cible;
    }
}
