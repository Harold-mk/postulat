package postulat.production;

import java.util.Objects;

/**
 * En-tête du CV. Ce ne sont pas des faits à citer, mais ils doivent être renseignés.
 *
 * @param lieu facultatif
 */
public record Identite(String nom, String titre, String contact, String lieu) {

    public static final Identite VIDE = new Identite("", "", "", "");

    public Identite {
        Objects.requireNonNull(nom);
        Objects.requireNonNull(titre);
        Objects.requireNonNull(contact);
        Objects.requireNonNull(lieu);
    }
}
