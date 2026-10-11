package postulat.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** UC-08 : corriger un dossier à la main, sans modèle, sans rompre ce que la génération garantissait. */
class CorrectionTest {

    static Assertion faits(String texte, String... ids) {
        return new Assertion(texte, Origine.Faits.de(ids));
    }

    static final Cv.Puce P1 = new Cv.Puce(faits("Conçu un tableau de bord Power BI", "exp-acme-r1"), 1);
    static final Cv.Puce P2 = new Cv.Puce(faits("Fiabilisé les données de ventes", "exp-acme-r2"), 3);
    static final Cv.Puce P3 = new Cv.Puce(faits("Automatisé un rapport mensuel", "exp-initech-r1"), 5);
    static final Cv.Puce RETIREE_ACME = new Cv.Puce(faits("Animé un atelier SQL", "exp-acme-r3"), 2);
    static final Cv.Puce RETIREE_INITECH = new Cv.Puce(faits("Documenté les flux", "exp-initech-r2"), 4);

    /**
     * 1 intitulé Acme · 2 précision · 3 et 4 puces · 5 intitulé Initech · 6 puce · 7 ligne de compétences ·
     * 8 Vous · 9 et 10 Moi · 11 Nous.
     */
    static Dossier dossier() {
        Cv cv = new Cv(Exemples.identite(), List.of(
                new Cv.Section("Expériences", List.of(
                        new Cv.Bloc(faits("Stagiaire data analyst — Acme", "exp-acme"), Optional.of(faits("2025", "exp-acme")),
                                List.of(P1, P2)),
                        new Cv.Bloc(faits("Assistant — Initech", "exp-initech"), Optional.empty(), List.of(P3)))),
                new Cv.Section("Compétences", List.of(new Cv.Ligne(faits("SQL", "comp-sql"))))));
        Lettre lettre = new Lettre(Exemples.identite(), "Globex", "Lyon, le 10 octobre 2026", "Candidature", List.of(
                new Lettre.Paragraphe(List.of(new Assertion("Globex recrute.", new Origine.Offre()))),
                new Lettre.Paragraphe(List.of(faits("J'ai conçu un tableau de bord.", "exp-acme-r1"),
                        faits("J'ai fiabilisé des données.", "exp-acme-r2"))),
                new Lettre.Paragraphe(List.of(faits("Rencontrons-nous.", "exp-acme")))));
        AvisRecruteur avis = new AvisRecruteur(
                Arrays.stream(Critere.values()).map(c -> new AvisRecruteur.NoteCritere(c, 4, "")).toList(), List.of(), List.of(), List.of());
        return new Dossier(cv, lettre, new Evaluation(Optional.empty(), avis, ReglesGeneration.PAR_DEFAUT),
                new Dossier.Rapport(1, List.of(new Dossier.Retiree(RETIREE_ACME, 0, 0), new Dossier.Retiree(RETIREE_INITECH, 0, 1)),
                        List.of(), List.of()));
    }

    static List<Cv.Puce> puces(Dossier d, int contenu) {
        return ((Cv.Bloc) d.cv().sections().get(0).contenus().get(contenu)).puces();
    }

    @Nested
    class Corriger {

        @Test
        void une_phrase_reecrite_devient_de_l_auteur_et_une_puce_garde_son_rang() {
            Dossier d = dossier().corriger(4, "  Fiabilisé chaque semaine les données de ventes ");

            assertThat(d.phrase(4)).isEqualTo(new Assertion("Fiabilisé chaque semaine les données de ventes", new Origine.Auteur()));
            assertThat(puces(d, 0).get(1).rang()).isEqualTo(3);
            assertThat(d.assertions()).hasSize(11);
            assertThat(d.assertionsDeLAuteur()).isEqualTo(1);
            assertThat(d.evaluation()).isEqualTo(dossier().evaluation());
            assertThat(d.rapport()).isEqualTo(dossier().rapport());
        }

        @Test
        void tout_se_corrige_intitule_precision_ligne_et_phrase_de_lettre() {
            Dossier d = dossier().corriger(1, "Data analyst — Acme").corriger(2, "2025, six mois").corriger(7, "SQL avancé")
                    .corriger(9, "J'ai bâti un tableau de bord suivi par toute l'équipe.");

            assertThat(List.of(1, 2, 7, 9)).allSatisfy(n -> assertThat(d.phrase(n).origine()).isEqualTo(new Origine.Auteur()));
            assertThat(d.lettre().paragraphes().get(1).phrases().get(0).texte()).startsWith("J'ai bâti");
            assertThat(d.assertionsDeLAuteur()).isEqualTo(4);
        }

