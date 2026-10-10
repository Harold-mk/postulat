package postulat.production;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Un CV tiré du profil entier, sans offre ni modèle : de quoi voir la mise en page sur ses propres faits
 * avant que la génération n'existe. Ce n'est pas un CV ciblé — tout y est, dans l'ordre du profil —,
 * mais chaque phrase y cite déjà le fait dont elle vient.
 */
public final class Apercu {

    private Apercu() {}

    public static Cv de(Profil profil) {
        int[] rang = {1};
        List<Cv.Section> sections = new ArrayList<>();

        List<Cv.Contenu> experiences = profil.experiences().stream()
                .map(e -> (Cv.Contenu) bloc(e.id(), joindre(" — ", e.poste(), e.organisation()),
                        joindre(" · ", e.periode(), e.duree(), e.lieu()), e.realisations(), rang))
                .toList();
        if (!experiences.isEmpty()) {
            sections.add(new Cv.Section("Expériences", experiences));
        }

        List<Cv.Contenu> formations = profil.formations().stream()
                .map(f -> (Cv.Contenu) bloc(f.id(), joindre(" — ", f.diplome(), f.etablissement()),
                        joindre(" · ", f.periode(), f.details()), f.realisations(), rang))
                .toList();
        if (!formations.isEmpty()) {
            sections.add(new Cv.Section("Formation", formations));
        }

        if (!profil.competences().isEmpty()) {
            String liste = profil.competences().stream()
                    .map(c -> c.niveau().isBlank() ? c.nom() : c.nom() + " (" + c.niveau() + ")")
                    .collect(Collectors.joining(" · "));
            List<String> ids = profil.competences().stream().map(Competence::id).toList();
            sections.add(new Cv.Section("Compétences", List.of(new Cv.Ligne(new Assertion(liste, new Origine.Faits(ids))))));
        }
        return new Cv(profil.identite(), sections);
    }

    private static Cv.Bloc bloc(String id, String intitule, String precision, List<Realisation> realisations, int[] rang) {
        List<Cv.Puce> puces = new ArrayList<>();
        for (Realisation r : realisations) {
            String texte = r.resultat().isBlank() ? r.tache() : r.tache() + " : " + r.resultat();
            puces.add(new Cv.Puce(new Assertion(texte, Origine.Faits.de(r.id())), rang[0]++));
        }
        Optional<Assertion> laPrecision = precision.isBlank()
                ? Optional.empty()
                : Optional.of(new Assertion(precision, Origine.Faits.de(id)));
        return new Cv.Bloc(new Assertion(intitule, Origine.Faits.de(id)), laPrecision, puces);
    }

    private static String joindre(String separateur, String... parties) {
        return Arrays.stream(parties).filter(p -> !p.isBlank()).collect(Collectors.joining(separateur));
    }
}
