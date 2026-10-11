package postulat.stockage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import postulat.production.Assertion;
import postulat.production.AvisRecruteur;
import postulat.production.Critere;
import postulat.production.Cv;
import postulat.production.Dossier;
import postulat.production.Evaluation;
import postulat.production.Exigence;
import postulat.production.Identite;
import postulat.production.Instantane;
import postulat.production.Lettre;
import postulat.production.NoteAts;
import postulat.production.Origine;
import postulat.production.ReglesGeneration;

/**
 * Une version du dossier mise en tables, phrase par phrase (migrations V4 à V6), et relue à
 * l'identique : la version 0 est le brouillon courant, les suivantes les versions validées, que des
 * triggers rendent immuables. Les transactions restent à {@link Base} : ici, on ne fait que traduire.
 */
final class TablesDossier {

    static final int BROUILLON = 0;

    private static final String CV = "CV";
    private static final String LETTRE = "LETTRE";
    private static final String RETIREE = "RETIREE";

    private static final String INTITULE = "INTITULE";
    private static final String PRECISION = "PRECISION";
    private static final String PUCE = "PUCE";
    private static final String LIGNE = "LIGNE";
    private static final String PHRASE = "PHRASE";

    private static final String FORCE = "FORCE";
    private static final String FAIBLESSE = "FAIBLESSE";
    private static final String CORRECTION = "CORRECTION";
    private static final String CORRECTION_ECARTEE = "CORRECTION_ECARTEE";
    private static final String AVERTISSEMENT = "AVERTISSEMENT";

    private static final List<String> TABLES_FILLES = List.of(
            "dossier_phrase_fait", "dossier_phrase", "dossier_section", "dossier_exigence", "dossier_critere", "dossier_remarque");

    private final Connection connexion;
    private final String id;
    private final int version;

    TablesDossier(Connection connexion, UUID candidature, int version) {
        this.connexion = connexion;
        this.id = candidature.toString();
        this.version = version;
    }

    // --- écriture ---

    /** Remplace le brouillon : rien de l'ancien ne survit à côté du nouveau. */
    void ecrireBrouillon(DossierGenere g) throws SQLException {
        exigerBrouillon();
        for (String table : TABLES_FILLES) {
            executer("DELETE FROM " + table + " WHERE candidature_id = ? AND version = ?", id, version);
        }
        executer("DELETE FROM dossier WHERE candidature_id = ? AND version = ?", id, version);
        inserer(g, null, null);
    }

    /** Ajoute une version validée, avec l'instantané des faits qu'elle cite. Elle ne se touchera plus. */
    void ecrireVersion(VersionValidee v) throws SQLException {
        if (version == BROUILLON || v.version() != version) {
            throw new IllegalStateException("Version validée incohérente : " + v.version() + " au lieu de " + version + ".");
        }
        inserer(v.dossier(), v.valideLe().toString(), v.repertoire());
        for (int i = 0; i < v.faits().size(); i++) {
            Instantane f = v.faits().get(i);
            executer("INSERT INTO dossier_fait (candidature_id, version, rang, fait_id, contenu) VALUES (?, ?, ?, ?, ?)",
                    id, version, i, f.fait(), f.contenu());
        }
    }

