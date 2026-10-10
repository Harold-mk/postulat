package postulat.stockage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import postulat.suivi.Canal;
import postulat.suivi.Candidature;
import postulat.suivi.Contact;
import postulat.suivi.Etape;
import postulat.suivi.TypeEntretien;

class BaseTest {

    static final Clock HORLOGE = Clock.fixed(Instant.parse("2026-10-10T08:30:00Z"), ZoneOffset.UTC);
    static final LocalDate J0 = LocalDate.of(2026, 9, 1);
    static final LocalDate AUJOURDHUI = LocalDate.of(2026, 10, 10);

    @TempDir
    Path dossier;

    Base base;

    @BeforeEach
    void ouvrir() {
        base = ouvrirBase();
    }

    @AfterEach
    void fermer() {
        base.close();
    }

    Base ouvrirBase() {
        return Base.ouvrir(dossier.resolve("postulat.db"), new Sauvegardes(dossier.resolve("sauvegardes"), HORLOGE), HORLOGE);
    }

    static Candidature envoyee(String entreprise) {
        return Candidature.creer(entreprise, "Stage data", Etape.ENVOYEE, J0, AUJOURDHUI);
    }

    /** Un journal qui contient chacun des neuf types d'événements. */
    static Candidature completeLe(LocalDate j) {
        return Candidature.creer("Acme", "Stage data", Etape.ENVOYEE, j, AUJOURDHUI)
                .relancer(j.plusDays(10), AUJOURDHUI)
                .interagir(Canal.APPEL, "La RH rappelle", j.plusDays(11), AUJOURDHUI)
                .noterRetourPromis(j.plusDays(20), j.plusDays(11), AUJOURDHUI)
                .avancer(Etape.ENTRETIEN, j.plusDays(12), AUJOURDHUI)
                .planifierEntretien(TypeEntretien.TECHNIQUE, j.plusDays(15), j.plusDays(12), AUJOURDHUI)
                .noterEntretien(TypeEntretien.TECHNIQUE, j.plusDays(15), AUJOURDHUI)
                .noter("Compte rendu : SQL, puis étude de cas", j.plusDays(15), AUJOURDHUI)
                .avancer(Etape.REFUSEE, j.plusDays(20), AUJOURDHUI)
                .reouvrir(Etape.ENTRETIEN, j.plusDays(25), AUJOURDHUI);
    }

    void executerSql(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dossier.resolve("postulat.db"));
                Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    @Test
    void chaque_type_d_evenement_fait_l_aller_retour_a_l_identique() {
        Candidature c = completeLe(J0);
        long numero = base.creer(c).numero();

        assertThat(base.trouver(numero)).contains(new Numerotee(numero, c));
    }

    @Test
    void les_numeros_sont_attribues_dans_l_ordre_de_creation() {
        long premier = base.creer(envoyee("A")).numero();
        long second = base.creer(envoyee("B")).numero();

        assertThat(second).isGreaterThan(premier);
        assertThat(base.toutes()).extracting(n -> n.candidature().entreprise()).containsExactly("A", "B");
        assertThat(base.trouver(second + 1)).isEmpty();
    }

    @Test
    void enregistrer_ajoute_les_evenements_nouveaux_et_l_etape_suit() {
        Candidature c = envoyee("Acme");
        long numero = base.creer(c).numero();
        Candidature avancee = c.relancer(J0.plusDays(10), AUJOURDHUI).avancer(Etape.ENTRETIEN, J0.plusDays(12), AUJOURDHUI);

        base.enregistrer(avancee);

        assertThat(base.trouver(numero).orElseThrow().candidature()).isEqualTo(avancee);
        assertThat(base.aLEtape(Etape.ENTRETIEN)).extracting(Numerotee::numero).containsExactly(numero);
        assertThat(base.aLEtape(Etape.ENVOYEE)).isEmpty();
    }

    @Test
    void une_ecriture_perimee_est_refusee_sans_rien_perdre() {
        Candidature lue = envoyee("Acme");
        base.creer(lue);
        Candidature parAilleurs = lue.relancer(J0.plusDays(10), AUJOURDHUI);
        base.enregistrer(parAilleurs);

        Candidature perimee = lue.avancer(Etape.REFUSEE, J0.plusDays(11), AUJOURDHUI);

        assertThatThrownBy(() -> base.enregistrer(perimee))
                .isInstanceOf(ErreurStockage.class)
                .hasMessageContaining("rien n'a été enregistré");
        assertThat(base.toutes().get(0).candidature()).isEqualTo(parAilleurs);
    }

    @Test
    void un_journal_raccourci_est_refuse() {
        Candidature c = envoyee("Acme");
        base.creer(c);
        base.enregistrer(c.relancer(J0.plusDays(10), AUJOURDHUI));

        assertThatThrownBy(() -> base.enregistrer(c)).isInstanceOf(ErreurStockage.class);
    }

    @Test
    void une_candidature_inconnue_ne_s_enregistre_pas() {
        assertThatThrownBy(() -> base.enregistrer(envoyee("Inconnue")))
                .isInstanceOf(ErreurStockage.class)
                .hasMessageContaining("inconnue");
    }

