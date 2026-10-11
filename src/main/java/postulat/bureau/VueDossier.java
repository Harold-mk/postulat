package postulat.bureau;

import atlantafx.base.theme.Styles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import postulat.bureau.Ui.Ton;
import postulat.cas.CasDossier;
import postulat.cas.Refus;
import postulat.libelles.Libelles;
import postulat.production.Assertion;
import postulat.production.AvisRecruteur;
import postulat.production.Cv;
import postulat.production.Dossier;
import postulat.production.Evaluation;
import postulat.production.Exigence;
import postulat.production.Lettre;
import postulat.production.Origine;
import postulat.stockage.CoutModele;
import postulat.stockage.DossierGenere;
import postulat.stockage.Numerotee;
import postulat.stockage.Reglages;
import postulat.stockage.VersionValidee;
import postulat.suivi.Candidature;
import postulat.suivi.Etape;

/**
 * Le dossier d'une candidature : le générer (UC-07), le relire phrase par phrase avec l'origine de
 * chacune (CA-3.7), le corriger à la main et le faire réévaluer (UC-08), le valider (UC-09), en verser
 * une phrase au profil (UC-04). Les appels au modèle se font hors du fil de l'interface ; les
 * corrections, qui n'en font aucun, s'enregistrent sur-le-champ.
 */
final class VueDossier {

    private VueDossier() {}

    /** La place d'une phrase dans le dossier : elle décide de son allure et de ce qu'on peut en dire. */
    enum Place { INTITULE, PRECISION, PUCE, LIGNE, LETTRE }

    static Node de(Fenetre f, Session s, long numero) {
        Session.Fiche fiche = s.fiche(numero);
        Numerotee n = fiche.numerotee();
        Candidature c = n.candidature();
        ZoneId zone = s.horloge().getZone();
        String titre = "Dossier — " + Libelles.titre(numero, c);
        boolean generable = c.etape() == Etape.A_PREPARER || c.etape() == Etape.PRETE;
        List<Node> contenu = new ArrayList<>();
        FlowPane barre = new FlowPane(8, 8, retour("← Fiche", () -> f.ouvrirFiche(numero)));
        contenu.add(barre);

        if (fiche.dossier().isEmpty()) {
            if (generable) {
                Button generer = Ui.bouton("Générer le dossier…", () -> generer(f, s, numero, false));
                generer.getStyleClass().add(Styles.ACCENT);
                barre.getChildren().add(generer);
            }
            contenu.add(Ui.encadre(generable ? "Pas encore de dossier. La génération rédige un CV ciblé et une lettre d'après votre "
                    + "profil et l'offre, vérifie que chaque phrase s'appuie sur un fait, les fait noter — filtre ATS et recruteur — "
                    + "et les révise sous le seuil."
                    : c.etape() == Etape.REPEREE ? "Pas de dossier : l'offre est seulement repérée. Pour en préparer un, poursuivez-la "
                            + "d'abord : « Poursuivre : à préparer », sur la page de l'offre."
                            : "Pas de dossier en cours. Un dossier se génère à l'étape « à préparer », ou « prête » pour une nouvelle "
                                    + "version.", Ton.NEUTRE));
            if (!fiche.versions().isEmpty()) {
                contenu.add(Ui.rubrique("Versions validées", versions(f, n, fiche.versions(), zone)));
            }
            return Ui.page(titre, "", contenu.toArray(Node[]::new));
        }

        DossierGenere g = fiche.dossier().get();
        Dossier d = g.dossier();
        String sousTitre = "Généré le " + date(g.genereLe(), zone) + (g.modele().isEmpty() ? "" : " par " + g.modele())
                + g.corrigeLe().map(le -> ", corrigé le " + date(le, zone)).orElse("") + " · " + d.assertions().size()
                + " phrases, dont " + d.assertionsDeLAuteur() + " de votre main";
        if (generable) {
            Button valider = Ui.bouton("Valider…", () -> valider(f, s, n));
            valider.getStyleClass().add(Styles.ACCENT);
            barre.getChildren().add(valider);
        }
        barre.getChildren().add(Ui.bouton("Nouvel avis du recruteur", () -> evaluer(f, s, numero)));
        if (generable) {
            barre.getChildren().add(Ui.bouton("Régénérer…", () -> generer(f, s, numero, true)));
        }
        barre.getChildren().addAll(Ui.bouton("Aperçu du CV", () -> Ui.ouvrir(apercu(s, numero, d, "cv"))),
                Ui.bouton("Aperçu de la lettre", () -> Ui.ouvrir(apercu(s, numero, d, "lettre"))));

        if (s.profilAChange(g)) {
            contenu.add(Ui.encadre("Le profil a changé depuis la génération : ce dossier n'en tient pas compte. Pour qu'il s'en serve : "
                    + "« Régénérer ».", Ton.ATTENTION));
        }
        if (g.avisPerime()) {
            contenu.add(Ui.encadre("L'avis du recruteur date d'avant vos corrections : sa note juge la version précédente. Pour un avis "
                    + "sur celle-ci : « Nouvel avis du recruteur », un appel payant.", Ton.ATTENTION));
        }
        if (!fiche.versions().isEmpty()) {
            VersionValidee derniere = fiche.versions().getLast();
            contenu.add(derniere.dossier().dossier().equals(d)
                    ? Ui.encadre("Ce brouillon est la version " + derniere.version() + ", validée telle quelle.", Ton.SUCCES)
                    : Ui.encadre("Ce brouillon diffère de la version " + derniere.version() + " validée : pour le valider à son tour, "
                            + "« Valider ».", Ton.NEUTRE));
        }
        contenu.add(Ui.rubrique("Évaluation", evaluation(d, g.avisPerime())));
        contenu.addAll(phrases(f, s, numero, d, true));
        if (!d.rapport().retirees().isEmpty()) {
            contenu.add(Ui.rubrique("Retirées pour tenir sur une page", retirees(f, s, numero, d)));
        }
        contenu.add(Ui.rubrique("Rapport", rapport(d)));
        if (!fiche.versions().isEmpty()) {
            contenu.add(Ui.rubrique("Versions validées", versions(f, n, fiche.versions(), zone)));
        }
        return Ui.page(titre, sousTitre, contenu.toArray(Node[]::new));
    }

