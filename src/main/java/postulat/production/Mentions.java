package postulat.production;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Recherche d'un terme dans un texte, en mots entiers, sans tenir compte de la casse ni des accents.
 * Une seule règle pour deux usages : une lacune ne doit pas apparaître dans un document (RG-04), et un
 * filtre ATS cherche les exigences de l'offre (CA-5.1).
 *
 * <p>La forme de compatibilité Unicode (NFKD) ramène à l'ordinaire ce qu'une extraction de PDF
 * produit : ligatures « ﬁ », espaces insécables, exposants. La césure conditionnelle est retirée.
 */
final class Mentions {

    private Mentions() {}

    /** « SQL » n'est pas dans « NoSQL » ; « Machine learning » est dans « MACHINE-LEARNING ». */
    static boolean mentionne(String texte, String terme) {
        String cherche = cle(terme);
        return !cherche.isEmpty() && (" " + cle(texte) + " ").contains(" " + cherche + " ");
    }

    /** Minuscules sans accents, tout séparateur réduit à une espace ; « + » et « # » gardés pour C++ et C#. */
    static String cle(String texte) {
        return Normalizer.normalize(texte.replace("­", ""), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9+#]+", " ")
                .strip();
    }
}
