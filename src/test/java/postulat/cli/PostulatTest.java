package postulat.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.TypeConversionException;
import postulat.ia.Demande;
import postulat.ia.ErreurModele;
import postulat.ia.FauxModele;
import postulat.ia.Gamme;

/** Tests de bout en bout : de vraies commandes, une vraie base dans un répertoire temporaire, un jour fixe. */
class PostulatTest {

    /** Samedi 10 octobre 2026. */
    static final Clock HORLOGE = Clock.fixed(Instant.parse("2026-10-10T08:30:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dossier;

    /** Toute commande qui demanderait le modèle reçoit ce faux : aucun appel réel, aucun coût. */
    final FauxModele faux = new FauxModele();

    record Resultat(int code, String sortie, String erreurs) {}

    Resultat postulat(String... arguments) {
        return avecSaisie("", arguments);
    }

    Resultat avecSaisie(String saisie, String... arguments) {
        StringWriter sortie = new StringWriter();
        StringWriter erreurs = new StringWriter();
        CommandLine ligne = Postulat.ligneDeCommande(HORLOGE, new BufferedReader(new StringReader(saisie)), reglages -> faux);
        ligne.setOut(new PrintWriter(sortie, true));
        ligne.setErr(new PrintWriter(erreurs, true));
        String[] tous = Stream.concat(Stream.of("--donnees", dossier.toString()), Stream.of(arguments))
                .toArray(String[]::new);
        return new Resultat(ligne.execute(tous), sortie.toString(), erreurs.toString());
    }

    Resultat reussi(String... arguments) {
        Resultat r = postulat(arguments);
        assertThat(r.code()).as(r.erreurs()).isZero();
        return r;
    }

    @Nested
    class Ajouter {

        @Test
        void une_candidature_externe_entre_a_son_etape_et_a_sa_date_reelle() {
            Resultat r = reussi("ajouter", "Acme", "Stage data", "--etape", "envoyée", "--le", "01/10/2026");

            assertThat(r.sortie())
                    .contains("Candidature #1 Acme — Stage data ajoutée à l'étape « envoyée » le 01/10/2026.")
                    .contains("échéance le 11/10/2026 : relancer (relance n° 1)");
        }

        @Test
        void par_defaut_une_offre_entre_a_reperee_aujourd_hui() {
            assertThat(reussi("ajouter", "Acme", "Stage data").sortie())
                    .contains("à l'étape « repérée » le 10/10/2026");
        }

        @Test
        void un_doublon_est_refuse_avec_la_candidature_existante() {
            reussi("ajouter", "Société Générale", "Stage data");

            Resultat r = postulat("ajouter", "societe generale", "STAGE DATA");

            assertThat(r.code()).isEqualTo(Postulat.REFUSE);
            assertThat(r.erreurs()).contains("déjà suivie : #1").contains("postulat avancer 1").contains("--distincte");
        }

        @Test
        void un_doublon_termine_propose_la_reouverture() {
            reussi("ajouter", "Acme", "Stage data", "--etape", "envoyée");
            reussi("avancer", "1", "refusée");

            assertThat(postulat("ajouter", "Acme", "Stage data").erreurs()).contains("postulat rouvrir 1");
        }

        @Test
        void une_offre_distincte_s_ajoute_malgre_la_ressemblance() {
            reussi("ajouter", "Acme", "Stage data");
            assertThat(reussi("ajouter", "Acme", "Stage data", "--distincte").sortie()).contains("#2");
        }

        @Test
        void une_date_future_est_refusee_par_le_domaine() {
            Resultat r = postulat("ajouter", "Acme", "Stage", "--le", "2026-10-11");

            assertThat(r.code()).isEqualTo(Postulat.REFUSE);
            assertThat(r.erreurs()).contains("futur");
        }
    }

    @Nested
    class Avancer {

        @Test
        void une_transition_declaree_est_enregistree_et_conseille_la_suite() {
            reussi("ajouter", "Acme", "Stage", "--etape", "envoyée", "--le", "2026-10-01");

            Resultat r = reussi("avancer", "1", "entretien", "--le", "hier");

            assertThat(r.sortie()).contains("#1 Acme — Stage : passée à « entretien » le 09/10/2026.")
                    .contains("postulat planifier 1 JOUR --type");
        }

        @Test
        void une_transition_non_declaree_est_refusee_avec_les_possibles() {
            reussi("ajouter", "Acme", "Stage");

            Resultat r = postulat("avancer", "1", "envoyée");

            assertThat(r.code()).isEqualTo(Postulat.REFUSE);
            assertThat(r.erreurs()).contains("non autorisée").contains("à_préparer");
        }

        @Test
        void l_etape_se_tape_sans_accents_ni_soulignes() {
            reussi("ajouter", "Acme", "Stage", "--etape", "envoyee");
            assertThat(reussi("avancer", "1", "offre recue").sortie()).contains("« offre_reçue »");
        }

        @Test
        void une_etape_inconnue_est_une_saisie_invalide_expliquee_en_francais() {
            reussi("ajouter", "Acme", "Stage");

            Resultat r = postulat("avancer", "1", "embauchée");

            assertThat(r.code()).isEqualTo(Postulat.SAISIE_INVALIDE);
            assertThat(r.erreurs()).contains("Valeur refusée pour ETAPE : étape inconnue « embauchée »")
                    .contains("postulat avancer --aide");
        }

        @Test
        void une_candidature_inconnue_est_signalee() {
            Resultat r = postulat("avancer", "42", "envoyée");

            assertThat(r.code()).isEqualTo(Postulat.REFUSE);
            assertThat(r.erreurs()).contains("Aucune candidature n° 42");
        }

        @Test
        void une_terminale_se_rouvre_explicitement() {
            reussi("ajouter", "Acme", "Stage", "--etape", "envoyée");
            reussi("avancer", "1", "sans_suite");

            assertThat(reussi("rouvrir", "1", "envoyée").sortie()).contains("rouverte à « envoyée »");
        }
    }

    @Nested
    class Suivi {

        @Test
        void une_relance_est_comptee_et_repousse_l_echeance() {
            reussi("ajouter", "Acme", "Stage", "--etape", "envoyée", "--le", "2026-09-25");

            Resultat r = reussi("relancer", "1");

            assertThat(r.sortie()).contains("relance n° 1 enregistrée le 10/10/2026")
                    .contains("échéance le 20/10/2026 : relancer (relance n° 2)");
        }

        @Test
        void on_ne_relance_qu_une_candidature_envoyee() {
            reussi("ajouter", "Acme", "Stage");
            assertThat(postulat("relancer", "1").code()).isEqualTo(Postulat.REFUSE);
        }

        @Test
        void un_entretien_se_planifie_puis_se_passe_avec_son_compte_rendu() {
            reussi("ajouter", "Acme", "Stage", "--etape", "entretien", "--le", "2026-10-01");

            assertThat(reussi("planifier", "1", "2026-10-12", "--type", "technique").sortie())
                    .contains("entretien technique planifié le 12/10/2026")
                    .contains("échéance le 11/10/2026 : préparer l'entretien technique du 12/10/2026")
                    .contains("échéance le 12/10/2026 : remercier après l'entretien technique du 12/10/2026");
            assertThat(reussi("entretien", "1", "--type", "rh", "--compte-rendu", "Motivation, disponibilités").sortie())
                    .contains("entretien RH passé le 10/10/2026, compte rendu joint");
            assertThat(reussi("voir", "1").sortie())
                    .contains("note : Compte rendu de l'entretien RH : Motivation, disponibilités");
        }

        @Test
        void le_type_d_entretien_est_obligatoire() {
            reussi("ajouter", "Acme", "Stage", "--etape", "entretien");

            Resultat r = postulat("planifier", "1", "2026-10-12");

            assertThat(r.code()).isEqualTo(Postulat.SAISIE_INVALIDE);
            assertThat(r.erreurs()).contains("Manquant : --type");
        }

        @Test
        void retour_promis_interaction_contact_et_note_se_retrouvent_dans_la_fiche() {
            reussi("ajouter", "Acme", "Stage", "--etape", "envoyée", "--le", "2026-10-01");
            reussi("interaction", "1", "appel", "--notes", "La RH a reçu le dossier", "--le", "2026-10-05");
            reussi("retour-promis", "1", "2026-10-15", "--le", "2026-10-05");
            reussi("contact", "1", "Marie Curie", "--role", "RH", "--coordonnees", "marie@acme.test");
            reussi("noter", "1", "Rappeler", "après", "le", "15");

            assertThat(reussi("voir", "1").sortie())
                    .contains("#1 Acme — Stage")
                    .contains("Étape : envoyée, depuis le 01/10/2026 (il y a 9 jours)")
                    .contains("Dossier : aucun dossier rattaché.")
                    .contains("Marie Curie · RH · marie@acme.test")
                    .contains("05/10/2026  appel : La RH a reçu le dossier")
                    .contains("05/10/2026  retour promis pour le 15/10/2026")
                    .contains("10/10/2026  note : Rappeler après le 15")
                    .contains("15/10/2026  retour promis attendu");
        }

        @Test
        void une_fiche_sans_contact_ni_echeance_le_dit() {
            reussi("ajouter", "Acme", "Stage", "--etape", "envoyée");
            reussi("avancer", "1", "refusée");

            assertThat(reussi("voir", "1").sortie()).contains("Contacts\n  aucun".replace("\n", System.lineSeparator()))
                    .contains("Échéances\n  aucune".replace("\n", System.lineSeparator()));
        }
    }

    @Nested
    class Consultation {

        @Test
        void sans_action_due_la_liste_de_travail_le_dit_et_annonce_la_prochaine() {
            reussi("ajouter", "Acme", "Stage", "--etape", "envoyée", "--le", "2026-10-05");

            assertThat(reussi("actions").sortie())
                    .contains("Aucune action due aujourd'hui (10/10/2026).")
                    .contains("Prochaine échéance le 15/10/2026 : #1 Acme — Stage · relancer (relance n° 1)");
        }

        @Test
        void la_liste_de_travail_donne_le_contexte_et_la_commande_a_taper() {
            reussi("ajouter", "Acme", "Stage", "--etape", "envoyée", "--le", "2026-09-30");
            reussi("ajouter", "Globex", "Stage BI", "--etape", "envoyée", "--le", "2026-09-01");
            reussi("relancer", "2", "--le", "2026-09-11");
            reussi("relancer", "2", "--le", "2026-09-21");

            String sortie = reussi("actions").sortie();

            assertThat(sortie).contains("Actions dues le 10/10/2026 : 2")
                    .contains("relances épuisées sans réponse : passer à « sans_suite » ?, en retard de 9 jours → postulat avancer 2 sans_suite")
                    .contains("relancer (relance n° 1), aujourd'hui → postulat relancer 1");
            assertThat(sortie.indexOf("#2 Globex")).isLessThan(sortie.indexOf("#1 Acme"));
        }

        @Test
        void le_tableau_de_bord_couvre_les_six_rubriques() {
            reussi("ajouter", "Acme", "Stage", "--etape", "envoyée", "--le", "2026-10-01");
            reussi("ajouter", "Globex", "Stage", "--le", "2026-10-03");

            assertThat(reussi("tableau").sortie())
                    .contains("Tableau de bord du 10/10/2026")
                    .contains("repérée 1 · à_préparer 0 · prête 0 · envoyée 1 · entretien 0 · offre_reçue 0")
                    .contains("terminées : écartée 0 · acceptée 0")
                    .contains("semaine du 28/09/2026    1 / 30   partielle : suivi commencé le 01/10/2026")
                    .contains("semaine du 05/10/2026    0 / 30   en cours")
                    .contains("#1 Acme — Stage · envoyée le 01/10/2026, il y a 9 jours, aucune relance")
                    .contains("Actions dues aujourd'hui : aucune")
                    .contains("11/10/2026 · #1 Acme — Stage · relancer (relance n° 1)")
                    .contains("#2 Globex — Stage · repérée le 03/10/2026, il y a 7 jours");
        }

        @Test
        void une_campagne_vide_a_un_tableau_juste() {
            assertThat(reussi("tableau").sortie())
                    .contains("En attente de réponse : aucune")
                    .contains("semaine du 05/10/2026    0 / 30   en cours, partielle");
        }

        @Test
        void la_liste_regroupe_par_entreprise_et_cache_les_terminees() {
            reussi("ajouter", "Globex", "Stage BI");
            reussi("ajouter", "Acme", "Stage data", "--etape", "envoyée");
            reussi("ajouter", "acme", "Stage finance");
            reussi("ajouter", "Initech", "Stage", "--etape", "envoyée");
            reussi("avancer", "4", "refusée");

            String liste = reussi("liste").sortie();
            assertThat(liste).contains("Acme").contains("  #2 Stage data · envoyée depuis le 10/10/2026")
                    .contains("  #3 Stage finance").doesNotContain("Initech");
            assertThat(liste.indexOf("#3")).isLessThan(liste.indexOf("Globex"));

            assertThat(reussi("liste", "--toutes").sortie()).contains("Initech");
            assertThat(reussi("liste", "--etape", "refusée").sortie()).contains("Initech").doesNotContain("Acme");
            assertThat(reussi("liste", "--entreprise", "glob").sortie()).contains("Globex").doesNotContain("Acme");
            assertThat(reussi("liste", "--entreprise", "zzz").sortie()).contains("Aucune candidature ne correspond.");
        }

        @Test
        void une_liste_vide_dit_comment_commencer() {
            assertThat(reussi("liste").sortie()).contains("postulat ajouter ENTREPRISE POSTE");
        }
    }

    @Nested
    class SauvegardeEtRestauration {

        Path sauvegarder() throws IOException {
            reussi("sauvegarder");
            try (Stream<Path> archives = Files.list(dossier.resolve("sauvegardes"))) {
                return archives.sorted().findFirst().orElseThrow();
            }
        }

        @Test
        void la_sauvegarde_donne_son_chemin_et_sa_taille() {
            assertThat(reussi("sauvegarder").sortie())
                    .contains("Sauvegarde écrite : ").contains("postulat-2026-10-10-083000.zip").contains("Ko)")
                    .contains("Pour la restaurer : postulat restaurer");
        }

        @Test
        void sans_confirmation_la_restauration_ne_touche_a_rien() throws IOException {
            reussi("ajouter", "Avant", "Stage");
            Path archive = sauvegarder();
            reussi("ajouter", "Après", "Stage");

            Resultat r = avecSaisie("non\n", "restaurer", archive.toString());

            assertThat(r.code()).isZero();
            assertThat(r.sortie()).contains("Archive vérifiée : 1 candidature(s), 1 événement(s)")
                    .contains("Tapez « oui » pour confirmer").contains("Restauration annulée : rien n'a été modifié.");
            assertThat(reussi("liste").sortie()).contains("Après");
        }

        @Test
        void confirmee_elle_rend_l_etat_sauvegarde_et_garde_l_etat_remplace() throws IOException {
            reussi("ajouter", "Avant", "Stage");
            Path archive = sauvegarder();
            reussi("ajouter", "Après", "Stage");

            Resultat r = avecSaisie("Oui\n", "restaurer", archive.toString());

            assertThat(r.sortie()).contains("Données restaurées depuis").contains("L'état remplacé reste récupérable");
            assertThat(reussi("liste").sortie()).contains("Avant").doesNotContain("Après");
        }

        @Test
        void l_option_oui_evite_la_question() throws IOException {
            Path archive = sauvegarder();
            assertThat(reussi("restaurer", archive.toString(), "--oui").sortie()).doesNotContain("Tapez");
        }

        @Test
        void une_archive_invalide_est_une_erreur_technique_sans_degat() throws IOException {
            reussi("ajouter", "Acme", "Stage");
            Path faux = Files.writeString(dossier.resolve("faux.zip"), "pas une archive");

            Resultat r = postulat("restaurer", faux.toString(), "--oui");

            assertThat(r.code()).isEqualTo(Postulat.ERREUR);
            assertThat(r.erreurs()).contains("rien n'a été modifié");
            assertThat(reussi("liste").sortie()).contains("Acme");
        }
    }

    @Nested
    class LigneDeCommande {

        @Test
        void sans_commande_l_aide_s_affiche_en_francais() {
            assertThat(reussi().sortie()).contains("Usage : postulat").contains("Commandes :").contains("tableau");
        }

        @Test
        void chaque_commande_a_son_aide() {
            assertThat(reussi("planifier", "--aide").sortie()).contains("Paramètres :").contains("--type=TYPE");
        }

        @Test
        void le_repertoire_des_donnees_peut_suivre_la_commande() {
            Path ailleurs = dossier.resolve("ailleurs");
            CommandLine ligne = Postulat.ligneDeCommande(HORLOGE, new BufferedReader(new StringReader("")));
            ligne.setOut(new PrintWriter(new StringWriter()));

            assertThat(ligne.execute("ajouter", "Acme", "Stage", "--donnees", ailleurs.toString())).isZero();
            assertThat(ailleurs.resolve("postulat.db")).exists();
        }

        @Test
        void un_parametre_manquant_ou_en_trop_est_explique() {
            assertThat(postulat("relancer").erreurs()).contains("Manquant : NUMERO");
            assertThat(postulat("liste", "en-trop").erreurs()).contains("Argument inattendu : en-trop");
        }

        @Test
        void une_date_illisible_est_refusee() {
            reussi("ajouter", "Acme", "Stage", "--etape", "envoyée");

            Resultat r = postulat("relancer", "1", "--le", "31/02/2026");

            assertThat(r.code()).isEqualTo(Postulat.SAISIE_INVALIDE);
            assertThat(r.erreurs()).contains("--le : date illisible « 31/02/2026 »");
        }

        @Test
        void les_reglages_du_repertoire_de_donnees_s_appliquent() throws IOException {
            Files.createDirectories(dossier);
            Files.writeString(dossier.resolve("postulat.properties"), "relance.delai.jours = 3\n");

            assertThat(reussi("ajouter", "Acme", "Stage", "--etape", "envoyée").sortie())
                    .contains("échéance le 13/10/2026 : relancer (relance n° 1)");
        }

        @Test
        void un_reglage_invalide_est_une_erreur_technique() throws IOException {
            Files.writeString(dossier.resolve("postulat.properties"), "objectif.hebdomadaire = beaucoup\n");

            Resultat r = postulat("tableau");

            assertThat(r.code()).isEqualTo(Postulat.ERREUR);
            assertThat(r.erreurs()).contains("objectif.hebdomadaire");
        }
    }

    @Nested
    class LectureDesSaisies {

        static final LocalDate AUJOURDHUI = LocalDate.of(2026, 10, 10);

        @Test
        void une_date_s_ecrit_comme_on_la_dit() {
            assertThat(Conversions.date("2026-10-01", AUJOURDHUI)).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(Conversions.date("01/10/2026", AUJOURDHUI)).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(Conversions.date("1/10/2026", AUJOURDHUI)).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThatThrownBy(() -> Conversions.date("31/02/2026", AUJOURDHUI)).isInstanceOf(TypeConversionException.class);
            assertThat(Conversions.date("hier", AUJOURDHUI)).isEqualTo(AUJOURDHUI.minusDays(1));
            assertThat(Conversions.date("Aujourd'hui", AUJOURDHUI)).isEqualTo(AUJOURDHUI);
            assertThatThrownBy(() -> Conversions.date("1er octobre", AUJOURDHUI)).isInstanceOf(TypeConversionException.class);
        }

        @Test
        void la_cle_de_comparaison_ignore_accents_casse_et_separateurs() {
            assertThat(Conversions.cle(" Offre Reçue ")).isEqualTo("offre_recue");
            assertThat(Conversions.cle("à-préparer")).isEqualTo("a_preparer");
        }
    }

    @Nested
    class LeProfil {

        static final String VALIDE = """
                identite: {nom: Camille Martin, titre: Étudiante en master data, contact: camille@exemple.test}
                experiences:
                  - id: exp-acme
                    poste: Stagiaire data analyst
                    organisation: Acme
                    periode: 2025-01 à 2025-06
                    realisations:
                      - {id: exp-acme-r1, objectif: Suivre les ventes, tache: Tableau de bord Power BI}
                      - {id: exp-acme-r2, objectif: Fiabiliser les données, tache: Contrôles SQL quotidiens}
                formations:
                  - {id: form-master, diplome: Master data, etablissement: Université Lumière, periode: 2024 à 2026}
                competences:
                  - {id: comp-sql, nom: SQL}
                """;

        @Test
        void l_initialisation_ecrit_un_gabarit_dans_le_repertoire_des_donnees() {
            Resultat r = reussi("profil", "initialiser");

            assertThat(r.sortie()).contains("Gabarit de profil écrit : " + dossier.resolve("profil.yaml"))
                    .contains("postulat profil verifier");
            assertThat(dossier.resolve("profil.yaml")).exists();
        }

        @Test
        void elle_n_ecrase_jamais_un_profil_et_propose_un_nom_derive() throws IOException {
            Files.writeString(dossier.resolve("profil.yaml"), "mes faits");

            Resultat r = postulat("profil", "initialiser");

            assertThat(r.code()).isEqualTo(Postulat.REFUSE);
            assertThat(r.erreurs()).contains("Un profil existe déjà").contains("n'a pas été modifié")
                    .contains("--fichier \"" + dossier.resolve("profil-2.yaml") + "\"");
            assertThat(Files.readString(dossier.resolve("profil.yaml"))).isEqualTo("mes faits");
        }

        @Test
        void le_gabarit_verifie_liste_chaque_a_completer_a_sa_ligne() {
            reussi("profil", "initialiser");

            Resultat r = postulat("profil", "verifier");

            assertThat(r.code()).isEqualTo(Postulat.REFUSE);
            assertThat(r.sortie()).contains("12 problèmes, dans l'ordre du fichier :")
                    .containsPattern("ligne \\d+, colonne \\d+ · identite\\.nom : à compléter")
                    .contains("Corrigez, puis relancez : postulat profil verifier");
        }

        @Test
        void un_profil_complet_est_declare_valide_avec_son_decompte() throws IOException {
            Path fichier = Files.writeString(dossier.resolve("mon-profil.yaml"), VALIDE);

            Resultat r = reussi("profil", "verifier", "--fichier", fichier.toString());

            assertThat(r.sortie()).contains("Profil valide : 5 faits : 1 expérience, 2 réalisations, 1 formation, 1 compétence.");
        }

        @Test
        void sans_profil_la_verification_renvoie_au_gabarit() {
            Resultat r = postulat("profil", "verifier");

            assertThat(r.code()).isEqualTo(Postulat.REFUSE);
            assertThat(r.erreurs()).contains("Aucun profil").contains("postulat profil initialiser");
        }

        @Test
        void une_erreur_de_syntaxe_est_situee() throws IOException {
            Files.writeString(dossier.resolve("profil.yaml"), "identite:\n  nom: A\n\ttitre: B\n");

            assertThat(postulat("profil", "verifier").sortie()).contains("1 problème,").contains("ligne 3").contains("tabulations");
        }

        @Test
        void sans_sous_commande_l_aide_du_profil_s_affiche() {
            assertThat(reussi("profil").sortie()).contains("initialiser").contains("verifier");
        }
    }

    @Nested
    class LeModele {

        @Test
        void l_essai_interroge_la_gamme_economique_et_dit_ce_qu_il_a_coute() {
            faux.repondre(new Modeles.Essai.Salut("Prêt."));

            Resultat r = reussi("modele", "essai");

            assertThat(r.sortie()).contains("Modèle claude-haiku-5-5 (gamme économique) : « Prêt. »")
                    .contains("100 jetons en entrée, 50 en sortie").contains("coût 0,0015 $")
                    .contains("L'appel est journalisé");
            assertThat(faux.demandes()).singleElement().satisfies(d -> {
                assertThat(d.gamme()).isEqualTo(Gamme.ECONOMIQUE);
                assertThat(d.tache()).isEqualTo("essai");
                assertThat(d.candidature()).isEmpty();
            });
        }

        @Test
        void l_essai_de_la_gamme_capable_suit_les_reglages() throws IOException {
            Files.writeString(dossier.resolve("postulat.properties"), "modele.capable = claude-sonnet-5-5\n");
            faux.repondre(new Modeles.Essai.Salut("Prêt."));

            Resultat r = reussi("modele", "essai", "--gamme", "capable");

            assertThat(r.sortie()).contains("Modèle claude-sonnet-5-5 (gamme capable)");
            assertThat(faux.demandes()).extracting(Demande::gamme).containsExactly(Gamme.CAPABLE);
        }

        @Test
        void les_couts_se_cumulent_par_mois_appels_echoues_compris() {
            faux.repondre(new Modeles.Essai.Salut("Un.")).repondre(new Modeles.Essai.Salut("Deux."))
                    .echouer(new ErreurModele(ErreurModele.Nature.INDISPONIBLE, "API injoignable.", null, null));
            reussi("modele", "essai");
            reussi("modele", "essai");
            postulat("modele", "essai");

            assertThat(reussi("modele", "couts").sortie())
                    .contains("Coûts du modèle de langage, par mois")
                    .contains("  2026-10   3 appels   0,0030 $")
                    .contains("  total     3 appels   0,0030 $");
        }

        @Test
        void sans_appel_les_couts_le_disent() {
            assertThat(reussi("modele", "couts").sortie()).contains("Aucun appel au modèle").contains("postulat modele essai");
        }

        @Test
        void un_echec_du_modele_est_explique_et_journalise() {
            faux.echouer(new ErreurModele(ErreurModele.Nature.CONFIGURATION, "Aucune clé d'API.", null, null));

            Resultat r = postulat("modele", "essai");

            assertThat(r.code()).isEqualTo(Postulat.ERREUR);
            assertThat(r.erreurs()).contains("Aucune clé d'API.");
            assertThat(reussi("modele", "couts").sortie()).contains("1 appel");
        }

        @Test
        void une_gamme_inconnue_est_une_saisie_invalide() {
            Resultat r = postulat("modele", "essai", "--gamme", "premium");

            assertThat(r.code()).isEqualTo(Postulat.SAISIE_INVALIDE);
            assertThat(r.erreurs()).contains("gamme inconnue « premium »");
            assertThat(faux.demandes()).isEmpty();
        }

        @Test
        void la_fiche_d_une_candidature_affiche_ce_que_le_modele_a_coute() {
            reussi("ajouter", "Acme", "Stage");

            assertThat(reussi("voir", "1").sortie()).contains("Modèle de langage : aucun appel");
        }

        @Test
        void sans_sous_commande_l_aide_du_modele_s_affiche() {
            assertThat(reussi("modele").sortie()).contains("Usage : postulat modele").contains("COMMANDE")
                    .doesNotContain("[COMMAND]").contains("essai").contains("couts");
        }
    }
}
