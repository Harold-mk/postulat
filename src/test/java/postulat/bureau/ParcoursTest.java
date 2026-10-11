package postulat.bureau;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static postulat.bureau.CapturesTest.sur;

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
import java.util.function.Predicate;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextInputControl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import postulat.JeuDEssai;
import postulat.ia.FauxModele;
import postulat.production.Competence;
import postulat.production.Dossier;
import postulat.production.Fait;
import postulat.production.Origine;
import postulat.production.Realisation;
import postulat.stockage.Donnees;
import postulat.suivi.Etape;

/**
 * Une offre conduite dans la fenêtre jusqu'au dossier validé, chaque boîte remplie par un pilote au lieu
 * du candidat : le code des boîtes — vérifications, refus, enregistrement — est celui qui s'exécute pour
 * lui. Sans affichage disponible, ces tests sont ignorés.
 */
class ParcoursTest {

    static final Clock HORLOGE = Clock.fixed(Instant.parse("2026-10-11T09:00:00Z"), ZoneOffset.UTC);
    static final LocalDate J = LocalDate.of(2026, 10, 11);

    @TempDir
    Path dossier;

    final FauxModele faux = new FauxModele();
    Session s;
    Fenetre f;

    /** Ce que le pilote répond à la prochaine boîte ; faux pour l'annuler. */
    Predicate<Formulaire> reponse = fo -> false;
    final List<String> formulaires = new ArrayList<>();
    final List<String> confirmations = new ArrayList<>();
    final List<Travail> travaux = new ArrayList<>();

    @BeforeEach
    void ouvrir() throws Exception {
        assumeTrue(CapturesTest.javafx(), "aucun affichage : parcours ignoré");
        Files.writeString(dossier.resolve("profil.yaml"), JeuDEssai.PROFIL);
        Files.writeString(dossier.resolve("postulat.properties"), "lettre.mots.min = 50\nlettre.mots.max = 150\n");
        s = new Session(new Donnees(dossier, HORLOGE), HORLOGE, r -> faux);
        Pilote.Actif.pilote = new Pilote() {
            @Override
            public boolean remplir(Formulaire formulaire) {
                formulaires.add(formulaire.titre());
                return reponse.test(formulaire);
            }

            @Override
            public boolean confirmer(String titre, String texte) {
                confirmations.add(titre);
                return true;
            }

            @Override
            public void travail(Travail travail) {
                travaux.add(travail);
            }
        };
        f = sur(() -> Fenetre.ouvrir(s));
    }

    @AfterEach
    void lacher() {
        Pilote.Actif.pilote = null;
    }

    static void ecrire(Formulaire fo, String champ, String valeur) {
        ((TextInputControl) fo.champ(champ)).setText(valeur);
    }

    @SuppressWarnings("unchecked")
    static <T> void choisir(Formulaire fo, String champ, Predicate<T> lequel) {
        ComboBox<T> c = (ComboBox<T>) fo.champ(champ);
        c.setValue(c.getItems().stream().filter(lequel).findFirst().orElseThrow());
    }

    /** Une boîte remplie, dans le fil de l'interface ; ce qu'elle lève remonte au test. */
    void faire(Runnable action) throws Exception {
        sur(() -> {
            action.run();
            return null;
        });
    }

    Dossier dossier1() {
        return s.fiche(1).dossier().orElseThrow().dossier();
    }

    /** L'offre soumise par la boîte « Nouvelle offre », poursuivie, son dossier généré : la candidature n° 1. */
    void jusquAuDossier() throws Exception {
        faux.repondre(JeuDEssai.FICHE).repondre(JeuDEssai.RAPPROCHEMENT);
        reponse = fo -> {
            ecrire(fo, "Texte", JeuDEssai.OFFRE);
            return true;
        };
        faire(f::nouvelleOffre);
        s.avancer(1, Etape.A_PREPARER, J);
        faux.repondre(JeuDEssai.CV).repondre(JeuDEssai.LETTRE).repondre(JeuDEssai.avis(5));
        faire(() -> VueDossier.generer(f, s, 1, false));
    }

