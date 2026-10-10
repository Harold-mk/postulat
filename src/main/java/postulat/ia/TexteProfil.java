package postulat.ia;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import postulat.production.Competence;
import postulat.production.Experience;
import postulat.production.Fait;
import postulat.production.Formation;
import postulat.production.Profil;
import postulat.production.Realisation;

/**
 * Le profil tel que le modèle le lit : un fait par ligne, précédé de l'identifiant qu'il devra citer.
 * C'est la partie stable des demandes, mise en cache d'un appel à l'autre.
 */
final class TexteProfil {

    private TexteProfil() {}

    /**
     * @param avecConsignes ajoute la consigne de présentation de chaque fait qui en a une (CA-1.5) :
     *     utile pour rédiger, inutile pour juger une couverture
     */
    static String de(Profil profil, boolean avecConsignes) {
        List<String> lignes = new ArrayList<>();
        for (Experience e : profil.experiences()) {
            lignes.add(ligne(e, "Expérience : " + joindre(e.poste(), e.organisation(), e.periode(), e.duree(), e.lieu()), avecConsignes));
            e.realisations().forEach(r -> lignes.add(realisation(r, e.id(), avecConsignes)));
        }
        for (Formation f : profil.formations()) {
            lignes.add(ligne(f, "Formation : " + joindre(f.diplome(), f.etablissement(), f.periode(), f.details()), avecConsignes));
            f.realisations().forEach(r -> lignes.add(realisation(r, f.id(), avecConsignes)));
        }
        for (Competence c : profil.competences()) {
            lignes.add(ligne(c, "Compétence : " + joindre(c.nom(), c.niveau()), avecConsignes));
        }
        return String.join("\n", lignes);
    }

    private static String realisation(Realisation r, String parent, boolean avecConsignes) {
        return ligne(r, "Réalisation (dans " + parent + ") : objectif : " + r.objectif() + " ; tâche : " + r.tache()
                + (r.resultat().isEmpty() ? "" : " ; résultat : " + r.resultat())
                + (r.competences().isEmpty() ? "" : " ; compétences : " + String.join(", ", r.competences())), avecConsignes);
    }

    private static String ligne(Fait f, String contenu, boolean avecConsignes) {
        return "[" + f.id() + "] " + contenu + (avecConsignes && !f.consigne().isBlank() ? " (consigne : " + f.consigne() + ")" : "");
    }

    private static String joindre(String... parties) {
        return Arrays.stream(parties).filter(p -> !p.isBlank()).collect(Collectors.joining(" — "));
    }
}
