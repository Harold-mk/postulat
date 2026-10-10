package postulat.ia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static postulat.production.Exigence.obligatoire;
import static postulat.production.Exigence.souhaitee;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import postulat.ia.ErreurModele.Nature;
import postulat.ia.GenerationDossier.AvisLu;
import postulat.ia.GenerationDossier.BlocGenere;
import postulat.ia.GenerationDossier.Cible;
import postulat.ia.GenerationDossier.CritereLu;
import postulat.ia.GenerationDossier.CvGenere;
import postulat.ia.GenerationDossier.LettreGeneree;
import postulat.ia.GenerationDossier.PhraseGeneree;
import postulat.ia.GenerationDossier.PhraseLettre;
import postulat.ia.GenerationDossier.PuceGeneree;
import postulat.ia.GenerationDossier.SectionGeneree;
import postulat.production.Competence;
import postulat.production.Couverture;
import postulat.production.Critere;
import postulat.production.Cv;
import postulat.production.Dossier;
import postulat.production.Experience;
import postulat.production.Formation;
import postulat.production.Identite;
import postulat.production.Lettre;
import postulat.production.OffreAnalysee;
import postulat.production.Origine;
import postulat.production.Profil;
import postulat.production.Rapprochement;
import postulat.production.Realisation;
import postulat.production.ReglesGeneration;

class GenerationDossierTest {

    static final Identite CAMILLE = new Identite("Camille Martin", "Étudiante en master data", "camille@exemple.test", "Lyon");

    static final Profil PROFIL = new Profil(CAMILLE,
            List.of(new Experience("exp-acme", "Stagiaire data analyst", "Acme", "2025-01 à 2025-06", "6 mois", "Lyon", "",
                    List.of(new Realisation("exp-acme-r1", "Suivre les ventes", "Tableau de bord Power BI",
                            "5 KPI utilisés par 12 commerciaux", List.of("Power BI", "SQL"),
                            "Toujours citer le nombre d'utilisateurs")))),
            List.of(new Formation("form-master", "Master data", "Université Lumière", "2024 à 2026", "", "", List.of())),
            List.of(new Competence("comp-sql", "SQL", "avancé", ""), new Competence("comp-python", "Python", "", "")));

    static final String TEXTE_OFFRE = "Globex, 250 salariés, recherche un stagiaire data analyst à Lyon pour 6 mois. "
            + "SQL et Power BI exigés ; Kubernetes serait un plus.";

    static final OffreAnalysee ANALYSE = new OffreAnalysee("Stage data analyst", "Globex", "Lyon", "stage", "", "6 mois", "",
            List.of("Construire des tableaux de bord"), List.of(obligatoire("SQL"), obligatoire("Power BI"), souhaitee("Kubernetes")));

    static final Rapprochement RAPPROCHEMENT = new Rapprochement(List.of(
            new Couverture(obligatoire("SQL"), List.of("comp-sql", "exp-acme-r1"), ""),
            new Couverture(obligatoire("Power BI"), List.of("exp-acme-r1"), ""),
            new Couverture(souhaitee("Kubernetes"), List.of(), "Jamais pratiqué.")), 70, "");

    static final UUID CANDIDATURE = UUID.randomUUID();

    static final Cible CIBLE = new Cible(CANDIDATURE, "Globex", "Stage data analyst", ANALYSE, TEXTE_OFFRE, Optional.of(RAPPROCHEMENT));

    /** Une lettre de test n'a pas besoin de 250 mots pour éprouver la fourchette. */
    static final ReglesGeneration REGLES = new ReglesGeneration(75, 40, 2, 50, 120);

    static final LocalDate JOUR = LocalDate.of(2026, 10, 10);

    // --- réponses du modèle ---

    static PhraseGeneree phrase(String texte, String... faits) {
        return new PhraseGeneree(texte, List.of(faits));
    }

    static PuceGeneree puce(String texte, int rang, String... faits) {
        return new PuceGeneree(texte, List.of(faits), rang);
    }

