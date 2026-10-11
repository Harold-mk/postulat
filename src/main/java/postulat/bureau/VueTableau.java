package postulat.bureau;

import atlantafx.base.controls.Card;
import atlantafx.base.theme.Styles;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import postulat.libelles.Libelles;
import postulat.suivi.Etape;
import postulat.suivi.TableauDeBord;

/** UC-16 : l'état de la campagne d'un coup d'œil. */
final class VueTableau {

    private VueTableau() {}

    static Node de(Fenetre f, Session s) {
        TableauDeBord t = s.tableau();
        Map<UUID, Long> numeros = s.numeros();
        LocalDate aujourdhui = s.aujourdhui();

        TableauDeBord.Semaine cetteSemaine = t.semaines().get(t.semaines().size() - 1);
        ProgressBar progression = new ProgressBar(Math.min(1, cetteSemaine.envoyees() / (double) t.objectifHebdomadaire()));
        progression.setMaxWidth(Double.MAX_VALUE);
        FlowPane chiffres = new FlowPane(16, 16,
                chiffre("Envois cette semaine", cetteSemaine.envoyees() + " / " + t.objectifHebdomadaire(), progression),
                chiffre("En attente de réponse", String.valueOf(t.enAttente().size()), null),
                chiffre("Actions dues aujourd'hui", String.valueOf(t.actionsDues().size()), null),
                chiffre("Brouillons à « repérée »", String.valueOf(t.brouillons().size()), null));

        HBox graphiques = new HBox(16, repartition(t), envois(t));
        graphiques.getChildren().forEach(g -> HBox.setHgrow(g, Priority.ALWAYS));

        VBox actions = liste(t.actionsDues().stream()
                .map(e -> ligne(f, numeros.get(e.candidatureId()), Libelles.titre(numeros.get(e.candidatureId()), e),
                        Libelles.motif(e.motif()) + ", " + Libelles.retard(e, aujourdhui)))
                .toList(), "Rien à faire aujourd'hui.");
        VBox aVenir = liste(t.aVenir().stream()
                .map(e -> ligne(f, numeros.get(e.candidatureId()), Libelles.date(e.date()) + " · "
                        + Libelles.titre(numeros.get(e.candidatureId()), e), Libelles.motif(e.motif())))
                .toList(), "Aucune échéance dans les sept prochains jours.");
        VBox enAttente = liste(t.enAttente().stream()
                .map(c -> ligne(f, numeros.get(c.id()), Libelles.titre(numeros.get(c.id()), c), "envoyée le "
                        + Libelles.date(c.dateEntreeEtape()) + ", " + Libelles.ilYA(c.dateEntreeEtape(), aujourdhui)))
                .toList(), "Aucune candidature en attente.");
        VBox brouillons = liste(t.brouillons().stream()
                .map(c -> ligne(f, numeros.get(c.id()), Libelles.titre(numeros.get(c.id()), c), "repérée "
                        + Libelles.ilYA(c.dateEntreeEtape(), aujourdhui)))
                .toList(), "Aucun brouillon.");

        return Ui.page("Tableau de bord", "Au " + Ui.jour(aujourdhui), chiffres, graphiques,
                Ui.rubrique("Actions dues aujourd'hui, la plus urgente d'abord", actions),
                Ui.rubrique("Échéances des sept prochains jours", aVenir),
                Ui.rubrique("En attente de réponse, la plus ancienne d'abord", enAttente),
                Ui.rubrique("Brouillons restés à « repérée », le plus ancien d'abord", brouillons));
    }

    private static Card chiffre(String nom, String valeur, Node detail) {
        Label n = Ui.discret(nom);
        Label v = new Label(valeur);
        v.getStyleClass().add(Styles.TITLE_1);
        VBox corps = new VBox(4, n, v);
        if (detail != null) {
            corps.getChildren().add(detail);
        }
        Card c = new Card();
        c.setBody(corps);
        c.setPrefWidth(220);
        return c;
    }

    private static Node repartition(TableauDeBord t) {
        int plus = t.repartition().values().stream().mapToInt(Integer::intValue).max().orElse(0);
        NumberAxis nombre = new NumberAxis(0, plus + 1, 1);
        nombre.setMinorTickVisible(false);
        BarChart<String, Number> g = new BarChart<>(new CategoryAxis(), nombre);
        g.setTitle("Répartition par étape");
        g.setLegendVisible(false);
        g.setAnimated(false);
        g.setPrefHeight(260);
        XYChart.Series<String, Number> serie = new XYChart.Series<>();
        for (Map.Entry<Etape, Integer> e : t.repartition().entrySet()) {
            if (e.getValue() > 0 || !e.getKey().estTerminale()) {
                serie.getData().add(new XYChart.Data<>(Ui.etape(e.getKey()), e.getValue()));
            }
        }
        g.getData().add(serie);
        return g;
    }

    private static Node envois(TableauDeBord t) {
        BarChart<String, Number> g = new BarChart<>(new CategoryAxis(), new NumberAxis(0, Math.max(t.objectifHebdomadaire(),
                t.semaines().stream().mapToInt(TableauDeBord.Semaine::envoyees).max().orElse(0)), 5));
        g.setTitle("Envois par semaine — objectif " + t.objectifHebdomadaire());
        g.setLegendVisible(false);
        g.setAnimated(false);
        g.setPrefHeight(260);
        XYChart.Series<String, Number> serie = new XYChart.Series<>();
        for (TableauDeBord.Semaine s : t.semaines()) {
            serie.getData().add(new XYChart.Data<>("sem. du " + Libelles.date(s.lundi()).substring(0, 5)
                    + (s.enCours() ? " (en cours)" : s.partielle() ? " (partielle)" : ""), s.envoyees()));
        }
        g.getData().add(serie);
        return g;
    }

    /** Une ligne cliquable : le titre mène à la fiche. */
    static Node ligne(Fenetre f, long numero, String titre, String detail) {
        Hyperlink lien = new Hyperlink(titre);
        lien.setOnAction(e -> f.ouvrirFiche(numero));
        HBox l = new HBox(8, lien, Ui.discret(detail));
        l.setAlignment(Pos.CENTER_LEFT);
        return l;
    }

    static VBox liste(List<Node> lignes, String sinon) {
        VBox v = new VBox(2);
        if (lignes.isEmpty()) {
            v.getChildren().add(Ui.discret(sinon));
        } else {
            v.getChildren().addAll(lignes);
        }
        return v;
    }
}