    @Test
    void la_base_refuse_toute_modification_ou_suppression_d_evenement() {
        base.creer(envoyee("Acme"));

        assertThatThrownBy(() -> executerSql("UPDATE evenement SET survenu_le = '2020-01-01'"))
                .hasMessageContaining("RG-11");
        assertThatThrownBy(() -> executerSql("DELETE FROM evenement"))
                .hasMessageContaining("RG-11");
    }

    @Test
    void les_contacts_sont_rattaches_dans_l_ordre_de_leur_ajout() {
        Candidature c = envoyee("Acme");
        base.creer(c);
        base.ajouterContact(c.id(), new Contact("Marie Curie", "RH", "marie@acme.fr"));
        base.ajouterContact(c.id(), new Contact("Pierre Curie", "", ""));

        assertThat(base.contacts(c.id())).containsExactly(
                new Contact("Marie Curie", "RH", "marie@acme.fr"), new Contact("Pierre Curie", "", ""));
        assertThat(base.contacts(envoyee("Autre").id())).isEmpty();
    }

    @Test
    void un_contact_ne_se_rattache_pas_a_une_candidature_inconnue() {
        assertThatThrownBy(() -> base.ajouterContact(envoyee("Inconnue").id(), new Contact("X", "", "")))
                .isInstanceOf(ErreurStockage.class);
    }

    @Test
    void les_donnees_survivent_a_la_fermeture() {
        Candidature c = completeLe(J0);
        base.creer(c);
        base.close();

        base = ouvrirBase();

        assertThat(base.toutes()).extracting(Numerotee::candidature).containsExactly(c);
        assertThat(base.nombreEvenements()).isEqualTo(c.journal().size());
    }

    @Test
    void une_etape_stockee_qui_contredit_son_journal_est_detectee() throws SQLException {
        long numero = base.creer(envoyee("Acme")).numero();
        assertThat(base.verifierCoherence()).isEmpty();

        executerSql("UPDATE candidature SET etape = 'ACCEPTEE'");

        assertThat(base.verifierCoherence()).containsExactly(new Base.Divergence(numero, Etape.ACCEPTEE, Etape.ENVOYEE));
    }

    @Test
    void un_fichier_qui_n_est_pas_une_base_est_refuse_sans_etre_modifie() throws Exception {
        Path texte = dossier.resolve("notes.db");
        Files.writeString(texte, "Ceci est un texte, pas une base SQLite. ".repeat(40));

        assertThatThrownBy(() -> Base.ouvrir(texte, new Sauvegardes(dossier, HORLOGE), HORLOGE))
                .isInstanceOf(ErreurStockage.class);
        assertThat(Files.readString(texte)).startsWith("Ceci est un texte");
    }

    /**
     * RG-10 comme propriété (ADR-0005, point 5) : sur des parcours légaux tirés au hasard, enregistrés
     * pas à pas, l'étape stockée reste égale au rejeu du journal et rien ne se perd. La graine est fixe
     * pour qu'un échec se reproduise.
     */
    @Test
    void l_etape_stockee_egale_toujours_le_rejeu_du_journal() {
        Random hasard = new Random(20261010);
        List<Candidature> attendues = new ArrayList<>();

        for (int n = 0; n < 150; n++) {
            Etape depart = Etape.values()[hasard.nextInt(Etape.values().length)];
            LocalDate jour = J0.plusDays(hasard.nextInt(30));
            Candidature c = Candidature.creer("Entreprise " + n, "Stage", depart, jour, AUJOURDHUI.plusYears(1));
            base.creer(c);
            for (int pas = hasard.nextInt(12); pas > 0; pas--) {
                jour = jour.plusDays(hasard.nextInt(4));
                c = etapeAuHasard(c, jour, hasard);
                base.enregistrer(c);
            }
            attendues.add(c);
        }

        assertThat(base.verifierCoherence()).isEmpty();
        assertThat(base.toutes()).extracting(Numerotee::candidature).containsExactlyElementsOf(attendues);
    }

    static Candidature etapeAuHasard(Candidature c, LocalDate jour, Random hasard) {
        LocalDate loin = AUJOURDHUI.plusYears(1);
        Etape etape = c.etape();
        if (etape.estTerminale()) {
            return hasard.nextBoolean()
                    ? c.reouvrir(ouverteAuHasard(hasard), jour, loin)
                    : c.noter("note " + hasard.nextInt(), jour, loin);
        }
        List<Etape> suivantes = etape.suivantes().stream().sorted().toList();
        return switch (hasard.nextInt(5)) {
            case 0 -> etape == Etape.ENVOYEE ? c.relancer(jour, loin) : c.noter("note", jour, loin);
            case 1 -> etape == Etape.ENTRETIEN
                    ? c.planifierEntretien(TypeEntretien.values()[hasard.nextInt(3)], jour.plusDays(5), jour, loin)
                    : c.interagir(Canal.COURRIEL, "échange", jour, loin);
            case 2 -> c.noterRetourPromis(jour.plusDays(hasard.nextInt(10)), jour, loin);
            default -> c.avancer(suivantes.get(hasard.nextInt(suivantes.size())), jour, loin);
        };
    }

    static Etape ouverteAuHasard(Random hasard) {
        List<Etape> ouvertes = List.of(Etape.values()).stream().filter(e -> !e.estTerminale()).toList();
        return ouvertes.get(hasard.nextInt(ouvertes.size()));
    }
}