    static CvGenere cv(PuceGeneree... puces) {
        return new CvGenere(List.of(
                new SectionGeneree("Expériences", List.of(new BlocGenere(phrase("Stagiaire data analyst — Acme", "exp-acme"),
                        phrase("2025 · 6 mois · Lyon", "exp-acme"), List.of(puces))), List.of()),
                new SectionGeneree("Formation", List.of(new BlocGenere(phrase("Master data — Université Lumière", "form-master"),
                        phrase("", "form-master"), List.of())), null),
                new SectionGeneree("Compétences", null, List.of(phrase("SQL (avancé) · Python", "[comp-sql]", " comp-python ")))));
    }

    static CvGenere cv() {
        return cv(puce("Conçu un tableau de bord Power BI de 5 KPI pour 12 commerciaux", 1, "exp-acme-r1"));
    }

    static PhraseLettre faits(String texte, String... ids) {
        return new PhraseLettre(texte, "faits", List.of(ids));
    }

    static PhraseLettre offre(String texte) {
        return new PhraseLettre(texte, "offre", null);
    }

    static final PhraseLettre VOUS_1 = offre("Globex recherche un stagiaire data analyst à Lyon pour six mois, au sein d'une "
            + "entreprise de 250 salariés.");
    static final PhraseLettre VOUS_2 = faits("Votre exigence de SQL et de Power BI rejoint exactement ce que je pratique.",
            "comp-sql", "exp-acme-r1");
    static final PhraseLettre MOI_1 = faits("Chez Acme, j'ai conçu un tableau de bord Power BI de cinq KPI, utilisé chaque semaine "
            + "par douze commerciaux.", "exp-acme-r1", "exp-acme");
    static final PhraseLettre MOI_2 = faits("Mon master data à l'Université Lumière m'a donné des bases solides en modélisation.",
            "form-master");
    static final PhraseLettre NOUS_1 = faits("Ensemble, nous pourrions fiabiliser vos indicateurs et les rendre lisibles par tous "
            + "vos commerciaux.", "exp-acme-r1");
    static final PhraseLettre NOUS_2 = offre("Je serais heureuse d'en parler avec vous lors d'un entretien.");

    static LettreGeneree lettre() {
        return new LettreGeneree(List.of(VOUS_1, VOUS_2), List.of(MOI_1, MOI_2), List.of(NOUS_1, NOUS_2));
    }

    static AvisLu avis(int note, String... corrections) {
        return new AvisLu(Arrays.stream(Critere.values()).map(c -> new CritereLu(c.name(), note, "Justifié.")).toList(),
                List.of("Des preuves chiffrées"), List.of("Ciblage perfectible"), List.of(corrections));
    }

    final List<String> progression = new ArrayList<>();

    Dossier generer(FauxModele faux) {
        return GenerationDossier.generer(faux, PROFIL, CIBLE, REGLES, JOUR, progression::add);
    }

    ErreurModele echec(FauxModele faux) {
        try {
            generer(faux);
        } catch (ErreurModele e) {
            return e;
        }
        throw new AssertionError("un échec était attendu");
    }

    static List<String> taches(FauxModele faux) {
        return faux.demandes().stream().map(Demande::tache).toList();
    }

    @Nested
    class PremierePasse {