    private void inserer(DossierGenere g, String valideLe, String repertoire) throws SQLException {
        Dossier d = g.dossier();
        Identite e = d.cv().entete();
        Lettre l = d.lettre();
        Evaluation ev = d.evaluation();
        ReglesGeneration r = ev.regles();
        executer("INSERT INTO dossier (candidature_id, version, genere_le, empreinte_profil, modele, passes, nom, titre, contact, "
                + "lieu, destinataire, lieu_et_date, objet, paragraphes, note_ats, ats_lisible, seuil, poids_ats, revisions_max, "
                + "mots_min, mots_max, corrige_le, avis_perime, valide_le, repertoire) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, version, g.genereLe().toString(), g.empreinteProfil(), g.modele(), d.rapport().passes(),
                e.nom(), e.titre(), e.contact(), e.lieu(), l.destinataire(), l.lieuEtDate(), l.objet(), l.paragraphes().size(),
                ev.ats().map(NoteAts::note).orElse(null), ev.ats().map(a -> a.lisible() ? 1 : 0).orElse(null),
                r.seuil(), r.poidsAts(), r.revisionsMax(), r.motsMin(), r.motsMax(),
                g.corrigeLe().map(Instant::toString).orElse(null), g.avisPerime() ? 1 : 0, valideLe, repertoire);

        int[] rang = {0};
        for (int s = 0; s < d.cv().sections().size(); s++) {
            Cv.Section section = d.cv().sections().get(s);
            executer("INSERT INTO dossier_section (candidature_id, version, rang, titre) VALUES (?, ?, ?, ?)", id, version, s,
                    section.titre());
            for (int c = 0; c < section.contenus().size(); c++) {
                switch (section.contenus().get(c)) {
                    case Cv.Bloc b -> {
                        phrase(rang, CV, s, c, INTITULE, null, b.intitule());
                        if (b.precision().isPresent()) {
                            phrase(rang, CV, s, c, PRECISION, null, b.precision().get());
                        }
                        for (Cv.Puce p : b.puces()) {
                            phrase(rang, CV, s, c, PUCE, p.rang(), p.assertion());
                        }
                    }
                    case Cv.Ligne ligne -> phrase(rang, CV, s, c, LIGNE, null, ligne.assertion());
                }
            }
        }
        for (int p = 0; p < l.paragraphes().size(); p++) {
            for (Assertion a : l.paragraphes().get(p).phrases()) {
                phrase(rang, LETTRE, p, 0, PHRASE, null, a);
            }
        }
        for (Dossier.Retiree x : d.rapport().retirees()) {
            phrase(rang, RETIREE, x.section(), x.contenu(), PUCE, x.puce().rang(), x.puce().assertion());
        }

        if (ev.ats().isPresent()) {
            List<Exigence> presentes = ev.ats().get().presentes();
            List<Exigence> toutes = new ArrayList<>(presentes);
            toutes.addAll(ev.ats().get().absentes());
            for (int i = 0; i < toutes.size(); i++) {
                Exigence x = toutes.get(i);
                executer("INSERT INTO dossier_exigence (candidature_id, version, rang, libelle, obligatoire, presente) "
                        + "VALUES (?, ?, ?, ?, ?, ?)", id, version, i, x.libelle(), x.obligatoire() ? 1 : 0, i < presentes.size() ? 1 : 0);
            }
        }
        AvisRecruteur avis = ev.avis();
        for (int i = 0; i < avis.criteres().size(); i++) {
            AvisRecruteur.NoteCritere n = avis.criteres().get(i);
            executer("INSERT INTO dossier_critere (candidature_id, version, rang, critere, note, justification) VALUES (?, ?, ?, ?, ?, ?)",
                    id, version, i, n.critere().name(), n.note(), n.justification());
        }
        remarques(FORCE, avis.forces());
        remarques(FAIBLESSE, avis.faiblesses());
        remarques(CORRECTION, avis.corrections());
        remarques(CORRECTION_ECARTEE, d.rapport().correctionsEcartees());
        remarques(AVERTISSEMENT, d.rapport().avertissements());
    }

