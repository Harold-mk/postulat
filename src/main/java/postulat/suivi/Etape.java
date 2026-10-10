package postulat.suivi;

import java.util.Set;

/**
 * Position d'une candidature dans le pipeline (US-6). Liste fermée de 11 étapes.
 *
 * <p>Les trois étapes d'entretien du cadrage v0.8 sont fusionnées en {@link #ENTRETIEN} : le type
 * d'entretien (RH, technique, final) est porté par l'événement, pas par l'étape (CA-6.13).
 *
 * <p>La table des transitions est un {@code switch} sans branche {@code default} : ajouter une étape
 * sans déclarer ses transitions ne compile pas (RG-08).
 */
public enum Etape {
    REPEREE("repérée"),
    ECARTEE("écartée"),
    A_PREPARER("à_préparer"),
    PRETE("prête"),
    ENVOYEE("envoyée"),
    ENTRETIEN("entretien"),
    OFFRE_RECUE("offre_reçue"),
    ACCEPTEE("acceptée"),
    REFUSEE("refusée"),
    SANS_SUITE("sans_suite"),
    RETIREE("retirée");

    private final String libelle;

    Etape(String libelle) {
        this.libelle = libelle;
    }

    public String libelle() {
        return libelle;
    }

    /** Étapes atteignables depuis celle-ci par une transition ordinaire. Vide pour une terminale. */
    public Set<Etape> suivantes() {
        return switch (this) {
            case REPEREE -> Set.of(A_PREPARER, ECARTEE);
            case A_PREPARER -> Set.of(PRETE, ECARTEE, RETIREE);
            case PRETE -> Set.of(ENVOYEE, RETIREE);
            case ENVOYEE -> Set.of(ENTRETIEN, OFFRE_RECUE, REFUSEE, SANS_SUITE, RETIREE);
            case ENTRETIEN -> Set.of(OFFRE_RECUE, REFUSEE, SANS_SUITE, RETIREE);
            case OFFRE_RECUE -> Set.of(ACCEPTEE, REFUSEE, RETIREE);
            case ECARTEE, ACCEPTEE, REFUSEE, SANS_SUITE, RETIREE -> Set.of();
        };
    }

    public boolean estTerminale() {
        return suivantes().isEmpty();
    }

    public boolean peutAllerVers(Etape cible) {
        return suivantes().contains(cible);
    }

    @Override
    public String toString() {
        return libelle;
    }
}
