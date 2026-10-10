package postulat.ia;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Une demande au modèle. Les consignes sont la partie stable — mise en cache d'une demande à
 * l'autre —, le contenu la partie qui change à chaque fois.
 *
 * @param tache nom court qui identifie l'usage dans le journal : « analyse-offre », « cv-cible »…
 * @param candidature la candidature concernée, pour afficher le coût par candidature (EN-5)
 */
public record Demande(String tache, Gamme gamme, String consignes, String contenu, Optional<UUID> candidature) {

    public Demande {
        Objects.requireNonNull(gamme);
        Objects.requireNonNull(candidature);
        exigerTexte(tache, "La tâche");
        exigerTexte(consignes, "Les consignes");
        exigerTexte(contenu, "Le contenu");
    }

    public static Demande horsCandidature(String tache, Gamme gamme, String consignes, String contenu) {
        return new Demande(tache, gamme, consignes, contenu, Optional.empty());
    }

    private static void exigerTexte(String valeur, String nom) {
        if (valeur == null || valeur.isBlank()) {
            throw new IllegalArgumentException(nom + " d'une demande au modèle ne peut pas être vide.");
        }
    }
}
