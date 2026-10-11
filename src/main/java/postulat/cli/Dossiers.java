package postulat.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import postulat.ia.ErreurModele;
import postulat.ia.GenerationDossier;
import postulat.production.Ats;
import postulat.production.Dossier;
import postulat.production.Evaluation;
import postulat.production.Exigence;
import postulat.production.NoteAts;
import postulat.production.OffreAnalysee;
import postulat.production.ReglesGeneration;
import postulat.rendu.ErreurRendu;
import postulat.rendu.Rendu;
import postulat.stockage.Base;
import postulat.stockage.CoutModele;
import postulat.stockage.DossierGenere;
import postulat.stockage.LectureProfil;
import postulat.stockage.Numerotee;
import postulat.stockage.Offre;
import postulat.stockage.Reglages;
import postulat.suivi.Candidature;
import postulat.suivi.Etape;

/**
 * Le dossier d'une candidature : le générer (UC-07), le relire, le corriger à la main et le faire
 * réévaluer (UC-08). Il n'est pas encore validé : ses PDF sont des aperçus, écrits dans le répertoire des
 * données, et remplacés à chaque génération ou correction.
 */
final class Dossiers {

    private Dossiers() {}

    @Command(name = "dossier", description = "Le dossier d'une candidature : CV ciblé et lettre, vérifiés, évalués, corrigés "
            + "(UC-07, UC-08).",
            subcommands = {Generer.class, Voir.class, Modifier.class, Supprimer.class, Reintroduire.class, Evaluer.class})
    static final class CommandeDossier extends Commande {

        @Override
        public Integer call() {
            spec.commandLine().usage(sortie());
            return 0;
        }
    }

    @Command(name = "generer", description = "Génère le CV ciblé et la lettre, vérifie chaque phrase, les note — filtre ATS "
            + "et recruteur — et les révise sous le seuil. Remplace le dossier précédent, dont les phrases de votre main sont "
            + "reportées telles quelles. Appels payants au modèle capable.")
    static final class Generer extends Commande.SurCandidature {

        @Override
        public Integer call() throws IOException {
            Reglages reglages = racine().donnees().reglages();
            PrintWriter out = sortie();
            try (Base base = ouvrirBase()) {
                Numerotee n = trouver(base);
                Candidature c = n.candidature();
                if (c.etape() != Etape.A_PREPARER) {
                    throw new Refus("La candidature n° " + numero + " est à l'étape " + Affichage.etape(c.etape()) + " : un dossier "
                            + "se génère à l'étape « à_préparer »." + (c.etape() == Etape.REPEREE
                                    ? " Pour poursuivre cette offre : postulat avancer " + numero + " à_préparer" : ""));
                }
                GenerationDossier.Cible cible = cible(base, n);
                LectureProfil lecture = profilUtilisable();
                List<GenerationDossier.Reprise> reprises = base.dossier(c.id())
                        .map(g -> GenerationDossier.reprises(g.dossier())).orElse(List.of());
                out.println("Dossier de " + Affichage.titre(n.numero(), c) + " : jusqu'à " + (1 + reglages.generation().revisionsMax())
                        + " passe(s) de trois appels à " + reglages.modeleCapable() + ".");
                if (!reprises.isEmpty()) {
                    out.println(reprises.size() + " phrase(s) de votre main reportée(s) telles quelles : le modèle n'en choisit que "
                            + "la place (RG-27).");
                }
                out.flush();
                CoutModele avant = base.coutModele(c.id());
                Dossier dossier;
                try {
                    dossier = GenerationDossier.generer(modele(base, reglages), lecture.profil().orElseThrow(), cible, reprises,
                            reglages.generation(), aujourdhui(), ligne -> {
                                out.println("  " + ligne);
                                out.flush();
                            });
                } catch (ErreurModele | ErreurRendu e) {
                    out.println("Coût engagé malgré l'échec : " + cout(avant, base.coutModele(c.id())) + ".");
                    throw e;
                }
                base.enregistrerDossier(c.id(), DossierGenere.nouveau(dossier, racine().horloge().instant(), lecture.empreinte()));
                out.println();
                Affichage.dossier(out, dossier, false);
                out.println();
                apercus(this, n.numero(), dossier);
                out.println("Coût de la génération : " + cout(avant, base.coutModele(c.id())) + ".");
            }
            return 0;
        }
    }

