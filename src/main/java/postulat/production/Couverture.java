package postulat.production;

import java.util.List;
import java.util.Objects;

/**
 * Rattachement d'une exigence aux faits du profil qui la démontrent (glossaire § 4.1, CA-2.2). Sans
 * fait, c'est une lacune : elle se signale, elle ne se comble jamais par de l'écriture.
 *
 * @param faits identifiants des faits qui couvrent l'exigence ; vide pour une lacune
 * @param justification pourquoi ces faits la couvrent, ou ce qui manque
 */
public record Couverture(Exigence exigence, List<String> faits, String justification) {

    public Couverture {
        Objects.requireNonNull(exigence);
        faits = List.copyOf(faits);
        Objects.requireNonNull(justification);
    }

    public boolean couverte() {
        return !faits.isEmpty();
    }
}
