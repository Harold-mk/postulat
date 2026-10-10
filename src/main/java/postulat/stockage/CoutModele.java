package postulat.stockage;

import java.math.BigDecimal;

/** Ce qu'ont coûté des appels au modèle (EN-5) : ceux d'une candidature, ou ceux d'un mois. */
public record CoutModele(int appels, long coutMicroDollars) {

    public static final CoutModele AUCUN = new CoutModele(0, 0);

    public BigDecimal coutDollars() {
        return BigDecimal.valueOf(coutMicroDollars, 6);
    }
}
