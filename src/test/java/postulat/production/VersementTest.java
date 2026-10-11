package postulat.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** UC-04 : verser au profil ce que l'auteur a écrit, sans requalifier ce qui a déjà été produit (RG-23). */
class VersementTest {

    static final Realisation ATELIER = new Realisation("exp-acme-r2", "Partager la pratique", "Animé un atelier SQL", "", List.of(), "");

    @Nested
    class LeProfil {

        @Test
        void une_realisation_se_range_apres_celles_de_son_parent() {
            Profil p = Exemples.complet().avecRealisation("exp-acme", ATELIER);

            assertThat(p.realisationsDe("exp-acme")).containsExactly(Exemples.dashboard(), ATELIER);
            Realisation memoire = new Realisation("form-master-r1", "Apprendre", "Mémoire", "", List.of(), "");
            assertThat(p.avecRealisation("form-master", memoire).realisationsDe("form-master")).containsExactly(memoire);
            assertThat(p.realisationsDe("comp-sql")).isEmpty();
            assertThatThrownBy(() -> p.avecRealisation("comp-sql", memoire)).hasMessageContaining("ni une expérience ni une formation");
        }

        @Test
        void une_competence_s_ajoute_et_un_fait_se_remplace_a_sa_place() {
            Competence python = new Competence("comp-python", "Python", "", "");
            Profil p = Exemples.complet().avecCompetence(python);
            assertThat(p.competences()).containsExactly(Exemples.sql(), python);

            Realisation enrichie = new Realisation("exp-acme-dashboard", "Suivre les ventes chaque semaine",
                    "Concevoir un tableau de bord Power BI", "5 KPI, utilisé par 12 commerciaux, chaque lundi", List.of("Power BI"), "");
            assertThat(p.avecFaitRemplace(enrichie).realisationsDe("exp-acme")).containsExactly(enrichie);
            Competence sqlExpert = new Competence("comp-sql", "SQL", "expert", "");
            assertThat(p.avecFaitRemplace(sqlExpert).competences()).containsExactly(sqlExpert, python);
            assertThatThrownBy(() -> p.avecFaitRemplace(new Competence("exp-acme-dashboard", "x", "", "")))
                    .hasMessageContaining("Aucun fait « exp-acme-dashboard » de cette sorte");
            assertThatThrownBy(() -> p.avecFaitRemplace(Exemples.stage())).hasMessageContaining("Seules une réalisation ou une compétence");
        }

        /** RG-25, UC-04 E1 : l'identifiant d'un fait versé est libre, même si la suite logique est déjà prise. */
        @Test
        void un_identifiant_libre_se_derive_du_parent_ou_du_nom() {
            Profil p = Exemples.complet();

            assertThat(p.identifiantDeRealisation("exp-acme")).isEqualTo("exp-acme-r2");
            Profil prise = p.avecRealisation("exp-acme", new Realisation("exp-acme-r2", "a", "b", "", List.of(), ""));
            assertThat(prise.identifiantDeRealisation("exp-acme")).isEqualTo("exp-acme-r3");
            assertThat(p.identifiantDeCompetence("Power BI")).isEqualTo("comp-power-bi");
            assertThat(p.identifiantDeCompetence("SQL")).isEqualTo("comp-sql-2");
            assertThat(p.identifiantDeCompetence("Écoute & synthèse !")).isEqualTo("comp-ecoute-synthese");
            assertThat(p.identifiantDeCompetence("C++")).isEqualTo("comp-c");
            assertThat(p.identifiantDeCompetence("++")).isEqualTo("comp");
        }
    }

    /** UC-04 A2 : une détection prudente, sans modèle, de ce qui redit un fait existant. */
    @Nested
    class LeRecoupement {

        @Test
        void une_reformulation_du_meme_fait_se_reconnait() {
            Profil p = Exemples.complet();

            assertThat(Recoupement.realisationProche(p, "exp-acme", "Conçu un tableau de bord Power BI pour les ventes"))
                    .contains(Exemples.dashboard());
            assertThat(Recoupement.realisationProche(p, "exp-acme", "Animé un atelier SQL pour l'équipe")).isEmpty();
            assertThat(Recoupement.realisationProche(p, "form-master", "Conçu un tableau de bord Power BI")).isEmpty();
        }

        @Test
        void les_mots_vides_et_trop_courts_ne_comptent_pas() {
            assertThat(Recoupement.motsSignificatifs("Pour chaque vente, un bilan de 5 KPI avec l'équipe"))
                    .containsExactlyInAnyOrder("vente", "bilan", "equipe", "5");
            assertThat(Recoupement.recoupent(java.util.Set.of("vente"), java.util.Set.of("vente"))).isFalse();
        }

        @Test
        void une_competence_homonyme_se_reconnait_sans_casse_ni_accents() {
            assertThat(Recoupement.competenceHomonyme(Exemples.complet(), " sql ")).contains(Exemples.sql());
            assertThat(Recoupement.competenceHomonyme(Exemples.complet(), "Python")).isEmpty();
        }
    }

    /** RG-23 : versée, la phrase reste de l'auteur ; elle retient seulement le fait qu'on en a tiré. */
    @Nested
    class LeDossier {

        static Dossier dossier() {
            Dossier d = InstantaneTest.dossier(Apercu.de(Exemples.complet()));
            return d.corriger(3, "Bâti le suivi hebdomadaire des ventes");
        }

        @Test
        void une_phrase_versee_garde_son_origine_d_auteur_et_retient_le_fait() {
            Dossier d = dossier().marquerVersee(3, "exp-acme-r2");

            assertThat(d.phrase(3)).isEqualTo(new Assertion("Bâti le suivi hebdomadaire des ventes",
                    new Origine.Auteur(Optional.of("exp-acme-r2"))));
            assertThat(d.assertionsDeLAuteur()).isEqualTo(2);
            int lettre = d.cv().assertions().size() + 3;
            assertThat(d.marquerVersee(lettre, "comp-langues").phrase(lettre).origine())
                    .isEqualTo(new Origine.Auteur(Optional.of("comp-langues")));
        }

        @Test
        void seule_une_phrase_de_l_auteur_pas_encore_versee_se_verse() {
            assertThatThrownBy(() -> dossier().marquerVersee(1, "x")).hasMessageContaining("n'est pas de votre main");
            assertThatThrownBy(() -> dossier().marquerVersee(3, "a").marquerVersee(3, "b"))
                    .hasMessageContaining("déjà versée au profil, dans le fait « a »");
        }

        @Test
        void le_parent_propose_est_celui_que_cite_l_intitule_du_bloc() {
            Dossier d = dossier();
            int lettre = d.cv().assertions().size() + 1;

            assertThat(d.parentPropose(3)).contains("exp-acme");
            assertThat(d.parentPropose(2)).contains("exp-acme");
            assertThat(d.parentPropose(1)).isEmpty();
            assertThat(d.parentPropose(lettre)).isEmpty();
            assertThat(d.corriger(1, "Data analyst").parentPropose(3)).isEmpty();
        }
    }
}