    @Command(name = "voir", description = "Relit le dossier courant : chaque phrase numérotée et son origine, l'évaluation, le "
            + "rapport.")
    static final class Voir extends Commande.SurCandidature {

        @Option(names = "--apercus", description = "Réécrire aussi les PDF d'aperçu, sans rien régénérer.")
        boolean reecrire;

        @Override
        public Integer call() throws IOException {
            Numerotee n;
            DossierGenere d;
            try (Base base = ouvrirBase()) {
                n = trouver(base);
                d = courant(base, n);
            }
            PrintWriter out = sortie();
            out.println("Dossier de " + Affichage.titre(n.numero(), n.candidature()) + ", généré le "
                    + Affichage.date(LocalDate.ofInstant(d.genereLe(), racine().horloge().getZone()))
                    + d.corrigeLe().map(le -> ", corrigé le " + Affichage.date(LocalDate.ofInstant(le, racine().horloge().getZone())))
                            .orElse("") + ".");
            Path profil = racine().donnees().fichierProfil();
            if (Files.exists(profil) && !racine().donnees().lireProfil(profil).empreinte().equals(d.empreinteProfil())) {
                out.println("Le profil a changé depuis : ce dossier n'en tient pas compte. Pour régénérer : postulat dossier generer "
                        + numero);
            }
            out.println();
            Affichage.dossier(out, d.dossier(), d.avisPerime());
            if (d.avisPerime()) {
                out.println("  Pour un avis sur la version actuelle : postulat dossier evaluer " + numero);
            }
            Path apercus = racine().donnees().repertoire().resolve("apercus");
            if (reecrire) {
                out.println();
                out.println("Aperçus réécrits : " + ecrireApercus(this, numero, d.dossier()));
            } else if (Files.exists(apercus.resolve(fichier(numero, "cv")))) {
                out.println();
                out.println("Aperçus : " + apercus.resolve(fichier(numero, "cv")) + " et " + fichier(numero, "lettre"));
            }
            return 0;
        }
    }

    /**
     * Une correction à la main (UC-08) : elle n'appelle aucun modèle. Le dossier corrigé est re-rendu ; s'il
     * ne tient plus sur une page, la correction est refusée avant d'être acceptée (E1). Sinon la note ATS
     * est recalculée, et l'avis du recruteur, qui jugeait la version précédente, est déclaré périmé.
     */
    abstract static class Correction extends Commande.SurCandidature {

        /** La correction, appliquée au dossier ; une {@link IllegalArgumentException} est un refus. */
        abstract Dossier appliquer(Dossier dossier);

        /** Ce qui a changé, en une ou deux lignes. */
        abstract String compteRendu(Dossier avant, Dossier apres);

        @Override
        public Integer call() throws IOException {
            PrintWriter out = sortie();
            Dossier evalue;
            try (Base base = ouvrirBase()) {
                Numerotee n = trouver(base);
                UUID id = n.candidature().id();
                DossierGenere g = courant(base, n);
                Dossier corrige;
                try {
                    corrige = appliquer(g.dossier());
                } catch (IllegalArgumentException e) {
                    throw new Refus(e.getMessage());
                }
                Rendu.Resultat cv;
                try {
                    cv = Rendu.cvTelQuel(corrige.cv());
                    Rendu.lettre(corrige.lettre());
                } catch (ErreurRendu e) {
                    throw new Refus("Correction refusée, rien n'a été modifié. " + e.getMessage()
                            + " Raccourcissez, ou supprimez d'abord une autre phrase.");
                }
                List<Exigence> exigences = base.offre(id).flatMap(Offre::analyse).map(OffreAnalysee::exigences).orElse(List.of());
                Optional<NoteAts> ats = Ats.noter(cv.texte(), exigences);
                Evaluation avant = g.dossier().evaluation();
                evalue = new Dossier(corrige.cv(), corrige.lettre(), new Evaluation(ats, avant.avis(), avant.regles()), corrige.rapport());
                base.enregistrerDossier(id, g.corrige(evalue, racine().horloge().instant()));

                out.println(compteRendu(g.dossier(), evalue));
                out.println("CV : une page, mise en page « " + cv.gabarit() + " »."
                        + (ats.isEmpty() ? "" : " Filtre ATS : " + avant.ats().map(a -> a.note() + " → ").orElse("")
                                + ats.get().note() + "/100."));
                cv.avertissements().forEach(a -> out.println("Attention : " + a));
                ReglesGeneration regles = avant.regles();
                int mots = evalue.lettre().mots();
                if (mots < regles.motsMin() || mots > regles.motsMax()) {
                    out.println("Attention : la lettre compte " + mots + " mots, hors de la fourchette de " + regles.motsMin() + " à "
                            + regles.motsMax() + " (CA-4.4).");
                }
                out.println("L'avis du recruteur date d'avant cette correction. Pour un nouvel avis, un appel au modèle : "
                        + "postulat dossier evaluer " + numero);
            }
            apercus(this, numero, evalue);
            return 0;
        }
    }

