package postulat.production;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Provenance d'une assertion (glossaire § 4.1, § 4.3-e). Une assertion porte exactement une origine
 * (RG-02) : c'est un champ unique d'un type fermé, donc il n'y a pas de cas « aucune » ni « deux ».
 *
 * <p>Trois origines, et non deux comme le prévoyait le cadrage : une lettre parle aussi de l'entreprise,
 * et ces phrases ne viennent ni du profil ni de l'auteur, mais du texte de l'offre (lacune L-03).
 */
public sealed interface Origine {

    /**
     * Tirée du texte de l'offre : ce que l'entreprise fait et cherche (CA-4.3, lacune L-03). Elle ne
     * dit que ce que l'offre dit, et n'affirme rien sur le candidat.
     */
    record Offre() implements Origine {}

    /**
     * Dérivée des faits du profil désignés par leurs identifiants (CA-3.1). Une liste vide est
     * admise ici pour que la vérification la signale avec le reste, au lieu d'échouer à la lecture.
     */
    record Faits(List<String> ids) implements Origine {

        public Faits {
            ids = List.copyOf(ids);
        }

        public static Faits de(String... ids) {
            return new Faits(List.of(ids));
        }
    }

    /**
     * Écrite par l'auteur lui-même (CA-3.8). Traçable à lui, non au profil : la vérification ne la
     * rejette pas, et le modèle ne la réécrit jamais sans son accord (RG-27).
     *
     * @param verseeDans le fait du profil que l'auteur en a tiré (UC-04), s'il l'a fait. La phrase n'en
     *     devient pas pour autant d'origine « faits » : ce fait n'existait pas quand elle a été écrite
     *     (RG-23). Mais la régénération suivante ne la reporte plus : elle s'appuie sur le fait.
     */
    record Auteur(Optional<String> verseeDans) implements Origine {

        public Auteur {
            Objects.requireNonNull(verseeDans);
        }

        /** Une phrase de l'auteur, pas encore versée au profil. */
        public Auteur() {
            this(Optional.empty());
        }
    }
}