        @Test
        void au_dessus_du_seuil_le_dossier_est_rendu_sans_revision() {
            FauxModele faux = new FauxModele().repondre(cv()).repondre(lettre()).repondre(avis(4));

            Dossier d = generer(faux);

            assertThat(taches(faux)).containsExactly("cv-cible", "lettre", "avis-recruteur");
            assertThat(d.cv().entete()).isEqualTo(CAMILLE);
            assertThat(d.cv().sections()).extracting(Cv.Section::titre).containsExactly("Expériences", "Formation", "Compétences");
            Cv.Bloc formation = (Cv.Bloc) d.cv().sections().get(1).contenus().get(0);
            assertThat(formation.precision()).isEmpty();
            Cv.Ligne competences = (Cv.Ligne) d.cv().sections().get(2).contenus().get(0);
            assertThat(competences.assertion().origine()).isEqualTo(Origine.Faits.de("comp-sql", "comp-python"));

            Lettre l = d.lettre();
            assertThat(l.destinataire()).isEqualTo("Globex — Service du recrutement");
            assertThat(l.lieuEtDate()).isEqualTo("Lyon, le 10 octobre 2026");
            assertThat(l.objet()).isEqualTo("Candidature au poste « Stage data analyst »");
            assertThat(l.paragraphes()).hasSize(3);
            assertThat(l.paragraphes().get(0).phrases().get(0).origine()).isEqualTo(new Origine.Offre());
            assertThat(l.paragraphes().get(1).phrases().get(0).origine()).isEqualTo(Origine.Faits.de("exp-acme-r1", "exp-acme"));

            assertThat(d.evaluation().ats()).hasValueSatisfying(a -> {
                assertThat(a.note()).isEqualTo(80);
                assertThat(a.absentes()).containsExactly(souhaitee("Kubernetes"));
            });
            assertThat(d.evaluation().avis().note()).isEqualTo(80);
            assertThat(d.evaluation().noteGlobale()).isEqualTo(80);
            assertThat(d.rapport().passes()).isEqualTo(1);
            assertThat(d.rapport().retirees()).isEmpty();
            assertThat(d.rapport().avertissements()).isEmpty();
            assertThat(progression).containsExactly("Première passe : CV ciblé…", "Première passe : lettre…",
                    "Première passe : avis du recruteur…", "Première passe : note globale 80/100, seuil 75.");
        }

        @Test
        void tout_se_demande_au_modele_capable_profil_et_consignes_en_partie_stable() {
            FauxModele faux = new FauxModele().repondre(cv()).repondre(lettre()).repondre(avis(4));

            generer(faux);

            List<Demande> d = faux.demandes();
            assertThat(d).allSatisfy(x -> {
                assertThat(x.gamme()).isEqualTo(Gamme.CAPABLE);
                assertThat(x.candidature()).contains(CANDIDATURE);
                assertThat(x.consignes()).contains("[exp-acme-r1] Réalisation (dans exp-acme)")
                        .contains("(consigne : Toujours citer le nombre d'utilisateurs)");
                assertThat(x.contenu()).contains("Offre : Stage data analyst — Globex").contains("Lieu : Lyon")
                        .contains("- Construire des tableaux de bord")
                        .contains("- [obligatoire] SQL — couverte par comp-sql, exp-acme-r1")
                        .contains("- [souhaitée] Kubernetes — LACUNE : ne l'écris pas");
            });
            assertThat(d.get(0).consignes()).contains("rang de pertinence").contains("mots exacts");
            assertThat(d.get(1).consignes()).contains("Vous – Moi – Nous").contains("entre 50 et 120 mots");
            assertThat(d.get(1).contenu()).contains(TEXTE_OFFRE);
            assertThat(d.get(2).consignes()).contains("ADEQUATION (Adéquation)").contains("Ne propose jamais d'ajouter");
            assertThat(d.get(2).contenu()).contains("Conçu un tableau de bord Power BI de 5 KPI pour 12 commerciaux")
                    .contains("Madame, Monsieur,").contains("Chez Acme, j'ai conçu");
        }

        @Test
        void un_profil_incomplet_est_refuse_avant_tout_appel() {
            Profil incomplet = new Profil(new Identite("", "", "", ""), PROFIL.experiences(), PROFIL.formations(), PROFIL.competences());
            FauxModele faux = new FauxModele();

            assertThatThrownBy(() -> GenerationDossier.generer(faux, incomplet, CIBLE, REGLES, JOUR, progression::add))
                    .hasMessageContaining("UC-07 E1");
            assertThat(faux.demandes()).isEmpty();
        }

        @Test
        void une_offre_aux_exigences_non_rapprochees_ne_se_cible_pas() {
            assertThatThrownBy(() -> new Cible(CANDIDATURE, "Globex", "Stage", ANALYSE, TEXTE_OFFRE, Optional.empty()))
                    .hasMessageContaining("lacunes sont inconnues");
        }

