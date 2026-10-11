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

    // --- corrections à la main (UC-08) ---

    /** Ce qu'une assertion est dans le CV. */
    public enum Role { INTITULE, PRECISION, PUCE, LIGNE }

    /**
     * Où se trouve une assertion : sa section, son contenu dans la section — bloc ou ligne —, et son rôle.
     * Les indices partent de 0.
     */
    public record Place(int section, int contenu, Role role) {}

    /** La place de l'assertion de rang {@code index} dans l'ordre de lecture, à partir de 0. */
    public Place place(int index) {
        return trouver(index).place();
    }

    /** Une place, et le rang de lecture de la première assertion de son contenu. */
    private record Trouvee(Place place, int debut) {}

    private Trouvee trouver(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("Le CV n'a pas d'assertion n° " + (index + 1) + ".");
        }
        int i = 0;
        for (int s = 0; s < sections.size(); s++) {
            List<Contenu> contenus = sections.get(s).contenus();
            for (int c = 0; c < contenus.size(); c++) {
                switch (contenus.get(c)) {
                    case Bloc b -> {
                        int taille = 1 + (b.precision().isPresent() ? 1 : 0) + b.puces().size();
                        if (index < i + taille) {
                            Role role = index == i ? Role.INTITULE
                                    : b.precision().isPresent() && index == i + 1 ? Role.PRECISION : Role.PUCE;
                            return new Trouvee(new Place(s, c, role), i);
                        }
                        i += taille;
                    }
                    case Ligne l -> {
                        if (index == i) {
                            return new Trouvee(new Place(s, c, Role.LIGNE), i);
                        }
                        i++;
                    }
                }
            }
        }
        throw new IllegalArgumentException("Le CV n'a pas d'assertion n° " + (index + 1) + ".");
    }

    /** La place de cette puce : celle d'une puce retirée, pour qu'elle puisse y revenir. */
    public Optional<Place> place(Puce puce) {
        for (int s = 0; s < sections.size(); s++) {
            List<Contenu> contenus = sections.get(s).contenus();
            for (int c = 0; c < contenus.size(); c++) {
                if (contenus.get(c) instanceof Bloc b && b.puces().contains(puce)) {
                    return Optional.of(new Place(s, c, Role.PUCE));
                }
            }
        }
        return Optional.empty();
    }

    /** Le même CV, l'assertion de rang {@code index} remplacée ; une puce garde son rang de pertinence. */
    public Cv remplacer(int index, Assertion nouvelle) {
        return modifier(index, Optional.of(nouvelle));
    }

    /**
     * Le même CV sans l'assertion de rang {@code index}. Sans son intitulé, un bloc n'a plus de sens : il
     * part en entier, précision et puces comprises.
     */
    public Cv supprimer(int index) {
        return modifier(index, Optional.empty());
    }

    /** Le même CV, la puce remise dans le bloc désigné, à sa place dans l'ordre de pertinence. */
    public Cv avec(Puce puce, int section, int contenu) {
        if (section >= sections.size() || contenu >= sections.get(section).contenus().size()
                || !(sections.get(section).contenus().get(contenu) instanceof Bloc b)) {
            throw new IllegalArgumentException("Le bloc où revenait cette puce n'existe plus.");
        }
        List<Puce> puces = new ArrayList<>(b.puces());
        int position = 0;
        while (position < puces.size() && puces.get(position).rang() < puce.rang()) {
            position++;
        }
        puces.add(position, puce);
        return avecContenu(section, contenu, Optional.of(new Bloc(b.intitule(), b.precision(), puces)));
    }

    private Cv modifier(int index, Optional<Assertion> nouvelle) {
        Trouvee t = trouver(index);
        Place p = t.place();
        Contenu c = sections.get(p.section()).contenus().get(p.contenu());
        Optional<Contenu> remplacant = switch (c) {
            case Ligne l -> nouvelle.map(Ligne::new);
            case Bloc b -> switch (p.role()) {
                case INTITULE -> nouvelle.map(a -> new Bloc(a, b.precision(), b.puces()));
                case PRECISION -> Optional.of(new Bloc(b.intitule(), nouvelle, b.puces()));
                default -> {
                    int k = index - t.debut() - 1 - (b.precision().isPresent() ? 1 : 0);
                    List<Puce> puces = new ArrayList<>(b.puces());
                    if (nouvelle.isPresent()) {
                        puces.set(k, new Puce(nouvelle.get(), puces.get(k).rang()));
                    } else {
                        puces.remove(k);
                    }
                    yield Optional.of(new Bloc(b.intitule(), b.precision(), puces));
                }
            };
        };
        return avecContenu(p.section(), p.contenu(), remplacant);
    }

    /** Le même CV où un contenu est remplacé, ou retiré s'il n'y a pas de remplaçant. */
    private Cv avecContenu(int section, int contenu, Optional<Contenu> remplacant) {
        List<Section> nouvelles = new ArrayList<>(sections);
        List<Contenu> contenus = new ArrayList<>(sections.get(section).contenus());
        if (remplacant.isPresent()) {
            contenus.set(contenu, remplacant.get());
        } else {
            contenus.remove(contenu);
        }
        nouvelles.set(section, new Section(sections.get(section).titre(), contenus));
        return new Cv(entete, nouvelles);
    }

    private static List<Puce> puces(List<Section> sections) {
        return sections.stream()
                .flatMap(s -> s.contenus().stream())
                .flatMap(c -> c instanceof Bloc b ? b.puces().stream() : java.util.stream.Stream.<Puce>empty())
                .toList();
    }
}