        @Test
        void une_correction_vide_identique_ou_hors_du_dossier_est_refusee() {
            assertThatThrownBy(() -> dossier().corriger(3, "  ")).hasMessageContaining("ne peut pas être vide");
            assertThatThrownBy(() -> dossier().corriger(3, null)).hasMessageContaining("ne peut pas être vide");
            assertThatThrownBy(() -> dossier().corriger(3, "Conçu un tableau de bord Power BI")).hasMessageContaining("rien n'a changé");
            assertThatThrownBy(() -> dossier().corriger(12, "Texte")).hasMessageContaining("pas de phrase n° 12 : elles vont de 1 à 11");
            assertThatThrownBy(() -> dossier().corriger(0, "Texte")).hasMessageContaining("pas de phrase n° 0");
        }
    }

    /** UC-08 A2 : une phrase se retire ; la place libérée peut rendre une puce retirée. */
    @Nested
    class Supprimer {

        @Test
        void une_puce_ou_une_precision_se_retire_seule() {
            Dossier d = dossier().supprimer(3).supprimer(2);

            assertThat(puces(d, 0)).containsExactly(P2);
            assertThat(((Cv.Bloc) d.cv().sections().get(0).contenus().get(0)).precision()).isEmpty();
            assertThat(d.assertions()).hasSize(9);
        }

        @Test
        void sans_son_intitule_un_bloc_part_en_entier_avec_ses_puces_retirees() {
            Dossier d = dossier().supprimer(1);

            assertThat(d.cv().sections().get(0).contenus()).hasSize(1);
            assertThat(d.phrase(1).texte()).isEqualTo("Assistant — Initech");
            assertThat(d.rapport().retirees()).containsExactly(new Dossier.Retiree(RETIREE_INITECH, 0, 0));
        }

        @Test
        void une_ligne_se_retire_et_sa_section_reste_vide() {
            Dossier d = dossier().supprimer(7);

            assertThat(d.cv().sections().get(1).contenus()).isEmpty();
            assertThat(d.rapport().retirees()).hasSize(2);
        }

        @Test
        void une_phrase_de_lettre_se_retire_mais_jamais_la_derniere_d_une_partie() {
            assertThat(dossier().supprimer(9).lettre().paragraphes().get(1).phrases()).hasSize(1);
            assertThatThrownBy(() -> dossier().supprimer(8)).hasMessageContaining("seule phrase de sa partie")
                    .hasMessageContaining("Vous – Moi – Nous");
            assertThatThrownBy(() -> dossier().supprimer(20)).hasMessageContaining("pas de phrase n° 20");
        }
    }

    @Nested
    class Reintroduire {

        @Test
        void une_puce_retiree_revient_dans_son_bloc_a_son_rang_de_pertinence() {
            Dossier d = dossier().reintroduire(1);

            assertThat(puces(d, 0)).containsExactly(P1, RETIREE_ACME, P2);
            assertThat(d.rapport().retirees()).containsExactly(new Dossier.Retiree(RETIREE_INITECH, 0, 1));
            assertThat(puces(d.reintroduire(1), 1)).containsExactly(RETIREE_INITECH, P3);
        }

        @Test
        void une_puce_retiree_dont_le_bloc_est_parti_ne_revient_pas() {
            Dossier sansInitech = dossier().supprimer(5);

            assertThat(sansInitech.rapport().retirees()).containsExactly(new Dossier.Retiree(RETIREE_ACME, 0, 0));
            Dossier d = dossier();
            Dossier egare = new Dossier(d.cv(), d.lettre(), d.evaluation(),
                    new Dossier.Rapport(1, List.of(new Dossier.Retiree(RETIREE_ACME, 1, 0)), List.of(), List.of()));
            assertThatThrownBy(() -> egare.reintroduire(1)).hasMessageContaining("n'existe plus");
        }

        @Test
        void un_numero_de_puce_retiree_inconnu_est_refuse() {
            assertThatThrownBy(() -> dossier().reintroduire(3)).hasMessageContaining("vont de 1 à 2");
            assertThatThrownBy(() -> dossier().reintroduire(1).reintroduire(1).reintroduire(1))
                    .hasMessageContaining("Aucune puce n'a été retirée");
        }
    }

    @Nested
    class Places {

        @Test
        void chaque_assertion_a_sa_place_et_chaque_puce_son_bloc() {
            Cv cv = dossier().cv();

            assertThat(cv.place(0)).isEqualTo(new Cv.Place(0, 0, Cv.Role.INTITULE));
            assertThat(cv.place(1)).isEqualTo(new Cv.Place(0, 0, Cv.Role.PRECISION));
            assertThat(cv.place(5)).isEqualTo(new Cv.Place(0, 1, Cv.Role.PUCE));
            assertThat(cv.place(6)).isEqualTo(new Cv.Place(1, 0, Cv.Role.LIGNE));
            assertThat(cv.place(P3)).contains(new Cv.Place(0, 1, Cv.Role.PUCE));
            assertThat(cv.place(RETIREE_ACME)).isEmpty();
            assertThatThrownBy(() -> cv.place(7)).hasMessageContaining("n° 8");
            assertThatThrownBy(() -> cv.place(-1)).hasMessageContaining("n° 0");
        }
    }
}
