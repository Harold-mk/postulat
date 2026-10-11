package postulat.bureau;

import atlantafx.base.theme.Styles;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Un travail long — des appels au modèle — mené hors du fil de l'interface, dans une boîte qui en montre
 * l'avancement ligne à ligne. La boîte ne se ferme pas tant qu'il dure : un appel payé ne s'interrompt
 * pas à moitié. À la fin, elle dit ce qui a été fait, ou pourquoi rien ne l'a été.
 */
final class Travail {

    private final Dialog<Void> dialogue = new Dialog<>();
    private final TextArea journal = new TextArea();
    private final ProgressBar barre = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
    private final Label etat = Ui.texte("En cours…");
    private final Label duree = Ui.discret("");
    private final Button fermer;
    private boolean fini;
    private boolean enAttente;
    private long debut;
    private Timeline chrono;

    Travail(String titre, String explication) {
        dialogue.setTitle("postulat");
        dialogue.setHeaderText(titre);
        journal.setEditable(false);
        journal.setWrapText(true);
        journal.setPrefRowCount(10);
        journal.setPrefColumnCount(56);
        barre.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(barre, Priority.ALWAYS);
        HBox avancement = new HBox(12, barre, duree);
        VBox contenu = new VBox(10);
        if (!explication.isEmpty()) {
            Label l = Ui.discret(explication);
            l.setMaxWidth(520);
            contenu.getChildren().add(l);
        }
        contenu.getChildren().addAll(avancement, journal, etat);
        contenu.setPadding(new Insets(8, 0, 0, 0));
        VBox.setVgrow(journal, Priority.ALWAYS);
        dialogue.getDialogPane().setContent(contenu);
        dialogue.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        fermer = (Button) dialogue.getDialogPane().lookupButton(ButtonType.CLOSE);
        fermer.setText("Fermer");
        fermer.setDisable(true);
        dialogue.setOnCloseRequest(e -> {
            if (!fini) {
                e.consume();
            }
        });
    }

    /**
     * Lance le travail sur un fil à lui. {@code travail} reçoit de quoi écrire au journal ; son résultat
     * passe à {@code fin}, sur le fil de l'interface, qui rend le bilan à afficher.
     */
    <T> Task<T> lancer(Function<Consumer<String>, T> travail, Function<T, String> fin) {
        debut = System.nanoTime();
        chrono = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1), e -> montrerDuree()));
        chrono.setCycleCount(Timeline.INDEFINITE);
        chrono.play();
        Task<T> tache = new Task<>() {
            @Override
            protected T call() {
                return travail.apply(ligne -> Platform.runLater(() -> ecrire(ligne)));
            }
        };
        tache.setOnSucceeded(e -> {
            String bilan;
            try {
                bilan = fin.apply(tache.getValue());
            } catch (RuntimeException ex) {
                terminer(false, Ui.explication(ex));
                return;
            }
            terminer(true, bilan);
        });
        tache.setOnFailed(e -> terminer(false, Ui.explication(tache.getException())));
        Thread fil = new Thread(tache, "postulat-travail");
        fil.setDaemon(true);
        fil.start();
        return tache;
    }

    /** Une ligne au journal, sur le fil de l'interface ; une zone de texte ne connaît que les fins de ligne LF. */
    void ecrire(String ligne) {
        journal.appendText((journal.getText().isEmpty() ? "" : "\n") + ligne);
    }

    private void montrerDuree() {
        long s = Duration.ofNanos(System.nanoTime() - debut).toSeconds();
        duree.setText(s / 60 + ":" + String.format("%02d", s % 60));
    }

    private void terminer(boolean reussi, String bilan) {
        fini = true;
        if (enAttente) {
            enAttente = false;
            Platform.exitNestedEventLoop(this, null);
        }
        if (chrono != null) {
            chrono.stop();
        }
        montrerDuree();
        barre.setProgress(1);
        Ui.colorer(barre, reussi ? Ui.Ton.SUCCES : Ui.Ton.DANGER);
        etat.setText(bilan);
        etat.getStyleClass().add(reussi ? Styles.SUCCESS : Styles.DANGER);
        fermer.setDisable(false);
        fermer.requestFocus();
    }

    boolean fini() {
        return fini;
    }

    String journal() {
        return journal.getText();
    }

    String etat() {
        return etat.getText();
    }

    /**
     * Montre la boîte jusqu'à ce que le candidat la ferme, le travail fini. Sous un pilote, rien ne
     * s'affiche, mais l'attente est la même : une boucle d'évènements imbriquée, jusqu'à la fin du travail.
     */
    void afficher() {
        Pilote pilote = Pilote.Actif.pilote;
        if (pilote == null) {
            dialogue.showAndWait();
            return;
        }
        if (!fini) {
            enAttente = true;
            Platform.enterNestedEventLoop(this);
        }
        pilote.travail(this);
    }

    /** Le panneau de la boîte, sans l'ouvrir : pour la voir telle qu'elle s'affichera. */
    DialogPane panneau() {
        return dialogue.getDialogPane();
    }
}
