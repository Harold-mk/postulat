package postulat.cli;

import picocli.CommandLine.Command;
import postulat.bureau.Bureau;
import postulat.bureau.Session;

/** L'autre façon de se servir de postulat : la fenêtre (itération 9). */
final class Interface {

    private Interface() {}

    @Command(name = "bureau", description = "Ouvre l'interface de bureau : tableau de bord, actions du jour, candidatures. Elle lit "
            + "et écrit les mêmes données que la ligne de commande.")
    static final class OuvrirBureau extends Commande {

        @Override
        public Integer call() {
            Bureau.lancer(new Session(racine().donnees(), racine().horloge(), racine().fabriqueModele()));
            return 0;
        }
    }
}
