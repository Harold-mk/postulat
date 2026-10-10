package postulat.production;

import java.util.List;
import java.util.Objects;

/**
 * Une lettre de motivation, telle qu'elle sera rendue (US-4) : l'expéditeur, le destinataire, la date,
 * l'objet, puis des paragraphes — Vous, Moi, Nous — faits d'assertions qui portent leur origine.
 *
 * <p>Contrairement au CV, une lettre ne s'ampute pas phrase à phrase : couper une phrase casse le
 * raisonnement. Sa longueur se tient en amont, par le nombre de mots demandé à la rédaction (CA-4.4).
 *
 * <p>L'appel et la formule de politesse sont fixés ici, pas rédigés : ils n'affirment rien.
 *
 * @param lieuEtDate tels qu'ils s'écrivent en tête de lettre : « Lyon, le 10 octobre 2026 »
 */
public record Lettre(Identite expediteur, String destinataire, String lieuEtDate, String objet, List<Paragraphe> paragraphes) {

    public static final String APPEL = "Madame, Monsieur,";
    public static final String FORMULE = "Je vous prie d'agréer, Madame, Monsieur, l'expression de mes salutations distinguées.";

    public Lettre {
        Objects.requireNonNull(expediteur);
        Objects.requireNonNull(destinataire);
        Objects.requireNonNull(lieuEtDate);
        Objects.requireNonNull(objet);
        paragraphes = List.copyOf(paragraphes);
    }

    public record Paragraphe(List<Assertion> phrases) {

        public Paragraphe {
            phrases = List.copyOf(phrases);
        }
    }

    /** Toutes les phrases, dans l'ordre : ce que la vérification de traçabilité examine. */
    public List<Assertion> assertions() {
        return paragraphes.stream().flatMap(p -> p.phrases().stream()).toList();
    }

    /** La longueur du corps de la lettre, en mots (CA-4.4). */
    public int mots() {
        return assertions().stream().mapToInt(a -> a.texte().strip().split("\\s+").length).sum();
    }
}
