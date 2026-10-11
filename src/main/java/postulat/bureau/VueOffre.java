package postulat.bureau;

import atlantafx.base.theme.Styles;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javafx.geometry.HPos;
import javafx.geometry.VPos;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import postulat.bureau.Ui.Ton;
import postulat.libelles.Libelles;
import postulat.production.Couverture;
import postulat.production.Exigence;
import postulat.production.OffreAnalysee;
import postulat.production.Rapprochement;
import postulat.stockage.Numerotee;
import postulat.stockage.Offre;
import postulat.suivi.Candidature;
import postulat.suivi.Etape;

/**
 * UC-05 et UC-06 : l'offre telle qu'on la lit pour décider. Sa fiche, chaque exigence couverte ou en
 * lacune avec ce qui la justifie, la note d'adéquation ; puis la décision, qui reste au candidat. Ce qui
 * manque encore — analyse, rapprochement — est dit, avec de quoi y remédier.
 */
final class VueOffre {

    private VueOffre() {}

    static Node de(Fenetre f, Session s, long numero) {
        Session.Fiche fiche = s.fiche(numero);
        Numerotee n = fiche.numerotee();
        Candidature c = n.candidature();
        String titre = "Offre — " + Libelles.titre(numero, c);
        if (fiche.offre().isEmpty()) {
            return Ui.page(titre, "", retour(f, numero),
                    Ui.encadre("Candidature saisie à la main, sans offre : il n'y a rien à analyser.", Ton.NEUTRE));
        }
        Offre o = fiche.offre().get();
        boolean figee = !fiche.versions().isEmpty();
        String sousTitre = "Texte conservé : " + o.texteBrut().length() + " caractères, reçu le "
                + Libelles.date(LocalDate.ofInstant(o.recueLe(), s.horloge().getZone())) + " · " + o.source();

        List<Node> contenu = new ArrayList<>();
        contenu.add(actions(f, s, n, o, figee));
        if (figee) {
            contenu.add(Ui.encadre("Un dossier a été validé : l'offre, son analyse et son rapprochement sont figés avec lui (CA-6.8).",
                    Ton.NEUTRE));
        }
        if (o.analyse().isEmpty()) {
            contenu.add(Ui.encadre("Non analysée : le modèle était indisponible quand l'offre a été ajoutée. Son texte est conservé "
                    + "tel quel ; « Analyser l'offre » la fera lire.", Ton.ATTENTION));
        } else {
            OffreAnalysee a = o.analyse().get();
            contenu.add(Ui.rubrique("Fiche", fiche(a)));
            if (!a.missions().isEmpty()) {
                VBox missions = new VBox(4);
                a.missions().forEach(m -> missions.getChildren().add(Ui.texte("•  " + m)));
                contenu.add(Ui.rubrique("Missions", missions));
            }
            contenu.add(Ui.rubrique("Adéquation au profil", adequation(a, o.rapprochement())));
            if (!a.exigences().isEmpty()) {
                contenu.add(Ui.rubrique("Exigences", exigences(a, o.rapprochement())));
            }
        }
        Label texte = Ui.texte(o.texteBrut());
        TitledPane brut = new TitledPane("Texte de l'offre, tel qu'il a été reçu", texte);
        brut.setExpanded(false);
        contenu.add(brut);
        return Ui.page(titre, sousTitre, contenu.toArray(Node[]::new));
    }

    private static Button retour(Fenetre f, long numero) {
        Button b = new Button("← Fiche");
        b.getStyleClass().add(Styles.FLAT);
        b.setOnAction(e -> f.ouvrirFiche(numero));
        return b;
    }

    /** La décision (UC-06), puis ce qui peut se refaire. */
    private static FlowPane actions(Fenetre f, Session s, Numerotee n, Offre o, boolean figee) {
        long numero = n.numero();
        Etape etape = n.candidature().etape();
        FlowPane barre = new FlowPane(8, 8, retour(f, numero));
        if (etape == Etape.REPEREE) {
            Button poursuivre = Ui.bouton("Poursuivre : à préparer", () -> VueFiche.date(f, "Poursuivre cette offre : « à préparer »",
                    s.aujourdhui(), jour -> s.avancer(numero, Etape.A_PREPARER, jour)));
            poursuivre.getStyleClass().add(Styles.ACCENT);
            barre.getChildren().addAll(poursuivre, Ui.bouton("Écarter…", () -> VueFiche.ecarter(f, s, numero)));
        }
        if (etape == Etape.A_PREPARER || etape == Etape.PRETE) {
            Button dossier = Ui.bouton("Dossier →", () -> f.ouvrirDossier(numero));
            if (etape == Etape.A_PREPARER) {
                dossier.getStyleClass().add(Styles.ACCENT);
            }
            barre.getChildren().add(dossier);
        }
        if (!figee) {
            if (o.analyse().isEmpty()) {
                Button analyser = Ui.bouton("Analyser l'offre", () -> analyser(f, s, numero, false));
                analyser.getStyleClass().add(Styles.ACCENT);
                barre.getChildren().add(analyser);
            } else {
                barre.getChildren().add(Ui.bouton(o.rapprochement().isEmpty() ? "Rapprocher du profil" : "Rapprocher de nouveau",
                        () -> analyser(f, s, numero, false)));
                barre.getChildren().add(Ui.bouton("Tout réanalyser", () -> analyser(f, s, numero, true)));
            }
        }
        return barre;
    }

