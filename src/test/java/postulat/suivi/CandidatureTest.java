package postulat.suivi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CandidatureTest {

    static final LocalDate J0 = LocalDate.of(2026, 10, 1);
    static final LocalDate AUJOURDHUI = LocalDate.of(2026, 10, 31);

    static Candidature repereeLe(LocalDate date) {
        return Candidature.creer("Acme", "Stage data", Etape.REPEREE, date, AUJOURDHUI);
    }

    static Candidature envoyeeLe(LocalDate date) {
        return Candidature.creer("Acme", "Stage data", Etape.ENVOYEE, date, AUJOURDHUI);
    }

    @Nested
    class Creation {

        @Test
        void une_candidature_externe_entre_a_l_etape_voulue_et_a_sa_date_reelle() {
            Candidature c = envoyeeLe(J0);

            assertThat(c.etape()).isEqualTo(Etape.ENVOYEE);
            assertThat(c.journal()).containsExactly(new Evenement.Creation(J0, Etape.ENVOYEE));
        }

        @Test
        void une_date_future_est_refusee() {
            assertThatThrownBy(() -> envoyeeLe(AUJOURDHUI.plusDays(1)))
                    .isInstanceOf(RefusMetier.class)
                    .hasMessageContaining("futur");
        }

        @Test
        void l_entreprise_et_le_poste_sont_obligatoires() {
            assertThatThrownBy(() -> Candidature.creer(" ", "Stage", Etape.REPEREE, J0, AUJOURDHUI))
                    .isInstanceOf(RefusMetier.class)
                    .hasMessageContaining("entreprise");
            assertThatThrownBy(() -> Candidature.creer("Acme", "", Etape.REPEREE, J0, AUJOURDHUI))
                    .isInstanceOf(RefusMetier.class)
                    .hasMessageContaining("poste");
        }

        @Test
        void un_journal_sans_creation_est_impossible() {
            List<Evenement> journal = List.of(new Evenement.Note(J0, "texte"));
            assertThatThrownBy(() -> new Candidature(UUID.randomUUID(), "Acme", "Stage", journal))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Transitions {

        @Test
        void le_parcours_nominal_jusqu_a_l_acceptation() {
            Candidature c = repereeLe(J0)
                    .avancer(Etape.A_PREPARER, J0, AUJOURDHUI)
                    .avancer(Etape.PRETE, J0.plusDays(1), AUJOURDHUI)
                    .avancer(Etape.ENVOYEE, J0.plusDays(2), AUJOURDHUI)
                    .avancer(Etape.ENTRETIEN, J0.plusDays(9), AUJOURDHUI)
                    .avancer(Etape.OFFRE_RECUE, J0.plusDays(20), AUJOURDHUI)
                    .avancer(Etape.ACCEPTEE, J0.plusDays(21), AUJOURDHUI);

            assertThat(c.etape()).isEqualTo(Etape.ACCEPTEE);
            assertThat(c.journal()).hasSize(7);
        }

        @Test
        void une_transition_non_declaree_est_refusee_avec_la_liste_des_possibles() {
            Candidature c = repereeLe(J0);

            assertThatThrownBy(() -> c.avancer(Etape.ENVOYEE, J0, AUJOURDHUI))
                    .isInstanceOf(RefusMetier.class)
                    .hasMessageContaining("non autorisée")
                    .hasMessageContaining("à_préparer")
                    .hasMessageContaining("écartée");
        }

        @Test
        void une_operation_refusee_ne_modifie_rien() {
            Candidature c = repereeLe(J0);
            try {
                c.avancer(Etape.ENVOYEE, J0, AUJOURDHUI);
            } catch (RefusMetier attendu) {
                // la candidature d'origine est immuable
            }
            assertThat(c.journal()).hasSize(1);
            assertThat(c.etape()).isEqualTo(Etape.REPEREE);
        }

        @Test
        void chaque_operation_renvoie_une_nouvelle_candidature_sans_toucher_l_ancienne() {
            Candidature avant = repereeLe(J0);
            Candidature apres = avant.avancer(Etape.A_PREPARER, J0, AUJOURDHUI);

            assertThat(avant.etape()).isEqualTo(Etape.REPEREE);
            assertThat(apres.etape()).isEqualTo(Etape.A_PREPARER);
            assertThat(apres.id()).isEqualTo(avant.id());
        }

        @Test
        void le_journal_ne_peut_pas_etre_modifie_de_l_exterieur() {
            Candidature c = repereeLe(J0);
            assertThatThrownBy(() -> c.journal().add(new Evenement.Note(J0, "x")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void une_date_anterieure_au_dernier_evenement_est_refusee() {
            Candidature c = envoyeeLe(J0.plusDays(5));

            assertThatThrownBy(() -> c.avancer(Etape.REFUSEE, J0, AUJOURDHUI))
                    .isInstanceOf(RefusMetier.class)
                    .hasMessageContaining("antérieure");
        }

        @Test
        void le_meme_jour_que_le_dernier_evenement_est_accepte() {
            Candidature c = envoyeeLe(J0).avancer(Etape.REFUSEE, J0, AUJOURDHUI);
            assertThat(c.etape()).isEqualTo(Etape.REFUSEE);
        }

        @Test
        void une_transition_datee_dans_le_futur_est_refusee() {
            Candidature c = envoyeeLe(J0);
            assertThatThrownBy(() -> c.avancer(Etape.REFUSEE, AUJOURDHUI.plusDays(1), AUJOURDHUI))
                    .isInstanceOf(RefusMetier.class);
        }
    }

    @Nested
    class Ecartement {

        @Test
        void ecarter_une_offre_reperee_garde_son_motif_dans_le_journal() {
            Candidature c = repereeLe(J0).ecarter("  Trop loin de Lyon ", J0.plusDays(2), AUJOURDHUI);

            assertThat(c.etape()).isEqualTo(Etape.ECARTEE);
            assertThat(c.journal()).last().isEqualTo(new Evenement.Ecartement(J0.plusDays(2), "Trop loin de Lyon"));
            assertThat(c.dateEntreeEtape()).isEqualTo(J0.plusDays(2));
        }

        @Test
        void une_offre_a_preparer_peut_encore_etre_ecartee() {
            Candidature c = repereeLe(J0).avancer(Etape.A_PREPARER, J0, AUJOURDHUI).ecarter("Poste pourvu", J0, AUJOURDHUI);
            assertThat(c.etape()).isEqualTo(Etape.ECARTEE);
        }

        @Test
        void ecarter_sans_motif_est_refuse() {
            assertThatThrownBy(() -> repereeLe(J0).ecarter(" ", J0, AUJOURDHUI))
                    .isInstanceOf(RefusMetier.class)
                    .hasMessageContaining("motif");
        }

        @Test
        void avancer_vers_ecartee_est_refuse_parce_qu_il_faut_un_motif() {
            assertThatThrownBy(() -> repereeLe(J0).avancer(Etape.ECARTEE, J0, AUJOURDHUI))
                    .isInstanceOf(RefusMetier.class)
                    .hasMessageContaining("motif");
        }

        @Test
        void une_candidature_envoyee_ne_s_ecarte_pas() {
            assertThatThrownBy(() -> envoyeeLe(J0).ecarter("Finalement non", J0, AUJOURDHUI))
                    .isInstanceOf(RefusMetier.class)
                    .hasMessageContaining("envoyée");
        }

        @Test
        void on_n_entre_pas_dans_le_suivi_deja_ecarte() {
            assertThatThrownBy(() -> Candidature.creer("Acme", "Stage", Etape.ECARTEE, J0, AUJOURDHUI))
                    .isInstanceOf(RefusMetier.class);
        }

        @Test
        void une_offre_ecartee_se_rouvre_explicitement() {
            Candidature c = repereeLe(J0).ecarter("Hésitation", J0, AUJOURDHUI).reouvrir(Etape.REPEREE, J0.plusDays(1), AUJOURDHUI);
            assertThat(c.etape()).isEqualTo(Etape.REPEREE);
        }
    }

    @Nested
    class EtapesTerminales {

        Candidature refusee = envoyeeLe(J0).avancer(Etape.REFUSEE, J0.plusDays(3), AUJOURDHUI);

        @Test
        void aucune_transition_ordinaire_n_est_permise() {
            assertThatThrownBy(() -> refusee.avancer(Etape.ENTRETIEN, J0.plusDays(4), AUJOURDHUI))
                    .isInstanceOf(RefusMetier.class)
                    .hasMessageContaining("terminale");
        }

        @Test
        void une_note_reste_permise() {
            Candidature c = refusee.noter("Retour : profil trop junior", J0.plusDays(4), AUJOURDHUI);
            assertThat(c.etape()).isEqualTo(Etape.REFUSEE);
            assertThat(c.journal()).last().isInstanceOf(Evenement.Note.class);
        }

        @Test
        void la_reouverture_explicite_garde_l_historique_intact() {
            Candidature c = refusee.reouvrir(Etape.ENTRETIEN, J0.plusDays(10), AUJOURDHUI);

            assertThat(c.etape()).isEqualTo(Etape.ENTRETIEN);
            assertThat(c.journal()).startsWith(refusee.journal().toArray(Evenement[]::new));
            assertThat(c.journal()).last().isEqualTo(new Evenement.Reouverture(J0.plusDays(10), Etape.ENTRETIEN));
        }

        @Test
        void on_ne_rouvre_pas_une_candidature_ouverte() {
            assertThatThrownBy(() -> envoyeeLe(J0).reouvrir(Etape.ENTRETIEN, J0, AUJOURDHUI))
                    .isInstanceOf(RefusMetier.class);
        }

        @Test
        void on_ne_rouvre_pas_vers_une_autre_terminale() {
            assertThatThrownBy(() -> refusee.reouvrir(Etape.ACCEPTEE, J0.plusDays(4), AUJOURDHUI))
                    .isInstanceOf(RefusMetier.class);
        }
    }

    @Nested
    class EntretiensEtNotes {

        @Test
        void plusieurs_entretiens_sont_des_evenements_distincts_sans_changer_l_etape() {
            Candidature c = envoyeeLe(J0)
                    .avancer(Etape.ENTRETIEN, J0.plusDays(5), AUJOURDHUI)
                    .noterEntretien(TypeEntretien.RH, J0.plusDays(5), AUJOURDHUI)
                    .noterEntretien(TypeEntretien.TECHNIQUE, J0.plusDays(8), AUJOURDHUI)
                    .noterEntretien(TypeEntretien.TECHNIQUE, J0.plusDays(12), AUJOURDHUI);

            assertThat(c.etape()).isEqualTo(Etape.ENTRETIEN);
            assertThat(c.journal()).filteredOn(e -> e instanceof Evenement.EntretienPasse).hasSize(3);
        }

        @Test
        void un_entretien_s_enregistre_a_l_etape_entretien_seulement() {
            assertThatThrownBy(() -> envoyeeLe(J0).noterEntretien(TypeEntretien.RH, J0, AUJOURDHUI))
                    .isInstanceOf(RefusMetier.class);
        }

        @Test
        void une_note_vide_est_refusee() {
            assertThatThrownBy(() -> envoyeeLe(J0).noter("  ", J0, AUJOURDHUI))
                    .isInstanceOf(RefusMetier.class);
        }
    }

    @Nested
    class Relances {

        @Test
        void on_ne_relance_qu_une_candidature_envoyee() {
            assertThatThrownBy(() -> repereeLe(J0).relancer(J0, AUJOURDHUI))
                    .isInstanceOf(RefusMetier.class);
        }

        @Test
        void les_relances_sont_comptees_depuis_la_derniere_entree_a_envoyee() {
            Candidature c = envoyeeLe(J0)
                    .relancer(J0.plusDays(10), AUJOURDHUI)
                    .avancer(Etape.SANS_SUITE, J0.plusDays(25), AUJOURDHUI)
                    .reouvrir(Etape.ENVOYEE, J0.plusDays(27), AUJOURDHUI);

            assertThat(c.dateEnvoi()).contains(J0.plusDays(27));
            assertThat(c.relancesDepuisEnvoi()).isEmpty();
        }

        @Test
        void hors_de_l_etape_envoyee_il_n_y_a_ni_date_d_envoi_ni_relances() {
            Candidature c = envoyeeLe(J0).avancer(Etape.ENTRETIEN, J0.plusDays(3), AUJOURDHUI);
            assertThat(c.dateEnvoi()).isEmpty();
            assertThat(c.relancesDepuisEnvoi()).isEmpty();
        }
    }

    @Nested
    class EcheancesSaisies {

        Candidature enEntretien = envoyeeLe(J0).avancer(Etape.ENTRETIEN, J0.plusDays(3), AUJOURDHUI);

        @Test
        void un_entretien_planifie_peut_etre_a_venir_et_ne_change_pas_l_etape() {
            LocalDate le = AUJOURDHUI.plusDays(10);
            Candidature c = enEntretien.planifierEntretien(TypeEntretien.FINAL, le, J0.plusDays(4), AUJOURDHUI);

            assertThat(c.etape()).isEqualTo(Etape.ENTRETIEN);
            assertThat(c.journal()).last()
                    .isEqualTo(new Evenement.EntretienPlanifie(J0.plusDays(4), TypeEntretien.FINAL, le));
        }

        @Test
        void un_entretien_se_planifie_a_l_etape_entretien_seulement() {
            assertThatThrownBy(() -> envoyeeLe(J0).planifierEntretien(TypeEntretien.RH, J0.plusDays(9), J0, AUJOURDHUI))
                    .isInstanceOf(RefusMetier.class)
                    .hasMessageContaining("entretien");
        }

        @Test
        void un_entretien_ne_se_planifie_pas_dans_le_passe_de_sa_prise_de_rendez_vous() {
            assertThatThrownBy(() -> enEntretien.planifierEntretien(
                            TypeEntretien.RH, J0.plusDays(3), J0.plusDays(5), AUJOURDHUI))
                    .isInstanceOf(RefusMetier.class);
        }

        @Test
        void la_prise_de_rendez_vous_elle_meme_ne_peut_pas_etre_future() {
            assertThatThrownBy(() -> enEntretien.planifierEntretien(
                            TypeEntretien.RH, AUJOURDHUI.plusDays(9), AUJOURDHUI.plusDays(1), AUJOURDHUI))
                    .isInstanceOf(RefusMetier.class)
                    .hasMessageContaining("futur");
        }

        @Test
        void un_retour_promis_porte_sur_une_date_posterieure_a_la_promesse() {
            Candidature c = envoyeeLe(J0).noterRetourPromis(AUJOURDHUI.plusDays(5), J0.plusDays(2), AUJOURDHUI);
            assertThat(c.journal()).last()
                    .isEqualTo(new Evenement.RetourPromis(J0.plusDays(2), AUJOURDHUI.plusDays(5)));

            assertThatThrownBy(() -> envoyeeLe(J0).noterRetourPromis(J0, J0.plusDays(2), AUJOURDHUI))
                    .isInstanceOf(RefusMetier.class);
        }

        @Test
        void une_terminale_refuse_retour_promis_et_interaction() {
            Candidature refusee = envoyeeLe(J0).avancer(Etape.REFUSEE, J0.plusDays(3), AUJOURDHUI);

            assertThatThrownBy(() -> refusee.noterRetourPromis(AUJOURDHUI, J0.plusDays(4), AUJOURDHUI))
                    .isInstanceOf(RefusMetier.class)
                    .hasMessageContaining("terminale");
            assertThatThrownBy(() -> refusee.interagir(Canal.APPEL, "", J0.plusDays(4), AUJOURDHUI))
                    .isInstanceOf(RefusMetier.class)
                    .hasMessageContaining("terminale");
        }
    }

    @Nested
    class ContactsEtInteractions {

        @Test
        void une_interaction_est_datee_typee_et_porte_des_notes() {
            Candidature c = envoyeeLe(J0).interagir(Canal.APPEL, "  Appel de la RH  ", J0.plusDays(2), AUJOURDHUI);

            assertThat(c.journal()).last()
                    .isEqualTo(new Evenement.Interaction(J0.plusDays(2), Canal.APPEL, "Appel de la RH"));
            assertThat(c.etape()).isEqualTo(Etape.ENVOYEE);
        }

        @Test
        void une_interaction_sans_notes_est_permise() {
            Candidature c = envoyeeLe(J0).interagir(Canal.COURRIEL, "", J0, AUJOURDHUI);
            assertThat(c.journal()).hasSize(2);
        }

        @Test
        void une_interaction_future_est_refusee() {
            assertThatThrownBy(() -> envoyeeLe(J0).interagir(Canal.APPEL, "x", AUJOURDHUI.plusDays(1), AUJOURDHUI))
                    .isInstanceOf(RefusMetier.class);
        }

        @Test
        void un_contact_exige_un_nom_et_le_reste_est_facultatif() {
            assertThat(new Contact(" Marie Curie ", "", "")).isEqualTo(new Contact("Marie Curie", "", ""));
            assertThatThrownBy(() -> new Contact(" ", "RH", "")).isInstanceOf(RefusMetier.class);
        }
    }

    @Nested
    class Reperes {

        @Test
        void la_date_d_entree_dans_l_etape_suit_la_derniere_transition() {
            Candidature c = repereeLe(J0)
                    .avancer(Etape.A_PREPARER, J0.plusDays(2), AUJOURDHUI)
                    .noter("note", J0.plusDays(4), AUJOURDHUI);

            assertThat(c.dateCreation()).isEqualTo(J0);
            assertThat(c.dateEntreeEtape()).isEqualTo(J0.plusDays(2));
            assertThat(repereeLe(J0).dateEntreeEtape()).isEqualTo(J0);
        }

        @Test
        void une_meme_offre_se_reconnait_malgre_casse_accents_et_ponctuation() {
            Candidature c = Candidature.creer("Société Générale", "Stage – Data", Etape.REPEREE, J0, AUJOURDHUI);

            assertThat(c.memeOffre("societe generale", "stage data")).isTrue();
            assertThat(c.memeOffre("Société Générale", "Stage Finance")).isFalse();
            assertThat(c.memeOffre("Générale", "Stage – Data")).isFalse();
        }
    }
}
