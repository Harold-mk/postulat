package postulat.bureau;

import atlantafx.base.theme.Styles;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.util.converter.LocalDateStringConverter;

/**
 * Une boîte de saisie : des champs en colonne, et un bouton qui enregistre. Un refus du domaine laisse la
 * boîte ouverte, avec ce qu'on y a tapé : on corrige au lieu de tout ressaisir.
 */
final class Formulaire {

    private final Dialog<ButtonType> dialogue = new Dialog<>();
    private final GridPane grille = new GridPane();
    private int lignes;

    Formulaire(String titre, String explication) {
        dialogue.setTitle("postulat");
        dialogue.setHeaderText(titre);
        grille.setHgap(12);
        grille.setVgap(10);
        grille.setPadding(new Insets(8, 0, 0, 0));
        if (!explication.isEmpty()) {
            Label l = Ui.discret(explication);
            l.setMaxWidth(420);
            grille.add(l, 0, lignes++, 2, 1);
        }
        dialogue.getDialogPane().setContent(grille);
        dialogue.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        ((Button) dialogue.getDialogPane().lookupButton(ButtonType.OK)).setText("Enregistrer");
        ((Button) dialogue.getDialogPane().lookupButton(ButtonType.OK)).getStyleClass().add(Styles.ACCENT);
        ((Button) dialogue.getDialogPane().lookupButton(ButtonType.CANCEL)).setText("Annuler");
    }

    TextField texte(String libelle, String valeur) {
        TextField t = new TextField(valeur);
        t.setPrefColumnCount(28);
        return ajouter(libelle, t);
    }

    TextArea paragraphe(String libelle) {
        TextArea t = new TextArea();
        t.setPrefRowCount(4);
        t.setPrefColumnCount(28);
        t.setWrapText(true);
        return ajouter(libelle, t);
    }

    DatePicker date(String libelle, LocalDate valeur) {
        DatePicker d = new DatePicker(valeur);
        d.setConverter(new LocalDateStringConverter(DateTimeFormatter.ofPattern("dd/MM/uuuu"), null));
        return ajouter(libelle, d);
    }

    <T> ComboBox<T> choix(String libelle, List<T> valeurs, T valeur, Function<T, String> nom) {
        ComboBox<T> c = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(valeurs));
        c.setValue(valeur);
        c.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(T t) {
                return t == null ? "" : nom.apply(t);
            }

            @Override
            public T fromString(String s) {
                return null;
            }
        });
        return ajouter(libelle, c);
    }

    private <N extends Node> N ajouter(String libelle, N champ) {
        grille.add(new Label(libelle), 0, lignes);
        grille.add(champ, 1, lignes++);
        GridPane.setHgrow(champ, Priority.ALWAYS);
        return champ;
    }

    /** Le panneau de la boîte, sans l'ouvrir : pour la voir telle qu'elle s'affichera. */
    javafx.scene.control.DialogPane panneau() {
        return dialogue.getDialogPane();
    }

    /**
     * Montre la boîte. {@code enregistrer} n'est appelé qu'à la validation ; s'il lève un refus, celui-ci
     * est montré et la boîte reste ouverte.
     *
     * @return vrai si l'enregistrement a eu lieu
     */
    boolean afficher(Runnable enregistrer) {
        boolean[] fait = {false};
        dialogue.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(ActionEvent.ACTION, e -> {
            try {
                enregistrer.run();
                fait[0] = true;
            } catch (RuntimeException ex) {
                Ui.alerte(ex);
                e.consume();
            }
        });
        dialogue.showAndWait();
        return fait[0];
    }
}
