package postulat.ia;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import postulat.production.Competence;
import postulat.production.Experience;
import postulat.production.Formation;
import postulat.production.Identite;
import postulat.production.LectureFidele;
import postulat.production.Profil;
import postulat.production.Realisation;

/**
 * P1 : tirer d'un CV existant un profil pré-rempli (UC-02). Gamme économique : c'est de l'extraction.
 *
 * <p>Le modèle range ; il ne devine pas (CA-1.3). Ce qu'il ne trouve pas, il le laisse vide, et le code
 * l'écrit « à compléter ». Le code vérifie aussi ce qui s'invente le plus vite : un nombre absent du CV, ou
 * un nom d'organisation, d'établissement ou de compétence qu'on n'y lit pas, est remplacé par « à
 * compléter », et signalé. Les identifiants viennent du code, jamais du modèle. Puis l'auteur relit
 * chaque ligne : ce fichier devient la seule source de faits.
 */
public final class ImportCv {

    // --- la forme imposée à la réponse ---

    public record ProfilLu(
            @JsonPropertyDescription("L'en-tête du CV") IdentiteLue identite,
            @JsonPropertyDescription("Stages, alternances, emplois, projets, dans l'ordre du CV") List<ExperienceLue> experiences,
            @JsonPropertyDescription("Diplômes et formations, dans l'ordre du CV") List<FormationLue> formations,
            @JsonPropertyDescription("Compétences, outils et langues, une par élément") List<CompetenceLue> competences) {}

    public record IdentiteLue(
            @JsonPropertyDescription("Prénom et nom") String nom,
            @JsonPropertyDescription("Le titre ou l'accroche sous le nom ; vide s'il n'y en a pas") String titre,
            @JsonPropertyDescription("Coordonnées telles qu'écrites : courriel, téléphone, séparés par « · »") String contact,
            @JsonPropertyDescription("Ville ; vide si absente") String lieu) {}

    public record ExperienceLue(
            @JsonPropertyDescription("Intitulé du poste, tel qu'écrit") String poste,
            @JsonPropertyDescription("Entreprise ou organisation, telle qu'écrite") String organisation,
            @JsonPropertyDescription("Dates telles qu'écrites, ex. « 2025-01 à 2025-06 » ; vide si absentes") String periode,
            @JsonPropertyDescription("Durée seulement si le CV l'écrit, ex. « 6 mois » ; sinon vide") String duree,
            @JsonPropertyDescription("Lieu ; vide si absent") String lieu,
            @JsonPropertyDescription("Une par puce ou réalisation décrite") List<RealisationLue> realisations) {}

    public record RealisationLue(
            @JsonPropertyDescription("Pourquoi, seulement si le CV le dit ; sinon vide") String objectif,
            @JsonPropertyDescription("Ce qui a été fait, avec les mots du CV") String tache,
            @JsonPropertyDescription("L'effet obtenu, seulement si le CV le dit ; sinon vide") String resultat,
            @JsonPropertyDescription("Outils ou compétences que cette puce nomme") List<String> competences) {}

    public record FormationLue(
            @JsonPropertyDescription("Diplôme ou formation, tel qu'écrit") String diplome,
            @JsonPropertyDescription("Établissement, tel qu'écrit") String etablissement,
            @JsonPropertyDescription("Dates telles qu'écrites ; vide si absentes") String periode,
            @JsonPropertyDescription("Spécialité, mention, matières ; vide si absent") String details,
            @JsonPropertyDescription("Projets menés pendant la formation, s'il y en a") List<RealisationLue> realisations) {}

    public record CompetenceLue(
            @JsonPropertyDescription("Le nom, tel qu'écrit : « SQL », « Anglais »") String nom,
            @JsonPropertyDescription("Le niveau, seulement si le CV l'écrit ; sinon vide") String niveau) {}

    /**
     * Ce que l'import a donné.
     *
     * @param remplacements ce que le code a remplacé par « à compléter », et pourquoi : l'auteur doit le savoir
     */
    public record Import(Profil profil, List<String> remplacements, Consommation consommation) {

        public Import {
            Objects.requireNonNull(profil);
            remplacements = List.copyOf(remplacements);
            Objects.requireNonNull(consommation);
        }
    }

    /** L'organisation d'un projet mené seul : la consigne la nomme ainsi, le CV ne l'écrit pas forcément. */
    static final String PROJET_PERSONNEL = "Projet personnel";

    static final String CONSIGNES = """
            Tu ranges le contenu d'un CV dans une structure : identité, expériences, formations, compétences.

            Règle absolue : tu n'écris que ce que le CV dit. Tu ne devines rien, tu ne complètes rien, tu n'embellis
            rien. Une information absente reste une chaîne vide ; le programme la marquera « à compléter », et le
            candidat l'écrira lui-même. Ce profil deviendra la seule source de vérité de ses candidatures.

            - Reprends les mots du CV : noms d'entreprises, d'établissements, de diplômes, d'outils, tels qu'écrits.
            - Une expérience par poste ; ses réalisations, une par puce ou par phrase qui décrit ce qui a été fait.
            - objectif : le but d'une réalisation, seulement si le CV l'énonce. Le plus souvent, il ne l'énonce pas :
              laisse vide.
            - resultat : seulement un effet que le CV écrit, avec ses chiffres exacts. Jamais de chiffre absent du CV.
            - duree : seulement si le CV écrit une durée ; ne la calcule pas à partir des dates.
            - Un projet personnel ou associatif est une expérience, d'organisation « Projet personnel » ou le nom de
              l'association.
            - competences : chaque outil, langage, logiciel ou langue que le CV liste, un par élément, avec son niveau
              s'il est écrit.
            """;

    private ImportCv() {}

