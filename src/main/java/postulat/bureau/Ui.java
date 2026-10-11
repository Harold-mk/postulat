package postulat.bureau;

import atlantafx.base.theme.Styles;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import postulat.cas.CasDossier;
import postulat.cas.Refus;
import postulat.libelles.Libelles;
import postulat.suivi.Etape;
import postulat.suivi.RefusMetier;

/** Les petites pièces communes aux écrans : titres, encadrés, pastilles, messages d'erreur. */
final class Ui {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("EEEE d MMMM uuuu", Locale.FRENCH);

    private Ui() {}

    /** Une page : un titre, un sous-titre, et ce qui suit, dans une zone qui défile. */
    static Node page(String titre, String sousTitre, Node... contenu) {
        Label t = new Label(titre);
        t.getStyleClass().add(Styles.TITLE_2);
        t.setWrapText(true);
        VBox page = new VBox(16, t);
        if (!sousTitre.isEmpty()) {
            Label s = new Label(sousTitre);
            s.getStyleClass().add(Styles.TEXT_MUTED);
            s.setWrapText(true);
            page.getChildren().add(s);
        }
        page.getChildren().addAll(contenu);
        page.setPadding(new Insets(24));
        ScrollPane defilement = new ScrollPane(page);
        defilement.setFitToWidth(true);
        return defilement;
    }

    /** Une rubrique : un titre de section, puis son contenu. */
    static Node rubrique(String titre, Node contenu) {
        Label t = new Label(titre);
        t.getStyleClass().add(Styles.TITLE_4);
        return new VBox(8, t, contenu);
    }

    static Label texte(String texte) {
        Label l = new Label(texte);
        l.setWrapText(true);
        return l;
    }

    static Label discret(String texte) {
        Label l = texte(texte);
        l.getStyleClass().add(Styles.TEXT_MUTED);
        return l;
    }

