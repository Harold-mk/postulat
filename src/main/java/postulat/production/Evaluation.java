package postulat.production;

import java.util.Objects;
import java.util.Optional;

/**
 * L'évaluation d'un dossier (US-5) : la note ATS, déterministe, sur le texte relu du CV ; l'avis du
 * recruteur ; et leur note globale pondérée, comparée au seuil (CA-5.3).
 *
 * @param ats absente quand l'offre n'a aucune exigence : la note globale est alors celle du recruteur
 */
public record Evaluation(Optional<NoteAts> ats, AvisRecruteur avis, ReglesGeneration regles) {

    public Evaluation {
        Objects.requireNonNull(ats);
        Objects.requireNonNull(avis);
        Objects.requireNonNull(regles);
    }

    public int noteGlobale() {
        return ats.map(a -> Math.round((regles.poidsAts() * a.note() + (100 - regles.poidsAts()) * avis.note()) / 100f))
                .orElse(avis.note());
    }

    public boolean seuilAtteint() {
        return noteGlobale() >= regles.seuil();
    }
}
