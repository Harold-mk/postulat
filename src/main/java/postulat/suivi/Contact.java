package postulat.suivi;

import java.util.Objects;

/**
 * Personne identifiée chez l'entreprise, rattachée à une candidature (CA-8.1).
 *
 * <p>Un contact n'est pas un événement : il ne dit pas ce qui s'est passé, mais à qui l'on parle. Il
 * n'entre donc pas dans le journal, dont l'ordre chronologique strict (RG-24) interdirait sinon de
 * saisir après coup un échange antérieur à l'ajout du contact.
 *
 * @param role facultatif, vide s'il est inconnu
 * @param coordonnees facultatives, vides si elles sont inconnues
 */
public record Contact(String nom, String role, String coordonnees) {

    public Contact {
        Objects.requireNonNull(role);
        Objects.requireNonNull(coordonnees);
        if (nom == null || nom.isBlank()) {
            throw new RefusMetier("Le nom du contact est obligatoire.");
        }
        nom = nom.strip();
        role = role.strip();
        coordonnees = coordonnees.strip();
    }
}
