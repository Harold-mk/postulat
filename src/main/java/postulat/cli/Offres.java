package postulat.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import postulat.libelles.Libelles;
import postulat.ia.AnalyseOffre;
import postulat.ia.AppelModele;
import postulat.ia.Demande;
import postulat.ia.ErreurModele;
import postulat.ia.Modele;
import postulat.ia.ModeleJournalise;
import postulat.ia.RapprochementOffre;
import postulat.production.OffreAnalysee;
import postulat.production.Profil;
import postulat.production.Rapprochement;
import postulat.stockage.Base;
import postulat.stockage.LectureProfil;
import postulat.stockage.Numerotee;
import postulat.stockage.Offre;
import postulat.stockage.Reglages;
import postulat.suivi.Candidature;
import postulat.suivi.Etape;

/**
 * Soumettre une offre et la comprendre avant d'y investir du temps (UC-05) : analyse, rapprochement
 * avec le profil, note d'adéquation. La décision — poursuivre ou écarter — reste au candidat (UC-06).
 */
final class Offres {

    /** En deçà, ce n'est pas une offre : jamais d'analyse d'une page vide (UC-05 E1). */
    static final int LONGUEUR_MINIMALE = 150;

    static final String ENTREE_STANDARD = "entrée standard";

    private Offres() {}

    @Command(name = "offre", description = "Soumettre une offre, la faire analyser et rapprocher du profil (UC-05).",
            subcommands = {Ajouter.class, Analyser.class})
    static final class CommandeOffre extends Commande {

        @Override
        public Integer call() {
            spec.commandLine().usage(sortie());
            return 0;
        }
    }

    @Command(name = "ajouter", description = "Soumet le texte d'une offre : analyse, rapprochement avec le profil, note "
            + "d'adéquation. La candidature entre à l'étape « repérée » (UC-05).")
    static final class Ajouter extends Commande {

        @Parameters(index = "0", paramLabel = "FICHIER",
                description = "Fichier texte de l'offre (UTF-8), ou « - » pour l'entrée standard.")
        String source;

        @Option(names = "--entreprise", paramLabel = "NOM", description = "L'entreprise, si l'offre ne la nomme pas ou pour la corriger.")
        String entreprise;

        @Option(names = "--poste", paramLabel = "INTITULE", description = "Le poste, à défaut de l'analyse ou pour le corriger.")
        String poste;

        @Option(names = "--le", paramLabel = "DATE", description = "Jour où l'offre a été repérée. Par défaut : aujourd'hui.")
        LocalDate le;

        @Option(names = "--distincte", description = "Ajouter même si une candidature porte la même entreprise et le même poste.")
        boolean distincte;

        @Override
        public Integer call() {
            String texte = lireTexte();
            LocalDate aujourdhui = aujourdhui();
            Reglages reglages = racine().donnees().reglages();
            PrintWriter out = sortie();
            // L'analyse précède la candidature, qui lui doit son entreprise et son poste : ses appels attendent
            // ici, et s'inscrivent au journal rattachés à la candidature dès qu'elle existe (EN-5).
            List<AppelModele> enAttente = new ArrayList<>();
            try (Base base = ouvrirBase()) {
                Optional<Numerotee> memeTexte = base.candidatureDeLOffre(texte);
                if (memeTexte.isPresent()) {
                    dejaSuivie(out, memeTexte.get());
                    return 0;
                }
                Optional<OffreAnalysee> analyse = analyser(out, reglages, enAttente, texte);
                Optional<Numerotee> creee = Optional.empty();
                try {
                    Optional<Candidature> c = nouvelleCandidature(base, analyse, le == null ? aujourdhui : le, aujourdhui);
                    if (c.isPresent()) {
                        String origine = source.equals("-") ? ENTREE_STANDARD : Path.of(source).toAbsolutePath().toString();
                        creee = Optional.of(base.creerDepuisOffre(c.get(), origine, texte, racine().horloge().instant(), analyse));
                    }
                } finally {
                    Optional<UUID> rattachement = creee.map(n -> n.candidature().id());
                    enAttente.forEach(a -> base.journaliser(rattacher(a, rattachement)));
                }
                if (creee.isEmpty()) {
                    return 0;
                }
                Numerotee n = creee.get();
                out.println("Candidature " + Libelles.titre(n.numero(), n.candidature()) + " ajoutée à l'étape « repérée ».");
                if (analyse.isEmpty()) {
                    out.println("L'offre est conservée avec son texte, non analysée. Plus tard : postulat offre analyser " + n.numero());
                    return 0;
                }
                rapprocher(this, base, reglages, n, analyse.get());
                Affichage.offre(out, base.offre(n.candidature().id()).orElseThrow(), racine().horloge().getZone(), n.numero());
                Affichage.decision(out, n.numero());
            }
            return 0;
        }