        @Test
        void une_offre_sans_exigence_n_a_ni_lacune_ni_note_ats() {
            OffreAnalysee sansExigence = new OffreAnalysee("Stage", "", "", "", "", "", "", List.of(), List.of());
            Cible cible = new Cible(CANDIDATURE, "", "", sansExigence, TEXTE_OFFRE, Optional.empty());
            FauxModele faux = new FauxModele().repondre(cv()).repondre(lettre()).repondre(avis(4));

            Dossier d = GenerationDossier.generer(faux, PROFIL, cible, REGLES, JOUR, progression::add);

            assertThat(cible.lacunes()).isEmpty();
            assertThat(faux.demandes().get(0).contenu()).contains("L'offre n'énonce aucune exigence.");
            assertThat(d.evaluation().ats()).isEmpty();
            assertThat(d.evaluation().noteGlobale()).isEqualTo(80);
            assertThat(d.lettre().destinataire()).isEqualTo("Service du recrutement");
            assertThat(d.lettre().objet()).isEqualTo("Candidature");
        }

        @Test
        void le_premier_du_mois_s_ecrit_en_ordinal_et_le_lieu_est_facultatif() {
            assertThat(GenerationDossier.lieuEtDate(CAMILLE, LocalDate.of(2026, 10, 1))).isEqualTo("Lyon, le 1er octobre 2026");
            assertThat(GenerationDossier.lieuEtDate(new Identite("A", "B", "c@exemple.test", ""), LocalDate.of(2027, 2, 14)))
                    .isEqualTo("Le 14 février 2027");
        }
    }

    /** UC-07 E2 : une violation est redemandée une fois ; si elle persiste, la génération échoue. */
    @Nested
    class Verification {

        @Test
        void un_cv_refuse_est_redemande_une_fois_avec_ses_defauts() {
            FauxModele faux = new FauxModele()
                    .repondre(cv(puce("Conçu un tableau de bord pour 40 commerciaux", 1, "exp-acme-r1", "exp-inconnue")))
                    .repondre(cv()).repondre(lettre()).repondre(avis(4));

            Dossier d = generer(faux);

            assertThat(taches(faux)).containsExactly("cv-cible", "cv-cible", "lettre", "avis-recruteur");
            Demande reprise = faux.demandes().get(1);
            assertThat(reprise.consignes()).isEqualTo(faux.demandes().get(0).consignes());
            assertThat(reprise.contenu()).startsWith(faux.demandes().get(0).contenu())
                    .contains("Ta réponse précédente a été refusée")
                    .contains("« Conçu un tableau de bord pour 40 commerciaux » — RG-01")
                    .contains("exp-inconnue")
                    .contains("— RG-03").contains("« 40 »");
            assertThat(d.cv().puces()).hasSize(1);
        }

        @Test
        void un_cv_refuse_deux_fois_fait_echouer_la_generation_avant_la_lettre() {
            CvGenere fautif = cv(puce("Maîtrise de Kubernetes en production", 1, "exp-acme-r1"));
            FauxModele faux = new FauxModele().repondre(fautif).repondre(fautif);

            ErreurModele e = echec(faux);

            assertThat(e.nature()).isEqualTo(Nature.INVALIDE);
            assertThat(e.getMessage()).contains("Génération échouée, rien n'a été enregistré")
                    .contains("le CV ne passe toujours pas la vérification").contains("UC-07 E2").contains("RG-04")
                    .contains("Kubernetes");
            assertThat(taches(faux)).containsExactly("cv-cible", "cv-cible");
        }

        @Test
        void un_cv_mal_forme_est_refuse_comme_un_cv_infidele() {
            CvGenere rangsEnDouble = cv(puce("Conçu un tableau de bord Power BI", 1, "exp-acme-r1"),
                    puce("Suivi des ventes", 1, "exp-acme-r1"));
            CvGenere vide = new CvGenere(null);
            CvGenere sansTitre = new CvGenere(List.of(new SectionGeneree(" ", List.of(), List.of(phrase("SQL", "comp-sql")))));
            CvGenere sansIntitule = new CvGenere(List.of(new SectionGeneree("Expériences",
                    List.of(new BlocGenere(null, null, List.of())), List.of())));

            assertThat(echec(new FauxModele().repondre(rangsEnDouble).repondre(vide)).getMessage()).contains("Le CV est vide.");
            assertThat(echec(new FauxModele().repondre(sansTitre).repondre(sansIntitule)).getMessage())
                    .contains("CV mal formé : un intitulé manque.");
            FauxModele faux = new FauxModele().repondre(rangsEnDouble).repondre(sansTitre);
            echec(faux);
            assertThat(faux.demandes().get(1).contenu()).contains("Deux puces ont le rang de pertinence 1.");
        }

