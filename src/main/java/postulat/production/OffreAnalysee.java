package postulat.production;

import java.util.List;
import java.util.Objects;

/**
 * Lecture structurée d'une offre (CA-2.1, UC-05) : ce que le texte dit, rien de plus. Une information
 * que l'offre ne donne pas reste une chaîne vide, jamais une supposition.
 *
 * @param entreprise vide quand l'offre ne nomme pas l'employeur (cabinet, « notre client »)
 * @param exigences dans l'ordre de l'offre, libellés repris de ses mots exacts : un filtre ATS les cherchera tels quels
 */
public record OffreAnalysee(
        String poste,
        String entreprise,
        String lieu,
        String contrat,
        String debut,
        String duree,
        String niveau,
        List<String> missions,
        List<Exigence> exigences) {

    public OffreAnalysee {
        Objects.requireNonNull(poste);
        Objects.requireNonNull(entreprise);
        Objects.requireNonNull(lieu);
        Objects.requireNonNull(contrat);
        Objects.requireNonNull(debut);
        Objects.requireNonNull(duree);
        Objects.requireNonNull(niveau);
        missions = List.copyOf(missions);
        exigences = List.copyOf(exigences);
    }
}
