package postulat.stockage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.exceptions.Mark;
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;
import postulat.production.Anomalie;
import postulat.production.Competence;
import postulat.production.Experience;
import postulat.production.Formation;
import postulat.production.Identite;
import postulat.production.Profil;
import postulat.production.Realisation;
import postulat.stockage.LectureProfil.Probleme;

/**
 * Lit un profil YAML et situe chacun de ses problèmes à la ligne et à la colonne (UC-03).
 *
 * <p>Le fichier est lu comme un arbre de nœuds, sans conversion de types : « periode: 2025-06 » reste
 * un texte, et aucune classe n'est instanciée depuis le fichier. Le parcours est fait à la main pour
 * signaler les clés inconnues — une faute de frappe sur « organisation » ne doit pas faire disparaître
 * le champ en silence — et pour garder la position de chaque valeur. Les règles de contenu, elles,
 * restent au domaine ({@link Profil#anomalies()}) : ce lecteur ne fait que traduire leurs chemins en
 * positions.
 */
public final class LecteurProfil {

    private static final Set<String> RACINE = Set.of("identite", "experiences", "formations", "competences");
    private static final Set<String> IDENTITE = Set.of("nom", "titre", "contact", "lieu");
    private static final Set<String> EXPERIENCE =
            Set.of("id", "poste", "organisation", "periode", "duree", "lieu", "consigne", "realisations");
    private static final Set<String> REALISATION = Set.of("id", "objectif", "tache", "resultat", "competences", "consigne");
    private static final Set<String> FORMATION =
            Set.of("id", "diplome", "etablissement", "periode", "details", "consigne", "realisations");
    private static final Set<String> COMPETENCE = Set.of("id", "nom", "niveau", "consigne");

    private final Map<String, Mark> positions = new HashMap<>();
    private final List<Probleme> problemes = new ArrayList<>();

    private LecteurProfil() {}

    public static LectureProfil lire(Path fichier) {
        LoadSettings reglages = LoadSettings.builder().setLabel(fichier.getFileName().toString()).build();
        LecteurProfil lecteur = new LecteurProfil();
        Optional<Profil> profil;
        try (InputStream entree = Files.newInputStream(fichier)) {
            Optional<Node> racine = new Compose(reglages).composeInputStream(entree);
            profil = racine.isEmpty() ? lecteur.vide() : lecteur.profil(racine.get());
        } catch (MarkedYamlEngineException e) {
            lecteur.syntaxe(e.getProblemMark().or(e::getContextMark), e.getProblem(), e.getContext());
            profil = Optional.empty();
        } catch (YamlEngineException e) {
            lecteur.syntaxe(Optional.empty(), e.getMessage(), null);
            profil = Optional.empty();
        } catch (IOException e) {
            throw new ErreurStockage("Lecture du profil " + fichier + " impossible : " + e, e);
        }
        profil.ifPresent(p -> p.anomalies().forEach(lecteur::situer));
        lecteur.problemes.sort(Comparator.comparingInt(Probleme::ligne).thenComparingInt(Probleme::colonne));
        return new LectureProfil(fichier, profil, lecteur.problemes);
    }

    // --- structure ---

    private Optional<Profil> vide() {
        problemes.add(new Probleme(1, 1, "", "le fichier est vide. Pour un gabarit commenté : postulat profil initialiser"));
        return Optional.empty();
    }

    private Optional<Profil> profil(Node racine) {
        if (!(racine instanceof MappingNode)) {
            signaler(racine, "", "un profil commence par des rubriques : identite, experiences, formations, competences.");
            return Optional.empty();
        }
        Map<String, Node> rubriques = champs(racine, "", RACINE);
        return Optional.of(new Profil(
                identite(rubriques.get("identite")),
                liste(rubriques.get("experiences"), "experiences", this::experience),
                liste(rubriques.get("formations"), "formations", this::formation),
                liste(rubriques.get("competences"), "competences", this::competence)));
    }

    private Identite identite(Node noeud) {
        if (noeud == null) {
            return Identite.VIDE;
        }
        Map<String, Node> c = champs(noeud, "identite", IDENTITE);
        return new Identite(
                texte(c, "identite", "nom"), texte(c, "identite", "titre"),
                texte(c, "identite", "contact"), texte(c, "identite", "lieu"));
    }

    private Experience experience(Node noeud, String chemin) {
        Map<String, Node> c = champs(noeud, chemin, EXPERIENCE);
        return new Experience(
                texte(c, chemin, "id"), texte(c, chemin, "poste"), texte(c, chemin, "organisation"),
                texte(c, chemin, "periode"), texte(c, chemin, "duree"), texte(c, chemin, "lieu"),
                texte(c, chemin, "consigne"),
                liste(c.get("realisations"), chemin + ".realisations", this::realisation));
    }

    private Realisation realisation(Node noeud, String chemin) {
        Map<String, Node> c = champs(noeud, chemin, REALISATION);
        return new Realisation(
                texte(c, chemin, "id"), texte(c, chemin, "objectif"), texte(c, chemin, "tache"),
                texte(c, chemin, "resultat"), etiquettes(c.get("competences"), chemin + ".competences"),
                texte(c, chemin, "consigne"));
    }

    private Formation formation(Node noeud, String chemin) {
        Map<String, Node> c = champs(noeud, chemin, FORMATION);
        return new Formation(
                texte(c, chemin, "id"), texte(c, chemin, "diplome"), texte(c, chemin, "etablissement"),
                texte(c, chemin, "periode"), texte(c, chemin, "details"), texte(c, chemin, "consigne"),
                liste(c.get("realisations"), chemin + ".realisations", this::realisation));
    }

