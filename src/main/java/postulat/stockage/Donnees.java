package postulat.stockage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Le répertoire des données de l'utilisateur : la base, ses sauvegardes et les réglages. Il vit hors
 * du dépôt, dans le répertoire utilisateur du système, ce qui rend le commit d'une donnée personnelle
 * structurellement impossible plutôt que simplement interdit (EN-2, RG-21).
 */
public final class Donnees {

    static final String BASE = "postulat.db";
    static final String SAUVEGARDES = "sauvegardes";
    static final String REGLAGES = "postulat.properties";
    static final String PROFIL = "profil.yaml";
    static final String GABARIT = "/postulat/gabarit-profil.yaml";

    private final Path repertoire;
    private final Clock horloge;
    private final Sauvegardes sauvegardes;

    public Donnees(Path repertoire, Clock horloge) {
        this.repertoire = repertoire.toAbsolutePath();
        this.horloge = horloge;
        this.sauvegardes = new Sauvegardes(this.repertoire.resolve(SAUVEGARDES), horloge);
    }

    /**
     * {@code %LOCALAPPDATA%\postulat} sous Windows, {@code $XDG_DATA_HOME/postulat} ailleurs,
     * {@code ~/.local/share/postulat} à défaut.
     */
    public static Path repertoireParDefaut(Map<String, String> environnement, String systeme, Path maison) {
        if (systeme.toLowerCase(Locale.ROOT).startsWith("windows")) {
            String local = environnement.get("LOCALAPPDATA");
            return local == null || local.isBlank()
                    ? maison.resolve("AppData").resolve("Local").resolve("postulat")
                    : Path.of(local, "postulat");
        }
        String xdg = environnement.get("XDG_DATA_HOME");
        return xdg == null || xdg.isBlank()
                ? maison.resolve(".local").resolve("share").resolve("postulat")
                : Path.of(xdg, "postulat");
    }

    public Path repertoire() {
        return repertoire;
    }

    public Path fichierBase() {
        return repertoire.resolve(BASE);
    }

    public Path repertoireSauvegardes() {
        return sauvegardes.repertoire();
    }

    /** Le profil : la donnée la plus sensible du projet, donc ici et nulle part ailleurs par défaut. */
    public Path fichierProfil() {
        return repertoire.resolve(PROFIL);
    }

    public LectureProfil lireProfil(Path fichier) {
        return LecteurProfil.lire(fichier);
    }

    /**
     * Écrit le gabarit commenté du profil (UC-01). N'écrase jamais un fichier existant (A1), et ne
     * laisse aucun fichier partiel en cas d'échec (E1) : le gabarit prend son nom d'un seul mouvement.
     */
    public void ecrireGabaritProfil(Path cible) {
        Path provisoire = cible.resolveSibling(cible.getFileName() + ".provisoire");
        try (InputStream gabarit = Donnees.class.getResourceAsStream(GABARIT)) {
            if (gabarit == null) {
                throw new IllegalStateException("Gabarit introuvable dans le jar : " + GABARIT);
            }
            Files.createDirectories(cible.toAbsolutePath().getParent());
            Files.copy(gabarit, provisoire, StandardCopyOption.REPLACE_EXISTING);
            // Pas d'ATOMIC_MOVE : sous Windows, il remplace la cible existante sans rien dire. Un simple
            // renommage dans le même répertoire échoue au contraire si un profil est déjà là.
            Files.move(provisoire, cible);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(provisoire);
            } catch (IOException ignoree) {
                // le fichier provisoire porte un nom explicite ; l'erreur d'origine compte davantage
            }
            throw new ErreurStockage("Écriture du gabarit impossible dans " + cible.toAbsolutePath().getParent()
                    + " : " + e + ". Aucun fichier n'a été laissé.", e);
        }
    }

    /** Ouvre la base ; les migrations en attente s'appliquent après une sauvegarde automatique (ADR-0004). */
    public Base ouvrirBase() {
        return Base.ouvrir(fichierBase(), sauvegardes, horloge);
    }

    public Reglages reglages() {
        return Reglages.lire(repertoire.resolve(REGLAGES));
    }

    /** Archive datée de la base (UC-17). Renvoie son chemin. */
    public Path sauvegarder() {
        try (Base base = ouvrirBase()) {
            return sauvegardes.archiver(base::copierVers);
        }
    }

    /** Vérifie une archive sans rien modifier (UC-18, étapes 1 et 2). */
    public Restauration preparerRestauration(Path archive) {
        return Restauration.preparer(archive, repertoire, Migrations.duProjet(), horloge);
    }

    /**
     * Remplace l'état courant par une archive vérifiée (UC-18, étape 5), après avoir sauvegardé cet
     * état : une restauration par erreur reste ainsi réparable. Renvoie cette sauvegarde, s'il y avait
     * un état à sauvegarder.
     */
    public Optional<Path> restaurer(Restauration restauration) {
        Optional<Path> etatRemplace = Files.exists(fichierBase()) ? Optional.of(sauvegarder()) : Optional.empty();
        restauration.appliquer(fichierBase());
        return etatRemplace;
    }
}
