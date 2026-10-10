package postulat.production;

/**
 * Ce qui empêche d'utiliser le profil, situé par son chemin dans la structure — par exemple
 * {@code experiences[0].realisations[1].objectif}. Le lecteur du fichier traduit ce chemin en
 * ligne et colonne (UC-03).
 *
 * @param chemin vide pour une anomalie qui porte sur le profil entier
 */
public record Anomalie(String chemin, String message) {}
