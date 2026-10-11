package postulat.cli;

import java.io.PrintWriter;
import java.time.YearMonth;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import postulat.libelles.Libelles;
import postulat.ia.Consommation;
import postulat.ia.Demande;
import postulat.ia.Gamme;
import postulat.ia.Reponse;
import postulat.stockage.Base;
import postulat.stockage.CoutModele;
import postulat.stockage.Reglages;

/** Le modèle de langage : vérifier qu'il répond, et savoir ce qu'il coûte (EN-5, R5). */
final class Modeles {

    private Modeles() {}

    @Command(name = "modele", description = "Le modèle de langage : essai de connexion et coûts (EN-5).",
            subcommands = {Essai.class, Couts.class})
    static final class CommandeModele extends Commande {

        @Override
        public Integer call() {
            spec.commandLine().usage(sortie());
            return 0;
        }
    }

    @Command(name = "essai",
            description = "Vérifie la clé d'API et le modèle d'une gamme par un appel minuscule, journalisé, et en affiche le coût.")
    static final class Essai extends Commande {

        /** La forme de la réponse d'essai : une phrase. */
        public record Salut(String message) {}

        static final String CONSIGNES = "Tu réponds à un essai de connexion de l'application postulat. Réponds en français.";
        static final String CONTENU = "Confirme en une phrase courte que tu es prêt.";

        @Option(names = "--gamme", paramLabel = "GAMME", defaultValue = "economique",
                description = "economique ou capable. Par défaut : ${DEFAULT-VALUE}.")
        Gamme gamme;

        @Override
        public Integer call() {
            Reglages reglages = racine().donnees().reglages();
            String modele = gamme == Gamme.CAPABLE ? reglages.modeleCapable() : reglages.modeleEconomique();
            try (Base base = ouvrirBase()) {
                Reponse<Salut> r = modele(base, reglages)
                        .demander(Demande.horsCandidature("essai", gamme, CONSIGNES, CONTENU), Salut.class);
                PrintWriter out = sortie();
                out.println("Modèle " + modele + " (gamme " + Libelles.gamme(gamme) + ") : « " + r.valeur().message() + " »");
                out.println(consommation(r.consommation()));
                out.println("L'appel est journalisé. Cumul : postulat modele couts");
            }
            return 0;
        }

        static String consommation(Consommation c) {
            return "Consommation : " + c.jetonsEntree() + " jetons en entrée, " + c.jetonsSortie() + " en sortie, "
                    + c.jetonsCacheLus() + " lus en cache, " + c.jetonsCacheEcrits() + " écrits en cache — coût "
                    + Libelles.dollars(c.coutMicroDollars()) + ", " + c.duree().toMillis() + " ms.";
        }
    }

    @Command(name = "couts", description = "Le cumul des coûts du modèle, mois par mois (EN-5).")
    static final class Couts extends Commande {

        @Override
        public Integer call() {
            Map<YearMonth, CoutModele> parMois;
            try (Base base = ouvrirBase()) {
                parMois = base.coutsModeleParMois();
            }
            PrintWriter out = sortie();
            if (parMois.isEmpty()) {
                out.println("Aucun appel au modèle pour l'instant. Pour vérifier la connexion : postulat modele essai");
                return 0;
            }
            out.println("Coûts du modèle de langage, par mois (temps universel)");
            int appels = 0;
            long total = 0;
            for (var mois : parMois.entrySet()) {
                out.println("  " + mois.getKey() + "   " + Libelles.appels(mois.getValue().appels()) + "   "
                        + Libelles.dollars(mois.getValue().coutMicroDollars()));
                appels += mois.getValue().appels();
                total += mois.getValue().coutMicroDollars();
            }
            out.println("  total     " + Libelles.appels(appels) + "   " + Libelles.dollars(total));
            return 0;
        }
    }
}