        @Test
        void une_lettre_hors_structure_hors_longueur_ou_d_origine_inconnue_est_redemandee() {
            LettreGeneree fautive = new LettreGeneree(List.of(VOUS_1), List.of(offre("Je suis rigoureuse."),
                    new PhraseLettre("Je parle japonais.", "auteur", List.of())), List.of());
            FauxModele faux = new FauxModele().repondre(cv()).repondre(fautive).repondre(lettre()).repondre(avis(4));

            generer(faux);

            assertThat(faux.demandes().get(2).contenu())
                    .contains("« Je suis rigoureuse. » : la partie « Moi » ne parle que du candidat")
                    .contains("origine « auteur » inconnue")
                    .contains("La partie « Nous » est vide")
                    .contains("hors de la fourchette de 50 à 120 (CA-4.4)");
        }

        @Test
        void une_phrase_sur_l_entreprise_ne_dit_que_ce_que_l_offre_dit() {
            LettreGeneree fautive = new LettreGeneree(List.of(offre("Globex et ses 900 salariés ont adopté Kubernetes."), VOUS_2),
                    List.of(MOI_1, MOI_2), List.of(NOUS_1, NOUS_2));
            FauxModele faux = new FauxModele().repondre(cv()).repondre(fautive).repondre(fautive);

            ErreurModele e = echec(faux);

            assertThat(e.getMessage()).contains("la lettre ne passe toujours pas").contains("RG-03").contains("« 900 »")
                    .contains("texte de l'offre").contains("RG-04");
        }

        @Test
        void une_lettre_trop_longue_pour_une_page_est_redemandee() {
            String longue = "Chez Acme, j'ai conçu un tableau de bord Power BI pour suivre les ventes de l'équipe, " .repeat(120);
            LettreGeneree fleuve = new LettreGeneree(List.of(VOUS_1), List.of(faits(longue, "exp-acme-r1")), List.of(NOUS_2));
            ReglesGeneration large = new ReglesGeneration(75, 40, 0, 50, 2000);
            FauxModele faux = new FauxModele().repondre(cv()).repondre(fleuve).repondre(fleuve);

            assertThatThrownBy(() -> GenerationDossier.generer(faux, PROFIL, CIBLE, large, JOUR, progression::add))
                    .hasMessageContaining("dépasse une page");
        }

        @Test
        void un_avis_hors_grille_est_redemande() {
            AvisLu horsGrille = new AvisLu(List.of(new CritereLu("Originalité", 5, "")), null, null, null);
            AvisLu accents = new AvisLu(List.of(new CritereLu("adéquation", 4, ""), new CritereLu("PREUVES", 4, ""),
                    new CritereLu("Ciblage", 4, ""), new CritereLu("CLARTÉ", 4, ""), new CritereLu(" langue ", 4, null)),
                    List.of(" ", "Clair"), null, List.of());
            FauxModele faux = new FauxModele().repondre(cv()).repondre(lettre()).repondre(horsGrille).repondre(accents);

            Dossier d = generer(faux);

            assertThat(faux.demandes().get(3).contenu()).contains("Critère « Originalité » hors de la grille")
                    .contains("ADEQUATION, PREUVES, CIBLAGE, CLARTE, LANGUE");
            assertThat(d.evaluation().avis().note()).isEqualTo(80);
            assertThat(d.evaluation().avis().forces()).containsExactly("Clair");
        }

        @Test
        void un_avis_incomplet_ou_mal_note_est_redemande() {
            AvisLu incomplet = new AvisLu(List.of(new CritereLu("PREUVES", 4, "")), List.of(), List.of(), List.of());
            AvisLu horsBornes = new AvisLu(List.of(new CritereLu("PREUVES", 9, "")), List.of(), List.of(), List.of());
            FauxModele faux = new FauxModele().repondre(cv()).repondre(lettre()).repondre(incomplet).repondre(horsBornes);

            assertThat(echec(faux).getMessage()).contains("l'avis du recruteur ne passe toujours pas").contains("Preuves = 9");
            assertThat(faux.demandes().get(3).contenu()).contains("il en manque");
        }

