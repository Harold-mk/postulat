package postulat.rendu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** UC-02, étape 2 : le texte d'un CV existant, et rien d'autre, quel que soit ce que le fichier tente. */
class LectureCvTest {

    static final String W = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";

    @TempDir
    Path dossier;

    Path fichier(String nom, byte[] octets) throws IOException {
        return Files.write(dossier.resolve(nom), octets);
    }

    static byte[] docx(Map<String, String> entrees) throws IOException {
        ByteArrayOutputStream octets = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(octets)) {
            for (Map.Entry<String, String> e : entrees.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }
        return octets.toByteArray();
    }

    static String paragraphes(String... textes) {
        StringBuilder xml = new StringBuilder("<w:document " + W + "><w:body>");
        for (String t : textes) {
            xml.append("<w:p><w:r><w:t>").append(t).append("</w:t></w:r></w:p>");
        }
        return xml.append("</w:body></w:document>").toString();
    }

    @Test
    void un_pdf_se_lit_comme_le_lirait_un_filtre_ats() throws IOException {
        Path cv = fichier("cv.pdf", Rendu.cvTelQuel(RenduTest.cvDe(3)).pdf());

        assertThat(LectureCv.texte(cv)).contains("Camille Martin").contains("Réalisation numéro 2 menée de bout en bout");
    }

    @Test
    void un_docx_se_lit_en_tetes_d_abord_paragraphe_par_paragraphe() throws IOException {
        String corps = "<w:document " + W + "><w:body><w:p><w:r><w:t>Stagiaire data</w:t><w:tab/><w:t>2025</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t xml:space=\"preserve\">Conçu un tableau de bord </w:t></w:r><w:r><w:t>Power BI</w:t><w:br/>"
                + "<w:t>pour 12 commerciaux</w:t></w:r></w:p></w:body></w:document>";
        Path cv = fichier("cv.docx", docx(Map.of("word/document.xml", corps,
                "word/header1.xml", "<w:hdr " + W + ">" + paragraphes("Camille Martin · camille@exemple.test").substring(("<w:document "
                        + W + "><w:body>").length()).replace("</w:body></w:document>", "") + "</w:hdr>",
                "word/footer1.xml", "<w:ftr " + W + "><w:p><w:r><w:t>Page 1</w:t></w:r></w:p></w:ftr>")));

        assertThat(LectureCv.texte(cv)).isEqualTo("Camille Martin · camille@exemple.test\nStagiaire data\t2025\n"
                + "Conçu un tableau de bord Power BI\npour 12 commerciaux\nPage 1");
    }

    @Test
    void le_docx_ecrit_par_postulat_se_relit() throws IOException {
        Path cv = fichier("cv.docx", Rendu.cvDocx(RenduTest.cvDe(3)));

        assertThat(LectureCv.texte(cv)).contains("Camille Martin\nÉtudiante en master data").contains("•\tRéalisation numéro 3");
    }

    @Test
    void un_texte_se_lit_tel_quel_sans_sa_marque_d_ordre() throws IOException {
        String texte = "Camille Martin\nStagiaire data analyst chez Acme, 2025 : tableau de bord Power BI pour 12 commerciaux.";
        Path cv = fichier("cv.txt", ("﻿" + texte).getBytes(StandardCharsets.UTF_8));

        assertThat(LectureCv.texte(cv)).isEqualTo(texte);
    }

    /** UC-02 A1 : un scan n'a pas de texte ; on le dit, et on demande une version textuelle. */
    @Test
    void un_scan_sans_couche_texte_est_designe_comme_tel() throws IOException {
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        try (PDDocument d = new PDDocument()) {
            d.addPage(new PDPage());
            d.save(pdf);
        }

        assertThatThrownBy(() -> LectureCv.texte(fichier("scan.pdf", pdf.toByteArray()))).isInstanceOf(ErreurRendu.class)
                .hasMessageContaining("c'est sans doute un scan").hasMessageContaining("UC-02 A1").hasMessageContaining(".txt");
    }

    /** UC-02 E1 : sans texte exploitable, rien. */
    @Test
    void sans_texte_exploitable_rien_n_est_lu() throws IOException {
        assertThatThrownBy(() -> LectureCv.texte(fichier("vide.docx", docx(Map.of("word/document.xml", paragraphes("CV"))))))
                .hasMessageContaining("Aucun texte exploitable").hasMessageContaining("UC-02 E1");
        assertThatThrownBy(() -> LectureCv.texte(fichier("vide.txt", "  \n ".getBytes(StandardCharsets.UTF_8))))
                .hasMessageContaining("Aucun texte exploitable");
    }

    @Test
    void ce_qui_n_est_pas_un_cv_lisible_est_refuse() throws IOException {
        assertThatThrownBy(() -> LectureCv.texte(fichier("cv.odt", new byte[] {1, 2})))
                .hasMessageContaining("Format non pris en charge : cv.odt");
        assertThatThrownBy(() -> LectureCv.texte(fichier("abime.pdf", "pas un PDF".getBytes(StandardCharsets.UTF_8))))
                .hasMessageContaining("Ce PDF ne se lit pas");
        assertThatThrownBy(() -> LectureCv.texte(fichier("autre.docx", docx(Map.of("contenu.txt", "rien")))))
                .hasMessageContaining("n'est pas un DOCX");
        assertThatThrownBy(() -> LectureCv.texte(dossier.resolve("absent.pdf"))).hasMessageContaining("Fichier illisible");
    }

    /** Un DOCX piégé ne fait rien lire d'autre que lui-même : pas de DTD, pas d'entité externe. */
    @Test
    void un_docx_piege_par_une_entite_externe_est_refuse() throws IOException {
        Path ailleurs = Files.writeString(dossier.resolve("autre-fichier.txt"), "ne doit jamais sortir");
        String piege = "<?xml version=\"1.0\"?><!DOCTYPE w:document [<!ENTITY x SYSTEM \"" + ailleurs.toUri() + "\">]>"
                + "<w:document " + W + "><w:body><w:p><w:r><w:t>&x; et assez de texte pour passer le seuil des cinquante caractères"
                + "</w:t></w:r></w:p></w:body></w:document>";

        assertThatThrownBy(() -> LectureCv.texte(fichier("piege.docx", docx(Map.of("word/document.xml", piege)))))
                .hasMessageContaining("refusé ou illisible").hasMessageNotContaining("ne doit jamais sortir");
    }
}
