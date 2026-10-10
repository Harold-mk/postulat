package postulat.production;

/**
 * Une assertion qui rompt la garantie d'exactitude. Ce n'est pas un avertissement : une seule suffit
 * à faire échouer la génération (CA-3.2).
 *
 * @param assertion position de l'assertion fautive, à partir de 0
 * @param regle la règle rompue : RG-01, RG-03, RG-04, ou CA-3.1 pour une assertion qui ne cite rien
 */
public record Violation(int assertion, String regle, String message) {}
