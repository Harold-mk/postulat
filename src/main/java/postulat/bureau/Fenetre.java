package postulat.bureau;

import atlantafx.base.theme.Styles;
import java.util.function.Supplier;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import postulat.stockage.Numerotee;
import postulat.suivi.Etape;

/**
 * La fenêtre : la navigation à gauche, la page au centre. Chaque page se reconstruit à partir de la
 * session à chaque affichage : après une saisie, ce qu'on voit est toujours ce que la base contient.
 */
final class Fenetre {

    private final Session session;
    private final BorderPane racine = new BorderPane();
    private final StackPane centre = new StackPane();
    private final ToggleGroup navigation = new ToggleGroup();
    private Supplier<Node> page = () -> new Label();
    private final java.util.Map<Onglet, ToggleButton> onglets = new java.util.EnumMap<>(Onglet.class);

    /** Les pages auxquelles mène la navigation. */
    enum Onglet { TABLEAU, ACTIONS, CANDIDATURES }

    private Fenetre(Session session) {
        this.session = session;
    }

    static Fenetre ouvrir(Session session) {
        Fenetre f = new Fenetre(session);
        f.construire();
        return f;
    }

    private void construire() {
        Label marque = new Label("postulat");
        marque.getStyleClass().addAll(Styles.TITLE_3, Styles.ACCENT);
        Label jour = Ui.discret(Ui.jour(session.aujourdhui()));
        Button nouvelle = new Button("Nouvelle candidature");
        nouvelle.getStyleClass().add(Styles.ACCENT);
        nouvelle.setOnAction(e -> nouvelleCandidature());
        HBox haut = new HBox(16, marque, Ui.ressort(), jour, nouvelle);
        haut.setAlignment(Pos.CENTER_LEFT);
        haut.setPadding(new Insets(12, 24, 12, 24));
        haut.getStyleClass().add(Styles.BG_SUBTLE);

        ToggleButton tableau = onglet(Onglet.TABLEAU, "Tableau de bord", () -> VueTableau.de(this, session));
        ToggleButton actions = onglet(Onglet.ACTIONS, "Actions du jour", () -> VueActions.de(this, session));
        ToggleButton candidatures = onglet(Onglet.CANDIDATURES, "Candidatures", () -> VueCandidatures.de(this, session));
        VBox gauche = new VBox(4, tableau, actions, candidatures);
        gauche.setPadding(new Insets(16, 8, 16, 8));
        gauche.setPrefWidth(200);
        gauche.getStyleClass().add(Styles.BG_SUBTLE);

        racine.setTop(haut);
        racine.setLeft(gauche);
        racine.setCenter(centre);
        aller(Onglet.TABLEAU);
    }

    /** Va à une page de la navigation, comme un clic sur son bouton. */
    void aller(Onglet onglet) {
        onglets.get(onglet).fire();
    }

    private ToggleButton onglet(Onglet cle, String nom, Supplier<Node> vue) {
        ToggleButton b = new ToggleButton(nom);
        onglets.put(cle, b);
        b.setToggleGroup(navigation);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        b.getStyleClass().add(Styles.FLAT);
        b.setOnAction(e -> {
            b.setSelected(true);
            montrer(vue);
        });
        return b;
    }

    Parent racine() {
        return racine;
    }

    /** Affiche une page, et la retient pour la reconstruire après chaque saisie. */
    void montrer(Supplier<Node> vue) {
        page = vue;
        rafraichir();
    }

    void rafraichir() {
        Node n;
        try {
            n = page.get();
        } catch (RuntimeException e) {
            n = Ui.page("Page indisponible", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        centre.getChildren().setAll(n);
    }

    void ouvrirFiche(long numero) {
        navigation.selectToggle(null);
        montrer(() -> VueFiche.de(this, session, numero));
    }

    /** UC-10 : une candidature déposée hors de l'outil, ou une offre repérée, à son étape et à sa date réelles. */
    void nouvelleCandidature() {
        Formulaire f = new Formulaire("Nouvelle candidature", "Une candidature faite ailleurs entre directement à l'étape "
                + "atteinte, à sa date réelle.");
        var entreprise = f.texte("Entreprise", "");
        var poste = f.texte("Poste", "");
        var etape = f.choix("Étape", java.util.Arrays.stream(Etape.values()).filter(x -> x != Etape.ECARTEE).toList(),
                Etape.REPEREE, Ui::etape);
        var date = f.date("Le", session.aujourdhui());
        Numerotee[] creee = {null};
        boolean fait = f.afficher(() -> {
            try {
                creee[0] = session.ajouter(entreprise.getText(), poste.getText(), etape.getValue(), date.getValue(), false);
            } catch (Session.Doublon d) {
                javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION,
                        d.getMessage() + "\n\nS'agit-il d'une autre offre ? L'ajouter quand même ?");
                a.setHeaderText("Déjà suivie");
                if (a.showAndWait().filter(r -> r == javafx.scene.control.ButtonType.OK).isEmpty()) {
                    throw new IllegalArgumentException("Rien n'a été ajouté.");
                }
                creee[0] = session.ajouter(entreprise.getText(), poste.getText(), etape.getValue(), date.getValue(), true);
            }
        });
        if (fait) {
            ouvrirFiche(creee[0].numero());
        }
    }
}
