package postulat.stockage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import postulat.suivi.Canal;
import postulat.suivi.Candidature;
import postulat.suivi.Contact;
import postulat.suivi.Etape;
import postulat.suivi.Evenement;
import postulat.suivi.RefusMetier;
import postulat.suivi.TypeEntretien;

/**
 * La base de suivi, en JDBC nu (pile technique § 4) : la traduction entre les records du domaine et
 * les lignes SQL est écrite à la main, et le domaine n'en sait rien.
 *
 * <p>Deux garanties tiennent ici. Le journal est en ajout seul : aucune méthode ne modifie ni ne
 * supprime un événement, et des triggers le refusent de toute façon (RG-11). L'étape dénormalisée
 * n'est écrite que par {@link #creer} et {@link #enregistrer}, dans la transaction qui ajoute
 * l'événement dont elle découle (ADR-0005, RG-10).
 */
public final class Base implements AutoCloseable {

    /** Étape stockée qui ne correspond pas au rejeu du journal : une violation de RG-10. */
    public record Divergence(long numero, Etape stockee, Etape rejouee) {}

    private final Connection connexion;

    private Base(Connection connexion) {
        this.connexion = connexion;
    }

    /** Ouvre la base, la crée si besoin, et applique les migrations en attente après l'avoir sauvegardée. */
    public static Base ouvrir(Path fichier, Sauvegardes sauvegardes, Clock horloge) {
        return ouvrir(fichier, Migrations.duProjet(), horloge, sauvegardes::archiver);
    }

