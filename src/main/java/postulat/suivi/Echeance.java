package postulat.suivi;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Date à laquelle une action devient attendue, avec le contexte nécessaire pour agir sans rien
 * rouvrir (CA-7.6). Une échéance atteinte et non traitée est une <em>action due</em>.
 *
 * @param depuis ancienneté de la candidature, pour le tri : date d'envoi si elle est à l'étape
 *     « envoyée », date de création sinon
 */
public record Echeance(
        UUID candidatureId,
        String entreprise,
        String poste,
        Etape etape,
        Motif motif,
        LocalDate date,
        LocalDate depuis) {

    public Echeance {
        Objects.requireNonNull(candidatureId);
        Objects.requireNonNull(etape);
        Objects.requireNonNull(motif);
        Objects.requireNonNull(date);
        Objects.requireNonNull(depuis);
    }

    /** Vrai si l'action est due ce jour-là. */
    public boolean estAtteinte(LocalDate aujourdhui) {
        return !date.isAfter(aujourdhui);
    }

    /** Jours écoulés depuis l'échéance ; négatif tant qu'elle est à venir. */
    public long joursDeRetard(LocalDate aujourdhui) {
        return ChronoUnit.DAYS.between(date, aujourdhui);
    }

    /** Ce qu'il y a à faire. Liste fermée : un motif ajouté sans être traité ne compile pas. */
    public sealed interface Motif {

        /** Une relance de rang 1 ou 2 après le silence réglementaire (CA-7.1, 7.2). */
        record Relancer(int rang) implements Motif {}

        /** Toutes les relances sont faites et le silence persiste : on propose, on n'applique pas (CA-7.3). */
        record ProposerSansSuite() implements Motif {}

        /** Veille d'un entretien planifié (CA-7.4). */
        record PreparerEntretien(TypeEntretien type, LocalDate le) implements Motif {}

        /** Jour d'un entretien planifié : remercier après l'échange (CA-7.4). */
        record Remercier(TypeEntretien type, LocalDate le) implements Motif {}

        /** Le retour promis par l'entreprise est attendu ce jour-là (CA-7.5). */
        record RetourPromis() implements Motif {}
    }
}
