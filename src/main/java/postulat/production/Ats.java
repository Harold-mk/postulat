package postulat.production;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Note ATS : la simulation d'un filtre de recrutement par mots-clés (CA-5.1, EN-7).
 *
 * <p>Elle est délibérément stupide, et c'est une qualité : un vrai filtre ATS compare des chaînes. Le
 * rendre sémantique le rendrait moins fidèle à ce qu'il simule (cahier d'analyse § 6.2). Une exigence
 * compte donc comme présente si son libellé figure tel quel dans le texte, en mots entiers, casse et
 * accents mis à part — sans synonyme ni abréviation : « PowerBI » ne vaut pas « Power BI », « ML » ne
 * vaut pas « machine learning ». C'est précisément ce que la note doit révéler, pour que le CV
 * reprenne les mots de l'offre.
 *
 * <p>Le texte est celui réellement extrait du document final, non celui qu'on a voulu y mettre.
 */
public final class Ats {

    /** Une exigence obligatoire pèse deux fois une exigence souhaitée. */
    public static final int POIDS_OBLIGATOIRE = 2;
    public static final int POIDS_SOUHAITEE = 1;

    private Ats() {}

    /**
     * Note du texte face aux exigences de l'offre. Vide s'il n'y a aucune exigence : rien à comparer,
     * donc aucune note plutôt qu'une note trompeuse.
     */
    public static Optional<NoteAts> noter(String texteExtrait, Collection<Exigence> exigences) {
        List<Exigence> distinctes = distinctes(exigences);
        if (distinctes.isEmpty()) {
            return Optional.empty();
        }
        List<Exigence> presentes = new ArrayList<>();
        List<Exigence> absentes = new ArrayList<>();
        int poidsTotal = 0;
        int poidsRetrouve = 0;
        for (Exigence e : distinctes) {
            int poids = e.obligatoire() ? POIDS_OBLIGATOIRE : POIDS_SOUHAITEE;
            poidsTotal += poids;
            if (Mentions.mentionne(texteExtrait, e.libelle())) {
                presentes.add(e);
                poidsRetrouve += poids;
            } else {
                absentes.add(e);
            }
        }
        int note = Math.round(100f * poidsRetrouve / poidsTotal);
        return Optional.of(new NoteAts(note, !texteExtrait.isBlank(), presentes, absentes));
    }

    /**
     * Les exigences absentes du texte qu'un fait couvre pourtant : le CV a ce qu'il faut, mais pas les
     * mots de l'offre. Ce sont les seules qu'une révision peut faire apparaître ; une lacune, elle, ne
     * s'écrit jamais (RG-04).
     */
    public static List<Exigence> aReprendre(NoteAts note, Collection<Exigence> lacunes) {
        return note.absentes().stream()
                .filter(e -> lacunes.stream().noneMatch(l -> Mentions.cle(l.libelle()).equals(Mentions.cle(e.libelle()))))
                .toList();
    }

    /**
     * Une exigence citée deux fois par l'offre ne compte qu'une fois, et obligatoire si elle l'est
     * au moins une fois : un ATS ne gagne rien à la répétition.
     */
    private static List<Exigence> distinctes(Collection<Exigence> exigences) {
        Map<String, Exigence> parCle = new LinkedHashMap<>();
        for (Exigence e : exigences) {
            String cle = Mentions.cle(e.libelle());
            if (cle.isEmpty()) {
                continue; // un libellé fait de ponctuation ne désigne rien qu'un ATS puisse chercher
            }
            parCle.merge(cle, e, (premiere, autre) -> new Exigence(premiere.libelle(), premiere.obligatoire() || autre.obligatoire()));
        }
        return List.copyOf(parCle.values());
    }
}
