package postulat.production;

import java.util.List;

/**
 * Provenance d'une assertion (glossaire § 4.1, § 4.3-e). Une assertion porte exactement une origine
 * (RG-02) : c'est un champ unique d'un type fermé, donc il n'y a pas de cas « aucune » ni « deux ».
 */
public sealed interface Origine {

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
     */
    record Auteur() implements Origine {}
}
