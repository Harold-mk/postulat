package postulat.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import postulat.cas.CasOffre;
import postulat.cas.Refus;
import postulat.libelles.Libelles;
import postulat.stockage.Base;
import postulat.stockage.Numerotee;
import postulat.suivi.Etape;

/**
 * Soumettre une offre et la comprendre avant d'y investir du temps (UC-05) : analyse, rapprochement
 * avec le profil, note d'adéquation. La décision — poursuivre ou écarter — reste au candidat (UC-06).
 */
final class Offres {

    static final String ENTREE_STANDARD = "entrée standard";

    private Offres() {}

    @Command(name = "offre", description = "Soumettre une offre, la faire analyser et rapprocher du profil (UC-05).",
            subcommands = {Ajouter.class, Analyser.class})
    static final class CommandeOffre extends Commande {

        @Override
        public Integer call() {
            spec.commandLine().usage(sortie());
            return 0;
        }
    }

    @Command(name = "ajouter", description = "Soumet le texte d'une offre : analyse, rapprochement avec le profil, note "
            + "d'adéquation. La candidature entre à l'étape « repérée » (UC-05).")
    static final class Ajouter extends Commande {

        @Parameters(index = "0", paramLabel = "FICHIER",
                description = "Fichier texte de l'offre (UTF-8), ou « - » pour l'entrée standard.")
        String source;

        @Option(names = "--entreprise", paramLabel = "NOM", description = "L'entreprise, si l'offre ne la nomme pas ou pour la corriger.")
        String entreprise;

        @Option(names = "--poste", paramLabel = "INTITULE", description = "Le poste, à défaut de l'analyse ou pour le corriger.")
        String poste;

        @Option(names = "--le", paramLabel = "DATE", description = "Jour où l'offre a été repérée. Par défaut : aujourd'hui.")
        LocalDate le;

        @Option(names = "--distincte", description = "Ajouter même si une candidature porte la même entreprise et le même poste.")
        boolean distincte;

        @Override
        public Integer call() {
            String texte = lireTexte();
            PrintWriter out = sortie();
            CasOffre cas = new CasOffre(contexte());
            String origine = source.equals("-") ? ENTREE_STANDARD : Path.of(source).toAbsolutePath().toString();
            CasOffre.Soumission soumission = cas.soumettre(texte, origine, Optional.ofNullable(entreprise), Optional.ofNullable(poste),
                    le == null ? aujourdhui() : le, existante -> distincte, this::demander, out::println);
            switch (soumission) {
                case CasOffre.DejaSuivie d -> {
                    dejaSuivie(out, d.existante());
                    if (!d.memeTexte()) {
                        out.println("S'il s'agit d'une autre offre : ajoutez --distincte.");
                    }
                }
                case CasOffre.Ajoutee a -> {
                    Numerotee n = a.numerotee();
                    out.println("Candidature " + Libelles.titre(n.numero(), n.candidature()) + " ajoutée à l'étape « repérée ».");
                    if (a.analyse().isEmpty()) {
                        out.println("L'offre est conservée avec son texte, non analysée. Plus tard : postulat offre analyser " + n.numero());
                        return 0;
                    }
                    cas.rapprocher(n.numero(), out::println);
                    try (Base base = ouvrirBase()) {
                        Affichage.offre(out, base.offre(n.candidature().id()).orElseThrow(), racine().horloge().getZone(), n.numero());
                    }
                    Affichage.decision(out, n.numero());
                }
            }
            return 0;
        }

        private String lireTexte() {
            String texte;
            try {
                texte = source.equals("-")
                        ? racine().entree().lines().collect(Collectors.joining("\n"))
                        : Files.readString(Path.of(source), StandardCharsets.UTF_8);
            } catch (NoSuchFileException e) {
                throw new Refus("Fichier introuvable : " + Path.of(source).toAbsolutePath());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return CasOffre.exigerTexte(Conversions.sansMarqueDOrdre(texte),
                    "Copiez le texte complet de l'annonce dans un fichier, puis : postulat offre ajouter FICHIER");
        }

        /** Ce que l'analyse n'a pas trouvé se demande ; pas quand l'offre arrive par l'entrée standard. */
        private Optional<String> demander(String nom, boolean feminin) {
            if (source.equals("-")) {
                return Optional.empty();
            }
            sortie().print(nom + " de l'offre (l'analyse ne l'a pas trouvé" + (feminin ? "e" : "") + ") : ");
            sortie().flush();
            try {
                return Optional.ofNullable(racine().entree().readLine());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Command(name = "analyser", description = "Analyse une offre conservée, puis la rapproche du profil : quand le modèle "
            + "était indisponible, ou après une modification du profil.")
    static final class Analyser extends Commande.SurCandidature {

        @Option(names = "--tout", description = "Refaire aussi l'analyse de l'offre, pas seulement le rapprochement.")
        boolean tout;

        @Override
        public Integer call() {
            PrintWriter out = sortie();
            new CasOffre(contexte()).analyser(numero, tout, out::println);
            try (Base base = ouvrirBase()) {
                Numerotee n = trouver(base);
                Affichage.offre(out, base.offre(n.candidature().id()).orElseThrow(), racine().horloge().getZone(), n.numero());
                if (n.candidature().etape() == Etape.REPEREE) {
                    Affichage.decision(out, n.numero());
                }
            }
            return 0;
        }
    }

    static void dejaSuivie(PrintWriter out, Numerotee n) {
        out.println("Cette offre est déjà suivie : " + Libelles.titre(n.numero(), n.candidature()) + ", à l'étape "
                + Libelles.etape(n.candidature().etape()) + ". Pour la revoir : postulat voir " + n.numero());
    }
}
