package postulat.ia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static postulat.ia.FausseApi.erreur;
import static postulat.ia.FausseApi.message;

import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import postulat.ia.ErreurModele.Nature;

class ModeleAnthropicTest {

    /** La forme de réponse demandée dans ces essais. */
    record Salut(String message, int nombre) {}

    static final String OPUS = "claude-opus-5-5";
    static final String HAIKU = "claude-haiku-5-5";

    FausseApi api;
    ModeleAnthropic modele;

    @BeforeEach
    void demarrer() throws IOException {
        api = new FausseApi();
        modele = new ModeleAnthropic(api.client(), HAIKU, OPUS);
    }

    @AfterEach
    void arreter() {
        api.close();
    }

    static Demande demande(Gamme gamme) {
        return Demande.horsCandidature("essai", gamme, "Tu réponds à un essai de connexion.", "Dis bonjour.");
    }

    Reponse<Salut> demander(Gamme gamme) {
        return modele.demander(demande(gamme), Salut.class);
    }

    ErreurModele echec(Gamme gamme) {
        try {
            demander(gamme);
        } catch (ErreurModele e) {
            return e;
        }
        throw new AssertionError("un échec était attendu");
    }

    @Nested
    class Reussite {

        @Test
        void la_reponse_arrive_dans_la_forme_demandee_avec_son_json_et_son_cout() {
            api.repondre(200, message(OPUS, "{\"message\":\"Prêt\",\"nombre\":3}", "end_turn", 1200, 300, 800, 0));

            Reponse<Salut> r = demander(Gamme.CAPABLE);

            assertThat(r.valeur()).isEqualTo(new Salut("Prêt", 3));
            assertThat(r.json()).isEqualTo("{\"message\":\"Prêt\",\"nombre\":3}");
            Consommation c = r.consommation();
            assertThat(c.modele()).isEqualTo(OPUS);
            assertThat(c).extracting(Consommation::jetonsEntree, Consommation::jetonsSortie,
                    Consommation::jetonsCacheLus, Consommation::jetonsCacheEcrits).containsExactly(1200L, 300L, 800L, 0L);
            // 1200 × 4 + 300 × 20 + 800 × 0,20 = 10 960 millionièmes de dollar
            assertThat(c.coutMicroDollars()).isEqualTo(10_960);
            assertThat(c.coutDollars()).hasToString("0.010960");
        }

        @Test
        void la_gamme_capable_demande_effort_haut_cache_et_repli_sur_refus() {
            api.repondre(200, message(OPUS, "{\"message\":\"Prêt\",\"nombre\":1}", "end_turn", 10, 5, 0, 0));

            demander(Gamme.CAPABLE);

            String corps = api.derniere().corps();
            assertThat(corps).contains("\"model\":\"claude-opus-5-5\"", "\"max_tokens\":16000", "\"effort\":\"high\"",
                    "\"cache_control\":{\"type\":\"ephemeral\"}", "Tu réponds à un essai de connexion.", "Dis bonjour.",
                    "json_schema", "\"fallbacks\":\"default\"");
            assertThat(api.derniere().entetes().getFirst("anthropic-beta")).contains(ModeleAnthropic.BETA_REPLI);
        }

        @Test
        void la_gamme_economique_demande_effort_bas_sans_repli_que_haiku_ne_connait_pas() {
            api.repondre(200, message(HAIKU, "{\"message\":\"Prêt\",\"nombre\":1}", "end_turn", 10, 5, 0, 0));

            demander(Gamme.ECONOMIQUE);

            String corps = api.derniere().corps();
            assertThat(corps).contains("\"model\":\"claude-haiku-5-5\"", "\"effort\":\"low\"").doesNotContain("fallbacks");
            String beta = api.derniere().entetes().getFirst("anthropic-beta");
            assertThat(beta == null ? "" : beta).doesNotContain(ModeleAnthropic.BETA_REPLI);
        }

