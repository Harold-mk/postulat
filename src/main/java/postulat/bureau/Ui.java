package postulat.bureau;

import atlantafx.base.theme.Styles;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import postulat.suivi.Etape;
import postulat.suivi.RefusMetier;

/** Les petites pièces communes aux écrans : titres, encadrés, messages d'erreur. */
final class Ui {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("EEEE d MMMM uuuu", Locale.FRENCH);

    private Ui() {}

    /** Une page : un titre, un sous-titre, et ce qui suit, dans une zone qui défile. */
    static Node page(String titre, String sousTitre, Node... contenu) {
        Label t = new Label(titre);
        t.getStyleClass().add(Styles.TITLE_2);
        VBox page = new VBox(16, t);
        if (!sousTitre.isEmpty()) {
            Label s = new Label(sousTitre);
            s.getStyleClass().add(Styles.TEXT_MUTED);
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

    /** « à préparer », « offre reçue » : les étapes telles qu'on les lit, sans tiret bas. */
    static String etape(Etape etape) {
        return etape.libelle().replace('_', ' ');
    }

    /** « samedi 11 octobre 2026 ». */
    static String jour(LocalDate date) {
        return JOUR.format(date);
    }

    /**
     * Montre un refus ou une erreur. Les refus du domaine et du stockage parlent déjà au candidat : leur
     * message est montré tel quel.
     */
    static void alerte(Throwable e) {
        boolean attendu = e instanceof RefusMetier || e instanceof IllegalArgumentException
                || e instanceof postulat.stockage.ErreurStockage || e instanceof postulat.ia.ErreurModele
                || e instanceof postulat.rendu.ErreurRendu;
        Alert a = new Alert(attendu ? Alert.AlertType.WARNING : Alert.AlertType.ERROR);
        a.setTitle("postulat");
        a.setHeaderText(attendu ? "Ce n'est pas possible" : "Erreur inattendue");
        a.setContentText(attendu ? e.getMessage() : e.toString());
        a.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        a.showAndWait();
    }
}
