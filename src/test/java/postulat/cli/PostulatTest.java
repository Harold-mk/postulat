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
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.TypeConversionException;
import postulat.JeuDEssai;
import postulat.ia.AnalyseOffre.FicheOffre;
import postulat.ia.Demande;
import postulat.ia.RapprochementOffre.CouvertureLue;
import postulat.ia.RapprochementOffre.RapprochementLu;
import postulat.ia.ErreurModele;
import postulat.ia.FauxModele;
import postulat.ia.Gamme;
import postulat.ia.GenerationDossier.AvisLu;
import postulat.ia.GenerationDossier.BlocGenere;
import postulat.ia.GenerationDossier.CvGenere;
import postulat.ia.GenerationDossier.LettreGeneree;
import postulat.ia.GenerationDossier.PhraseGeneree;
import postulat.ia.GenerationDossier.PuceGeneree;
import postulat.ia.GenerationDossier.SectionGeneree;

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
        // Sans couleurs, quel que soit le terminal qui lance les tests : Git Bash en demanderait.
        ligne.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
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
        void la_marque_d_ordre_ajoutee_par_powershell_est_retiree() throws IOException {
            BufferedReader avecMarque = Postulat.sansMarqueDOrdre(new BufferedReader(new StringReader("﻿Acme\noui")));
            BufferedReader sansMarque = Postulat.sansMarqueDOrdre(new BufferedReader(new StringReader("Acme")));

            assertThat(avecMarque.readLine()).isEqualTo("Acme");
            assertThat(avecMarque.readLine()).isEqualTo("oui");
            assertThat(sansMarque.readLine()).isEqualTo("Acme");
            assertThat(Conversions.sansMarqueDOrdre("﻿texte")).isEqualTo("texte");
            assertThat(Conversions.sansMarqueDOrdre("")).isEmpty();
        }

        @Test
        void la_cle_de_comparaison_ignore_accents_casse_et_separateurs() {
            assertThat(Conversions.cle(" Offre Reçue ")).isEqualTo("offre_recue");
            assertThat(Conversions.cle("à-préparer")).isEqualTo("a_preparer");
        }
    }

    @Nested
    class LeProfil {

        static final String VALIDE = JeuDEssai.PROFIL;

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

        // --- UC-02 : importer un CV existant ---

        static final String CV_TEXTE = """
                Camille Martin
                camille@exemple.test · Lyon
                Stagiaire data analyst — Acme · 2025-01 à 2025-06
                • Tableau de bord Power BI de 5 KPI pour 12 commerciaux
                Master data — Université Lumière · 2024 à 2026
                Compétences : SQL, Python
                """;

        static postulat.ia.ImportCv.ProfilLu lu() {
            return new postulat.ia.ImportCv.ProfilLu(new postulat.ia.ImportCv.IdentiteLue("Camille Martin", "", "camille@exemple.test", "Lyon"),
                    List.of(new postulat.ia.ImportCv.ExperienceLue("Stagiaire data analyst", "Acme", "2025-01 à 2025-06", "", "", List.of(
                            new postulat.ia.ImportCv.RealisationLue("", "Tableau de bord Power BI", "5 KPI pour 12 commerciaux",
                                    List.of("Power BI"))))),
                    List.of(new postulat.ia.ImportCv.FormationLue("Master data", "Université Lumière", "2024 à 2026", "", List.of())),
                    List.of(new postulat.ia.ImportCv.CompetenceLue("SQL", ""), new postulat.ia.ImportCv.CompetenceLue("Python", "")));
        }

        Path cv() throws IOException {
            return Files.writeString(dossier.resolve("mon-cv.txt"), CV_TEXTE);
        }

        @Test
        void un_cv_pre_remplit_le_profil_et_l_auteur_est_enjoint_de_tout_relire() throws IOException {
            faux.repondre(lu());

            Resultat r = reussi("profil", "importer", cv().toString());

            assertThat(r.sortie()).contains("Profil importé de « mon-cv.txt » : " + dossier.resolve("profil.yaml"))
                    .contains("5 faits : 1 expérience, 1 réalisation, 1 formation, 2 compétences.")
                    .contains("2 champ(s) à écrire vous-même, marqués « à compléter » : postulat profil verifier les liste à la ligne près.")
                    .contains("RELISEZ CHAQUE LIGNE").contains("Coût de l'import : 1 appel, 0,0015 $.")
                    .doesNotContain("existait déjà").doesNotContain("Remplacés");
            assertThat(Files.readString(dossier.resolve("profil.yaml"))).startsWith("# Profil importé de « mon-cv.txt » le 10/10/2026.");
            assertThat(faux.demandes()).extracting(postulat.ia.Demande::tache).containsExactly("import-cv");
            Resultat verifier = postulat("profil", "verifier");
            assertThat(verifier.sortie()).contains("identite.titre : à compléter")
                    .contains("experiences[0].realisations[0].objectif : à compléter");
            assertThat(reussi("modele", "couts").sortie()).contains("1 appel");
        }

        /** UC-02 A2 : un profil existant n'est jamais écrasé ; l'import s'écrit à côté. */
        @Test
        void un_profil_existant_n_est_jamais_ecrase() throws IOException {
            Files.writeString(dossier.resolve("profil.yaml"), VALIDE);
            faux.repondre(lu());

            Resultat r = reussi("profil", "importer", cv().toString());

            assertThat(Files.readString(dossier.resolve("profil.yaml"))).isEqualTo(VALIDE);
            assertThat(dossier.resolve("profil-2.yaml")).exists();
            assertThat(r.sortie()).contains("Profil importé de « mon-cv.txt » : " + dossier.resolve("profil-2.yaml"))
                    .contains("Un profil existait déjà : il n'a pas été touché (UC-02 A2).")
                    .contains("postulat profil verifier --fichier \"" + dossier.resolve("profil-2.yaml") + "\"");
        }

        @Test
        void ce_que_le_cv_ne_dit_pas_est_signale() throws IOException {
            faux.repondre(new postulat.ia.ImportCv.ProfilLu(new postulat.ia.ImportCv.IdentiteLue("Camille Martin", "", "camille@exemple.test",
                    ""), List.of(), List.of(), List.of(new postulat.ia.ImportCv.CompetenceLue("Kubernetes", ""))));

            assertThat(reussi("profil", "importer", cv().toString()).sortie())
                    .contains("Remplacés par « à compléter », faute de les lire dans le CV :" + LeDossier.FIN
                            + "  - compétence : « Kubernetes » ne se lit pas dans le CV.");
        }

        /** UC-02 A1, E1, E2 : un scan, un fichier vide, un modèle indisponible : rien n'est écrit. */
        @Test
        void sans_texte_ou_sans_modele_aucun_profil_n_est_ecrit() throws IOException {
            java.io.ByteArrayOutputStream pdf = new java.io.ByteArrayOutputStream();
            try (org.apache.pdfbox.pdmodel.PDDocument d = new org.apache.pdfbox.pdmodel.PDDocument()) {
                d.addPage(new org.apache.pdfbox.pdmodel.PDPage());
                d.save(pdf);
            }
            Path scan = Files.write(dossier.resolve("scan.pdf"), pdf.toByteArray());

            Resultat r = postulat("profil", "importer", scan.toString());

            assertThat(r.code()).isEqualTo(Postulat.REFUSE);
            assertThat(r.erreurs()).contains("c'est sans doute un scan");
            assertThat(postulat("profil", "importer", dossier.resolve("absent.pdf").toString()).erreurs()).contains("Fichier introuvable");
            faux.echouer(new ErreurModele(ErreurModele.Nature.INDISPONIBLE, "API injoignable.", null, null));
            Resultat indisponible = postulat("profil", "importer", cv().toString());
            assertThat(indisponible.code()).isEqualTo(Postulat.ERREUR);
            assertThat(indisponible.erreurs()).contains("API injoignable.");
            assertThat(dossier.resolve("profil.yaml")).doesNotExist();
            assertThat(faux.demandes()).hasSize(1);
        }
    }

    @Nested
    class LesOffres {

        static final String TEXTE = JeuDEssai.OFFRE;

        static final FicheOffre FICHE = JeuDEssai.FICHE;

        static final RapprochementLu RAPPROCHEMENT = JeuDEssai.RAPPROCHEMENT;

        Path offre() throws IOException {
            return Files.writeString(dossier.resolve("offre-acme.txt"), TEXTE);
        }

        void profil() throws IOException {
            Files.createDirectories(dossier);
            Files.writeString(dossier.resolve("profil.yaml"), LeProfil.VALIDE);
        }

        @Test
        void une_offre_analysee_et_rapprochee_devient_un_brouillon_note() throws IOException {
            profil();
            faux.repondre(FICHE).repondre(RAPPROCHEMENT);

            Resultat r = reussi("offre", "ajouter", offre().toString());

            assertThat(r.sortie())
                    .contains("Candidature #1 Acme — Stagiaire data analyst ajoutée à l'étape « repérée ».")
                    .contains("Poste : Stagiaire data analyst · Entreprise : Acme · Lieu : Lyon · Contrat : stage · Début : mars 2027")
                    .contains("    - Construire des tableaux de bord")
                    .contains("couverte [O] SQL — comp-sql, exp-acme-r1 : SQL avancé.")
                    .contains("lacune   [S] Tableau : Seul Power BI est pratiqué.")
                    .contains("Adéquation : 72/100. Bonne base.")
                    .contains("Décision : postulat avancer 1 à_préparer · postulat ecarter 1 MOTIF");
            assertThat(faux.demandes()).extracting(Demande::gamme).containsExactly(Gamme.ECONOMIQUE, Gamme.CAPABLE);
            assertThat(reussi("voir", "1").sortie()).contains("Modèle de langage : 2 appels, 0,0030 $")
                    .contains("Adéquation : 72/100").contains("texte conservé : " + TEXTE.length() + " caractères");
            assertThat(reussi("tableau").sortie()).contains("#1 Acme — Stagiaire data analyst · repérée le 10/10/2026");
        }

        @Test
        void sans_profil_l_offre_est_analysee_mais_pas_rapprochee() throws IOException {
            faux.repondre(FICHE);

            Resultat r = reussi("offre", "ajouter", offre().toString());

            assertThat(r.sortie()).contains("Rapprochement impossible : aucun profil").contains("    [O] SQL")
                    .contains("Rapprochement avec le profil non fait");
            assertThat(faux.demandes()).hasSize(1);
        }

        @Test
        void un_profil_incomplet_empeche_le_rapprochement_et_le_dit() throws IOException {
            Files.writeString(dossier.resolve("profil.yaml"), "identite:\n  nom: à compléter\n");
            faux.repondre(FICHE);

            assertThat(reussi("offre", "ajouter", offre().toString()).sortie())
                    .contains("Rapprochement impossible : le profil a").contains("postulat profil verifier");
        }

        @Test
        void modele_indisponible_l_offre_est_conservee_puis_analysee_plus_tard() throws IOException {
            faux.echouer(new ErreurModele(ErreurModele.Nature.INDISPONIBLE, "API injoignable.", null, null));

            Resultat r = reussi("offre", "ajouter", offre().toString(), "--entreprise", "Acme", "--poste", "Stage data");

            assertThat(r.sortie()).contains("Analyse impossible : API injoignable.")
                    .contains("Candidature #1 Acme — Stage data ajoutée").contains("non analysée").contains("postulat offre analyser 1");
            assertThat(reussi("voir", "1").sortie()).contains("Non analysée");

            profil();
            faux.repondre(FICHE).repondre(RAPPROCHEMENT);
            assertThat(reussi("offre", "analyser", "1").sortie()).contains("Offre analysée.").contains("Adéquation : 72/100");
            assertThat(reussi("voir", "1").sortie()).contains("Modèle de langage : 3 appels, 0,0030 $");
        }

        @Test
        void sans_entreprise_connue_elle_est_demandee() throws IOException {
            faux.echouer(new ErreurModele(ErreurModele.Nature.INDISPONIBLE, "API injoignable.", null, null));

            Resultat r = avecSaisie("Acme\nStage data\n", "offre", "ajouter", offre().toString());

            assertThat(r.code()).isZero();
            assertThat(r.sortie()).contains("Entreprise de l'offre").contains("Candidature #1 Acme — Stage data ajoutée");
        }

        @Test
        void sans_reponse_rien_n_est_enregistre_mais_l_appel_est_journalise() throws IOException {
            faux.echouer(new ErreurModele(ErreurModele.Nature.INDISPONIBLE, "API injoignable.", null, null));

            Resultat r = postulat("offre", "ajouter", offre().toString());

            assertThat(r.code()).isEqualTo(Postulat.REFUSE);
            assertThat(r.erreurs()).contains("Entreprise inconnue").contains("--entreprise").contains("Rien n'a été enregistré");
            assertThat(reussi("liste").sortie()).contains("Aucune candidature");
            assertThat(reussi("modele", "couts").sortie()).contains("1 appel");
        }

        @Test
        void un_texte_trop_court_ou_introuvable_n_est_jamais_analyse() throws IOException {
            Path court = Files.writeString(dossier.resolve("court.txt"), "Stage data chez Acme.");

            assertThat(postulat("offre", "ajouter", court.toString()).erreurs()).contains("Texte trop court");
            assertThat(postulat("offre", "ajouter", dossier.resolve("absent.txt").toString()).erreurs()).contains("Fichier introuvable");
            assertThat(faux.demandes()).isEmpty();
        }

        @Test
        void la_meme_offre_collee_deux_fois_ne_cree_ni_doublon_ni_appel() throws IOException {
            faux.repondre(FICHE);
            reussi("offre", "ajouter", offre().toString());

            Resultat r = reussi("offre", "ajouter", offre().toString());

            assertThat(r.sortie()).contains("Cette offre est déjà suivie : #1 Acme — Stagiaire data analyst");
            assertThat(faux.demandes()).hasSize(1);
        }

        @Test
        void une_offre_deja_suivie_sous_un_autre_texte_est_reconnue() throws IOException {
            reussi("ajouter", "Acme", "Stagiaire data analyst");
            faux.repondre(FICHE);

            Resultat r = reussi("offre", "ajouter", offre().toString());

            assertThat(r.sortie()).contains("déjà suivie : #1").contains("--distincte");
            assertThat(reussi("liste").sortie()).doesNotContain("#2");
        }

        @Test
        void un_rapprochement_qui_cite_un_fait_absent_est_refuse_en_entier() throws IOException {
            profil();
            faux.repondre(FICHE).repondre(new RapprochementLu(List.of(
                    new CouvertureLue(1, List.of("comp-sql"), ""), new CouvertureLue(2, List.of("comp-tableau"), "")), 90, ""));

            Resultat r = reussi("offre", "ajouter", offre().toString());

            assertThat(r.sortie()).contains("Rapprochement impossible : Rapprochement refusé").contains("RG-01").contains("comp-tableau")
                    .contains("Rapprochement avec le profil non fait");
        }

        @Test
        void un_fichier_enregistre_avec_marque_d_ordre_donne_le_meme_texte() throws IOException {
            faux.repondre(FICHE);
            reussi("offre", "ajouter", offre().toString());
            Path avecMarque = Files.writeString(dossier.resolve("offre-bom.txt"), "﻿" + TEXTE);

            assertThat(reussi("offre", "ajouter", avecMarque.toString()).sortie()).contains("déjà suivie : #1");
        }

        @Test
        void une_offre_se_lit_aussi_sur_l_entree_standard() {
            faux.repondre(FICHE);

            Resultat r = avecSaisie(TEXTE, "offre", "ajouter", "-");

            assertThat(r.code()).as(r.erreurs()).isZero();
            assertThat(faux.demandes().get(0).contenu()).contains(TEXTE);
        }

        @Test
        void ecarter_garde_le_motif_et_l_offre() throws IOException {
            profil();
            faux.repondre(FICHE).repondre(RAPPROCHEMENT);
            reussi("offre", "ajouter", offre().toString());

            assertThat(reussi("ecarter", "1", "Tableau", "manque", "trop").sortie())
                    .contains("#1 Acme — Stagiaire data analyst : écartée le 10/10/2026 : Tableau manque trop.");
            assertThat(reussi("voir", "1").sortie()).contains("écartée : Tableau manque trop").contains("Adéquation : 72/100");
        }

        @Test
        void avancer_vers_ecartee_est_refuse_faute_de_motif() {
            reussi("ajouter", "Acme", "Stage");

            Resultat r = postulat("avancer", "1", "écartée");

            assertThat(r.code()).isEqualTo(Postulat.REFUSE);
            assertThat(r.erreurs()).contains("motif");
        }

        @Test
        void une_candidature_saisie_a_la_main_n_a_rien_a_analyser() {
            reussi("ajouter", "Acme", "Stage");

            assertThat(postulat("offre", "analyser", "1").erreurs()).contains("saisie sans offre");
            assertThat(reussi("voir", "1").sortie()).contains("Offre : aucune, candidature saisie à la main.");
        }

        @Test
        void sans_sous_commande_l_aide_de_l_offre_s_affiche() {
            assertThat(reussi("offre").sortie()).contains("ajouter").contains("analyser");
        }
    }

    /** UC-07 de bout en bout : la génération, ses refus avant tout appel, son échec sans trace, sa relecture. */
    @Nested
    class LeDossier {

        /** Ce que println écrit en fin de ligne : CRLF sous Windows. */
        static final String FIN = System.lineSeparator();

        static CvGenere cv(String puce) {
            return JeuDEssai.cv(puce);
        }

        static final CvGenere CV = JeuDEssai.CV;

        static final LettreGeneree LETTRE = JeuDEssai.LETTRE;

        static AvisLu avis(int note) {
            return JeuDEssai.avis(note);
        }

        /** Une offre analysée et rapprochée, poursuivie : la candidature n° 1 attend son dossier. */
        void candidaturePrete() throws IOException {
            Files.createDirectories(dossier);
            Files.writeString(dossier.resolve("profil.yaml"), LeProfil.VALIDE);
            Files.writeString(dossier.resolve("postulat.properties"), "lettre.mots.min = 50\nlettre.mots.max = 150\n");
            faux.repondre(LesOffres.FICHE).repondre(LesOffres.RAPPROCHEMENT);
            reussi("offre", "ajouter", Files.writeString(dossier.resolve("offre-acme.txt"), LesOffres.TEXTE).toString());
            reussi("avancer", "1", "à_préparer");
        }

        @Test
        void le_dossier_genere_est_verifie_evalue_enregistre_et_rendu_en_apercu() throws IOException {
            candidaturePrete();
            faux.repondre(CV).repondre(LETTRE).repondre(avis(5));

            Resultat r = reussi("dossier", "generer", "1");

            assertThat(r.sortie())
                    .contains("Dossier de #1 Acme — Stagiaire data analyst : jusqu'à 3 passe(s) de trois appels à claude-opus-5-5.")
                    .contains("  Première passe : CV ciblé…")
                    .contains("Note globale : 87/100, seuil 75 atteint — ATS 67/100 · recruteur 100/100")
                    .contains("   3    • Suivi des ventes sous Power BI [exp-acme-r1] (pertinence 1)")
                    .contains("Lettre — Acme — Service du recrutement · Le 10 octobre 2026")
                    .contains("   6    Acme, éditeur lyonnais de logiciels de gestion, cherche un stagiaire data analyst pour six mois à "
                            + "partir de mars 2027. [offre]")
                    .contains("  retrouvées : [O] SQL").contains("  absentes : [S] Tableau")
                    .contains("  Langue 5/5 — Solide.")
                    .contains("  Corrections proposées" + FIN + "    - Resserrez l'objet")
                    .contains("  Corrections écartées : elles demandaient un fait absent du profil (RG-07)" + FIN + "    - Citez Tableau")
                    .contains("Rapport : une passe, ")
                    .contains("Aperçus : " + dossier.resolve("apercus").resolve("dossier-1-cv.pdf") + " et dossier-1-lettre.pdf")
                    .contains("Coût de la génération : 3 appels, 0,0045 $.");
            assertThat(dossier.resolve("apercus").resolve("dossier-1-cv.pdf")).exists();
            assertThat(dossier.resolve("apercus").resolve("dossier-1-lettre.pdf")).exists();
            assertThat(faux.demandes()).extracting(Demande::tache)
                    .containsExactly("analyse-offre", "rapprochement", "cv-cible", "lettre", "avis-recruteur");

            assertThat(reussi("dossier", "voir", "1").sortie())
                    .contains("Dossier de #1 Acme — Stagiaire data analyst, généré le 10/10/2026.")
                    .contains("Note globale : 87/100").contains("[exp-acme-r1, exp-acme-r2]")
                    .contains("Aperçus : ")
                    .doesNotContain("Le profil a changé");
            assertThat(reussi("voir", "1").sortie())
                    .contains("Dossier : généré le 10/10/2026, note globale 87/100, non validé. Pour le relire : postulat dossier voir 1")
                    .contains("Modèle de langage : 5 appels, 0,0075 $");

            Files.writeString(dossier.resolve("profil.yaml"), LeProfil.VALIDE.replace("nom: SQL", "nom: SQL, niveau: avancé"));
            assertThat(reussi("dossier", "voir", "1").sortie()).contains("Le profil a changé depuis : ce dossier n'en tient pas compte.");
        }

        @Test
        void les_apercus_se_reecrivent_sans_rien_regenerer() throws IOException {
            candidaturePrete();
            faux.repondre(CV).repondre(LETTRE).repondre(avis(5));
            reussi("dossier", "generer", "1");
            Path cv = dossier.resolve("apercus").resolve("dossier-1-cv.pdf");
            Files.delete(cv);

            assertThat(reussi("dossier", "voir", "1").sortie()).doesNotContain("Aperçus");
            assertThat(reussi("dossier", "voir", "1", "--apercus").sortie()).contains("Aperçus réécrits : " + cv);
            assertThat(cv).exists();
            assertThat(faux.demandes()).hasSize(5);
        }

        @Test
        void une_offre_non_poursuivie_ne_se_genere_pas() throws IOException {
            candidaturePrete();
            reussi("ajouter", "Initech", "Stage BI", "--etape", "à_préparer");
            faux.repondre(LesOffres.FICHE).repondre(LesOffres.RAPPROCHEMENT);
            reussi("offre", "ajouter", Files.writeString(dossier.resolve("offre-globex.txt"), LesOffres.TEXTE.replace("Acme", "Globex"))
                    .toString(), "--entreprise", "Globex");

            Resultat reperee = postulat("dossier", "generer", "3");
            Resultat sansOffre = postulat("dossier", "generer", "2");

            assertThat(reperee.code()).isEqualTo(Postulat.REFUSE);
            assertThat(reperee.erreurs()).contains("est à l'étape « repérée » : un dossier se génère à l'étape « à_préparer », ou "
                    + "« prête » pour une nouvelle version.")
                    .contains("postulat avancer 3 à_préparer");
            assertThat(sansOffre.code()).isEqualTo(Postulat.REFUSE);
            assertThat(sansOffre.erreurs()).contains("saisie sans offre");
            assertThat(faux.demandes()).hasSize(4);
        }

        @Test
        void un_profil_incomplet_est_refuse_avant_tout_appel() throws IOException {
            candidaturePrete();
            Files.writeString(dossier.resolve("profil.yaml"), "identite:\n  nom: à compléter\n");

            Resultat r = postulat("dossier", "generer", "1");

            assertThat(r.code()).isEqualTo(Postulat.REFUSE);
            assertThat(r.erreurs()).contains("Le profil a").contains("rien n'est produit").contains("postulat profil verifier");
            assertThat(faux.demandes()).hasSize(2);
        }

        @Test
        void une_generation_en_echec_ne_laisse_que_son_cout_au_journal() throws IOException {
            candidaturePrete();
            CvGenere fautif = cv("Conçu un tableau de bord Tableau pour 40 commerciaux");
            faux.repondre(fautif).repondre(fautif);

            Resultat r = postulat("dossier", "generer", "1");

            assertThat(r.code()).isEqualTo(Postulat.ERREUR);
            assertThat(r.sortie()).contains("Coût engagé malgré l'échec : 2 appels, 0,0030 $.");
            assertThat(r.erreurs()).contains("Génération échouée, rien n'a été enregistré").contains("RG-03").contains("RG-04");
            assertThat(postulat("dossier", "voir", "1").erreurs()).contains("n'a pas encore de dossier")
                    .contains("postulat dossier generer 1");
            assertThat(reussi("voir", "1").sortie()).contains("Dossier : aucun dossier rattaché.")
                    .contains("Modèle de langage : 4 appels");
            assertThat(dossier.resolve("apercus").resolve("dossier-1-cv.pdf")).doesNotExist();
        }

        // --- UC-08 : corriger à la main ---

        static final PhraseGeneree INTITULE = new PhraseGeneree("Stagiaire data analyst — Acme", List.of("exp-acme"), "");
        static final PhraseGeneree PRECISION = new PhraseGeneree("2025", List.of("exp-acme"), "");
        static final SectionGeneree COMPETENCES = new SectionGeneree("Compétences", List.of(),
                List.of(new PhraseGeneree("SQL", List.of("comp-sql"), "")));

        /** Un CV qui remplit la page : des puces en ont été retirées, il n'y reste pas une ligne de libre. */
        static CvGenere cvPlein() {
            List<PuceGeneree> puces = java.util.stream.IntStream.rangeClosed(1, 90)
                    .mapToObj(i -> new PuceGeneree("Suivi des ventes sous Power BI pour l'équipe commerciale, avec des indicateurs "
                            + "relus chaque semaine par les commerciaux et la direction", List.of("exp-acme-r1"), i, ""))
                    .toList();
            return new CvGenere(List.of(new SectionGeneree("Expériences", List.of(new BlocGenere(INTITULE, PRECISION, puces)), List.of()),
                    COMPETENCES));
        }

        void dossierGenere(CvGenere cv) throws IOException {
            candidaturePrete();
            faux.repondre(cv).repondre(LETTRE).repondre(avis(5));
            reussi("dossier", "generer", "1");
        }

        @Test
        void une_phrase_reecrite_devient_de_votre_main_sans_aucun_appel() throws IOException {
            dossierGenere(CV);

            Resultat r = reussi("dossier", "modifier", "1", "3", "Suivi", "hebdomadaire des ventes", "sous", "Power", "BI");

            assertThat(r.sortie())
                    .contains("Phrase n° 3 réécrite, désormais de votre main (RG-27) :" + FIN
                            + "  « Suivi hebdomadaire des ventes sous Power BI »")
                    .contains("CV : une page, mise en page « ")
                    .contains("Filtre ATS : 67 → 67/100.")
                    .contains("L'avis du recruteur date d'avant cette correction. Pour un nouvel avis, un appel au modèle : "
                            + "postulat dossier evaluer 1")
                    .contains("Aperçus : ");
            assertThat(faux.demandes()).hasSize(5);
            assertThat(reussi("dossier", "voir", "1").sortie())
                    .contains("généré le 10/10/2026, corrigé le 10/10/2026.")
                    .contains("   3    • Suivi hebdomadaire des ventes sous Power BI [de votre main] (pertinence 1)")
                    .contains("recruteur 100/100, avis antérieur à vos corrections")
                    .contains("Recruteur : 100/100 — avis antérieur à vos corrections")
                    .contains("Pour un avis sur la version actuelle : postulat dossier evaluer 1")
                    .contains("dont 1 de votre main");
        }

        @Test
        void une_correction_impossible_est_refusee_et_ne_change_rien() throws IOException {
            candidaturePrete();
            assertThat(postulat("dossier", "modifier", "1", "3", "Texte").erreurs()).contains("n'a pas encore de dossier");
            faux.repondre(CV).repondre(LETTRE).repondre(avis(5));
            reussi("dossier", "generer", "1");

            assertThat(postulat("dossier", "modifier", "1", "99", "Texte").erreurs()).contains("pas de phrase n° 99 : elles vont de 1 à 9");
            assertThat(postulat("dossier", "modifier", "1", "3", "Suivi des ventes sous Power BI").erreurs()).contains("rien n'a changé");
            assertThat(postulat("dossier", "supprimer", "1", "6").erreurs()).contains("seule phrase de sa partie");
            assertThat(postulat("dossier", "reintroduire", "1", "R1").erreurs()).contains("Aucune puce n'a été retirée");
            Resultat illisible = postulat("dossier", "reintroduire", "1", "x");
            assertThat(illisible.code()).isEqualTo(Postulat.REFUSE);
            assertThat(illisible.erreurs()).contains("« x » ne désigne pas une puce retirée");
            assertThat(reussi("dossier", "voir", "1").sortie()).doesNotContain("corrigé le").contains("dont 0 de votre main");
        }

        @Test
        void sans_son_intitule_une_experience_part_en_entier() throws IOException {
            dossierGenere(CV);

            assertThat(reussi("dossier", "supprimer", "1", "1").sortie())
                    .contains("Retirée : « Stagiaire data analyst — Acme », avec le reste de son bloc (3 phrase(s)).");
            assertThat(reussi("dossier", "voir", "1").sortie()).doesNotContain("EXPÉRIENCES").contains("   1    SQL [comp-sql]");
        }

        /** UC-08 E1 et A2 : ce qui déborderait est refusé avant d'être accepté ; la place libérée rend une puce retirée. */
        @Test
        void sur_une_page_pleine_on_ne_gagne_de_place_qu_en_en_liberant() throws IOException {
            dossierGenere(cvPlein());
            String trop = "2025, " + "six mois au sein de l'équipe data, au plus près des commerciaux et de la direction ; ".repeat(12);

            Resultat reintroduite = postulat("dossier", "reintroduire", "1", "R1");
            Resultat allongee = postulat("dossier", "modifier", "1", "2", trop);

            assertThat(reintroduite.code()).isEqualTo(Postulat.REFUSE);
            assertThat(reintroduite.erreurs()).contains("Correction refusée, rien n'a été modifié. Le CV dépasserait une page de")
                    .contains("supprimez d'abord une autre phrase");
            assertThat(allongee.code()).isEqualTo(Postulat.REFUSE);
            assertThat(reussi("dossier", "voir", "1").sortie()).doesNotContain("corrigé le");

            reussi("dossier", "supprimer", "1", "3");
            assertThat(reussi("dossier", "reintroduire", "1", "R1").sortie())
                    .contains("Remise dans son bloc : « Suivi des ventes sous Power BI pour l'équipe commerciale");
        }

        @Test
        void un_nouvel_avis_juge_la_version_corrigee() throws IOException {
            dossierGenere(CV);
            reussi("dossier", "modifier", "1", "3", "Suivi des ventes hebdomadaires");
            faux.repondre(avis(4));

            Resultat r = reussi("dossier", "evaluer", "1");

            assertThat(r.sortie()).contains("Note globale : 87 → 75/100, seuil 75.").contains("Recruteur : 80/100" + FIN)
                    .contains("Coût de l'avis : 1 appel, 0,0015 $.");
            assertThat(faux.demandes().get(5).contenu()).contains("Suivi des ventes hebdomadaires");
            assertThat(reussi("dossier", "voir", "1").sortie()).doesNotContain("avis antérieur").contains("corrigé le 10/10/2026");
        }

        @Test
        void une_regeneration_reporte_vos_phrases_telles_quelles() throws IOException {
            dossierGenere(CV);
            reussi("dossier", "modifier", "1", "3", "Suivi hebdomadaire des ventes sous Power BI");
            faux.repondre(new CvGenere(List.of(new SectionGeneree("Expériences", List.of(new BlocGenere(INTITULE, PRECISION, List.of(
                            new PuceGeneree("", List.of(), 1, "A1"),
                            new PuceGeneree("Mis en place des contrôles SQL quotidiens", List.of("exp-acme-r2"), 2, "")))), List.of()),
                    COMPETENCES)))
                    .repondre(LETTRE).repondre(avis(5));

            Resultat r = reussi("dossier", "generer", "1");

            assertThat(r.sortie()).contains("1 phrase(s) de votre main reportée(s) telles quelles : le modèle n'en choisit que la place")
                    .contains("   3    • Suivi hebdomadaire des ventes sous Power BI [de votre main] (pertinence 1)");
            assertThat(faux.demandes().get(5).contenu()).contains("- A1 (section « Expériences », bloc « Stagiaire data analyst — "
                    + "Acme ») : « Suivi hebdomadaire des ventes sous Power BI »");
            assertThat(reussi("dossier", "voir", "1").sortie()).doesNotContain("corrigé le").doesNotContain("avis antérieur");
        }

        // --- UC-09 : valider ---

        Path versionValidee(int version) {
            return dossier.resolve("candidatures").resolve("1 Acme — Stagiaire data analyst").resolve("version " + version);
        }

        @Test
        void valider_fige_le_dossier_ecrit_ses_fichiers_et_fait_passer_a_prete() throws IOException {
            dossierGenere(CV);

            Resultat r = reussi("dossier", "valider", "1");

            assertThat(r.sortie())
                    .contains("Dossier de #1 Acme — Stagiaire data analyst validé : version 1, figée (RG-15).")
                    .contains("Candidature passée à l'étape « prête ». Après l'envoi, fait par vous : postulat avancer 1 envoyée")
                    .contains("Répertoire : " + versionValidee(1))
                    .contains("CV Camille Martin.pdf · CV Camille Martin.docx · Lettre Camille Martin.pdf · Lettre Camille Martin.docx")
                    .contains("Instantanés : 5 fait(s) cité(s), copiés tels qu'ils sont dans le profil")
                    .doesNotContain("Attention");
            assertThat(postulat.rendu.Rendu.pages(Files.readAllBytes(versionValidee(1).resolve("CV Camille Martin.pdf")))).isEqualTo(1);
            assertThat(Files.readAllBytes(versionValidee(1).resolve("Lettre Camille Martin.docx"))).startsWith('P', 'K');
            assertThat(faux.demandes()).hasSize(5);
            assertThat(reussi("voir", "1").sortie()).contains("Étape : prête")
                    .contains("Dossier : version 1 validée le 10/10/2026. Pour la relire : postulat dossier voir 1 --version 1")
                    .contains("  Fichiers : " + versionValidee(1))
                    .contains("dossier validé, version 1");
            assertThat(reussi("dossier", "voir", "1").sortie()).contains("Versions validées : 1 le 10/10/2026.")
                    .contains("Ce brouillon est la version 1, validée telle quelle.");
            assertThat(postulat("dossier", "valider", "1").erreurs()).contains("déjà validé, en version 1 : rien n'a changé depuis.");
            Resultat reanalyse = postulat("offre", "analyser", "1");
            assertThat(reanalyse.code()).isEqualTo(Postulat.REFUSE);
            assertThat(reanalyse.erreurs()).contains("son offre, son analyse et son rapprochement sont figés avec lui");
        }

        @Test
        void une_nouvelle_version_se_genere_et_se_valide_a_prete_et_l_envoyee_se_reconnait() throws IOException {
            dossierGenere(CV);
            reussi("dossier", "valider", "1");
            faux.repondre(CV).repondre(LETTRE).repondre(avis(5));
            reussi("dossier", "generer", "1");
            reussi("dossier", "modifier", "1", "3", "Suivi hebdomadaire des ventes sous Power BI");
            assertThat(reussi("dossier", "voir", "1").sortie()).contains("Ce brouillon diffère de la version 1 : pour le valider à son "
                    + "tour, postulat dossier valider 1");

            Resultat r = reussi("dossier", "valider", "1");

            assertThat(r.sortie()).contains("validé : version 2, figée (RG-15). La version 1 reste conservée.")
                    .doesNotContain("Candidature passée");
            assertThat(versionValidee(1)).isDirectory();
            assertThat(versionValidee(2)).isDirectory();
            reussi("avancer", "1", "envoyée");
            assertThat(reussi("voir", "1").sortie()).contains("Dossier : version 2 validée le 10/10/2026, envoyée.");
            assertThat(reussi("dossier", "voir", "1", "--version", "2").sortie()).contains("c'est celle qui a été envoyée.")
                    .contains("[de votre main]");
            assertThat(reussi("dossier", "voir", "1", "--version", "1").sortie()).doesNotContain("envoyée").doesNotContain("[de votre main]");
            assertThat(postulat("dossier", "valider", "1").erreurs()).contains("déjà validé");
        }

        /** EN-4 : modifier le profil, puis le brouillon ; la version validée n'a pas bougé, instantanés compris. */
        @Test
        void rien_de_ce_qui_suit_ne_touche_une_version_validee() throws IOException {
            dossierGenere(CV);
            reussi("dossier", "valider", "1");
            String avant = reussi("dossier", "voir", "1", "--version", "1").sortie();

            Files.writeString(dossier.resolve("profil.yaml"),
                    LeProfil.VALIDE.replace("tache: Tableau de bord Power BI", "tache: Tableaux de bord pour la direction"));
            reussi("dossier", "modifier", "1", "3", "Suivi des ventes hebdomadaires");

            assertThat(reussi("dossier", "voir", "1", "--version", "1").sortie()).isEqualTo(avant);
            assertThat(avant).contains("Version 1 du dossier de #1 Acme — Stagiaire data analyst, validée le 10/10/2026.")
                    .contains("Produite le 10/10/2026 par claude-opus-5-5, profil ")
                    .contains("couverte [O] SQL")
                    .contains("   3    • Suivi des ventes sous Power BI [exp-acme-r1] (pertinence 1)")
                    .contains("Justification : les faits cités, tels qu'ils étaient à la validation (RG-16)")
                    .contains("  [exp-acme-r1] Suivre les ventes · Tableau de bord Power BI");
        }

        /** UC-09 E2 : ce qui se fige doit encore tenir face au profil dont on copie les faits. */
        @Test
        void un_profil_qui_ne_justifie_plus_le_dossier_empeche_de_le_valider() throws IOException {
            dossierGenere(CV);
            Files.writeString(dossier.resolve("profil.yaml"), LeProfil.VALIDE.lines()
                    .filter(l -> !l.contains("exp-acme-r2")).collect(java.util.stream.Collectors.joining("\n")));

            Resultat r = postulat("dossier", "valider", "1");

            assertThat(r.code()).isEqualTo(Postulat.REFUSE);
            assertThat(r.erreurs()).contains("Validation refusée, rien n'a été enregistré (UC-09 E2)")
                    .contains("n° 4 « Mis en place des contrôles SQL quotidiens » — RG-01").contains("Régénérez le dossier");
            assertThat(dossier.resolve("candidatures")).doesNotExist();
            assertThat(reussi("voir", "1").sortie()).contains("Étape : à_préparer").doesNotContain("dossier validé");
        }

        /** UC-09 E1 : un répertoire impossible à écrire fait tout échouer, sans rien laisser. */
        @Test
        void si_le_repertoire_ne_peut_pas_etre_ecrit_rien_n_est_enregistre() throws IOException {
            dossierGenere(CV);
            Files.writeString(dossier.resolve("candidatures"), "un fichier, là où il faudrait un répertoire");

            Resultat r = postulat("dossier", "valider", "1");

            assertThat(r.code()).isEqualTo(Postulat.ERREUR);
            assertThat(r.erreurs()).contains("La validation a échoué entièrement, rien n'a été enregistré : ni dossier figé, ni "
                    + "changement d'étape (UC-09 E1).");
            assertThat(reussi("voir", "1").sortie()).contains("Étape : à_préparer").doesNotContain("dossier validé");
            assertThat(reussi("dossier", "voir", "1").sortie()).doesNotContain("Versions validées");
        }

        /** UC-12 E1 : des fichiers déplacés hors de l'application ; le contenu, lui, reste. */
        @Test
        void un_repertoire_deplace_se_signale_et_le_contenu_reste_lisible() throws IOException {
            dossierGenere(CV);
            reussi("dossier", "valider", "1");
            Files.move(versionValidee(1), versionValidee(1).resolveSibling("envoyée à Acme"));

            assertThat(reussi("voir", "1").sortie()).contains("  Fichiers introuvables : " + versionValidee(1))
                    .contains("le contenu reste lisible (UC-12 E1)");
            assertThat(reussi("dossier", "voir", "1", "--version", "1").sortie()).contains("Répertoire introuvable : " + versionValidee(1))
                    .contains("   3    • Suivi des ventes sous Power BI");
        }

        @Test
        void on_ne_relit_que_les_versions_qui_existent_et_on_ne_valide_qu_un_dossier_genere() throws IOException {
            candidaturePrete();
            assertThat(postulat("dossier", "valider", "1").erreurs()).contains("n'a pas encore de dossier");
            faux.repondre(CV).repondre(LETTRE).repondre(avis(5));
            reussi("dossier", "generer", "1");
            assertThat(postulat("dossier", "voir", "1", "--version", "1").erreurs()).contains("n'a aucune version validée");
            reussi("dossier", "valider", "1");
            assertThat(postulat("dossier", "voir", "1", "--version", "3").erreurs()).contains("Pas de version 3 : les versions validées "
                    + "vont de 1 à 1.");
        }

        // --- UC-04 : verser une phrase au profil ---

        String profilYaml() throws IOException {
            return Files.readString(dossier.resolve("profil.yaml"));
        }

        @Test
        void une_phrase_versee_devient_un_fait_que_la_regeneration_cite() throws IOException {
            dossierGenere(CV);
            Resultat modifiee = reussi("dossier", "modifier", "1", "3", "Bâti le suivi hebdomadaire des ventes sous Power BI");
            assertThat(modifiee.sortie()).contains("Pour en faire un fait du profil, que les prochaines générations pourront citer "
                    + "(CA-3.9) : postulat dossier verser 1 3 --objectif \"…\"");

            Resultat r = reussi("dossier", "verser", "1", "3", "--objectif", "Suivre les ventes chaque semaine", "--resultat",
                    "partagé à toute l'équipe", "--competences", "Power BI, SQL");

            assertThat(r.sortie()).contains("Fait « exp-acme-r3 » ajouté au profil :" + FIN + "  Suivre les ventes chaque semaine" + FIN
                            + "  Bâti le suivi hebdomadaire des ventes sous Power BI" + FIN + "  partagé à toute l'équipe" + FIN
                            + "  Power BI, SQL")
                    .contains("Ancienne version du profil conservée : " + dossier.resolve("profils").resolve("profil-20261010-083000-"))
                    .contains("La phrase n° 3 reste de votre main dans ce dossier (RG-23)");
            assertThat(profilYaml()).isEqualTo(LeProfil.VALIDE.replace("""
                          - {id: exp-acme-r2, objectif: Fiabiliser les données, tache: Contrôles SQL quotidiens}
                    """, """
                          - {id: exp-acme-r2, objectif: Fiabiliser les données, tache: Contrôles SQL quotidiens}
                          - {id: exp-acme-r3, objectif: "Suivre les ventes chaque semaine", tache: "Bâti le suivi hebdomadaire des \
                    ventes sous Power BI", resultat: "partagé à toute l'équipe", competences: ["Power BI", "SQL"]}
                    """));
            assertThat(Files.list(dossier.resolve("profils")).toList()).singleElement()
                    .satisfies(v -> assertThat(Files.readString(v)).isEqualTo(LeProfil.VALIDE));
            assertThat(reussi("profil", "verifier").code()).isZero();
            assertThat(reussi("dossier", "voir", "1").sortie())
                    .contains("   3    • Bâti le suivi hebdomadaire des ventes sous Power BI [de votre main, versée au profil : exp-acme-r3]");
            assertThat(faux.demandes()).hasSize(5);

            faux.repondre(CV).repondre(LETTRE).repondre(avis(5));
            Resultat regeneree = reussi("dossier", "generer", "1");
            assertThat(regeneree.sortie()).doesNotContain("reportée(s) telles quelles");
            assertThat(faux.demandes().get(5).contenu()).doesNotContain("Phrases de l'auteur");
            assertThat(faux.demandes().get(5).consignes()).contains("[exp-acme-r3] Réalisation (dans exp-acme) : objectif : Suivre les "
                    + "ventes chaque semaine ; tâche : Bâti le suivi hebdomadaire des ventes sous Power BI");
        }

        @Test
        void ce_qui_se_verse_est_une_phrase_de_votre_main_rangee_quelque_part() throws IOException {
            dossierGenere(CV);
            assertThat(postulat("dossier", "verser", "1", "3", "--objectif", "x").erreurs()).contains("n'est pas de votre main");
            reussi("dossier", "modifier", "1", "3", "Bâti le suivi hebdomadaire des ventes");
            reussi("dossier", "modifier", "1", "7", "J'ai animé des ateliers internes pour diffuser la pratique des tableaux.");

            assertThat(postulat("dossier", "verser", "1", "3").erreurs()).contains("Il manque l'objectif de cette réalisation");
            assertThat(postulat("dossier", "verser", "1", "7", "--objectif", "Partager").erreurs())
                    .contains("Précisez sous quelle expérience ou formation ranger cette réalisation : --dans ID. Possibles : exp-acme, "
                            + "form-master.");
            assertThat(postulat("dossier", "verser", "1", "7", "--dans", "exp-inconnue", "--objectif", "Partager").erreurs())
                    .contains("« exp-inconnue » n'est ni une expérience ni une formation du profil.");
            reussi("dossier", "verser", "1", "7", "--dans", "exp-acme", "--objectif", "Partager la pratique", "--distinct");
            assertThat(postulat("dossier", "verser", "1", "7", "--dans", "exp-acme", "--objectif", "x").erreurs())
                    .contains("déjà versée au profil, dans le fait « exp-acme-r3 »");
            assertThat(profilYaml()).doesNotContain("Bâti le suivi");
            assertThat(Files.list(dossier.resolve("profils")).count()).isEqualTo(1);
        }

        /** UC-04 A2 : une phrase qui redit un fait se propose de l'enrichir plutôt que d'en créer un second. */
        @Test
        void une_phrase_qui_redit_un_fait_propose_de_l_enrichir() throws IOException {
            dossierGenere(CV);
            reussi("dossier", "modifier", "1", "4", "Mis en place des contrôles SQL quotidiens automatisés");

            Resultat redite = postulat("dossier", "verser", "1", "4", "--objectif", "Fiabiliser");

            assertThat(redite.code()).isEqualTo(Postulat.REFUSE);
            assertThat(redite.erreurs()).contains("Cette phrase semble redire le fait « exp-acme-r2 » : Contrôles SQL quotidiens.")
                    .contains("--enrichir exp-acme-r2 --resultat \"…\"").contains("--distinct");
            assertThat(postulat("dossier", "verser", "1", "4", "--enrichir", "exp-acme-r2").erreurs())
                    .contains("Dites ce que la phrase apporte au fait « exp-acme-r2 »");

            Resultat r = reussi("dossier", "verser", "1", "4", "--enrichir", "exp-acme-r2", "--resultat", "contrôles désormais automatisés");

            assertThat(r.sortie()).contains("Fait « exp-acme-r2 » enrichi dans le profil :");
            assertThat(profilYaml()).contains("- {id: exp-acme-r2, objectif: Fiabiliser les données, tache: Contrôles SQL quotidiens, "
                    + "resultat: \"contrôles désormais automatisés\"}");
            assertThat(reussi("dossier", "voir", "1").sortie()).contains("[de votre main, versée au profil : exp-acme-r2]");
        }

        @Test
        void une_ligne_de_competences_se_verse_comme_une_competence() throws IOException {
            dossierGenere(CV);
            reussi("dossier", "modifier", "1", "5", "SQL, Power BI");

            assertThat(postulat("dossier", "verser", "1", "5", "--competence", "--nom", "sql").erreurs())
                    .contains("La compétence « SQL » est déjà au profil (comp-sql). Pour la compléter : --enrichir comp-sql");
            assertThat(reussi("dossier", "verser", "1", "5", "--competence", "--nom", "Power BI", "--niveau", "intermédiaire").sortie())
                    .contains("Fait « comp-power-bi » ajouté au profil :");
            assertThat(profilYaml()).endsWith("""
                      - {id: comp-sql, nom: SQL}
                      - {id: comp-power-bi, nom: "Power BI", niveau: "intermédiaire"}
                    """);
        }

        @Test
        void un_nom_de_repertoire_se_plie_a_ce_que_windows_accepte() {
            assertThat(postulat.cas.CasDossier.nomDeFichier("1 A<b>c:d/e\\f|g?h*i. ")).isEqualTo("1 A b c d e f g h i");
            assertThat(postulat.cas.CasDossier.nomDeFichier(" ... ")).isEqualTo("candidature");
            assertThat(postulat.cas.CasDossier.nomDeFichier("x".repeat(100))).hasSize(80);
        }
    }

    @Nested
    class LApercuDuCv {

        void profil() throws IOException {
            Files.createDirectories(dossier);
            Files.writeString(dossier.resolve("profil.yaml"), LeProfil.VALIDE);
        }

        @Test
        void l_apercu_du_profil_tient_sur_une_page_cite_ses_faits_et_se_relit() throws IOException {
            profil();

            Resultat r = reussi("cv", "apercu");

            Path pdf = dossier.resolve("apercus").resolve("cv-apercu.pdf");
            assertThat(r.sortie()).contains("Aperçu écrit : " + pdf + " (une page, mise en page « aéré »)")
                    .contains("Traçabilité : 7 phrases, chacune tirée d'un fait du profil.")
                    .contains("Tout le profil tient sur la page.")
                    .contains("Relu comme le ferait un filtre ATS");
            assertThat(postulat.rendu.Rendu.pages(Files.readAllBytes(pdf))).isEqualTo(1);
            assertThat(postulat.rendu.Rendu.texte(Files.readAllBytes(pdf))).contains("Contrôles SQL quotidiens");
        }

        @Test
        void l_apercu_s_ecrit_ou_on_le_demande_et_remplace_le_precedent() throws IOException {
            profil();
            Path ailleurs = dossier.resolve("sorties").resolve("mon-cv.pdf");

            reussi("cv", "apercu", "--sortie", ailleurs.toString());
            reussi("cv", "apercu", "--sortie", ailleurs.toString());

            assertThat(ailleurs).exists();
            assertThat(ailleurs.resolveSibling("mon-cv.pdf.provisoire")).doesNotExist();
        }

        @Test
        void face_a_une_offre_analysee_l_apercu_recoit_sa_note_ats() throws IOException {
            profil();
            faux.repondre(LesOffres.FICHE).repondre(LesOffres.RAPPROCHEMENT);
            reussi("offre", "ajouter", Files.writeString(dossier.resolve("offre.txt"), LesOffres.TEXTE).toString());

            assertThat(reussi("cv", "apercu", "--offre", "1").sortie()).containsPattern("Note ATS face à l'offre n° 1 : \\d+/100\\.");
        }

        @Test
        void sans_profil_utilisable_ou_sans_offre_analysee_rien_n_est_rendu() throws IOException {
            assertThat(postulat("cv", "apercu").erreurs()).contains("Aucun profil");

            Files.writeString(dossier.resolve("profil.yaml"), "identite:\n  nom: à compléter\n");
            assertThat(postulat("cv", "apercu").erreurs()).contains("postulat profil verifier");

            profil();
            reussi("ajouter", "Acme", "Stage");
            assertThat(postulat("cv", "apercu", "--offre", "1").erreurs()).contains("pas d'offre analysée");
            assertThat(postulat("cv", "apercu", "--offre", "9").erreurs()).contains("Aucune candidature n° 9");
            assertThat(dossier.resolve("apercus")).doesNotExist();
        }

        @Test
        void sans_sous_commande_l_aide_du_cv_s_affiche() {
            assertThat(reussi("cv").sortie()).contains("apercu");
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
