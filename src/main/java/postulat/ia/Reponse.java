package postulat.ia;

import java.util.Objects;

/**
 * La réponse du modèle, déjà dans la forme demandée, avec le JSON exact reçu et ce qu'elle a coûté.
 *
 * @param json le texte brut de la réponse : c'est lui que le journal conserve (EN-12)
 */
public record Reponse<T>(T valeur, String json, Consommation consommation) {

    public Reponse {
        Objects.requireNonNull(valeur);
        Objects.requireNonNull(json);
        Objects.requireNonNull(consommation);
    }
}