    public static Import importer(Modele modele, String texteCv) {
        Reponse<ProfilLu> r = modele.demander(Demande.horsCandidature("import-cv", Gamme.ECONOMIQUE, CONSIGNES,
                "Texte du CV :\n<<<\n" + texteCv.strip() + "\n>>>"), ProfilLu.class);
        return new Fidele(texteCv).profil(r.valeur(), r.consommation());
    }

    /** La construction du profil, champ par champ, confrontée au texte du CV. */
    private static final class Fidele {

        private final String source;
        private final List<String> remplacements = new ArrayList<>();
        private final Set<String> ids = new HashSet<>();

        Fidele(String source) {
            this.source = source;
        }

        Import profil(ProfilLu lu, Consommation consommation) {
            IdentiteLue i = lu.identite() == null ? new IdentiteLue("", "", "", "") : lu.identite();
            Identite identite = new Identite(nom("identite.nom", i.nom(), true), champ("identite.titre", i.titre(), true),
                    champ("identite.contact", i.contact(), true), champ("identite.lieu", i.lieu(), false));

            List<Experience> experiences = new ArrayList<>();
            for (ExperienceLue e : liste(lu.experiences())) {
                String organisation = nom("expérience « " + net(e.poste()) + " », organisation", e.organisation(), true);
                String id = libre("exp", organisation.equals(Profil.A_COMPLETER) ? net(e.poste()) : organisation);
                String quoi = "expérience " + id;
                experiences.add(new Experience(id, champ(quoi + ", poste", e.poste(), true), organisation,
                        champ(quoi + ", période", e.periode(), true), champ(quoi + ", durée", e.duree(), false),
                        champ(quoi + ", lieu", e.lieu(), false), "", realisations(id, e.realisations())));
            }
            List<Formation> formations = new ArrayList<>();
            for (FormationLue f : liste(lu.formations())) {
                String etablissement = nom("formation « " + net(f.diplome()) + " », établissement", f.etablissement(), true);
                String id = libre("form", net(f.diplome()).isEmpty() ? etablissement : net(f.diplome()));
                String quoi = "formation " + id;
                formations.add(new Formation(id, champ(quoi + ", diplôme", f.diplome(), true), etablissement,
                        champ(quoi + ", période", f.periode(), true), champ(quoi + ", détails", f.details(), false), "",
                        realisations(id, f.realisations())));
            }
            List<Competence> competences = new ArrayList<>();
            for (CompetenceLue c : liste(lu.competences())) {
                String nom = nom("compétence", c.nom(), true);
                String id = libre("comp", nom);
                competences.add(new Competence(id, nom, champ("compétence " + id + ", niveau", c.niveau(), false), ""));
            }
            return new Import(new Profil(identite, experiences, formations, competences), remplacements, consommation);
        }

        private List<Realisation> realisations(String parent, List<RealisationLue> lues) {
            List<Realisation> realisations = new ArrayList<>();
            for (RealisationLue r : liste(lues)) {
                String id = parent + "-r" + (realisations.size() + 1);
                ids.add(id);
                String quoi = "réalisation " + id;
                realisations.add(new Realisation(id, champ(quoi + ", objectif", r.objectif(), true),
                        champ(quoi + ", tâche", r.tache(), true), champ(quoi + ", résultat", r.resultat(), false),
                        liste(r.competences()).stream().map(ImportCv::net).filter(c -> !c.isEmpty())
                                .filter(c -> garder("réalisation " + id + ", compétence", c)).toList(),
                        ""));
            }
            return realisations;
        }

        /** Un champ de texte : vide, il vaut « à compléter » s'il est obligatoire ; un nombre absent du CV le fait remplacer. */
        private String champ(String quoi, String valeur, boolean obligatoire) {
            String v = net(valeur);
            if (v.isEmpty()) {
                return obligatoire ? Profil.A_COMPLETER : "";
            }
            List<String> absents = LectureFidele.nombresAbsents(v, source);
            if (!absents.isEmpty()) {
                remplacements.add(quoi + " : « " + v + " » contenait " + String.join(", ", absents) + ", absent(s) du CV.");
                return Profil.A_COMPLETER;
            }
            return v;
        }

        /** Un nom propre doit se lire dans le CV : sinon le modèle l'a inventé, ou reformulé. */
        private String nom(String quoi, String valeur, boolean obligatoire) {
            String v = champ(quoi, valeur, obligatoire);
            if (v.isEmpty() || v.equals(Profil.A_COMPLETER) || LectureFidele.figure(v, source) || v.equalsIgnoreCase(PROJET_PERSONNEL)) {
                return v;
            }
            remplacements.add(quoi + " : « " + v + " » ne se lit pas dans le CV.");
            return Profil.A_COMPLETER;
        }

        private boolean garder(String quoi, String nom) {
            if (LectureFidele.figure(nom, source)) {
                return true;
            }
            remplacements.add(quoi + " : « " + nom + " » ne se lit pas dans le CV ; retirée.");
            return false;
        }

        /** Un identifiant stable et unique, tiré du nom : « exp-acme », « exp-acme-2 », « form-master-data ». */
        private String libre(String prefixe, String nom) {
            String slug = nom.equals(Profil.A_COMPLETER) ? "" : Profil.slug(nom);
            String base = slug.isEmpty() ? prefixe + "-1" : prefixe + "-" + slug;
            String id = base;
            for (int n = 2; ids.contains(id); n++) {
                id = base + "-" + n;
            }
            ids.add(id);
            return id;
        }
    }

    private static <E> List<E> liste(List<E> elements) {
        return elements == null ? List.of() : elements.stream().filter(Objects::nonNull).toList();
    }

    private static String net(String texte) {
        return texte == null ? "" : texte.strip();
    }
}
