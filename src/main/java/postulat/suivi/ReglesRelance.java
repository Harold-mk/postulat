package postulat.suivi;

/**
 * Délais de relance (décision 7 du cadrage, CA-7.10) : configurables, et remplaçables plus tard par le
 * délai mesuré de CA-10.2.
 */
public record ReglesRelance(int delaiJours, int relancesMax) {

    public static final ReglesRelance PAR_DEFAUT = new ReglesRelance(10, 2);

    public ReglesRelance {
        if (delaiJours < 1) {
            throw new IllegalArgumentException("Le délai de relance doit être d'au moins un jour.");
        }
        if (relancesMax < 0) {
            throw new IllegalArgumentException("Le nombre de relances ne peut pas être négatif.");
        }
    }
}
