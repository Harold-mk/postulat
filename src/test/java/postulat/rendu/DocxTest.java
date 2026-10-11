package postulat.rendu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import postulat.production.Assertion;
import postulat.production.Cv;
import postulat.production.Lettre;
import postulat.production.Origine;

/** Le DOCX écrit à la main : une archive que Word ouvre, avec tout le texte, et la page du PDF. */
class DocxTest {

    static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    /** Les entrées de l'archive, par nom. */
    static Map<String, String> entrees(byte[] docx) throws IOException {
        Map<String, String> entrees = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx), StandardCharsets.UTF_8)) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                entrees.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entrees;
    }

    /** Le document, lu par un vrai analyseur XML : un fichier mal formé ne s'ouvrirait pas dans Word. */
    static Document document(byte[] docx) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(entrees(docx).get("word/document.xml").getBytes(StandardCharsets.UTF_8)));
    }

    /** Le texte des paragraphes, un par ligne, comme le lirait un portail de recrutement. */
    static String texte(Document d) {
        StringBuilder texte = new StringBuilder();
        NodeList paragraphes = d.getElementsByTagNameNS(W, "p");
        for (int i = 0; i < paragraphes.getLength(); i++) {
            NodeList t = ((Element) paragraphes.item(i)).getElementsByTagNameNS(W, "t");
            for (int j = 0; j < t.getLength(); j++) {
                texte.append(t.item(j).getTextContent());
            }
            texte.append('\n');
        }
        return texte.toString();
    }

    static Cv cv() {
        return new Cv(RenduTest.CAMILLE, List.of(
                new Cv.Section("Expériences", List.of(new Cv.Bloc(RenduTest.faits("Stagiaire data analyst — Acme"),
                        Optional.of(RenduTest.faits("2025 · Lyon")),
                        List.of(new Cv.Puce(RenduTest.faits("Rapports R&D <mensuels> \"fiabilisés\""), 1),
                                new Cv.Puce(new Assertion("Animé un atelier interne", new Origine.Auteur()), 2))))),
                new Cv.Section("Vide", List.of()),
                new Cv.Section("Compétences", List.of(new Cv.Ligne(RenduTest.faits("SQL · Python"))))));
    }

    @Test
    void le_cv_est_une_archive_complete_au_xml_bien_forme_qui_dit_tout() throws Exception {
        byte[] docx = Rendu.cvDocx(cv());

        assertThat(entrees(docx)).containsOnlyKeys("[Content_Types].xml", "_rels/.rels", "docProps/core.xml", "word/document.xml");
        assertThat(entrees(docx).get("docProps/core.xml")).contains("<dc:title>CV — Camille Martin</dc:title>")
                .contains("<dc:creator>Camille Martin</dc:creator>");
        String texte = texte(document(docx));
        assertThat(texte).contains("Camille Martin\n", "Étudiante en master data\n", "camille@exemple.test  ·  Lyon\n",
                "EXPÉRIENCES\n", "Stagiaire data analyst — Acme  ·  2025 · Lyon\n", "•Rapports R&D <mensuels> \"fiabilisés\"\n",
                "•Animé un atelier interne\n", "COMPÉTENCES\n", "SQL · Python\n");
        assertThat(texte).doesNotContain("VIDE");
    }

    @Test
    void la_page_est_l_a4_du_pdf_avec_les_marges_et_les_tailles_de_son_gabarit() throws Exception {
        Document d = document(Rendu.cvDocx(cv()));

        Element taille = (Element) d.getElementsByTagNameNS(W, "pgSz").item(0);
        Element marges = (Element) d.getElementsByTagNameNS(W, "pgMar").item(0);
        assertThat(taille.getAttributeNS(W, "w")).isEqualTo("11906");
        assertThat(taille.getAttributeNS(W, "h")).isEqualTo("16838");
        Gabarit aere = Gabarit.DU_PLUS_AERE_AU_PLUS_SERRE.get(0);
        assertThat(marges.getAttributeNS(W, "left")).isEqualTo(String.valueOf(Math.round(aere.marge() * 20)));
        assertThat(((Element) d.getElementsByTagNameNS(W, "sz").item(0)).getAttributeNS(W, "val"))
                .isEqualTo(String.valueOf(Math.round(2 * (aere.corps() + 8))));
        assertThat(((Element) d.getElementsByTagNameNS(W, "rFonts").item(0)).getAttributeNS(W, "ascii")).isEqualTo("Arial");
    }

    @Test
    void la_lettre_suit_l_ordre_du_pdf_appel_et_formule_compris() throws Exception {
        Lettre lettre = new Lettre(RenduTest.CAMILLE, "Globex — Service du recrutement", "Lyon, le 10 octobre 2026", "Candidature",
                List.of(new Lettre.Paragraphe(List.of(new Assertion("Globex recrute.", new Origine.Offre()))),
                        new Lettre.Paragraphe(List.of(RenduTest.faits("J'ai conçu un tableau de bord."),
                                new Assertion("Je parle espagnol.", new Origine.Auteur())))));

        String texte = texte(document(Rendu.lettreDocx(lettre)));

        assertThat(texte).containsSubsequence("Camille Martin\n", "Globex — Service du recrutement\n", "Lyon, le 10 octobre 2026\n",
                "Objet : Candidature\n", Lettre.APPEL + "\n", "Globex recrute.\n", "J'ai conçu un tableau de bord. Je parle espagnol.\n",
                Lettre.FORMULE + "\n", "Camille Martin\n");
    }

    @Test
    void la_meme_entree_donne_les_memes_octets() {
        assertThat(Rendu.cvDocx(cv())).isEqualTo(Rendu.cvDocx(cv()));
    }

    @Test
    void ce_qui_deborde_n_est_pas_ecrit_et_les_caracteres_interdits_en_xml_disparaissent() {
        assertThatThrownBy(() -> Rendu.cvDocx(RenduTest.cvDe(200))).isInstanceOf(ErreurRendu.class).hasMessageContaining("DOCX");
        assertThat(Docx.echapper("a\u0001b\tc\nd & <e>")).isEqualTo("ab\tc d &amp; &lt;e&gt;");
    }
}
