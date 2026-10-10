package postulat.production;

import java.util.List;
import java.util.Objects;

/**
 * Un poste occupé : stage, alternance, emploi, projet. Ses réalisations sont des faits à part entière,
 * citables séparément.
 *
 * @param duree facultative, mais c'est elle qui justifie « 6 mois » dans un CV : une période ne se
 *     convertit pas en durée par le système
 */
public record Experience(
        String id,
        String poste,
        String organisation,
        String periode,
        String duree,
        String lieu,
        String consigne,
        List<Realisation> realisations) implements Fait {

    public Experience {
        Objects.requireNonNull(id);
        Objects.requireNonNull(poste);
        Objects.requireNonNull(organisation);
        Objects.requireNonNull(periode);
        Objects.requireNonNull(duree);
        Objects.requireNonNull(lieu);
        Objects.requireNonNull(consigne);
        realisations = List.copyOf(realisations);
    }

    @Override
    public String texte() {
        return String.join("\n", poste, organisation, periode, duree, lieu);
    }
}
