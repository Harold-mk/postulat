package postulat.ia;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import postulat.production.Exigence;
import postulat.production.OffreAnalysee;

/**
 * P2 : lire le texte brut d'une offre et en tirer une fiche structurée (UC-05, étape 4). Gamme
 * économique : c'est de l'extraction, sans jugement.
 *
 * <p>Le texte brut reste la référence : il est conservé à part, et l'analyse peut toujours être
 * rejouée (CA-2.5).
 */
public final class AnalyseOffre {

    /** La forme imposée à la réponse du modèle. */
    public record FicheOffre(
            @JsonPropertyDescription("Intitulé du poste, tel qu'écrit dans l'offre") String poste,
            @JsonPropertyDescription("Nom de l'employeur ; vide si l'offre ne le donne pas") String entreprise,
            @JsonPropertyDescription("Lieu de travail ; vide si absent") String lieu,
            @JsonPropertyDescription("Type de contrat : stage, alternance… ; vide si absent") String contrat,
            @JsonPropertyDescription("Date ou période de début ; vide si absente") String debut,
            @JsonPropertyDescription("Durée ou rythme ; vide si absent") String duree,
            @JsonPropertyDescription("Niveau d'études ou diplôme demandé ; vide si absent") String niveau,
            @JsonPropertyDescription("Missions principales, une par élément") List<String> missions,
            @JsonPropertyDescription("Exigences, une par élément, dans l'ordre de l'offre") List<ExigenceLue> exigences) {}

    public record ExigenceLue(
            @JsonPropertyDescription("Libellé court, avec les mots exacts de l'offre") String libelle,
            @JsonPropertyDescription("Vrai si exigée, faux si présentée comme un plus") boolean obligatoire) {}

    static final String CONSIGNES = """
            Tu lis une offre de stage ou d'alternance et tu en tires une fiche structurée, en français.

            Règles :
            - N'écris que ce que le texte dit. Une information absente reste une chaîne vide : ne la devine jamais.
            - poste : l'intitulé tel qu'il est écrit.
            - entreprise : le nom de l'employeur. Vide si l'offre ne le donne pas (cabinet de recrutement, « notre client »).
            - lieu, contrat (stage, alternance, CDD…), debut (date ou période de début), duree (durée ou rythme),
              niveau (diplôme ou année d'études demandés) : tels que l'offre les donne.
            - missions : les missions principales, une par élément, reformulées brièvement.
            - exigences : chaque compétence, outil, langue ou qualification demandé, un par élément.
              Le libellé est court et reprend les mots exacts de l'offre (« Power BI », « SQL », « anglais courant ») :
              un filtre de recrutement cherchera ces mots tels quels.
              obligatoire est vrai si l'offre l'exige, faux si elle le présente comme un plus
              (« idéalement », « serait un plus », « apprécié », « souhaité »).
            - Une mission n'est pas une exigence. Ne déduis aucune exigence que l'offre n'énonce pas.
            """;

    private AnalyseOffre() {}

    public static OffreAnalysee analyser(Modele modele, String texteBrut, Optional<UUID> candidature) {
        Demande demande = new Demande("analyse-offre", Gamme.ECONOMIQUE, CONSIGNES,
                "Texte de l'offre :\n<<<\n" + texteBrut.strip() + "\n>>>", candidature);
        FicheOffre f = modele.demander(demande, FicheOffre.class).valeur();
        return new OffreAnalysee(
                net(f.poste()), net(f.entreprise()), net(f.lieu()), net(f.contrat()), net(f.debut()),
                net(f.duree()), net(f.niveau()),
                f.missions() == null ? List.of() : f.missions().stream().map(AnalyseOffre::net).filter(m -> !m.isEmpty()).toList(),
                f.exigences() == null ? List.of() : f.exigences().stream()
                        .filter(e -> e != null && !net(e.libelle()).isEmpty())
                        .map(e -> new Exigence(e.libelle(), e.obligatoire()))
                        .toList());
    }

    private static String net(String texte) {
        return texte == null ? "" : texte.strip();
    }
}
