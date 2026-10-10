package postulat.production;

/**
 * Compétence, outil ou qualification demandé par une offre, obligatoire ou souhaité (glossaire § 4.1).
 * Une exigence qu'aucun fait ne couvre est une lacune : elle se signale, elle ne s'écrit jamais (RG-04).
 */
public record Exigence(String libelle, boolean obligatoire) {

    public Exigence {
        if (libelle == null || libelle.isBlank()) {
            throw new IllegalArgumentException("Une exigence a un libellé.");
        }
        libelle = libelle.strip();
    }

    public static Exigence obligatoire(String libelle) {
        return new Exigence(libelle, true);
    }

    public static Exigence souhaitee(String libelle) {
        return new Exigence(libelle, false);
    }
}
