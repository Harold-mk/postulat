package postulat.ia;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Un appel au modèle tel que le journal le conserve : de quoi le reconstituer (EN-12) et en connaître
 * le coût (EN-5), qu'il ait abouti ou non.
 *
 * @param reponse le JSON exact reçu ; vide si l'appel a échoué
 * @param issue « réussi », ou la nature et le message de l'échec
 * @param consommation absente si l'appel a échoué avant toute réponse de l'API
 */
public record AppelModele(
        Instant survenuLe,
        Demande demande,
        String reponse,
        String issue,
        Optional<Consommation> consommation) {

    public static final String REUSSI = "réussi";

    public AppelModele {
        Objects.requireNonNull(survenuLe);
        Objects.requireNonNull(demande);
        Objects.requireNonNull(reponse);
        Objects.requireNonNull(issue);
        Objects.requireNonNull(consommation);
    }

    static AppelModele reussi(Instant survenuLe, Demande demande, Reponse<?> reponse) {
        return new AppelModele(survenuLe, demande, reponse.json(), REUSSI, Optional.of(reponse.consommation()));
    }

    static AppelModele echoue(Instant survenuLe, Demande demande, ErreurModele erreur) {
        return new AppelModele(survenuLe, demande, "", "échec " + erreur.nature() + " : " + erreur.getMessage(),
                erreur.consommation());
    }
}
