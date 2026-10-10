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
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import postulat.ia.AppelModele;
import postulat.ia.Consommation;
import postulat.ia.Demande;
import postulat.production.Couverture;
import postulat.production.Exigence;
import postulat.production.OffreAnalysee;
import postulat.production.Rapprochement;
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
        return transaction(() -> new Numerotee(insererCandidature(c), c));
    }

    /**
     * Crée la candidature avec son offre, d'un seul tenant (UC-05) : jamais l'une sans l'autre. Le texte
     * brut est conservé tel quel (CA-2.5) ; l'analyse est absente si le modèle était indisponible (E2).
     */
    public Numerotee creerDepuisOffre(Candidature c, String source, String texteBrut, Instant recueLe,
            Optional<OffreAnalysee> analyse) {
        return transaction(() -> {
            long numero = insererCandidature(c);
            try (PreparedStatement s = connexion.prepareStatement(
                    "INSERT INTO offre (candidature_id, source, texte_brut, recue_le) VALUES (?, ?, ?, ?)")) {
                s.setString(1, c.id().toString());
                s.setString(2, source);
                s.setString(3, texteBrut);
                s.setString(4, recueLe.toString());
                s.executeUpdate();
            }
            if (analyse.isPresent()) {
                ecrireAnalyse(c.id(), analyse.get(), recueLe);
            }
            return new Numerotee(numero, c);
        });
    }

    /** Remplace l'analyse d'une offre ; le rapprochement, fondé sur l'ancienne, est effacé avec elle. */
    public void enregistrerAnalyse(UUID candidature, OffreAnalysee analyse, Instant le) {
        transaction(() -> {
            ecrireAnalyse(candidature, analyse, le);
            return null;
        });
    }

    /** Remplace le rapprochement d'une offre analysée, exigence par exigence, dans l'ordre de l'analyse. */
    public void enregistrerRapprochement(UUID candidature, Rapprochement rapprochement, Instant le) {
        transaction(() -> {
            List<Exigence> exigences = exigences(candidature);
            List<Exigence> rapprochees = rapprochement.couvertures().stream().map(Couverture::exigence).toList();
            if (!exigences.equals(rapprochees)) {
                throw new ErreurStockage("Ce rapprochement ne porte pas sur les exigences de l'analyse enregistrée : "
                        + "rien n'a été enregistré.");
            }
            executer("DELETE FROM exigence_fait WHERE candidature_id = ?", candidature.toString());
            for (int rang = 0; rang < exigences.size(); rang++) {
                Couverture c = rapprochement.couvertures().get(rang);
                executer("UPDATE exigence SET justification = ? WHERE candidature_id = ? AND rang = ?",
                        c.justification(), candidature.toString(), rang);
                for (String fait : c.faits()) {
                    executer("INSERT INTO exigence_fait (candidature_id, rang, fait_id) VALUES (?, ?, ?)",
                            candidature.toString(), rang, fait);
                }
            }
            executer("UPDATE offre SET rapprochee_le = ?, note = ?, justification = ? WHERE candidature_id = ?",
                    le.toString(), rapprochement.note(), rapprochement.justification(), candidature.toString());
            return null;
        });
    }

    /** L'offre d'une candidature, si elle en a une : une candidature saisie à la main n'en a pas. */
    public Optional<Offre> offre(UUID candidature) {
        return lecture(() -> {
            try (PreparedStatement s = connexion.prepareStatement("SELECT * FROM offre WHERE candidature_id = ?")) {
                s.setString(1, candidature.toString());
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) {
                        return Optional.empty();
                    }
                    Optional<Instant> analyseeLe = instant(r.getString("analysee_le"));
                    Optional<Instant> rapprocheeLe = instant(r.getString("rapprochee_le"));
                    List<Exigence> exigences = exigences(candidature);
                    Optional<OffreAnalysee> analyse = analyseeLe.isEmpty() ? Optional.empty() : Optional.of(new OffreAnalysee(
                            r.getString("poste"), r.getString("entreprise"), r.getString("lieu"), r.getString("contrat"),
                            r.getString("debut"), r.getString("duree"), r.getString("niveau"), missions(candidature), exigences));
                    Optional<Rapprochement> rapprochement = rapprocheeLe.isEmpty() ? Optional.empty() : Optional.of(
                            new Rapprochement(couvertures(candidature, exigences), r.getInt("note"), r.getString("justification")));
                    return Optional.of(new Offre(r.getString("source"), r.getString("texte_brut"),
                            Instant.parse(r.getString("recue_le")), analyse, analyseeLe, rapprochement, rapprocheeLe));
                }
            }
        });
    }

    /**
     * Enregistre le dossier généré pour une candidature, en remplaçant le précédent d'un seul tenant
     * (UC-07 E4) : on ne trouve jamais un dossier fait de deux générations.
     */
    public void enregistrerDossier(UUID candidature, DossierGenere dossier) {
        transaction(() -> {
            new TablesDossier(connexion, candidature).ecrire(dossier);
            return null;
        });
    }

    /** Le dossier courant d'une candidature, s'il en a un. */
    public Optional<DossierGenere> dossier(UUID candidature) {
        return lecture(() -> new TablesDossier(connexion, candidature).lire());
    }

    /** La candidature dont l'offre a exactement ce texte : la même offre collée deux fois (RG-18). */
    public Optional<Numerotee> candidatureDeLOffre(String texteBrut) {
        Optional<Long> numero = lecture(() -> {
            try (PreparedStatement s = connexion.prepareStatement("SELECT c.numero FROM offre o "
                    + "JOIN candidature c ON c.id = o.candidature_id WHERE o.texte_brut = ? ORDER BY c.numero LIMIT 1")) {
                s.setString(1, texteBrut);
                try (ResultSet r = s.executeQuery()) {
                    return r.next() ? Optional.of(r.getLong(1)) : Optional.empty();
                }
            }
        });
        return numero.flatMap(this::trouver);
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

    /** Ajoute un appel au journal du modèle (EN-5, EN-12). */
    public void journaliser(AppelModele appel) {
        Demande d = appel.demande();
        Optional<Consommation> c = appel.consommation();
        transaction(() -> {
            try (PreparedStatement s = connexion.prepareStatement("INSERT INTO appel_modele (survenu_le, tache, gamme, "
                    + "candidature_id, modele, jetons_entree, jetons_sortie, jetons_cache_lus, jetons_cache_ecrits, "
                    + "cout_micro_dollars, duree_ms, issue, consignes, contenu, reponse) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                s.setString(1, appel.survenuLe().toString());
                s.setString(2, d.tache());
                s.setString(3, d.gamme().name());
                s.setString(4, d.candidature().map(UUID::toString).orElse(null));
                s.setString(5, c.map(Consommation::modele).orElse(""));
                s.setLong(6, c.map(Consommation::jetonsEntree).orElse(0L));
                s.setLong(7, c.map(Consommation::jetonsSortie).orElse(0L));
                s.setLong(8, c.map(Consommation::jetonsCacheLus).orElse(0L));
                s.setLong(9, c.map(Consommation::jetonsCacheEcrits).orElse(0L));
                s.setLong(10, c.map(Consommation::coutMicroDollars).orElse(0L));
                s.setLong(11, c.map(x -> x.duree().toMillis()).orElse(0L));
                s.setString(12, appel.issue());
                s.setString(13, d.consignes());
                s.setString(14, d.contenu());
                s.setString(15, appel.reponse());
                s.executeUpdate();
            }
            return null;
        });
    }

    /** Ce qu'ont coûté les appels faits pour une candidature (EN-5). */
    public CoutModele coutModele(UUID candidature) {
        return lecture(() -> {
            try (PreparedStatement s = connexion.prepareStatement(
                    "SELECT count(*), coalesce(sum(cout_micro_dollars), 0) FROM appel_modele WHERE candidature_id = ?")) {
                s.setString(1, candidature.toString());
                try (ResultSet r = s.executeQuery()) {
                    r.next();
                    return new CoutModele(r.getInt(1), r.getLong(2));
                }
            }
        });
    }

    /** Le cumul de chaque mois, du plus ancien au plus récent, en temps universel (EN-5). */
    public Map<YearMonth, CoutModele> coutsModeleParMois() {
        return lecture(() -> {
            Map<YearMonth, CoutModele> mois = new LinkedHashMap<>();
            try (Statement s = connexion.createStatement();
                    ResultSet r = s.executeQuery("SELECT substr(survenu_le, 1, 7) AS mois, count(*), sum(cout_micro_dollars) "
                            + "FROM appel_modele GROUP BY mois ORDER BY mois")) {
                while (r.next()) {
                    mois.put(YearMonth.parse(r.getString(1)), new CoutModele(r.getInt(2), r.getLong(3)));
                }
            }
            return mois;
        });
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
    private static final String ECARTEMENT = "ECARTEMENT";
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
            case Evenement.Ecartement x -> new Ligne(ECARTEMENT, x.date(), null, null, null, null, x.motif());
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
            case ECARTEMENT -> new Evenement.Ecartement(date, r.getString("texte"));
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

    private long insererCandidature(Candidature c) throws SQLException {
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
        return numero;
    }

    private void ecrireAnalyse(UUID candidature, OffreAnalysee a, Instant le) throws SQLException {
        String id = candidature.toString();
        executer("DELETE FROM exigence_fait WHERE candidature_id = ?", id);
        executer("DELETE FROM exigence WHERE candidature_id = ?", id);
        executer("DELETE FROM offre_mission WHERE candidature_id = ?", id);
        int modifiees = executer("UPDATE offre SET analysee_le = ?, poste = ?, entreprise = ?, lieu = ?, contrat = ?, "
                + "debut = ?, duree = ?, niveau = ?, rapprochee_le = NULL, note = NULL, justification = NULL "
                + "WHERE candidature_id = ?", le.toString(), a.poste(), a.entreprise(), a.lieu(), a.contrat(),
                a.debut(), a.duree(), a.niveau(), id);
        if (modifiees != 1) {
            throw new ErreurStockage("Aucune offre pour la candidature " + candidature + ".");
        }
        for (int rang = 0; rang < a.missions().size(); rang++) {
            executer("INSERT INTO offre_mission (candidature_id, rang, texte) VALUES (?, ?, ?)", id, rang, a.missions().get(rang));
        }
        for (int rang = 0; rang < a.exigences().size(); rang++) {
            Exigence e = a.exigences().get(rang);
            executer("INSERT INTO exigence (candidature_id, rang, libelle, obligatoire) VALUES (?, ?, ?, ?)",
                    id, rang, e.libelle(), e.obligatoire() ? 1 : 0);
        }
    }

    private List<String> missions(UUID candidature) throws SQLException {
        List<String> missions = new ArrayList<>();
        try (PreparedStatement s = connexion.prepareStatement(
                "SELECT texte FROM offre_mission WHERE candidature_id = ? ORDER BY rang")) {
            s.setString(1, candidature.toString());
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    missions.add(r.getString(1));
                }
            }
        }
        return missions;
    }

    private List<Exigence> exigences(UUID candidature) throws SQLException {
        List<Exigence> exigences = new ArrayList<>();
        try (PreparedStatement s = connexion.prepareStatement(
                "SELECT libelle, obligatoire FROM exigence WHERE candidature_id = ? ORDER BY rang")) {
            s.setString(1, candidature.toString());
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    exigences.add(new Exigence(r.getString(1), r.getInt(2) == 1));
                }
            }
        }
        return exigences;
    }

    private List<Couverture> couvertures(UUID candidature, List<Exigence> exigences) throws SQLException {
        List<Couverture> couvertures = new ArrayList<>();
        for (int rang = 0; rang < exigences.size(); rang++) {
            String justification = "";
            List<String> faits = new ArrayList<>();
            try (PreparedStatement s = connexion.prepareStatement(
                    "SELECT justification FROM exigence WHERE candidature_id = ? AND rang = ?")) {
                lier(s, candidature.toString(), rang);
                try (ResultSet r = s.executeQuery()) {
                    if (r.next() && r.getString(1) != null) {
                        justification = r.getString(1);
                    }
                }
            }
            try (PreparedStatement s = connexion.prepareStatement(
                    "SELECT fait_id FROM exigence_fait WHERE candidature_id = ? AND rang = ? ORDER BY rowid")) {
                lier(s, candidature.toString(), rang);
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        faits.add(r.getString(1));
                    }
                }
            }
            couvertures.add(new Couverture(exigences.get(rang), faits, justification));
        }
        return couvertures;
    }

    private int executer(String sql, Object... parametres) throws SQLException {
        try (PreparedStatement s = connexion.prepareStatement(sql)) {
            lier(s, parametres);
            return s.executeUpdate();
        }
    }

    private static Optional<Instant> instant(String texte) {
        return texte == null ? Optional.empty() : Optional.of(Instant.parse(texte));
    }

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