    /**
     * Une version validée, telle qu'elle a été figée : chaque phrase, et le contenu des faits qui la
     * justifient tel qu'il était ce jour-là (CA-6.10, RG-16).
     */
    static Node version(Fenetre f, Session s, long numero, int version) {
        Session.Fiche fiche = s.fiche(numero);
        Numerotee n = fiche.numerotee();
        ZoneId zone = s.horloge().getZone();
        VersionValidee v = fiche.versions().stream().filter(x -> x.version() == version).findFirst()
                .orElseThrow(() -> new Refus("La candidature n° " + numero + " n'a pas de version " + version + "."));
        boolean envoyee = n.candidature().versionEnvoyee().equals(Optional.of(version));
        DossierGenere g = v.dossier();
        Path repertoire = Path.of(v.repertoire());
        List<Node> contenu = new ArrayList<>();
        FlowPane barre = new FlowPane(8, 8, retour("← Dossier", () -> f.ouvrirDossier(numero)));
        contenu.add(barre);
        if (Files.isDirectory(repertoire)) {
            barre.getChildren().add(Ui.bouton("Ouvrir le répertoire", () -> Ui.ouvrir(repertoire)));
            contenu.add(Ui.discret("Répertoire : " + repertoire));
        } else {
            contenu.add(Ui.encadre("Répertoire introuvable : " + repertoire + ". Il a été déplacé ou supprimé hors de l'application ; "
                    + "le contenu ci-dessous reste complet (UC-12 E1).", Ton.ATTENTION));
        }
        contenu.add(Ui.rubrique("Évaluation", evaluation(g.dossier(), g.avisPerime())));
        contenu.addAll(phrases(f, s, numero, g.dossier(), false));
        VBox faits = new VBox(8);
        v.faits().forEach(x -> {
            Label texte = Ui.texte(Arrays.stream(x.contenu().split("\n")).filter(l -> !l.isBlank()).collect(Collectors.joining(" · ")));
            HBox.setHgrow(texte, Priority.ALWAYS);
            HBox ligne = new HBox(10, Ui.pastille(x.fait(), Ton.ACCENT), texte);
            ligne.setAlignment(Pos.BASELINE_LEFT);
            faits.getChildren().add(ligne);
        });
        contenu.add(Ui.rubrique("Justification : les faits cités, tels qu'ils étaient à la validation (RG-16)", faits));
        String empreinte = g.empreinteProfil().substring(0, Math.min(12, g.empreinteProfil().length()));
        return Ui.page("Version " + version + " — " + Libelles.titre(numero, n.candidature()),
                "Validée le " + date(v.valideLe(), zone) + (envoyee ? ", c'est celle qui a été envoyée" : "") + " · produite le "
                        + date(g.genereLe(), zone) + (g.modele().isEmpty() ? "" : " par " + g.modele()) + ", profil " + empreinte + "…",
                contenu.toArray(Node[]::new));
    }

