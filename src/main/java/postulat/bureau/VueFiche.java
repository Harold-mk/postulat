package postulat.bureau;

import atlantafx.base.theme.Styles;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import postulat.libelles.Libelles;
import postulat.production.OffreAnalysee;
import postulat.stockage.Numerotee;
import postulat.suivi.Canal;
import postulat.suivi.Candidature;
import postulat.suivi.Contact;
import postulat.suivi.Echeance;
import postulat.suivi.Etape;
import postulat.suivi.Evenement;
import postulat.suivi.TypeEntretien;

/**
 * UC-12 : tout sur une candidature, et ce qu'on peut y faire. Les boutons ne proposent que ce que
 * l'étape permet ; le domaine refuse le reste, et la boîte de saisie le dit.
 */
final class VueFiche {

    private VueFiche() {}

    static Node de(Fenetre f, Session s, long numero) {
        Session.Fiche fiche = s.fiche(numero);
        Numerotee n = fiche.numerotee();
        Candidature c = n.candidature();
        LocalDate aujourdhui = s.aujourdhui();

        Label etape = new Label(Ui.etape(c.etape()));
        etape.getStyleClass().addAll(Styles.TITLE_4, c.etape().estTerminale() ? Styles.TEXT_MUTED : Styles.ACCENT);
        HBox situation = new HBox(12, etape, Ui.discret("depuis le " + Libelles.date(c.dateEntreeEtape()) + ", "
                + Libelles.ilYA(c.dateEntreeEtape(), aujourdhui)));
        situation.setAlignment(Pos.BASELINE_LEFT);

        GridPane grille = new GridPane();
        grille.setHgap(32);
        grille.setVgap(24);
        grille.add(Ui.rubrique("Échéances", echeances(fiche.echeances(), aujourdhui)), 0, 0);
        grille.add(Ui.rubrique("Contacts", contacts(fiche.contacts())), 1, 0);
        grille.add(Ui.rubrique("Historique", historique(c)), 0, 1);
        grille.add(Ui.rubrique("Offre", offre(fiche)), 1, 1);
        grille.add(Ui.rubrique("Dossier", dossier(fiche, s)), 0, 2);
        grille.add(Ui.rubrique("Modèle de langage", Ui.discret(fiche.cout().appels() == 0 ? "aucun appel"
                : Libelles.appels(fiche.cout().appels()) + ", " + Libelles.dollars(fiche.cout().coutMicroDollars()))), 1, 2);

        return Ui.page(Libelles.titre(n.numero(), c), "", situation, actions(f, s, n), grille);
    }

    // --- ce qu'on peut faire ---

