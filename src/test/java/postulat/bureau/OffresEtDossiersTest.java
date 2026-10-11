package postulat.bureau;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import postulat.JeuDEssai;
import postulat.cas.CasDossier;
import postulat.cas.CasOffre;
import postulat.cas.Refus;
import postulat.ia.FauxModele;
import postulat.production.Origine;
import postulat.production.Rapprochement;
import postulat.stockage.Donnees;
import postulat.stockage.Numerotee;
import postulat.stockage.Offre;
import postulat.suivi.Etape;

/**
 * Les offres et les dossiers tels que la fenêtre les conduit : les mêmes cas que la ligne de commande,
 * et ce que la fenêtre y ajoute — une question au lieu d'une option, un doublon confirmé sans repayer
 * l'analyse.
 */
class OffresEtDossiersTest {

    static final Clock HORLOGE = Clock.fixed(Instant.parse("2026-10-11T09:00:00Z"), ZoneOffset.UTC);
    static final LocalDate J = LocalDate.of(2026, 10, 11);

    @TempDir
    Path dossier;

    FauxModele faux = new FauxModele();
    Session s;
    List<String> journal = new ArrayList<>();

    @BeforeEach
    void ouvrir() throws IOException {
        Files.writeString(dossier.resolve("profil.yaml"), JeuDEssai.PROFIL);
        Files.writeString(dossier.resolve("postulat.properties"), "lettre.mots.min = 50\nlettre.mots.max = 150\n");
        s = new Session(new Donnees(dossier, HORLOGE), HORLOGE, r -> faux);
    }

    /** L'offre de démonstration, analysée et rapprochée : la candidature n° 1. */
    long offre() {
        faux.repondre(JeuDEssai.FICHE).repondre(JeuDEssai.RAPPROCHEMENT);
        CasOffre.Soumission sm = s.offres().soumettre(JeuDEssai.OFFRE, "saisi dans la fenêtre", Optional.empty(), Optional.empty(), J,
                x -> false, (nom, feminin) -> Optional.empty(), journal::add);
        long n = ((CasOffre.Ajoutee) sm).numerotee().numero();
        s.offres().rapprocher(n, journal::add);
        return n;
    }

    @Test
    void une_offre_soumise_est_analysee_puis_rapprochee() {
        long n = offre();

        Session.Fiche fiche = s.fiche(n);
        assertThat(fiche.numerotee().candidature().entreprise()).isEqualTo("Acme");
        assertThat(fiche.numerotee().candidature().etape()).isEqualTo(Etape.REPEREE);
        assertThat(fiche.offre()).map(Offre::source).contains("saisi dans la fenêtre");
        assertThat(fiche.offre().flatMap(Offre::rapprochement)).map(Rapprochement::note).contains(72);
        assertThat(journal).isEmpty();
    }

    @Test
    void un_doublon_se_confirme_par_une_question_sans_repayer_l_analyse() {
        offre();
        List<Numerotee> demandes = new ArrayList<>();
        faux.repondre(JeuDEssai.FICHE);

        CasOffre.Soumission refusee = s.offres().soumettre(JeuDEssai.OFFRE + " Poste basé à Lyon.", "saisi dans la fenêtre",
                Optional.empty(), Optional.empty(), J, x -> {
                    demandes.add(x);
                    return false;
                }, (nom, feminin) -> Optional.empty(), journal::add);

        assertThat(refusee).isEqualTo(new CasOffre.DejaSuivie(demandes.getFirst(), false));
        assertThat(demandes).extracting(Numerotee::numero).containsExactly(1L);
        assertThat(s.candidatures()).hasSize(1);

        faux.repondre(JeuDEssai.FICHE);
        CasOffre.Soumission acceptee = s.offres().soumettre(JeuDEssai.OFFRE + " Poste basé à Lyon.", "saisi dans la fenêtre",
                Optional.empty(), Optional.empty(), J, x -> true, (nom, feminin) -> Optional.empty(), journal::add);

        assertThat(acceptee).isInstanceOfSatisfying(CasOffre.Ajoutee.class, a -> assertThat(a.numerotee().numero()).isEqualTo(2));
        assertThat(s.offres().soumettre(JeuDEssai.OFFRE, "saisi dans la fenêtre", Optional.empty(), Optional.empty(), J, x -> true,
                (nom, feminin) -> Optional.empty(), journal::add)).isEqualTo(new CasOffre.DejaSuivie(s.candidatures().getFirst(), true));
    }

