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
