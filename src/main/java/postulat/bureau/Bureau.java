package postulat.bureau;

import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.scene.Scene;
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
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
        fenetre.setTitle("postulat");
        fenetre.setScene(new Scene(Fenetre.ouvrir(session).racine(), 1200, 800));
        fenetre.setMinWidth(900);
        fenetre.setMinHeight(600);
        fenetre.show();
    }
}
