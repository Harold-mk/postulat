package postulat.bureau;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import atlantafx.base.theme.PrimerLight;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import postulat.ia.FauxModele;
import postulat.stockage.Donnees;
import postulat.suivi.Canal;
import postulat.suivi.Contact;
import postulat.suivi.Etape;
import postulat.suivi.TypeEntretien;

/**
 * Chaque écran se construit sur des données fictives, et sa capture est écrite dans
 * {@code target/captures} pour être regardée. Un écran qui ne se construit pas fait échouer le test.
 * Sans affichage disponible, ces tests sont ignorés.
 */
class CapturesTest {

    static final Clock HORLOGE = Clock.fixed(Instant.parse("2026-10-11T09:00:00Z"), ZoneOffset.UTC);
    static final Path CAPTURES = Path.of("target", "captures");

    static boolean affichage;

    @TempDir
    static Path dossier;

    static Session session;

    @BeforeAll
    static void demarrer() throws Exception {
        try {
            CompletableFuture<Void> pret = new CompletableFuture<>();
            Platform.startup(() -> pret.complete(null));
            pret.get(20, TimeUnit.SECONDS);
            affichage = true;
        } catch (IllegalStateException dejaDemarre) {
            affichage = true;
        } catch (UnsupportedOperationException | java.util.concurrent.TimeoutException sansAffichage) {
            affichage = false;
        }
        if (affichage) {
            Platform.setImplicitExit(false);
            sur(() -> {
                Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
                return null;
            });
        }
        session = new Session(new Donnees(dossier, HORLOGE), HORLOGE, r -> new FauxModele());
        remplir(session);
        Files.createDirectories(CAPTURES);
    }

    /** Une campagne fictive : de quoi peupler chaque écran. */
    static void remplir(Session s) {
        LocalDate j = LocalDate.of(2026, 10, 11);
        s.ajouter("Acme", "Stagiaire data analyst", Etape.REPEREE, j.minusDays(12), false);
        long globex = s.ajouter("Globex", "Stage business intelligence", Etape.A_PREPARER, j.minusDays(30), false).numero();
        s.avancer(globex, Etape.PRETE, j.minusDays(25));
        s.avancer(globex, Etape.ENVOYEE, j.minusDays(21));
        s.ajouterContact(globex, new Contact("Alex Morel", "chargé de recrutement", "alex.morel@exemple.test"));
        long initech = s.ajouter("Initech", "Data analyst en alternance", Etape.ENVOYEE, j.minusDays(14), false).numero();
        s.avancer(initech, Etape.ENTRETIEN, j.minusDays(3));
        s.planifier(initech, TypeEntretien.TECHNIQUE, j.plusDays(1), j.minusDays(3));
        s.interaction(initech, Canal.APPEL, "Premier échange avec la responsable data, très positif.", j.minusDays(3));
        long umbrella = s.ajouter("Umbrella", "Stage data science", Etape.REPEREE, j.minusDays(9), false).numero();
        s.ecarter(umbrella, "Poste à Lyon, je cherche à Paris.", j.minusDays(8));
        long hooli = s.ajouter("Hooli", "Stage data engineer", Etape.ENVOYEE, j.minusDays(6), false).numero();
        s.retourPromis(hooli, j.plusDays(4), j.minusDays(2));
        s.ajouter("Stark", "Stage analyste", Etape.A_PREPARER, j.minusDays(2), false);
        s.noter(1, "Offre trouvée sur le site de l'école.", j.minusDays(12));
    }

    static <T> T sur(Supplier<T> action) throws Exception {
        CompletableFuture<T> resultat = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                resultat.complete(action.get());
            } catch (RuntimeException e) {
                resultat.completeExceptionally(e);
            }
        });
        return resultat.get(30, TimeUnit.SECONDS);
    }

    /** Construit la page sur le fil de JavaFX, la met en scène hors écran, et l'enregistre en PNG. */
    static Path capturer(String nom, Supplier<Parent> page, double largeur, double hauteur) throws Exception {
        WritableImage image = sur(() -> {
            Parent p = page.get();
            if (p.getScene() != null) { // le panneau d'une boîte de saisie a déjà sa scène
                p.resize(largeur, hauteur);
                return p.snapshot(null, null);
            }
            return new Scene(p, largeur, hauteur).snapshot(null);
        });
        BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        PixelReader pixels = image.getPixelReader();
        for (int y = 0; y < png.getHeight(); y++) {
            for (int x = 0; x < png.getWidth(); x++) {
                png.setRGB(x, y, pixels.getArgb(x, y));
            }
        }
        Path fichier = CAPTURES.resolve(nom + ".png");
        try {
            ImageIO.write(png, "png", fichier.toFile());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return fichier;
    }

    static Parent fenetre(java.util.function.Consumer<Fenetre> aller) {
        Fenetre f = Fenetre.ouvrir(session);
        aller.accept(f);
        return f.racine();
    }

    @Test
    void le_tableau_de_bord() throws Exception {
        assumeTrue(affichage, "aucun affichage : capture ignorée");
        assertThat(capturer("1-tableau-de-bord", () -> fenetre(f -> { }), 1280, 1100)).exists();
    }

    @Test
    void les_actions_du_jour() throws Exception {
        assumeTrue(affichage, "aucun affichage : capture ignorée");
        assertThat(capturer("2-actions", () -> fenetre(f -> f.aller(Fenetre.Onglet.ACTIONS)), 1280, 800)).exists();
    }

    @Test
    void les_candidatures() throws Exception {
        assumeTrue(affichage, "aucun affichage : capture ignorée");
        assertThat(capturer("3-candidatures", () -> fenetre(f -> f.aller(Fenetre.Onglet.CANDIDATURES)), 1280, 800))
                .exists();
    }

    @Test
    void la_fiche_d_une_candidature() throws Exception {
        assumeTrue(affichage, "aucun affichage : capture ignorée");
        assertThat(capturer("4-fiche-entretien", () -> fenetre(f -> f.ouvrirFiche(3)), 1280, 900)).exists();
        assertThat(capturer("5-fiche-ecartee", () -> fenetre(f -> f.ouvrirFiche(4)), 1280, 900)).exists();
    }

    @Test
    void une_boite_de_saisie() throws Exception {
        assumeTrue(affichage, "aucun affichage : capture ignorée");
        assertThat(capturer("6-saisie-ecarter", () -> {
            Formulaire fo = new Formulaire("Écarter cette offre", "Ne pas postuler, en disant pourquoi : le motif sert à comprendre, "
                    + "plus tard, ce qu'on écarte. L'offre et son analyse restent conservées.");
            fo.paragraphe("Motif");
            fo.date("Le", session.aujourdhui());
            return fo.panneau();
        }, 560, 340)).exists();
    }
}
