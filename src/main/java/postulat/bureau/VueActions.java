package postulat.bureau;

import atlantafx.base.controls.Card;
import atlantafx.base.theme.Styles;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import postulat.libelles.Libelles;
import postulat.suivi.Echeance;
import postulat.suivi.Etape;

/**
 * UC-14 : ce qu'il y a à faire aujourd'hui, le plus urgent d'abord. Ce qui se fait d'un geste — une
 * relance faite, une candidature à classer sans suite — se fait ici ; le reste mène à la fiche.
 */
final class VueActions {

    private VueActions() {}

    static Node de(Fenetre f, Session s) {
        LocalDate aujourdhui = s.aujourdhui();
        List<Echeance> dues = s.actions();
        Map<UUID, Long> numeros = s.numeros();
        if (dues.isEmpty()) {
            // UC-14 A1 : le dire, plutôt qu'afficher une liste vide.
            String prochaine = s.prochaine().map(e -> "Prochaine échéance le " + Libelles.date(e.date()) + " : "
                    + Libelles.titre(numeros.get(e.candidatureId()), e) + " · " + Libelles.motif(e.motif())).orElse("Aucune échéance en vue.");
            return Ui.page("Actions du jour", Ui.jour(aujourdhui), Ui.texte("Aucune action due aujourd'hui."), Ui.discret(prochaine));
        }
        VBox cartes = new VBox(12);
        for (Echeance e : dues) {
            long numero = numeros.get(e.candidatureId());
            cartes.getChildren().add(carte(f, s, e, numero, aujourdhui));
        }
        return Ui.page("Actions du jour", dues.size() + " à faire le " + Ui.jour(aujourdhui) + ", la plus urgente d'abord", cartes);
    }

    private static Card carte(Fenetre f, Session s, Echeance e, long numero, LocalDate aujourdhui) {
        Hyperlink titre = new Hyperlink(Libelles.titre(numero, e));
        titre.setOnAction(x -> f.ouvrirFiche(numero));
        Label retard = new Label(Libelles.retard(e, aujourdhui));
        retard.getStyleClass().add(e.joursDeRetard(aujourdhui) > 0 ? Styles.DANGER : Styles.ACCENT);
        VBox texte = new VBox(4, titre, Ui.texte(Libelles.motif(e.motif())), retard);
        HBox ligne = new HBox(16, texte, Ui.ressort());
        ligne.setAlignment(Pos.CENTER_LEFT);
        switch (e.motif()) {
            case Echeance.Motif.Relancer r -> ligne.getChildren().add(bouton("Relance faite aujourd'hui", () -> {
                s.relancer(numero, aujourdhui);
                f.rafraichir();
            }));
            case Echeance.Motif.ProposerSansSuite p -> ligne.getChildren().add(bouton("Classer « sans suite »", () -> {
                s.avancer(numero, Etape.SANS_SUITE, aujourdhui);
                f.rafraichir();
            }));
            default -> { }
        }
        Button ouvrir = new Button("Ouvrir");
        ouvrir.setOnAction(x -> f.ouvrirFiche(numero));
        ligne.getChildren().add(ouvrir);
        Card c = new Card();
        c.setBody(ligne);
        return c;
    }

    private static Button bouton(String texte, Runnable action) {
        Button b = new Button(texte);
        b.getStyleClass().add(Styles.ACCENT);
        b.setOnAction(x -> {
            try {
                action.run();
            } catch (RuntimeException ex) {
                Ui.alerte(ex);
            }
        });
        return b;
    }
}
