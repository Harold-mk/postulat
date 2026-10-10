package postulat.rendu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import postulat.production.Assertion;
import postulat.production.Ats;
import postulat.production.Cv;
import postulat.production.Exigence;
import postulat.production.Identite;
import postulat.production.Lettre;
import postulat.production.Origine;

class RenduTest {

    static final Identite CAMILLE = new Identite("Camille Martin", "Étudiante en master data", "camille@exemple.test", "Lyon");

    static Assertion faits(String texte) {
        return new Assertion(texte, Origine.Faits.de("exp-acme"));
    }

    /** Un CV d'un seul bloc et de {@code n} puces courtes, la n° 1 la plus pertinente. */
    static Cv cvDe(int n) {
        List<Cv.Puce> puces = IntStream.rangeClosed(1, n)
                .mapToObj(i -> new Cv.Puce(faits("Réalisation numéro " + i + " menée de bout en bout"), i))
                .toList();
        return new Cv(CAMILLE, List.of(new Cv.Section("Expériences", List.of(
                new Cv.Bloc(faits("Stagiaire data analyst — Acme"), Optional.of(faits("2025-01 à 2025-06 · Lyon")), puces)))));
    }

    static String aplati(String texte) {
        return texte.replaceAll("\\s+", " ");
    }

    @Nested
    class UnePage {

        @Test
        void un_cv_court_tient_au_plus_aere_et_se_relit_entierement() {
            Rendu.Resultat r = Rendu.cv(cvDe(5));

            assertThat(Rendu.pages(r.pdf())).isEqualTo(1);
            assertThat(r.gabarit()).isEqualTo("aéré");
            assertThat(r.retirees()).isEmpty();
            assertThat(r.avertissements()).isEmpty();
            assertThat(aplati(r.texte())).contains("Camille Martin", "EXPÉRIENCES", "Stagiaire data analyst — Acme",
                    "2025-01 à 2025-06 · Lyon", "Réalisation numéro 5 menée de bout en bout");
        }

        @Test
        void un_cv_trop_long_se_compacte_avant_de_perdre_une_phrase() {
            Rendu.Resultat r = Rendu.cv(cvDe(60));

            assertThat(r.gabarit()).isNotEqualTo("aéré");
            assertThat(r.retirees()).isEmpty();
            assertThat(Rendu.pages(r.pdf())).isEqualTo(1);
        }

        /** RG-26 : au plus serré, le code retire les puces les moins pertinentes, et le rapport les liste toutes. */
        @Test
        void au_plus_serre_les_puces_les_moins_pertinentes_sont_retirees_et_listees() {
            Rendu.Resultat r = Rendu.cv(cvDe(120));

            assertThat(r.gabarit()).isEqualTo("compact");
            assertThat(r.retirees()).isNotEmpty();
            int gardees = 120 - r.retirees().size();
            assertThat(r.retirees()).extracting(Cv.Puce::rang)
                    .containsExactlyElementsOf(IntStream.iterate(120, i -> i > gardees, i -> i - 1).boxed().toList());
            String texte = aplati(r.texte());
            assertThat(texte).contains("Réalisation numéro " + gardees + " menée")
                    .doesNotContain("Réalisation numéro " + (gardees + 1) + " menée");
            assertThat(Rendu.pages(r.pdf())).isEqualTo(1);
        }

        @Test
        void un_cv_dont_meme_la_structure_deborde_est_refuse_avec_la_mesure_du_debordement() {
            List<Cv.Contenu> blocs = new ArrayList<>();
            for (int i = 0; i < 90; i++) {
                blocs.add(new Cv.Bloc(faits("Expérience " + i), Optional.empty(), List.of()));
            }
            Cv cv = new Cv(CAMILLE, List.of(new Cv.Section("Expériences", blocs)));

            assertThatThrownBy(() -> Rendu.cv(cv)).isInstanceOf(ErreurRendu.class)
                    .hasMessageContaining("Même sans aucune puce").hasMessageContaining(" mm");
        }
    }

    @Nested
    class TexteRelu {

