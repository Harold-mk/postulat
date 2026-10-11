package postulat.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import postulat.ia.ErreurModele;
import postulat.ia.GenerationDossier;
import postulat.production.Assertion;
import postulat.production.Ats;
import postulat.production.Competence;
import postulat.production.Dossier;
import postulat.production.Evaluation;
import postulat.production.Exigence;
import postulat.production.Fait;
import postulat.production.Instantane;
import postulat.production.NoteAts;
import postulat.production.OffreAnalysee;
import postulat.production.Profil;
import postulat.production.Realisation;
import postulat.production.Recoupement;
import postulat.production.ReglesGeneration;
import postulat.production.Tracabilite;
import postulat.production.Violation;
import postulat.rendu.ErreurRendu;
import postulat.rendu.Rendu;
import postulat.stockage.Base;
import postulat.stockage.CoutModele;
import postulat.stockage.DossierGenere;
import postulat.stockage.EcritureProfil;
import postulat.stockage.ErreurStockage;
import postulat.stockage.LectureProfil;
import postulat.stockage.Numerotee;
import postulat.stockage.Offre;
import postulat.stockage.Reglages;
import postulat.stockage.VersionValidee;
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
            + "et validés (UC-07 à UC-09).",
            subcommands = {Generer.class, Voir.class, Modifier.class, Supprimer.class, Reintroduire.class, Evaluer.class,
                Valider.class, Verser.class})
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
                if (c.etape() != Etape.A_PREPARER && c.etape() != Etape.PRETE) {
                    throw new Refus("La candidature n° " + numero + " est à l'étape " + Affichage.etape(c.etape()) + " : un dossier "
                            + "se génère à l'étape « à_préparer », ou « prête » pour une nouvelle version." + (c.etape() == Etape.REPEREE
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
                base.enregistrerDossier(c.id(), DossierGenere.nouveau(dossier, racine().horloge().instant(), lecture.empreinte(),
                        reglages.modeleCapable()));
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

        @Option(names = "--version", paramLabel = "V", description = "Relire la version validée V, telle qu'elle a été figée : "
                + "l'offre, chaque phrase et le contenu des faits qui la justifient (CA-6.10).")
        Integer version;

        @Override
        public Integer call() throws IOException {
            Numerotee n;
            DossierGenere d;
            List<VersionValidee> versions;
            Optional<Offre> offre;
            try (Base base = ouvrirBase()) {
                n = trouver(base);
                versions = base.versionsValidees(n.candidature().id());
                offre = base.offre(n.candidature().id());
                d = version == null ? courant(base, n) : null;
            }
            PrintWriter out = sortie();
            if (version != null) {
                VersionValidee v = versions.stream().filter(x -> x.version() == version).findFirst()
                        .orElseThrow(() -> new Refus(versions.isEmpty() ? "La candidature n° " + numero + " n'a aucune version validée."
                                : "Pas de version " + version + " : les versions validées vont de 1 à " + versions.size() + "."));
                versionValidee(out, n, v, offre, racine().horloge().getZone());
                return 0;
            }
            out.println("Dossier de " + Affichage.titre(n.numero(), n.candidature()) + ", généré le "
                    + Affichage.date(LocalDate.ofInstant(d.genereLe(), racine().horloge().getZone()))
                    + d.corrigeLe().map(le -> ", corrigé le " + Affichage.date(LocalDate.ofInstant(le, racine().horloge().getZone())))
                            .orElse("") + ".");
            Path profil = racine().donnees().fichierProfil();
            if (Files.exists(profil) && !racine().donnees().lireProfil(profil).empreinte().equals(d.empreinteProfil())) {
                out.println("Le profil a changé depuis : ce dossier n'en tient pas compte. Pour régénérer : postulat dossier generer "
                        + numero);
            }
            if (!versions.isEmpty()) {
                VersionValidee derniere = versions.get(versions.size() - 1);
                out.println("Versions validées : " + versions.stream()
                        .map(v -> v.version() + " le " + date(v.valideLe(), racine().horloge().getZone())
                                + (n.candidature().versionEnvoyee().equals(Optional.of(v.version())) ? ", envoyée" : ""))
                        .collect(java.util.stream.Collectors.joining(" · ")) + ". Pour en relire une : postulat dossier voir " + numero
                        + " --version V");
                out.println(derniere.dossier().dossier().equals(d.dossier())
                        ? "Ce brouillon est la version " + derniere.version() + ", validée telle quelle."
                        : "Ce brouillon diffère de la version " + derniere.version() + " : pour le valider à son tour, postulat "
                                + "dossier valider " + numero);
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

    /** Une version validée telle qu'elle a été figée : l'offre, le dossier, et ce qui justifie chaque phrase (CA-6.10). */
    private static void versionValidee(PrintWriter out, Numerotee n, VersionValidee v, Optional<Offre> offre, ZoneId zone) {
        boolean envoyee = n.candidature().versionEnvoyee().equals(Optional.of(v.version()));
        out.println("Version " + v.version() + " du dossier de " + Affichage.titre(n.numero(), n.candidature()) + ", validée le "
                + date(v.valideLe(), zone) + (envoyee ? ", c'est celle qui a été envoyée." : "."));
        Path repertoire = Path.of(v.repertoire());
        out.println(Files.isDirectory(repertoire) ? "Répertoire : " + repertoire
                : "Répertoire introuvable : " + repertoire + ". Il a été déplacé ou supprimé hors de l'application ; le contenu "
                        + "ci-dessous reste complet (UC-12 E1).");
        out.println("Produite le " + date(v.dossier().genereLe(), zone) + (v.dossier().modele().isEmpty() ? "" : " par " + v.dossier().modele())
                + ", profil " + v.dossier().empreinteProfil().substring(0, Math.min(12, v.dossier().empreinteProfil().length())) + "…");
        out.println();
        offre.ifPresent(o -> {
            Affichage.offre(out, o, zone, n.numero());
            out.println();
        });
        Affichage.dossier(out, v.dossier().dossier(), v.dossier().avisPerime());
        out.println();
        out.println("Justification : les faits cités, tels qu'ils étaient à la validation (RG-16)");
        v.faits().forEach(f -> out.println("  [" + f.fait() + "] " + java.util.Arrays.stream(f.contenu().split("\n"))
                .filter(x -> !x.isBlank()).collect(java.util.stream.Collectors.joining(" · "))));
    }

    static String date(Instant instant, ZoneId zone) {
        return Affichage.date(LocalDate.ofInstant(instant, zone));
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
                    + "  « " + apres.phrase(phrase).texte() + " »" + System.lineSeparator()
                    + "Pour en faire un fait du profil, que les prochaines générations pourront citer (CA-3.9) : postulat dossier verser "
                    + numero + " " + phrase + apres.parentPropose(phrase).map(p -> "").orElse(" --dans ID") + " --objectif \"…\"";
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

    @Command(name = "valider", description = "Valide le dossier : le fige, copie le contenu des faits qu'il cite, écrit le CV "
            + "et la lettre en PDF et en DOCX dans le répertoire de la candidature, et la fait passer à l'étape « prête ». "
            + "Aucun appel au modèle.")
    static final class Valider extends Commande.SurCandidature {

        @Override
        public Integer call() {
            PrintWriter out = sortie();
            try (Base base = ouvrirBase()) {
                Numerotee n = trouver(base);
                Candidature c = n.candidature();
                DossierGenere g = courant(base, n);
                List<VersionValidee> versions = base.versionsValidees(c.id());
                if (!versions.isEmpty() && versions.get(versions.size() - 1).dossier().dossier().equals(g.dossier())) {
                    throw new Refus("Ce dossier est déjà validé, en version " + versions.size() + " : rien n'a changé depuis.");
                }
                int version = versions.size() + 1;
                Candidature validee = c.valider(version, aujourdhui(), aujourdhui());
                GenerationDossier.Cible cible = cible(base, n);
                LectureProfil lecture = profilUtilisable();
                Profil profil = lecture.profil().orElseThrow();
                Dossier d = g.dossier();

                // E2 : ce qui se fige doit encore tenir face au profil, dont les faits vont être copiés.
                List<Violation> violations = Tracabilite.verifier(d.assertions(), profil, cible.lacunes(), cible.texteBrut());
                if (!violations.isEmpty()) {
                    throw new Refus("Validation refusée, rien n'a été enregistré (UC-09 E2) : le profil a changé depuis la génération, "
                            + "et ces phrases n'y retrouvent plus ce qui les justifiait :" + System.lineSeparator() + violations.stream()
                                    .map(x -> "  - n° " + (x.assertion() + 1) + " « " + d.assertions().get(x.assertion()).texte() + " » — "
                                            + x.regle() + " : " + x.message())
                                    .collect(java.util.stream.Collectors.joining(System.lineSeparator()))
                            + System.lineSeparator() + "Régénérez le dossier, ou réécrivez ces phrases à la main.");
                }
                List<Instantane> faits = Instantane.des(d, profil);

                String nom = d.cv().entete().nom();
                Map<String, byte[]> fichiers = new LinkedHashMap<>();
                try {
                    fichiers.put(nomDeFichier("CV " + nom) + ".pdf", Rendu.cvTelQuel(d.cv()).pdf());
                    fichiers.put(nomDeFichier("CV " + nom) + ".docx", Rendu.cvDocx(d.cv()));
                    fichiers.put(nomDeFichier("Lettre " + nom) + ".pdf", Rendu.lettre(d.lettre()).pdf());
                    fichiers.put(nomDeFichier("Lettre " + nom) + ".docx", Rendu.lettreDocx(d.lettre()));
                } catch (ErreurRendu e) {
                    throw new Refus("Validation refusée, rien n'a été enregistré : " + e.getMessage());
                }
                Path repertoire = ecrireRepertoire(n, version, fichiers);
                try {
                    base.validerDossier(validee, new VersionValidee(version, g, racine().horloge().instant(), repertoire.toString(), faits));
                } catch (RuntimeException e) {
                    effacer(repertoire);
                    throw e;
                }

                out.println("Dossier de " + Affichage.titre(n.numero(), c) + " validé : version " + version + ", figée (RG-15)."
                        + (version > 1 ? " La version " + (version - 1) + " reste conservée." : ""));
                if (c.etape() != Etape.PRETE) {
                    out.println("Candidature passée à l'étape « prête ». Après l'envoi, fait par vous : postulat avancer " + numero
                            + " envoyée");
                }
                out.println("Répertoire : " + repertoire);
                out.println("  " + String.join(" · ", fichiers.keySet()));
                out.println("Instantanés : " + faits.size() + " fait(s) cité(s), copiés tels qu'ils sont dans le profil : le modifier "
                        + "ne changera plus ce dossier (RG-16).");
                if (!lecture.empreinte().equals(g.empreinteProfil())) {
                    out.println("Attention : le profil a changé depuis la génération. Les phrases restent vérifiées contre sa version "
                            + "actuelle, dont les faits cités viennent d'être copiés.");
                }
            }
            return 0;
        }

        /**
         * Écrit les fichiers dans un répertoire provisoire, puis le renomme : on ne trouve jamais un
         * répertoire de candidature à moitié écrit. Si l'écriture échoue, rien ne reste (UC-09 E1).
         */
        private Path ecrireRepertoire(Numerotee n, int version, Map<String, byte[]> fichiers) {
            Path candidature = racine().donnees().repertoire().resolve("candidatures")
                    .resolve(nomDeFichier(n.numero() + " " + n.candidature().entreprise() + " — " + n.candidature().poste()));
            Path cible = candidature.resolve("version " + version);
            Path provisoire = candidature.resolve("version " + version + ".provisoire");
            if (Files.exists(cible)) {
                throw new Refus("Le répertoire " + cible + " existe déjà : déplacez-le, puis validez de nouveau. Rien n'a été enregistré.");
            }
            try {
                Files.createDirectories(provisoire);
                for (Map.Entry<String, byte[]> f : fichiers.entrySet()) {
                    Files.write(provisoire.resolve(f.getKey()), f.getValue());
                }
                Files.move(provisoire, cible);
                return cible;
            } catch (IOException e) {
                effacer(provisoire);
                throw new ErreurStockage("La validation a échoué entièrement, rien n'a été enregistré : ni dossier figé, ni changement "
                        + "d'étape (UC-09 E1). Le répertoire " + candidature + " n'a pas pu être écrit : " + e.getMessage(), e);
            }
        }
    }

    /**
     * Un nom que Windows accepte : sans les caractères qu'il interdit, sans espace ni point final, et
     * assez court pour ne pas buter sur la longueur des chemins.
     */
    static String nomDeFichier(String nom) {
        String propre = nom.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
        if (propre.length() > 80) {
            propre = propre.substring(0, 80).strip();
        }
        propre = propre.replaceAll("[. ]+$", "");
        return propre.isEmpty() ? "candidature" : propre;
    }

    /** Retire un répertoire que la validation vient d'écrire, et lui seul ; un échec ici ne masque pas l'erreur d'origine. */
    private static void effacer(Path repertoire) {
        try (java.util.stream.Stream<Path> chemins = Files.walk(repertoire)) {
            for (Path p : chemins.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException | java.io.UncheckedIOException ignoree) {
            // l'erreur d'origine dit déjà l'essentiel
        }
    }

    @Command(name = "verser", description = "Fait d'une phrase de votre main un fait du profil (UC-04) : une réalisation sous "
            + "une expérience ou une formation, ou une compétence. La phrase reste de votre main dans ce dossier ; la prochaine "
            + "génération s'appuiera sur le fait. Aucun appel au modèle.")
    static final class Verser extends Commande.SurCandidature {

        @Parameters(index = "1", paramLabel = "PHRASE", description = "Numéro de la phrase, de votre main.")
        int phrase;

        @Option(names = "--dans", paramLabel = "ID", description = "L'expérience ou la formation sous laquelle ranger la réalisation. "
                + "Par défaut, pour une puce du CV : celle de son bloc.")
        String dans;

        @Option(names = "--objectif", paramLabel = "TEXTE", description = "Le but de la réalisation : obligatoire pour en créer une.")
        String objectif;

        @Option(names = "--tache", paramLabel = "TEXTE", description = "Ce qui a été fait. Par défaut : la phrase elle-même.")
        String tache;

        @Option(names = "--resultat", paramLabel = "TEXTE", description = "Le résultat obtenu, chiffré s'il l'est.")
        String resultat;

        @Option(names = "--competences", paramLabel = "A,B", split = ",", description = "Les compétences mobilisées.")
        List<String> competences;

        @Option(names = "--consigne", paramLabel = "TEXTE", description = "Une consigne de présentation pour ce fait.")
        String consigne;

        @Option(names = "--competence", description = "Verser une compétence plutôt qu'une réalisation.")
        boolean commeCompetence;

        @Option(names = "--nom", paramLabel = "NOM", description = "Le nom de la compétence. Par défaut : la phrase elle-même.")
        String nom;

        @Option(names = "--niveau", paramLabel = "NIVEAU", description = "Le niveau de la compétence.")
        String niveau;

        @Option(names = "--enrichir", paramLabel = "ID", description = "Compléter ce fait existant plutôt que d'en créer un : les "
                + "champs donnés remplacent les siens (UC-04 A2).")
        String enrichir;

        @Option(names = "--distinct", description = "Créer le fait même s'il semble redire un fait existant.")
        boolean distinct;

        @Override
        public Integer call() {
            PrintWriter out = sortie();
            try (Base base = ouvrirBase()) {
                Numerotee n = trouver(base);
                DossierGenere g = courant(base, n);
                Assertion a;
                try {
                    a = g.dossier().phrase(phrase);
                    g.dossier().marquerVersee(phrase, ""); // d'emblée : une phrase de votre main, pas encore versée
                } catch (IllegalArgumentException e) {
                    throw new Refus(e.getMessage());
                }
                LectureProfil lecture = profilUtilisable();
                Profil profil = lecture.profil().orElseThrow();
                EcritureProfil ecriture = new EcritureProfil(racine().donnees().fichierProfil(),
                        racine().donnees().repertoire().resolve("profils"), racine().horloge().instant());

                Fait verse;
                EcritureProfil.Ecriture e;
                try {
                    if (enrichir != null) {
                        verse = enrichi(profil, a);
                        e = ecriture.remplacer(verse);
                    } else if (commeCompetence) {
                        verse = competence(profil, a);
                        e = ecriture.ajouterCompetence((Competence) verse);
                    } else {
                        String parent = parent(profil, g.dossier());
                        verse = realisation(profil, parent, a);
                        e = ecriture.ajouterRealisation(parent, (Realisation) verse);
                    }
                } catch (IllegalArgumentException ex) {
                    throw new Refus(ex.getMessage());
                }
                base.enregistrerDossier(n.candidature().id(), g.avec(g.dossier().marquerVersee(phrase, verse.id())));

                out.println((enrichir != null ? "Fait « " + verse.id() + " » enrichi dans le profil :" : "Fait « " + verse.id()
                        + " » ajouté au profil :"));
                Arrays.stream(verse.texte().split("\n")).filter(l -> !l.isBlank()).forEach(l -> out.println("  " + l));
                out.println("Ancienne version du profil conservée : " + e.ancienneVersion());
                out.println("La phrase n° " + phrase + " reste de votre main dans ce dossier (RG-23) ; la prochaine génération s'appuiera "
                        + "sur ce fait, avec toutes les vérifications ordinaires.");
                out.println("Si profil.yaml est ouvert dans un éditeur, rechargez-le avant d'y retoucher.");
            }
            return 0;
        }

        /** La réalisation à créer, sauf si elle redit un fait existant (UC-04 A2) : on propose alors d'enrichir celui-ci. */
        private Realisation realisation(Profil profil, String parent, Assertion a) {
            if (objectif == null || objectif.isBlank()) {
                throw new Refus("Il manque l'objectif de cette réalisation : --objectif \"…\", le but poursuivi. La tâche reprend votre "
                        + "phrase ; --tache pour la formuler autrement.");
            }
            Realisation r = new Realisation(profil.identifiantDeRealisation(parent), objectif.strip(), tache == null ? a.texte() : tache.strip(),
                    resultat == null ? "" : resultat.strip(), etiquettes(), consigne == null ? "" : consigne.strip());
            Optional<Realisation> proche = Recoupement.realisationProche(profil, parent, r.tache() + " " + r.resultat());
            if (proche.isPresent() && !distinct) {
                throw new Refus("Cette phrase semble redire le fait « " + proche.get().id() + " » : " + proche.get().tache()
                        + (proche.get().resultat().isBlank() ? "" : " ; " + proche.get().resultat()) + "." + System.lineSeparator()
                        + "Pour l'enrichir plutôt que d'en créer un second (UC-04 A2) : --enrichir " + proche.get().id()
                        + " --resultat \"…\". Pour créer quand même un fait distinct : --distinct");
            }
            return r;
        }

        private Competence competence(Profil profil, Assertion a) {
            String leNom = nom == null ? a.texte() : nom.strip();
            Optional<Competence> homonyme = Recoupement.competenceHomonyme(profil, leNom);
            if (homonyme.isPresent() && !distinct) {
                throw new Refus("La compétence « " + homonyme.get().nom() + " » est déjà au profil (" + homonyme.get().id() + "). Pour la "
                        + "compléter : --enrichir " + homonyme.get().id() + " --niveau \"…\". Pour en créer une seconde : --distinct");
            }
            return new Competence(profil.identifiantDeCompetence(leNom), leNom, niveau == null ? "" : niveau.strip(),
                    consigne == null ? "" : consigne.strip());
        }

        /** Le fait existant, avec les seuls champs donnés remplacés. */
        private Fait enrichi(Profil profil, Assertion a) {
            Fait existant = profil.fait(enrichir).orElseThrow(() -> new Refus("Aucun fait « " + enrichir + " » dans le profil."));
            return switch (existant) {
                case Realisation r -> {
                    if (objectif == null && tache == null && resultat == null && competences == null && consigne == null) {
                        throw new Refus("Dites ce que la phrase apporte au fait « " + enrichir + " » : --resultat \"…\", --tache \"…\", "
                                + "--objectif, --competences ou --consigne.");
                    }
                    yield new Realisation(r.id(), choisir(objectif, r.objectif()), choisir(tache, r.tache()), choisir(resultat, r.resultat()),
                            competences == null ? r.competences() : etiquettes(), choisir(consigne, r.consigne()));
                }
                case Competence c -> {
                    if (nom == null && niveau == null && consigne == null) {
                        throw new Refus("Dites ce que la phrase apporte à la compétence « " + enrichir + " » : --niveau, --nom ou --consigne.");
                    }
                    yield new Competence(c.id(), choisir(nom, c.nom()), choisir(niveau, c.niveau()), choisir(consigne, c.consigne()));
                }
                default -> throw new Refus("« " + enrichir + " » est une expérience ou une formation : seules une réalisation ou une "
                        + "compétence s'enrichissent depuis une phrase.");
            };
        }

        /** L'expérience ou la formation où ranger la réalisation : donnée, ou celle du bloc de la phrase. */
        private String parent(Profil profil, Dossier d) {
            String parent = dans != null ? dans.strip() : d.parentPropose(phrase).orElseThrow(() -> new Refus(
                    "Précisez sous quelle expérience ou formation ranger cette réalisation : --dans ID. Possibles : "
                            + String.join(", ", parents(profil)) + "."));
            if (!parents(profil).contains(parent)) {
                throw new Refus("« " + parent + " » n'est ni une expérience ni une formation du profil. Possibles : "
                        + String.join(", ", parents(profil)) + ".");
            }
            return parent;
        }

        private static List<String> parents(Profil profil) {
            List<String> ids = new java.util.ArrayList<>(profil.experiences().stream().map(Fait::id).toList());
            profil.formations().forEach(f -> ids.add(f.id()));
            return ids;
        }

        private List<String> etiquettes() {
            return competences == null ? List.of() : competences.stream().map(String::strip).filter(c -> !c.isEmpty()).toList();
        }

        private static String choisir(String donne, String actuel) {
            return donne == null ? actuel : donne.strip();
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
