package postulat.production;

/**
 * Unité atomique et vérifiable du profil (glossaire § 4.1) : la seule matière dont une assertion
 * d'origine « faits » peut dériver (RG-01). Liste fermée de quatre sortes.
 *
 * <p>Un champ absent vaut la chaîne vide, jamais {@code null} : un profil se construit même
 * incomplet, pour que {@link Profil#anomalies()} puisse dire tout ce qui manque d'un coup.
 */
public sealed interface Fait permits Experience, Realisation, Formation, Competence {

    /** Identifiant stable et unique dans le profil (CA-1.4, RG-25) : c'est lui que les dossiers citent. */
    String id();

    /** Consigne de présentation, transmise à toute génération qui utilise le fait (CA-1.5). Vide si aucune. */
    String consigne();

    /**
     * Tout le contenu du fait, ce qui peut justifier une donnée chiffrée d'une assertion (RG-03).
     * La consigne n'en fait pas partie : c'est une instruction, pas un fait.
     */
    String texte();
}