    private static FlowPane actions(Fenetre f, Session s, Numerotee n) {
        long numero = n.numero();
        Etape etape = n.candidature().etape();
        FlowPane barre = new FlowPane(8, 8);
        if (!etape.estTerminale()) {
            MenuButton avancer = new MenuButton("Avancer à…");
            avancer.getStyleClass().add(Styles.ACCENT);
            etape.suivantes().stream().filter(e -> e != Etape.ECARTEE).sorted().forEach(vers -> {
                MenuItem item = new MenuItem(Ui.etape(vers));
                item.setOnAction(x -> date(f, "Passer à « " + Ui.etape(vers) + " »", s.aujourdhui(), jour -> s.avancer(numero, vers, jour)));
                avancer.getItems().add(item);
            });
            barre.getChildren().add(avancer);
        }
        if (etape.peutAllerVers(Etape.ECARTEE)) {
            barre.getChildren().add(bouton("Écarter…", () -> {
                Formulaire fo = new Formulaire("Écarter cette offre", "Ne pas postuler, en disant pourquoi : le motif sert à "
                        + "comprendre, plus tard, ce qu'on écarte. L'offre et son analyse restent conservées.");
                var motif = fo.paragraphe("Motif");
                var jour = fo.date("Le", s.aujourdhui());
                if (fo.afficher(() -> s.ecarter(numero, motif.getText(), jour.getValue()))) {
                    f.rafraichir();
                }
            }));
        }
        if (etape.estTerminale()) {
            MenuButton rouvrir = new MenuButton("Rouvrir à…");
            Arrays.stream(Etape.values()).filter(e -> !e.estTerminale()).forEach(vers -> {
                MenuItem item = new MenuItem(Ui.etape(vers));
                item.setOnAction(x -> date(f, "Rouvrir à « " + Ui.etape(vers) + " »", s.aujourdhui(), jour -> s.rouvrir(numero, vers, jour)));
                rouvrir.getItems().add(item);
            });
            barre.getChildren().add(rouvrir);
        }
        if (etape == Etape.ENVOYEE) {
            barre.getChildren().add(bouton("Relance faite…", () -> date(f, "Relance faite par vous — postulat n'envoie rien",
                    s.aujourdhui(), jour -> s.relancer(numero, jour))));
        }
        if (etape == Etape.ENTRETIEN) {
            barre.getChildren().add(bouton("Entretien planifié…", () -> {
                Formulaire fo = new Formulaire("Entretien planifié", "Fixé à une date, même à venir : la veille, le préparer ; "
                        + "le jour même, remercier.");
                var type = fo.choix("Type", List.of(TypeEntretien.values()), TypeEntretien.RH, Libelles::entretien);
                var le = fo.date("Prévu le", s.aujourdhui().plusDays(1));
                var jour = fo.date("Noté le", s.aujourdhui());
                if (fo.afficher(() -> s.planifier(numero, type.getValue(), le.getValue(), jour.getValue()))) {
                    f.rafraichir();
                }
            }));
            barre.getChildren().add(bouton("Entretien passé…", () -> {
                Formulaire fo = new Formulaire("Entretien passé", "Le compte rendu, s'il y en a un, est joint en note.");
                var type = fo.choix("Type", List.of(TypeEntretien.values()), TypeEntretien.RH, Libelles::entretien);
                var compteRendu = fo.paragraphe("Compte rendu");
                var jour = fo.date("Le", s.aujourdhui());
                if (fo.afficher(() -> s.entretien(numero, type.getValue(), compteRendu.getText(), jour.getValue()))) {
                    f.rafraichir();
                }
            }));
        }
        if (!etape.estTerminale()) {
            barre.getChildren().add(bouton("Retour promis…", () -> {
                Formulaire fo = new Formulaire("Retour promis", "L'entreprise a promis une réponse pour une date : ce jour-là, "
                        + "s'il n'est rien arrivé, faire le point.");
                var pour = fo.date("Pour le", s.aujourdhui().plusDays(7));
                var jour = fo.date("Noté le", s.aujourdhui());
                if (fo.afficher(() -> s.retourPromis(numero, pour.getValue(), jour.getValue()))) {
                    f.rafraichir();
                }
            }));
            barre.getChildren().add(bouton("Échange…", () -> {
                Formulaire fo = new Formulaire("Échange avec l'entreprise", "");
                var canal = fo.choix("Par", List.of(Canal.values()), Canal.COURRIEL, Libelles::canal);
                var notes = fo.paragraphe("Notes");
                var jour = fo.date("Le", s.aujourdhui());
                if (fo.afficher(() -> s.interaction(numero, canal.getValue(), notes.getText(), jour.getValue()))) {
                    f.rafraichir();
                }
            }));
        }
        barre.getChildren().add(bouton("Contact…", () -> {
            Formulaire fo = new Formulaire("Nouveau contact", "");
            var nom = fo.texte("Nom", "");
            var role = fo.texte("Rôle", "");
            var coordonnees = fo.texte("Coordonnées", "");
            if (fo.afficher(() -> s.ajouterContact(numero, new Contact(nom.getText().strip(), role.getText().strip(),
                    coordonnees.getText().strip())))) {
                f.rafraichir();
            }
        }));
        barre.getChildren().add(bouton("Note…", () -> {
            Formulaire fo = new Formulaire("Note libre", "Permise à toute étape, même terminée.");
            var texte = fo.paragraphe("Note");
            var jour = fo.date("Le", s.aujourdhui());
            if (fo.afficher(() -> s.noter(numero, texte.getText(), jour.getValue()))) {
                f.rafraichir();
            }
        }));
        return barre;
    }

