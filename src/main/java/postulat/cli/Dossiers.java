package postulat.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import postulat.ia.ErreurModele;
import postulat.ia.GenerationDossier;
import postulat.production.Dossier;
import postulat.production.OffreAnalysee;
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
 * Le dossier d'une candidature : le générer, le relire (UC-07). Il n'est pas encore validé : ses PDF sont
 * des aperçus, écrits dans le répertoire des données, et remplacés à chaque génération.
 */
final class Dossiers {

    private Dossiers() {}

    @Command(name = "dossier", description = "Le dossier d'une candidature : CV ciblé et lettre, vérifiés et évalués (UC-07).",
            subcommands = {Generer.class, Voir.class})
    static final class CommandeDossier extends Commande {

        @Override
        public Integer call() {
            spec.commandLine().usage(sortie());
            return 0;
        }
    }

    @Command(name = "generer", description = "Génère le CV ciblé et la lettre, vérifie chaque phrase, les note — filtre ATS "
            + "et recruteur — et les révise sous le seuil. Remplace le dossier précédent. Appels payants au modèle capable.")
    static final class Generer extends Commande.SurCandidature {

        @Override
        public Integer call() throws IOException {
            Reglages reglages = racine().donnees().reglages();
            PrintWriter out = sortie();
            try (Base base = ouvrirBase()) {
                Numerotee n = trouver(base);
                Candidature c = n.candidature();
                GenerationDossier.Cible cible = cible(base, n);
                LectureProfil lecture = profilUtilisable();
                out.println("Dossier de " + Affichage.titre(n.numero(), c) + " : jusqu'à " + (1 + reglages.generation().revisionsMax())
                        + " passe(s) de trois appels à " + reglages.modeleCapable() + ".");
                out.flush();
                CoutModele avant = base.coutModele(c.id());
                Dossier dossier;
                try {
                    dossier = GenerationDossier.generer(modele(base, reglages), lecture.profil().orElseThrow(), cible,
                            reglages.generation(), aujourdhui(), ligne -> {
                                out.println("  " + ligne);
                                out.flush();
                            });
                } catch (ErreurModele | ErreurRendu e) {
                    out.println("Coût engagé malgré l'échec : " + cout(avant, base.coutModele(c.id())) + ".");
                    throw e;
                }
                base.enregistrerDossier(c.id(), new DossierGenere(dossier, racine().horloge().instant(), lecture.empreinte()));
                out.println();
                Affichage.dossier(out, dossier);
                out.println();
                try {
                    out.println("Aperçus : " + ecrireApercus(this, n.numero(), dossier));
                } catch (Refus e) {
                    out.println("Aperçus non écrits, le dossier est enregistré. " + e.getMessage()
                            + " Pour les réécrire sans rien régénérer : postulat dossier voir " + numero + " --apercus");
                }
                out.println("Coût de la génération : " + cout(avant, base.coutModele(c.id())) + ".");
            }
            return 0;
        }

        /** Les préconditions de UC-07, vérifiées avant tout appel : rien ne se paie pour un refus. */
        private GenerationDossier.Cible cible(Base base, Numerotee n) {
            Candidature c = n.candidature();
            if (c.etape() != Etape.A_PREPARER) {
                throw new Refus("La candidature n° " + numero + " est à l'étape " + Affichage.etape(c.etape()) + " : un dossier "
                        + "se génère à l'étape « à_préparer »." + (c.etape() == Etape.REPEREE
                                ? " Pour poursuivre cette offre : postulat avancer " + numero + " à_préparer" : ""));
            }
            Offre offre = base.offre(c.id()).orElseThrow(() -> new Refus("La candidature n° " + numero + " a été saisie sans "
                    + "offre : un dossier ciblé se rédige d'après le texte de l'offre."));
            OffreAnalysee analyse = offre.analyse().orElseThrow(() -> new Refus("L'offre n'est pas encore analysée : "
                    + "postulat offre analyser " + numero));
            if (!analyse.exigences().isEmpty() && offre.rapprochement().isEmpty()) {
                throw new Refus("L'offre n'est pas rapprochée du profil : ses lacunes, qu'aucune phrase ne doit écrire, sont "
                        + "inconnues. Pour la rapprocher : postulat offre analyser " + numero);
            }
            return new GenerationDossier.Cible(c.id(), c.entreprise(), c.poste(), analyse, offre.texteBrut(), offre.rapprochement());
        }

        private static String cout(CoutModele avant, CoutModele apres) {
            return Affichage.appels(apres.appels() - avant.appels()) + ", "
                    + Affichage.dollars(apres.coutMicroDollars() - avant.coutMicroDollars());
        }
    }

    @Command(name = "voir", description = "Relit le dossier courant : chaque phrase et son origine, l'évaluation, le rapport.")
    static final class Voir extends Commande.SurCandidature {

        @Option(names = "--apercus", description = "Réécrire aussi les PDF d'aperçu, sans rien régénérer.")
        boolean reecrire;

        @Override
        public Integer call() throws IOException {
            Optional<DossierGenere> g;
            Numerotee n;
            try (Base base = ouvrirBase()) {
                n = trouver(base);
                g = base.dossier(n.candidature().id());
            }
            if (g.isEmpty()) {
                throw new Refus("La candidature n° " + numero + " n'a pas encore de dossier. Pour le générer : postulat dossier generer "
                        + numero);
            }
            PrintWriter out = sortie();
            DossierGenere d = g.get();
            out.println("Dossier de " + Affichage.titre(n.numero(), n.candidature()) + ", généré le "
                    + Affichage.date(LocalDate.ofInstant(d.genereLe(), racine().horloge().getZone())) + ".");
            Path profil = racine().donnees().fichierProfil();
            if (Files.exists(profil) && !racine().donnees().lireProfil(profil).empreinte().equals(d.empreinteProfil())) {
                out.println("Le profil a changé depuis : ce dossier n'en tient pas compte. Pour régénérer : postulat dossier generer "
                        + numero);
            }
            out.println();
            Affichage.dossier(out, d.dossier());
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

    /** Les PDF du dossier, dans le répertoire des données : des aperçus, remplacés à chaque génération. */
    static String ecrireApercus(Commande commande, long numero, Dossier dossier) throws IOException {
        Path apercus = commande.racine().donnees().repertoire().resolve("apercus");
        Path cv = Commande.ecrirePdf(apercus.resolve(fichier(numero, "cv")), Rendu.cv(dossier.cv()).pdf());
        Commande.ecrirePdf(apercus.resolve(fichier(numero, "lettre")), Rendu.lettre(dossier.lettre()).pdf());
        return cv + " et " + fichier(numero, "lettre");
    }

    private static String fichier(long numero, String document) {
        return "dossier-" + numero + "-" + document + ".pdf";
    }
}
