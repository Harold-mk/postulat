package postulat.suivi;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Calcul des échéances (US-7). Fonctions pures : rien n'est stocké, tout est recalculé à la demande
 * à partir du journal et du jour courant, donc rien ne peut se désynchroniser.
 */
public final class Echeances {

    /**
     * Tri de CA-7.6 : retard décroissant, puis étape la plus avancée, puis ancienneté. À jour égal,
     * le retard décroît quand la date d'échéance croît : c'est donc aussi l'ordre chronologique des
     * échéances à venir.
     */
    static final Comparator<Echeance> PAR_URGENCE = Comparator
            .comparing(Echeance::date)
            .thenComparing(Echeance::etape, Comparator.reverseOrder())
            .thenComparing(Echeance::depuis);

    private Echeances() {}

    /** Échéances en cours d'une candidature, atteintes ou à venir. Aucune pour une terminale (UC-11 A1). */
    public static List<Echeance> echeances(
            Candidature candidature, LocalDate aujourdhui, ReglesRelance regles) {
        if (candidature.etape().estTerminale()) {
            return List.of();
        }
        List<Echeance> resultat = new ArrayList<>();
        relance(candidature, regles).ifPresent(resultat::add);
        resultat.addAll(entretiens(candidature, aujourdhui));
        retourPromis(candidature).ifPresent(resultat::add);
        return resultat;
    }

    /** Actions dues sur une candidature à cette date, par urgence. */
    public static List<Echeance> actionsDues(
            Candidature candidature, LocalDate aujourdhui, ReglesRelance regles) {
        return listeDeTravail(List.of(candidature), aujourdhui, regles);
    }

    /** Liste de travail du jour, triée par urgence (CA-7.6). */
    public static List<Echeance> listeDeTravail(
            Collection<Candidature> candidatures, LocalDate aujourdhui, ReglesRelance regles) {
        return candidatures.stream()
                .flatMap(c -> echeances(c, aujourdhui, regles).stream())
                .filter(e -> e.estAtteinte(aujourdhui))
                .sorted(PAR_URGENCE)
                .toList();
    }

    /** Échéances des {@code jours} prochains jours, aujourd'hui exclu, de la plus proche à la plus lointaine (CA-9.4). */
    public static List<Echeance> aVenir(
            Collection<Candidature> candidatures, LocalDate aujourdhui, int jours, ReglesRelance regles) {
        LocalDate limite = aujourdhui.plusDays(jours);
        return candidatures.stream()
                .flatMap(c -> echeances(c, aujourdhui, regles).stream())
                .filter(e -> !e.estAtteinte(aujourdhui) && !e.date().isAfter(limite))
                .sorted(PAR_URGENCE)
                .toList();
    }

    // --- interne ---

    /** Relance suivante, ou proposition de « sans_suite » quand les relances sont épuisées (CA-7.1 à 7.3). */
    private static Optional<Echeance> relance(Candidature c, ReglesRelance regles) {
        Optional<LocalDate> envoi = c.dateEnvoi();
        if (envoi.isEmpty()) {
            return Optional.empty();
        }
        List<LocalDate> relances = c.relancesDepuisEnvoi();
        LocalDate dernierSignal = relances.isEmpty() ? envoi.get() : relances.getLast();
        Echeance.Motif motif = relances.size() < regles.relancesMax()
                ? new Echeance.Motif.Relancer(relances.size() + 1)
                : new Echeance.Motif.ProposerSansSuite();
        return Optional.of(echeance(c, motif, dernierSignal.plusDays(regles.delaiJours())));
    }

    /**
     * Préparation la veille et remerciement le jour de chaque entretien planifié (CA-7.4). La
     * dernière planification d'un type remplace les précédentes. Passé son jour, préparer n'a plus
     * de sens ; passé le lendemain, remercier non plus : ces échéances s'éteignent d'elles-mêmes.
     */
    private static List<Echeance> entretiens(Candidature c, LocalDate aujourdhui) {
        if (c.etape() != Etape.ENTRETIEN) {
            return List.of();
        }
        Map<TypeEntretien, LocalDate> planifies = new EnumMap<>(TypeEntretien.class);
        for (Evenement e : c.journal()) {
            if (e instanceof Evenement.EntretienPlanifie p) {
                planifies.put(p.type(), p.le());
            }
        }
        List<Echeance> resultat = new ArrayList<>();
        planifies.forEach((type, le) -> {
            if (!aujourdhui.isAfter(le)) {
                resultat.add(echeance(c, new Echeance.Motif.PreparerEntretien(type, le), le.minusDays(1)));
            }
            if (!aujourdhui.isAfter(le.plusDays(1))) {
                resultat.add(echeance(c, new Echeance.Motif.Remercier(type, le), le));
            }
        });
        return resultat;
    }

    /**
     * Le dernier retour promis, tant que rien de nouveau n'est arrivé depuis (CA-7.5). Une note ne
     * compte pas : écrire un compte rendu n'est pas recevoir la réponse promise.
     */
    private static Optional<Echeance> retourPromis(Candidature c) {
        List<Evenement> journal = c.journal();
        int i = journal.size() - 1;
        while (journal.get(i) instanceof Evenement.Note) {
            i--; // s'arrête au plus tard sur la création, qui n'est pas une note
        }
        return journal.get(i) instanceof Evenement.RetourPromis rp
                ? Optional.of(echeance(c, new Echeance.Motif.RetourPromis(), rp.pour()))
                : Optional.empty();
    }

    private static Echeance echeance(Candidature c, Echeance.Motif motif, LocalDate date) {
        return new Echeance(c.id(), c.entreprise(), c.poste(), c.etape(), motif, date,
                c.dateEnvoi().orElse(c.dateCreation()));
    }
}
