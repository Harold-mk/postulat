package postulat.production;

import java.util.Objects;

/** Une phrase d'un document produit — puce de CV, phrase de lettre — et son origine (CA-3.7). */
public record Assertion(String texte, Origine origine) {

    public Assertion {
        Objects.requireNonNull(origine);
        if (texte == null || texte.isBlank()) {
            throw new IllegalArgumentException("Une assertion a un texte.");
        }
    }
}
