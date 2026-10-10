package postulat.production;

import java.util.List;
import java.util.Objects;

/**
 * L'offre face au profil (UC-05, étapes 6 et 7) : chaque exigence couverte ou lacune, et une note
 * d'adéquation justifiée qui précède toute génération (CA-2.6).
 *
 * <p>Le jugement vient du modèle — rapprocher « capacité à modéliser des données » d'un « schéma en
 * étoile avec dbt » ne se fait pas en comparant des chaînes (L-01) —, mais il ne peut citer que des
 * faits du profil : {@link #faitsInconnus} le vérifie, et un rapprochement qui en cite un autre est
 * refusé en entier (RG-01).
 *
 * @param couvertures une par exigence de l'offre, dans le même ordre
 * @param note de 0 à 100
 */
public record Rapprochement(List<Couverture> couvertures, int note, String justification) {

    public Rapprochement {
        couvertures = List.copyOf(couvertures);
        Objects.requireNonNull(justification);
        if (note < 0 || note > 100) {
            throw new IllegalArgumentException("Une note d'adéquation va de 0 à 100 : " + note);
        }
    }

    /** Les exigences qu'aucun fait ne couvre : celles qu'un document produit ne doit jamais mentionner (RG-04). */
    public List<Exigence> lacunes() {
        return couvertures.stream().filter(c -> !c.couverte()).map(Couverture::exigence).toList();
    }

    /** Les identifiants cités qui n'existent pas dans le profil, sans doublon, dans leur ordre d'apparition. */
    public List<String> faitsInconnus(Profil profil) {
        return couvertures.stream()
                .flatMap(c -> c.faits().stream())
                .distinct()
                .filter(id -> profil.fait(id).isEmpty())
                .toList();
    }
}
