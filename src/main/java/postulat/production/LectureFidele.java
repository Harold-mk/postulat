package postulat.production;

import java.util.List;
import java.util.Set;

/**
 * Ce qu'un modèle a lu d'un document ne dit que ce que le document dit (CA-1.3). Deux vérifications,
 * déterministes et grossières comme il se doit, sur ce qui s'invente le plus vite : un nombre, et un nom
 * propre.
 */
public final class LectureFidele {

    private LectureFidele() {}

    /** Les nombres de {@code texte} que {@code source} ne contient pas ; une date compte par son année (RG-03). */
    public static List<String> nombresAbsents(String texte, String source) {
        Set<String> presents = Nombres.extraire(source);
        return Nombres.extraire(texte).stream().filter(n -> !presents.contains(n)).sorted().toList();
    }

    /** Vrai si le nom figure dans la source, en mots entiers, casse et accents mis à part. */
    public static boolean figure(String nom, String source) {
        return Mentions.cle(nom).isEmpty() || Mentions.mentionne(source, nom);
    }
}
