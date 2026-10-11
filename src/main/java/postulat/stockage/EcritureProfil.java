package postulat.stockage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.CollectionNode;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;
import postulat.production.Competence;
import postulat.production.Experience;
import postulat.production.Fait;
import postulat.production.Formation;
import postulat.production.Identite;
import postulat.production.Profil;
import postulat.production.Realisation;

/**
 * Écrit dans le profil YAML un fait versé depuis un dossier (UC-04), en touchant le moins possible au
 * fichier : le texte du fait est inséré à sa place, et tout le reste — commentaires, ordre, mise en
 * forme — demeure tel que l'auteur l'a écrit.
 *
 * <p>Le profil est la seule source de faits : y écrire demande des précautions.
 *
 * <ul>
 *   <li>Le nouveau texte est relu avant de remplacer l'ancien, et doit redonner exactement le profil
 *       attendu, l'ancien plus le fait, sans le moindre problème. Sinon rien n'est écrit, et le texte à
 *       ajouter à la main est rendu.
 *   <li>L'ancienne version est d'abord copiée, datée, dans le répertoire d'historique (CA-1.6).
 *   <li>Le remplacement se fait d'un seul mouvement : jamais de profil à moitié écrit.
 * </ul>
 */
public final class EcritureProfil {

    /** Ce qu'une écriture a donné : le profil relu, et où son ancienne version a été conservée. */
    public record Ecriture(Profil profil, Path ancienneVersion) {}

    private static final Pattern IDENTIFIANT = Pattern.compile("[a-z0-9]+([._-][a-z0-9]+)*");
    private static final DateTimeFormatter HORODATAGE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final Path fichier;
    private final Path historique;
    private final Instant le;

    /**
     * @param historique le répertoire où l'ancienne version est conservée avant chaque écriture
     * @param le l'instant de l'écriture, qui date la copie
     */
    public EcritureProfil(Path fichier, Path historique, Instant le) {
        this.fichier = fichier;
        this.historique = historique;
        this.le = le;
    }

    /** Ajoute une réalisation après celles de l'expérience ou de la formation {@code parent}. */
    public Ecriture ajouterRealisation(String parent, Realisation r) {
        List<Champ> champs = champs(r, false);
        return ecrire(p -> p.avecRealisation(parent, r),
                t -> t.ajouterAuParent(parent, champs),
                "sous l'expérience ou la formation « " + parent + " », dans « realisations » :", champs);
    }

    /** Ajoute une compétence après les autres. */
    public Ecriture ajouterCompetence(Competence c) {
        List<Champ> champs = champs(c, false);
        return ecrire(p -> p.avecCompetence(c), t -> t.ajouterCompetence(champs), "dans « competences » :", champs);
    }

    /** Remplace une réalisation ou une compétence existante par celle-ci, de même identifiant : seuls les champs changés sont réécrits. */
    public Ecriture remplacer(Fait nouveau) {
        List<Champ> tous = switch (nouveau) {
            case Realisation r -> champs(r, true);
            case Competence c -> champs(c, true);
            default -> throw new IllegalArgumentException("Seules une réalisation ou une compétence s'enrichissent ainsi.");
        };
        return ecrire(p -> p.avecFaitRemplace(nouveau), t -> t.remplacer(nouveau.id(), tous),
                "à la place du fait « " + nouveau.id() + " » :", tous.stream().filter(c -> !c.vide()).toList());
    }

