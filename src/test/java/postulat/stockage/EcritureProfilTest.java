package postulat.stockage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import postulat.production.Competence;
import postulat.production.Profil;
import postulat.production.Realisation;

/** UC-04 : un fait s'écrit dans le profil à sa place, et rien d'autre ne bouge, commentaires compris. */
class EcritureProfilTest {

    static final Instant LE = Instant.parse("2026-10-11T10:30:00Z");

    /** Un profil écrit à la main, en blocs, avec ses commentaires. */
    static final String EN_BLOCS = """
            # Mon profil : la seule source de faits.
            identite:
              nom: Camille Martin
              titre: Étudiante en master data
              contact: camille@exemple.test

            experiences:
              - id: exp-acme
                poste: Stagiaire data analyst   # mon premier stage
                organisation: Acme
                periode: 2025-01 à 2025-06
                realisations:
                  - id: exp-acme-r1
                    objectif: Suivre les ventes
                    tache: Tableau de bord Power BI
                    competences: [Power BI, SQL]

                  # une réalisation à venir
              - id: exp-initech
                poste: Assistante
                organisation: Initech
                periode: 2024

            competences:
              - id: comp-sql
                nom: SQL
            """;

    /** Le même genre de profil, écrit en ligne. */
    static final String EN_LIGNE = """
            identite: {nom: Camille Martin, titre: Étudiante, contact: camille@exemple.test}
            experiences:
              - id: exp-acme
                poste: Stagiaire data analyst
                organisation: Acme
                periode: 2025
                realisations:
                  - {id: exp-acme-r1, objectif: Suivre les ventes, tache: Tableau de bord Power BI}
            formations:
              - {id: form-master, diplome: Master data, etablissement: Université Lumière, periode: 2024 à 2026}
            competences:
              - {id: comp-sql, nom: SQL}
            """;

    static final Realisation ATELIER = new Realisation("exp-acme-r2", "Partager la pratique", "Animé un atelier « SQL : les jointures »",
            "8 collègues formés, #1 des retours", List.of("SQL"), "");

    @TempDir
    Path dossier;

    Path profil(String yaml) throws IOException {
        return Files.writeString(dossier.resolve("profil.yaml"), yaml, StandardCharsets.UTF_8);
    }

    EcritureProfil ecriture() {
        return new EcritureProfil(dossier.resolve("profil.yaml"), dossier.resolve("profils"), LE);
    }

    Profil lu() {
        return LecteurProfil.lire(dossier.resolve("profil.yaml")).profil().orElseThrow();
    }

    String texte() throws IOException {
        return Files.readString(dossier.resolve("profil.yaml"), StandardCharsets.UTF_8);
    }

    @Nested
    class UneRealisation {

        @Test
        void elle_se_range_apres_les_autres_en_blocs_et_le_reste_ne_bouge_pas() throws IOException {
            profil(EN_BLOCS);
            Profil avant = lu();

            EcritureProfil.Ecriture e = ecriture().ajouterRealisation("exp-acme", ATELIER);

            assertThat(lu()).isEqualTo(avant.avecRealisation("exp-acme", ATELIER)).isEqualTo(e.profil());
            assertThat(texte()).isEqualTo(EN_BLOCS.replace("""
                            competences: [Power BI, SQL]
                    """, """
                            competences: [Power BI, SQL]
                          - id: exp-acme-r2
                            objectif: "Partager la pratique"
                            tache: "Animé un atelier « SQL : les jointures »"
                            resultat: "8 collègues formés, #1 des retours"
                            competences: ["SQL"]
                    """));
            assertThat(e.ancienneVersion()).hasFileName("profil-20261011-103000-" + LecteurProfil.lire(e.ancienneVersion())
                    .empreinte().substring(0, 8) + ".yaml").hasContent(EN_BLOCS);
        }

        @Test
        void en_ligne_elle_s_ecrit_en_ligne() throws IOException {
            profil(EN_LIGNE);
            Profil avant = lu();

            ecriture().ajouterRealisation("exp-acme", ATELIER);

            assertThat(lu()).isEqualTo(avant.avecRealisation("exp-acme", ATELIER));
            assertThat(texte()).contains("tache: Tableau de bord Power BI}\n      - {id: exp-acme-r2, objectif: \"Partager la pratique\", "
                    + "tache: \"Animé un atelier « SQL : les jointures »\", resultat: \"8 collègues formés, #1 des retours\", "
                    + "competences: [\"SQL\"]}\nformations:");
        }

        @Test
        void sans_realisations_le_parent_en_recoit_une_liste_dans_son_style() throws IOException {
            profil(EN_BLOCS);
            Realisation r = new Realisation("exp-initech-r1", "Automatiser", "Automatisé un rapport", "", List.of(), "");
            ecriture().ajouterRealisation("exp-initech", r);
            assertThat(texte()).contains("""
                        periode: 2024
                        realisations:
                          - id: exp-initech-r1
                            objectif: "Automatiser"
                            tache: "Automatisé un rapport"
                    """);

            profil(EN_LIGNE);
            Realisation m = new Realisation("form-master-r1", "Apprendre", "Mémoire sur les séries temporelles", "", List.of(), "");
            ecriture().ajouterRealisation("form-master", m);
            assertThat(texte()).contains("periode: 2024 à 2026, realisations: [{id: form-master-r1, objectif: \"Apprendre\", "
                    + "tache: \"Mémoire sur les séries temporelles\"}]}");
            assertThat(lu().realisationsDe("form-master")).containsExactly(m);
        }

