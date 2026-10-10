package postulat.cli;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import postulat.production.Competence;
import postulat.production.Experience;
import postulat.production.Fait;
import postulat.production.Formation;
import postulat.production.Profil;
import postulat.production.Realisation;
import postulat.stockage.LectureProfil;

/** Le profil, seule source de faits : le créer à partir d'un gabarit, puis le vérifier (UC-01, UC-03). */
final class Profils {

    private Profils() {}

    @Command(name = "profil", description = "Le profil, seule source de faits de vos candidatures (UC-01, UC-03).",
            subcommands = {Initialiser.class, Verifier.class})
    static final class CommandeProfil extends Commande {

        @Override
        public Integer call() {
            spec.commandLine().usage(sortie());
            return 0;
        }
    }

    /** Commun aux deux commandes : le fichier visé, celui du répertoire des données par défaut. */
    abstract static class SurProfil extends Commande {

        @Option(names = "--fichier", paramLabel = "FICHIER",
                description = "Fichier profil. Par défaut : profil.yaml dans le répertoire des données.")
        Path fichier;

        Path fichier() {
            return fichier != null ? fichier.toAbsolutePath() : racine().donnees().fichierProfil();
        }
    }

    @Command(name = "initialiser", description = "Écrit un gabarit de profil commenté, sans jamais écraser un profil existant (UC-01).")
    static final class Initialiser extends SurProfil {

        @Override
        public Integer call() {
            Path cible = fichier();
            if (Files.exists(cible)) {
                throw new Refus("Un profil existe déjà : " + cible + ". Il n'a pas été modifié.\n"
                        + "Pour un gabarit vierge à côté : postulat profil initialiser --fichier \"" + nomDerive(cible) + "\"");
            }
            racine().donnees().ecrireGabaritProfil(cible);
            PrintWriter out = sortie();
            out.println("Gabarit de profil écrit : " + cible);
            out.println("Remplacez chaque « à compléter », en commençant par l'identité et une première expérience.");
            out.println("Chaque fait doit être vrai : c'est la seule matière dont vos CV seront tirés.");
            out.println("Puis : postulat profil verifier");
            return 0;
        }

        /** profil-2.yaml, profil-3.yaml… : le premier nom libre à côté du fichier existant (UC-01 A1). */
        static Path nomDerive(Path existant) {
            String nom = existant.getFileName().toString();
            int point = nom.lastIndexOf('.');
            String base = point < 0 ? nom : nom.substring(0, point);
            String extension = point < 0 ? "" : nom.substring(point);
            Path derive = existant;
            for (int i = 2; Files.exists(derive); i++) {
                derive = existant.resolveSibling(base + "-" + i + extension);
            }
            return derive;
        }
    }

    @Command(name = "verifier",
            description = "Dit si le profil est utilisable et, sinon, où chaque manque se trouve, à la ligne près (UC-03).")
    static final class Verifier extends SurProfil {

        @Override
        public Integer call() {
            Path cible = fichier();
            if (!Files.exists(cible)) {
                throw new Refus("Aucun profil : " + cible + ".\nPour créer un gabarit commenté : postulat profil initialiser");
            }
            LectureProfil lecture = racine().donnees().lireProfil(cible);
            PrintWriter out = sortie();
            out.println("Profil : " + cible);
            if (lecture.estUtilisable()) {
                out.println("Profil valide : " + decompte(lecture.profil().orElseThrow()) + ".");
                return 0;
            }
            int n = lecture.problemes().size();
            out.println(n + (n > 1 ? " problèmes" : " problème") + ", dans l'ordre du fichier :");
            for (LectureProfil.Probleme p : lecture.problemes()) {
                out.println("  ligne " + p.ligne() + ", colonne " + p.colonne() + " · "
                        + (p.chemin().isEmpty() ? "" : p.chemin() + " : ") + p.message());
            }
            out.println("Corrigez, puis relancez : postulat profil verifier");
            return Postulat.REFUSE;
        }

        /** « 6 faits : 1 expérience, 3 réalisations, 1 formation, 1 compétence » (UC-03 A1). */
        static String decompte(Profil profil) {
            Map<String, Long> parSorte = profil.faits().stream()
                    .collect(Collectors.groupingBy(Verifier::sorte, Collectors.counting()));
            Function<String, String> ligne = sorte -> {
                long nombre = parSorte.getOrDefault(sorte, 0L);
                return nombre + " " + sorte + (nombre > 1 ? "s" : "");
            };
            int total = profil.faits().size();
            return total + (total > 1 ? " faits : " : " fait : ")
                    + List.of("expérience", "réalisation", "formation", "compétence").stream()
                            .map(ligne).collect(Collectors.joining(", "));
        }

        private static String sorte(Fait fait) {
            return switch (fait) {
                case Experience e -> "expérience";
                case Realisation r -> "réalisation";
                case Formation f -> "formation";
                case Competence c -> "compétence";
            };
        }
    }
}
