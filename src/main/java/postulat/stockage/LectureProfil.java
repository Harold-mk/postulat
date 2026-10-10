package postulat.stockage;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import postulat.production.Profil;

/**
 * Ce que donne la lecture d'un fichier profil (UC-03) : le profil s'il a pu être construit, et tous
 * ses problèmes, de syntaxe, de structure ou de contenu, dans l'ordre du fichier.
 *
 * @param empreinte SHA-256 du contenu lu, en hexadécimal : un dossier retient de quel profil il est issu
 * @param profil absent seulement si le fichier n'a pas pu être lu comme un profil (syntaxe, fichier vide)
 */
public record LectureProfil(Path fichier, String empreinte, Optional<Profil> profil, List<Probleme> problemes) {

    /** Un problème situé dans le fichier. Lignes et colonnes comptées à partir de 1. */
    public record Probleme(int ligne, int colonne, String chemin, String message) {}

    public LectureProfil {
        problemes = List.copyOf(problemes);
    }

    /** Vrai si le profil peut servir à produire un dossier : lu, et sans aucun problème. */
    public boolean estUtilisable() {
        return profil.isPresent() && problemes.isEmpty();
    }
}
