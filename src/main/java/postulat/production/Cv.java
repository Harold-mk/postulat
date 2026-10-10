package postulat.production;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Un CV produit, tel qu'il sera rendu : l'en-tête, puis des sections dont chaque phrase est une
 * assertion qui porte son origine (CA-3.7). L'en-tête vient de l'identité du profil ; ce n'est pas un
 * fait cité.
 *
 * <p>Chaque puce a un rang de pertinence pour l'offre, unique, 1 étant la plus pertinente (CA-3.12) :
 * c'est le jugement du modèle, rendu explicite. Quand le document ne tient pas sur une page, c'est le
 * code qui retire les dernières, dans cet ordre, et jamais le modèle qui raccourcit (RG-26).
 */
public record Cv(Identite entete, List<Section> sections) {

    public Cv {
        Objects.requireNonNull(entete);
        sections = List.copyOf(sections);
        Set<Integer> rangs = new HashSet<>();
        for (Puce p : puces(sections)) {
            if (!rangs.add(p.rang())) {
                throw new IllegalArgumentException("Deux puces ont le rang de pertinence " + p.rang() + ".");
            }
        }
    }

    /** Une section titrée : « Expériences », « Formation », « Compétences ». */
    public record Section(String titre, List<Contenu> contenus) {

        public Section {
            Objects.requireNonNull(titre);
            contenus = List.copyOf(contenus);
        }
    }

    /** Ce qu'une section contient : des blocs à puces, ou des lignes simples. Liste fermée. */
    public sealed interface Contenu permits Bloc, Ligne {}

    /**
     * Une expérience ou une formation : un intitulé en gras, une précision facultative — dates, lieu —,
     * et des puces. Intitulé et précision sont des assertions comme les autres : leurs dates se vérifient.
     */
    public record Bloc(Assertion intitule, Optional<Assertion> precision, List<Puce> puces) implements Contenu {

        public Bloc {
            Objects.requireNonNull(intitule);
            Objects.requireNonNull(precision);
            puces = List.copyOf(puces);
        }
    }

    /** Une ligne simple : la liste des compétences, les langues. */
    public record Ligne(Assertion assertion) implements Contenu {

        public Ligne {
            Objects.requireNonNull(assertion);
        }
    }

    /** Une puce et son rang de pertinence pour l'offre, 1 étant la plus pertinente (CA-3.12). */
    public record Puce(Assertion assertion, int rang) {

        public Puce {
            Objects.requireNonNull(assertion);
            if (rang < 1) {
                throw new IllegalArgumentException("Un rang de pertinence commence à 1 : " + rang);
            }
        }
    }

    /** Toutes les assertions dans l'ordre de lecture : ce que la vérification de traçabilité examine. */
    public List<Assertion> assertions() {
        List<Assertion> assertions = new ArrayList<>();
        for (Section s : sections) {
            for (Contenu c : s.contenus()) {
                switch (c) {
                    case Bloc b -> {
                        assertions.add(b.intitule());
                        b.precision().ifPresent(assertions::add);
                        b.puces().forEach(p -> assertions.add(p.assertion()));
                    }
                    case Ligne l -> assertions.add(l.assertion());
                }
            }
        }
        return assertions;
    }

    /** Les puces, de la plus pertinente à la moins pertinente. */
    public List<Puce> puces() {
        return puces(sections).stream().sorted(Comparator.comparingInt(Puce::rang)).toList();
    }

    /** La puce la moins pertinente : la première à retirer si le document déborde (RG-26). */
    public Optional<Puce> moinsPertinente() {
        return puces(sections).stream().max(Comparator.comparingInt(Puce::rang));
    }

    /** Le même CV sans cette puce ; les blocs et sections, eux, restent. */
    public Cv sans(Puce retiree) {
        List<Section> restantes = sections.stream()
                .map(s -> new Section(s.titre(), s.contenus().stream()
                        .map(c -> switch (c) {
                            case Bloc b -> (Contenu) new Bloc(b.intitule(), b.precision(),
                                    b.puces().stream().filter(p -> !p.equals(retiree)).toList());
                            case Ligne l -> l;
                        })
                        .toList()))
                .toList();
        return new Cv(entete, restantes);
    }

    private static List<Puce> puces(List<Section> sections) {
        return sections.stream()
                .flatMap(s -> s.contenus().stream())
                .flatMap(c -> c instanceof Bloc b ? b.puces().stream() : java.util.stream.Stream.<Puce>empty())
                .toList();
    }
}
