package postulat.stockage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import postulat.production.Competence;
import postulat.production.Experience;
import postulat.production.Fait;
import postulat.production.Identite;
import postulat.production.Profil;
import postulat.production.Realisation;
import postulat.stockage.LectureProfil.Probleme;

class LecteurProfilTest {

    static final Clock HORLOGE = Clock.fixed(Instant.parse("2026-10-10T08:30:00Z"), ZoneOffset.UTC);

    static final String COMPLET = """
            identite:
              nom: Camille Martin
              titre: Étudiante en master data
              contact: camille@exemple.test
              lieu: Lyon

            experiences:
              - id: exp-acme
                poste: Stagiaire data analyst
                organisation: Acme
                periode: 2025-01 à 2025-06
                duree: 6 mois
                realisations:
                  - id: exp-acme-dashboard
                    objectif: Suivre les ventes chaque semaine
                    tache: Concevoir un tableau de bord Power BI
                    resultat: 5 KPI, utilisé par 12 commerciaux
                    competences: [Power BI, SQL]

            competences:
              - id: comp-sql
                nom: SQL
                niveau: avancé
                consigne: toujours citer avec un exemple
            """;

    @TempDir
    Path dossier;

    LectureProfil lire(String yaml) {
        try {
            Path fichier = dossier.resolve("profil.yaml");
            Files.writeString(fichier, yaml, StandardCharsets.UTF_8);
            return LecteurProfil.lire(fichier);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Probleme seul(LectureProfil l) {
        assertThat(l.problemes()).hasSize(1);
        return l.problemes().get(0);
    }

    @Nested
    class ProfilLisible {

        @Test
        void un_profil_complet_se_lit_tel_quel_et_est_utilisable() {
            LectureProfil l = lire(COMPLET);

            assertThat(l.problemes()).isEmpty();
            assertThat(l.estUtilisable()).isTrue();
            Profil p = l.profil().orElseThrow();
            assertThat(p.identite()).isEqualTo(new Identite("Camille Martin", "Étudiante en master data", "camille@exemple.test", "Lyon"));
            assertThat(p.experiences()).containsExactly(new Experience("exp-acme", "Stagiaire data analyst", "Acme",
                    "2025-01 à 2025-06", "6 mois", "", "", List.of(new Realisation("exp-acme-dashboard",
                            "Suivre les ventes chaque semaine", "Concevoir un tableau de bord Power BI",
                            "5 KPI, utilisé par 12 commerciaux", List.of("Power BI", "SQL"), ""))));
            assertThat(p.formations()).isEmpty();
            assertThat(p.competences()).containsExactly(new Competence("comp-sql", "SQL", "avancé", "toujours citer avec un exemple"));
        }

        @Test
        void l_empreinte_designe_le_contenu_exact_du_fichier() {
            String empreinte = lire(COMPLET).empreinte();

            assertThat(empreinte).hasSize(64).matches("[0-9a-f]+");
            assertThat(lire(COMPLET).empreinte()).isEqualTo(empreinte);
            assertThat(lire(COMPLET.replace("avancé", "expert")).empreinte()).isNotEqualTo(empreinte);
        }

        @Test
        void aucune_valeur_n_est_convertie_en_nombre_date_ou_booleen() {
            LectureProfil l = lire(COMPLET.replace("nom: SQL", "nom: Non").replace("2025-01 à 2025-06", "2025-06"));

            Profil p = l.profil().orElseThrow();
            assertThat(p.experiences().get(0).periode()).isEqualTo("2025-06");
            assertThat(p.competences().get(0).nom()).isEqualTo("Non");
        }

        @Test
        void les_etiquettes_s_ecrivent_aussi_sur_une_ligne() {
            LectureProfil l = lire(COMPLET.replace("[Power BI, SQL]", "Power BI, SQL"));

            Realisation r = l.profil().orElseThrow().experiences().get(0).realisations().get(0);
            assertThat(r.competences()).containsExactly("Power BI", "SQL");
        }

        @Test
        void une_rubrique_vide_ou_absente_ne_fait_pas_echouer_la_lecture() {
            LectureProfil l = lire(COMPLET + "\nformations:\n");

            assertThat(l.problemes()).isEmpty();
            assertThat(l.profil().orElseThrow().faits()).extracting(Fait::id)
                    .containsExactly("exp-acme", "exp-acme-dashboard", "comp-sql");
        }
    }

    @Nested
    class ProblemesSitues {

        @Test
        void une_faute_de_frappe_est_signalee_a_sa_ligne_et_le_champ_manquant_aussi() {
            LectureProfil l = lire(COMPLET.replace("    organisation: Acme", "    orgnisation: Acme"));

            assertThat(l.problemes()).extracting(Probleme::ligne, Probleme::chemin).containsExactly(
                    org.assertj.core.groups.Tuple.tuple(8, "experiences[0].organisation"),
                    org.assertj.core.groups.Tuple.tuple(10, "experiences[0].orgnisation"));
            assertThat(l.problemes().get(0).message()).isEqualTo("obligatoire");
            assertThat(l.problemes().get(1).message()).contains("champ inconnu « orgnisation »").contains("organisation");
            assertThat(l.estUtilisable()).isFalse();
        }

        @Test
        void une_valeur_a_completer_est_situee_a_sa_ligne_et_sa_colonne() {
            Probleme p = seul(lire(COMPLET.replace("niveau: avancé", "niveau: à compléter")));

            assertThat(p).isEqualTo(new Probleme(23, 13, "competences[0].niveau", "à compléter"));
        }

        @Test
        void deux_faits_au_meme_identifiant_sont_signales_au_second() {
            Probleme p = seul(lire(COMPLET.replace("id: comp-sql", "id: exp-acme")));

            assertThat(p.ligne()).isEqualTo(21);
            assertThat(p.message()).contains("déjà utilisé par experiences[0]");
        }

        @Test
        void un_champ_ecrit_deux_fois_est_signale() {
            Probleme p = seul(lire(COMPLET.replace("    niveau: avancé", "    niveau: avancé\n    niveau: débutant")));

            assertThat(p.ligne()).isEqualTo(24);
            assertThat(p.message()).contains("écrit deux fois");
        }

        @Test
        void une_liste_attendue_est_signalee() {
            Probleme p = seul(lire("""
                    identite: {nom: A, titre: B, contact: C}
                    experiences: un stage chez Acme
                    competences:
                      - {id: c, nom: SQL}
                    """));

            assertThat(p).extracting(Probleme::ligne, Probleme::chemin).containsExactly(2, "experiences");
            assertThat(p.message()).contains("liste");
        }

        @Test
        void une_valeur_simple_attendue_est_signalee() {
            LectureProfil l = lire(COMPLET.replace("nom: Camille Martin", "nom: [Camille, Martin]"));

            assertThat(l.problemes()).extracting(Probleme::chemin, Probleme::message).containsExactly(
                    org.assertj.core.groups.Tuple.tuple("identite.nom", "une valeur simple est attendue, pas une liste ni des sous-champs."),
                    org.assertj.core.groups.Tuple.tuple("identite.nom", "obligatoire"));
        }

        @Test
        void une_etiquette_composee_est_refusee() {
            Probleme p = seul(lire(COMPLET.replace("[Power BI, SQL]", "[Power BI, {nom: SQL}]")));

            assertThat(p.chemin()).isEqualTo("experiences[0].realisations[0].competences[1]");
        }

        @Test
        void un_element_de_liste_qui_n_a_pas_de_champs_est_signale() {
            LectureProfil l = lire("""
                    identite: {nom: A, titre: B, contact: C}
                    competences:
                      - SQL
                    """);

            assertThat(l.problemes()).extracting(Probleme::chemin)
                    .containsExactly("competences[0]", "competences[0].id", "competences[0].nom");
        }
    }

    @Nested
    class FichierIllisible {

        @Test
        void une_erreur_de_syntaxe_donne_sa_position() {
            LectureProfil l = lire("identite:\n  nom: A\n\ttitre: B\n");

            Probleme p = seul(l);
            assertThat(p.ligne()).isEqualTo(3);
            assertThat(p.message()).startsWith("syntaxe YAML").contains("tabulations");
            assertThat(l.profil()).isEmpty();
        }

        @Test
        void un_fichier_vide_renvoie_au_gabarit() {
            LectureProfil l = lire("# rien que des commentaires\n");

            assertThat(seul(l).message()).contains("postulat profil initialiser");
            assertThat(l.profil()).isEmpty();
        }

        @Test
        void un_document_qui_n_est_pas_un_profil_est_signale() {
            LectureProfil l = lire("- une\n- liste\n");

            assertThat(seul(l).message()).contains("identite, experiences");
            assertThat(l.estUtilisable()).isFalse();
        }

        @Test
        void un_fichier_introuvable_est_une_erreur_technique() {
            assertThatThrownBy(() -> LecteurProfil.lire(dossier.resolve("absent.yaml"))).isInstanceOf(ErreurStockage.class);
        }
    }

    @Nested
    class Gabarit {

        Donnees donnees() {
            return new Donnees(dossier, HORLOGE);
        }

        @Test
        void le_gabarit_se_lit_et_ne_signale_que_ses_a_completer_a_leur_ligne() throws IOException {
            Path fichier = donnees().fichierProfil();
            donnees().ecrireGabaritProfil(fichier);

            LectureProfil l = donnees().lireProfil(fichier);
            List<String> lignes = Files.readAllLines(fichier);

            assertThat(l.profil()).isPresent();
            assertThat(l.problemes()).hasSize(12).allSatisfy(p -> {
                assertThat(p.message()).isEqualTo("à compléter");
                assertThat(lignes.get(p.ligne() - 1)).contains("à compléter");
            });
        }

        @Test
        void le_gabarit_n_ecrase_jamais_un_profil_existant() throws IOException {
            Path fichier = donnees().fichierProfil();
            Files.writeString(fichier, "mes faits");

            assertThatThrownBy(() -> donnees().ecrireGabaritProfil(fichier)).isInstanceOf(ErreurStockage.class);
            assertThat(Files.readString(fichier)).isEqualTo("mes faits");
            try (Stream<Path> fichiers = Files.list(dossier)) {
                assertThat(fichiers).extracting(f -> f.getFileName().toString()).containsExactly("profil.yaml");
            }
        }

        @Test
        void le_gabarit_cree_le_repertoire_des_donnees_au_besoin() {
            Path fichier = dossier.resolve("neuf").resolve("profil.yaml");

            donnees().ecrireGabaritProfil(fichier);

            assertThat(fichier).exists();
        }
    }
}