    /** {@code avantMigration} reçoit de quoi copier la base avant qu'une migration ne la touche. */
    static Base ouvrir(Path fichier, Migrations migrations, Clock horloge, Consumer<Sauvegardes.CopieBase> avantMigration) {
        Path repertoire = fichier.toAbsolutePath().getParent();
        try {
            Files.createDirectories(repertoire);
        } catch (IOException e) {
            throw new ErreurStockage("Impossible de créer le répertoire de données " + repertoire + " : " + e, e);
        }
        Connection c = null;
        try {
            c = DriverManager.getConnection("jdbc:sqlite:" + fichier.toAbsolutePath());
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA foreign_keys = ON");
            }
            Connection ouverte = c;
            migrations.appliquer(c, horloge, () -> avantMigration.accept(cible -> copier(ouverte, cible)));
            return new Base(c);
        } catch (SQLException e) {
            fermerSansEchec(c);
            throw new ErreurStockage("Ouverture de la base " + fichier + " impossible : " + e.getMessage(), e);
        } catch (RuntimeException e) {
            fermerSansEchec(c);
            throw e;
        }
    }

    /** Enregistre une candidature nouvelle et son journal ; lui attribue son numéro. */
    public Numerotee creer(Candidature c) {
        return transaction(() -> {
            long numero;
            try (PreparedStatement s = connexion.prepareStatement(
                    "INSERT INTO candidature (id, entreprise, poste, etape) VALUES (?, ?, ?, ?) RETURNING numero")) {
                s.setString(1, c.id().toString());
                s.setString(2, c.entreprise());
                s.setString(3, c.poste());
                s.setString(4, c.etape().name());
                try (ResultSet r = s.executeQuery()) {
                    r.next();
                    numero = r.getLong(1);
                }
            }
            insererEvenements(c, 0);
            return new Numerotee(numero, c);
        });
    }

    /**
     * Ajoute à la base les événements nouveaux du journal et met l'étape à jour, dans une même
     * transaction. Refuse si le journal enregistré n'est pas un préfixe exact de celui fourni : la
     * candidature a été modifiée entre-temps, ou l'on tente de réécrire l'historique.
     */
    public void enregistrer(Candidature c) {
        transaction(() -> {
            List<Evenement> enregistres = journal(c.id());
            if (enregistres.isEmpty()) {
                throw new ErreurStockage("Candidature inconnue de la base : " + c.id() + ".");
            }
            if (c.journal().size() < enregistres.size()
                    || !c.journal().subList(0, enregistres.size()).equals(enregistres)) {
                throw new ErreurStockage("La candidature a changé dans la base depuis sa lecture, ou son "
                        + "historique a été réécrit : rien n'a été enregistré. Recommencez l'opération.");
            }
            insererEvenements(c, enregistres.size());
            try (PreparedStatement s = connexion.prepareStatement("UPDATE candidature SET etape = ? WHERE id = ?")) {
                s.setString(1, c.etape().name());
                s.setString(2, c.id().toString());
                s.executeUpdate();
            }
            return null;
        });
    }

    public Optional<Numerotee> trouver(long numero) {
        return charger("c.numero = ?", numero).stream().findFirst();
    }

    /** Toutes les candidatures, par numéro croissant. */
    public List<Numerotee> toutes() {
        return charger("1 = 1");
    }

    /** Candidatures à une étape, trouvées par la colonne dénormalisée (ADR-0005). */
    public List<Numerotee> aLEtape(Etape etape) {
        return charger("c.etape = ?", etape.name());
    }

    public void ajouterContact(UUID candidature, Contact contact) {
        transaction(() -> {
            try (PreparedStatement s = connexion.prepareStatement(
                    "INSERT INTO contact (candidature_id, rang, nom, role, coordonnees) "
                            + "VALUES (?, (SELECT count(*) FROM contact WHERE candidature_id = ?), ?, ?, ?)")) {
                s.setString(1, candidature.toString());
                s.setString(2, candidature.toString());
                s.setString(3, contact.nom());
                s.setString(4, contact.role());
                s.setString(5, contact.coordonnees());
                s.executeUpdate();
            }
            return null;
        });
    }

    /** Contacts d'une candidature, dans l'ordre de leur ajout. */
    public List<Contact> contacts(UUID candidature) {
        return lecture(() -> {
            List<Contact> contacts = new ArrayList<>();
            try (PreparedStatement s = connexion.prepareStatement(
                    "SELECT nom, role, coordonnees FROM contact WHERE candidature_id = ? ORDER BY rang")) {
                s.setString(1, candidature.toString());
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        contacts.add(new Contact(r.getString(1), r.getString(2), r.getString(3)));
                    }
                }
            }
            return contacts;
        });
    }

    /** Rejoue chaque journal et le compare à l'étape stockée (RG-10, UC-18 étape 6). Vide si tout concorde. */
    public List<Divergence> verifierCoherence() {
        Map<Long, Etape> stockees = lecture(() -> {
            Map<Long, Etape> etapes = new HashMap<>();
            try (Statement s = connexion.createStatement();
                    ResultSet r = s.executeQuery("SELECT numero, etape FROM candidature")) {
                while (r.next()) {
                    etapes.put(r.getLong(1), Etape.valueOf(r.getString(2)));
                }
            }
            return etapes;
        });
        return toutes().stream()
                .filter(n -> stockees.get(n.numero()) != n.candidature().etape())
                .map(n -> new Divergence(n.numero(), stockees.get(n.numero()), n.candidature().etape()))
                .toList();
    }

    /** Nombre total d'événements enregistrés. */
    public int nombreEvenements() {
        return lecture(() -> {
            try (Statement s = connexion.createStatement();
                    ResultSet r = s.executeQuery("SELECT count(*) FROM evenement")) {
                r.next();
                return r.getInt(1);
            }
        });
    }

    /** Copie cohérente de la base, même ouverte, compactée au passage. */
    void copierVers(Path cible) throws SQLException {
        copier(connexion, cible);
    }

    @Override
    public void close() {
        try {
            connexion.close();
        } catch (SQLException e) {
            throw new ErreurStockage("Fermeture de la base impossible : " + e.getMessage(), e);
        }
    }

    // --- traduction entre événements et lignes ---

    private static final String CREATION = "CREATION";
    private static final String CHANGEMENT_ETAPE = "CHANGEMENT_ETAPE";
    private static final String REOUVERTURE = "REOUVERTURE";
    private static final String RELANCE = "RELANCE";
    private static final String ENTRETIEN_PASSE = "ENTRETIEN_PASSE";
    private static final String ENTRETIEN_PLANIFIE = "ENTRETIEN_PLANIFIE";
    private static final String INTERACTION = "INTERACTION";
    private static final String RETOUR_PROMIS = "RETOUR_PROMIS";
    private static final String NOTE = "NOTE";

    /** Une ligne de la table {@code evenement}, colonnes sans objet à {@code null}. */
    private record Ligne(
            String type, LocalDate date, Etape etape, TypeEntretien entretien, Canal canal,
            LocalDate prevuLe, String texte) {

        static Ligne de(String type, LocalDate date) {
            return new Ligne(type, date, null, null, null, null, null);
        }
    }

    private static Ligne enLigne(Evenement e) {
        return switch (e) {
            case Evenement.Creation x -> new Ligne(CREATION, x.date(), x.etape(), null, null, null, null);
            case Evenement.ChangementEtape x -> new Ligne(CHANGEMENT_ETAPE, x.date(), x.vers(), null, null, null, null);
            case Evenement.Reouverture x -> new Ligne(REOUVERTURE, x.date(), x.vers(), null, null, null, null);
            case Evenement.RelanceEffectuee x -> Ligne.de(RELANCE, x.date());
            case Evenement.EntretienPasse x -> new Ligne(ENTRETIEN_PASSE, x.date(), null, x.type(), null, null, null);
            case Evenement.EntretienPlanifie x ->
                    new Ligne(ENTRETIEN_PLANIFIE, x.date(), null, x.type(), null, x.le(), null);
            case Evenement.Interaction x -> new Ligne(INTERACTION, x.date(), null, null, x.canal(), null, x.notes());
            case Evenement.RetourPromis x -> new Ligne(RETOUR_PROMIS, x.date(), null, null, null, x.pour(), null);
            case Evenement.Note x -> new Ligne(NOTE, x.date(), null, null, null, null, x.texte());
        };
    }

    private static Evenement depuisLigne(ResultSet r) throws SQLException {
        String type = r.getString("type");
        LocalDate date = LocalDate.parse(r.getString("survenu_le"));
        return switch (type) {
            case CREATION -> new Evenement.Creation(date, Etape.valueOf(r.getString("etape")));
            case CHANGEMENT_ETAPE -> new Evenement.ChangementEtape(date, Etape.valueOf(r.getString("etape")));
            case REOUVERTURE -> new Evenement.Reouverture(date, Etape.valueOf(r.getString("etape")));
            case RELANCE -> new Evenement.RelanceEffectuee(date);
            case ENTRETIEN_PASSE -> new Evenement.EntretienPasse(date, TypeEntretien.valueOf(r.getString("entretien")));
            case ENTRETIEN_PLANIFIE -> new Evenement.EntretienPlanifie(
                    date, TypeEntretien.valueOf(r.getString("entretien")), LocalDate.parse(r.getString("prevu_le")));
            case INTERACTION -> new Evenement.Interaction(date, Canal.valueOf(r.getString("canal")), r.getString("texte"));
            case RETOUR_PROMIS -> new Evenement.RetourPromis(date, LocalDate.parse(r.getString("prevu_le")));
            case NOTE -> new Evenement.Note(date, r.getString("texte"));
            default -> throw new ErreurStockage("Type d'événement inconnu dans la base : " + type + ".");
        };
    }

    // --- interne ---

    private void insererEvenements(Candidature c, int depuis) throws SQLException {
        try (PreparedStatement s = connexion.prepareStatement(
                "INSERT INTO evenement (candidature_id, rang, type, survenu_le, etape, entretien, canal, prevu_le, texte) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (int rang = depuis; rang < c.journal().size(); rang++) {
                Ligne l = enLigne(c.journal().get(rang));
                s.setString(1, c.id().toString());
                s.setInt(2, rang);
                s.setString(3, l.type());
                s.setString(4, l.date().toString());
                s.setString(5, l.etape() == null ? null : l.etape().name());
                s.setString(6, l.entretien() == null ? null : l.entretien().name());
                s.setString(7, l.canal() == null ? null : l.canal().name());
                s.setString(8, l.prevuLe() == null ? null : l.prevuLe().toString());
                s.setString(9, l.texte());
                s.executeUpdate();
            }
        }
    }

    private List<Evenement> journal(UUID candidature) throws SQLException {
        List<Evenement> journal = new ArrayList<>();
        try (PreparedStatement s = connexion.prepareStatement(
                "SELECT * FROM evenement WHERE candidature_id = ? ORDER BY rang")) {
            s.setString(1, candidature.toString());
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    journal.add(depuisLigne(r));
                }
            }
        }
        return journal;
    }

    /** Charge les candidatures qui satisfont un filtre SQL sur l'alias {@code c}, avec leur journal complet. */
    private List<Numerotee> charger(String filtre, Object... parametres) {
        return lecture(() -> {
            Map<String, List<Evenement>> journaux = new HashMap<>();
            try (PreparedStatement s = connexion.prepareStatement("SELECT e.* FROM evenement e "
                    + "JOIN candidature c ON c.id = e.candidature_id WHERE " + filtre + " ORDER BY e.candidature_id, e.rang")) {
                lier(s, parametres);
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        journaux.computeIfAbsent(r.getString("candidature_id"), id -> new ArrayList<>()).add(depuisLigne(r));
                    }
                }
            }
            List<Numerotee> resultat = new ArrayList<>();
            try (PreparedStatement s = connexion.prepareStatement(
                    "SELECT c.numero, c.id, c.entreprise, c.poste FROM candidature c WHERE " + filtre + " ORDER BY c.numero")) {
                lier(s, parametres);
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        resultat.add(lire(r, journaux));
                    }
                }
            }
            return resultat;
        });
    }

    private static Numerotee lire(ResultSet r, Map<String, List<Evenement>> journaux) throws SQLException {
        long numero = r.getLong("numero");
        String id = r.getString("id");
        try {
            return new Numerotee(numero, new Candidature(
                    UUID.fromString(id), r.getString("entreprise"), r.getString("poste"), journaux.getOrDefault(id, List.of())));
        } catch (IllegalArgumentException | RefusMetier e) {
            throw new ErreurStockage("La candidature n° " + numero + " est illisible : " + e.getMessage(), e);
        }
    }

    private static void lier(PreparedStatement s, Object... parametres) throws SQLException {
        for (int i = 0; i < parametres.length; i++) {
            s.setObject(i + 1, parametres[i]);
        }
    }

    private static void copier(Connection connexion, Path cible) throws SQLException {
        try (PreparedStatement s = connexion.prepareStatement("VACUUM INTO ?")) {
            s.setString(1, cible.toAbsolutePath().toString());
            s.executeUpdate();
        }
    }

    @FunctionalInterface
    private interface Travail<T> {
        T executer() throws SQLException;
    }

    /** Tout ou rien : une écriture interrompue ne laisse aucune trace (R2). */
    private <T> T transaction(Travail<T> travail) {
        try {
            connexion.setAutoCommit(false);
            try {
                T resultat = travail.executer();
                connexion.commit();
                return resultat;
            } catch (SQLException | RuntimeException e) {
                connexion.rollback();
                throw e;
            } finally {
                connexion.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new ErreurStockage("La base a refusé l'écriture, rien n'a été enregistré : " + e.getMessage(), e);
        }
    }

    private <T> T lecture(Travail<T> travail) {
        try {
            return travail.executer();
        } catch (SQLException e) {
            throw new ErreurStockage("Lecture de la base impossible : " + e.getMessage(), e);
        }
    }

    private static void fermerSansEchec(Connection c) {
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignoree) {
                // l'erreur d'origine est plus utile que celle de la fermeture
            }
        }
    }
}
