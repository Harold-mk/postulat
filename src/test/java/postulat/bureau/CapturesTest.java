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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import postulat.JeuDEssai;
import postulat.cas.CasOffre;
import postulat.cas.Refus;
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

    static final FauxModele FAUX = new FauxModele();

    /** Démarre JavaFX une fois pour toute la suite ; faux s'il n'y a pas d'affichage. */
    static synchronized boolean javafx() throws Exception {
        if (demarre != null) {
            return demarre;
        }
        boolean ok;
        try {
            CompletableFuture<Void> pret = new CompletableFuture<>();
            Platform.startup(() -> pret.complete(null));
            pret.get(20, TimeUnit.SECONDS);
            ok = true;
        } catch (IllegalStateException dejaDemarre) {
            ok = true;
        } catch (UnsupportedOperationException | java.util.concurrent.TimeoutException sansAffichage) {
            ok = false;
        }
        if (ok) {
            Platform.setImplicitExit(false);
            sur(() -> {
                Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
                return null;
            });
        }
        demarre = ok;
        return ok;
    }

    private static Boolean demarre;

    @BeforeAll
    static void demarrer() throws Exception {
        affichage = javafx();
        Files.writeString(dossier.resolve("profil.yaml"), JeuDEssai.PROFIL);
        Files.writeString(dossier.resolve("postulat.properties"), "lettre.mots.min = 50\nlettre.mots.max = 150\n");
        session = new Session(new Donnees(dossier, HORLOGE), HORLOGE, r -> FAUX);
        remplir(session);
        offresEtDossier(session);
        Files.createDirectories(CAPTURES);
    }

    /** Une campagne fictive : de quoi peupler chaque écran. */
    static void remplir(Session s) {
        LocalDate j = LocalDate.of(2026, 10, 11);
        s.ajouter("Wonka", "Stagiaire data analyst", Etape.REPEREE, j.minusDays(12), false);
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

    /**
     * L'offre de démonstration, devenue la candidature n° 7 : analysée, poursuivie, son dossier généré,
     * validé en version 1, puis corrigé à la main. Et la même offre chez Globex, n° 8, seulement repérée.
     */
    static void offresEtDossier(Session s) {
        LocalDate j = LocalDate.of(2026, 10, 11);
        FAUX.repondre(JeuDEssai.FICHE).repondre(JeuDEssai.RAPPROCHEMENT);
        long acme = ((CasOffre.Ajoutee) s.offres().soumettre(JeuDEssai.OFFRE, "saisi dans la fenêtre", Optional.empty(),
                Optional.empty(), j.minusDays(4), x -> true, (nom, feminin) -> Optional.empty(), x -> { })).numerotee().numero();
        s.offres().rapprocher(acme, x -> { });
        s.avancer(acme, Etape.A_PREPARER, j.minusDays(3));
        FAUX.repondre(JeuDEssai.CV).repondre(JeuDEssai.LETTRE).repondre(JeuDEssai.avis(4));
        s.dossiers().generer(acme, p -> { }, x -> { });
        s.dossiers().corriger(acme, d -> d.corriger(4, "Automatisé des contrôles SQL quotidiens sur les ventes"));
        s.dossiers().valider(acme);
        s.dossiers().corriger(acme, d -> d.corriger(3, "Suivi hebdomadaire des ventes sous Power BI"));

        FAUX.repondre(JeuDEssai.FICHE).repondre(JeuDEssai.RAPPROCHEMENT);
        long globex = ((CasOffre.Ajoutee) s.offres().soumettre(JeuDEssai.OFFRE.replace("Acme", "Globex"), "saisi dans la fenêtre",
                Optional.of("Globex"), Optional.empty(), j.minusDays(1), x -> true, (nom, feminin) -> Optional.empty(), x -> { }))
                .numerotee().numero();
        s.offres().rapprocher(globex, x -> { });
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

    @Test
    void la_page_d_une_offre() throws Exception {
        assumeTrue(affichage, "aucun affichage : capture ignorée");
        assertThat(capturer("7-offre-reperee", () -> fenetre(f -> f.ouvrirOffre(8)), 1280, 1100)).exists();
    }

    @Test
    void la_page_d_un_dossier() throws Exception {
        assumeTrue(affichage, "aucun affichage : capture ignorée");
        assertThat(capturer("8-dossier", () -> fenetre(f -> {
            f.annoncer("Phrase n° 3 réécrite, désormais de votre main." + System.lineSeparator() + "CV : une page, mise en page "
                    + "« aérée ». Filtre ATS : 67 → 67/100.", Ui.Ton.SUCCES);
            f.ouvrirDossier(7);
        }), 1280, 2400)).exists();
        assertThat(capturer("9-version-validee", () -> fenetre(f -> f.ouvrirVersion(7, 1)), 1280, 1900)).exists();
        assertThat(capturer("10-fiche-avec-dossier", () -> fenetre(f -> f.ouvrirFiche(7)), 1280, 1000)).exists();
    }

    @Test
    void un_travail_long_montre_son_avancement_puis_son_bilan() throws Exception {
        assumeTrue(affichage, "aucun affichage : capture ignorée");
        Travail[] t = {null, null};
        Task<?>[] taches = {null, null};
        sur(() -> {
            t[0] = new Travail("Génération du dossier", "Le CV ciblé, la lettre, puis l'avis du recruteur ; sous le seuil, une "
                    + "révision.");
            taches[0] = t[0].lancer(info -> {
                info.accept("Jusqu'à 3 passe(s) de trois appels à claude-opus-5-5.");
                info.accept("Première passe : CV ciblé…");
                info.accept("Première passe : lettre…");
                return 87;
            }, note -> "Dossier généré : note globale " + note + "/100, seuil atteint. Coût : 3 appels, 0,0045 $.");
            t[1] = new Travail("Analyse et rapprochement", "");
            taches[1] = t[1].lancer(info -> {
                throw new Refus("Le profil a 2 problème(s) : rien n'est produit tant qu'ils demeurent.");
            }, x -> "jamais");
            return null;
        });
        for (Task<?> tache : taches) {
            try {
                tache.get(20, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException attendue) {
                // le second échoue, comme prévu
            }
        }
        sur(() -> null); // la fin du travail passe par le fil de l'interface : elle y est maintenant traitée

        assertThat(sur(t[0]::journal)).isEqualTo(String.join("\n", "Jusqu'à 3 passe(s) de trois appels à claude-opus-5-5.",
                "Première passe : CV ciblé…", "Première passe : lettre…"));
        assertThat(sur(t[0]::fini)).isTrue();
        assertThat(sur(t[0]::etat)).isEqualTo("Dossier généré : note globale 87/100, seuil atteint. Coût : 3 appels, 0,0045 $.");
        assertThat(sur(t[1]::etat)).isEqualTo("Le profil a 2 problème(s) : rien n'est produit tant qu'ils demeurent.");
        assertThat(capturer("11-travail-fini", () -> t[0].panneau(), 620, 420)).exists();
    }
}