    /** Une saisie qui ne demande qu'une date : celle du jour, par défaut. */
    private static void date(Fenetre f, String titre, LocalDate parDefaut, java.util.function.Consumer<LocalDate> action) {
        Formulaire fo = new Formulaire(titre, "");
        var jour = fo.date("Le", parDefaut);
        if (fo.afficher(() -> action.accept(jour.getValue()))) {
            f.rafraichir();
        }
    }

    private static Button bouton(String texte, Runnable action) {
        Button b = new Button(texte);
        b.setOnAction(x -> {
            try {
                action.run();
            } catch (RuntimeException ex) {
                Ui.alerte(ex);
            }
        });
        return b;
    }

    // --- ce qu'on lit ---

    private static Node echeances(List<Echeance> echeances, LocalDate aujourdhui) {
        VBox v = new VBox(4);
        if (echeances.isEmpty()) {
            v.getChildren().add(Ui.discret("aucune"));
        }
        echeances.stream().sorted(java.util.Comparator.comparing(Echeance::date)).forEach(e -> {
            Label l = Ui.texte(Libelles.date(e.date()) + " · " + Libelles.motif(e.motif()));
            if (e.estAtteinte(aujourdhui)) {
                l.getStyleClass().add(e.joursDeRetard(aujourdhui) > 0 ? Styles.DANGER : Styles.ACCENT);
            }
            v.getChildren().add(l);
        });
        return v;
    }

    private static Node contacts(List<Contact> contacts) {
        VBox v = new VBox(4);
        if (contacts.isEmpty()) {
            v.getChildren().add(Ui.discret("aucun"));
        }
        contacts.forEach(c -> v.getChildren().add(Ui.texte(String.join(" · ", java.util.stream.Stream.of(c.nom(), c.role(),
                c.coordonnees()).filter(x -> !x.isBlank()).toList()))));
        return v;
    }

    private static Node historique(Candidature c) {
        VBox v = new VBox(4);
        for (Evenement e : c.journal().reversed()) {
            v.getChildren().add(Ui.texte(Libelles.date(e.date()) + "  " + Libelles.evenement(e)));
        }
        return v;
    }

    private static Node offre(Session.Fiche fiche) {
        if (fiche.offre().isEmpty()) {
            return Ui.discret("aucune, candidature saisie à la main");
        }
        var o = fiche.offre().get();
        if (o.analyse().isEmpty()) {
            return Ui.discret("texte conservé, non analysé");
        }
        OffreAnalysee a = o.analyse().get();
        VBox v = new VBox(4, Ui.texte(String.join(" · ", java.util.stream.Stream.of(a.poste(), a.entreprise(), a.lieu(), a.contrat(),
                a.duree()).filter(x -> !x.isBlank()).toList())));
        o.rapprochement().ifPresentOrElse(r -> v.getChildren().add(Ui.texte("Adéquation " + r.note() + "/100 · "
                        + (r.couvertures().size() - r.lacunes().size()) + " exigence(s) couverte(s), " + r.lacunes().size() + " lacune(s)")),
                () -> v.getChildren().add(Ui.discret(a.exigences().size() + " exigence(s), pas encore rapprochées du profil")));
        return v;
    }

    private static Node dossier(Session.Fiche fiche, Session s) {
        VBox v = new VBox(4);
        fiche.versions().forEach(x -> v.getChildren().add(Ui.texte("Version " + x.version() + " validée le "
                + Libelles.date(LocalDate.ofInstant(x.valideLe(), s.horloge().getZone()))
                + (fiche.numerotee().candidature().versionEnvoyee().equals(java.util.Optional.of(x.version())) ? ", envoyée" : ""))));
        fiche.dossier().ifPresent(d -> v.getChildren().add(Ui.discret("Brouillon généré le "
                + Libelles.date(LocalDate.ofInstant(d.genereLe(), s.horloge().getZone())) + ", note globale "
                + d.dossier().evaluation().noteGlobale() + "/100")));
        if (v.getChildren().isEmpty()) {
            v.getChildren().add(Ui.discret("aucun dossier"));
        }
        return v;
    }
}