    /**
     * Écrit un profil neuf, tiré d'un CV (UC-02), sous {@code cible} — qui ne doit pas exister : un profil
     * ne s'écrase jamais (A2). Le fichier s'ouvre sur un avertissement : il est à relire ligne à ligne.
     *
     * @param origine ce qui a produit ce profil, pour l'en-tête : « importé de « cv.pdf » le 11/10/2026 »
     */
    public static void nouveau(Path cible, Profil profil, String origine) {
        String n = System.lineSeparator();
        StringBuilder y = new StringBuilder();
        y.append("# Profil ").append(origine).append('.').append(n)
                .append('#').append(n)
                .append("# RELISEZ CHAQUE LIGNE : ce fichier devient la seule source de faits de vos candidatures.").append(n)
                .append("# Le modèle n'a fait que ranger ce que dit le CV, et a pu se tromper de case. Ce qu'il n'y a pas").append(n)
                .append("# trouvé est marqué « à compléter » : écrivez-le, ou retirez l'élément.").append(n)
                .append("# Vérifiez ensuite avec :  postulat profil verifier").append(n).append(n);
        Identite i = profil.identite();
        y.append("identite:").append(n);
        ligne(y, "  ", "nom", i.nom(), true);
        ligne(y, "  ", "titre", i.titre(), true);
        ligne(y, "  ", "contact", i.contact(), true);
        ligne(y, "  ", "lieu", i.lieu(), false);
        y.append(n).append("experiences:").append(profil.experiences().isEmpty() ? " []" : "").append(n);
        for (Experience e : profil.experiences()) {
            y.append("  - id: ").append(e.id()).append(n);
            ligne(y, "    ", "poste", e.poste(), true);
            ligne(y, "    ", "organisation", e.organisation(), true);
            ligne(y, "    ", "periode", e.periode(), true);
            ligne(y, "    ", "duree", e.duree(), false);
            ligne(y, "    ", "lieu", e.lieu(), false);
            realisations(y, e.realisations());
        }
        y.append(n).append("formations:").append(profil.formations().isEmpty() ? " []" : "").append(n);
        for (Formation f : profil.formations()) {
            y.append("  - id: ").append(f.id()).append(n);
            ligne(y, "    ", "diplome", f.diplome(), true);
            ligne(y, "    ", "etablissement", f.etablissement(), true);
            ligne(y, "    ", "periode", f.periode(), true);
            ligne(y, "    ", "details", f.details(), false);
            realisations(y, f.realisations());
        }
        y.append(n).append("competences:").append(profil.competences().isEmpty() ? " []" : "").append(n);
        for (Competence c : profil.competences()) {
            y.append("  - id: ").append(c.id()).append(n);
            ligne(y, "    ", "nom", c.nom(), true);
            ligne(y, "    ", "niveau", c.niveau(), false);
        }

        Path provisoire = cible.resolveSibling(cible.getFileName() + ".provisoire");
        try {
            Files.createDirectories(cible.toAbsolutePath().getParent());
            Files.writeString(provisoire, y, StandardCharsets.UTF_8);
            // sans REPLACE_EXISTING : si un profil est apparu entre-temps, le renommage échoue au lieu de l'écraser
            Files.move(provisoire, cible);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(provisoire);
            } catch (IOException ignoree) {
                // l'erreur d'origine dit déjà l'essentiel
            }
            throw new ErreurStockage("Le profil importé n'a pas pu être écrit sous " + cible + " : " + e + ". Aucun fichier n'a été laissé.", e);
        }
        Optional<Profil> relu = LecteurProfil.lire(cible).profil();
        if (relu.isEmpty() || !relu.get().equals(profil)) {
            throw new ErreurStockage("Le profil écrit sous " + cible + " ne se relit pas comme prévu : vérifiez-le avec postulat profil "
                    + "verifier avant de vous en servir.");
        }
    }

    private static void ligne(StringBuilder y, String retrait, String cle, String valeur, boolean obligatoire) {
        if (obligatoire || !valeur.isBlank()) {
            y.append(retrait).append(cle).append(": ").append(guillemets(valeur)).append(System.lineSeparator());
        }
    }

    private static void realisations(StringBuilder y, List<Realisation> realisations) {
        String n = System.lineSeparator();
        if (realisations.isEmpty()) {
            return;
        }
        y.append("    realisations:").append(n);
        for (Realisation r : realisations) {
            y.append("      - id: ").append(r.id()).append(n);
            ligne(y, "        ", "objectif", r.objectif(), true);
            ligne(y, "        ", "tache", r.tache(), true);
            ligne(y, "        ", "resultat", r.resultat(), false);
            if (!r.competences().isEmpty()) {
                y.append("        competences: ").append(r.competences().stream().map(EcritureProfil::guillemets)
                        .collect(Collectors.joining(", ", "[", "]"))).append(n);
            }
        }
    }

    // --- l'écriture, vérifiée ---

    private Ecriture ecrire(UnaryOperator<Profil> attendu, java.util.function.Function<Texte, String> edition, String ou,
            List<Champ> champs) {
        LectureProfil avant = LecteurProfil.lire(fichier);
        if (!avant.estUtilisable()) {
            throw new ErreurStockage("Le profil a des problèmes : rien n'y est écrit tant qu'ils demeurent (postulat profil verifier).");
        }
        Profil voulu = attendu.apply(avant.profil().orElseThrow());
        Path provisoire = fichier.resolveSibling(fichier.getFileName() + ".provisoire");
        try {
            Texte texte = new Texte(Files.readAllBytes(fichier));
            String nouveau;
            try {
                nouveau = edition.apply(texte);
            } catch (Inserable e) {
                throw aLaMain(e.getMessage(), ou, champs);
            }
            Files.write(provisoire, texte.octets(nouveau));
            LectureProfil apres = LecteurProfil.lire(provisoire);
            if (!apres.estUtilisable() || !apres.profil().orElseThrow().equals(voulu)) {
                Files.deleteIfExists(provisoire);
                throw aLaMain("le texte inséré ne se relisait pas comme prévu", ou, champs);
            }
            Files.createDirectories(historique);
            Path copie = historique.resolve("profil-" + HORODATAGE.format(le) + "-" + avant.empreinte().substring(0, 8) + ".yaml");
            Files.copy(fichier, copie, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(provisoire, fichier, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(provisoire, fichier, StandardCopyOption.REPLACE_EXISTING);
            }
            return new Ecriture(apres.profil().orElseThrow(), copie);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(provisoire);
            } catch (IOException ignoree) {
                // l'erreur d'origine dit déjà l'essentiel
            }
            throw new ErreurStockage("Le profil " + fichier + " n'a pas pu être écrit, il n'a pas été modifié : " + e.getMessage(), e);
        }
    }

    /** Le refus, avec de quoi faire l'ajout soi-même : la sûreté du profil passe avant la commodité. */
    private ErreurStockage aLaMain(String raison, String ou, List<Champ> champs) {
        return new ErreurStockage("Le profil n'a pas été modifié : " + raison + ". Ajoutez vous-même, " + ou + System.lineSeparator()
                + "  - " + champs.stream().map(Champ::yaml).collect(Collectors.joining(System.lineSeparator() + "    ")));
    }

    // --- les champs d'un fait, tels qu'ils s'écrivent ---

    /**
     * Un champ et sa valeur, déjà écrite en YAML : identifiant nu, texte entre guillemets, liste entre crochets.
     *
     * @param brut la valeur telle que le profil la lit : un texte, ou une liste de textes
     */
    private record Champ(String cle, String valeur, Object brut) {

        String yaml() {
            return cle + ": " + valeur;
        }

        boolean vide() {
            return brut instanceof String t ? t.isBlank() : ((List<?>) brut).isEmpty();
        }

        /** Vrai si le nœud du fichier dit déjà exactement cela. */
        boolean dejaDans(Node noeud) {
            if (noeud instanceof ScalarNode s) {
                return s.getValue().equals(brut);
            }
            return noeud instanceof SequenceNode seq && seq.getValue().stream().allMatch(e -> e instanceof ScalarNode)
                    && seq.getValue().stream().map(e -> ((ScalarNode) e).getValue()).toList().equals(brut);
        }
    }

    /** @param tous avec les champs vides : pour remplacer, où un champ vidé doit l'être aussi dans le fichier */
    private static List<Champ> champs(Realisation r, boolean tous) {
        List<Champ> champs = new ArrayList<>();
        champs.add(new Champ("id", identifiant(r.id()), r.id()));
        texte(champs, "objectif", r.objectif(), true);
        texte(champs, "tache", r.tache(), true);
        texte(champs, "resultat", r.resultat(), tous);
        if (tous || !r.competences().isEmpty()) {
            champs.add(new Champ("competences", r.competences().stream().map(EcritureProfil::guillemets)
                    .collect(Collectors.joining(", ", "[", "]")), r.competences()));
        }
        texte(champs, "consigne", r.consigne(), tous);
        return champs;
    }

    private static List<Champ> champs(Competence c, boolean tous) {
        List<Champ> champs = new ArrayList<>();
        champs.add(new Champ("id", identifiant(c.id()), c.id()));
        texte(champs, "nom", c.nom(), true);
        texte(champs, "niveau", c.niveau(), tous);
        texte(champs, "consigne", c.consigne(), tous);
        return champs;
    }

    private static void texte(List<Champ> champs, String cle, String valeur, boolean memeVide) {
        if (memeVide || !valeur.isBlank()) {
            champs.add(new Champ(cle, guillemets(valeur), valeur));
        }
    }

    private static String identifiant(String id) {
        return IDENTIFIANT.matcher(id).matches() ? id : guillemets(id);
    }

    /** Une chaîne YAML entre guillemets doubles : rien de ce qu'elle contient ne peut être pris pour de la syntaxe. */
    static String guillemets(String valeur) {
        StringBuilder s = new StringBuilder("\"");
        valeur.codePoints().forEach(c -> {
            switch (c) {
                case '\\' -> s.append("\\\\");
                case '"' -> s.append("\\\"");
                case '\n' -> s.append("\\n");
                case '\t' -> s.append("\\t");
                default -> {
                    if (c >= 0x20) {
                        s.appendCodePoint(c);
                    }
                }
            }
        });
        return s.append('"').toString();
    }

    // --- le texte du fichier, et ses positions ---

    /** Un endroit où le fichier ne se laisse pas compléter sans risque : l'auteur le fera à la main. */
    private static final class Inserable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Inserable(String message) {
            super(message);
        }
    }

    /**
     * Le texte du profil, son arbre YAML et la position de chaque nœud : de quoi insérer au bon endroit.
     * La marque d'ordre d'octets et les fins de ligne du fichier sont conservées.
     */
    private static final class Texte {

        private final boolean marqueDOrdre;
        private final String texte;
        private final String finDeLigne;
        private final MappingNode racine;

        Texte(byte[] octets) {
            String brut = new String(octets, StandardCharsets.UTF_8);
            marqueDOrdre = brut.startsWith("﻿");
            texte = marqueDOrdre ? brut.substring(1) : brut;
            finDeLigne = texte.contains("\r\n") ? "\r\n" : "\n";
            try {
                Optional<Node> n = new Compose(LoadSettings.builder().build()).composeString(texte);
                if (n.isEmpty() || !(n.get() instanceof MappingNode m)) {
                    throw new Inserable("le fichier n'a pas la forme d'un profil");
                }
                racine = m;
            } catch (YamlEngineException e) {
                throw new Inserable("le fichier ne se lit pas comme du YAML");
            }
        }

        byte[] octets(String nouveau) {
            return ((marqueDOrdre ? "﻿" : "") + nouveau).getBytes(StandardCharsets.UTF_8);
        }

        // --- les trois éditions ---

        String ajouterAuParent(String parent, List<Champ> champs) {
            MappingNode p = parent(parent);
            Optional<Node> realisations = valeur(p, "realisations");
            if (realisations.isEmpty()) {
                return ajouterCle(p, "realisations", champs);
            }
            if (realisations.get() instanceof SequenceNode seq) {
                return ajouterALaSequence(seq, champs);
            }
            throw new Inserable("les réalisations de « " + parent + " » ne sont pas une liste");
        }

        String ajouterCompetence(List<Champ> champs) {
            Optional<Node> competences = valeur(racine, "competences");
            if (competences.isPresent() && competences.get() instanceof SequenceNode seq) {
                return ajouterALaSequence(seq, champs);
            }
            if (competences.isEmpty()) {
                return ajouterCle(racine, "competences", champs);
            }
            throw new Inserable("« competences » n'est pas une liste");
        }

        /** Réécrit les champs qui changent, ajoute ceux qui manquent ; les autres ne bougent pas d'un caractère. */
        String remplacer(String id, List<Champ> tous) {
            MappingNode fait = fait(id);
            List<Edition> editions = new ArrayList<>();
            for (Champ c : tous) {
                Optional<NodeTuple> t = tuple(fait, c.cle());
                if (t.isPresent()) {
                    Node v = t.get().getValueNode();
                    if (c.dejaDans(v)) {
                        continue;
                    }
                    if (v instanceof CollectionNode<?> col && col.getFlowStyle() != FlowStyle.FLOW) {
                        throw new Inserable("le champ « " + c.cle() + " » est une liste sur plusieurs lignes");
                    }
                    editions.add(new Edition(debut(v), fin(v), c.valeur()));
                } else if (!c.vide()) {
                    editions.add(insertionDeCle(fait, List.of(c)));
                }
            }
            return appliquer(editions);
        }

        // --- les insertions ---

        /** Un nouvel élément à la fin d'une liste, dans le style de ses éléments. */
        private String ajouterALaSequence(SequenceNode seq, List<Champ> champs) {
            if (seq.getFlowStyle() == FlowStyle.FLOW) {
                int fermante = fin(seq) - 1;
                exiger(texte.charAt(fermante) == ']', "la liste ne se ferme pas où prévu");
                return appliquer(List.of(new Edition(fermante, fermante, (seq.getValue().isEmpty() ? "" : ", ") + enLigne(champs))));
            }
            List<Node> elements = seq.getValue();
            exiger(!elements.isEmpty(), "liste vide");
            Node premier = elements.get(0);
            int debutPremier = debut(premier);
            int tiret = debutPremier - 1;
            while (tiret >= 0 && texte.charAt(tiret) == ' ') {
                tiret--;
            }
            exiger(tiret >= 0 && texte.charAt(tiret) == '-', "les éléments de la liste ne commencent pas par un tiret");
            int debutLigne = texte.lastIndexOf('\n', tiret) + 1;
            exiger(texte.substring(debutLigne, tiret).isBlank(), "la liste ne commence pas sa ligne");
            String retraitTiret = " ".repeat(tiret - debutLigne);
            String retraitElement = " ".repeat(debutPremier - debutLigne);
            Node dernier = elements.get(elements.size() - 1);
            exiger(dernier instanceof MappingNode, "les éléments de la liste ne sont pas des faits");
            boolean enLigne = ((MappingNode) dernier).getFlowStyle() == FlowStyle.FLOW;
            String element = finDeLigne + retraitTiret + "- "
                    + (enLigne ? enLigne(champs) : enBloc(champs, retraitElement));
            int position = finDeLaLigne(finProfonde(dernier));
            return appliquer(List.of(new Edition(position, position, element)));
        }

        /** Une rubrique nouvelle dans un fait — « realisations » —, ou à la racine — « competences ». */
        private String ajouterCle(MappingNode m, String cle, List<Champ> champs) {
            if (m.getFlowStyle() == FlowStyle.FLOW) {
                int fermante = fin(m) - 1;
                exiger(texte.charAt(fermante) == '}', "le fait ne se ferme pas où prévu");
                return appliquer(List.of(new Edition(fermante, fermante, ", " + cle + ": [" + enLigne(champs) + "]")));
            }
            String retrait = retraitDesCles(m);
            String ajout = finDeLigne + retrait + cle + ":" + finDeLigne + retrait + "  - " + enBloc(champs, retrait + "    ");
            int position = m == racine ? texte.stripTrailing().length() : finDeLaLigne(finProfonde(m));
            return appliquer(List.of(new Edition(position, position, ajout)));
        }

        /** L'insertion d'un ou plusieurs champs dans un fait. */
        private Edition insertionDeCle(MappingNode m, List<Champ> champs) {
            if (m.getFlowStyle() == FlowStyle.FLOW) {
                int fermante = fin(m) - 1;
                exiger(texte.charAt(fermante) == '}', "le fait ne se ferme pas où prévu");
                return new Edition(fermante, fermante, champs.stream().map(c -> ", " + c.yaml()).collect(Collectors.joining()));
            }
            String retrait = retraitDesCles(m);
            int position = finDeLaLigne(finProfonde(m));
            return new Edition(position, position, champs.stream().map(c -> finDeLigne + retrait + c.yaml()).collect(Collectors.joining()));
        }

        private String enLigne(List<Champ> champs) {
            return champs.stream().map(Champ::yaml).collect(Collectors.joining(", ", "{", "}"));
        }

        private String enBloc(List<Champ> champs, String retrait) {
            return champs.stream().map(Champ::yaml).collect(Collectors.joining(finDeLigne + retrait));
        }

        // --- les positions ---

        private record Edition(int debut, int fin, String texte) {}

        /**
         * Applique les éditions de la dernière à la première : chacune laisse intactes les positions des
         * précédentes. Deux insertions au même endroit gardent l'ordre où elles sont données.
         */
        private String appliquer(List<Edition> editions) {
            StringBuilder s = new StringBuilder(texte);
            List<Integer> ordre = new ArrayList<>(java.util.stream.IntStream.range(0, editions.size()).boxed().toList());
            ordre.sort(Comparator.comparingInt((Integer i) -> editions.get(i).debut()).thenComparingInt(i -> i).reversed());
            ordre.forEach(i -> s.replace(editions.get(i).debut(), editions.get(i).fin(), editions.get(i).texte()));
            return s.toString();
        }

        private int debut(Node n) {
            return position(n.getStartMark().orElseThrow(() -> new Inserable("position inconnue")).getIndex());
        }

        private int fin(Node n) {
            return position(n.getEndMark().orElseThrow(() -> new Inserable("position inconnue")).getIndex());
        }

        /** L'analyseur compte en points de code ; Java, en unités UTF-16. */
        private int position(int pointsDeCode) {
            return texte.offsetByCodePoints(0, pointsDeCode);
        }

        /** Où finit vraiment un nœud : son dernier texte, sans les lignes vides ni les commentaires qui suivent. */
        private int finProfonde(Node n) {
            if (n instanceof MappingNode m && m.getFlowStyle() != FlowStyle.FLOW && !m.getValue().isEmpty()) {
                return finProfonde(m.getValue().get(m.getValue().size() - 1).getValueNode());
            }
            if (n instanceof SequenceNode s && s.getFlowStyle() != FlowStyle.FLOW && !s.getValue().isEmpty()) {
                return finProfonde(s.getValue().get(s.getValue().size() - 1));
            }
            return fin(n);
        }

        /** La fin de la ligne où se trouve {@code position}, avant son saut de ligne. */
        private int finDeLaLigne(int position) {
            int saut = position > 0 && texte.charAt(position - 1) == '\n' ? position - 1 : texte.indexOf('\n', position);
            if (saut < 0) {
                return texte.length();
            }
            return saut > 0 && texte.charAt(saut - 1) == '\r' ? saut - 1 : saut;
        }

        private String retraitDesCles(MappingNode m) {
            exiger(!m.getValue().isEmpty(), "fait vide");
            int premiereCle = debut(m.getValue().get(0).getKeyNode());
            return " ".repeat(premiereCle - (texte.lastIndexOf('\n', premiereCle) + 1));
        }

        // --- la navigation ---

        private MappingNode parent(String id) {
            for (String rubrique : List.of("experiences", "formations")) {
                for (MappingNode m : elements(rubrique)) {
                    if (valeur(m, "id").filter(v -> v instanceof ScalarNode s && s.getValue().strip().equals(id)).isPresent()) {
                        return m;
                    }
                }
            }
            throw new Inserable("« " + id + " » est introuvable dans le fichier");
        }

        private MappingNode fait(String id) {
            List<MappingNode> candidats = new ArrayList<>(elements("competences"));
            for (String rubrique : List.of("experiences", "formations")) {
                for (MappingNode m : elements(rubrique)) {
                    valeur(m, "realisations").filter(v -> v instanceof SequenceNode).ifPresent(v -> ((SequenceNode) v).getValue()
                            .forEach(r -> {
                                if (r instanceof MappingNode rm) {
                                    candidats.add(rm);
                                }
                            }));
                }
            }
            return candidats.stream()
                    .filter(m -> valeur(m, "id").filter(v -> v instanceof ScalarNode s && s.getValue().strip().equals(id)).isPresent())
                    .findFirst().orElseThrow(() -> new Inserable("le fait « " + id + " » est introuvable dans le fichier"));
        }

        private List<MappingNode> elements(String rubrique) {
            return valeur(racine, rubrique).filter(v -> v instanceof SequenceNode).map(v -> ((SequenceNode) v).getValue().stream()
                    .filter(e -> e instanceof MappingNode).map(e -> (MappingNode) e).toList()).orElse(List.of());
        }

        private static Optional<Node> valeur(MappingNode m, String cle) {
            return tuple(m, cle).map(NodeTuple::getValueNode);
        }

        private static Optional<NodeTuple> tuple(MappingNode m, String cle) {
            return m.getValue().stream().filter(t -> cle(t).equals(cle)).findFirst();
        }

        private static String cle(NodeTuple t) {
            return t.getKeyNode() instanceof ScalarNode s ? s.getValue() : "";
        }

        private static void exiger(boolean condition, String sinon) {
            if (!condition) {
                throw new Inserable(sinon);
            }
        }
    }
}