    @Test
    void une_offre_collee_est_analysee_rapprochee_et_sa_page_s_ouvre() throws Exception {
        faux.repondre(JeuDEssai.FICHE).repondre(JeuDEssai.RAPPROCHEMENT);
        reponse = fo -> {
            ecrire(fo, "Texte", JeuDEssai.OFFRE);
            ecrire(fo, "Poste", " Stage data analyst ");
            return true;
        };

        faire(f::nouvelleOffre);

        assertThat(formulaires).containsExactly("Nouvelle offre");
        assertThat(travaux).singleElement().satisfies(t -> {
            assertThat(t.journal()).isEqualTo("Rapprochement avec le profil…");
            assertThat(t.etat()).isEqualTo("Candidature #1 Acme — Stage data analyst ajoutée à l'étape « repérée ».");
        });
        assertThat(s.fiche(1).offre().orElseThrow().rapprochement()).isPresent();
        assertThat(s.fiche(1).offre().orElseThrow().source()).isEqualTo("saisi dans la fenêtre");

        reponse = fo -> {
            ecrire(fo, "Texte", "Stage data, Paris.");
            return true;
        };
        assertThatThrownBy(() -> faire(f::nouvelleOffre)).hasRootCauseMessage("Texte trop court pour une offre (18 caractères) : "
                + "rien n'a été analysé. Collez le texte complet de l'annonce : l'entreprise, les missions, le profil recherché.");
        reponse = fo -> false;
        faire(f::nouvelleOffre);
        assertThat(s.candidatures()).hasSize(1);
    }

    @Test
    void un_dossier_se_genere_en_arriere_plan_et_se_corrige_phrase_par_phrase() throws Exception {
        jusquAuDossier();

        assertThat(confirmations).containsExactly("Générer le dossier");
        assertThat(travaux.getLast().journal()).startsWith("Jusqu'à 3 passe(s) de trois appels à claude-opus-5-5.\nPremière passe : CV ciblé…");
        assertThat(travaux.getLast().etat()).startsWith("Dossier généré : note globale 87/100, seuil atteint. Coût : 3 appels");

        reponse = fo -> {
            ecrire(fo, "Texte", "Automatisé des contrôles SQL quotidiens sur les ventes");
            return true;
        };
        faire(() -> VueDossier.reecrire(f, s, 1, 4, dossier1().phrase(4)));

        assertThat(dossier1().phrase(4)).satisfies(a -> {
            assertThat(a.texte()).isEqualTo("Automatisé des contrôles SQL quotidiens sur les ventes");
            assertThat(a.origine()).isEqualTo(new Origine.Auteur());
        });
        assertThat(s.fiche(1).dossier().orElseThrow().avisPerime()).isTrue();

        String retiree = dossier1().phrase(3).texte();
        faire(() -> VueDossier.supprimer(f, s, 1, 3, dossier1().phrase(3), VueDossier.Place.PUCE));
        assertThat(dossier1().assertions()).extracting(a -> a.texte()).doesNotContain(retiree);
        assertThat(confirmations).endsWith("Retirer la phrase n° 3");

        faux.repondre(JeuDEssai.avis(4));
        faire(() -> VueDossier.evaluer(f, s, 1));
        assertThat(travaux.getLast().etat()).startsWith("Note globale : 87 → ").contains("/100, seuil 75. Coût : 1 appel");
        assertThat(s.fiche(1).dossier().orElseThrow().avisPerime()).isFalse();
    }

    @Test
    void une_phrase_de_votre_main_se_verse_au_profil_apres_les_verifications_de_la_boite() throws Exception {
        jusquAuDossier();
        reponse = fo -> {
            ecrire(fo, "Texte", "Automatisé des contrôles SQL quotidiens sur les ventes");
            return true;
        };
        faire(() -> VueDossier.reecrire(f, s, 1, 4, dossier1().phrase(4)));

        reponse = fo -> true;
        assertThatThrownBy(() -> faire(() -> Verser.realisation(f, s, 1, dossier1(), 4)))
                .hasRootCauseMessage("Il manque l'objectif : le but que poursuivait cette réalisation.");
        reponse = fo -> {
            ecrire(fo, "Objectif", "Fiabiliser les ventes");
            return true;
        };
        assertThatThrownBy(() -> faire(() -> Verser.realisation(f, s, 1, dossier1(), 4)))
                .rootCause().hasMessageStartingWith("Cette phrase semble redire le fait « exp-acme-r2 » : Contrôles SQL quotidiens.");
        reponse = fo -> {
            ecrire(fo, "Objectif", "Fiabiliser les ventes");
            ecrire(fo, "Résultat", "zéro écart en fin de mois");
            ((CheckBox) fo.champ("Créer le fait même s'il semble redire un fait existant")).setSelected(true);
            return true;
        };
        faire(() -> Verser.realisation(f, s, 1, dossier1(), 4));

        assertThat(s.profil().orElseThrow().fait("exp-acme-r3")).hasValueSatisfying(x -> assertThat(x)
                .isEqualTo(new Realisation("exp-acme-r3", "Fiabiliser les ventes", "Automatisé des contrôles SQL quotidiens sur les ventes",
                        "zéro écart en fin de mois", List.of(), "")));
        assertThat(dossier1().phrase(4).origine()).isEqualTo(new Origine.Auteur(Optional.of("exp-acme-r3")));
        assertThat(fichiers(dossier.resolve("profils"))).isEqualTo(1);

        reponse = fo -> {
            ecrire(fo, "Texte", "SQL avancé, fenêtrage et index");
            return true;
        };
        faire(() -> VueDossier.reecrire(f, s, 1, 5, dossier1().phrase(5)));
        reponse = fo -> {
            choisir(fo, "Fait", (Fait x) -> x.id().equals("comp-sql"));
            assertThat(fo.champ("Niveau").isVisible()).isTrue();
            assertThat(fo.champ("Objectif").isVisible()).isFalse();
            return true;
        };
        assertThatThrownBy(() -> faire(() -> Verser.enrichir(f, s, 1, dossier1(), 5)))
                .hasRootCauseMessage("Dites ce que la phrase apporte au fait « comp-sql » : remplissez au moins un champ.");
        reponse = fo -> {
            choisir(fo, "Fait", (Fait x) -> x.id().equals("comp-sql"));
            ecrire(fo, "Niveau", "avancé");
            return true;
        };
        faire(() -> Verser.enrichir(f, s, 1, dossier1(), 5));

        assertThat(s.profil().orElseThrow().fait("comp-sql")).contains(new Competence("comp-sql", "SQL", "avancé", ""));
        assertThat(dossier1().phrase(5).origine()).isEqualTo(new Origine.Auteur(Optional.of("comp-sql")));
    }

