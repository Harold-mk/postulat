package postulat.bureau;

import atlantafx.base.theme.PrimerLight;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javafx.application.Application;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

/**
 * L'interface de bureau (itération 9), en JavaFX, avec le thème Primer d'AtlantaFX. Elle lit et écrit les
 * mêmes données que la ligne de commande, par les mêmes règles : elle n'en est qu'une autre façon de
 * s'en servir.
 */
public final class Bureau extends Application {

    /** JavaFX instancie l'application lui-même : la session lui est donc confiée avant le lancement. */
    private static Session session;

    /** Ouvre la fenêtre et rend la main quand elle se ferme. */
    public static void lancer(Session s) {
        session = s;
        Application.launch(Bureau.class);
    }

    @Override
    public void start(Stage fenetre) {
        // Lancée sans console, la fenêtre est le seul endroit où une erreur imprévue peut se voir.
        Thread.currentThread().setUncaughtExceptionHandler((fil, e) -> Ui.alerte(e));
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
        fenetre.setTitle("postulat");
        fenetre.getIcons().setAll(icones());
        fenetre.setScene(new Scene(Fenetre.ouvrir(session).racine(), 1200, 800));
        fenetre.setMinWidth(900);
        fenetre.setMinHeight(600);
        fenetre.show();
    }

    /** L'icône de la fenêtre, en plusieurs tailles : Windows prend celle qui convient à la barre des tâches. */
    static List<Image> icones() {
        return List.of(16, 32, 48, 256).stream().map(t -> {
            InputStream png = Bureau.class.getResourceAsStream("icone-" + t + ".png");
            if (png == null) {
                throw new IllegalStateException("Icône introuvable : icone-" + t + ".png");
            }
            return new Image(png);
        }).toList();
    }

    /**
     * Vérifie que l'interface peut s'ouvrir ici, sans rien afficher : JavaFX et ses bibliothèques natives,
     * le thème, l'icône, et chaque page de la navigation, construite et rendue hors écran. Pour contrôler
     * une installation. Les données ne sont que lues.
     *
     * @return le compte rendu, en une ligne
     * @throws IllegalStateException si quelque chose manque : la cause dit quoi
     */
    public static String verifier(Session s) {
        boolean demarreIci = demarrer();
        CompletableFuture<String> resultat = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
                int icones = icones().size();
                Fenetre f = Fenetre.ouvrir(s);
                Scene scene = new Scene(f.racine(), 1200, 800);
                Fenetre.Onglet[] onglets = Fenetre.Onglet.values();
                for (Fenetre.Onglet o : onglets) {
                    f.aller(o);
                    scene.snapshot(null);
                }
                resultat.complete("Interface de bureau prête : JavaFX " + System.getProperty("javafx.runtime.version") + ", rendu "
                        + (Platform.isSupported(ConditionalFeature.SCENE3D) ? "accéléré" : "logiciel") + ", " + onglets.length
                        + " pages construites, icône en " + icones + " tailles ; nous sommes le " + Ui.jour(s.aujourdhui()) + ".");
            } catch (RuntimeException | Error e) {
                resultat.completeExceptionally(e);
            }
        });
        try {
            return resultat.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("L'interface de bureau ne peut pas s'ouvrir ici : " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("L'interface de bureau ne s'est pas construite en une minute.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Vérification interrompue.", e);
        } finally {
            if (demarreIci) {
                Platform.exit();
            }
        }
    }

    /** Démarre JavaFX s'il ne l'est pas déjà ; vrai si c'est ici qu'il l'a été. */
    private static boolean demarrer() {
        CompletableFuture<Void> pret = new CompletableFuture<>();
        try {
            Platform.startup(() -> pret.complete(null));
        } catch (IllegalStateException dejaDemarre) {
            return false;
        }
        try {
            pret.get(60, TimeUnit.SECONDS);
            return true;
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("JavaFX n'a pas pu démarrer : " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Démarrage de JavaFX interrompu.", e);
        }
    }
}
