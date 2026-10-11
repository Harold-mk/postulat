package postulat.cli;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import postulat.cas.CasDossier;
import postulat.cas.Refus;
import postulat.libelles.Libelles;
import postulat.production.Dossier;
import postulat.stockage.Base;
import postulat.stockage.CoutModele;
import postulat.stockage.DossierGenere;
import postulat.stockage.Numerotee;
import postulat.stockage.Offre;
import postulat.stockage.VersionValidee;

/**
 * Le dossier d'une candidature en ligne de commande : le générer (UC-07), le relire, le corriger à la main
 * et le faire réévaluer (UC-08), le valider (UC-09), en verser une phrase au profil (UC-04). Ce qui se
 * fait est dans {@link CasDossier}, partagé avec la fenêtre ; ici, on ne fait que le dire.
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
        public Integer call() {
            PrintWriter out = sortie();
            CasDossier.Generation g;
            try {
                g = new CasDossier(contexte()).generer(numero, p -> {
                    out.println("Dossier de " + Libelles.titre(p.numerotee().numero(), p.numerotee().candidature()) + " : jusqu'à "
                            + p.passesMax() + " passe(s) de trois appels à " + p.modele() + ".");
                    if (p.reprises() > 0) {
                        out.println(p.reprises() + " phrase(s) de votre main reportée(s) telles quelles : le modèle n'en choisit que "
                                + "la place (RG-27).");
                    }
                    out.flush();
                }, ligne -> {
                    out.println("  " + ligne);
                    out.flush();
                });
            } catch (CasDossier.EchecCouteux e) {
                out.println("Coût engagé malgré l'échec : " + cout(e.engage()) + ".");
                throw e.origine();
            }
            out.println();
            Affichage.dossier(out, g.dossier(), false);
            out.println();
            apercus(out, numero, g.apercus());
            out.println("Coût de la génération : " + cout(g.cout()) + ".");
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
        public Integer call() {
            Numerotee n;
            DossierGenere d;
            List<VersionValidee> versions;
            Optional<Offre> offre;
            try (Base base = ouvrirBase()) {
                n = trouver(base);
                versions = base.versionsValidees(n.candidature().id());
                offre = base.offre(n.candidature().id());
                d = version == null ? CasDossier.courant(base, n) : null;
            }
            PrintWriter out = sortie();
            ZoneId zone = racine().horloge().getZone();
            if (version != null) {
                VersionValidee v = versions.stream().filter(x -> x.version() == version).findFirst()
                        .orElseThrow(() -> new Refus(versions.isEmpty() ? "La candidature n° " + numero + " n'a aucune version validée."
                                : "Pas de version " + version + " : les versions validées vont de 1 à " + versions.size() + "."));
                versionValidee(out, n, v, offre, zone);
                return 0;
            }
            out.println("Dossier de " + Libelles.titre(n.numero(), n.candidature()) + ", généré le " + date(d.genereLe(), zone)
                    + d.corrigeLe().map(le -> ", corrigé le " + date(le, zone)).orElse("") + ".");
            Path profil = racine().donnees().fichierProfil();
            if (Files.exists(profil) && !racine().donnees().lireProfil(profil).empreinte().equals(d.empreinteProfil())) {
                out.println("Le profil a changé depuis : ce dossier n'en tient pas compte. Pour régénérer : postulat dossier generer "
                        + numero);
            }
            if (!versions.isEmpty()) {
                VersionValidee derniere = versions.get(versions.size() - 1);
                out.println("Versions validées : " + versions.stream()
                        .map(v -> v.version() + " le " + date(v.valideLe(), zone)
                                + (n.candidature().versionEnvoyee().equals(Optional.of(v.version())) ? ", envoyée" : ""))
                        .collect(Collectors.joining(" · ")) + ". Pour en relire une : postulat dossier voir " + numero + " --version V");
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
            CasDossier cas = new CasDossier(contexte());
            if (reecrire) {
                out.println();
                out.println("Aperçus réécrits : " + cas.ecrireApercus(numero, d.dossier()));
            } else if (Files.exists(cas.repertoireApercus().resolve(CasDossier.fichierApercu(numero, "cv")))) {
                out.println();
                out.println("Aperçus : " + cas.repertoireApercus().resolve(CasDossier.fichierApercu(numero, "cv")) + " et "
                        + CasDossier.fichierApercu(numero, "lettre"));
            }
            return 0;
        }
    }

    /** Une version validée telle qu'elle a été figée : l'offre, le dossier, et ce qui justifie chaque phrase (CA-6.10). */
    private static void versionValidee(PrintWriter out, Numerotee n, VersionValidee v, Optional<Offre> offre, ZoneId zone) {
        boolean envoyee = n.candidature().versionEnvoyee().equals(Optional.of(v.version()));
        out.println("Version " + v.version() + " du dossier de " + Libelles.titre(n.numero(), n.candidature()) + ", validée le "
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
        v.faits().forEach(f -> out.println("  [" + f.fait() + "] " + Arrays.stream(f.contenu().split("\n"))
                .filter(x -> !x.isBlank()).collect(Collectors.joining(" · "))));
    }

    static String date(Instant instant, ZoneId zone) {
        return Libelles.date(LocalDate.ofInstant(instant, zone));
    }

    /** Une correction à la main (UC-08), sans aucun modèle : voir {@link CasDossier#corriger}. */
    abstract static class Correction extends Commande.SurCandidature {

        /** La correction, appliquée au dossier ; une {@link IllegalArgumentException} est un refus. */
        abstract Dossier appliquer(Dossier dossier);

        /** Ce qui a changé, en une ou deux lignes. */
        abstract String compteRendu(Dossier avant, Dossier apres);

        @Override
        public Integer call() {
            UnaryOperator<Dossier> correction = this::appliquer;
            CasDossier.Correction c = new CasDossier(contexte()).corriger(numero, correction);
            PrintWriter out = sortie();
            out.println(compteRendu(c.avant(), c.apres()));
            out.println("CV : une page, mise en page « " + c.cv().gabarit() + " »."
                    + (c.ats().isEmpty() ? "" : " Filtre ATS : " + c.atsAvant().map(a -> a.note() + " → ").orElse("")
                            + c.ats().get().note() + "/100."));
            c.cv().avertissements().forEach(a -> out.println("Attention : " + a));
            c.longueur().ifPresent(l -> out.println("Attention : " + l));
            out.println("L'avis du recruteur date d'avant cette correction. Pour un nouvel avis, un appel au modèle : "
                    + "postulat dossier evaluer " + numero);
            apercus(out, numero, c.apercus());
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
            CasDossier.Reevaluation r = new CasDossier(contexte()).evaluer(numero);
            PrintWriter out = sortie();
            out.println("Note globale : " + r.avant().evaluation().noteGlobale() + " → " + r.apres().evaluation().noteGlobale()
                    + "/100, seuil " + r.apres().evaluation().regles().seuil() + ".");
            Affichage.evaluation(out, r.apres(), false);
            out.println("Coût de l'avis : " + cout(r.cout()) + ".");
            return 0;
        }
    }

    @Command(name = "valider", description = "Valide le dossier : le fige, copie le contenu des faits qu'il cite, écrit le CV "
            + "et la lettre en PDF et en DOCX dans le répertoire de la candidature, et la fait passer à l'étape « prête ». "
            + "Aucun appel au modèle.")
    static final class Valider extends Commande.SurCandidature {

        @Override
        public Integer call() {
            CasDossier.Validation v = new CasDossier(contexte()).valider(numero);
            PrintWriter out = sortie();
            out.println("Dossier de " + Libelles.titre(v.numerotee().numero(), v.numerotee().candidature()) + " validé : version "
                    + v.version() + ", figée (RG-15)." + (v.version() > 1 ? " La version " + (v.version() - 1) + " reste conservée." : ""));
            if (v.passeeAPrete()) {
                out.println("Candidature passée à l'étape « prête ». Après l'envoi, fait par vous : postulat avancer " + numero
                        + " envoyée");
            }
            out.println("Répertoire : " + v.repertoire());
            out.println("  " + String.join(" · ", v.fichiers()));
            out.println("Instantanés : " + v.faits() + " fait(s) cité(s), copiés tels qu'ils sont dans le profil : le modifier "
                    + "ne changera plus ce dossier (RG-16).");
            if (v.profilChange()) {
                out.println("Attention : le profil a changé depuis la génération. Les phrases restent vérifiées contre sa version "
                        + "actuelle, dont les faits cités viennent d'être copiés.");
            }
            return 0;
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
            CasDossier.Versement v = new CasDossier(contexte()).verser(numero, phrase, new CasDossier.Demande(
                    Optional.ofNullable(dans), Optional.ofNullable(objectif), Optional.ofNullable(tache), Optional.ofNullable(resultat),
                    Optional.ofNullable(competences), Optional.ofNullable(consigne), commeCompetence, Optional.ofNullable(nom),
                    Optional.ofNullable(niveau), Optional.ofNullable(enrichir), distinct));
            PrintWriter out = sortie();
            out.println(v.enrichi() ? "Fait « " + v.verse().id() + " » enrichi dans le profil :"
                    : "Fait « " + v.verse().id() + " » ajouté au profil :");
            Arrays.stream(v.verse().texte().split("\n")).filter(l -> !l.isBlank()).forEach(l -> out.println("  " + l));
            out.println("Ancienne version du profil conservée : " + v.ancienneVersion());
            out.println("La phrase n° " + phrase + " reste de votre main dans ce dossier (RG-23) ; la prochaine génération s'appuiera "
                    + "sur ce fait, avec toutes les vérifications ordinaires.");
            out.println("Si profil.yaml est ouvert dans un éditeur, rechargez-le avant d'y retoucher.");
            return 0;
        }
    }

    // --- commun ---

    private static void apercus(PrintWriter out, long numero, CasDossier.Apercus a) {
        a.ecrits().ifPresent(e -> out.println("Aperçus : " + e));
        a.echec().ifPresent(e -> out.println("Aperçus non écrits, le dossier est enregistré. " + e
                + " Pour les réécrire sans rien régénérer : postulat dossier voir " + numero + " --apercus"));
    }

    private static String cout(CoutModele c) {
        return Libelles.appels(c.appels()) + ", " + Libelles.dollars(c.coutMicroDollars());
    }
}
