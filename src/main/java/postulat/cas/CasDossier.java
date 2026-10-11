package postulat.cas;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
 * Les cas d'utilisation du dossier — générer, corriger, réévaluer, valider, verser au profil (UC-04,
 * UC-07 à UC-09) —, écrits une fois pour la ligne de commande et pour la fenêtre. Chacun vérifie ses
 * préconditions avant tout appel au modèle, enregistre d'un seul tenant, et rend ce qu'il faut pour en
 * rendre compte ; l'affichage reste à l'interface.
 */
public final class CasDossier {

    private final Contexte ctx;

    public CasDossier(Contexte ctx) {
        this.ctx = Objects.requireNonNull(ctx);
    }

    // --- générer (UC-07) ---

    /** Ce qui est su avant le premier appel : de quoi l'annoncer. */
    public record Preparation(Numerotee numerotee, int passesMax, String modele, int reprises) {}

    /** Les aperçus PDF : écrits, ou pas — un PDF ouvert dans un lecteur n'annule pas ce qui est enregistré. */
    public record Apercus(Optional<String> ecrits, Optional<String> echec) {}

    public record Generation(Numerotee numerotee, Dossier dossier, CoutModele cout, Apercus apercus) {}

    /** Une génération qui a échoué après avoir coûté : le coût engagé, et la cause. */
    public static final class EchecCouteux extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient CoutModele engage;
        private final RuntimeException cause;

        EchecCouteux(RuntimeException cause, CoutModele engage) {
            super(cause.getMessage(), cause);
            this.cause = cause;
            this.engage = engage;
        }

        public CoutModele engage() {
            return engage;
        }

