package postulat.stockage;

import postulat.suivi.Candidature;

/**
 * Une candidature et son numéro de stockage, poignée courte pour la ligne de commande. Le numéro
 * n'appartient pas au domaine : l'identité d'une candidature reste son UUID.
 */
public record Numerotee(long numero, Candidature candidature) {}
