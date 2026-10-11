package postulat.production;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Le profil : l'ensemble des faits vérifiés sur le candidat, source unique de vérité (glossaire
 * § 4.1). Le modèle de langage peut réordonner et reformuler ces faits ; il n'en crée aucun (RG-01).
 *
 * <p>Un profil se construit même incomplet : c'est {@link #anomalies()} qui dit s'il est utilisable,
 * en listant tout ce qui manque d'un coup plutôt qu'en s'arrêtant au premier manque (CA-1.2).
 */
public record Profil(
        Identite identite,
        List<Experience> experiences,
        List<Formation> formations,
        List<Competence> competences) {

    /** Valeur que le gabarit et l'import laissent à la place de ce qui reste à écrire (CA-1.3). */
    public static final String A_COMPLETER = "à compléter";

    /** Stable et citable tel quel par un modèle de langage : minuscules, chiffres, séparés par - . ou _. */
    private static final Pattern IDENTIFIANT = Pattern.compile("[a-z0-9]+([._-][a-z0-9]+)*");

    public Profil {
        Objects.requireNonNull(identite);
        experiences = List.copyOf(experiences);
        formations = List.copyOf(formations);
        competences = List.copyOf(competences);
    }

    /** Tous les faits, réalisations comprises, dans l'ordre du profil. */
    public List<Fait> faits() {
        List<Fait> faits = new ArrayList<>();
        for (Experience e : experiences) {
            faits.add(e);
            faits.addAll(e.realisations());
        }
        for (Formation f : formations) {
            faits.add(f);
            faits.addAll(f.realisations());
        }
        faits.addAll(competences);
        return faits;
    }

    public Optional<Fait> fait(String id) {
        return faits().stream().filter(f -> f.id().equals(id)).findFirst();
    }

    /** Vrai si rien n'empêche d'utiliser le profil : aucun manque, aucun identifiant en double. */
    public boolean estComplet() {
        return anomalies().isEmpty();
    }

    /**
     * Tout ce qui empêche d'utiliser le profil, dans l'ordre du profil : champ obligatoire vide, valeur
     * laissée « à compléter », identifiant invalide ou déjà pris (RG-25 : toute la traçabilité repose
     * sur leur unicité).
     */
    public List<Anomalie> anomalies() {
        Controle c = new Controle();
        c.obligatoire("identite.nom", identite.nom());
        c.obligatoire("identite.titre", identite.titre());
        c.obligatoire("identite.contact", identite.contact());
        c.facultatif("identite.lieu", identite.lieu());

        for (int i = 0; i < experiences.size(); i++) {
            Experience e = experiences.get(i);
            String chemin = "experiences[" + i + "]";
            c.identifiant(chemin, e.id());
            c.obligatoire(chemin + ".poste", e.poste());
            c.obligatoire(chemin + ".organisation", e.organisation());
            c.obligatoire(chemin + ".periode", e.periode());
            c.facultatif(chemin + ".duree", e.duree());
            c.facultatif(chemin + ".lieu", e.lieu());
            c.facultatif(chemin + ".consigne", e.consigne());
            c.realisations(chemin, e.realisations());
        }
        for (int i = 0; i < formations.size(); i++) {
            Formation f = formations.get(i);
            String chemin = "formations[" + i + "]";
            c.identifiant(chemin, f.id());
            c.obligatoire(chemin + ".diplome", f.diplome());
            c.obligatoire(chemin + ".etablissement", f.etablissement());
            c.obligatoire(chemin + ".periode", f.periode());
            c.facultatif(chemin + ".details", f.details());
            c.facultatif(chemin + ".consigne", f.consigne());
            c.realisations(chemin, f.realisations());
        }
        for (int i = 0; i < competences.size(); i++) {
            Competence k = competences.get(i);
            String chemin = "competences[" + i + "]";
            c.identifiant(chemin, k.id());
            c.obligatoire(chemin + ".nom", k.nom());
            c.facultatif(chemin + ".niveau", k.niveau());
            c.facultatif(chemin + ".consigne", k.consigne());
        }
        if (faits().isEmpty()) {
            c.anomalies.add(new Anomalie("", "aucun fait : il faut au moins une expérience, une formation ou une compétence."));
        }
        return c.anomalies;
    }

    // --- l'ajout d'un fait (UC-04) ---

    /** Le même profil, la réalisation ajoutée après celles de l'expérience ou de la formation {@code parent}. */
    public Profil avecRealisation(String parent, Realisation r) {
        exigerParent(parent);
        return new Profil(identite,
                experiences.stream().map(e -> !e.id().equals(parent) ? e : new Experience(e.id(), e.poste(), e.organisation(),
                        e.periode(), e.duree(), e.lieu(), e.consigne(), ajoute(e.realisations(), r))).toList(),
                formations.stream().map(f -> !f.id().equals(parent) ? f : new Formation(f.id(), f.diplome(), f.etablissement(),
                        f.periode(), f.details(), f.consigne(), ajoute(f.realisations(), r))).toList(),
                competences);
    }

    /** Le même profil, la compétence ajoutée après les autres. */
    public Profil avecCompetence(Competence c) {
        return new Profil(identite, experiences, formations, ajoute(competences, c));
    }

    /** Le même profil, où la réalisation ou la compétence de même identifiant est remplacée par celle-ci, à sa place. */
    public Profil avecFaitRemplace(Fait nouveau) {
        if (nouveau instanceof Experience || nouveau instanceof Formation) {
            throw new IllegalArgumentException("Seules une réalisation ou une compétence s'enrichissent ainsi.");
        }
        if (fait(nouveau.id()).isEmpty() || fait(nouveau.id()).get().getClass() != nouveau.getClass()) {
            throw new IllegalArgumentException("Aucun fait « " + nouveau.id() + " » de cette sorte à remplacer.");
        }
        return new Profil(identite,
                experiences.stream().map(e -> new Experience(e.id(), e.poste(), e.organisation(), e.periode(), e.duree(), e.lieu(),
                        e.consigne(), remplace(e.realisations(), nouveau))).toList(),
                formations.stream().map(f -> new Formation(f.id(), f.diplome(), f.etablissement(), f.periode(), f.details(),
                        f.consigne(), remplace(f.realisations(), nouveau))).toList(),
                remplace(competences, nouveau));
    }

    /**
     * Un identifiant libre pour une nouvelle réalisation de {@code parent} : « exp-acme-r3 » après deux
     * réalisations, ou le premier suivant qui n'est pris nulle part dans le profil (RG-25, UC-04 E1).
     */
    public String identifiantDeRealisation(String parent) {
        exigerParent(parent);
        int n = realisationsDe(parent).size() + 1;
        while (fait(parent + "-r" + n).isPresent()) {
            n++;
        }
        return parent + "-r" + n;
    }

    /** Un identifiant libre pour une nouvelle compétence : « comp-power-bi », ou « comp-power-bi-2 » s'il est pris. */
    public String identifiantDeCompetence(String nom) {
        String base = "comp-" + Normalizer.normalize(nom, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (base.equals("comp-")) {
            base = "comp";
        }
        String id = base;
        for (int n = 2; fait(id).isPresent(); n++) {
            id = base + "-" + n;
        }
        return id;
    }

    /** Les réalisations de l'expérience ou de la formation {@code parent}. */
    public List<Realisation> realisationsDe(String parent) {
        return experiences.stream().filter(e -> e.id().equals(parent)).findFirst().map(Experience::realisations)
                .or(() -> formations.stream().filter(f -> f.id().equals(parent)).findFirst().map(Formation::realisations))
                .orElse(List.of());
    }

    private void exigerParent(String parent) {
        if (experiences.stream().noneMatch(e -> e.id().equals(parent)) && formations.stream().noneMatch(f -> f.id().equals(parent))) {
            throw new IllegalArgumentException("« " + parent + " » n'est ni une expérience ni une formation du profil.");
        }
    }

    private static <F> List<F> ajoute(List<F> faits, F nouveau) {
        List<F> tous = new ArrayList<>(faits);
        tous.add(nouveau);
        return tous;
    }

    @SuppressWarnings("unchecked")
    private static <F extends Fait> List<F> remplace(List<F> faits, Fait nouveau) {
        return faits.stream().map(f -> f.id().equals(nouveau.id()) && f.getClass() == nouveau.getClass() ? (F) nouveau : f).toList();
    }

    /** Vrai pour « à compléter », sans tenir compte de la casse, des accents ni de la ponctuation finale. */
    public static boolean estACompleter(String valeur) {
        String cle = Normalizer.normalize(valeur, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s.…!]+$", "")
                .replaceAll("\\s+", " ")
                .strip();
        return cle.equals("a completer");
    }

    /** Accumule les anomalies au fil du parcours, et retient le premier usage de chaque identifiant. */
    private static final class Controle {

        final List<Anomalie> anomalies = new ArrayList<>();
        final Map<String, String> premiersUsages = new HashMap<>();

        void obligatoire(String chemin, String valeur) {
            if (valeur.isBlank()) {
                anomalies.add(new Anomalie(chemin, "obligatoire"));
            } else {
                facultatif(chemin, valeur);
            }
        }

        void facultatif(String chemin, String valeur) {
            if (estACompleter(valeur)) {
                anomalies.add(new Anomalie(chemin, A_COMPLETER));
            }
        }

        void identifiant(String cheminDuFait, String id) {
            String chemin = cheminDuFait + ".id";
            if (id.isBlank() || estACompleter(id)) {
                obligatoire(chemin, id);
            } else if (!IDENTIFIANT.matcher(id).matches()) {
                anomalies.add(new Anomalie(chemin, "identifiant « " + id
                        + " » invalide : minuscules et chiffres, séparés par des tirets, par exemple exp-stage-2025"));
            } else if (premiersUsages.containsKey(id)) {
                anomalies.add(new Anomalie(chemin, "identifiant « " + id + " » déjà utilisé par "
                        + premiersUsages.get(id) + " : chaque fait a le sien (RG-25)"));
            } else {
                premiersUsages.put(id, cheminDuFait);
            }
        }

        void realisations(String cheminParent, List<Realisation> realisations) {
            for (int j = 0; j < realisations.size(); j++) {
                Realisation r = realisations.get(j);
                String chemin = cheminParent + ".realisations[" + j + "]";
                identifiant(chemin, r.id());
                obligatoire(chemin + ".objectif", r.objectif());
                obligatoire(chemin + ".tache", r.tache());
                facultatif(chemin + ".resultat", r.resultat());
                facultatif(chemin + ".consigne", r.consigne());
            }
        }
    }
}
