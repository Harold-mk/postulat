package postulat.cli;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Collectors;
import picocli.CommandLine;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;
import postulat.ia.Gamme;
import postulat.suivi.Canal;
import postulat.suivi.Etape;
import postulat.suivi.TypeEntretien;

/**
 * Lecture des saisies : on tape comme on parle. « offre reçue », « offre_recue » et « OFFRE_RECUE »
 * désignent la même étape ; une date s'écrit 2026-10-01, 01/10/2026, hier ou aujourd'hui.
 */
final class Conversions {

    /** Accepte 01/10/2026 comme 1/10/2026 ; refuse le 31 février. */
    private static final DateTimeFormatter FRANCAIS =
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT);

    /** Marque d'ordre des octets : invisible, ajoutée par certains éditeurs et par PowerShell. */
    static final char MARQUE_D_ORDRE = '﻿';

    private Conversions() {}

    static String sansMarqueDOrdre(String texte) {
        return !texte.isEmpty() && texte.charAt(0) == MARQUE_D_ORDRE ? texte.substring(1) : texte;
    }

    static void enregistrer(CommandLine ligne, Clock horloge) {
        ligne.registerConverter(Etape.class, parLibelle(Etape.class, Etape::libelle, "étape inconnue"));
        ligne.registerConverter(TypeEntretien.class, parLibelle(TypeEntretien.class, Enum::name, "type d'entretien inconnu"));
        ligne.registerConverter(Canal.class, parLibelle(Canal.class, Enum::name, "canal inconnu"));
        ligne.registerConverter(Gamme.class, parLibelle(Gamme.class, Enum::name, "gamme inconnue"));
        ligne.registerConverter(LocalDate.class, texte -> date(texte, LocalDate.now(horloge)));
    }

    static LocalDate date(String texte, LocalDate aujourdhui) {
        String cle = cle(texte);
        if (cle.equals("aujourd_hui") || cle.equals("aujourdhui")) {
            return aujourdhui;
        }
        if (cle.equals("hier")) {
            return aujourdhui.minusDays(1);
        }
        try {
            return texte.contains("/") ? LocalDate.parse(texte.strip(), FRANCAIS) : LocalDate.parse(texte.strip());
        } catch (DateTimeParseException e) {
            throw new TypeConversionException("date illisible « " + texte
                    + " » : écrire 2026-10-01, 01/10/2026, hier ou aujourd'hui.");
        }
    }

    /** Forme de comparaison : sans accents ni casse, espaces et tirets fondus en « _ ». */
    static String cle(String texte) {
        return Normalizer.normalize(texte.strip(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s'’-]+", "_");
    }

    private static <E extends Enum<E>> ITypeConverter<E> parLibelle(
            Class<E> type, Function<E, String> libelle, String inconnu) {
        return texte -> Arrays.stream(type.getEnumConstants())
                .filter(e -> cle(libelle.apply(e)).equals(cle(texte)) || cle(e.name()).equals(cle(texte)))
                .findFirst()
                .orElseThrow(() -> new TypeConversionException(inconnu + " « " + texte + " ». Possibles : "
                        + Arrays.stream(type.getEnumConstants()).map(e -> libelle.apply(e).toLowerCase(Locale.ROOT))
                                .collect(Collectors.joining(", ")) + "."));
    }
}
