package postulat.rendu;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import postulat.production.Assertion;
import postulat.production.Cv;
import postulat.production.Identite;
import postulat.production.Lettre;

/**
 * Le CV et la lettre au format Word (CA-6.8), écrits à la main : un DOCX est une archive zip de quatre
 * fichiers XML, et ces quatre-là suffisent. Une bibliothèque de plus de 15 Mo pour écrire une page n'en
 * vaudrait pas la peine.
 *
 * <p>La mise en page reprend celle du PDF — même gabarit, mêmes marges, mêmes tailles, Arial à la place
 * d'Helvetica, dont elle a les mesures —, mais Word recalcule ses lignes lui-même : c'est le PDF qui
 * garantit la page unique (RG-05). Le DOCX sert aux formulaires qui l'exigent, et à qui veut retoucher
 * une virgule hors de l'application.
 *
 * <p>L'archive est reproductible : mêmes entrées, mêmes octets. Ses dates sont fixes, et elle ne dit
 * rien de la machine qui l'a écrite.
 */
final class Docx {

    private static final LocalDateTime DATE_FIXE = LocalDateTime.of(2000, 1, 1, 0, 0);

    /** Twips par point : Word compte en vingtièmes de point. */
    private static final int TWIPS = 20;

    private Docx() {}

    static byte[] cv(Cv cv, Gabarit g) {
        Corps c = new Corps(g);
        entete(c, cv.entete(), g);
        for (Cv.Section s : cv.sections()) {
            if (s.contenus().isEmpty()) {
                continue;
            }
            c.paragraphe(g.espace(), 0, 0, true, run(s.titre().toUpperCase(Locale.FRENCH), true, g.corps() + 1));
            boolean premier = true;
            for (Cv.Contenu contenu : s.contenus()) {
                float avant = premier ? 0 : g.espace() / 2;
                premier = false;
                switch (contenu) {
                    case Cv.Bloc b -> {
                        String suite = b.precision().map(p -> "  ·  " + p.texte()).orElse("");
                        c.paragraphe(avant, 0, 0, false, run(b.intitule().texte(), true, g.corps()) + run(suite, false, g.corps()));
                        for (Cv.Puce p : b.puces()) {
                            c.paragraphe(0, 6 + 8, 8, false, run("•", false, g.corps()) + "<w:r><w:tab/></w:r>"
                                    + run(p.assertion().texte(), false, g.corps()));
                        }
                    }
                    case Cv.Ligne l -> c.paragraphe(avant, 0, 0, false, run(l.assertion().texte(), false, g.corps()));
                }
            }
        }
        return archive(c.document(), "CV — " + cv.entete().nom(), cv.entete().nom());
    }

    static byte[] lettre(Lettre lettre, Gabarit g) {
        Corps c = new Corps(g);
        entete(c, lettre.expediteur(), g);
        c.paragraphe(3 * g.espace(), 0, 0, false, run(lettre.destinataire(), false, g.corps()));
        c.paragraphe(2 * g.espace(), 0, 0, false, run(lettre.lieuEtDate(), false, g.corps()));
        c.paragraphe(2 * g.espace(), 0, 0, false, run("Objet : " + lettre.objet(), true, g.corps()));
        c.paragraphe(2 * g.espace(), 0, 0, false, run(Lettre.APPEL, false, g.corps()));
        for (Lettre.Paragraphe p : lettre.paragraphes()) {
            String texte = String.join(" ", p.phrases().stream().map(Assertion::texte).toList());
            c.paragraphe(1.5f * g.espace(), 0, 0, false, run(texte, false, g.corps()));
        }
        c.paragraphe(1.5f * g.espace(), 0, 0, false, run(Lettre.FORMULE, false, g.corps()));
        c.paragraphe(3 * g.espace(), 0, 0, false, run(lettre.expediteur().nom(), false, g.corps()));
        return archive(c.document(), "Lettre — " + lettre.expediteur().nom(), lettre.expediteur().nom());
    }

    private static void entete(Corps c, Identite identite, Gabarit g) {
        c.paragraphe(0, 0, 0, false, run(identite.nom(), true, g.corps() + 8));
        if (!identite.titre().isBlank()) {
            c.paragraphe(0, 0, 0, false, run(identite.titre(), false, g.corps() + 1.5f));
        }
        String contact = identite.lieu().isBlank() ? identite.contact() : identite.contact() + "  ·  " + identite.lieu();
        if (!contact.isBlank()) {
            c.paragraphe(0, 0, 0, false, run(contact, false, g.corps() - 0.5f));
        }
    }

