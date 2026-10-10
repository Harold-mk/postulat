package postulat.suivi;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Agrégat central du suivi : une candidature et son journal d'événements.
 *
 * <p>Immuable : chaque opération renvoie une nouvelle {@code Candidature} dont le journal compte un
 * événement de plus. Rien n'est jamais retiré ni modifié dans le journal (RG-11).
 *
 * <p>Le jour courant est toujours passé en paramètre ({@code aujourdhui}) : le domaine ne lit jamais
 * l'horloge (EN-8).
 */
public record Candidature(UUID id, String entreprise, String poste, List<Evenement> journal) {

    public Candidature {
        Objects.requireNonNull(id);
        exigerTexte(entreprise, "L'entreprise");
        exigerTexte(poste, "Le poste");
        journal = List.copyOf(journal);
        if (journal.isEmpty() || !(journal.get(0) instanceof Evenement.Creation)) {
            throw new IllegalArgumentException("Un journal commence toujours par une création.");
        }
    }

    /**
     * Crée une candidature à l'étape voulue, à sa date réelle (CA-6.4, UC-10). Une candidature
     * déposée hors de l'outil peut entrer directement à une étape avancée.
     */
    public static Candidature creer(
            String entreprise, String poste, Etape etape, LocalDate date, LocalDate aujourdhui) {
        refuserDateFuture(date, aujourdhui);
        return new Candidature(
                UUID.randomUUID(), entreprise, poste, List.of(new Evenement.Creation(date, etape)));
    }

    /** Étape courante, recalculée en rejouant le journal. Jamais stockée. */
    public Etape etape() {
        Etape courante = null;
        for (Evenement e : journal) {
            courante = switch (e) {
                case Evenement.Creation c -> c.etape();
                case Evenement.ChangementEtape c -> c.vers();
                case Evenement.Reouverture r -> r.vers();
                case Evenement.RelanceEffectuee r -> courante;
                case Evenement.EntretienPasse ep -> courante;
                case Evenement.EntretienPlanifie ep -> courante;
                case Evenement.Interaction i -> courante;
                case Evenement.RetourPromis rp -> courante;
                case Evenement.Note n -> courante;
            };
        }
        return courante;
    }

    /** Fait passer la candidature à une étape déclarée dans la table des transitions (RG-08, RG-09). */
    public Candidature avancer(Etape vers, LocalDate date, LocalDate aujourdhui) {
        Etape actuelle = etape();
        if (actuelle.estTerminale()) {
            throw new RefusMetier("La candidature est à l'étape terminale « " + actuelle
                    + " » : seules une note ou une réouverture sont possibles.");
        }
        if (!actuelle.peutAllerVers(vers)) {
            throw new RefusMetier("Transition « " + actuelle + " » → « " + vers
                    + " » non autorisée. Possibles : " + lister(actuelle) + ".");
        }
        return ajouter(new Evenement.ChangementEtape(date, vers), aujourdhui);
    }

    /** Sort explicitement d'une étape terminale (CA-6.5). */
    public Candidature reouvrir(Etape vers, LocalDate date, LocalDate aujourdhui) {
        if (!etape().estTerminale()) {
            throw new RefusMetier("Seule une candidature à une étape terminale peut être rouverte.");
        }
        if (vers.estTerminale()) {
            throw new RefusMetier("Une réouverture mène à une étape ouverte, pas à « " + vers + " ».");
        }
        return ajouter(new Evenement.Reouverture(date, vers), aujourdhui);
    }

    /** Enregistre une relance faite par le candidat (CA-7.9). */
    public Candidature relancer(LocalDate date, LocalDate aujourdhui) {
        if (etape() != Etape.ENVOYEE) {
            throw new RefusMetier("On ne relance qu'une candidature à l'étape « envoyée ».");
        }
        return ajouter(new Evenement.RelanceEffectuee(date), aujourdhui);
    }

    /** Enregistre un entretien passé, sans changer l'étape (CA-6.13). */
    public Candidature noterEntretien(TypeEntretien type, LocalDate date, LocalDate aujourdhui) {
        if (etape() != Etape.ENTRETIEN) {
            throw new RefusMetier("Passe d'abord la candidature à l'étape « entretien ».");
        }
        return ajouter(new Evenement.EntretienPasse(date, type), aujourdhui);
    }

    /**
     * Enregistre un entretien fixé pour le jour {@code le}, qui peut être à venir (CA-7.4). Le
     * replanifier, c'est planifier de nouveau le même type : seule la dernière date compte.
     */
    public Candidature planifierEntretien(
            TypeEntretien type, LocalDate le, LocalDate date, LocalDate aujourdhui) {
        if (etape() != Etape.ENTRETIEN) {
            throw new RefusMetier("Passe d'abord la candidature à l'étape « entretien ».");
        }
        return ajouter(new Evenement.EntretienPlanifie(date, type, le), aujourdhui);
    }

