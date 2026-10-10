package postulat.ia;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Prix d'un modèle, en dollars par million de jetons (EN-5). L'écriture en cache est celle de la
 * durée de vie par défaut, cinq minutes : 1,25 fois l'entrée.
 *
 * <p>Ce sont les tarifs publics de l'API au 2026-10-06. Ils changent : un écart avec la facture de la
 * Console se corrige ici. Claude Haiku 5.5 coûte cinq fois plus au-delà de 100 000 jetons d'entrée ;
 * ce palier n'est pas modélisé, aucune demande de postulat n'en approchant.
 */
public record Tarif(BigDecimal entree, BigDecimal sortie, BigDecimal lectureCache, BigDecimal ecritureCache) {

    private static final Map<String, Tarif> CONNUS = Map.of(
            "claude-opus-5-5", de("4.00", "20.00", "0.20", "5.00"),
            "claude-opus-5", de("5.00", "25.00", "0.50", "6.25"),
            "claude-opus-4-8", de("5.00", "25.00", "0.50", "6.25"),
            "claude-sonnet-5-5", de("2.00", "10.00", "0.20", "2.50"),
            "claude-haiku-5-5", de("0.10", "0.50", "0.01", "0.125"),
            "claude-fable-5-1", de("10.00", "50.00", "0.25", "12.50"));

    /** Le tarif d'un modèle connu. Les modèles de repli côté serveur (Opus 5, Opus 4.8) en font partie. */
    public static Optional<Tarif> de(String modele) {
        return Optional.ofNullable(CONNUS.get(modele));
    }

    /** Modèles dont le coût se mesure : les seuls qu'on puisse choisir pour une gamme. */
    public static Set<String> modelesConnus() {
        return new TreeSet<>(CONNUS.keySet());
    }

    /**
     * Coût en micro-dollars : des jetons multipliés par un prix au million donnent directement des
     * millionièmes de dollar. Un entier, pour additionner sans erreur d'arrondi.
     */
    public long microDollars(long entree, long sortie, long lecturesCache, long ecrituresCache) {
        return this.entree.multiply(BigDecimal.valueOf(entree))
                .add(this.sortie.multiply(BigDecimal.valueOf(sortie)))
                .add(lectureCache.multiply(BigDecimal.valueOf(lecturesCache)))
                .add(ecritureCache.multiply(BigDecimal.valueOf(ecrituresCache)))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private static Tarif de(String entree, String sortie, String lecture, String ecriture) {
        return new Tarif(new BigDecimal(entree), new BigDecimal(sortie), new BigDecimal(lecture), new BigDecimal(ecriture));
    }
}