    // --- les appels au modèle, hors du fil de l'interface ---

    /** UC-07 : des appels payants au modèle capable, annoncés avant d'être faits. */
    static void generer(Fenetre f, Session s, long numero, boolean regenerer) {
        Reglages r = s.donnees().reglages();
        int passes = 1 + r.generation().revisionsMax();
        if (!Ui.confirmer(regenerer ? "Régénérer le dossier" : "Générer le dossier", "Jusqu'à " + passes + " passe(s) de trois "
                + "appels payants au modèle " + r.modeleCapable() + ". " + (regenerer ? "Le dossier actuel est remplacé ; les "
                        + "phrases de votre main y sont reportées telles quelles, le modèle n'en choisit que la place (RG-27)."
                        : "Chaque phrase est vérifiée contre le profil et l'offre ; rien n'est enregistré si la génération échoue."),
                "Générer")) {
            return;
        }
        Travail t = new Travail("Génération du dossier", "Le CV ciblé, la lettre, puis l'avis du recruteur ; sous le seuil, une "
                + "révision. Cela prend quelques minutes.");
        t.lancer(info -> s.dossiers().generer(numero, p -> {
            info.accept("Jusqu'à " + p.passesMax() + " passe(s) de trois appels à " + p.modele() + ".");
            if (p.reprises() > 0) {
                info.accept(p.reprises() + " phrase(s) de votre main reportée(s) telles quelles (RG-27).");
            }
        }, info), g -> {
            f.rafraichir();
            Evaluation e = g.dossier().evaluation();
            return "Dossier généré : note globale " + e.noteGlobale() + "/100, " + (e.seuilAtteint() ? "seuil atteint."
                    : "sous le seuil de " + e.regles().seuil() + ".") + " Coût : " + cout(g.cout()) + "."
                    + g.apercus().echec().map(x -> " Aperçus non écrits : " + x).orElse("");
        });
        t.afficher();
    }

    /** UC-08, étape 7 : un nouvel avis du recruteur sur le dossier tel qu'il est. */
    static void evaluer(Fenetre f, Session s, long numero) {
        Travail t = new Travail("Nouvel avis du recruteur", "Un appel payant au modèle capable, sur le dossier tel que vous l'avez "
                + "corrigé.");
        t.lancer(info -> {
            info.accept("Avis du recruteur…");
            return s.dossiers().evaluer(numero);
        }, r -> {
            f.rafraichir();
            return "Note globale : " + r.avant().evaluation().noteGlobale() + " → " + r.apres().evaluation().noteGlobale() + "/100, seuil "
                    + r.apres().evaluation().regles().seuil() + ". Coût : " + cout(r.cout()) + ".";
        });
        t.afficher();
    }

    /** UC-09 : aucun appel au modèle ; le dossier se fige, ses fichiers s'écrivent. */
    static void valider(Fenetre f, Session s, Numerotee n) {
        long numero = n.numero();
        if (!Ui.confirmer("Valider le dossier", "Le dossier sera figé, avec une copie des faits qu'il cite ; le CV et la lettre "
                + "seront écrits en PDF et en DOCX dans le répertoire de la candidature"
                + (n.candidature().etape() == Etape.A_PREPARER ? ", qui passera à l'étape « prête »." : "."), "Valider")) {
            return;
        }
        CasDossier.Validation v = s.dossiers().valider(numero);
        List<String> l = new ArrayList<>();
        l.add("Version " + v.version() + " validée, figée (RG-15)." + (v.version() > 1 ? " La version " + (v.version() - 1)
                + " reste conservée." : ""));
        if (v.passeeAPrete()) {
            l.add("Candidature passée à l'étape « prête ». Après l'envoi, fait par vous : « Avancer à… envoyée », sur la fiche.");
        }
        l.add("Répertoire : " + v.repertoire() + " — " + String.join(" · ", v.fichiers()));
        l.add(v.faits() + " fait(s) cité(s), copiés tels qu'ils sont dans le profil : le modifier ne changera plus ce dossier (RG-16).");
        if (v.profilChange()) {
            l.add("Attention : le profil a changé depuis la génération. Les phrases restent vérifiées contre sa version actuelle, "
                    + "dont les faits cités viennent d'être copiés.");
        }
        f.annoncer(String.join(System.lineSeparator(), l), v.profilChange() ? Ton.ATTENTION : Ton.SUCCES);
        f.rafraichir();
    }

