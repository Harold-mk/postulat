package postulat.production;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Le contenu d'un fait cité, copié au moment où le dossier est figé (RG-16, CA-6.9). Un dossier validé
 * ne garde aucun renvoi vers le profil : le profil peut changer ensuite, ce qui a été envoyé, et ce qui
 * le justifiait, reste lisible tel quel (EN-4).
 *
 * @param fait l'identifiant cité
 * @param contenu tout le contenu du fait, tel que la vérification de traçabilité l'a lu
 */
public record Instantane(String fait, String contenu) {

    public Instantane {
        Objects.requireNonNull(fait);
        Objects.requireNonNull(contenu);
    }

    /**
     * Les faits cités par le dossier, dans l'ordre de leur première citation, avec leur contenu actuel.
     *
     * @throws IllegalArgumentException si un fait cité n'est plus dans le profil : la vérification de
     *     traçabilité aurait dû le refuser avant
     */
    public static List<Instantane> des(Dossier dossier, Profil profil) {
        Set<String> ids = new LinkedHashSet<>();
        for (Assertion a : dossier.assertions()) {
            if (a.origine() instanceof Origine.Faits f) {
                ids.addAll(f.ids());
            }
        }
        List<Instantane> instantanes = new ArrayList<>();
        for (String id : ids) {
            Fait f = profil.fait(id).orElseThrow(() -> new IllegalArgumentException("Le fait « " + id + " » n'est plus dans le profil."));
            instantanes.add(new Instantane(id, f.texte()));
        }
        return instantanes;
    }
}