        @Test
        void un_modele_indisponible_en_cours_de_chaine_fait_tout_echouer() {
            FauxModele faux = new FauxModele().repondre(cv())
                    .echouer(new ErreurModele(Nature.INDISPONIBLE, "API injoignable.", null, null));

            assertThat(echec(faux).nature()).isEqualTo(Nature.INDISPONIBLE);
        }
    }

    /** CA-5.4 : sous le seuil, une révision bornée ; la meilleure version est conservée. */
    @Nested
    class Revision {

        @Test
        void sous_le_seuil_la_passe_suivante_repart_de_l_avis() {
            FauxModele faux = new FauxModele()
                    .repondre(cv()).repondre(lettre()).repondre(avis(2, "Mentionnez Kubernetes", "Chiffrez davantage"))
                    .repondre(cv()).repondre(lettre()).repondre(avis(5));

            Dossier d = generer(faux);

            assertThat(taches(faux)).hasSize(6);
            String retour = faux.demandes().get(3).contenu();
            assertThat(retour).contains("Version précédente, notée 56/100, sous le seuil de 75")
                    .contains("CV relu :").contains("Conçu un tableau de bord Power BI")
                    .contains("Lettre relue :")
                    .contains("Clarté 2/5 : Justifié.")
                    .contains("Faiblesses :\n- Ciblage perfectible")
                    .contains("Corrections à appliquer :\n- Chiffrez davantage")
                    .doesNotContain("Mentionnez Kubernetes");
            assertThat(faux.demandes().get(4).contenu()).contains("Version précédente");
            assertThat(d.rapport().passes()).isEqualTo(2);
            assertThat(d.evaluation().noteGlobale()).isEqualTo(92);
            assertThat(d.rapport().avertissements()).isEmpty();
            assertThat(progression).contains("Révision 1 : CV ciblé…", "Révision 1 : note globale 92/100, seuil 75.");
        }

        @Test
        void les_mots_de_l_offre_absents_du_cv_sont_signales_a_la_revision() {
            CvGenere sansPowerBi = cv(puce("Conçu un tableau de bord de 5 KPI pour 12 commerciaux", 1, "exp-acme-r1"));
            FauxModele faux = new FauxModele()
                    .repondre(sansPowerBi).repondre(lettre()).repondre(avis(2))
                    .repondre(cv()).repondre(lettre()).repondre(avis(5));

            generer(faux);

            assertThat(faux.demandes().get(3).contenu())
                    .contains("leurs mots exacts manquent au CV ; reprends-les tels quels : Power BI.")
                    .doesNotContain("tels quels : Power BI, Kubernetes");
        }

        @Test
        void revisions_epuisees_la_meilleure_version_est_gardee_et_le_rapport_le_dit() {
            ReglesGeneration uneRevision = new ReglesGeneration(75, 40, 1, 50, 120);
            FauxModele faux = new FauxModele()
                    .repondre(cv()).repondre(lettre()).repondre(avis(3, "Ajoutez Kubernetes", "Resserrez la lettre"))
                    .repondre(cv()).repondre(lettre()).repondre(avis(2));

            Dossier d = GenerationDossier.generer(faux, PROFIL, CIBLE, uneRevision, JOUR, progression::add);

            assertThat(taches(faux)).hasSize(6);
            assertThat(d.evaluation().noteGlobale()).isEqualTo(68);
            assertThat(d.evaluation().avis().corrections()).containsExactly("Resserrez la lettre");
            assertThat(d.rapport().correctionsEcartees()).containsExactly("Ajoutez Kubernetes");
            assertThat(d.rapport().passes()).isEqualTo(2);
            assertThat(d.rapport().avertissements().get(0)).contains("Seuil non atteint après 2 passe(s)")
                    .contains("notée 68/100 pour un seuil de 75").contains("UC-07 A2");
        }

