package postulat.ia;

/**
 * Le modèle de langage : la seule abstraction du projet, parce que c'est la seule dépendance qu'il faut
 * remplacer par un faux dans les tests (README, règle 3 ; EN-13). Changer de fournisseur ne touche
 * aucun fichier du domaine.
 *
 * <p>Le modèle répond dans une forme imposée : un {@code record} dont le schéma JSON lui est transmis
 * (sorties structurées). Il n'est jamais source de faits (RG-01) : ce qu'il produit passe ensuite par
 * les vérifications déterministes du domaine.
 */
public interface Modele {

    /**
     * Pose la demande et rend la réponse dans la forme voulue.
     *
     * @throws ErreurModele si le modèle est indisponible, refuse, ou rend une réponse inutilisable
     */
    <T> Reponse<T> demander(Demande demande, Class<T> forme);
}
