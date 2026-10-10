package postulat.suivi;

/**
 * Canal d'une interaction (CA-8.2). Les entretiens ont leur propre événement, typé RH, technique ou
 * final ({@link Evenement.EntretienPasse}) : ils ne sont pas une interaction comme une autre.
 */
public enum Canal {
    COURRIEL,
    APPEL
}
