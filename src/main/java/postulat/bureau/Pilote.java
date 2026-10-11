package postulat.bureau;

/**
 * Pour les tests : répond aux boîtes à la place du candidat, sans rien afficher. Hors des tests, aucun
 * pilote n'est actif, et chaque boîte s'affiche et attend le candidat. Le code des boîtes est le même
 * dans les deux cas : seuls l'affichage et l'attente changent.
 */
interface Pilote {

    /** Remplit le formulaire ; vrai pour l'enregistrer, faux pour l'annuler. */
    boolean remplir(Formulaire formulaire);

    /** Répond à une demande de confirmation. */
    boolean confirmer(String titre, String texte);

    /** Examine un travail long, une fois fini. */
    void travail(Travail travail);

    /** Le pilote des tests, s'il y en a un. */
    final class Actif {

        static volatile Pilote pilote;

        private Actif() {}
    }
}