        @Test
        void un_repli_se_paie_au_tarif_du_modele_qui_a_repondu() {
            api.repondre(200, message("claude-opus-4-8", "{\"message\":\"Prêt\",\"nombre\":1}", "end_turn", 1000, 100, 0, 0));

            Consommation c = demander(Gamme.CAPABLE).consommation();

            assertThat(c.modele()).isEqualTo("claude-opus-4-8");
            assertThat(c.coutMicroDollars()).isEqualTo(1000 * 5 + 100 * 25);
        }

        @Test
        void le_modele_de_chaque_gamme_est_celui_des_reglages() {
            assertThat(modele.modele(Gamme.ECONOMIQUE)).isEqualTo(HAIKU);
            assertThat(modele.modele(Gamme.CAPABLE)).isEqualTo(OPUS);
        }

        @Test
        void un_modele_dont_le_cout_est_inconnu_est_refuse_d_emblee() {
            assertThatThrownBy(() -> new ModeleAnthropic(api.client(), "modele-inconnu", OPUS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("EN-5");
        }
    }

    @Nested
    class ReponsesInutilisables {

        @Test
        void un_refus_est_un_echec_qui_garde_ce_qu_il_a_coute() {
            api.repondre(200, """
                    {"id":"msg_essai","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],
                     "stop_reason":"refusal","stop_sequence":null,
                     "stop_details":{"type":"refusal","category":"cyber","explanation":"essai"},
                     "usage":{"input_tokens":50,"output_tokens":0}}
                    """);

            ErreurModele e = echec(Gamme.CAPABLE);

            assertThat(e.nature()).isEqualTo(Nature.REFUS);
            assertThat(e.getMessage()).contains("cyber");
            assertThat(e.consommation()).hasValueSatisfying(c -> assertThat(c.jetonsEntree()).isEqualTo(50));
            assertThat(e.reessayable()).isFalse();
        }

        @Test
        void une_reponse_coupee_n_est_pas_utilisee() {
            api.repondre(200, message(OPUS, "{\"message\":\"Pr", "max_tokens", 10, 16000, 0, 0));

            assertThat(echec(Gamme.CAPABLE).nature()).isEqualTo(Nature.TRONQUEE);
        }

        @Test
        void une_reponse_hors_de_la_forme_demandee_est_invalide() {
            api.repondre(200, message(OPUS, "pas du JSON", "end_turn", 10, 5, 0, 0));

            ErreurModele e = echec(Gamme.CAPABLE);

            assertThat(e.nature()).isEqualTo(Nature.INVALIDE);
            assertThat(e.consommation()).isPresent();
        }
    }

    @Nested
    class ErreursDeLApi {

        @ParameterizedTest(name = "HTTP {0} → {2}")
        @CsvSource({
            "401, authentication_error, CONFIGURATION, ANTHROPIC_API_KEY",
            "402, billing_error, CONFIGURATION, Crédit épuisé",
            "403, permission_error, CONFIGURATION, 403",
            "404, not_found_error, CONFIGURATION, claude-opus-5-5",
            "400, invalid_request_error, INVALIDE, 400",
            "429, rate_limit_error, INDISPONIBLE, réessayez",
            "500, api_error, INDISPONIBLE, rien n'est perdu",
            "529, overloaded_error, INDISPONIBLE, rien n'est perdu",
        })
        void chaque_erreur_dit_ce_qu_il_faut_faire(int statut, String type, Nature nature, String indice) {
            api.repondre(statut, erreur(type, "essai"));

            ErreurModele e = echec(Gamme.CAPABLE);

            assertThat(e.nature()).isEqualTo(nature);
            assertThat(e.getMessage()).contains(indice);
            assertThat(e.consommation()).isEmpty();
            assertThat(e.reessayable()).isEqualTo(nature == Nature.INDISPONIBLE);
        }

        @Test
        void une_api_injoignable_est_indisponible_et_rien_n_est_perdu() {
            api.close();

            ErreurModele e = echec(Gamme.ECONOMIQUE);

            assertThat(e.nature()).isEqualTo(Nature.INDISPONIBLE);
            assertThat(e.getMessage()).contains("rien n'est perdu");
        }
    }
}
