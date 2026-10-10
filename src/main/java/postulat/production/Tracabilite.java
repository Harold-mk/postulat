package postulat.production;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Vérification déterministe de la traçabilité (US-3) : le mécanisme central de la garantie
 * d'exactitude. Fonction pure, sans réseau ni fichier (CA-3.6) : elle s'exécute sur ce que le modèle
 * a produit, et c'est elle, non le modèle, qui décide si une assertion est recevable.
 *
 * <p>Une assertion d'origine « faits » subit toutes les règles, sans assouplissement (CA-3.11). Une
 * assertion d'origine « auteur » n'en subit aucune (CA-3.8) : le risque que la vérification combat
 * n'est pas que l'auteur se trompe sur son propre parcours, c'est que le modèle invente (§ 4.3-e).
 */
public final class Tracabilite {

    private Tracabilite() {}

    /**
     * Toutes les violations des assertions, dans leur ordre. Vide si le document est recevable.
     *
     * @param lacunes les exigences de l'offre qu'aucun fait ne couvre : aucune ne doit apparaître (RG-04)
     */
    public static List<Violation> verifier(List<Assertion> assertions, Profil profil, Collection<String> lacunes) {
        List<Violation> violations = new ArrayList<>();
        for (int i = 0; i < assertions.size(); i++) {
            Assertion a = assertions.get(i);
            switch (a.origine()) {
                case Origine.Auteur auteur -> { } // CA-3.8 : traçable à son auteur, pas au profil
                case Origine.Faits faits -> verifierFaits(i, a.texte(), faits, profil, lacunes, violations);
            }
        }
        return violations;
    }

    // --- interne ---

    private static void verifierFaits(
            int i, String texte, Origine.Faits faits, Profil profil, Collection<String> lacunes, List<Violation> violations) {
        if (faits.ids().isEmpty()) {
            violations.add(new Violation(i, "CA-3.1", "ne cite aucun fait : une assertion d'origine « faits » en cite au moins un."));
        }

        // RG-01 : le profil est la seule source de faits.
        List<Fait> cites = new ArrayList<>();
        List<String> inconnus = new ArrayList<>();
        for (String id : faits.ids()) {
            profil.fait(id).ifPresentOrElse(cites::add, () -> inconnus.add(id));
        }
        if (!inconnus.isEmpty()) {
            violations.add(new Violation(i, "RG-01", "cite " + liste(inconnus) + ", absent(s) du profil."));
        }

        // RG-03 : toute donnée chiffrée figure dans les faits cités.
        Set<String> justifies = new HashSet<>();
        cites.forEach(f -> justifies.addAll(Nombres.extraire(f.texte())));
        List<String> injustifies = Nombres.extraire(texte).stream().filter(n -> !justifies.contains(n)).toList();
        if (!injustifies.isEmpty()) {
            violations.add(new Violation(i, "RG-03", "donnée(s) chiffrée(s) absente(s) des faits cités : "
                    + liste(injustifies) + "."));
        }

        // RG-04 : une lacune se signale, elle ne s'écrit jamais.
        for (String lacune : lacunes) {
            if (mentionne(texte, lacune)) {
                violations.add(new Violation(i, "RG-04", "mentionne « " + lacune
                        + " », exigence qu'aucun fait ne couvre : elle se signale comme lacune, elle ne s'écrit pas."));
            }
        }
    }

    /** Mention en mots entiers, sans tenir compte de la casse ni des accents : « SQL » n'est pas dans « NoSQL ». */
    static boolean mentionne(String texte, String terme) {
        String cherche = cle(terme);
        return !cherche.isEmpty() && (" " + cle(texte) + " ").contains(" " + cherche + " ");
    }

    /** Minuscules sans accents, tout séparateur réduit à une espace ; « + » et « # » gardés pour C++ et C#. */
    private static String cle(String texte) {
        return Normalizer.normalize(texte, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9+#]+", " ")
                .strip();
    }

    private static String liste(Collection<String> elements) {
        return elements.stream().map(e -> "« " + e + " »").collect(Collectors.joining(", "));
    }
}
