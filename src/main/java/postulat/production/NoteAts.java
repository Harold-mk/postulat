package postulat.production;

import java.util.List;

/**
 * Ce qu'un filtre ATS verrait du document (CA-5.1), et pourquoi.
 *
 * @param note de 0 à 100 : la part pondérée des exigences retrouvées telles quelles dans le texte
 * @param lisible faux si le texte extrait est vide : un ATS n'y lirait rien, quel que soit le fond
 * @param presentes exigences retrouvées, dans l'ordre de l'offre
 * @param absentes exigences introuvables, dans l'ordre de l'offre
 */
public record NoteAts(int note, boolean lisible, List<Exigence> presentes, List<Exigence> absentes) {

    public NoteAts {
        if (note < 0 || note > 100) {
            throw new IllegalArgumentException("Une note ATS va de 0 à 100 : " + note);
        }
        presentes = List.copyOf(presentes);
        absentes = List.copyOf(absentes);
    }

    /** Les absences qu'un filtre strict tiendrait pour éliminatoires. */
    public List<Exigence> obligatoiresAbsentes() {
        return absentes.stream().filter(Exigence::obligatoire).toList();
    }
}
