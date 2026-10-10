package postulat.production;

/**
 * Ce qui règle la génération d'un dossier (CA-5.3, CA-5.4, CA-4.4), configurable.
 *
 * @param seuil note globale, sur 100, au-dessus de laquelle un dossier n'est plus révisé
 * @param poidsAts part de la note ATS dans la note globale, en pourcentage ; le reste va au recruteur
 * @param revisionsMax révisions au plus après la première passe : la révision est bornée
 * @param motsMin longueur minimale de la lettre, en mots
 * @param motsMax longueur maximale de la lettre, en mots
 */
public record ReglesGeneration(int seuil, int poidsAts, int revisionsMax, int motsMin, int motsMax) {

    public static final ReglesGeneration PAR_DEFAUT = new ReglesGeneration(75, 40, 2, 250, 350);

    public ReglesGeneration {
        if (seuil < 0 || seuil > 100) {
            throw new IllegalArgumentException("Le seuil va de 0 à 100 : " + seuil);
        }
        if (poidsAts < 0 || poidsAts > 100) {
            throw new IllegalArgumentException("Le poids de la note ATS va de 0 à 100 : " + poidsAts);
        }
        if (revisionsMax < 0 || revisionsMax > 5) {
            throw new IllegalArgumentException("Les révisions vont de 0 à 5 : " + revisionsMax);
        }
        if (motsMin < 50 || motsMax < motsMin) {
            throw new IllegalArgumentException("La fourchette de mots de la lettre est incohérente : " + motsMin + " à " + motsMax);
        }
    }
}
