package postulat.ia;

import java.time.Clock;
import java.util.function.Consumer;

/**
 * Journalise chaque appel d'un modèle, réussi ou non, avant de rendre la main (EN-5, EN-12) : le coût
 * se mesure dès le premier appel (R5), et toute génération reste reconstituable.
 *
 * <p>Un décorateur de {@link Modele}, non une seconde abstraction : le journal est une simple fonction
 * qui reçoit l'appel.
 */
public final class ModeleJournalise implements Modele {

    private final Modele modele;
    private final Consumer<AppelModele> journal;
    private final Clock horloge;

    public ModeleJournalise(Modele modele, Consumer<AppelModele> journal, Clock horloge) {
        this.modele = modele;
        this.journal = journal;
        this.horloge = horloge;
    }

    @Override
    public <T> Reponse<T> demander(Demande demande, Class<T> forme) {
        var debut = horloge.instant();
        Reponse<T> reponse;
        try {
            reponse = modele.demander(demande, forme);
        } catch (ErreurModele e) {
            journal.accept(AppelModele.echoue(debut, demande, e));
            throw e;
        }
        journal.accept(AppelModele.reussi(debut, demande, reponse));
        return reponse;
    }
}
