package postulat.production;

import java.util.List;
import java.util.Objects;

/**
 * Fait structuré en objectif, tâche et résultat (glossaire § 4.1), rattaché à une expérience ou à
 * une formation.
 *
 * @param resultat facultatif : le résultat peut être absent
 * @param competences étiquettes libres, facultatives
 */
public record Realisation(
        String id,
        String objectif,
        String tache,
        String resultat,
        List<String> competences,
        String consigne) implements Fait {

    public Realisation {
        Objects.requireNonNull(id);
        Objects.requireNonNull(objectif);
        Objects.requireNonNull(tache);
        Objects.requireNonNull(resultat);
        competences = List.copyOf(competences);
        Objects.requireNonNull(consigne);
    }

    @Override
    public String texte() {
        return String.join("\n", objectif, tache, resultat, String.join(", ", competences));
    }
}
