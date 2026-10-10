package postulat.stockage;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import postulat.ia.Tarif;
import postulat.suivi.ReglesRelance;
import postulat.suivi.TableauDeBord;

/**
 * Réglages de la campagne, lus dans {@code postulat.properties} au répertoire des données s'il
 * existe : délais de relance (CA-7.10), objectif hebdomadaire (CA-9.2), et modèle derrière chaque
 * gamme (cadrage § 11). Tout réglage absent prend sa valeur par défaut.
 *
 * <pre>
 * relance.delai.jours = 10
 * relance.nombre = 2
 * objectif.hebdomadaire = 30
 * modele.economique = claude-haiku-5-5
 * modele.capable = claude-opus-5-5
 * </pre>
 */
public record Reglages(ReglesRelance relance, int objectifHebdomadaire, String modeleEconomique, String modeleCapable) {

    public static final String MODELE_ECONOMIQUE = "claude-haiku-5-5";
    public static final String MODELE_CAPABLE = "claude-opus-5-5";

    public static final Reglages PAR_DEFAUT = new Reglages(
            ReglesRelance.PAR_DEFAUT, TableauDeBord.OBJECTIF_HEBDOMADAIRE, MODELE_ECONOMIQUE, MODELE_CAPABLE);

    public Reglages {
        Objects.requireNonNull(relance);
        if (objectifHebdomadaire < 1) {
            throw new IllegalArgumentException("objectif.hebdomadaire doit être d'au moins 1.");
        }
        exigerTarif("modele.economique", modeleEconomique);
        exigerTarif("modele.capable", modeleCapable);
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
                    entier(p, "objectif.hebdomadaire", TableauDeBord.OBJECTIF_HEBDOMADAIRE),
                    p.getProperty("modele.economique", MODELE_ECONOMIQUE).strip(),
                    p.getProperty("modele.capable", MODELE_CAPABLE).strip());
        } catch (IOException | IllegalArgumentException e) {
            throw new ErreurStockage("Réglages illisibles dans " + fichier + " : " + e.getMessage(), e);
        }
    }

    /** Un modèle sans tarif connu rendrait son coût immesurable (EN-5) : il est refusé d'emblée. */
    private static void exigerTarif(String cle, String modele) {
        if (modele == null || Tarif.de(modele).isEmpty()) {
            throw new IllegalArgumentException(cle + " : modèle « " + modele + " » sans tarif connu, son coût ne "
                    + "serait pas mesurable. Possibles : " + String.join(", ", Tarif.modelesConnus()) + ".");
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