        @Test
        void les_accents_et_signes_du_francais_se_relisent_intacts() {
            String phrase = "Élaboration d'un modèle prédictif : 12 % d'économies, œuvre « collective » — à Besançon…";
            Cv cv = new Cv(CAMILLE, List.of(new Cv.Section("Expériences", List.of(
                    new Cv.Bloc(faits("Stage"), Optional.empty(), List.of(new Cv.Puce(faits(phrase), 1)))))));

            Rendu.Resultat r = Rendu.cv(cv);

            assertThat(aplati(r.texte())).contains(phrase);
            assertThat(r.avertissements()).isEmpty();
        }

        @Test
        void un_caractere_que_la_police_ignore_est_remplace_et_signale() {
            Cv cv = new Cv(CAMILLE, List.of(new Cv.Section("Expériences", List.of(new Cv.Bloc(faits("Stage"), Optional.empty(),
                    List.of(new Cv.Puce(faits("Livraison → mise en production ✓"), 1)))))));

            Rendu.Resultat r = Rendu.cv(cv);

            assertThat(aplati(r.texte())).contains("Livraison -> mise en production ?");
            assertThat(r.avertissements()).singleElement().asString().contains("U+2713");
        }

        @Test
        void un_mot_plus_long_qu_une_ligne_est_coupe_et_la_relecture_le_signale() {
            String mot = "a".repeat(200);
            Cv cv = new Cv(CAMILLE, List.of(new Cv.Section("Expériences", List.of(new Cv.Bloc(faits("Stage"), Optional.empty(),
                    List.of(new Cv.Puce(faits(mot), 1)))))));

            Rendu.Resultat r = Rendu.cv(cv);

            assertThat(r.avertissements()).anySatisfy(a -> assertThat(a).contains("invisible pour un filtre ATS"));
        }

        /** CA-5.1 : la note ATS porte sur le texte relu, qu'une exigence soit coupée en fin de ligne ou non. */
        @Test
        void la_note_ats_se_calcule_sur_le_texte_relu_du_pdf() {
            String longue = "Pilotage d'un projet de migration vers Power BI " + "avec documentation détaillée ".repeat(6)
                    + "et requêtes SQL optimisées";
            Cv cv = new Cv(CAMILLE, List.of(new Cv.Section("Expériences", List.of(new Cv.Bloc(faits("Stage"), Optional.empty(),
                    List.of(new Cv.Puce(faits(longue), 1)))))));

            Rendu.Resultat r = Rendu.cv(cv);

            assertThat(Ats.noter(r.texte(), List.of(Exigence.obligatoire("Power BI"), Exigence.obligatoire("SQL"),
                    Exigence.souhaitee("dbt"))).orElseThrow().note()).isEqualTo(80);
        }

        @Test
        void un_fichier_qui_n_est_pas_un_pdf_est_illisible() {
            assertThatThrownBy(() -> Rendu.texte("pas un PDF".getBytes())).isInstanceOf(ErreurRendu.class);
            assertThatThrownBy(() -> Rendu.pages("pas un PDF".getBytes())).isInstanceOf(ErreurRendu.class);
        }
    }

    @Nested
    class Lettres {

        static Lettre lettre(int paragraphes) {
            List<Lettre.Paragraphe> corps = IntStream.range(0, paragraphes)
                    .mapToObj(i -> new Lettre.Paragraphe(List.of(
                            faits("Votre équipe data construit des outils de décision pour toute l'entreprise."),
                            faits("J'y apporterais une pratique quotidienne de SQL et de Power BI, acquise en stage."))))
                    .toList();
            return new Lettre(CAMILLE, "Acme — Service des ressources humaines", "Lyon, le 10 octobre 2026",
                    "Candidature au stage de data analyst", corps);
        }

        @Test
        void une_lettre_tient_sur_une_page_et_se_relit() {
            Rendu.Resultat r = Rendu.lettre(lettre(3));

            assertThat(Rendu.pages(r.pdf())).isEqualTo(1);
            assertThat(aplati(r.texte())).contains("Objet : Candidature au stage de data analyst",
                    "Lyon, le 10 octobre 2026", "acquise en stage.");
            assertThat(r.avertissements()).isEmpty();
        }

        @Test
        void une_lettre_trop_longue_est_a_raccourcir_pas_a_tronquer() {
            assertThatThrownBy(() -> Rendu.lettre(lettre(40)))
                    .isInstanceOf(ErreurRendu.class)
                    .hasMessageContaining("à raccourcir");
        }
    }
}