        public RuntimeException origine() {
            return cause;
        }
    }

    /**
     * Génère, évalue et révise le dossier (UC-07). Les phrases de l'auteur du dossier précédent sont
     * reportées telles quelles (RG-27).
     *
     * @param annonce reçoit ce qui va se passer, avant le premier appel
     * @param progression reçoit une ligne à chaque étape
     * @throws EchecCouteux si le modèle ou le rendu échoue en cours de route : rien n'est enregistré
     */
    public Generation generer(long numero, Consumer<Preparation> annonce, Consumer<String> progression) {
        Reglages reglages = ctx.donnees().reglages();
        try (Base base = ctx.donnees().ouvrirBase()) {
            Numerotee n = trouver(base, numero);
            Candidature c = n.candidature();
            if (c.etape() != Etape.A_PREPARER && c.etape() != Etape.PRETE) {
                throw new Refus("La candidature n° " + numero + " est à l'étape « " + c.etape() + " » : un dossier "
                        + "se génère à l'étape « à_préparer », ou « prête » pour une nouvelle version." + (c.etape() == Etape.REPEREE
                                ? " Pour poursuivre cette offre : postulat avancer " + numero + " à_préparer" : ""));
            }
            GenerationDossier.Cible cible = cible(base, n);
            LectureProfil lecture = ctx.profilUtilisable();
            List<GenerationDossier.Reprise> reprises = base.dossier(c.id())
                    .map(g -> GenerationDossier.reprises(g.dossier())).orElse(List.of());
            annonce.accept(new Preparation(n, 1 + reglages.generation().revisionsMax(), reglages.modeleCapable(), reprises.size()));
            CoutModele avant = base.coutModele(c.id());
            Dossier dossier;
            try {
                dossier = GenerationDossier.generer(ctx.modele(base, reglages), lecture.profil().orElseThrow(), cible, reprises,
                        reglages.generation(), ctx.aujourdhui(), progression);
            } catch (ErreurModele | ErreurRendu e) {
                throw new EchecCouteux(e, difference(avant, base.coutModele(c.id())));
            }
            base.enregistrerDossier(c.id(), DossierGenere.nouveau(dossier, ctx.horloge().instant(), lecture.empreinte(),
                    reglages.modeleCapable()));
            Apercus apercus = apercus(numero, dossier);
            return new Generation(n, dossier, difference(avant, base.coutModele(c.id())), apercus);
        }
    }

    // --- corriger (UC-08) ---

    /**
     * Ce qu'une correction a donné.
     *
     * @param longueur l'avertissement sur la longueur de la lettre, si elle sort de la fourchette (CA-4.4)
     */
    public record Correction(
            Dossier avant, Dossier apres, Rendu.Resultat cv, Optional<NoteAts> atsAvant, Optional<NoteAts> ats,
            Optional<String> longueur, Apercus apercus) {}

    /**
     * Une correction à la main (UC-08) : elle n'appelle aucun modèle. Le dossier corrigé est re-rendu ;
     * s'il ne tient plus sur une page, la correction est refusée avant d'être acceptée (E1). Sinon la note
     * ATS est recalculée, et l'avis du recruteur, qui jugeait la version précédente, est déclaré périmé.
     *
     * @param correction une {@link IllegalArgumentException} qu'elle lève est un refus
     */
    public Correction corriger(long numero, UnaryOperator<Dossier> correction) {
        Dossier avantCorrection;
        Dossier evalue;
        Rendu.Resultat cv;
        Optional<NoteAts> atsAvant;
        Optional<NoteAts> ats;
        Optional<String> longueur;
        try (Base base = ctx.donnees().ouvrirBase()) {
            Numerotee n = trouver(base, numero);
            UUID id = n.candidature().id();
            DossierGenere g = courant(base, n);
            Dossier corrige;
            try {
                corrige = correction.apply(g.dossier());
            } catch (IllegalArgumentException e) {
                throw new Refus(e.getMessage());
            }
            try {
                cv = Rendu.cvTelQuel(corrige.cv());
                Rendu.lettre(corrige.lettre());
            } catch (ErreurRendu e) {
                throw new Refus("Correction refusée, rien n'a été modifié. " + e.getMessage()
                        + " Raccourcissez, ou supprimez d'abord une autre phrase.");
            }
            List<Exigence> exigences = base.offre(id).flatMap(Offre::analyse).map(OffreAnalysee::exigences).orElse(List.of());
            ats = Ats.noter(cv.texte(), exigences);
            Evaluation avant = g.dossier().evaluation();
            atsAvant = avant.ats();
            avantCorrection = g.dossier();
            evalue = new Dossier(corrige.cv(), corrige.lettre(), new Evaluation(ats, avant.avis(), avant.regles()), corrige.rapport());
            base.enregistrerDossier(id, g.corrige(evalue, ctx.horloge().instant()));
            ReglesGeneration regles = avant.regles();
            int mots = evalue.lettre().mots();
            longueur = mots < regles.motsMin() || mots > regles.motsMax()
                    ? Optional.of("la lettre compte " + mots + " mots, hors de la fourchette de " + regles.motsMin() + " à "
                            + regles.motsMax() + " (CA-4.4).")
                    : Optional.empty();
        }
        return new Correction(avantCorrection, evalue, cv, atsAvant, ats, longueur, apercus(numero, evalue));
    }

    // --- réévaluer (UC-08, étape 7) ---

    public record Reevaluation(Dossier avant, Dossier apres, CoutModele cout) {}

    public Reevaluation evaluer(long numero) {
        Reglages reglages = ctx.donnees().reglages();
        try (Base base = ctx.donnees().ouvrirBase()) {
            Numerotee n = trouver(base, numero);
            UUID id = n.candidature().id();
            DossierGenere g = courant(base, n);
            GenerationDossier.Cible cible = cible(base, n);
            LectureProfil lecture = ctx.profilUtilisable();
            CoutModele avant = base.coutModele(id);
            Dossier reevalue = GenerationDossier.reevaluer(ctx.modele(base, reglages), lecture.profil().orElseThrow(), cible, g.dossier());
            base.enregistrerDossier(id, g.reevalue(reevalue));
            return new Reevaluation(g.dossier(), reevalue, difference(avant, base.coutModele(id)));
        }
    }

    // --- valider (UC-09) ---

    /**
     * @param passeeAPrete vrai si la validation a fait passer la candidature à « prête »
     * @param profilChange vrai si le profil a changé depuis la génération
     */
    public record Validation(
            Numerotee numerotee, int version, Path repertoire, List<String> fichiers, int faits, boolean passeeAPrete,
            boolean profilChange) {}

    /**
     * Valide le dossier : le fige, copie le contenu des faits qu'il cite, écrit le CV et la lettre en PDF
     * et en DOCX dans le répertoire de la candidature, et la fait passer à « prête ». D'un seul tenant
     * (E1) ; refusé si le profil ne justifie plus les phrases (E2).
     */
    public Validation valider(long numero) {
        try (Base base = ctx.donnees().ouvrirBase()) {
            Numerotee n = trouver(base, numero);
            Candidature c = n.candidature();
            DossierGenere g = courant(base, n);
            List<VersionValidee> versions = base.versionsValidees(c.id());
            if (!versions.isEmpty() && versions.get(versions.size() - 1).dossier().dossier().equals(g.dossier())) {
                throw new Refus("Ce dossier est déjà validé, en version " + versions.size() + " : rien n'a changé depuis.");
            }
            int version = versions.size() + 1;
            Candidature validee = c.valider(version, ctx.aujourdhui(), ctx.aujourdhui());
            GenerationDossier.Cible cible = cible(base, n);
            LectureProfil lecture = ctx.profilUtilisable();
            Profil profil = lecture.profil().orElseThrow();
            Dossier d = g.dossier();

            // E2 : ce qui se fige doit encore tenir face au profil, dont les faits vont être copiés.
            List<Violation> violations = Tracabilite.verifier(d.assertions(), profil, cible.lacunes(), cible.texteBrut());
            if (!violations.isEmpty()) {
                throw new Refus("Validation refusée, rien n'a été enregistré (UC-09 E2) : le profil a changé depuis la génération, "
                        + "et ces phrases n'y retrouvent plus ce qui les justifiait :" + System.lineSeparator() + violations.stream()
                                .map(x -> "  - n° " + (x.assertion() + 1) + " « " + d.assertions().get(x.assertion()).texte() + " » — "
                                        + x.regle() + " : " + x.message())
                                .collect(Collectors.joining(System.lineSeparator()))
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
                base.validerDossier(validee, new VersionValidee(version, g, ctx.horloge().instant(), repertoire.toString(), faits));
            } catch (RuntimeException e) {
                effacer(repertoire);
                throw e;
            }
            return new Validation(n, version, repertoire, List.copyOf(fichiers.keySet()), faits.size(), c.etape() != Etape.PRETE,
                    !lecture.empreinte().equals(g.empreinteProfil()));
        }
    }

    /**
     * Écrit les fichiers dans un répertoire provisoire, puis le renomme : on ne trouve jamais un
     * répertoire de candidature à moitié écrit. Si l'écriture échoue, rien ne reste (UC-09 E1).
     */
    private Path ecrireRepertoire(Numerotee n, int version, Map<String, byte[]> fichiers) {
        Path candidature = ctx.donnees().repertoire().resolve("candidatures")
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

    /**
     * Un nom que Windows accepte : sans les caractères qu'il interdit, sans espace ni point final, et
     * assez court pour ne pas buter sur la longueur des chemins.
     */
    public static String nomDeFichier(String nom) {
        String propre = nom.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
        if (propre.length() > 80) {
            propre = propre.substring(0, 80).strip();
        }
        propre = propre.replaceAll("[. ]+$", "");
        return propre.isEmpty() ? "candidature" : propre;
    }

    /** Retire un répertoire que la validation vient d'écrire, et lui seul ; un échec ici ne masque pas l'erreur d'origine. */
    private static void effacer(Path repertoire) {
        try (Stream<Path> chemins = Files.walk(repertoire)) {
            for (Path p : chemins.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException | UncheckedIOException ignoree) {
            // l'erreur d'origine dit déjà l'essentiel
        }
    }

    // --- verser au profil (UC-04) ---

    /**
     * Ce que l'auteur a dit du fait à tirer de sa phrase. Une valeur absente garde celle par défaut : la
     * tâche reprend la phrase, le parent est celui du bloc.
     */
    public record Demande(
            Optional<String> dans, Optional<String> objectif, Optional<String> tache, Optional<String> resultat,
            Optional<List<String>> competences, Optional<String> consigne, boolean commeCompetence, Optional<String> nom,
            Optional<String> niveau, Optional<String> enrichir, boolean distinct) {}

    public record Versement(Fait verse, boolean enrichi, Path ancienneVersion) {}

    /**
     * Fait d'une phrase de l'auteur un fait du profil (UC-04) : une réalisation ou une compétence, ou un
     * fait existant enrichi (A2). La phrase reste de l'auteur dans le dossier (RG-23).
     */
    public Versement verser(long numero, int phrase, Demande demande) {
        try (Base base = ctx.donnees().ouvrirBase()) {
            Numerotee n = trouver(base, numero);
            DossierGenere g = courant(base, n);
            Assertion a;
            try {
                a = g.dossier().phrase(phrase);
                g.dossier().marquerVersee(phrase, ""); // d'emblée : une phrase de l'auteur, pas encore versée
            } catch (IllegalArgumentException e) {
                throw new Refus(e.getMessage());
            }
            Profil profil = ctx.profilUtilisable().profil().orElseThrow();
            EcritureProfil ecriture = new EcritureProfil(ctx.donnees().fichierProfil(), ctx.donnees().repertoire().resolve("profils"),
                    ctx.horloge().instant());
            Fait verse;
            EcritureProfil.Ecriture e;
            try {
                if (demande.enrichir().isPresent()) {
                    verse = enrichi(profil, demande);
                    e = ecriture.remplacer(verse);
                } else if (demande.commeCompetence()) {
                    verse = competence(profil, a, demande);
                    e = ecriture.ajouterCompetence((Competence) verse);
                } else {
                    String parent = parent(profil, g.dossier(), phrase, demande);
                    verse = realisation(profil, parent, a, demande);
                    e = ecriture.ajouterRealisation(parent, (Realisation) verse);
                }
            } catch (IllegalArgumentException ex) {
                throw new Refus(ex.getMessage());
            }
            base.enregistrerDossier(n.candidature().id(), g.avec(g.dossier().marquerVersee(phrase, verse.id())));
            return new Versement(verse, demande.enrichir().isPresent(), e.ancienneVersion());
        }
    }

    /** La réalisation à créer, sauf si elle redit un fait existant (UC-04 A2) : on propose alors d'enrichir celui-ci. */
    private static Realisation realisation(Profil profil, String parent, Assertion a, Demande d) {
        if (d.objectif().map(String::isBlank).orElse(true)) {
            throw new Refus("Il manque l'objectif de cette réalisation : --objectif \"…\", le but poursuivi. La tâche reprend votre "
                    + "phrase ; --tache pour la formuler autrement.");
        }
        Realisation r = new Realisation(profil.identifiantDeRealisation(parent), d.objectif().get().strip(),
                d.tache().map(String::strip).orElse(a.texte()), d.resultat().map(String::strip).orElse(""), etiquettes(d),
                d.consigne().map(String::strip).orElse(""));
        Optional<Realisation> proche = Recoupement.realisationProche(profil, parent, r.tache() + " " + r.resultat());
        if (proche.isPresent() && !d.distinct()) {
            throw new Refus("Cette phrase semble redire le fait « " + proche.get().id() + " » : " + proche.get().tache()
                    + (proche.get().resultat().isBlank() ? "" : " ; " + proche.get().resultat()) + "." + System.lineSeparator()
                    + "Pour l'enrichir plutôt que d'en créer un second (UC-04 A2) : --enrichir " + proche.get().id()
                    + " --resultat \"…\". Pour créer quand même un fait distinct : --distinct");
        }
        return r;
    }

    private static Competence competence(Profil profil, Assertion a, Demande d) {
        String leNom = d.nom().map(String::strip).orElse(a.texte());
        Optional<Competence> homonyme = Recoupement.competenceHomonyme(profil, leNom);
        if (homonyme.isPresent() && !d.distinct()) {
            throw new Refus("La compétence « " + homonyme.get().nom() + " » est déjà au profil (" + homonyme.get().id() + "). Pour la "
                    + "compléter : --enrichir " + homonyme.get().id() + " --niveau \"…\". Pour en créer une seconde : --distinct");
        }
        return new Competence(profil.identifiantDeCompetence(leNom), leNom, d.niveau().map(String::strip).orElse(""),
                d.consigne().map(String::strip).orElse(""));
    }

    /** Le fait existant, avec les seuls champs donnés remplacés. */
    private static Fait enrichi(Profil profil, Demande d) {
        String id = d.enrichir().orElseThrow();
        Fait existant = profil.fait(id).orElseThrow(() -> new Refus("Aucun fait « " + id + " » dans le profil."));
        return switch (existant) {
            case Realisation r -> {
                if (d.objectif().isEmpty() && d.tache().isEmpty() && d.resultat().isEmpty() && d.competences().isEmpty()
                        && d.consigne().isEmpty()) {
                    throw new Refus("Dites ce que la phrase apporte au fait « " + id + " » : --resultat \"…\", --tache \"…\", "
                            + "--objectif, --competences ou --consigne.");
                }
                yield new Realisation(r.id(), choisir(d.objectif(), r.objectif()), choisir(d.tache(), r.tache()),
                        choisir(d.resultat(), r.resultat()), d.competences().isEmpty() ? r.competences() : etiquettes(d),
                        choisir(d.consigne(), r.consigne()));
            }
            case Competence c -> {
                if (d.nom().isEmpty() && d.niveau().isEmpty() && d.consigne().isEmpty()) {
                    throw new Refus("Dites ce que la phrase apporte à la compétence « " + id + " » : --niveau, --nom ou --consigne.");
                }
                yield new Competence(c.id(), choisir(d.nom(), c.nom()), choisir(d.niveau(), c.niveau()), choisir(d.consigne(), c.consigne()));
            }
            default -> throw new Refus("« " + id + " » est une expérience ou une formation : seules une réalisation ou une "
                    + "compétence s'enrichissent depuis une phrase.");
        };
    }

    /** L'expérience ou la formation où ranger la réalisation : donnée, ou celle du bloc de la phrase. */
    private static String parent(Profil profil, Dossier dossier, int phrase, Demande d) {
        String parent = d.dans().map(String::strip).orElseGet(() -> dossier.parentPropose(phrase).orElseThrow(() -> new Refus(
                "Précisez sous quelle expérience ou formation ranger cette réalisation : --dans ID. Possibles : "
                        + String.join(", ", parents(profil)) + ".")));
        if (!parents(profil).contains(parent)) {
            throw new Refus("« " + parent + " » n'est ni une expérience ni une formation du profil. Possibles : "
                    + String.join(", ", parents(profil)) + ".");
        }
        return parent;
    }

    /** Les identifiants des expériences et formations, où une réalisation peut se ranger. */
    public static List<String> parents(Profil profil) {
        List<String> ids = new ArrayList<>(profil.experiences().stream().map(Fait::id).toList());
        profil.formations().forEach(f -> ids.add(f.id()));
        return ids;
    }

    private static List<String> etiquettes(Demande d) {
        return d.competences().orElse(List.of()).stream().map(String::strip).filter(c -> !c.isEmpty()).toList();
    }

    private static String choisir(Optional<String> donne, String actuel) {
        return donne.map(String::strip).orElse(actuel);
    }

    // --- commun ---

    /** Le dossier courant, ou un refus qui dit comment en obtenir un. */
    public static DossierGenere courant(Base base, Numerotee n) {
        return base.dossier(n.candidature().id()).orElseThrow(() -> new Refus("La candidature n° " + n.numero()
                + " n'a pas encore de dossier. Pour le générer : postulat dossier generer " + n.numero()));
    }

    /** L'offre visée, analysée et rapprochée du profil : vérifiée avant tout appel, rien ne se paie pour un refus. */
    public static GenerationDossier.Cible cible(Base base, Numerotee n) {
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

    public static Numerotee trouver(Base base, long numero) {
        return base.trouver(numero).orElseThrow(() -> new Refus("Aucune candidature n° " + numero
                + ". Les numéros sont dans : postulat liste --toutes"));
    }

    /** Réécrit les aperçus ; un PDF ouvert dans un lecteur n'annule pas ce qui vient d'être enregistré. */
    public Apercus apercus(long numero, Dossier dossier) {
        try {
            return new Apercus(Optional.of(ecrireApercus(numero, dossier)), Optional.empty());
        } catch (Refus e) {
            return new Apercus(Optional.empty(), Optional.of(e.getMessage()));
        }
    }

    /** Les PDF du dossier, dans le répertoire des données : des aperçus, remplacés à chaque génération. */
    public String ecrireApercus(long numero, Dossier dossier) {
        Path repertoire = repertoireApercus();
        try {
            Path cv = Contexte.ecrirePdf(repertoire.resolve(fichierApercu(numero, "cv")), Rendu.cvTelQuel(dossier.cv()).pdf());
            Contexte.ecrirePdf(repertoire.resolve(fichierApercu(numero, "lettre")), Rendu.lettre(dossier.lettre()).pdf());
            return cv + " et " + fichierApercu(numero, "lettre");
        } catch (IOException e) {
            throw new ErreurStockage("Aperçus impossibles à écrire dans " + repertoire + " : " + e.getMessage(), e);
        }
    }

    public Path repertoireApercus() {
        return ctx.donnees().repertoire().resolve("apercus");
    }

    public static String fichierApercu(long numero, String document) {
        return "dossier-" + numero + "-" + document + ".pdf";
    }

    private static CoutModele difference(CoutModele avant, CoutModele apres) {
        return new CoutModele(apres.appels() - avant.appels(), apres.coutMicroDollars() - avant.coutMicroDollars());
    }
}
