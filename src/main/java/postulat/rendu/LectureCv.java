package postulat.rendu;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Le texte d'un CV existant, pour en tirer un profil (UC-02, étape 2) : un PDF par PDFBox, un DOCX par son
 * XML, un fichier texte tel quel.
 *
 * <p>Le fichier vient d'ailleurs : il est lu avec méfiance. Sa taille est bornée, et le XML d'un DOCX
 * est lu sans DTD ni entité externe : un document piégé ne fait rien lire d'autre que lui-même.
 */
public final class LectureCv {

    /** En deçà, ce n'est pas un CV mais une image, ou rien. */
    static final int TEXTE_MINIMAL = 50;

    private static final long TAILLE_MAXIMALE = 20L * 1024 * 1024;
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private LectureCv() {}

    /**
     * @throws ErreurRendu si le format n'est pas pris en charge, si le fichier est illisible, ou s'il n'en
     *     sort aucun texte exploitable — un scan sans couche texte est désigné comme tel (UC-02 A1, E1)
     */
    public static String texte(Path fichier) {
        String nom = fichier.getFileName().toString().toLowerCase(Locale.ROOT);
        byte[] octets;
        try {
            if (Files.size(fichier) > TAILLE_MAXIMALE) {
                throw new ErreurRendu("Le fichier dépasse 20 Mo : ce n'est pas un CV d'une ou deux pages.");
            }
            octets = Files.readAllBytes(fichier);
        } catch (IOException e) {
            throw new ErreurRendu("Fichier illisible : " + fichier + " — " + e.getMessage(), e);
        }
        if (nom.endsWith(".pdf")) {
            String texte = pdf(octets);
            if (significatif(texte) < TEXTE_MINIMAL) {
                throw new ErreurRendu("Ce PDF ne contient presque aucun texte (" + significatif(texte) + " caractères) : c'est sans doute "
                        + "un scan, une image du CV (UC-02 A1). Fournissez une version textuelle : le PDF exporté par votre traitement "
                        + "de texte, le DOCX, ou le texte copié dans un fichier .txt.");
            }
            return texte;
        }
        String texte;
        if (nom.endsWith(".docx")) {
            texte = docx(octets);
        } else if (nom.endsWith(".txt")) {
            String brut = new String(octets, StandardCharsets.UTF_8);
            texte = brut.startsWith("﻿") ? brut.substring(1) : brut;
        } else {
            throw new ErreurRendu("Format non pris en charge : " + fichier.getFileName() + ". Un CV se donne en PDF, en DOCX, ou en texte "
                    + "dans un fichier .txt.");
        }
        if (significatif(texte) < TEXTE_MINIMAL) {
            throw new ErreurRendu("Aucun texte exploitable dans " + fichier.getFileName() + " (" + significatif(texte) + " caractères) : "
                    + "aucun profil n'a été écrit (UC-02 E1).");
        }
        return texte;
    }

    private static String pdf(byte[] octets) {
        try (PDDocument document = Loader.loadPDF(octets)) {
            return new PDFTextStripper().getText(document);
        } catch (IOException e) {
            throw new ErreurRendu("Ce PDF ne se lit pas (protégé, ou abîmé) : " + e.getMessage(), e);
        }
    }

    /** Le texte d'un DOCX : les en-têtes d'abord — on y met souvent nom et coordonnées —, puis le corps, puis les pieds de page. */
    static String docx(byte[] octets) {
        List<String> entetes = new ArrayList<>();
        String corps = null;
        List<String> pieds = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(octets))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                String nom = e.getName();
                if (nom.equals("word/document.xml")) {
                    corps = paragraphes(lireBorne(zip));
                } else if (nom.matches("word/header\\d*\\.xml")) {
                    entetes.add(paragraphes(lireBorne(zip)));
                } else if (nom.matches("word/footer\\d*\\.xml")) {
                    pieds.add(paragraphes(lireBorne(zip)));
                }
            }
        } catch (IOException e) {
            throw new ErreurRendu("Ce DOCX ne se lit pas : " + e.getMessage(), e);
        }
        if (corps == null) {
            throw new ErreurRendu("Ce fichier n'est pas un DOCX : il n'a pas de document Word à l'intérieur.");
        }
        List<String> tout = new ArrayList<>(entetes);
        tout.add(corps);
        tout.addAll(pieds);
        return String.join("\n", tout);
    }

    /** Une entrée de l'archive, sans jamais lire plus que la taille permise : une archive peut mentir sur sa taille. */
    private static byte[] lireBorne(InputStream entree) throws IOException {
        byte[] lu = entree.readNBytes((int) TAILLE_MAXIMALE + 1);
        if (lu.length > TAILLE_MAXIMALE) {
            throw new ErreurRendu("Ce DOCX contient une partie démesurée : il n'a pas été lu.");
        }
        return lu;
    }

    /** Un paragraphe Word par ligne ; les tabulations et sauts de ligne internes sont gardés. */
    private static String paragraphes(byte[] xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            org.w3c.dom.Document d = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            NodeList p = d.getElementsByTagNameNS(W, "p");
            List<String> lignes = new ArrayList<>();
            for (int i = 0; i < p.getLength(); i++) {
                StringBuilder ligne = new StringBuilder();
                texte(p.item(i), ligne);
                lignes.add(ligne.toString());
            }
            return String.join("\n", lignes);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new ErreurRendu("Le XML de ce DOCX est refusé ou illisible : " + e.getMessage(), e);
        }
    }

    private static void texte(Node n, StringBuilder ligne) {
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element e && W.equals(e.getNamespaceURI())) {
                switch (e.getLocalName()) {
                    case "t" -> ligne.append(e.getTextContent());
                    case "tab" -> ligne.append('\t');
                    case "br", "cr" -> ligne.append('\n');
                    case "p" -> { } // un paragraphe imbriqué, dans une zone de texte, a sa propre ligne
                    default -> texte(e, ligne);
                }
            }
        }
    }

    private static int significatif(String texte) {
        return texte.replaceAll("\\s", "").length();
    }
}