    // --- le corps du document ---

    /** Les paragraphes, accumulés dans l'ordre, puis la page : A4, marges du gabarit. */
    private static final class Corps {

        private final Gabarit g;
        private final StringBuilder xml = new StringBuilder();

        Corps(Gabarit g) {
            this.g = g;
        }

        /**
         * @param avant espace avant le paragraphe, en points
         * @param retrait retrait à gauche, en points ; {@code suspendu} en ressort la première ligne, pour la puce
         * @param filet un trait sous le paragraphe : celui des titres de section
         */
        void paragraphe(float avant, float retrait, float suspendu, boolean filet, String runs) {
            xml.append("<w:p><w:pPr><w:spacing w:before=\"").append(Math.round(avant * TWIPS))
                    .append("\" w:after=\"0\" w:line=\"").append(Math.round(240 * g.interligne())).append("\" w:lineRule=\"auto\"/>");
            if (retrait > 0) {
                xml.append("<w:ind w:left=\"").append(Math.round(retrait * TWIPS)).append("\" w:hanging=\"")
                        .append(Math.round(suspendu * TWIPS)).append("\"/>");
            }
            if (filet) {
                xml.append("<w:pBdr><w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"1\" w:color=\"000000\"/></w:pBdr>");
            }
            xml.append("</w:pPr>").append(runs).append("</w:p>");
        }

        String document() {
            int marge = Math.round(g.marge() * TWIPS);
            return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                    + xml
                    + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"" + marge + "\" w:right=\"" + marge
                    + "\" w:bottom=\"" + marge + "\" w:left=\"" + marge + "\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/>"
                    + "</w:sectPr></w:body></w:document>";
        }
    }

    /** Un passage de texte, en Arial, à la taille dite — Word la compte en demi-points —, en français pour le correcteur. */
    private static String run(String texte, boolean gras, float taille) {
        if (texte.isEmpty()) {
            return "";
        }
        int demiPoints = Math.round(2 * taille);
        return "<w:r><w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:cs=\"Arial\"/>" + (gras ? "<w:b/>" : "")
                + "<w:sz w:val=\"" + demiPoints + "\"/><w:szCs w:val=\"" + demiPoints + "\"/><w:lang w:val=\"fr-FR\"/></w:rPr>"
                + "<w:t xml:space=\"preserve\">" + echapper(texte) + "</w:t></w:r>";
    }

    /** Le texte tel que XML le veut : caractères réservés échappés, caractères de contrôle retirés. */
    static String echapper(String texte) {
        StringBuilder sortie = new StringBuilder(texte.length());
        texte.codePoints().forEach(c -> {
            switch (c) {
                case '&' -> sortie.append("&amp;");
                case '<' -> sortie.append("&lt;");
                case '>' -> sortie.append("&gt;");
                case '"' -> sortie.append("&quot;");
                default -> {
                    if (c == '\t' || c >= 0x20 && c != 0xFFFE && c != 0xFFFF) {
                        sortie.appendCodePoint(c);
                    } else if (c == '\n' || c == '\r') {
                        sortie.append(' ');
                    }
                }
            }
        });
        return sortie.toString();
    }

    // --- l'archive ---

    private static byte[] archive(String document, String titre, String auteur) {
        ByteArrayOutputStream octets = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(octets, StandardCharsets.UTF_8)) {
            entree(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>\
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
                    <Default Extension="xml" ContentType="application/xml"/>\
                    <Override PartName="/word/document.xml" \
                    ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>\
                    <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>\
                    </Types>""");
            entree(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>\
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                    <Relationship Id="rId1" \
                    Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" \
                    Target="word/document.xml"/>\
                    <Relationship Id="rId2" \
                    Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" \
                    Target="docProps/core.xml"/>\
                    </Relationships>""");
            entree(zip, "docProps/core.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" "
                    + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>" + echapper(titre) + "</dc:title><dc:creator>"
                    + echapper(auteur) + "</dc:creator></cp:coreProperties>");
            entree(zip, "word/document.xml", document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return octets.toByteArray();
    }

    private static void entree(ZipOutputStream zip, String nom, String contenu) throws IOException {
        ZipEntry e = new ZipEntry(nom);
        e.setTimeLocal(DATE_FIXE);
        zip.putNextEntry(e);
        zip.write(contenu.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
