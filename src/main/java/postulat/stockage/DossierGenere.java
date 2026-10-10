package postulat.stockage;

import java.time.Instant;
import java.util.Objects;
import postulat.production.Dossier;

/**
 * Le dossier courant d'une candidature, tel que la base le conserve.
 *
 * @param empreinteProfil SHA-256 du profil dont il est issu : un profil modifié depuis se voit
 */
public record DossierGenere(Dossier dossier, Instant genereLe, String empreinteProfil) {

    public DossierGenere {
        Objects.requireNonNull(dossier);
        Objects.requireNonNull(genereLe);
        Objects.requireNonNull(empreinteProfil);
    }
}
