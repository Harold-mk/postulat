package postulat.stockage;

import static org.assertj.core.api.Assertions.assertThat;
import static postulat.production.Exigence.obligatoire;
import static postulat.production.Exigence.souhaitee;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import postulat.production.Assertion;
import postulat.production.AvisRecruteur;
import postulat.production.Critere;
import postulat.production.Cv;
import postulat.production.Dossier;
import postulat.production.Evaluation;
import postulat.production.Identite;
import postulat.production.Lettre;
import postulat.production.NoteAts;
import postulat.production.Origine;
import postulat.production.ReglesGeneration;
import postulat.suivi.Candidature;
import postulat.suivi.Etape;

/** Le dossier courant en base : relu à l'identique, phrase par phrase, origine comprise (CA-3.7). */
class DossierStockageTest {

    static final Clock HORLOGE = Clock.fixed(Instant.parse("2026-10-10T08:30:00Z"), ZoneOffset.UTC);
    static final Instant GENERE = Instant.parse("2026-10-10T09:00:00Z");
    static final Identite CAMILLE = new Identite("Camille Martin", "Étudiante en master data", "camille@exemple.test", "Lyon");

    static Assertion faits(String texte, String... ids) {
        return new Assertion(texte, Origine.Faits.de(ids));
    }

    static final Cv.Puce DASHBOARD = new Cv.Puce(faits("Conçu un tableau de bord Power BI de 5 KPI", "exp-acme-r1", "comp-sql"), 1);
    static final Cv.Puce RETIREE = new Cv.Puce(faits("Nettoyé les données de ventes", "exp-acme-r2"), 3);

    static final Cv CV = new Cv(CAMILLE, List.of(
            new Cv.Section("Expériences", List.of(
                    new Cv.Bloc(faits("Stagiaire data analyst — Acme", "exp-acme"), Optional.of(faits("2025 · 6 mois", "exp-acme")),
                            List.of(DASHBOARD, new Cv.Puce(new Assertion("Animé un atelier interne", new Origine.Auteur()), 2))),
                    new Cv.Bloc(faits("Assistant — Initech", "exp-initech"), Optional.empty(), List.of()))),
            new Cv.Section("Compétences", List.of(new Cv.Ligne(faits("SQL · Python", "comp-sql", "comp-python")))),
            new Cv.Section("Vide", List.of())));

    static final Lettre LETTRE = new Lettre(CAMILLE, "Globex — Service du recrutement", "Lyon, le 10 octobre 2026",
            "Candidature au poste « Stage data »", List.of(
                    new Lettre.Paragraphe(List.of(new Assertion("Globex compte 250 salariés.", new Origine.Offre()))),
                    new Lettre.Paragraphe(List.of(faits("J'ai conçu un tableau de bord.", "exp-acme-r1"),
                            new Assertion("Je parle japonais.", new Origine.Auteur()))),
                    new Lettre.Paragraphe(List.of(faits("Rencontrons-nous.", "exp-acme")))));

    static AvisRecruteur avis() {
        return new AvisRecruteur(
                Arrays.stream(Critere.values()).map(c -> new AvisRecruteur.NoteCritere(c, c.ordinal() % 6, "Pour " + c.libelle())).toList(),
                List.of("Preuves chiffrées", "Lettre ciblée"), List.of("Ciblage perfectible"), List.of("Reprenez « Power BI »"));
    }

    static Dossier dossier(Optional<NoteAts> ats) {
        return new Dossier(CV, LETTRE, new Evaluation(ats, avis(), new ReglesGeneration(70, 30, 1, 200, 300)),
                new Dossier.Rapport(2, List.of(new Dossier.Retiree(RETIREE, 0, 1)), List.of("Seuil non atteint", "CV : phrase introuvable"),
                        List.of("Ajoutez Kubernetes")));
    }

    static final NoteAts ATS = new NoteAts(67, true, List.of(obligatoire("SQL"), souhaitee("Python")),
            List.of(obligatoire("Power BI"), souhaitee("Kubernetes")));

    @TempDir
    Path dossier;

    Base base;
    Candidature candidature;

    @BeforeEach
    void ouvrir() {
        base = Base.ouvrir(dossier.resolve("postulat.db"), new Sauvegardes(dossier.resolve("sauvegardes"), HORLOGE), HORLOGE);
        candidature = Candidature.creer("Globex", "Stage data", Etape.A_PREPARER, LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 10));
        base.creer(candidature);
    }

    @AfterEach
    void fermer() {
        base.close();
    }

    @Test
    void sans_generation_il_n_y_a_pas_de_dossier() {
        assertThat(base.dossier(candidature.id())).isEmpty();
    }

    @Test
    void le_dossier_se_relit_a_l_identique() {
        DossierGenere g = DossierGenere.nouveau(dossier(Optional.of(ATS)), GENERE, "ab12");

        base.enregistrerDossier(candidature.id(), g);

        assertThat(base.dossier(candidature.id())).contains(g);
    }

    @Test
    void sans_note_ats_le_dossier_se_relit_aussi() {
        DossierGenere g = DossierGenere.nouveau(dossier(Optional.empty()), GENERE, "ab12");

        base.enregistrerDossier(candidature.id(), g);

        assertThat(base.dossier(candidature.id())).contains(g);
    }

    @Test
    void une_generation_remplace_la_precedente_en_entier() {
        base.enregistrerDossier(candidature.id(), DossierGenere.nouveau(dossier(Optional.of(ATS)), GENERE, "ab12"));
        Dossier court = new Dossier(new Cv(CAMILLE, List.of()),
                new Lettre(CAMILLE, "Globex", "Le 11 octobre 2026", "Candidature", List.of()),
                new Evaluation(Optional.empty(), avis(), ReglesGeneration.PAR_DEFAUT),
                new Dossier.Rapport(1, List.of(), List.of(), List.of()));
        DossierGenere nouveau = DossierGenere.nouveau(court, GENERE.plusSeconds(3600), "cd34");

        base.enregistrerDossier(candidature.id(), nouveau);

        assertThat(base.dossier(candidature.id())).contains(nouveau);
    }

    @Test
    void une_correction_se_relit_avec_sa_date_et_son_avis_perime_jusqu_au_suivant() {
        Dossier d = dossier(Optional.of(ATS));
        Instant corrige = GENERE.plusSeconds(60);
        DossierGenere g = DossierGenere.nouveau(d, GENERE, "ab12").corrige(d.corriger(1, "Data analyst — Acme"), corrige);

        base.enregistrerDossier(candidature.id(), g);

        assertThat(base.dossier(candidature.id())).contains(g);
        assertThat(g.avisPerime()).isTrue();
        DossierGenere reevalue = g.reevalue(g.dossier());
        base.enregistrerDossier(candidature.id(), reevalue);
        assertThat(base.dossier(candidature.id())).hasValueSatisfying(x -> {
            assertThat(x.avisPerime()).isFalse();
            assertThat(x.corrigeLe()).contains(corrige);
            assertThat(x.dossier().reintroduire(1).cv().sections().get(0).contenus().get(1)).isInstanceOf(Cv.Bloc.class);
        });
    }
}
