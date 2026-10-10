package postulat.production;

import java.util.Objects;

/**
 * Une compétence, un outil, une langue.
 *
 * @param niveau facultatif
 */
public record Competence(String id, String nom, String niveau, String consigne) implements Fait {

    public Competence {
        Objects.requireNonNull(id);
        Objects.requireNonNull(nom);
        Objects.requireNonNull(niveau);
        Objects.requireNonNull(consigne);
    }

    @Override
    public String texte() {
        return String.join("\n", nom, niveau);
    }
}
