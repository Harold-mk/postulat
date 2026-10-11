package postulat.stockage;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import postulat.production.Dossier;

/**
 * Le dossier courant d'une candidature, tel que la base le conserve.
 *
 * @param empreinteProfil SHA-256 du profil dont il est issu : un profil modifié depuis se voit
 * @param corrigeLe la dernière correction à la main, s'il y en a eu une (UC-08)
 * @param avisPerime vrai si le dossier a été corrigé depuis l'avis du recruteur : cet avis jugeait une
 *     version qui n'existe plus
 */
public record DossierGenere(Dossier dossier, Instant genereLe, String empreinteProfil, Optional<Instant> corrigeLe, boolean avisPerime) {

    public DossierGenere {
        Objects.requireNonNull(dossier);
        Objects.requireNonNull(genereLe);
        Objects.requireNonNull(empreinteProfil);
        Objects.requireNonNull(corrigeLe);
    }

    /** Un dossier tout juste généré : ni corrigé, ni périmé. */
    public static DossierGenere nouveau(Dossier dossier, Instant genereLe, String empreinteProfil) {
        return new DossierGenere(dossier, genereLe, empreinteProfil, Optional.empty(), false);
    }

    /** Le même, corrigé à la main : l'avis du recruteur ne le juge plus. */
    public DossierGenere corrige(Dossier corrige, Instant le) {
        return new DossierGenere(corrige, genereLe, empreinteProfil, Optional.of(le), true);
    }

    /** Le même, avec un nouvel avis du recruteur, qui juge la version actuelle. */
    public DossierGenere reevalue(Dossier reevalue) {
        return new DossierGenere(reevalue, genereLe, empreinteProfil, corrigeLe, false);
    }
}
