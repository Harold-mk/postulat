package postulat.rendu;

import java.util.List;

/**
 * Un niveau de compaction de la mise en page, du plus aéré au plus serré. RG-26 : avant de retirer la
 * moindre phrase, on compacte ; mais jamais en deçà de 9 points, sous lesquels un CV ne se lit plus.
 *
 * @param corps taille du texte courant, en points
 * @param interligne hauteur d'une ligne rapportée à la taille du texte
 * @param marge marge de chaque côté de la page, en points (72 points font 25,4 mm)
 * @param espace écart entre deux sections ou deux blocs, en points
 */
record Gabarit(String nom, float corps, float interligne, float marge, float espace) {

    static final List<Gabarit> DU_PLUS_AERE_AU_PLUS_SERRE = List.of(
            new Gabarit("aéré", 10.5f, 1.30f, 54f, 10f),
            new Gabarit("standard", 10f, 1.22f, 46f, 8f),
            new Gabarit("serré", 9.5f, 1.15f, 40f, 6f),
            new Gabarit("compact", 9f, 1.10f, 34f, 4f));
}