        @Test
        void les_fins_de_ligne_windows_et_la_marque_d_ordre_restent() throws IOException {
            Files.write(dossier.resolve("profil.yaml"), ("﻿" + EN_BLOCS.replace("\n", "\r\n")).getBytes(StandardCharsets.UTF_8));

            ecriture().ajouterRealisation("exp-acme", ATELIER);

            String t = texte();
            assertThat(t).startsWith("﻿# Mon profil").doesNotContain("\r\r").contains("\r\n      - id: exp-acme-r2\r\n");
            assertThat(t.replace("\r\n", "")).doesNotContain("\n");
            assertThat(lu().realisationsDe("exp-acme")).contains(ATELIER);
        }
    }

    @Nested
    class UneCompetence {

        @Test
        void elle_rejoint_la_liste_ou_en_ouvre_une() throws IOException {
            profil(EN_LIGNE);
            Competence powerBi = new Competence("comp-power-bi", "Power BI", "intermédiaire", "");
            ecriture().ajouterCompetence(powerBi);
            assertThat(texte()).endsWith("""
                      - {id: comp-sql, nom: SQL}
                      - {id: comp-power-bi, nom: "Power BI", niveau: "intermédiaire"}
                    """);

            profil(EN_BLOCS.substring(0, EN_BLOCS.lastIndexOf("competences:")));
            ecriture().ajouterCompetence(powerBi);
            assertThat(texte()).endsWith("""
                        periode: 2024
                    competences:
                      - id: comp-power-bi
                        nom: "Power BI"
                        niveau: "intermédiaire"

                    """);
            assertThat(lu().competences()).containsExactly(powerBi);
        }
    }

    /** UC-04 A2 : enrichir un fait existant, ne réécrire que ce qui change. */
    @Nested
    class UnRemplacement {

        @Test
        void seuls_les_champs_qui_changent_sont_reecrits() throws IOException {
            profil(EN_BLOCS);
            Realisation enrichie = new Realisation("exp-acme-r1", "Suivre les ventes", "Tableau de bord Power BI",
                    "utilisé chaque semaine par 12 commerciaux", List.of("Power BI", "SQL"), "citer le nombre d'utilisateurs");

            ecriture().remplacer(enrichie);

            assertThat(texte()).isEqualTo(EN_BLOCS.replace("""
                            competences: [Power BI, SQL]
                    """, """
                            competences: [Power BI, SQL]
                            resultat: "utilisé chaque semaine par 12 commerciaux"
                            consigne: "citer le nombre d'utilisateurs"
                    """));
            assertThat(lu().fait("exp-acme-r1")).contains(enrichie);
        }

        @Test
        void un_champ_present_se_reecrit_a_sa_place_y_compris_en_ligne() throws IOException {
            profil(EN_LIGNE);
            Realisation enrichie = new Realisation("exp-acme-r1", "Suivre les ventes", "Tableau de bord Power BI de 5 KPI",
                    "", List.of(), "");

            ecriture().remplacer(enrichie);

            assertThat(texte()).contains("  - {id: exp-acme-r1, objectif: Suivre les ventes, tache: \"Tableau de bord Power BI de 5 KPI\"}");
            Competence sql = new Competence("comp-sql", "SQL", "avancé", "");
            ecriture().remplacer(sql);
            assertThat(texte()).contains("  - {id: comp-sql, nom: SQL, niveau: \"avancé\"}");
        }
    }

    @Nested
    class Prudence {

        @Test
        void ce_qui_ne_s_insere_pas_sans_risque_n_est_pas_ecrit_et_se_fait_a_la_main() throws IOException {
            String multiligne = EN_BLOCS.replace("competences: [Power BI, SQL]", "competences:\n          - Power BI\n          - SQL");
            profil(multiligne);
            Realisation autre = new Realisation("exp-acme-r1", "Suivre les ventes", "Tableau de bord Power BI", "", List.of("Excel"), "");

            assertThatThrownBy(() -> ecriture().remplacer(autre)).isInstanceOf(ErreurStockage.class)
                    .hasMessageContaining("Le profil n'a pas été modifié : le champ « competences » est une liste sur plusieurs lignes")
                    .hasMessageContaining("Ajoutez vous-même, à la place du fait « exp-acme-r1 » :")
                    .hasMessageContaining("  - id: exp-acme-r1").hasMessageContaining("competences: [\"Excel\"]");
            assertThat(texte()).isEqualTo(multiligne);
            assertThat(dossier.resolve("profils")).doesNotExist();
            assertThat(dossier.resolve("profil.yaml.provisoire")).doesNotExist();
        }

        @Test
        void un_profil_qui_a_des_problemes_ne_recoit_rien() throws IOException {
            String incomplet = EN_LIGNE.replace("nom: Camille Martin", "nom: à compléter");
            profil(incomplet);

            assertThatThrownBy(() -> ecriture().ajouterRealisation("exp-acme", ATELIER)).isInstanceOf(ErreurStockage.class)
                    .hasMessageContaining("postulat profil verifier");
            assertThat(texte()).isEqualTo(incomplet);
        }

        @Test
        void un_parent_inconnu_est_refuse_avant_toute_ecriture() throws IOException {
            profil(EN_LIGNE);

            assertThatThrownBy(() -> ecriture().ajouterRealisation("exp-inconnue", ATELIER))
                    .hasMessageContaining("n'est ni une expérience ni une formation");
            assertThat(texte()).isEqualTo(EN_LIGNE);
        }

        @Test
        void une_chaine_entre_guillemets_ne_peut_pas_passer_pour_de_la_syntaxe() {
            assertThat(EcritureProfil.guillemets("a \"b\" \\ c\nd\te\u0001")).isEqualTo("\"a \\\"b\\\" \\\\ c\\nd\\te\"");
        }
    }
}
