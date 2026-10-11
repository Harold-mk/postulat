package postulat.bureau;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import postulat.cas.CasDossier;
import postulat.cas.CasOffre;
import postulat.cas.Contexte;
import postulat.ia.Modele;
import postulat.production.Profil;
import postulat.stockage.Base;
import postulat.stockage.CoutModele;
import postulat.stockage.Donnees;
import postulat.stockage.DossierGenere;
import postulat.stockage.LectureProfil;
import postulat.stockage.Numerotee;
import postulat.stockage.Offre;
import postulat.stockage.Reglages;
import postulat.stockage.VersionValidee;
import postulat.suivi.Canal;
import postulat.suivi.Candidature;
import postulat.suivi.Contact;
import postulat.suivi.Echeance;
import postulat.suivi.Echeances;
import postulat.suivi.Etape;
import postulat.suivi.TableauDeBord;
import postulat.suivi.TypeEntretien;

/**
 * Ce que l'interface de bureau lit et fait, sans rien de graphique : chaque écran l'interroge, chaque
 * bouton l'appelle. La base s'ouvre et se ferme à chaque opération, comme dans la ligne de commande :
 * rien ne reste verrouillé quand la fenêtre attend.
 *
 * <p>Les refus du domaine ({@link postulat.suivi.RefusMetier}) traversent tels quels : l'interface les
 * montre, sans les reformuler.
 */
public final class Session {

    /** Une même offre est déjà suivie (RG-18) : l'ajout attend une confirmation explicite. */
    public static final class Doublon extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient Numerotee existante;

        Doublon(Numerotee existante) {
            super("Cette offre est déjà suivie : #" + existante.numero() + " " + existante.candidature().entreprise() + " — "
                    + existante.candidature().poste() + ", à l'étape « " + existante.candidature().etape() + " ».");
            this.existante = existante;
        }

