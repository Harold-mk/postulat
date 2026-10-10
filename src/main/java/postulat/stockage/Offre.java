package postulat.stockage;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import postulat.production.OffreAnalysee;
import postulat.production.Rapprochement;

/**
 * L'offre d'une candidature telle que la base la conserve : son texte brut toujours, son analyse et son
 * rapprochement quand ils ont pu être faits.
 *
 * @param source d'où vient le texte : un chemin de fichier, ou « entrée standard »
 * @param analyse absente si le modèle était indisponible (UC-05 E2)
 * @param rapprochement absent si le profil était absent ou incomplet (UC-05 A3)
 */
public record Offre(
        String source,
        String texteBrut,
        Instant recueLe,
        Optional<OffreAnalysee> analyse,
        Optional<Instant> analyseeLe,
        Optional<Rapprochement> rapprochement,
        Optional<Instant> rapprocheeLe) {

    public Offre {
        Objects.requireNonNull(source);
        Objects.requireNonNull(texteBrut);
        Objects.requireNonNull(recueLe);
        Objects.requireNonNull(analyse);
        Objects.requireNonNull(analyseeLe);
        Objects.requireNonNull(rapprochement);
        Objects.requireNonNull(rapprocheeLe);
    }
}
