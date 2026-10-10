package postulat.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import postulat.production.Apercu;
import postulat.production.Ats;
import postulat.production.Cv;
import postulat.production.Exigence;
import postulat.production.NoteAts;
import postulat.production.OffreAnalysee;
import postulat.production.Profil;
import postulat.production.Tracabilite;
import postulat.production.Violation;
import postulat.rendu.Rendu;
import postulat.stockage.Base;
import postulat.stockage.LectureProfil;
import postulat.stockage.Numerotee;
import postulat.stockage.Offre;

/** Les documents produits : pour l'instant, l'aperçu du CV tiré du profil (itération 7). */
final class Documents {

    private Documents() {}

    @Command(name = "cv", description = "Le CV : aperçu de sa mise en page sur une page A4 (RG-05).", subcommands = ApercuDuCv.class)
    static final class CommandeCv extends Commande {

        @Override
        public Integer call() {
            spec.commandLine().usage(sortie());
            return 0;
        }
    }

    @Command(name = "apercu", description = "Rend sur une page le CV tiré du profil entier, vérifie que chaque phrase cite "
            + "un fait, et le relit comme un filtre ATS. Ce n'est pas encore un CV ciblé : tout le profil y est.")
    static final class ApercuDuCv extends Commande {

        @Option(names = "--offre", paramLabel = "N", description = "Noter le texte relu face aux exigences de l'offre de la candidature n° N.")
        Long offre;

        @Option(names = "--sortie", paramLabel = "FICHIER", description = "Où écrire le PDF. Par défaut : apercus\\cv-apercu.pdf "
                + "dans le répertoire des données.")
        Path sortie;

        @Override
        public Integer call() throws IOException {
            Profil profil = profilUtilisable();
            List<Exigence> exigences = exigencesDeLOffre();
            Cv cv = Apercu.de(profil);
            Rendu.Resultat r = Rendu.cv(cv);
            Path fichier = ecrire(r.pdf());

            PrintWriter out = sortie();
            out.println("Aperçu écrit : " + fichier + " (une page, mise en page « " + r.gabarit() + " »)");
            List<Violation> violations = Tracabilite.verifier(cv.assertions(), profil, List.of());
            out.println(violations.isEmpty()
                    ? "Traçabilité : " + cv.assertions().size() + " phrases, chacune tirée d'un fait du profil."
                    : "Traçabilité : " + violations.size() + " violation(s) — " + violations);
            if (r.retirees().isEmpty()) {
                out.println("Tout le profil tient sur la page.");
            } else {
                out.println("Retirées pour tenir sur une page, les moins pertinentes d'abord (RG-26) :");
                r.retirees().forEach(p -> out.println("  - " + p.assertion().texte()));
            }
            r.avertissements().forEach(a -> out.println("Attention : " + a));
            out.println("Relu comme le ferait un filtre ATS : " + r.texte().strip().length() + " caractères.");
            if (offre != null) {
                Optional<NoteAts> note = Ats.noter(r.texte(), exigences);
                note.ifPresentOrElse(n -> {
                    out.println("Note ATS face à l'offre n° " + offre + " : " + n.note() + "/100.");
                    if (!n.obligatoiresAbsentes().isEmpty()) {
                        out.println("  Obligatoires absentes du texte : "
                                + String.join(", ", n.obligatoiresAbsentes().stream().map(Exigence::libelle).toList()));
                    }
                }, () -> out.println("L'offre n° " + offre + " n'a aucune exigence analysée : pas de note ATS."));
            }
            return 0;
        }

        private Profil profilUtilisable() {
            Path fichier = racine().donnees().fichierProfil();
            if (!Files.exists(fichier)) {
                throw new Refus("Aucun profil : " + fichier + ". Pour en créer un : postulat profil initialiser");
            }
            LectureProfil lecture = racine().donnees().lireProfil(fichier);
            if (!lecture.estUtilisable()) {
                throw new Refus("Le profil a " + lecture.problemes().size() + " problème(s) : postulat profil verifier");
            }
            return lecture.profil().orElseThrow();
        }

        private List<Exigence> exigencesDeLOffre() {
            if (offre == null) {
                return List.of();
            }
            try (Base base = ouvrirBase()) {
                Numerotee n = base.trouver(offre).orElseThrow(() -> new Refus("Aucune candidature n° " + offre + "."));
                return base.offre(n.candidature().id())
                        .flatMap(Offre::analyse)
                        .map(OffreAnalysee::exigences)
                        .orElseThrow(() -> new Refus("La candidature n° " + offre + " n'a pas d'offre analysée."));
            }
        }

        /** Écrit d'un seul mouvement : un PDF ouvert ailleurs n'est pas laissé à moitié remplacé. */
        private Path ecrire(byte[] pdf) throws IOException {
            Path cible = sortie != null ? sortie.toAbsolutePath()
                    : racine().donnees().repertoire().resolve("apercus").resolve("cv-apercu.pdf");
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
    }
}
