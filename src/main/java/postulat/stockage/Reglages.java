package postulat.stockage;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import postulat.suivi.ReglesRelance;
import postulat.suivi.TableauDeBord;

/**
 * Réglages de la campagne, lus dans {@code postulat.properties} au répertoire des données s'il
 * existe : délais de relance (CA-7.10) et objectif hebdomadaire (CA-9.2). Tout réglage absent prend
 * sa valeur du cadrage.
 *
 * <pre>
 * relance.delai.jours = 10
 * relance.nombre = 2
 * objectif.hebdomadaire = 30
 * </pre>
 */
public record Reglages(ReglesRelance relance, int objectifHebdomadaire) {

    public static final Reglages PAR_DEFAUT =
            new Reglages(ReglesRelance.PAR_DEFAUT, TableauDeBord.OBJECTIF_HEBDOMADAIRE);

    public Reglages {
        if (objectifHebdomadaire < 1) {
            throw new IllegalArgumentException("objectif.hebdomadaire doit être d'au moins 1.");
        }
    }

    static Reglages lire(Path fichier) {
        if (!Files.exists(fichier)) {
            return PAR_DEFAUT;
        }
        Properties p = new Properties();
        try (Reader lecteur = Files.newBufferedReader(fichier, StandardCharsets.UTF_8)) {
            p.load(lecteur);
            return new Reglages(
                    new ReglesRelance(
                            entier(p, "relance.delai.jours", ReglesRelance.PAR_DEFAUT.delaiJours()),
                            entier(p, "relance.nombre", ReglesRelance.PAR_DEFAUT.relancesMax())),
                    entier(p, "objectif.hebdomadaire", TableauDeBord.OBJECTIF_HEBDOMADAIRE));
        } catch (IOException | IllegalArgumentException e) {
            throw new ErreurStockage("Réglages illisibles dans " + fichier + " : " + e.getMessage(), e);
        }
    }

    private static int entier(Properties p, String cle, int parDefaut) {
        String valeur = p.getProperty(cle);
        if (valeur == null) {
            return parDefaut;
        }
        try {
            return Integer.parseInt(valeur.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(cle + " doit être un nombre entier, pas « " + valeur + " ».", e);
        }
    }
}