    /** Enregistre un échange passé avec l'entreprise (CA-8.2). */
    public Candidature interagir(Canal canal, String notes, LocalDate date, LocalDate aujourdhui) {
        refuserSiTerminale();
        return ajouter(new Evenement.Interaction(date, canal, notes.strip()), aujourdhui);
    }

    /** Enregistre la promesse d'une réponse pour le jour {@code pour} (CA-7.5). */
    public Candidature noterRetourPromis(LocalDate pour, LocalDate date, LocalDate aujourdhui) {
        refuserSiTerminale();
        return ajouter(new Evenement.RetourPromis(date, pour), aujourdhui);
    }

    /** Ajoute une note libre, permise à toute étape (CA-6.5). */
    public Candidature noter(String texte, LocalDate date, LocalDate aujourdhui) {
        return ajouter(new Evenement.Note(date, texte), aujourdhui);
    }

    /**
     * Vrai si l'entreprise et le poste désignent la même offre que cette candidature (RG-18), sans
     * tenir compte de la casse, des accents ni de la ponctuation.
     */
    public boolean memeOffre(String autreEntreprise, String autrePoste) {
        return cle(entreprise).equals(cle(autreEntreprise)) && cle(poste).equals(cle(autrePoste));
    }

    public LocalDate dateCreation() {
        return journal.get(0).date();
    }

    /** Date à laquelle la candidature est entrée dans son étape courante. */
    public LocalDate dateEntreeEtape() {
        return journal.get(indexEntreeEtape()).date();
    }

    /**
     * Date à laquelle la candidature est entrée pour la dernière fois à l'étape « envoyée », si elle
     * y est actuellement.
     */
    public Optional<LocalDate> dateEnvoi() {
        return etape() == Etape.ENVOYEE ? Optional.of(dateEntreeEtape()) : Optional.empty();
    }

    /** Relances effectuées depuis la dernière entrée à l'étape « envoyée ». */
    public List<LocalDate> relancesDepuisEnvoi() {
        if (etape() != Etape.ENVOYEE) {
            return List.of();
        }
        return journal.subList(indexEntreeEtape() + 1, journal.size()).stream()
                .filter(e -> e instanceof Evenement.RelanceEffectuee)
                .map(Evenement::date)
                .toList();
    }

    public LocalDate dateDernierEvenement() {
        return journal.get(journal.size() - 1).date();
    }

    // --- interne ---

    /** Position du dernier événement qui a fixé l'étape : c'est l'entrée dans l'étape courante. */
    private int indexEntreeEtape() {
        for (int i = journal.size() - 1; i > 0; i--) {
            if (journal.get(i) instanceof Evenement.ChangementEtape
                    || journal.get(i) instanceof Evenement.Reouverture) {
                return i;
            }
        }
        return 0;
    }

    private Candidature ajouter(Evenement e, LocalDate aujourdhui) {
        refuserDateFuture(e.date(), aujourdhui);
        if (e.date().isBefore(dateDernierEvenement())) {
            throw new RefusMetier("Date antérieure au dernier événement enregistré ("
                    + dateDernierEvenement() + ") : l'historique doit rester ordonné.");
        }
        List<Evenement> suite = new ArrayList<>(journal);
        suite.add(e);
        return new Candidature(id, entreprise, poste, suite);
    }

    private void refuserSiTerminale() {
        if (etape().estTerminale()) {
            throw new RefusMetier("La candidature est à l'étape terminale « " + etape()
                    + " » : seules une note ou une réouverture sont possibles.");
        }
    }

    private static void refuserDateFuture(LocalDate date, LocalDate aujourdhui) {
        if (date.isAfter(aujourdhui)) {
            throw new RefusMetier("Date dans le futur (" + date + ") : on n'enregistre que ce qui a eu lieu.");
        }
    }

    private static String lister(Etape etape) {
        return etape.suivantes().stream()
                .sorted()
                .map(Etape::libelle)
                .collect(Collectors.joining(", "));
    }

    /** Forme canonique d'un libellé pour la comparaison : sans accents, casse ni ponctuation. */
    private static String cle(String texte) {
        return Normalizer.normalize(texte, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .strip();
    }

    private static void exigerTexte(String valeur, String nom) {
        if (valeur == null || valeur.isBlank()) {
            throw new RefusMetier(nom + " est obligatoire.");
        }
    }
}