    /** Analyse — si besoin, ou entièrement avec {@code tout} —, puis rapproche du profil : des appels au modèle. */
    static void analyser(Fenetre f, Session s, long numero, boolean tout) {
        Travail t = new Travail(tout ? "Nouvelle analyse de l'offre" : "Analyse et rapprochement",
                "L'offre est lue par le modèle économique, puis rapprochée de votre profil : chaque exigence, couverte ou en lacune.");
        t.lancer(info -> {
            s.offres().analyser(numero, tout, info);
            return s.fiche(numero).offre().flatMap(Offre::rapprochement);
        }, r -> {
            f.rafraichir();
            return r.map(x -> "Adéquation : " + x.note() + "/100, " + x.lacunes().size() + " lacune(s).")
                    .orElse("L'offre est analysée, mais pas rapprochée du profil : voir ci-dessus.");
        });
        t.afficher();
    }

    private static Node fiche(OffreAnalysee a) {
        GridPane g = new GridPane();
        g.setHgap(16);
        g.setVgap(4);
        int ligne = 0;
        for (String[] champ : new String[][] {{"Poste", a.poste()}, {"Entreprise", a.entreprise()}, {"Lieu", a.lieu()},
                {"Contrat", a.contrat()}, {"Début", a.debut()}, {"Durée", a.duree()}, {"Niveau", a.niveau()}}) {
            if (!champ[1].isBlank()) {
                g.add(Ui.discret(champ[0]), 0, ligne);
                g.add(Ui.texte(champ[1]), 1, ligne++);
            }
        }
        return g;
    }

    private static Node adequation(OffreAnalysee a, Optional<Rapprochement> rapprochement) {
        if (a.exigences().isEmpty()) {
            return Ui.discret("Aucune exigence relevée dans l'offre : rapprochement sans objet.");
        }
        if (rapprochement.isEmpty()) {
            return Ui.encadre("Pas encore rapprochée du profil : sans profil utilisable, ses lacunes sont inconnues, et aucun dossier "
                    + "ne peut s'écrire. Quand le profil est prêt : « Rapprocher du profil ».", Ton.ATTENTION);
        }
        Rapprochement r = rapprochement.get();
        Label note = new Label(r.note() + "/100");
        note.getStyleClass().add(Styles.TITLE_1);
        ProgressBar barre = new ProgressBar(r.note() / 100.0);
        barre.setPrefWidth(240);
        Ui.colorer(barre, r.note() >= 70 ? Ton.SUCCES : r.note() >= 40 ? Ton.ATTENTION : Ton.DANGER);
        long couvertes = r.couvertures().stream().filter(Couverture::couverte).count();
        VBox detail = new VBox(6, barre, Ui.discret(couvertes + " exigence(s) couverte(s) sur " + r.couvertures().size() + ", "
                + r.lacunes().size() + " lacune(s)"));
        HBox haut = new HBox(20, note, detail);
        haut.setAlignment(Pos.CENTER_LEFT);
        return new VBox(8, haut, Ui.texte(r.justification()));
    }

    /** Chaque exigence, son état, les faits qui la couvrent, la justification. */
    private static Node exigences(OffreAnalysee a, Optional<Rapprochement> rapprochement) {
        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(10);
        ColumnConstraints etroite = new ColumnConstraints();
        etroite.setHalignment(HPos.LEFT);
        ColumnConstraints large = new ColumnConstraints();
        large.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(etroite, etroite, large);
        int ligne = 0;
        if (rapprochement.isEmpty()) {
            for (Exigence e : a.exigences()) {
                g.add(Ui.pastille("à rapprocher", Ton.NEUTRE), 0, ligne);
                g.add(importance(e), 1, ligne);
                g.add(Ui.texte(e.libelle()), 2, ligne++);
            }
            return g;
        }
        for (Couverture c : rapprochement.get().couvertures()) {
            g.add(c.couverte() ? Ui.pastille("couverte", Ton.SUCCES) : Ui.pastille("lacune", Ton.DANGER), 0, ligne);
            g.add(importance(c.exigence()), 1, ligne);
            Label libelle = Ui.texte(c.exigence().libelle());
            libelle.getStyleClass().add(Styles.TEXT_BOLD);
            FlowPane faits = new FlowPane(6, 4);
            c.faits().forEach(id -> faits.getChildren().add(Ui.pastille(id, Ton.ACCENT)));
            VBox detail = new VBox(4, libelle);
            if (!faits.getChildren().isEmpty()) {
                detail.getChildren().add(faits);
            }
            if (!c.justification().isBlank()) {
                detail.getChildren().add(Ui.discret(c.justification()));
            }
            g.add(detail, 2, ligne++);
        }
        g.getChildren().forEach(x -> GridPane.setValignment(x, VPos.TOP));
        return g;
    }

    private static Label importance(Exigence e) {
        return e.obligatoire() ? Ui.pastille("obligatoire", Ton.ATTENTION) : Ui.pastille("souhaitée", Ton.NEUTRE);
    }

    /** Entreprise, poste : ce qu'on a saisi, s'il y en a. */
    static Optional<String> saisi(String texte) {
        return Stream.ofNullable(texte).map(String::strip).filter(x -> !x.isEmpty()).findFirst();
    }
}
