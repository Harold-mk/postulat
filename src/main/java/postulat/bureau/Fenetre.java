package postulat.bureau;

import atlantafx.base.theme.Styles;
import java.io.File;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import postulat.bureau.Ui.Ton;
import postulat.cas.CasOffre;
import postulat.libelles.Libelles;
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
    private final Map<Onglet, ToggleButton> onglets = new EnumMap<>(Onglet.class);
    private Node bandeau;

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
        Button offre = new Button("Nouvelle offre");
        offre.getStyleClass().add(Styles.ACCENT);
        offre.setOnAction(e -> nouvelleOffre());
        Button candidature = new Button("Candidature faite ailleurs");
        candidature.setOnAction(e -> nouvelleCandidature());
        HBox haut = new HBox(16, marque, Ui.ressort(), jour, candidature, offre);
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

    /** Reconstruit la page ; un bandeau annoncé s'y montre une fois, au-dessus. */
    void rafraichir() {
        Node n;
        try {
            n = page.get();
        } catch (RuntimeException e) {
            n = Ui.page("Page indisponible", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        if (bandeau != null) {
            VBox.setVgrow(n, Priority.ALWAYS);
            VBox avecBandeau = new VBox(bandeau, n);
            VBox.setMargin(bandeau, new Insets(16, 24, 0, 24));
            bandeau = null;
            n = avecBandeau;
        }
        centre.getChildren().setAll(n);
    }

    /** Ce qu'une opération a fait, à lire en haut de la page qui suit ; il disparaît à l'affichage suivant. */
    void annoncer(String texte, Ton ton) {
        Button fermer = new Button("×");
        fermer.getStyleClass().addAll(Styles.FLAT, Styles.SMALL);
        Label l = Ui.texte(texte);
        HBox.setHgrow(l, Priority.ALWAYS);
        l.setMaxWidth(Double.MAX_VALUE);
        HBox b = Ui.encadre(new HBox(8, l, fermer), ton);
        b.setAlignment(Pos.TOP_LEFT);
        fermer.setOnAction(e -> {
            if (b.getParent() instanceof VBox v) {
                v.getChildren().remove(b);
            }
        });
        bandeau = b;
    }

    void ouvrirFiche(long numero) {
        navigation.selectToggle(null);
        montrer(() -> VueFiche.de(this, session, numero));
    }

    void ouvrirOffre(long numero) {
        navigation.selectToggle(null);
        montrer(() -> VueOffre.de(this, session, numero));
    }

    void ouvrirDossier(long numero) {
        navigation.selectToggle(null);
        montrer(() -> VueDossier.de(this, session, numero));
    }

    void ouvrirVersion(long numero, int version) {
        navigation.selectToggle(null);
        montrer(() -> VueDossier.version(this, session, numero, version));
    }

    /**
     * UC-05 : une offre repérée, collée ou lue d'un fichier. Elle est analysée et rapprochée du profil hors
     * du fil de l'interface ; la candidature entre à l'étape « repérée », et sa page s'ouvre pour décider.
     */
    void nouvelleOffre() {
        Formulaire fo = new Formulaire("Nouvelle offre", "Collez le texte complet de l'annonce, ou lisez-le d'un fichier texte. "
                + "Il est analysé par le modèle économique, puis rapproché de votre profil ; la candidature entre à l'étape "
                + "« repérée », et la décision vous revient.");
        var texte = fo.paragraphe("Texte", "", 12);
        texte.setPrefColumnCount(52);
        Path[] fichier = {null};
        Label source = Ui.discret("");
        Button lire = new Button("Lire un fichier texte…");
        lire.setOnAction(e -> {
            FileChooser choix = new FileChooser();
            choix.setTitle("Texte de l'offre");
            choix.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Texte", "*.txt"),
                    new FileChooser.ExtensionFilter("Tous les fichiers", "*.*"));
            File choisi = choix.showOpenDialog(fo.panneau().getScene().getWindow());
            if (choisi == null) {
                return;
            }
            try {
                String lu = Files.readString(choisi.toPath(), StandardCharsets.UTF_8);
                texte.setText(lu.startsWith("﻿") ? lu.substring(1) : lu);
                fichier[0] = choisi.toPath().toAbsolutePath();
                source.setText(fichier[0].getFileName().toString());
            } catch (CharacterCodingException ex) {
                Ui.alerte(new IllegalArgumentException(choisi + " n'est pas un texte en UTF-8 : enregistrez-le en UTF-8, ou collez "
                        + "son contenu."));
            } catch (IOException ex) {
                Ui.alerte(new IllegalArgumentException("Lecture impossible de " + choisi + " : " + ex.getMessage()));
            }
        });
        HBox ligneFichier = new HBox(8, lire, source);
        ligneFichier.setAlignment(Pos.CENTER_LEFT);
        fo.champ("", ligneFichier);
        var entreprise = fo.texte("Entreprise", "");
        entreprise.setPromptText("si l'offre ne la nomme pas, ou pour la corriger");
        var poste = fo.texte("Poste", "");
        poste.setPromptText("à défaut de l'analyse, ou pour le corriger");
        var date = fo.date("Repérée le", session.aujourdhui());
        String[] net = {null};
        if (!fo.afficher(() -> net[0] = CasOffre.exigerTexte(texte.getText(),
                "Collez le texte complet de l'annonce : l'entreprise, les missions, le profil recherché."))) {
            return;
        }
        String origine = fichier[0] != null ? fichier[0].toString() : "saisi dans la fenêtre";
        long[] aOuvrir = {0};
        Travail t = new Travail("Analyse de l'offre", "Le modèle économique lit l'offre ; puis chaque exigence est rapprochée de "
                + "votre profil.");
        t.lancer(info -> {
            CasOffre cas = session.offres();
            CasOffre.Soumission soumission = cas.soumettre(net[0], origine, VueOffre.saisi(entreprise.getText()),
                    VueOffre.saisi(poste.getText()), date.getValue(), existante -> Ui.surLInterface(() -> Ui.confirmer("Déjà suivie ?",
                            "Une candidature porte déjà cette entreprise et ce poste : " + Libelles.titre(existante.numero(),
                                    existante.candidature()) + ", à l'étape « " + Ui.etape(existante.candidature().etape())
                                    + " ». S'agit-il d'une autre offre ?", "L'ajouter quand même")),
                    (nom, feminin) -> Ui.surLInterface(() -> demander(nom, feminin)), info);
            if (soumission instanceof CasOffre.Ajoutee a && a.analyse().isPresent()) {
                info.accept("Rapprochement avec le profil…");
                cas.rapprocher(a.numerotee().numero(), info);
            }
            return soumission;
        }, soumission -> switch (soumission) {
            case CasOffre.DejaSuivie d -> {
                aOuvrir[0] = d.existante().numero();
                yield "Cette offre est déjà suivie : " + Libelles.titre(d.existante().numero(), d.existante().candidature())
                        + ". Rien n'a été ajouté.";
            }
            case CasOffre.Ajoutee a -> {
                aOuvrir[0] = a.numerotee().numero();
                yield "Candidature " + Libelles.titre(a.numerotee().numero(), a.numerotee().candidature())
                        + " ajoutée à l'étape « repérée »." + (a.analyse().isEmpty()
                                ? " L'offre est conservée avec son texte, non analysée : sa page permettra de l'analyser plus tard." : "");
            }
        });
        t.afficher();
        if (aOuvrir[0] > 0) {
            ouvrirOffre(aOuvrir[0]);
        }
    }

    /** Ce que ni la saisie ni l'analyse n'ont donné : l'entreprise, le poste. */
    private static Optional<String> demander(String nom, boolean feminin) {
        TextInputDialog d = new TextInputDialog();
        d.setTitle("postulat");
        d.setHeaderText(nom + " de l'offre");
        d.setContentText("L'analyse ne l'a pas trouvé" + (feminin ? "e" : "") + " :");
        return d.showAndWait();
    }

    /** UC-10 : une candidature déposée hors de l'outil, ou une offre repérée, à son étape et à sa date réelles. */
    void nouvelleCandidature() {
        Formulaire f = new Formulaire("Candidature faite ailleurs", "Une candidature faite hors de l'outil entre directement à "
                + "l'étape atteinte, à sa date réelle.");
        var entreprise = f.texte("Entreprise", "");
        var poste = f.texte("Poste", "");
        var etape = f.choix("Étape", Arrays.stream(Etape.values()).filter(x -> x != Etape.ECARTEE).toList(),
                Etape.REPEREE, Ui::etape);
        var date = f.date("Le", session.aujourdhui());
        Numerotee[] creee = {null};
        boolean fait = f.afficher(() -> {
            try {
                creee[0] = session.ajouter(entreprise.getText(), poste.getText(), etape.getValue(), date.getValue(), false);
            } catch (Session.Doublon d) {
                Alert a = new Alert(Alert.AlertType.CONFIRMATION, d.getMessage() + "\n\nS'agit-il d'une autre offre ? L'ajouter quand même ?");
                a.setHeaderText("Déjà suivie");
                if (a.showAndWait().filter(r -> r == ButtonType.OK).isEmpty()) {
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
