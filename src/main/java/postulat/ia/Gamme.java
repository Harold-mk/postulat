package postulat.ia;

/**
 * Gamme de modèle (cadrage § 11). La répartition par tâche se fait entre modèles d'une même API : un
 * paramètre, non un second adaptateur. Le modèle derrière chaque gamme se règle dans
 * {@code postulat.properties}.
 */
public enum Gamme {
    /** Extraction structurée, classement : analyse d'offre, note d'adéquation. */
    ECONOMIQUE,
    /** Rédaction et jugement : CV ciblé, lettre, avis recruteur. */
    CAPABLE
}
