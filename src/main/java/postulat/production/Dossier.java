package postulat.production;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Tout ce qui a été produit pour une candidature à un instant donné (glossaire § 4.2) : le CV ciblé et
 * la lettre, structurés phrase par phrase, leur évaluation, et le rapport de production (CA-5.5).
 * C'est un concept, pas un répertoire : les fichiers rendus viennent à la validation (UC-09).
 */
public record Dossier(Cv cv, Lettre lettre, Evaluation evaluation, Rapport rapport) {

    /**
     * @param passes nombre de passes de génération, la première comprise (CA-5.4)
     * @param retirees puces retirées pour tenir sur une page (RG-26, CA-3.13)
     * @param correctionsEcartees corrections du recruteur écartées parce qu'elles demandaient un fait
     *     absent du profil (RG-07)
     */
    public record Rapport(int passes, List<Cv.Puce> retirees, List<String> avertissements, List<String> correctionsEcartees) {

        public Rapport {
            retirees = List.copyOf(retirees);
            avertissements = List.copyOf(avertissements);
            correctionsEcartees = List.copyOf(correctionsEcartees);
        }
    }

    public Dossier {
        Objects.requireNonNull(cv);
        Objects.requireNonNull(lettre);
        Objects.requireNonNull(evaluation);
        Objects.requireNonNull(rapport);
    }

    /** Toutes les phrases du dossier, CV puis lettre. */
    public List<Assertion> assertions() {
        List<Assertion> toutes = new ArrayList<>(cv.assertions());
        toutes.addAll(lettre.assertions());
        return toutes;
    }

    /** Combien de phrases sont de la main de l'auteur (CA-3.10). */
    public long assertionsDeLAuteur() {
        return assertions().stream().filter(a -> a.origine() instanceof Origine.Auteur).count();
    }
}
