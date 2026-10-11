package postulat.production;

import java.util.ArrayList;
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

    /** La même lettre, la phrase de rang {@code index} — toutes parties confondues, à partir de 0 — remplacée. */
    public Lettre remplacer(int index, Assertion nouvelle) {
        return modifier(index, nouvelle);
    }

    /**
     * La même lettre sans la phrase de rang {@code index}. La dernière phrase d'une partie ne se supprime
     * pas : la lettre perdrait sa structure Vous – Moi – Nous (CA-4.1). Elle se réécrit.
     */
    public Lettre supprimer(int index) {
        return modifier(index, null);
    }

    private Lettre modifier(int index, Assertion nouvelle) {
        int debut = 0;
        for (int p = 0; p < paragraphes.size(); p++) {
            List<Assertion> phrases = paragraphes.get(p).phrases();
            if (index >= debut && index < debut + phrases.size()) {
                List<Assertion> modifiees = new ArrayList<>(phrases);
                if (nouvelle != null) {
                    modifiees.set(index - debut, nouvelle);
                } else if (phrases.size() == 1) {
                    throw new IllegalArgumentException("C'est la seule phrase de sa partie : la supprimer romprait la structure "
                            + "Vous – Moi – Nous (CA-4.1). Réécrivez-la plutôt.");
                } else {
                    modifiees.remove(index - debut);
                }
                List<Paragraphe> nouveaux = new ArrayList<>(paragraphes);
                nouveaux.set(p, new Paragraphe(modifiees));
                return new Lettre(expediteur, destinataire, lieuEtDate, objet, nouveaux);
            }
            debut += phrases.size();
        }
        throw new IllegalArgumentException("La lettre n'a pas de phrase n° " + (index + 1) + ".");
    }

    /** La longueur du corps de la lettre, en mots (CA-4.4). */
    public int mots() {
        return assertions().stream().mapToInt(a -> a.texte().strip().split("\\s+").length).sum();
    }
}
