package postulat.ia;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import postulat.ia.ErreurModele.Nature;
import postulat.production.Competence;
import postulat.production.Couverture;
import postulat.production.Exigence;
import postulat.production.Experience;
import postulat.production.Formation;
import postulat.production.Profil;
import postulat.production.Rapprochement;
import postulat.production.Realisation;

/**
 * P3 et P4 : rapprocher les exigences d'une offre des faits du profil, puis noter l'adéquation (UC-05,
 * étapes 6 et 7). Gamme capable : c'est un jugement sémantique (L-01).
 *
 * <p>Le modèle juge, le code vérifie. Chaque exigence doit recevoir exactement une réponse, et tout
 * fait cité doit exister dans le profil (RG-01) : sinon le rapprochement est refusé en entier, plutôt
 * que retouché.
 *
 * <p>Les consignes et le profil forment la partie stable de la demande, mise en cache d'une offre à
 * l'autre ; seules les exigences changent.
 */
public final class RapprochementOffre {

    /** La forme imposée à la réponse du modèle. */
    public record RapprochementLu(
            @JsonPropertyDescription("Une entrée par exigence, dans l'ordre") List<CouvertureLue> couvertures,
            @JsonPropertyDescription("Adéquation de 0 à 100") int note,
            @JsonPropertyDescription("Deux ou trois phrases qui justifient la note") String justification) {}

    public record CouvertureLue(
            @JsonPropertyDescription("Numéro de l'exigence, à partir de 1") int exigence,
            @JsonPropertyDescription("Identifiants des faits qui la démontrent ; vide pour une lacune") List<String> faits,
            @JsonPropertyDescription("Une phrase : pourquoi ces faits la couvrent, ou ce qui manque") String justification) {}

    static final String CONSIGNES = """
            Tu rapproches les exigences d'une offre de stage des faits du profil d'un candidat.
            Le profil ci-dessous est la seule source de vérité sur le candidat.

            Pour chaque exigence, dans l'ordre, réponds par :
            - exigence : son numéro ;
            - faits : les identifiants des faits qui démontrent cette exigence, tels qu'ils figurent entre crochets
              dans le profil ; une liste vide si aucun fait ne la démontre ;
            - justification : une phrase qui dit pourquoi ces faits la couvrent, ou ce qui manque.

            Sois exigeant. Un fait couvre une exigence seulement s'il la démontre :
            - un outil n'est pas couvert par un mot qui lui ressemble : l'outil « Tableau » n'est pas un « tableau de bord » ;
            - une durée d'expérience n'est couverte que si les faits l'atteignent : un stage de 6 mois ne vaut pas
              « 3 ans d'expérience » ;
            - une compétence voisine ne vaut pas la compétence demandée.
            Dans le doute, c'est une lacune. Une lacune se signale ; elle ne se comble jamais.

            Puis note l'adéquation de 0 à 100, les exigences obligatoires d'abord, et justifie la note
            en deux ou trois phrases, en français.
            """;

    private RapprochementOffre() {}

    public static Rapprochement rapprocher(Modele modele, Profil profil, List<Exigence> exigences, UUID candidature) {
        if (exigences.isEmpty()) {
            throw new IllegalArgumentException("Aucune exigence à rapprocher.");
        }
        String numerotees = IntStream.range(0, exigences.size())
                .mapToObj(i -> (i + 1) + ". [" + (exigences.get(i).obligatoire() ? "obligatoire" : "souhaitée") + "] "
                        + exigences.get(i).libelle())
                .collect(Collectors.joining("\n"));
        Demande demande = new Demande("rapprochement", Gamme.CAPABLE, CONSIGNES + "\nProfil :\n" + texte(profil),
                "Exigences de l'offre :\n" + numerotees, Optional.of(candidature));
        RapprochementLu lu = modele.demander(demande, RapprochementLu.class).valeur();
        return verifier(lu, profil, exigences);
    }

    /** Le profil tel que le modèle le lit : un fait par ligne, précédé de l'identifiant qu'il devra citer. */
    static String texte(Profil profil) {
        List<String> lignes = new ArrayList<>();
        for (Experience e : profil.experiences()) {
            lignes.add("[" + e.id() + "] Expérience : " + joindre(e.poste(), e.organisation(), e.periode(), e.duree(), e.lieu()));
            e.realisations().forEach(r -> lignes.add(ligne(r, e.id())));
        }
        for (Formation f : profil.formations()) {
            lignes.add("[" + f.id() + "] Formation : " + joindre(f.diplome(), f.etablissement(), f.periode(), f.details()));
            f.realisations().forEach(r -> lignes.add(ligne(r, f.id())));
        }
        for (Competence c : profil.competences()) {
            lignes.add("[" + c.id() + "] Compétence : " + joindre(c.nom(), c.niveau()));
        }
        return String.join("\n", lignes);
    }

    // --- interne ---

    private static Rapprochement verifier(RapprochementLu lu, Profil profil, List<Exigence> exigences) {
        List<CouvertureLue> lues = lu.couvertures() == null ? List.of() : lu.couvertures();
        List<Couverture> couvertures = new ArrayList<>();
        for (int i = 1; i <= exigences.size(); i++) {
            int numero = i;
            List<CouvertureLue> pourElle = lues.stream().filter(c -> c != null && c.exigence() == numero).toList();
            if (pourElle.size() != 1) {
                throw invalide("le modèle a répondu " + pourElle.size() + " fois pour l'exigence n° " + numero
                        + " (« " + exigences.get(i - 1).libelle() + " »), au lieu d'une");
            }
            CouvertureLue c = pourElle.get(0);
            List<String> faits = c.faits() == null ? List.of() : c.faits().stream().map(String::strip).distinct().toList();
            couvertures.add(new Couverture(exigences.get(i - 1), faits, c.justification() == null ? "" : c.justification().strip()));
        }
        if (lues.size() != exigences.size()) {
            throw invalide("le modèle a répondu pour " + lues.size() + " exigences, l'offre en compte " + exigences.size());
        }
        Rapprochement r;
        try {
            r = new Rapprochement(couvertures, lu.note(), lu.justification() == null ? "" : lu.justification().strip());
        } catch (IllegalArgumentException e) {
            throw invalide(e.getMessage());
        }
        List<String> inconnus = r.faitsInconnus(profil);
        if (!inconnus.isEmpty()) {
            throw invalide("il cite des faits absents du profil (RG-01) : " + String.join(", ", inconnus));
        }
        return r;
    }

    private static ErreurModele invalide(String raison) {
        return new ErreurModele(Nature.INVALIDE, "Rapprochement refusé, rien n'a été enregistré : " + raison + ".", null, null);
    }

    private static String ligne(Realisation r, String parent) {
        return "[" + r.id() + "] Réalisation (dans " + parent + ") : objectif : " + r.objectif() + " ; tâche : " + r.tache()
                + (r.resultat().isEmpty() ? "" : " ; résultat : " + r.resultat())
                + (r.competences().isEmpty() ? "" : " ; compétences : " + String.join(", ", r.competences()));
    }

    private static String joindre(String... parties) {
        return java.util.Arrays.stream(parties).filter(p -> !p.isBlank()).collect(Collectors.joining(" — "));
    }
}