    private Competence competence(Node noeud, String chemin) {
        Map<String, Node> c = champs(noeud, chemin, COMPETENCE);
        return new Competence(texte(c, chemin, "id"), texte(c, chemin, "nom"), texte(c, chemin, "niveau"),
                texte(c, chemin, "consigne"));
    }

    // --- nœuds ---

    /**
     * Les champs d'une rubrique, par nom. Une clé inconnue ou répétée est signalée à sa ligne : c'est
     * ainsi qu'une faute de frappe se voit au lieu de vider un champ en silence.
     */
    private Map<String, Node> champs(Node noeud, String chemin, Set<String> connus) {
        Map<String, Node> champs = new LinkedHashMap<>();
        if (noeud instanceof ScalarNode s && s.getValue().isBlank()) {
            positions.put(chemin, marque(noeud)); // rubrique vide : le domaine dira ce qui y manque
            return champs;
        }
        if (!(noeud instanceof MappingNode rubrique)) {
            signaler(noeud, chemin, "des champs « nom: valeur » sont attendus ici.");
            return champs;
        }
        positions.put(chemin, marque(noeud));
        for (NodeTuple t : rubrique.getValue()) {
            String cle = t.getKeyNode() instanceof ScalarNode s ? s.getValue() : "";
            String cheminDuChamp = chemin.isEmpty() ? cle : chemin + "." + cle;
            if (!connus.contains(cle)) {
                signaler(t.getKeyNode(), cheminDuChamp, "champ inconnu « " + cle + " ». Champs possibles : "
                        + String.join(", ", connus.stream().sorted().toList()) + ".");
            } else if (champs.containsKey(cle)) {
                signaler(t.getKeyNode(), cheminDuChamp, "champ « " + cle + " » écrit deux fois.");
            } else {
                champs.put(cle, t.getValueNode());
                positions.put(cheminDuChamp, marque(t.getValueNode()));
            }
        }
        return champs;
    }

    /** Un champ texte ; vide s'il est absent, et le domaine dira s'il était obligatoire. */
    private String texte(Map<String, Node> champs, String chemin, String nom) {
        Node noeud = champs.get(nom);
        if (noeud == null) {
            return "";
        }
        if (noeud instanceof ScalarNode s) {
            return s.getValue().strip();
        }
        signaler(noeud, chemin + "." + nom, "une valeur simple est attendue, pas une liste ni des sous-champs.");
        return "";
    }

    private <T> List<T> liste(Node noeud, String chemin, BiFunction<Node, String, T> element) {
        List<T> elements = new ArrayList<>();
        if (noeud == null || noeud instanceof ScalarNode s && s.getValue().isBlank()) {
            return elements;
        }
        if (!(noeud instanceof SequenceNode sequence)) {
            signaler(noeud, chemin, "une liste est attendue : chaque élément commence par « - ».");
            return elements;
        }
        positions.put(chemin, marque(noeud));
        for (int i = 0; i < sequence.getValue().size(); i++) {
            Node n = sequence.getValue().get(i);
            String cheminElement = chemin + "[" + i + "]";
            positions.put(cheminElement, marque(n));
            elements.add(element.apply(n, cheminElement));
        }
        return elements;
    }

    /** Des étiquettes : une liste [SQL, Python], ou une simple ligne séparée par des virgules. */
    private List<String> etiquettes(Node noeud, String chemin) {
        if (noeud instanceof ScalarNode s) {
            return Arrays.stream(s.getValue().split(",")).map(String::strip).filter(e -> !e.isEmpty()).toList();
        }
        return liste(noeud, chemin, (n, c) -> {
            if (n instanceof ScalarNode s) {
                return s.getValue().strip();
            }
            signaler(n, c, "une étiquette est un simple mot ou groupe de mots.");
            return "";
        }).stream().filter(e -> !e.isEmpty()).toList();
    }

    // --- positions ---

    /** Situe une anomalie du domaine : à son champ s'il est écrit, sinon à la rubrique qui aurait dû le contenir. */
    private void situer(Anomalie anomalie) {
        String chemin = anomalie.chemin();
        while (!positions.containsKey(chemin) && !chemin.isEmpty()) {
            int coupure = Math.max(chemin.lastIndexOf('.'), chemin.lastIndexOf('['));
            chemin = coupure < 0 ? "" : chemin.substring(0, coupure);
        }
        Mark m = positions.get(chemin);
        problemes.add(new Probleme(m == null ? 1 : m.getLine() + 1, m == null ? 1 : m.getColumn() + 1,
                anomalie.chemin(), anomalie.message()));
    }

    private void signaler(Node noeud, String chemin, String message) {
        Mark m = marque(noeud);
        problemes.add(new Probleme(m == null ? 1 : m.getLine() + 1, m == null ? 1 : m.getColumn() + 1, chemin, message));
    }

    private void syntaxe(Optional<Mark> position, String probleme, String contexte) {
        String message = "syntaxe YAML : " + probleme + (contexte == null || contexte.isBlank() ? "" : " (" + contexte + ")");
        if (probleme != null && probleme.contains("\\t")) {
            message += ". L'indentation se fait avec des espaces, jamais avec des tabulations.";
        }
        problemes.add(new Probleme(position.map(m -> m.getLine() + 1).orElse(1),
                position.map(m -> m.getColumn() + 1).orElse(1), "", message));
    }

    private static Mark marque(Node noeud) {
        return noeud == null ? null : noeud.getStartMark().orElse(null);
    }
}
