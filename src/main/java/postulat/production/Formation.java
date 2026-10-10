package postulat.production;

import java.util.List;
import java.util.Objects;

/**
 * Un diplôme, une certification, une formation suivie. Ses réalisations sont les projets menés
 * pendant la formation.
 *
 * @param details facultatifs : mention, spécialité, matières
 */
public record Formation(
        String id,
        String diplome,
        String etablissement,
        String periode,
        String details,
        String consigne,
        List<Realisation> realisations) implements Fait {

    public Formation {
        Objects.requireNonNull(id);
        Objects.requireNonNull(diplome);
        Objects.requireNonNull(etablissement);
        Objects.requireNonNull(periode);
        Objects.requireNonNull(details);
        Objects.requireNonNull(consigne);
        realisations = List.copyOf(realisations);
    }

    @Override
    public String texte() {
        return String.join("\n", diplome, etablissement, periode, details);
    }
}