        private String lireTexte() {
            String texte;
            try {
                texte = source.equals("-")
                        ? racine().entree().lines().collect(Collectors.joining("\n"))
                        : Files.readString(Path.of(source), StandardCharsets.UTF_8);
            } catch (NoSuchFileException e) {
                throw new Refus("Fichier introuvable : " + Path.of(source).toAbsolutePath());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            texte = Conversions.sansMarqueDOrdre(texte).strip();
            if (texte.length() < LONGUEUR_MINIMALE) {
                throw new Refus("Texte trop court pour une offre (" + texte.length() + " caractères) : rien n'a été analysé. "
                        + "Copiez le texte complet de l'annonce dans un fichier, puis : postulat offre ajouter FICHIER");
            }
            return texte;
        }

        /** P2. Un modèle indisponible n'empêche pas de conserver l'offre (UC-05 E2). */
        private Optional<OffreAnalysee> analyser(PrintWriter out, Reglages reglages, List<AppelModele> journal, String texte) {
            try {
                Modele modele = new ModeleJournalise(racine().fabriqueModele().apply(reglages), journal::add, racine().horloge());
                return Optional.of(AnalyseOffre.analyser(modele, texte, Optional.empty()));
            } catch (ErreurModele e) {
                out.println("Analyse impossible : " + e.getMessage());
                return Optional.empty();
            }
        }

        /** Entreprise et poste viennent de l'analyse, ou de vous ; un doublon n'est jamais créé (RG-18). */
        private Optional<Candidature> nouvelleCandidature(Base base, Optional<OffreAnalysee> analyse, LocalDate date,
                LocalDate aujourdhui) {
            String nomEntreprise = choisir(entreprise, analyse.map(OffreAnalysee::entreprise), "Entreprise", true);
            String intitule = choisir(poste, analyse.map(OffreAnalysee::poste), "Poste", false);
            if (!distincte) {
                Optional<Numerotee> existante = base.toutes().stream()
                        .filter(n -> n.candidature().memeOffre(nomEntreprise, intitule)).findFirst();
                if (existante.isPresent()) {
                    dejaSuivie(sortie(), existante.get());
                    sortie().println("S'il s'agit d'une autre offre : ajoutez --distincte.");
                    return Optional.empty();
                }
            }
            return Optional.of(Candidature.creer(nomEntreprise, intitule, Etape.REPEREE, date, aujourdhui));
        }

        private String choisir(String option, Optional<String> analyse, String nom, boolean feminin) {
            if (option != null && !option.isBlank()) {
                return option.strip();
            }
            if (analyse.isPresent() && !analyse.get().isBlank()) {
                return analyse.get();
            }
            if (!source.equals("-")) {
                sortie().print(nom + " de l'offre (l'analyse ne l'a pas trouvé" + (feminin ? "e" : "") + ") : ");
                sortie().flush();
                try {
                    String saisi = racine().entree().readLine();
                    if (saisi != null && !saisi.isBlank()) {
                        return saisi.strip();
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            throw new Refus(nom + (feminin ? " inconnue : précisez-la" : " inconnu : précisez-le") + " avec --"
                    + nom.toLowerCase(java.util.Locale.ROOT) + ". Rien n'a été enregistré.");
        }

        private static AppelModele rattacher(AppelModele a, Optional<UUID> candidature) {
            Demande d = a.demande();
            return new AppelModele(a.survenuLe(),
                    new Demande(d.tache(), d.gamme(), d.consignes(), d.contenu(), candidature),
                    a.reponse(), a.issue(), a.consommation());
        }
    }

    @Command(name = "analyser", description = "Analyse une offre conservée, puis la rapproche du profil : quand le modèle "
            + "était indisponible, ou après une modification du profil.")
    static final class Analyser extends Commande.SurCandidature {

        @Option(names = "--tout", description = "Refaire aussi l'analyse de l'offre, pas seulement le rapprochement.")
        boolean tout;

        @Override
        public Integer call() {
            Reglages reglages = racine().donnees().reglages();
            PrintWriter out = sortie();
            try (Base base = ouvrirBase()) {
                Numerotee n = trouver(base);
                UUID id = n.candidature().id();
                if (base.aUnDossierValide(id)) {
                    throw new Refus("Un dossier de la candidature n° " + numero + " a été validé : son offre, son analyse et son "
                            + "rapprochement sont figés avec lui (CA-6.8). Rien n'a été refait.");
                }
                Offre offre = base.offre(id).orElseThrow(() -> new Refus("La candidature n° " + numero
                        + " a été saisie sans offre : il n'y a rien à analyser."));
                OffreAnalysee analyse;
                if (offre.analyse().isEmpty() || tout) {
                    analyse = AnalyseOffre.analyser(modele(base, reglages), offre.texteBrut(), Optional.of(id));
                    base.enregistrerAnalyse(id, analyse, racine().horloge().instant());
                    out.println("Offre analysée.");
                } else {
                    analyse = offre.analyse().get();
                }
                rapprocher(this, base, reglages, n, analyse);
                Affichage.offre(out, base.offre(id).orElseThrow(), racine().horloge().getZone(), n.numero());
                if (n.candidature().etape() == Etape.REPEREE) {
                    Affichage.decision(out, n.numero());
                }
            }
            return 0;
        }
    }

    /**
     * P3 et P4, si le profil le permet (UC-05 A3) : sans profil utilisable, l'offre reste analysée mais
     * non rapprochée, et le système le dit.
     */
    static void rapprocher(Commande commande, Base base, Reglages reglages, Numerotee n, OffreAnalysee analyse) {
        PrintWriter out = commande.sortie();
        if (analyse.exigences().isEmpty()) {
            out.println("Aucune exigence relevée dans l'offre : rapprochement sans objet.");
            return;
        }
        Optional<Profil> profil = profilUtilisable(commande, n.numero());
        if (profil.isEmpty()) {
            return;
        }
        UUID id = n.candidature().id();
        try {
            Rapprochement r = RapprochementOffre.rapprocher(commande.modele(base, reglages), profil.get(), analyse.exigences(), id);
            base.enregistrerRapprochement(id, r, commande.racine().horloge().instant());
        } catch (ErreurModele e) {
            out.println("Rapprochement impossible : " + e.getMessage() + " Plus tard : postulat offre analyser " + n.numero());
        }
    }

    private static Optional<Profil> profilUtilisable(Commande commande, long numero) {
        PrintWriter out = commande.sortie();
        Path fichier = commande.racine().donnees().fichierProfil();
        if (!Files.exists(fichier)) {
            out.println("Rapprochement impossible : aucun profil (postulat profil initialiser). L'offre reste analysée ; "
                    + "ensuite : postulat offre analyser " + numero);
            return Optional.empty();
        }
        LectureProfil lecture = commande.racine().donnees().lireProfil(fichier);
        if (!lecture.estUtilisable()) {
            out.println("Rapprochement impossible : le profil a " + lecture.problemes().size()
                    + " problème(s) (postulat profil verifier). Ensuite : postulat offre analyser " + numero);
            return Optional.empty();
        }
        return lecture.profil();
    }

    static void dejaSuivie(PrintWriter out, Numerotee n) {
        out.println("Cette offre est déjà suivie : " + Libelles.titre(n.numero(), n.candidature()) + ", à l'étape "
                + Libelles.etape(n.candidature().etape()) + ". Pour la revoir : postulat voir " + n.numero());
    }
}
