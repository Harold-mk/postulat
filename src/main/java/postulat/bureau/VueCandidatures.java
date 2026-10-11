package postulat.bureau;

import atlantafx.base.theme.Styles;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import postulat.libelles.Libelles;
import postulat.stockage.Numerotee;
import postulat.suivi.Etape;

/** CA-6.7 : toutes les candidatures, filtrées par étape ou par texte ; un double-clic ouvre la fiche. */
final class VueCandidatures {

    private static final String EN_COURS = "En cours";
    private static final String TOUTES = "Toutes";

    private VueCandidatures() {}

    static Node de(Fenetre f, Session s) {
        List<Numerotee> toutes = s.candidatures();
        FilteredList<Numerotee> affichees = new FilteredList<>(FXCollections.observableArrayList(toutes));

        TextField recherche = new TextField();
        recherche.setPromptText("Entreprise ou poste");
        recherche.setPrefColumnCount(24);
        List<String> filtres = new ArrayList<>(List.of(EN_COURS, TOUTES));
        Arrays.stream(Etape.values()).forEach(e -> filtres.add(Ui.etape(e)));
        ComboBox<String> etape = new ComboBox<>(FXCollections.observableArrayList(filtres));
        etape.setValue(EN_COURS);
        Runnable filtrer = () -> affichees.setPredicate(n -> retenue(n, etape.getValue(), recherche.getText()));
        recherche.textProperty().addListener((o, a, b) -> filtrer.run());
        etape.valueProperty().addListener((o, a, b) -> filtrer.run());
        filtrer.run();
        HBox barre = new HBox(12, recherche, etape);
        barre.setAlignment(Pos.CENTER_LEFT);

        TableView<Numerotee> table = new TableView<>(affichees);
        table.getStyleClass().addAll(Styles.STRIPED, Styles.BORDERED);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().add(colonne("N°", 60, n -> "#" + n.numero()));
        table.getColumns().add(colonne("Entreprise", 220, n -> n.candidature().entreprise()));
        table.getColumns().add(colonne("Poste", 280, n -> n.candidature().poste()));
        table.getColumns().add(colonne("Étape", 130, n -> Ui.etape(n.candidature().etape())));
        TableColumn<Numerotee, java.time.LocalDate> depuis = new TableColumn<>("Depuis le");
        depuis.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().candidature().dateEntreeEtape()));
        depuis.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(java.time.LocalDate d, boolean vide) {
                super.updateItem(d, vide);
                setText(vide || d == null ? null : Libelles.date(d));
            }
        });
        depuis.setPrefWidth(110);
        table.getColumns().add(depuis);
        table.setPlaceholder(new Label(toutes.isEmpty() ? "Aucune candidature pour l'instant : « Nouvelle candidature », en haut."
                : "Aucune candidature ne correspond."));
        table.setRowFactory(t -> {
            TableRow<Numerotee> ligne = new TableRow<>();
            ligne.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !ligne.isEmpty()) {
                    f.ouvrirFiche(ligne.getItem().numero());
                }
            });
            return ligne;
        });
        table.setPrefHeight(560);

        return Ui.page("Candidatures", toutes.size() + " au total · un double-clic ouvre la fiche", new VBox(12, barre, table));
    }

    private static TableColumn<Numerotee, String> colonne(String titre, double largeur, java.util.function.Function<Numerotee, String> valeur) {
        TableColumn<Numerotee, String> c = new TableColumn<>(titre);
        c.setCellValueFactory(x -> new ReadOnlyStringWrapper(valeur.apply(x.getValue())));
        c.setPrefWidth(largeur);
        return c;
    }

    /** Le filtre de la liste : une étape, ou celles en cours, et un texte cherché sans casse ni accents. */
    static boolean retenue(Numerotee n, String etape, String texte) {
        boolean bonneEtape = switch (etape) {
            case EN_COURS -> !n.candidature().etape().estTerminale();
            case TOUTES -> true;
            default -> Ui.etape(n.candidature().etape()).equals(etape);
        };
        return bonneEtape && (texte == null || texte.isBlank()
                || cle(n.candidature().entreprise() + " " + n.candidature().poste()).contains(cle(texte)));
    }

    private static String cle(String texte) {
        return Normalizer.normalize(texte, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).strip();
    }
}
