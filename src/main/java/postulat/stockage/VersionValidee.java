package postulat.stockage;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import postulat.production.Instantane;

/**
 * Une version validée du dossier (UC-09) : figée (RG-15), avec l'instantané des faits qu'elle cite
 * (RG-16) et le répertoire où ses fichiers ont été écrits (CA-6.15).
 *
 * @param version 1 pour la première validée
 * @param repertoire chemin absolu : il peut avoir été déplacé depuis, le contenu reste lisible ici
 */
public record VersionValidee(int version, DossierGenere dossier, Instant valideLe, String repertoire, List<Instantane> faits) {

    public VersionValidee {
        if (version < 1) {
            throw new IllegalArgumentException("Les versions validées se numérotent à partir de 1 : " + version);
        }
        Objects.requireNonNull(dossier);
        Objects.requireNonNull(valideLe);
        Objects.requireNonNull(repertoire);
        faits = List.copyOf(faits);
    }
}