    @Test
    void la_validation_fige_le_dossier_et_l_offre_avec_lui() throws Exception {
        jusquAuDossier();
        faux.repondre(JeuDEssai.FICHE).repondre(JeuDEssai.RAPPROCHEMENT);
        faire(() -> VueOffre.analyser(f, s, 1, true));
        assertThat(travaux.getLast().etat()).isEqualTo("Adéquation : 72/100, 1 lacune(s).");

        faire(() -> VueDossier.valider(f, s, s.fiche(1).numerotee()));

        assertThat(confirmations).endsWith("Valider le dossier");
        assertThat(s.fiche(1).versions()).singleElement().satisfies(v -> assertThat(Path.of(v.repertoire())).isDirectory());
        assertThat(s.fiche(1).numerotee().candidature().etape()).isEqualTo(Etape.PRETE);

        faire(() -> VueOffre.analyser(f, s, 1, false));
        assertThat(travaux.getLast().etat()).contains("son offre, son analyse et son rapprochement sont figés avec lui (CA-6.8)");
        assertThat(sur(() -> VueDossier.version(f, s, 1, 1))).isNotNull();
        assertThatThrownBy(() -> sur(() -> VueDossier.version(f, s, 1, 2))).hasRootCauseMessage(
                "La candidature n° 1 n'a pas de version 2.");
    }

    @Test
    void une_generation_qui_echoue_dit_ce_qu_elle_a_coute_et_n_enregistre_rien() throws Exception {
        faux.repondre(JeuDEssai.FICHE).repondre(JeuDEssai.RAPPROCHEMENT);
        reponse = fo -> {
            ecrire(fo, "Texte", JeuDEssai.OFFRE);
            return true;
        };
        faire(f::nouvelleOffre);
        s.avancer(1, Etape.A_PREPARER, J);
        var fautif = JeuDEssai.cv("Conçu un tableau de bord Tableau pour 40 commerciaux");
        faux.repondre(fautif).repondre(fautif);

        faire(() -> VueDossier.generer(f, s, 1, false));

        assertThat(travaux.getLast().etat()).endsWith(System.lineSeparator() + "Coût engagé malgré l'échec : 2 appels, 0,0030 $.");
        assertThat(s.fiche(1).dossier()).isEmpty();
    }

    @Test
    void sans_profil_rien_ne_se_verse() throws Exception {
        jusquAuDossier();
        Files.delete(dossier.resolve("profil.yaml"));

        assertThatThrownBy(() -> faire(() -> Verser.competence(f, s, 1, dossier1(), 5)))
                .hasRootCauseMessage("Le profil est absent, ou il a des problèmes : corrigez-le avant d'y verser une phrase.");
        assertThat(formulaires).doesNotContain("Verser la phrase n° 5 comme compétence");
    }

    @Test
    void les_pages_se_construisent_a_chaque_etape() throws Exception {
        faire(() -> f.ouvrirDossier(1));
        s.ajouter("Initech", "Data analyst", Etape.A_PREPARER, J, false);
        assertThat(sur(() -> VueDossier.de(f, s, 1))).isNotNull();
        assertThat(sur(() -> VueOffre.de(f, s, 1))).isNotNull();
    }

    static long fichiers(Path repertoire) throws IOException {
        try (var l = Files.list(repertoire)) {
            return l.count();
        }
    }
}
