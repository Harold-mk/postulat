package postulat.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import postulat.bureau.Bureau;
import postulat.bureau.Session;

/** L'autre façon de se servir de postulat : la fenêtre (itération 9). */
final class Interface {

    private Interface() {}

    @Command(name = "bureau", description = "Ouvre l'interface de bureau : tableau de bord, actions du jour, candidatures, offres "
            + "et dossiers. Elle lit et écrit les mêmes données que la ligne de commande.")
    static final class OuvrirBureau extends Commande {

        @Option(names = "--verifier", description = "Vérifie que l'interface peut s'ouvrir ici, sans rien afficher : JavaFX, ses "
                + "bibliothèques, le thème, chaque page. Pour contrôler une installation.")
        boolean verifier;

        @Override
        public Integer call() {
            Session session = new Session(racine().donnees(), racine().horloge(), racine().fabriqueModele());
            if (!verifier) {
                Bureau.lancer(session);
                return 0;
            }
            try {
                sortie().println(Bureau.verifier(session));
                return 0;
            } catch (IllegalStateException e) {
                spec.commandLine().getErr().println(e.getMessage());
                return Postulat.ERREUR;
            }
        }
    }
}