    static Region ressort() {
        Region r = new Region();
        javafx.scene.layout.HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /** Le ton d'une pastille ou d'un encadré : la couleur dit ce qu'il faut en penser. */
    enum Ton {
        ACCENT("accent"), SUCCES("success"), ATTENTION("warning"), DANGER("danger"), NEUTRE("neutral");

        private final String couleur;

        Ton(String couleur) {
            this.couleur = couleur;
        }
    }

    /** Une pastille : un mot court sur fond de couleur, l'origine d'une phrase ou l'état d'une exigence. */
    static Label pastille(String texte, Ton ton) {
        Label l = new Label(texte);
        l.setMinWidth(Region.USE_PREF_SIZE);
        l.setStyle("-fx-background-color: -color-" + ton.couleur + "-subtle; -fx-text-fill: -color-"
                + (ton == Ton.NEUTRE ? "fg-muted" : ton.couleur + "-fg") + "; -fx-background-radius: 4; -fx-padding: 1 6 1 6; "
                + "-fx-font-size: 0.85em;");
        return l;
    }

    /** Colore une barre de progression : sa couleur dit ce qu'il faut penser de ce qu'elle mesure. */
    static ProgressBar colorer(ProgressBar barre, Ton ton) {
        barre.setStyle("-color-progress-bar-fill: -color-" + ton.couleur + "-emphasis;");
        return barre;
    }

    /** Un encadré : ce qu'il faut savoir avant d'aller plus loin, sur toute la largeur. */
    static Node encadre(String texte, Ton ton) {
        Label l = texte(texte);
        l.setMaxWidth(Double.MAX_VALUE);
        return encadre(l, ton);
    }

    /** Encadre un contenu quelconque : un texte et ses boutons. */
    static <R extends Region> R encadre(R contenu, Ton ton) {
        contenu.setStyle("-fx-background-color: -color-" + ton.couleur + "-subtle; -fx-border-color: -color-" + ton.couleur
                + "-muted; -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 10 14 10 14;");
        return contenu;
    }

    static Button bouton(String texte, Runnable action) {
        Button b = new Button(texte);
        b.setOnAction(x -> {
            try {
                action.run();
            } catch (RuntimeException ex) {
                alerte(ex);
            }
        });
        return b;
    }

    /** « à préparer », « offre reçue » : les étapes telles qu'on les lit, sans tiret bas. */
    static String etape(Etape etape) {
        return etape.libelle().replace('_', ' ');
    }

    /** « samedi 11 octobre 2026 ». */
    static String jour(LocalDate date) {
        return JOUR.format(date);
    }

    /** Les refus du domaine, des cas et du stockage parlent déjà au candidat : leur message se montre tel quel. */
    static boolean attendue(Throwable e) {
        return e instanceof RefusMetier || e instanceof Refus || e instanceof IllegalArgumentException
                || e instanceof postulat.stockage.ErreurStockage || e instanceof postulat.ia.ErreurModele
                || e instanceof postulat.rendu.ErreurRendu || e instanceof CasDossier.EchecCouteux;
    }

    /** Ce qu'une erreur a à dire au candidat ; une génération qui échoue dit aussi ce qu'elle a coûté. */
    static String explication(Throwable e) {
        if (e instanceof CasDossier.EchecCouteux c) {
            return c.origine().getMessage() + System.lineSeparator() + "Coût engagé malgré l'échec : "
                    + Libelles.appels(c.engage().appels()) + ", " + Libelles.dollars(c.engage().coutMicroDollars()) + ".";
        }
        return attendue(e) ? e.getMessage() : e.toString();
    }

    /** Montre un refus ou une erreur ; sous un pilote, la relève, pour que le test la voie. */
    static void alerte(Throwable e) {
        if (Pilote.Actif.pilote != null) {
            throw new IllegalStateException("Alerte : " + explication(e), e);
        }
        boolean attendu = attendue(e);
        Alert a = new Alert(attendu ? Alert.AlertType.WARNING : Alert.AlertType.ERROR);
        a.setTitle("postulat");
        a.setHeaderText(attendu ? "Ce n'est pas possible" : "Erreur inattendue");
        a.setContentText(explication(e));
        a.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        a.showAndWait();
    }

    /** Demande une confirmation ; vrai si le candidat accepte. */
    static boolean confirmer(String titre, String texte, String oui) {
        Pilote pilote = Pilote.Actif.pilote;
        if (pilote != null) {
            return pilote.confirmer(titre, texte);
        }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, texte, new ButtonType(oui, ButtonType.OK.getButtonData()), ButtonType.CANCEL);
        a.setTitle("postulat");
        a.setHeaderText(titre);
        ((Button) a.getDialogPane().lookupButton(ButtonType.CANCEL)).setText("Annuler");
        a.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        return a.showAndWait().filter(r -> r.getButtonData() == ButtonType.OK.getButtonData()).isPresent();
    }

    /**
     * Pose une question depuis un travail mené hors du fil de l'interface, et attend la réponse : la
     * question, elle, s'affiche sur le fil de l'interface.
     */
    static <T> T surLInterface(Supplier<T> question) {
        if (Platform.isFxApplicationThread()) {
            return question.get();
        }
        FutureTask<T> tache = new FutureTask<>(question::get);
        Platform.runLater(tache);
        try {
            return tache.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Question interrompue.", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
        }
    }

    /**
     * Ouvre un fichier ou un répertoire comme l'Explorateur le ferait : un PDF dans son lecteur, un
     * répertoire dans une fenêtre. Hors du fil de l'interface, qui ne l'attend pas.
     */
    static void ouvrir(Path chemin) {
        if (!Files.exists(chemin)) {
            alerte(new IllegalArgumentException("Introuvable : " + chemin + ". Il a été déplacé ou supprimé hors de l'application."));
            return;
        }
        Thread fil = new Thread(() -> {
            try {
                Desktop.getDesktop().open(chemin.toFile());
            } catch (IOException | UnsupportedOperationException e) {
                Platform.runLater(() -> alerte(new IllegalArgumentException("Impossible d'ouvrir " + chemin + " : " + e.getMessage())));
            }
        }, "postulat-ouvrir");
        fil.setDaemon(true);
        fil.start();
    }
}