    private void phrase(int[] rang, String document, int section, int contenu, String role, Integer pertinence, Assertion a)
            throws SQLException {
        String origine = switch (a.origine()) {
            case Origine.Faits f -> "FAITS";
            case Origine.Offre o -> "OFFRE";
            case Origine.Auteur au -> "AUTEUR";
        };
        executer("INSERT INTO dossier_phrase (candidature_id, version, rang, document, section, contenu, role, pertinence, texte, "
                + "origine) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, version, rang[0], document, section, contenu, role, pertinence, a.texte(), origine);
        if (a.origine() instanceof Origine.Faits f) {
            for (int i = 0; i < f.ids().size(); i++) {
                executer("INSERT INTO dossier_phrase_fait (candidature_id, version, rang, position, fait_id) VALUES (?, ?, ?, ?, ?)",
                        id, version, rang[0], i, f.ids().get(i));
            }
        }
        rang[0]++;
    }

    private void remarques(String nature, List<String> textes) throws SQLException {
        for (int i = 0; i < textes.size(); i++) {
            executer("INSERT INTO dossier_remarque (candidature_id, version, nature, rang, texte) VALUES (?, ?, ?, ?, ?)",
                    id, version, nature, i, textes.get(i));
        }
    }

    // --- lecture ---

    /** Une phrase relue, avec sa place dans le dossier. */
    private record Ligne(String document, int section, int contenu, String role, Integer pertinence, Assertion assertion) {}

    Optional<DossierGenere> lireBrouillon() throws SQLException {
        exigerBrouillon();
        return lire().map(Lu::dossier);
    }

    Optional<VersionValidee> lireVersion() throws SQLException {
        Optional<Lu> lu = lire();
        if (lu.isEmpty()) {
            return Optional.empty();
        }
        List<Instantane> faits = new ArrayList<>();
        try (PreparedStatement s = connexion.prepareStatement(
                "SELECT fait_id, contenu FROM dossier_fait WHERE candidature_id = ? AND version = ? ORDER BY rang")) {
            s.setString(1, id);
            s.setInt(2, version);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    faits.add(new Instantane(r.getString(1), r.getString(2)));
                }
            }
        }
        return Optional.of(new VersionValidee(version, lu.get().dossier(), lu.get().valideLe().orElseThrow(),
                lu.get().repertoire().orElseThrow(), faits));
    }

    private record Lu(DossierGenere dossier, Optional<Instant> valideLe, Optional<String> repertoire) {}

    private Optional<Lu> lire() throws SQLException {
        try (PreparedStatement s = connexion.prepareStatement("SELECT * FROM dossier WHERE candidature_id = ? AND version = ?")) {
            s.setString(1, id);
            s.setInt(2, version);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                Identite entete = new Identite(r.getString("nom"), r.getString("titre"), r.getString("contact"), r.getString("lieu"));
                List<Ligne> lignes = phrases();
                Cv cv = new Cv(entete, sections(lignes));
                List<Lettre.Paragraphe> paragraphes = new ArrayList<>();
                for (int p = 0; p < r.getInt("paragraphes"); p++) {
                    int numero = p;
                    paragraphes.add(new Lettre.Paragraphe(lignes.stream()
                            .filter(l -> l.document().equals(LETTRE) && l.section() == numero).map(Ligne::assertion).toList()));
                }
                Lettre lettre = new Lettre(entete, r.getString("destinataire"), r.getString("lieu_et_date"), r.getString("objet"), paragraphes);
                ReglesGeneration regles = new ReglesGeneration(r.getInt("seuil"), r.getInt("poids_ats"), r.getInt("revisions_max"),
                        r.getInt("mots_min"), r.getInt("mots_max"));
                int noteAts = r.getInt("note_ats");
                Optional<NoteAts> ats = r.wasNull() ? Optional.empty() : Optional.of(ats(noteAts, r.getInt("ats_lisible") == 1));
                AvisRecruteur avis = new AvisRecruteur(criteres(), remarques(FORCE), remarques(FAIBLESSE), remarques(CORRECTION));
                List<Dossier.Retiree> retirees = lignes.stream().filter(l -> l.document().equals(RETIREE))
                        .map(l -> new Dossier.Retiree(new Cv.Puce(l.assertion(), l.pertinence()), l.section(), l.contenu())).toList();
                Dossier dossier = new Dossier(cv, lettre, new Evaluation(ats, avis, regles),
                        new Dossier.Rapport(r.getInt("passes"), retirees, remarques(AVERTISSEMENT), remarques(CORRECTION_ECARTEE)));
                Optional<Instant> corrigeLe = Optional.ofNullable(r.getString("corrige_le")).map(Instant::parse);
                DossierGenere g = new DossierGenere(dossier, Instant.parse(r.getString("genere_le")), r.getString("empreinte_profil"),
                        r.getString("modele"), corrigeLe, r.getInt("avis_perime") == 1);
                return Optional.of(new Lu(g, Optional.ofNullable(r.getString("valide_le")).map(Instant::parse),
                        Optional.ofNullable(r.getString("repertoire"))));
            }
        }
    }

    private List<Ligne> phrases() throws SQLException {
        Map<Integer, List<String>> faits = new LinkedHashMap<>();
        try (PreparedStatement s = connexion.prepareStatement(
                "SELECT rang, fait_id FROM dossier_phrase_fait WHERE candidature_id = ? AND version = ? ORDER BY rang, position")) {
            s.setString(1, id);
            s.setInt(2, version);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    faits.computeIfAbsent(r.getInt(1), k -> new ArrayList<>()).add(r.getString(2));
                }
            }
        }
        List<Ligne> lignes = new ArrayList<>();
        try (PreparedStatement s = connexion.prepareStatement(
                "SELECT * FROM dossier_phrase WHERE candidature_id = ? AND version = ? ORDER BY rang")) {
            s.setString(1, id);
            s.setInt(2, version);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    Origine origine = switch (r.getString("origine")) {
                        case "FAITS" -> new Origine.Faits(faits.getOrDefault(r.getInt("rang"), List.of()));
                        case "OFFRE" -> new Origine.Offre();
                        case "AUTEUR" -> new Origine.Auteur();
                        default -> throw new ErreurStockage("Origine inconnue dans la base : " + r.getString("origine") + ".");
                    };
                    int pertinence = r.getInt("pertinence");
                    lignes.add(new Ligne(r.getString("document"), r.getInt("section"), r.getInt("contenu"), r.getString("role"),
                            r.wasNull() ? null : pertinence, new Assertion(r.getString("texte"), origine)));
                }
            }
        }
        return lignes;
    }

    /** Les sections, et dans chacune ses blocs et ses lignes, regroupés par numéro de contenu. */
    private List<Cv.Section> sections(List<Ligne> lignes) throws SQLException {
        List<Cv.Section> sections = new ArrayList<>();
        try (PreparedStatement s = connexion.prepareStatement(
                "SELECT rang, titre FROM dossier_section WHERE candidature_id = ? AND version = ? ORDER BY rang")) {
            s.setString(1, id);
            s.setInt(2, version);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    int numero = r.getInt(1);
                    Map<Integer, List<Ligne>> parContenu = new LinkedHashMap<>();
                    lignes.stream().filter(l -> l.document().equals(CV) && l.section() == numero)
                            .forEach(l -> parContenu.computeIfAbsent(l.contenu(), k -> new ArrayList<>()).add(l));
                    List<Cv.Contenu> contenus = new ArrayList<>();
                    for (List<Ligne> contenu : parContenu.values()) {
                        contenus.add(contenu(contenu));
                    }
                    sections.add(new Cv.Section(r.getString(2), contenus));
                }
            }
        }
        return sections;
    }

    private static Cv.Contenu contenu(List<Ligne> lignes) {
        Ligne premiere = lignes.get(0);
        if (premiere.role().equals(LIGNE)) {
            return new Cv.Ligne(premiere.assertion());
        }
        Optional<Assertion> precision = lignes.stream().filter(l -> l.role().equals(PRECISION)).map(Ligne::assertion).findFirst();
        List<Cv.Puce> puces = lignes.stream().filter(l -> l.role().equals(PUCE))
                .map(l -> new Cv.Puce(l.assertion(), l.pertinence())).toList();
        return new Cv.Bloc(premiere.assertion(), precision, puces);
    }

    private NoteAts ats(int note, boolean lisible) throws SQLException {
        List<Exigence> presentes = new ArrayList<>();
        List<Exigence> absentes = new ArrayList<>();
        try (PreparedStatement s = connexion.prepareStatement("SELECT libelle, obligatoire, presente FROM dossier_exigence "
                + "WHERE candidature_id = ? AND version = ? ORDER BY rang")) {
            s.setString(1, id);
            s.setInt(2, version);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    Exigence e = new Exigence(r.getString(1), r.getInt(2) == 1);
                    (r.getInt(3) == 1 ? presentes : absentes).add(e);
                }
            }
        }
        return new NoteAts(note, lisible, presentes, absentes);
    }

    private List<AvisRecruteur.NoteCritere> criteres() throws SQLException {
        List<AvisRecruteur.NoteCritere> criteres = new ArrayList<>();
        try (PreparedStatement s = connexion.prepareStatement("SELECT critere, note, justification FROM dossier_critere "
                + "WHERE candidature_id = ? AND version = ? ORDER BY rang")) {
            s.setString(1, id);
            s.setInt(2, version);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    criteres.add(new AvisRecruteur.NoteCritere(Critere.valueOf(r.getString(1)), r.getInt(2), r.getString(3)));
                }
            }
        }
        return criteres;
    }

    private List<String> remarques(String nature) throws SQLException {
        List<String> textes = new ArrayList<>();
        try (PreparedStatement s = connexion.prepareStatement(
                "SELECT texte FROM dossier_remarque WHERE candidature_id = ? AND version = ? AND nature = ? ORDER BY rang")) {
            s.setString(1, id);
            s.setInt(2, version);
            s.setString(3, nature);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    textes.add(r.getString(1));
                }
            }
        }
        return textes;
    }

    private void exigerBrouillon() {
        if (version != BROUILLON) {
            throw new IllegalStateException("Seul le brouillon se remplace ; une version validée ne se touche plus (RG-15).");
        }
    }

    private void executer(String sql, Object... parametres) throws SQLException {
        try (PreparedStatement s = connexion.prepareStatement(sql)) {
            for (int i = 0; i < parametres.length; i++) {
                s.setObject(i + 1, parametres[i]);
            }
            s.executeUpdate();
        }
    }
}
