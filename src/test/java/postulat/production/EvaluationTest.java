package postulat.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static postulat.production.Exigence.obligatoire;
import static postulat.production.Exigence.souhaitee;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EvaluationTest {

    /** Un avis où chaque critère a la même note. */
    static AvisRecruteur avis(int note, String... corrections) {
        return new AvisRecruteur(
                Arrays.stream(Critere.values()).map(c -> new AvisRecruteur.NoteCritere(c, note, "")).toList(),
                List.of("Des preuves chiffrées"), List.of("Trop long"), List.of(corrections));
    }

    static NoteAts ats(int note) {
        return new NoteAts(note, true, List.of(), List.of());
    }

    @Nested
    class LaGrille {

        @Test
        void la_note_du_recruteur_est_la_somme_de_la_grille_rapportee_a_100() {
            assertThat(avis(5).note()).isEqualTo(100);
            assertThat(avis(0).note()).isZero();
            List<AvisRecruteur.NoteCritere> notes = List.of(
                    new AvisRecruteur.NoteCritere(Critere.ADEQUATION, 5, ""), new AvisRecruteur.NoteCritere(Critere.PREUVES, 4, ""),
                    new AvisRecruteur.NoteCritere(Critere.CIBLAGE, 3, ""), new AvisRecruteur.NoteCritere(Critere.CLARTE, 4, ""),
                    new AvisRecruteur.NoteCritere(Critere.LANGUE, 5, ""));
            assertThat(new AvisRecruteur(notes, List.of(), List.of(), List.of()).note()).isEqualTo(84);
        }

        @Test
        void chaque_critere_se_note_une_fois_et_aucun_ne_manque() {
            List<AvisRecruteur.NoteCritere> sansLangue = Arrays.stream(Critere.values()).filter(c -> c != Critere.LANGUE)
                    .map(c -> new AvisRecruteur.NoteCritere(c, 3, "")).toList();
            assertThatThrownBy(() -> new AvisRecruteur(sansLangue, List.of(), List.of(), List.of()))
                    .hasMessageContaining("LANGUE");

            List<AvisRecruteur.NoteCritere> double_ = new java.util.ArrayList<>(avis(3).criteres());
            double_.add(new AvisRecruteur.NoteCritere(Critere.CLARTE, 1, ""));
            assertThatThrownBy(() -> new AvisRecruteur(double_, List.of(), List.of(), List.of()))
                    .hasMessageContaining("Clarté").hasMessageContaining("deux fois");
        }

        @Test
        void un_critere_se_note_de_0_a_5() {
            assertThatThrownBy(() -> new AvisRecruteur.NoteCritere(Critere.PREUVES, 6, ""))
                    .hasMessageContaining("Preuves = 6");
            assertThatThrownBy(() -> new AvisRecruteur.NoteCritere(Critere.PREUVES, -1, "")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void chaque_critere_dit_ce_qu_il_mesure() {
            assertThat(Critere.values()).allSatisfy(c -> {
                assertThat(c.libelle()).isNotBlank();
                assertThat(c.question()).endsWith("?");
            });
        }
    }

    /** RG-07 : aucune correction proposée n'implique d'ajouter un fait absent du profil. */
    @Nested
    class LesCorrections {

        @Test
        void une_correction_qui_nomme_une_lacune_est_ecartee_et_gardee_pour_memoire() {
            AvisRecruteur a = avis(3, "Mentionnez votre pratique de Kubernetes", "Chiffrez le résultat du tableau de bord");
            List<Exigence> lacunes = List.of(obligatoire("Kubernetes"));

            assertThat(a.correctionsSur(lacunes)).containsExactly("Mentionnez votre pratique de Kubernetes");
            AvisRecruteur filtre = a.sansCorrectionsSur(lacunes);
            assertThat(filtre.corrections()).containsExactly("Chiffrez le résultat du tableau de bord");
            assertThat(filtre.criteres()).isEqualTo(a.criteres());
            assertThat(filtre.forces()).isEqualTo(a.forces());
        }

        @Test
        void sans_lacune_tout_est_garde() {
            AvisRecruteur a = avis(3, "Resserrez la lettre");
            assertThat(a.sansCorrectionsSur(List.of())).isEqualTo(a);
        }
    }

    /** CA-5.3 : une note globale pondérée, comparée à un seuil configurable. */
    @Nested
    class LaNoteGlobale {

        @Test
        void elle_pondere_le_filtre_ats_et_le_recruteur() {
            ReglesGeneration regles = new ReglesGeneration(75, 40, 2, 250, 350);
            Evaluation e = new Evaluation(Optional.of(ats(50)), avis(5), regles);

            assertThat(e.noteGlobale()).isEqualTo(80);
            assertThat(e.seuilAtteint()).isTrue();
        }

        @Test
        void sans_exigence_seule_compte_la_note_du_recruteur() {
            Evaluation e = new Evaluation(Optional.empty(), avis(3), ReglesGeneration.PAR_DEFAUT);

            assertThat(e.noteGlobale()).isEqualTo(60);
            assertThat(e.seuilAtteint()).isFalse();
        }

        @Test
        void le_seuil_est_atteint_a_egalite() {
            assertThat(new Evaluation(Optional.empty(), avis(3), new ReglesGeneration(60, 40, 0, 250, 350)).seuilAtteint()).isTrue();
        }

        @Test
        void des_reglages_incoherents_sont_refuses() {
            assertThatThrownBy(() -> new ReglesGeneration(101, 40, 2, 250, 350)).hasMessageContaining("seuil");
            assertThatThrownBy(() -> new ReglesGeneration(75, -1, 2, 250, 350)).hasMessageContaining("poids");
            assertThatThrownBy(() -> new ReglesGeneration(75, 40, 6, 250, 350)).hasMessageContaining("révisions");
            assertThatThrownBy(() -> new ReglesGeneration(75, 40, 2, 350, 250)).hasMessageContaining("fourchette");
            assertThatThrownBy(() -> new ReglesGeneration(75, 40, 2, 10, 350)).hasMessageContaining("fourchette");
        }
    }

    @Nested
    class LeFiltreAts {

        @Test
        void seules_les_absentes_couvertes_sont_a_reprendre() {
            NoteAts note = Ats.noter("SQL avancé", List.of(obligatoire("SQL"), obligatoire("Power BI"), souhaitee("Kubernetes")))
                    .orElseThrow();

            assertThat(Ats.aReprendre(note, List.of(souhaitee("kubernetes")))).containsExactly(obligatoire("Power BI"));
        }
    }

    @Nested
    class LeDossier {

        @Test
        void il_compte_ses_phrases_et_celles_de_l_auteur() {
            Cv cv = Apercu.de(Exemples.complet());
            Lettre lettre = new Lettre(Exemples.identite(), "Acme", "Lyon, le 10 octobre 2026", "Candidature", List.of(
                    new Lettre.Paragraphe(List.of(new Assertion("Votre équipe data m'attire.", new Origine.Offre()))),
                    new Lettre.Paragraphe(List.of(new Assertion("J'ai conçu un tableau de bord.", Origine.Faits.de("exp-acme-dashboard")),
                            new Assertion("Je parle couramment espagnol.", new Origine.Auteur())))));
            Dossier d = new Dossier(cv, lettre, new Evaluation(Optional.empty(), avis(4), ReglesGeneration.PAR_DEFAUT),
                    new Dossier.Rapport(1, List.of(), List.of(), List.of()));

            assertThat(d.assertions()).hasSize(cv.assertions().size() + 3);
            assertThat(d.assertions().get(cv.assertions().size()).texte()).isEqualTo("Votre équipe data m'attire.");
            assertThat(d.assertionsDeLAuteur()).isEqualTo(1);
            assertThat(lettre.mots()).isEqualTo(14);
        }
    }
}