    /** L'aperçu PDF, réécrit s'il manque. */
    private static Path apercu(Session s, long numero, Dossier d, String document) {
        return s.apercu(numero, document).orElseGet(() -> {
            s.dossiers().ecrireApercus(numero, d);
            return s.apercu(numero, document).orElseThrow();
        });
    }

    // --- les corrections à la main (UC-08), sans appel ---

    static void reecrire(Fenetre f, Session s, long numero, int phrase, Assertion a) {
        Formulaire fo = new Formulaire("Réécrire la phrase n° " + phrase, "Elle devient de votre main : la vérification ne la rejette "
                + "pas, et aucune génération ne la réécrira (RG-27). Si elle dit un fait nouveau, versez-la ensuite au profil.");
        var texte = fo.paragraphe("Texte", a.texte(), 3);
        texte.setPrefColumnCount(48);
        CasDossier.Correction[] c = {null};
        if (fo.afficher(() -> c[0] = s.dossiers().corriger(numero, d -> d.corriger(phrase, texte.getText())))) {
            annoncer(f, "Phrase n° " + phrase + " réécrite, désormais de votre main.", c[0]);
            f.rafraichir();
        }
    }

    static void supprimer(Fenetre f, Session s, long numero, int phrase, Assertion a, Place place) {
        if (!Ui.confirmer("Retirer la phrase n° " + phrase, "« " + a.texte() + " »" + (place == Place.INTITULE
                ? System.lineSeparator() + System.lineSeparator() + "C'est l'intitulé d'un bloc : le bloc entier est retiré." : ""),
                "Retirer")) {
            return;
        }
        CasDossier.Correction c = s.dossiers().corriger(numero, d -> d.supprimer(phrase));
        int parties = c.avant().assertions().size() - c.apres().assertions().size();
        annoncer(f, "Retirée : « " + a.texte() + " »" + (parties > 1 ? ", avec le reste de son bloc (" + (parties - 1)
                + " phrase(s))." : "."), c);
        f.rafraichir();
    }

    static void reintroduire(Fenetre f, Session s, long numero, int retiree, Assertion a) {
        CasDossier.Correction c = s.dossiers().corriger(numero, d -> d.reintroduire(retiree));
        annoncer(f, "Remise dans son bloc : « " + a.texte() + " »", c);
        f.rafraichir();
    }

    /** Ce qu'une correction a donné, en haut de la page : la page, le filtre ATS, ce qui mérite attention. */
    private static void annoncer(Fenetre f, String quoi, CasDossier.Correction c) {
        List<String> l = new ArrayList<>();
        l.add(quoi);
        l.add("CV : une page, mise en page « " + c.cv().gabarit() + " »." + (c.ats().isEmpty() ? "" : " Filtre ATS : "
                + c.atsAvant().map(a -> a.note() + " → ").orElse("") + c.ats().get().note() + "/100."));
        c.cv().avertissements().forEach(a -> l.add("Attention : " + a));
        c.longueur().ifPresent(x -> l.add("Attention : " + x));
        l.add("L'avis du recruteur date d'avant cette correction : « Nouvel avis du recruteur » pour en avoir un.");
        c.apercus().echec().ifPresent(x -> l.add("Aperçus non écrits, le dossier est enregistré. " + x));
        boolean attention = !c.cv().avertissements().isEmpty() || c.longueur().isPresent() || c.apercus().echec().isPresent();
        f.annoncer(String.join(System.lineSeparator(), l), attention ? Ton.ATTENTION : Ton.SUCCES);
    }

    // --- ce qu'on lit ---

