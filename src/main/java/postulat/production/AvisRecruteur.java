package postulat.production;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * L'avis d'un recruteur exigeant, simulé par le modèle (US-5) : une note par critère de la grille, des
 * forces, des faiblesses et des corrections proposées. La note sur 100 se calcule ici, pas chez le
 * modèle : la grille fait foi.
 */
public record AvisRecruteur(List<NoteCritere> criteres, List<String> forces, List<String> faiblesses, List<String> corrections) {

    /** La note d'un critère, de 0 à 5, et ce qui la justifie. */
    public record NoteCritere(Critere critere, int note, String justification) {

        public NoteCritere {
            Objects.requireNonNull(critere);
            Objects.requireNonNull(justification);
            if (note < 0 || note > 5) {
                throw new IllegalArgumentException("Un critère se note de 0 à 5 : " + critere.libelle() + " = " + note);
            }
        }
    }

    public AvisRecruteur {
        criteres = List.copyOf(criteres);
        forces = List.copyOf(forces);
        faiblesses = List.copyOf(faiblesses);
        corrections = List.copyOf(corrections);
        EnumSet<Critere> notes = EnumSet.noneOf(Critere.class);
        for (NoteCritere n : criteres) {
            if (!notes.add(n.critere())) {
                throw new IllegalArgumentException("Le critère « " + n.critere().libelle() + " » est noté deux fois.");
            }
        }
        if (notes.size() != Critere.values().length) {
            throw new IllegalArgumentException("Tous les critères de la grille se notent : il en manque "
                    + EnumSet.complementOf(notes) + ".");
        }
    }

    /**
     * Le même avis, privé des corrections qui nomment une lacune : les suivre obligerait à écrire un fait
     * absent du profil (RG-07). Les corrections écartées restent dans le rapport, pour mémoire.
     */
    public AvisRecruteur sansCorrectionsSur(Collection<Exigence> lacunes) {
        List<String> ecartees = correctionsSur(lacunes);
        return new AvisRecruteur(criteres, forces, faiblesses, corrections.stream().filter(c -> !ecartees.contains(c)).toList());
    }

    /** Les corrections qui nomment une exigence qu'aucun fait ne couvre. */
    public List<String> correctionsSur(Collection<Exigence> lacunes) {
        return corrections.stream().filter(c -> lacunes.stream().anyMatch(l -> Mentions.mentionne(c, l.libelle()))).toList();
    }

    /** La somme des notes rapportée à 100. */
    public int note() {
        int total = criteres.stream().mapToInt(NoteCritere::note).sum();
        return Math.round(100f * total / (5f * criteres.size()));
    }
}
