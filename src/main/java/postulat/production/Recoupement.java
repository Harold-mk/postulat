package postulat.production;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Un fait à verser redit-il un fait du profil (UC-04 A2) ? La détection se fait sans modèle, donc
 * grossièrement : par les mots significatifs que les deux partagent. Elle ne décide rien. Elle signale,
 * et l'auteur choisit d'enrichir le fait existant ou d'en créer un second.
 */
public final class Recoupement {

    /** Des mots trop courants pour dire de quoi parle une phrase. */
    private static final Set<String> VIDES = Set.of(
            "avec", "dans", "pour", "chaque", "leur", "leurs", "sous", "plus", "moins", "entre", "cette", "tout", "tous",
            "toute", "toutes", "aussi", "ainsi", "depuis", "apres", "avant", "sans", "chez", "vers", "par", "mise", "place",
            "fait", "faire", "dont", "elle", "elles", "nous", "vous", "votre", "notre", "mon", "mes", "son", "ses");

    private Recoupement() {}

    /**
     * La réalisation de {@code parent} que {@code texte} semble redire : celle avec qui il partage au moins
     * deux mots significatifs, qui font au moins la moitié des mots du plus court des deux.
     */
    public static Optional<Realisation> realisationProche(Profil profil, String parent, String texte) {
        Set<String> nouveaux = motsSignificatifs(texte);
        return profil.realisationsDe(parent).stream()
                .filter(r -> recoupent(nouveaux, motsSignificatifs(r.objectif() + " " + r.tache() + " " + r.resultat())))
                .findFirst();
    }

    /** La compétence du profil qui porte déjà ce nom, casse, accents et ponctuation mis à part. */
    public static Optional<Competence> competenceHomonyme(Profil profil, String nom) {
        return profil.competences().stream().filter(c -> Mentions.cle(c.nom()).equals(Mentions.cle(nom))).findFirst();
    }

    static boolean recoupent(Set<String> a, Set<String> b) {
        Set<String> communs = new HashSet<>(a);
        communs.retainAll(b);
        return communs.size() >= 2 && 2 * communs.size() >= Math.min(a.size(), b.size());
    }

    /** Les mots qui portent le sens : sans accents ni casse, d'au moins quatre lettres ou chiffrés, hors mots vides. */
    static Set<String> motsSignificatifs(String texte) {
        String net = Normalizer.normalize(texte, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return Arrays.stream(net.split("[^\\p{L}\\p{N}]+"))
                .filter(m -> m.length() >= 4 || m.chars().anyMatch(Character::isDigit))
                .filter(m -> !VIDES.contains(m))
                .collect(Collectors.toSet());
    }
}
