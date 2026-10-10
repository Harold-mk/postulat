package postulat.ia;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Double de test du modèle (EN-13) : rend, dans l'ordre, les réponses ou les échecs qu'on lui a
 * préparés, et retient chaque demande reçue. Aucun réseau, aucune clé, aucun coût.
 */
public final class FauxModele implements Modele {

    /** Ce que coûte une réponse du faux : assez pour vérifier une addition, rien de réel. */
    public static final Consommation CONSOMMATION = new Consommation("faux-modele", 100, 50, 0, 0, 1_500, Duration.ofMillis(20));

    private final Deque<Object> suite = new ArrayDeque<>();
    private final List<Demande> demandes = new ArrayList<>();

    public FauxModele repondre(Object valeur) {
        suite.add(valeur);
        return this;
    }

    public FauxModele echouer(ErreurModele erreur) {
        suite.add(erreur);
        return this;
    }

    public List<Demande> demandes() {
        return List.copyOf(demandes);
    }

    @Override
    public <T> Reponse<T> demander(Demande demande, Class<T> forme) {
        demandes.add(demande);
        Object suivant = suite.poll();
        if (suivant == null) {
            throw new IllegalStateException("Le faux modèle n'a plus de réponse préparée pour « " + demande.tache() + " ».");
        }
        if (suivant instanceof ErreurModele erreur) {
            throw erreur;
        }
        return new Reponse<>(forme.cast(suivant), String.valueOf(suivant), CONSOMMATION);
    }
}
