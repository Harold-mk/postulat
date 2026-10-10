package postulat.suivi;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * État de la campagne d'un coup d'œil (US-9). Que des dénombrements sur les journaux : justes dès la
 * première candidature (CA-9.5), et calculés sans base ni fichier (EN-7).
 *
 * @param repartition nombre de candidatures à chaque étape, toutes étapes présentes (CA-9.1)
 * @param semaines envois des dernières semaines, la plus ancienne d'abord (CA-9.2)
 * @param enAttente candidatures envoyées sans réponse, la plus ancienne d'abord (CA-9.3)
 * @param actionsDues liste de travail du jour (CA-9.4)
 * @param aVenir échéances des sept prochains jours (CA-9.4)
 * @param brouillons offres restées à « repérée », la plus ancienne d'abord (CA-9.7)
 */
public record TableauDeBord(
        Map<Etape, Integer> repartition,
        int objectifHebdomadaire,
        List<Semaine> semaines,
        List<Candidature> enAttente,
        List<Echeance> actionsDues,
        List<Echeance> aVenir,
        List<Candidature> brouillons) {

    /** Décision 9 du cadrage. */
    public static final int OBJECTIF_HEBDOMADAIRE = 30;

    static final int SEMAINES_AFFICHEES = 4;
    static final int HORIZON_JOURS = 7;

    public TableauDeBord {
        repartition = Collections.unmodifiableMap(new EnumMap<>(repartition));
        semaines = List.copyOf(semaines);
        enAttente = List.copyOf(enAttente);
        actionsDues = List.copyOf(actionsDues);
        aVenir = List.copyOf(aVenir);
        brouillons = List.copyOf(brouillons);
    }

    /**
     * Envois d'une semaine, du lundi au dimanche.
     *
     * @param partielle le suivi a commencé après le lundi : la semaine ne se compare pas à
     *     l'objectif, et un faible volume n'y est pas un retard (UC-16 A1, RG-22)
     * @param enCours la semaine contient le jour courant
     */
    public record Semaine(LocalDate lundi, int envoyees, boolean partielle, boolean enCours) {}

    public static TableauDeBord calculer(
            Collection<Candidature> candidatures,
            LocalDate aujourdhui,
            ReglesRelance regles,
            int objectifHebdomadaire) {
        Map<Etape, Integer> repartition = new EnumMap<>(Etape.class);
        for (Etape e : Etape.values()) {
            repartition.put(e, 0);
        }
        candidatures.forEach(c -> repartition.merge(c.etape(), 1, Integer::sum));

        return new TableauDeBord(
                repartition,
                objectifHebdomadaire,
                semaines(candidatures, aujourdhui),
                aLEtape(candidatures, Etape.ENVOYEE),
                Echeances.listeDeTravail(candidatures, aujourdhui, regles),
                Echeances.aVenir(candidatures, aujourdhui, HORIZON_JOURS, regles),
                aLEtape(candidatures, Etape.REPEREE));
    }

    // --- interne ---

    private static List<Semaine> semaines(Collection<Candidature> candidatures, LocalDate aujourdhui) {
        LocalDate debutDuSuivi = candidatures.stream()
                .map(Candidature::dateCreation)
                .min(Comparator.naturalOrder())
                .orElse(aujourdhui);
        LocalDate lundiCourant = lundi(aujourdhui);
        LocalDate premierLundi = lundi(debutDuSuivi);
        if (premierLundi.isBefore(lundiCourant.minusWeeks(SEMAINES_AFFICHEES - 1))) {
            premierLundi = lundiCourant.minusWeeks(SEMAINES_AFFICHEES - 1);
        }
        List<LocalDate> envois = candidatures.stream().flatMap(TableauDeBord::envois).toList();

        return Stream.iterate(premierLundi, l -> !l.isAfter(lundiCourant), l -> l.plusWeeks(1))
                .map(l -> new Semaine(
                        l,
                        (int) envois.stream().filter(d -> lundi(d).equals(l)).count(),
                        debutDuSuivi.isAfter(l),
                        l.equals(lundiCourant)))
                .toList();
    }

    /**
     * Dates d'envoi d'une candidature : création à « envoyée » ou passage à « envoyée ». Une
     * réouverture vers « envoyée » n'est pas un nouvel envoi.
     */
    private static Stream<LocalDate> envois(Candidature c) {
        return c.journal().stream()
                .filter(e -> e instanceof Evenement.Creation cr && cr.etape() == Etape.ENVOYEE
                        || e instanceof Evenement.ChangementEtape ch && ch.vers() == Etape.ENVOYEE)
                .map(Evenement::date);
    }

    private static List<Candidature> aLEtape(Collection<Candidature> candidatures, Etape etape) {
        return candidatures.stream()
                .filter(c -> c.etape() == etape)
                .sorted(Comparator.comparing(Candidature::dateEntreeEtape)
                        .thenComparing(Candidature::entreprise))
                .toList();
    }

    private static LocalDate lundi(LocalDate jour) {
        return jour.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