    @Command(name = "modifier", description = "Réécrit une phrase du dossier, désignée par son numéro (voir : postulat dossier "
            + "voir). Elle devient de votre main : la vérification ne la rejette pas, et aucune génération ne la réécrira.")
    static final class Modifier extends Correction {

        @Parameters(index = "1", paramLabel = "PHRASE", description = "Numéro de la phrase.")
        int phrase;

        @Parameters(index = "2..*", arity = "1..*", paramLabel = "TEXTE", description = "Le nouveau texte, entre guillemets ou non.")
        List<String> mots;

        @Override
        Dossier appliquer(Dossier dossier) {
            return dossier.corriger(phrase, String.join(" ", mots));
        }

        @Override
        String compteRendu(Dossier avant, Dossier apres) {
            return "Phrase n° " + phrase + " réécrite, désormais de votre main (RG-27) :" + System.lineSeparator()
                    + "  « " + apres.phrase(phrase).texte() + " »";
        }
    }

    @Command(name = "supprimer", description = "Retire une phrase du dossier. Retirer l'intitulé d'une expérience retire "
            + "l'expérience entière.")
    static final class Supprimer extends Correction {

        @Parameters(index = "1", paramLabel = "PHRASE", description = "Numéro de la phrase.")
        int phrase;

        @Override
        Dossier appliquer(Dossier dossier) {
            return dossier.supprimer(phrase);
        }

        @Override
        String compteRendu(Dossier avant, Dossier apres) {
            int parties = avant.assertions().size() - apres.assertions().size();
            return "Retirée : « " + avant.phrase(phrase).texte() + " »"
                    + (parties > 1 ? ", avec le reste de son bloc (" + (parties - 1) + " phrase(s))." : ".");
        }
    }

    @Command(name = "reintroduire", description = "Remet dans son bloc une puce retirée faute de place (R1, R2… dans le "
            + "rapport), si la page le permet.")
    static final class Reintroduire extends Correction {

        @Parameters(index = "1", paramLabel = "RETIREE", description = "Numéro de la puce retirée : 1 pour R1.")
        String retiree;

        @Override
        Dossier appliquer(Dossier dossier) {
            return dossier.reintroduire(numeroDeRetiree());
        }

        @Override
        String compteRendu(Dossier avant, Dossier apres) {
            return "Remise dans son bloc : « " + avant.rapport().retirees().get(numeroDeRetiree() - 1).puce().assertion().texte() + " »";
        }

        /** « R2 », « r2 » ou « 2 ». */
        private int numeroDeRetiree() {
            try {
                return Integer.parseInt(retiree.strip().replaceFirst("^[Rr]", ""));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("« " + retiree + " » ne désigne pas une puce retirée : R1, R2…");
            }
        }
    }

    @Command(name = "evaluer", description = "Demande un nouvel avis du recruteur sur le dossier tel qu'il est, après vos "
            + "corrections. Un appel payant au modèle capable.")
    static final class Evaluer extends Commande.SurCandidature {