        @Test
        void sans_revision_permise_une_seule_passe() {
            ReglesGeneration aucune = new ReglesGeneration(75, 40, 0, 50, 120);
            FauxModele faux = new FauxModele().repondre(cv()).repondre(lettre()).repondre(avis(1));

            Dossier d = GenerationDossier.generer(faux, PROFIL, CIBLE, aucune, JOUR, progression::add);

            assertThat(d.rapport().passes()).isEqualTo(1);
            assertThat(d.rapport().avertissements().get(0)).contains("après 1 passe(s)");
        }
    }

    /** RG-26 : le modèle ordonne, le code retire les puces les moins pertinentes jusqu'à ce que le CV tienne. */
    @Nested
    class UnePage {

        @Test
        void un_cv_trop_long_perd_ses_dernieres_puces_et_le_rapport_les_liste() {
            PuceGeneree[] puces = IntStream.rangeClosed(1, 90)
                    .mapToObj(i -> puce("Conçu et maintenu un tableau de bord Power BI pour suivre les ventes de l'équipe commerciale "
                            + "chaque semaine, avec des indicateurs relus par les commerciaux", i, "exp-acme-r1"))
                    .toArray(PuceGeneree[]::new);
            FauxModele faux = new FauxModele().repondre(cv(puces)).repondre(lettre()).repondre(avis(4));

            Dossier d = generer(faux);

            assertThat(d.rapport().retirees()).isNotEmpty();
            assertThat(d.rapport().retirees().get(0).rang()).isEqualTo(90);
            int gardees = d.cv().puces().size();
            assertThat(gardees + d.rapport().retirees().size()).isEqualTo(90);
            assertThat(d.cv().puces()).extracting(Cv.Puce::rang).allMatch(r -> r <= gardees);
        }
    }

    /** De bout en bout par le SDK : les trois formes imposées ont un schéma que l'API accepte et se relisent. */
    @Nested
    class FormesImposees {

        @Test
        void le_cv_la_lettre_et_l_avis_passent_par_le_sdk() throws IOException {
            try (FausseApi api = new FausseApi()) {
                ModeleAnthropic modele = new ModeleAnthropic(api.client(), "claude-haiku-5-5", "claude-opus-5-5");
                Demande demande = new Demande("cv-cible", Gamme.CAPABLE, "Consignes", "Contenu", Optional.empty());

                api.repondre(200, FausseApi.message("claude-opus-5-5", """
                        {"sections":[{"titre":"Compétences","blocs":[],"lignes":[{"texte":"SQL","faits":["comp-sql"]}]}]}""",
                        "end_turn", 900, 120, 0, 0));
                CvGenere cv = modele.demander(demande, CvGenere.class).valeur();
                assertThat(api.derniere().corps()).contains("json_schema", "sections", "blocs", "puces", "rang", "precision");

                api.repondre(200, FausseApi.message("claude-opus-5-5", """
                        {"vous":[{"texte":"Globex recrute.","origine":"offre","faits":[]}],
                         "moi":[{"texte":"Je pratique SQL.","origine":"faits","faits":["comp-sql"]}],
                         "nous":[{"texte":"Rencontrons-nous.","origine":"offre","faits":[]}]}""",
                        "end_turn", 900, 120, 0, 0));
                LettreGeneree lettre = modele.demander(demande, LettreGeneree.class).valeur();
                assertThat(api.derniere().corps()).contains("vous", "moi", "nous", "origine");

                api.repondre(200, FausseApi.message("claude-opus-5-5", """
                        {"criteres":[{"critere":"LANGUE","note":4,"justification":"Soignée."}],
                         "forces":["Clair"],"faiblesses":[],"corrections":[]}""",
                        "end_turn", 900, 120, 0, 0));
                AvisLu avis = modele.demander(demande, AvisLu.class).valeur();
                assertThat(api.derniere().corps()).contains("criteres", "corrections");

                assertThat(cv.sections().get(0).lignes().get(0).faits()).containsExactly("comp-sql");
                assertThat(lettre.moi().get(0).origine()).isEqualTo("faits");
                assertThat(avis.criteres().get(0).note()).isEqualTo(4);
            }
        }
    }
}
