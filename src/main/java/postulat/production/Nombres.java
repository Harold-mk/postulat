package postulat.production;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Les données chiffrées d'un texte, sous une forme comparable (RG-03) : « 1 500 », « 1500 » et
 * « 1 500,0 » donnent toutes « 1500 », « cinq » donne « 5 ».
 *
 * <p>Deux choix de rigueur. Une date ne compte que par son année : « 2025-06 » justifie « en 2025 »,
 * pas « 6 mois » ni « 6 % ». Et les nombres en lettres autres que les plus simples — « vingt-cinq »,
 * « une centaine » — restent des mots, que les faits cités doivent contenir tels quels : mieux vaut
 * refuser une formulation que laisser passer une approximation inventée.
 */
final class Nombres {

    /** AAAA-MM, AAAA-MM-JJ, MM/AAAA, JJ/MM/AAAA. Le groupe nommé est l'année. */
    private static final Pattern DATE = Pattern.compile(
            "(?<![\\d.,])(?:(?<an1>\\d{4})-\\d{1,2}(?:-\\d{1,2})?|\\d{1,2}/(?:\\d{1,2}/)?(?<an2>\\d{4}))(?![\\d.,])");

    /** 1 500 ou 1500, éventuellement décimal : 1,5 ou 1.5. Séparateur de milliers : espace ordinaire ou insécable. */
    private static final Pattern CHIFFRES = Pattern.compile("\\d{1,3}(?:[ \\u00A0\\u202F]\\d{3})+(?:[.,]\\d+)?|\\d+(?:[.,]\\d+)?");

    private static final Pattern MOT = Pattern.compile("\\p{L}+(?:-\\p{L}+)*");

    private static final Map<String, String> SIMPLES = Map.ofEntries(
            Map.entry("zero", "0"), Map.entry("deux", "2"), Map.entry("trois", "3"), Map.entry("quatre", "4"),
            Map.entry("cinq", "5"), Map.entry("six", "6"), Map.entry("sept", "7"), Map.entry("huit", "8"),
            Map.entry("neuf", "9"), Map.entry("dix", "10"), Map.entry("onze", "11"), Map.entry("douze", "12"),
            Map.entry("treize", "13"), Map.entry("quatorze", "14"), Map.entry("quinze", "15"), Map.entry("seize", "16"),
            Map.entry("vingt", "20"), Map.entry("trente", "30"), Map.entry("quarante", "40"),
            Map.entry("cinquante", "50"), Map.entry("soixante", "60"), Map.entry("cent", "100"), Map.entry("mille", "1000"));

    /** Mots qui disent une quantité sans être un nombre simple : ils doivent figurer tels quels dans les faits. */
    private static final Set<String> AUTRES = Set.of(
            "vingts", "cents", "septante", "octante", "huitante", "nonante", "million", "millions", "milliard",
            "milliards", "dizaine", "dizaines", "douzaine", "douzaines", "quinzaine", "quinzaines", "vingtaine",
            "vingtaines", "trentaine", "trentaines", "quarantaine", "quarantaines", "cinquantaine", "cinquantaines",
            "soixantaine", "soixantaines", "centaine", "centaines", "millier", "milliers");

    /** Liaisons admises à l'intérieur d'un nombre composé : vingt-et-un. */
    private static final Set<String> LIAISONS = Set.of("et", "un", "une");

    private Nombres() {}

    static Set<String> extraire(String texte) {
        Set<String> nombres = new LinkedHashSet<>();
        String reste = sansDates(texte, nombres);
        Matcher chiffres = CHIFFRES.matcher(reste);
        while (chiffres.find()) {
            nombres.add(normaliser(chiffres.group()));
        }
        Matcher mots = MOT.matcher(sansAccents(reste).replaceAll("pour cent", "%"));
        while (mots.find()) {
            String mot = mots.group();
            if (SIMPLES.containsKey(mot)) {
                nombres.add(SIMPLES.get(mot));
            } else if (estNombreEnLettres(mot)) {
                nombres.add(mot);
            }
        }
        return nombres;
    }

    /** Retire chaque date du texte en ne gardant que son année. */
    private static String sansDates(String texte, Set<String> nombres) {
        Matcher date = DATE.matcher(texte);
        StringBuilder reste = new StringBuilder();
        while (date.find()) {
            String annee = date.group("an1") != null ? date.group("an1") : date.group("an2");
            nombres.add(annee);
            date.appendReplacement(reste, " ");
        }
        date.appendTail(reste);
        return reste.toString();
    }

    private static boolean estNombreEnLettres(String mot) {
        String[] parties = mot.split("-");
        boolean auMoinsUnNombre = Arrays.stream(parties).anyMatch(p -> SIMPLES.containsKey(p) || AUTRES.contains(p));
        boolean queDesNombres = Arrays.stream(parties)
                .allMatch(p -> SIMPLES.containsKey(p) || AUTRES.contains(p) || LIAISONS.contains(p));
        return auMoinsUnNombre && queDesNombres;
    }

    private static String normaliser(String nombre) {
        String brut = nombre.replaceAll("[ \\u00A0\\u202F]", "").replace(',', '.');
        return new BigDecimal(brut).stripTrailingZeros().toPlainString();
    }

    private static String sansAccents(String texte) {
        return Normalizer.normalize(texte, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
