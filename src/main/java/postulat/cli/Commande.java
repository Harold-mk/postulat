package postulat.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import postulat.ia.Modele;
import postulat.ia.ModeleJournalise;
import postulat.stockage.Base;
import postulat.stockage.LectureProfil;
import postulat.stockage.Numerotee;
import postulat.stockage.Reglages;

/** Socle des sous-commandes : accès aux données, au jour courant et à la sortie. */
abstract class Commande implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    /** La commande racine, quelle que soit la profondeur : « postulat profil verifier » compte deux niveaux. */
    Postulat racine() {
        return (Postulat) spec.root().userObject();
    }

    PrintWriter sortie() {
        return spec.commandLine().getOut();
    }

    LocalDate aujourdhui() {
        return racine().aujourdhui();
    }

    Base ouvrirBase() {
        return racine().donnees().ouvrirBase();
    }

    /** Le modèle des réglages, dont chaque appel s'inscrit au journal de la base (EN-5, EN-12). */
    Modele modele(Base base, Reglages reglages) {
        return new ModeleJournalise(racine().fabriqueModele().apply(reglages), base::journaliser, racine().horloge());
    }

    /**
     * Le profil, s'il peut servir à produire un document ; sinon un refus qui dit quoi faire, avant tout
     * appel au modèle (UC-07 E1).
     */
    LectureProfil profilUtilisable() {
        Path fichier = racine().donnees().fichierProfil();
        if (!Files.exists(fichier)) {
            throw new Refus("Aucun profil : " + fichier + ". Pour en créer un : postulat profil initialiser");
        }
        LectureProfil lecture = racine().donnees().lireProfil(fichier);
        if (!lecture.estUtilisable()) {
            throw new Refus("Le profil a " + lecture.problemes().size() + " problème(s) : rien n'est produit tant qu'ils "
                    + "demeurent. Pour les voir : postulat profil verifier");
        }
        return lecture;
    }

    /** Écrit d'un seul mouvement : un PDF ouvert ailleurs n'est pas laissé à moitié remplacé. */
    static Path ecrirePdf(Path cible, byte[] pdf) throws IOException {
        Files.createDirectories(cible.getParent());
        Path provisoire = cible.resolveSibling(cible.getFileName() + ".provisoire");
        Files.write(provisoire, pdf);
        try {
            Files.move(provisoire, cible, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(provisoire);
            throw new Refus("Impossible de remplacer " + cible + " : le PDF est-il ouvert dans un lecteur ? Fermez-le, puis relancez.");
        }
        return cible;
    }

    /** Numéro de chaque candidature, pour afficher les échéances que le domaine désigne par UUID. */
    static Map<UUID, Long> numeros(List<Numerotee> candidatures) {
        return candidatures.stream().collect(Collectors.toMap(n -> n.candidature().id(), Numerotee::numero));
    }

    /** Commande qui porte sur une candidature désignée par son numéro. */
    abstract static class SurCandidature extends Commande {

        @Parameters(index = "0", paramLabel = "NUMERO", description = "Numéro de la candidature (voir : postulat liste).")
        long numero;

        Numerotee trouver(Base base) {
            return base.trouver(numero).orElseThrow(() -> new Refus("Aucune candidature n° " + numero
                    + ". Les numéros sont dans : postulat liste --toutes"));
        }
    }
}
