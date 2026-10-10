package postulat.ia;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/**
 * Ce qu'un appel a consommé (EN-5) : jetons par catégorie, coût, durée.
 *
 * @param modele le modèle qui a réellement répondu, qui peut différer du modèle demandé après un
 *     repli côté serveur
 * @param jetonsEntree hors cache : les jetons lus en cache ou écrits en cache sont comptés à part
 */
public record Consommation(
        String modele,
        long jetonsEntree,
        long jetonsSortie,
        long jetonsCacheLus,
        long jetonsCacheEcrits,
        long coutMicroDollars,
        Duration duree) {

    public Consommation {
        Objects.requireNonNull(modele);
        Objects.requireNonNull(duree);
    }

    public BigDecimal coutDollars() {
        return BigDecimal.valueOf(coutMicroDollars, 6);
    }
}
