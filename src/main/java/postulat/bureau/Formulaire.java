package postulat.bureau;

import atlantafx.base.theme.Styles;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
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
    private final Map<Node, Label> libelles = new HashMap<>();
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
        return champ(libelle, t);
    }

    TextArea paragraphe(String libelle) {
        return paragraphe(libelle, "", 4);
    }

    TextArea paragraphe(String libelle, String valeur, int rangs) {
        TextArea t = new TextArea(valeur);
        t.setPrefRowCount(rangs);
        t.setPrefColumnCount(28);
        t.setWrapText(true);
        return champ(libelle, t);
    }

    DatePicker date(String libelle, LocalDate valeur) {
        DatePicker d = new DatePicker(valeur);
        d.setConverter(new LocalDateStringConverter(DateTimeFormatter.ofPattern("dd/MM/uuuu"), null));
        return champ(libelle, d);
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
        return champ(libelle, c);
    }

    /** Une case à cocher, sur toute la largeur. */
    CheckBox caseACocher(String libelle) {
        CheckBox c = new CheckBox(libelle);
        c.setWrapText(true);
        c.setMaxWidth(420);
        grille.add(c, 1, lignes++);
        return c;
    }

    /** N'importe quel champ, son libellé à gauche. */
    <N extends Node> N champ(String libelle, N champ) {
        Label l = new Label(libelle);
        libelles.put(champ, l);
        grille.add(l, 0, lignes);
        grille.add(champ, 1, lignes++);
        GridPane.setHgrow(champ, Priority.ALWAYS);
        return champ;
    }

    /** Montre ou cache un champ et son libellé : la ligne cachée ne prend pas de place. */
    void montrer(Node champ, boolean visible) {
        for (Node n : new Node[] {champ, libelles.get(champ)}) {
            if (n != null) {
                n.setVisible(visible);
                n.setManaged(visible);
            }
        }
        if (dialogue.getDialogPane().getScene() != null && dialogue.getDialogPane().getScene().getWindow() != null) {
            dialogue.getDialogPane().getScene().getWindow().sizeToScene();
        }
    }

    /** Le champ d'un libellé, ou la case à cocher de ce texte : pour qu'un pilote le remplisse. */
    Node champ(String libelle) {
        return libelles.entrySet().stream().filter(e -> e.getValue().getText().equals(libelle)).map(Map.Entry::getKey).findFirst()
                .or(() -> grille.getChildren().stream().filter(n -> n instanceof CheckBox c && c.getText().equals(libelle)).findFirst())
                .orElseThrow(() -> new IllegalArgumentException("Aucun champ « " + libelle + " » dans « " + titre() + " »."));
    }

    String titre() {
        return dialogue.getHeaderText();
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
        Pilote pilote = Pilote.Actif.pilote;
        if (pilote != null) {
            if (!pilote.remplir(this)) {
                return false;
            }
            enregistrer.run();
            return true;
        }
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
