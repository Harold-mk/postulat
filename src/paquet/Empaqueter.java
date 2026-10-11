import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Fait de postulat une application Windows portable (itération 9c) : un dossier {@code Postulat\} qui
 * porte son propre Java, réduit aux modules dont il se sert, et JavaFX en modules nommés — la
 * configuration que JavaFX prend en charge. Deux lanceurs : {@code Postulat.exe} ouvre la fenêtre, sans
 * console ; {@code postulat-cli.exe} est la ligne de commande. Le dossier est vérifié, puis zippé.
 *
 * <p>Lancé par Maven : {@code mvnw -Ppaquet package}. Arguments : le répertoire {@code target}, la
 * version du projet, et le chemin de classes d'exécution du projet.
 */
public final class Empaqueter {

    /** Ce que le jdeps du JDK trouve dans le code et ses dépendances, plus ce qu'il ne peut pas voir. */
    private static final List<String> MODULES = List.of(
            "java.base", "java.desktop", "java.logging", "java.net.http", "java.sql", "jdk.unsupported",
            // les dates en français : sans ces données, « dimanche » s'écrirait « Sunday »
            "jdk.localedata",
            // les pages de codes des consoles Windows, IBM850 et les autres
            "jdk.charsets",
            "javafx.controls");

    /**
     * Les options de la machine virtuelle, pour les deux lanceurs. Le Java embarqué est celui qui
     * construit : une option qu'il ne connaîtrait pas l'empêcherait de démarrer.
     */
    private static List<String> optionsJava() {
        List<String> options = new ArrayList<>();
        // JavaFX et sqlite-jdbc chargent leurs bibliothèques natives : c'est voulu, Java n'a pas à en avertir.
        options.add("--enable-native-access=javafx.graphics,ALL-UNNAMED");
        if (Runtime.version().feature() >= 23) {
            // Le moteur de rendu de JavaFX 21 se sert encore de sun.misc.Unsafe ; Java 24+ le signale à chaque
            // lancement. L'option n'existe que depuis Java 23.
            options.add("--sun-misc-unsafe-memory-access=allow");
        }
        return options;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage : Empaqueter TARGET VERSION CHEMIN_DE_CLASSES");
        }
        Path target = Path.of(args[0]);
        String version = args[1].replace("-SNAPSHOT", "");
        List<Path> chemin = Arrays.stream(args[2].split(File.pathSeparator)).filter(s -> !s.isBlank()).map(Path::of).toList();

        Path paquet = target.resolve("paquet");
        effacer(paquet);
        Path entree = Files.createDirectories(paquet.resolve("entree"));
        Path javafx = Files.createDirectories(paquet.resolve("javafx"));

        // 1. Le code de postulat en un jar, et ses dépendances à côté ; JavaFX à part, en modules.
        Path classes = target.resolve("classes");
        Path manifeste = Files.writeString(paquet.resolve("MANIFEST.MF"),
                "Implementation-Title: postulat\nImplementation-Version: " + version + "\n");
        outil("jar", "--create", "--file", entree.resolve("postulat.jar").toString(), "--manifest", manifeste.toString(),
                "-C", classes.toString(), ".");
        List<Path> modulesJavafx = new ArrayList<>();
        for (Path p : chemin) {
            String nom = p.getFileName().toString();
            if (p.equals(classes) || Files.isDirectory(p)) {
                continue;
            }
            if (nom.startsWith("javafx-")) {
                if (nom.endsWith("-win.jar")) { // les jars sans classificateur sont vides : JavaFX y choisit le système
                    modulesJavafx.add(Files.copy(p, javafx.resolve(nom)));
                }
                continue;
            }
            Files.copy(p, entree.resolve(nom));
        }
        if (modulesJavafx.size() != 3) {
            throw new IllegalStateException("JavaFX pour Windows attendu en trois modules, trouvé : " + modulesJavafx);
        }
        etape("Code et dépendances : " + fichiers(entree) + " jars ; JavaFX : " + modulesJavafx.size() + " modules.");

