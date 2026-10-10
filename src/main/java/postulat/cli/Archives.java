package postulat.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import postulat.stockage.Donnees;
import postulat.stockage.Restauration;

/** Sauvegarde et restauration : la perte d'une campagne doit rester réparable (EN-3). */
final class Archives {

    private Archives() {}

    @Command(name = "sauvegarder", description = "Écrit une archive datée de toutes les données (UC-17).")
    static final class Sauvegarder extends Commande {

        @Override
        public Integer call() throws IOException {
            Path archive = racine.donnees().sauvegarder();
            long ko = (Files.size(archive) + 1023) / 1024;
            sortie().println("Sauvegarde écrite : " + archive + " (" + ko + " Ko).");
            sortie().println("Pour la restaurer : postulat restaurer \"" + archive + "\"");
            return 0;
        }
    }

    @Command(name = "restaurer",
            description = "Remplace les données par celles d'une sauvegarde, après l'avoir entièrement vérifiée (UC-18).")
    static final class Restaurer extends Commande {

        @Parameters(index = "0", paramLabel = "ARCHIVE", description = "Fichier .zip écrit par postulat sauvegarder.")
        Path archive;

        @Option(names = "--oui", description = "Confirmer sans poser la question.")
        boolean oui;

        @Override
        public Integer call() {
            Donnees donnees = racine.donnees();
            PrintWriter out = sortie();
            try (Restauration r = donnees.preparerRestauration(archive)) {
                out.println("Archive vérifiée : " + r.candidatures() + " candidature(s), " + r.evenements()
                        + " événement(s), chaque étape conforme à son journal.");
                out.println("Restaurer remplace toutes les données de " + donnees.repertoire()
                        + ". L'état actuel sera d'abord sauvegardé.");
                if (!oui && !confirme(out)) {
                    out.println("Restauration annulée : rien n'a été modifié.");
                    return 0;
                }
                Optional<Path> etatRemplace = donnees.restaurer(r);
                out.println("Données restaurées depuis " + archive + ".");
                etatRemplace.ifPresent(p -> out.println("L'état remplacé reste récupérable : postulat restaurer \"" + p + "\""));
            }
            return 0;
        }

        private boolean confirme(PrintWriter out) {
            out.print("Tapez « oui » pour confirmer : ");
            out.flush();
            try {
                String reponse = racine.entree().readLine();
                return reponse != null && Conversions.cle(reponse).equals("oui");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