        public Numerotee existante() {
            return existante;
        }
    }

    /** Tout sur une candidature, de quoi remplir sa fiche (UC-12). */
    public record Fiche(
            Numerotee numerotee, List<Contact> contacts, List<Echeance> echeances, CoutModele cout, Optional<Offre> offre,
            Optional<DossierGenere> dossier, List<VersionValidee> versions) {}

    private final Donnees donnees;
    private final Clock horloge;
    private final Function<Reglages, Modele> fabriqueModele;

    public Session(Donnees donnees, Clock horloge, Function<Reglages, Modele> fabriqueModele) {
        this.donnees = Objects.requireNonNull(donnees);
        this.horloge = Objects.requireNonNull(horloge);
        this.fabriqueModele = Objects.requireNonNull(fabriqueModele);
    }

    public LocalDate aujourdhui() {
        return LocalDate.now(horloge);
    }

    public Donnees donnees() {
        return donnees;
    }

    public Clock horloge() {
        return horloge;
    }

    Function<Reglages, Modele> fabriqueModele() {
        return fabriqueModele;
    }

    // --- offres et dossiers : les mêmes cas que la ligne de commande ---

    private Contexte contexte() {
        return new Contexte(donnees, horloge, fabriqueModele);
    }

    /** Soumettre, analyser et rapprocher une offre (UC-05). */
    public CasOffre offres() {
        return new CasOffre(contexte());
    }

    /** Générer, corriger, réévaluer et valider un dossier, en verser une phrase au profil (UC-04, UC-07 à UC-09). */
    public CasDossier dossiers() {
        return new CasDossier(contexte());
    }

    /** Le profil, s'il se lit sans problème : ses faits, pour y verser une phrase (UC-04). */
    public Optional<Profil> profil() {
        Path fichier = donnees.fichierProfil();
        if (!Files.exists(fichier)) {
            return Optional.empty();
        }
        LectureProfil lecture = donnees.lireProfil(fichier);
        return lecture.estUtilisable() ? lecture.profil() : Optional.empty();
    }

    /** Vrai si le profil a changé depuis la génération du dossier : celui-ci n'en tient pas compte. */
    public boolean profilAChange(DossierGenere dossier) {
        Path fichier = donnees.fichierProfil();
        return Files.exists(fichier) && !donnees.lireProfil(fichier).empreinte().equals(dossier.empreinteProfil());
    }

    /** L'aperçu PDF du CV ({@code "cv"}) ou de la lettre ({@code "lettre"}), s'il a été écrit. */
    public Optional<Path> apercu(long numero, String document) {
        Path fichier = dossiers().repertoireApercus().resolve(CasDossier.fichierApercu(numero, document));
        return Files.exists(fichier) ? Optional.of(fichier) : Optional.empty();
    }

    // --- lire ---

    /** Toutes les candidatures, par numéro. */
    public List<Numerotee> candidatures() {
        try (Base base = donnees.ouvrirBase()) {
            return base.toutes();
        }
    }

    /** Le tableau de bord du jour (UC-16). */
    public TableauDeBord tableau() {
        Reglages r = donnees.reglages();
        return TableauDeBord.calculer(candidatures().stream().map(Numerotee::candidature).toList(), aujourdhui(), r.relance(),
                r.objectifHebdomadaire());
    }

    /** Ce qu'il y a à faire aujourd'hui, le plus urgent d'abord (UC-14). */
    public List<Echeance> actions() {
        return Echeances.listeDeTravail(candidatures().stream().map(Numerotee::candidature).toList(), aujourdhui(),
                donnees.reglages().relance());
    }

    /** La prochaine échéance, quand rien n'est dû aujourd'hui (UC-14 A1). */
    public Optional<Echeance> prochaine() {
        return candidatures().stream()
                .flatMap(n -> Echeances.echeances(n.candidature(), aujourdhui(), donnees.reglages().relance()).stream())
                .min(Comparator.comparing(Echeance::date));
    }

    /** Le numéro de chaque candidature, pour retrouver celle d'une échéance. */
    public Map<UUID, Long> numeros() {
        return candidatures().stream().collect(Collectors.toMap(n -> n.candidature().id(), Numerotee::numero));
    }

    public Fiche fiche(long numero) {
        try (Base base = donnees.ouvrirBase()) {
            Numerotee n = trouver(base, numero);
            UUID id = n.candidature().id();
            return new Fiche(n, base.contacts(id), Echeances.echeances(n.candidature(), aujourdhui(), donnees.reglages().relance()),
                    base.coutModele(id), base.offre(id), base.dossier(id), base.versionsValidees(id));
        }
    }

    // --- saisir (UC-10, UC-11, UC-13, UC-15) ---

    /**
     * Ajoute une candidature à l'étape atteinte et à sa date réelle (UC-10).
     *
     * @param distincte vrai pour ajouter même si une candidature porte la même entreprise et le même poste
     * @throws Doublon si c'est la même offre et que {@code distincte} est faux (RG-18)
     */
    public Numerotee ajouter(String entreprise, String poste, Etape etape, LocalDate date, boolean distincte) {
        try (Base base = donnees.ouvrirBase()) {
            if (!distincte) {
                base.toutes().stream().filter(n -> n.candidature().memeOffre(entreprise, poste)).findFirst().ifPresent(n -> {
                    throw new Doublon(n);
                });
            }
            return base.creer(Candidature.creer(entreprise.strip(), poste.strip(), etape, date, aujourdhui()));
        }
    }

    public Numerotee avancer(long numero, Etape vers, LocalDate date) {
        return appliquer(numero, c -> c.avancer(vers, date, aujourdhui()));
    }

    public Numerotee ecarter(long numero, String motif, LocalDate date) {
        return appliquer(numero, c -> c.ecarter(motif.strip(), date, aujourdhui()));
    }

    public Numerotee rouvrir(long numero, Etape vers, LocalDate date) {
        return appliquer(numero, c -> c.reouvrir(vers, date, aujourdhui()));
    }

    public Numerotee relancer(long numero, LocalDate date) {
        return appliquer(numero, c -> c.relancer(date, aujourdhui()));
    }

    public Numerotee planifier(long numero, TypeEntretien type, LocalDate le, LocalDate date) {
        return appliquer(numero, c -> c.planifierEntretien(type, le, date, aujourdhui()));
    }

    /** Un entretien passé, et son compte rendu joint en note s'il y en a un (CA-6.13, CA-8.4). */
    public Numerotee entretien(long numero, TypeEntretien type, String compteRendu, LocalDate date) {
        return appliquer(numero, c -> {
            Candidature passe = c.noterEntretien(type, date, aujourdhui());
            return compteRendu == null || compteRendu.isBlank() ? passe
                    : passe.noter("Compte rendu de l'entretien " + postulat.libelles.Libelles.entretien(type) + " : " + compteRendu.strip(),
                            date, aujourdhui());
        });
    }

    public Numerotee retourPromis(long numero, LocalDate pour, LocalDate date) {
        return appliquer(numero, c -> c.noterRetourPromis(pour, date, aujourdhui()));
    }

    public Numerotee interaction(long numero, Canal canal, String notes, LocalDate date) {
        return appliquer(numero, c -> c.interagir(canal, notes == null ? "" : notes, date, aujourdhui()));
    }

    public Numerotee noter(long numero, String texte, LocalDate date) {
        return appliquer(numero, c -> c.noter(texte.strip(), date, aujourdhui()));
    }

    public void ajouterContact(long numero, Contact contact) {
        try (Base base = donnees.ouvrirBase()) {
            base.ajouterContact(trouver(base, numero).candidature().id(), contact);
        }
    }

    /** Lit la candidature, lui applique l'opération du domaine, enregistre le journal qui en résulte. */
    private Numerotee appliquer(long numero, UnaryOperator<Candidature> operation) {
        try (Base base = donnees.ouvrirBase()) {
            Numerotee n = trouver(base, numero);
            Candidature apres = operation.apply(n.candidature());
            base.enregistrer(apres);
            return new Numerotee(n.numero(), apres);
        }
    }

    private static Numerotee trouver(Base base, long numero) {
        return base.trouver(numero).orElseThrow(() -> new IllegalArgumentException("Aucune candidature n° " + numero + "."));
    }
}