        @Override
        public Integer call() {
            Reglages reglages = racine().donnees().reglages();
            PrintWriter out = sortie();
            try (Base base = ouvrirBase()) {
                Numerotee n = trouver(base);
                UUID id = n.candidature().id();
                DossierGenere g = courant(base, n);
                GenerationDossier.Cible cible = cible(base, n);
                LectureProfil lecture = profilUtilisable();
                CoutModele avant = base.coutModele(id);
                Dossier reevalue = GenerationDossier.reevaluer(modele(base, reglages), lecture.profil().orElseThrow(), cible, g.dossier());
                base.enregistrerDossier(id, g.reevalue(reevalue));
                out.println("Note globale : " + g.dossier().evaluation().noteGlobale() + " → " + reevalue.evaluation().noteGlobale()
                        + "/100, seuil " + reevalue.evaluation().regles().seuil() + ".");
                Affichage.evaluation(out, reevalue, false);
                out.println("Coût de l'avis : " + cout(avant, base.coutModele(id)) + ".");
            }
            return 0;
        }
    }

    // --- commun ---

    /** Le dossier courant, ou un refus qui dit comment en obtenir un. */
    static DossierGenere courant(Base base, Numerotee n) {
        return base.dossier(n.candidature().id()).orElseThrow(() -> new Refus("La candidature n° " + n.numero()
                + " n'a pas encore de dossier. Pour le générer : postulat dossier generer " + n.numero()));
    }

    /** L'offre visée, analysée et rapprochée du profil : vérifiée avant tout appel, rien ne se paie pour un refus. */
    static GenerationDossier.Cible cible(Base base, Numerotee n) {
        Candidature c = n.candidature();
        Offre offre = base.offre(c.id()).orElseThrow(() -> new Refus("La candidature n° " + n.numero() + " a été saisie sans "
                + "offre : un dossier ciblé se rédige d'après le texte de l'offre."));
        OffreAnalysee analyse = offre.analyse().orElseThrow(() -> new Refus("L'offre n'est pas encore analysée : "
                + "postulat offre analyser " + n.numero()));
        if (!analyse.exigences().isEmpty() && offre.rapprochement().isEmpty()) {
            throw new Refus("L'offre n'est pas rapprochée du profil : ses lacunes, qu'aucune phrase ne doit écrire, sont "
                    + "inconnues. Pour la rapprocher : postulat offre analyser " + n.numero());
        }
        return new GenerationDossier.Cible(c.id(), c.entreprise(), c.poste(), analyse, offre.texteBrut(), offre.rapprochement());
    }

    /** Réécrit les aperçus ; un PDF ouvert dans un lecteur n'annule pas ce qui vient d'être enregistré. */
    static void apercus(Commande commande, long numero, Dossier dossier) throws IOException {
        PrintWriter out = commande.sortie();
        try {
            out.println("Aperçus : " + ecrireApercus(commande, numero, dossier));
        } catch (Refus e) {
            out.println("Aperçus non écrits, le dossier est enregistré. " + e.getMessage()
                    + " Pour les réécrire sans rien régénérer : postulat dossier voir " + numero + " --apercus");
        }
    }

    /** Les PDF du dossier, dans le répertoire des données : des aperçus, remplacés à chaque génération. */
    static String ecrireApercus(Commande commande, long numero, Dossier dossier) throws IOException {
        Path apercus = commande.racine().donnees().repertoire().resolve("apercus");
        Path cv = Commande.ecrirePdf(apercus.resolve(fichier(numero, "cv")), Rendu.cvTelQuel(dossier.cv()).pdf());
        Commande.ecrirePdf(apercus.resolve(fichier(numero, "lettre")), Rendu.lettre(dossier.lettre()).pdf());
        return cv + " et " + fichier(numero, "lettre");
    }

    private static String fichier(long numero, String document) {
        return "dossier-" + numero + "-" + document + ".pdf";
    }

    private static String cout(CoutModele avant, CoutModele apres) {
        return Affichage.appels(apres.appels() - avant.appels()) + ", "
                + Affichage.dollars(apres.coutMicroDollars() - avant.coutMicroDollars());
    }
}
