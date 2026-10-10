package postulat.cli;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.MissingParameterException;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;
import picocli.CommandLine.TypeConversionException;
import picocli.CommandLine.UnmatchedArgumentException;
import postulat.stockage.Donnees;
import postulat.stockage.ErreurStockage;
import postulat.suivi.RefusMetier;

/**
 * Point d'entrée de la ligne de commande : une sous-commande par cas d'utilisation (pile technique
 * § 5). Codes de sortie : 0 fait, 1 refusé par une règle, 2 saisie invalide, 3 erreur technique.
 */
@Command(
        name = "postulat",
        description = "Suivi d'une campagne de candidatures de stage. Rien n'est jamais envoyé : "
                + "postulat prépare, mesure, rappelle et archive.",
        synopsisSubcommandLabel = "COMMANDE",
        subcommands = {
            Consultations.Tableau.class,
            Consultations.Actions.class,
            Consultations.Liste.class,
            Consultations.Voir.class,
            Saisies.Ajouter.class,
            Saisies.Avancer.class,
            Saisies.Rouvrir.class,
            Saisies.Relancer.class,
            Saisies.Planifier.class,
            Saisies.Entretien.class,
            Saisies.RetourPromis.class,
            Saisies.Interaction.class,
            Saisies.AjouterContact.class,
            Saisies.Noter.class,
            Archives.Sauvegarder.class,
            Archives.Restaurer.class,
        },
        footer = {"", "Exemples :",
            "  postulat ajouter \"Acme\" \"Stage data\" --etape envoyée --le 01/10/2026",
            "  postulat actions",
            "  postulat relancer 3"})
public final class Postulat implements Callable<Integer> {

    static final int REFUSE = 1;
    static final int SAISIE_INVALIDE = 2;
    static final int ERREUR = 3;

    @Option(names = {"-h", "--aide"}, usageHelp = true, scope = ScopeType.INHERIT,
            description = "Affiche l'aide de la commande.")
    boolean aide;

    @Option(names = "--donnees", paramLabel = "REPERTOIRE", scope = ScopeType.INHERIT,
            description = "Répertoire des données. Par défaut : %%LOCALAPPDATA%%\\postulat sous Windows, "
                    + "$XDG_DATA_HOME/postulat ailleurs.")
    Path repertoire;

    @Spec
    CommandSpec spec;

    private final Clock horloge;
    private final BufferedReader entree;

    Postulat(Clock horloge, BufferedReader entree) {
        this.horloge = horloge;
        this.entree = entree;
    }

    public static void main(String[] arguments) {
        BufferedReader clavier = new BufferedReader(new InputStreamReader(System.in));
        System.exit(ligneDeCommande(Clock.systemDefaultZone(), clavier).execute(arguments));
    }

    static CommandLine ligneDeCommande(Clock horloge, BufferedReader entree) {
        CommandLine ligne = new CommandLine(new Postulat(horloge, entree));
        Conversions.enregistrer(ligne, horloge);
        franciser(ligne);
        ligne.setParameterExceptionHandler((e, arguments) -> {
            CommandLine fautive = e.getCommandLine();
            fautive.getErr().println(enFrancais(e));
            fautive.getErr().println("Aide : " + fautive.getCommandSpec().qualifiedName() + " --aide");
            return SAISIE_INVALIDE;
        });
        ligne.setExecutionExceptionHandler((e, fautive, resultat) -> {
            if (e instanceof RefusMetier || e instanceof Refus) {
                fautive.getErr().println(e.getMessage());
                return REFUSE;
            }
            fautive.getErr().println(e instanceof ErreurStockage ? e.getMessage() : "Erreur inattendue : " + e);
            return ERREUR;
        });
        return ligne;
    }

    /** Sans sous-commande, l'aide. */
    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return 0;
    }

    Donnees donnees() {
        Path choisi = repertoire != null ? repertoire : Donnees.repertoireParDefaut(
                System.getenv(), System.getProperty("os.name"), Path.of(System.getProperty("user.home")));
        return new Donnees(choisi, horloge);
    }

    LocalDate aujourdhui() {
        return LocalDate.now(horloge);
    }

    BufferedReader entree() {
        return entree;
    }

    /** Les erreurs de saisie les plus courantes, dites en français ; les autres restent dans les mots de picocli. */
    static String enFrancais(ParameterException e) {
        if (e instanceof MissingParameterException manquant) {
            return "Manquant : " + manquant.getMissing().stream().map(Postulat::nom).collect(Collectors.joining(", ")) + ".";
        }
        if (e instanceof UnmatchedArgumentException inattendu) {
            return "Argument inattendu : " + String.join(" ", inattendu.getUnmatched()) + ".";
        }
        if (e.getCause() instanceof TypeConversionException conversion && e.getArgSpec() != null) {
            return "Valeur refusée pour " + nom(e.getArgSpec()) + " : " + conversion.getMessage();
        }
        return e.getMessage();
    }

    private static String nom(ArgSpec argument) {
        return argument instanceof OptionSpec option ? option.longestName() : argument.paramLabel();
    }

    private static void franciser(CommandLine ligne) {
        ligne.getCommandSpec().usageMessage()
                .autoWidth(true)
                .synopsisHeading("Usage : ")
                .descriptionHeading("%n")
                .parameterListHeading("%nParamètres :%n")
                .optionListHeading("%nOptions :%n")
                .commandListHeading("%nCommandes :%n");
        ligne.getSubcommands().values().forEach(Postulat::franciser);
    }
}