    /** Le CV puis la lettre, chaque phrase numérotée — c'est par ce numéro qu'on la désigne — et suivie de son origine. */
    private static List<Node> phrases(Fenetre f, Session s, long numero, Dossier d, boolean modifiable) {
        int[] compteur = {1};
        VBox cv = new VBox(6);
        for (Cv.Section section : d.cv().sections()) {
            if (section.contenus().isEmpty()) {
                continue;
            }
            Label t = new Label(section.titre().toUpperCase(Locale.FRENCH));
            t.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED, Styles.TEXT_BOLD);
            VBox.setMargin(t, new Insets(8, 0, 0, 0));
            cv.getChildren().add(t);
            for (Cv.Contenu contenu : section.contenus()) {
                switch (contenu) {
                    case Cv.Bloc b -> {
                        cv.getChildren().add(ligne(f, s, numero, d, compteur[0]++, b.intitule(), Place.INTITULE, 0, modifiable));
                        b.precision().ifPresent(p -> cv.getChildren().add(ligne(f, s, numero, d, compteur[0]++, p, Place.PRECISION, 0,
                                modifiable)));
                        b.puces().forEach(p -> cv.getChildren().add(ligne(f, s, numero, d, compteur[0]++, p.assertion(), Place.PUCE,
                                p.rang(), modifiable)));
                    }
                    case Cv.Ligne l -> cv.getChildren().add(ligne(f, s, numero, d, compteur[0]++, l.assertion(), Place.LIGNE, 0,
                            modifiable));
                }
            }
        }
        Lettre l = d.lettre();
        ReglesMots mots = new ReglesMots(l.mots(), d.evaluation().regles().motsMin(), d.evaluation().regles().motsMax());
        VBox lettre = new VBox(6, Ui.discret(l.destinataire() + " · " + l.lieuEtDate() + " · " + mots), Ui.texte("Objet : " + l.objet()));
        String[] parties = {"Vous", "Moi", "Nous"};
        for (int i = 0; i < l.paragraphes().size(); i++) {
            Label t = new Label(i < parties.length ? parties[i] : "Paragraphe " + (i + 1));
            t.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED, Styles.TEXT_BOLD);
            VBox.setMargin(t, new Insets(8, 0, 0, 0));
            lettre.getChildren().add(t);
            l.paragraphes().get(i).phrases().forEach(p -> lettre.getChildren().add(ligne(f, s, numero, d, compteur[0]++, p, Place.LETTRE, 0,
                    modifiable)));
        }
        return List.of(Ui.rubrique("CV", cv), Ui.rubrique("Lettre", lettre));
    }

    /** « 182 mots », et ce qu'il faut en penser si c'est hors de la fourchette (CA-4.4). */
    private record ReglesMots(int mots, int min, int max) {

        @Override
        public String toString() {
            return mots + " mots" + (mots < min || mots > max ? ", hors de la fourchette de " + min + " à " + max : "");
        }
    }

    /** Une phrase : son numéro, son texte, son origine, et — si l'on peut encore corriger — ce qu'on peut en faire. */
    private static Node ligne(Fenetre f, Session s, long numero, Dossier d, int phrase, Assertion a, Place place, int rang,
            boolean modifiable) {
        Label n = Ui.discret(String.valueOf(phrase));
        n.setMinWidth(28);
        n.setAlignment(Pos.TOP_RIGHT);
        Label texte = Ui.texte((place == Place.PUCE ? "•  " : "") + a.texte());
        texte.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(texte, Priority.ALWAYS);
        if (place == Place.INTITULE) {
            texte.getStyleClass().add(Styles.TEXT_BOLD);
        } else if (place == Place.PRECISION) {
            texte.getStyleClass().add(Styles.TEXT_MUTED);
        }
        HBox marques = new HBox(6, origine(a.origine()));
        if (place == Place.PUCE) {
            Label pertinence = Ui.pastille("rang " + rang, Ton.NEUTRE);
            pertinence.setTooltip(new Tooltip("Rang de pertinence : faute de place, les puces de rang le plus élevé sont retirées "
                    + "d'abord (RG-26)."));
            marques.getChildren().add(pertinence);
        }
        marques.setAlignment(Pos.TOP_RIGHT);
        HBox ligne = new HBox(10, n, texte, marques);
        ligne.setAlignment(Pos.TOP_LEFT);
        if (modifiable) {
            ligne.getChildren().add(menu(f, s, numero, d, phrase, a, place));
        }
        return ligne;
    }

    private static MenuButton menu(Fenetre f, Session s, long numero, Dossier d, int phrase, Assertion a, Place place) {
        MenuButton m = new MenuButton("Corriger");
        m.getStyleClass().addAll(Styles.FLAT, Styles.SMALL);
        m.getItems().addAll(item("Réécrire…", () -> reecrire(f, s, numero, phrase, a)),
                item("Retirer…", () -> supprimer(f, s, numero, phrase, a, place)));
        if (a.origine() instanceof Origine.Auteur auteur && auteur.verseeDans().isEmpty()) {
            m.getItems().addAll(new SeparatorMenuItem(),
                    item("Verser au profil comme réalisation…", () -> Verser.realisation(f, s, numero, d, phrase)),
                    item("Verser au profil comme compétence…", () -> Verser.competence(f, s, numero, d, phrase)),
                    item("Verser au profil pour enrichir un fait…", () -> Verser.enrichir(f, s, numero, d, phrase)));
        }
        return m;
    }

    private static MenuItem item(String texte, Runnable action) {
        MenuItem i = new MenuItem(texte);
        i.setOnAction(e -> {
            try {
                action.run();
            } catch (RuntimeException ex) {
                Ui.alerte(ex);
            }
        });
        return i;
    }

    /** L'origine d'une phrase (CA-3.7) : les faits du profil qu'elle cite, l'offre, ou vous. */
    private static Label origine(Origine o) {
        return switch (o) {
            case Origine.Faits x -> {
                Label p = Ui.pastille(String.join(", ", x.ids()), Ton.ACCENT);
                p.setTooltip(new Tooltip("Tirée des faits du profil : " + String.join(", ", x.ids())));
                yield p;
            }
            case Origine.Offre x -> {
                Label p = Ui.pastille("offre", Ton.NEUTRE);
                p.setTooltip(new Tooltip("Tirée du texte de l'offre : elle ne dit rien de vous."));
                yield p;
            }
            case Origine.Auteur x -> Ui.pastille(x.verseeDans().map(id -> "de votre main · versée : " + id).orElse("de votre main"),
                    Ton.SUCCES);
        };
    }

    /** La note globale, le filtre ATS, le recruteur critère par critère, et ce qu'il propose. */
    private static Node evaluation(Dossier d, boolean avisPerime) {
        Evaluation e = d.evaluation();
        Label note = new Label(e.noteGlobale() + "/100");
        note.getStyleClass().add(Styles.TITLE_1);
        Label seuil = e.seuilAtteint() ? Ui.pastille("seuil " + e.regles().seuil() + " atteint", Ton.SUCCES)
                : Ui.pastille("sous le seuil de " + e.regles().seuil(), Ton.ATTENTION);
        Label detail = Ui.discret("filtre ATS " + e.ats().map(a -> a.note() + "/100").orElse("sans objet") + " · recruteur "
                + e.avis().note() + "/100" + (avisPerime ? ", avis antérieur à vos corrections" : ""));
        HBox haut = new HBox(20, note, new VBox(6, seuil, detail));
        haut.setAlignment(Pos.CENTER_LEFT);
        VBox v = new VBox(12, haut);
        e.ats().ifPresent(a -> {
            if (!a.lisible()) {
                v.getChildren().add(Ui.encadre("Le texte du CV est illisible pour un filtre ATS.", Ton.DANGER));
            }
            v.getChildren().add(exigences("Retrouvées par le filtre ATS", a.presentes(), Ton.SUCCES));
            v.getChildren().add(exigences("Absentes", a.absentes(), Ton.DANGER));
        });
        AvisRecruteur avis = e.avis();
        GridPane criteres = new GridPane();
        criteres.setHgap(12);
        criteres.setVgap(6);
        int ligne = 0;
        for (AvisRecruteur.NoteCritere c : avis.criteres()) {
            criteres.add(Ui.texte(c.critere().libelle()), 0, ligne);
            criteres.add(Ui.pastille(c.note() + "/5", c.note() >= 4 ? Ton.SUCCES : c.note() == 3 ? Ton.NEUTRE : Ton.ATTENTION), 1, ligne);
            criteres.add(Ui.discret(c.justification()), 2, ligne++);
        }
        v.getChildren().add(Ui.rubrique("Le recruteur" + (avisPerime ? " — avis antérieur à vos corrections" : ""), criteres));
        liste(v, "Forces", avis.forces());
        liste(v, "Faiblesses", avis.faiblesses());
        liste(v, "Corrections proposées", avis.corrections());
        liste(v, "Corrections écartées : elles demandaient un fait absent du profil (RG-07)", d.rapport().correctionsEcartees());
        return v;
    }

    private static Node exigences(String titre, List<Exigence> exigences, Ton ton) {
        FlowPane p = new FlowPane(6, 4, Ui.discret(titre + " :"));
        if (exigences.isEmpty()) {
            p.getChildren().add(Ui.discret("aucune"));
        }
        exigences.forEach(x -> p.getChildren().add(Ui.pastille(x.libelle() + (x.obligatoire() ? " · obligatoire" : ""), ton)));
        return p;
    }

    private static void liste(VBox v, String titre, List<String> elements) {
        if (elements.isEmpty()) {
            return;
        }
        VBox l = new VBox(4);
        elements.forEach(x -> l.getChildren().add(Ui.texte("•  " + x)));
        Label t = new Label(titre);
        t.getStyleClass().add(Styles.TEXT_BOLD);
        v.getChildren().add(new VBox(4, t, l));
    }

    private static Node retirees(Fenetre f, Session s, long numero, Dossier d) {
        VBox v = new VBox(6, Ui.discret("Les moins pertinentes d'abord (RG-26). Une puce se remet dans son bloc si la page le permet."));
        List<Dossier.Retiree> retirees = d.rapport().retirees();
        for (int i = 0; i < retirees.size(); i++) {
            int r = i + 1;
            Assertion a = retirees.get(i).puce().assertion();
            Label texte = Ui.texte(a.texte());
            texte.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(texte, Priority.ALWAYS);
            Label n = Ui.discret("R" + r);
            n.setMinWidth(28);
            HBox ligne = new HBox(10, n, texte, origine(a.origine()), Ui.bouton("Remettre", () -> reintroduire(f, s, numero, r, a)));
            ligne.setAlignment(Pos.CENTER_LEFT);
            v.getChildren().add(ligne);
        }
        return v;
    }

    private static Node rapport(Dossier d) {
        Dossier.Rapport r = d.rapport();
        VBox v = new VBox(6, Ui.texte((r.passes() == 1 ? "Une passe" : r.passes() + " passes") + " de génération ; "
                + d.assertions().size() + " phrases, dont " + d.assertionsDeLAuteur() + " de votre main."));
        r.avertissements().forEach(a -> v.getChildren().add(Ui.encadre(a, Ton.ATTENTION)));
        return v;
    }

    private static Node versions(Fenetre f, Numerotee n, List<VersionValidee> versions, ZoneId zone) {
        VBox v = new VBox(8);
        for (VersionValidee x : versions.reversed()) {
            Label nom = new Label("Version " + x.version());
            nom.getStyleClass().add(Styles.TEXT_BOLD);
            HBox ligne = new HBox(10, nom, Ui.discret("validée le " + date(x.valideLe(), zone)));
            if (n.candidature().versionEnvoyee().equals(Optional.of(x.version()))) {
                ligne.getChildren().add(Ui.pastille("envoyée", Ton.SUCCES));
            }
            ligne.getChildren().addAll(Ui.ressort(), Ui.bouton("Relire", () -> f.ouvrirVersion(n.numero(), x.version())),
                    Ui.bouton("Ouvrir le répertoire", () -> Ui.ouvrir(Path.of(x.repertoire()))));
            ligne.setAlignment(Pos.CENTER_LEFT);
            v.getChildren().add(ligne);
        }
        return v;
    }

    private static Button retour(String texte, Runnable action) {
        Button b = Ui.bouton(texte, action);
        b.getStyleClass().add(Styles.FLAT);
        return b;
    }

    private static String date(Instant instant, ZoneId zone) {
        return Libelles.date(LocalDate.ofInstant(instant, zone));
    }

    private static String cout(CoutModele c) {
        return Libelles.appels(c.appels()) + ", " + Libelles.dollars(c.coutMicroDollars());
    }
}