        // 2. Un Java réduit aux modules utiles, JavaFX compris.
        Path runtime = paquet.resolve("runtime");
        Path jmods = Path.of(System.getProperty("java.home"), "jmods");
        outil("jlink",
                "--module-path", (Files.isDirectory(jmods) ? jmods + File.pathSeparator : "") + javafx,
                "--add-modules", String.join(",", MODULES),
                "--include-locales=fr,en",
                // les bibliothèques natives de JavaFX vont dans bin, ci-dessous : inutile de les garder dans les modules
                "--exclude-resources=glob:/javafx.graphics/*.dll",
                "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=zip-6",
                "--generate-cds-archive",
                "--output", runtime.toString());
        // L'archive CDS fait gagner un dixième de seconde à chaque commande ; sa jumelle « nocoops » ne sert
        // qu'au-delà de 32 Go de mémoire, et pèse 15 Mo.
        Files.deleteIfExists(runtime.resolve("bin").resolve("server").resolve("classes_nocoops.jsa"));
        // JavaFX cherche ses bibliothèques à côté de Java ; sinon il les extrairait dans le profil de l'utilisateur.
        int natives = 0;
        for (Path jar : modulesJavafx) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (ZipEntry e : zip.stream().filter(e -> !e.getName().contains("/") && e.getName().endsWith(".dll")).toList()) {
                    Path cible = runtime.resolve("bin").resolve(e.getName());
                    if (Files.exists(cible)) {
                        continue; // celles que Java fournit déjà, comme la bibliothèque C, restent les siennes
                    }
                    try (InputStream in = zip.getInputStream(e)) {
                        Files.copy(in, cible);
                    }
                    natives++;
                }
            }
        }
        etape("Java réduit : " + taille(runtime) + " ; " + natives + " bibliothèques natives de JavaFX.");

        // 3. Les deux lanceurs.
        Path image = paquet.resolve("image");
        Path console = Files.writeString(paquet.resolve("postulat-cli.properties"),
                "win-console=true\narguments=\ndescription=postulat en ligne de commande\n");
        List<String> jpackage = new ArrayList<>(List.of(
                "--type", "app-image", "--name", "Postulat", "--app-version", version,
                "--vendor", "postulat", "--description", "Suivi d'une campagne de candidatures de stage",
                "--input", entree.toString(), "--main-jar", "postulat.jar", "--main-class", "postulat.cli.Postulat",
                "--runtime-image", runtime.toString(),
                "--icon", Path.of("src", "paquet", "postulat.ico").toString(),
                "--arguments", "bureau",
                "--add-launcher", "postulat-cli=" + console,
                "--dest", image.toString()));
        optionsJava().forEach(o -> jpackage.addAll(List.of("--java-options", o)));
        outil("jpackage", jpackage.toArray(String[]::new));
        Path application = image.resolve("Postulat");
        etape("Application : " + application + " (" + taille(application) + ").");

        // 4. Vérifier ce qui a été construit, avec le lanceur en console, sur des données jetables.
        verifier(application, paquet);

        // 5. Le dossier, zippé.
        Path zip = target.resolve("Postulat-" + version + "-windows.zip");
        zipper(application, zip);
        etape("Prêt : " + zip.toAbsolutePath() + " (" + taille(zip) + ").");
    }

    /** Le lanceur en console répond, l'interface s'ouvre hors écran, et rien n'avertit sur la sortie d'erreur. */
    static void verifier(Path application, Path paquet) throws Exception {
        Path cli = application.resolve("postulat-cli.exe");
        Path donnees = Files.createDirectories(paquet.resolve("donnees-essai"));
        Resultat aide = lancer(cli);
        exiger(aide.code() == 0 && aide.sortie().contains("Usage : postulat"), "le lanceur en console, sans argument, montre l'aide",
                aide);
        Resultat version = lancer(cli, "--version");
        exiger(version.code() == 0 && version.sortie().startsWith("postulat "), "--version donne la version", version);
        Resultat liste = lancer(cli, "--donnees", donnees.toString(), "liste");
        exiger(liste.code() == 0, "la base se crée et se lit", liste);
        Resultat bureau = lancer(cli, "--donnees", donnees.toString(), "bureau", "--verifier");
        // sans accent : le lanceur en console écrit dans la page de codes de la console, pas forcément celle du système
        exiger(bureau.code() == 0 && bureau.sortie().contains("Interface de bureau pr"), "l'interface s'ouvre hors écran", bureau);
        // sans les données de langue, « dimanche » s'écrirait « Sunday »
        exiger(bureau.sortie().matches("(?s).*nous sommes le (lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche) [0-9]+ .*"),
                "les dates s'écrivent en français", bureau);
        for (Resultat r : List.of(aide, version, liste, bureau)) {
            exiger(r.erreurs().isBlank(), "aucun avertissement sur la sortie d'erreur", r);
        }
        effacer(donnees);
        etape("Vérifié : " + version.sortie().strip() + " ; " + bureau.sortie().strip());
    }

    record Resultat(List<String> commande, int code, String sortie, String erreurs) {}

    static Resultat lancer(Path programme, String... arguments) throws Exception {
        List<String> commande = new ArrayList<>(List.of(programme.toString()));
        commande.addAll(List.of(arguments));
        // L'entrée reste ouverte et muette, comme une console où l'on n'a encore rien tapé : une commande qui la
        // lirait d'avance attendrait indéfiniment, et la vérification le dirait.
        ProcessBuilder pb = new ProcessBuilder(commande);
        // un environnement Windows ordinaire, comme au menu Démarrer : sous Git Bash, ces variables font croire à
        // picocli qu'il écrit dans un terminal Unix — couleurs, et appel à stty
        pb.environment().keySet().removeIf(k -> List.of("TERM", "OSTYPE", "MSYSTEM", "SHELL").contains(k.toUpperCase()));
        // et jamais d'appel payant pendant une vérification : sans clé, aucun modèle ne répond
        pb.environment().keySet().removeIf(k -> k.equalsIgnoreCase("ANTHROPIC_API_KEY"));
        Process p = pb.start();
        // lues en parallèle : un tampon plein bloquerait le programme
        String[] lus = new String[2];
        Thread a = Thread.ofVirtual().start(() -> lus[0] = lire(p.getInputStream()));
        Thread b = Thread.ofVirtual().start(() -> lus[1] = lire(p.getErrorStream()));
        if (!p.waitFor(2, TimeUnit.MINUTES)) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            throw new IllegalStateException("Sans réponse après deux minutes, attend-elle l'entrée ? " + commande);
        }
        p.getOutputStream().close();
        a.join();
        b.join();
        return new Resultat(commande, p.exitValue(), lus[0], lus[1]);
    }

    /**
     * Le lanceur en console écrit dans la page de codes de la console dont il hérite : UTF-8 sous certains
     * terminaux, celle du système ailleurs. Ce qui se lit en UTF-8 est pris tel quel, le reste dans
     * l'encodage du système.
     */
    private static String lire(InputStream in) {
        byte[] octets;
        try {
            octets = in.readAllBytes();
        } catch (IOException e) {
            return "(illisible : " + e.getMessage() + ")";
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(octets)).toString();
        } catch (CharacterCodingException pasUtf8) {
            return new String(octets, Charset.forName(System.getProperty("native.encoding")));
        }
    }

    private static void exiger(boolean condition, String quoi, Resultat r) {
        if (!condition) {
            throw new IllegalStateException("Vérification échouée : " + quoi + "." + System.lineSeparator() + "  commande : "
                    + String.join(" ", r.commande()) + System.lineSeparator() + "  code : " + r.code() + System.lineSeparator()
                    + "  sortie : " + r.sortie().strip() + System.lineSeparator() + "  erreurs : " + r.erreurs().strip());
        }
    }

    /** Un outil du JDK — jar, jlink, jpackage —, dans ce processus ; son échec arrête tout, avec ce qu'il a dit. */
    static void outil(String nom, String... arguments) {
        ToolProvider outil = ToolProvider.findFirst(nom)
                .orElseThrow(() -> new IllegalStateException("Outil introuvable dans ce JDK : " + nom));
        StringWriter dit = new StringWriter();
        PrintWriter ecrit = new PrintWriter(dit);
        int code = outil.run(ecrit, ecrit, arguments);
        ecrit.flush();
        if (code != 0) {
            throw new IllegalStateException(nom + " a échoué (code " + code + ") :" + System.lineSeparator() + dit);
        }
    }

    static void zipper(Path dossier, Path zip) throws IOException {
        Files.deleteIfExists(zip);
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out);
                Stream<Path> chemins = Files.walk(dossier)) {
            for (Path p : chemins.filter(Files::isRegularFile).sorted().toList()) {
                String nom = dossier.getFileName() + "/" + dossier.relativize(p).toString().replace(File.separatorChar, '/');
                z.putNextEntry(new ZipEntry(nom));
                Files.copy(p, z);
                z.closeEntry();
            }
        }
    }

    static void effacer(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> chemins = Files.walk(p)) {
            for (Path x : chemins.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(x);
            }
        }
    }

    static long fichiers(Path dossier) throws IOException {
        try (Stream<Path> l = Files.list(dossier)) {
            return l.count();
        }
    }

    static String taille(Path p) throws IOException {
        long octets;
        if (Files.isDirectory(p)) {
            try (Stream<Path> chemins = Files.walk(p)) {
                octets = chemins.filter(Files::isRegularFile).mapToLong(x -> x.toFile().length()).sum();
            }
        } else {
            octets = Files.size(p);
        }
        return String.format("%.1f Mo", octets / 1_000_000.0);
    }

    static void etape(String texte) {
        System.out.println("[paquet] " + texte);
    }
}