    @Test
    void ce_que_l_analyse_ne_trouve_pas_se_demande_et_un_texte_court_est_refuse() {
        faux.repondre(new postulat.ia.AnalyseOffre.FicheOffre("", "", "", "", "", "", "", List.of(), List.of()));
        List<String> questions = new ArrayList<>();

        CasOffre.Soumission sm = s.offres().soumettre(JeuDEssai.OFFRE, "saisi dans la fenêtre", Optional.empty(), Optional.of("Stage"),
                J, x -> false, (nom, feminin) -> {
                    questions.add(nom + (feminin ? " (f)" : ""));
                    return Optional.of(" Initech ");
                }, journal::add);

        assertThat(questions).containsExactly("Entreprise (f)");
        assertThat(sm).isInstanceOfSatisfying(CasOffre.Ajoutee.class,
                a -> assertThat(a.numerotee().candidature().entreprise()).isEqualTo("Initech"));
        assertThatThrownBy(() -> CasOffre.exigerTexte("  Stage data.  ", "Collez le texte complet."))
                .isInstanceOf(Refus.class)
                .hasMessage("Texte trop court pour une offre (11 caractères) : rien n'a été analysé. Collez le texte complet.");
    }

    @Test
    void un_dossier_se_genere_se_corrige_se_verse_au_profil_et_se_valide() throws IOException {
        long n = offre();
        s.avancer(n, Etape.A_PREPARER, J);
        faux.repondre(JeuDEssai.CV).repondre(JeuDEssai.LETTRE).repondre(JeuDEssai.avis(5));
        List<String> progression = new ArrayList<>();

        CasDossier.Generation g = s.dossiers().generer(n, p -> progression.add("annonce : " + p.passesMax()), progression::add);

        assertThat(progression).startsWith("annonce : 3", "Première passe : CV ciblé…");
        assertThat(g.dossier().evaluation().noteGlobale()).isEqualTo(87);
        assertThat(s.apercu(n, "cv")).hasValueSatisfying(p -> assertThat(p).exists());
        assertThat(s.apercu(n, "lettre")).isPresent();
        assertThat(s.apercu(9, "cv")).isEmpty();
        assertThat(s.profilAChange(s.fiche(n).dossier().orElseThrow())).isFalse();

        s.dossiers().corriger(n, d -> d.corriger(5, "Python"));
        CasDossier.Versement v = s.dossiers().verser(n, 5, new CasDossier.Demande(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), true, Optional.empty(), Optional.of("courant"), Optional.empty(),
                false));

        assertThat(v.verse().id()).isEqualTo("comp-python");
        assertThat(s.profil()).hasValueSatisfying(p -> assertThat(p.fait("comp-python")).isPresent());
        assertThat(s.fiche(n).dossier().orElseThrow().dossier().phrase(5).origine())
                .isEqualTo(new Origine.Auteur(Optional.of("comp-python")));
        assertThat(s.profilAChange(s.fiche(n).dossier().orElseThrow())).isTrue();

        CasDossier.Validation validation = s.dossiers().valider(n);

        assertThat(validation.version()).isEqualTo(1);
        assertThat(validation.passeeAPrete()).isTrue();
        assertThat(validation.repertoire()).isDirectory();
        assertThat(s.fiche(n).versions()).hasSize(1);
        assertThat(s.fiche(n).numerotee().candidature().etape()).isEqualTo(Etape.PRETE);
    }

    @Test
    void sans_profil_utilisable_rien_ne_se_verse() throws IOException {
        Files.writeString(dossier.resolve("profil.yaml"), "identite:\n  nom: A\n\ttitre: B\n");

        assertThat(s.profil()).isEmpty();
        Files.delete(dossier.resolve("profil.yaml"));
        assertThat(s.profil()).isEmpty();
    }
}
