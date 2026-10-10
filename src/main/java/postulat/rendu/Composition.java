package postulat.rendu;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * La mise en page d'un document sur une page A4, calculée avant d'être dessinée : chaque ligne est
 * coupée et mesurée avec les dimensions exactes de la police, si bien que la hauteur totale — donc
 * « tient sur une page » — se sait sans rien rendre (RG-05).
 *
 * <p>Helvetica, l'une des polices standard du format PDF : rien à embarquer, des accents français
 * complets, et un texte que les filtres ATS relisent sans perte. Un caractère qu'elle ne sait pas
 * représenter est remplacé, et l'avertissement le dit.
 *
 * <p>Aucune césure : un mot coupé en deux ne se retrouve plus par un filtre qui cherche ce mot.
 */
final class Composition {

    static final PDRectangle PAGE = PDRectangle.A4;

    /** Un morceau de texte dans une seule police, décalé de {@code dx} depuis le début de sa ligne. */
    private record Fragment(PDFont police, float taille, float dx, String texte) {}

    private sealed interface Element permits Texte, Espace, Filet {}

    private record Texte(float hauteur, float taille, float retrait, List<Fragment> fragments) implements Element {}

    private record Espace(float hauteur) implements Element {}

    private record Filet(float hauteur) implements Element {}

    final PDFont normale = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    final PDFont grasse = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    private final Gabarit gabarit;
    private final List<Element> elements = new ArrayList<>();
    private final Set<String> avertissements = new LinkedHashSet<>();
    private float hauteur;

    Composition(Gabarit gabarit) {
        this.gabarit = gabarit;
    }

    Gabarit gabarit() {
        return gabarit;
    }

    float largeurUtile() {
        return PAGE.getWidth() - 2 * gabarit.marge();
    }

    float hauteurUtile() {
        return PAGE.getHeight() - 2 * gabarit.marge();
    }

    float hauteur() {
        return hauteur;
    }

    boolean tient() {
        return hauteur <= hauteurUtile();
    }

    List<String> avertissements() {
        return List.copyOf(avertissements);
    }

    // --- construction ---

    void espace(float h) {
        ajouter(new Espace(h));
    }

    void filet() {
        ajouter(new Filet(3f));
    }

    /**
     * Un paragraphe coupé à la largeur utile, sur les espaces. Avec une puce, la puce se place au
     * retrait et le texte, y compris ses lignes suivantes, un cran plus loin.
     */
    void paragraphe(String texte, PDFont police, float taille, float retrait, String puce) {
        String propre = nettoyer(texte, police);
        float decalage = puce == null ? 0 : largeur(puce + " ", police, taille) + 2;
        float largeurTexte = largeurUtile() - retrait - decalage;
        List<String> lignes = couper(propre, police, taille, largeurTexte);
        for (int i = 0; i < lignes.size(); i++) {
            List<Fragment> fragments = new ArrayList<>();
            if (i == 0 && puce != null) {
                fragments.add(new Fragment(police, taille, 0, puce));
            }
            fragments.add(new Fragment(police, taille, decalage, lignes.get(i)));
            ajouter(new Texte(taille * gabarit.interligne(), taille, retrait, fragments));
        }
    }

    /**
     * Un intitulé en gras suivi de sa précision sur la même ligne si la place le permet ; sinon la
     * précision passe à la ligne, en plus petit.
     */
    void intitule(String intitule, String precision, float taille) {
        String gras = nettoyer(intitule, grasse);
        if (precision == null || precision.isBlank()) {
            paragraphe(gras, grasse, taille, 0, null);
            return;
        }
        String suite = "  ·  " + nettoyer(precision, normale);
        float l = largeur(gras, grasse, taille);
        if (l + largeur(suite, normale, taille) <= largeurUtile()) {
            ajouter(new Texte(taille * gabarit.interligne(), taille, 0,
                    List.of(new Fragment(grasse, taille, 0, gras), new Fragment(normale, taille, l, suite))));
        } else {
            paragraphe(gras, grasse, taille, 0, null);
            paragraphe(precision, normale, taille - 0.5f, 0, null);
        }
    }

    // --- rendu ---

    /** Dessine la page et rend le PDF. À n'appeler que si {@link #tient()}. */
    byte[] pdf(String titre, String auteur) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PAGE);
            document.addPage(page);
            document.getDocumentInformation().setTitle(titre);
            document.getDocumentInformation().setAuthor(auteur);
            document.getDocumentInformation().setCreator("postulat");
            try (PDPageContentStream flux = new PDPageContentStream(document, page)) {
                float y = PAGE.getHeight() - gabarit.marge();
                for (Element e : elements) {
                    switch (e) {
                        case Espace espace -> y -= espace.hauteur();
                        case Filet filet -> {
                            flux.setLineWidth(0.6f);
                            flux.moveTo(gabarit.marge(), y - 1.5f);
                            flux.lineTo(PAGE.getWidth() - gabarit.marge(), y - 1.5f);
                            flux.stroke();
                            y -= filet.hauteur();
                        }
                        case Texte texte -> {
                            float ligneDeBase = y - texte.taille();
                            for (Fragment f : texte.fragments()) {
                                flux.beginText();
                                flux.setFont(f.police(), f.taille());
                                flux.newLineAtOffset(gabarit.marge() + texte.retrait() + f.dx(), ligneDeBase);
                                flux.showText(f.texte());
                                flux.endText();
                            }
                            y -= texte.hauteur();
                        }
                    }
                }
            }
            ByteArrayOutputStream sortie = new ByteArrayOutputStream();
            document.save(sortie);
            return sortie.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- interne ---

    private void ajouter(Element e) {
        elements.add(e);
        hauteur += switch (e) {
            case Texte t -> t.hauteur();
            case Espace s -> s.hauteur();
            case Filet f -> f.hauteur();
        };
    }

    /** Coupe sur les espaces ; un mot plus long que la ligne entière se coupe, faute de mieux, à la lettre. */
    private List<String> couper(String texte, PDFont police, float taille, float largeurMax) {
        List<String> lignes = new ArrayList<>();
        StringBuilder courante = new StringBuilder();
        for (String mot : texte.strip().split(" +")) {
            String essai = courante.isEmpty() ? mot : courante + " " + mot;
            if (largeur(essai, police, taille) <= largeurMax) {
                courante.setLength(0);
                courante.append(essai);
                continue;
            }
            if (!courante.isEmpty()) {
                lignes.add(courante.toString());
                courante.setLength(0);
            }
            while (largeur(mot, police, taille) > largeurMax && mot.length() > 1) {
                int n = mot.length() - 1;
                while (n > 1 && largeur(mot.substring(0, n), police, taille) > largeurMax) {
                    n--;
                }
                lignes.add(mot.substring(0, n));
                mot = mot.substring(n);
            }
            courante.append(mot);
        }
        if (!courante.isEmpty() || lignes.isEmpty()) {
            lignes.add(courante.toString());
        }
        return lignes;
    }

    float largeur(String texte, PDFont police, float taille) {
        try {
            return police.getStringWidth(texte) / 1000f * taille;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Ce que la police ne sait pas écrire : les espaces fines deviennent des espaces, les tirets
     * insécables des tirets, une flèche « -> », et le reste un point d'interrogation, signalé.
     */
    String nettoyer(String texte, PDFont police) {
        StringBuilder propre = new StringBuilder();
        texte.codePoints().forEach(c -> {
            String remplacement = switch (c) {
                case '\n', '\r', '\t', ' ', ' ', ' ' -> " ";
                case '‐', '‑', '‒' -> "-";
                case '→' -> "->";
                case '←' -> "<-";
                default -> null;
            };
            if (remplacement != null) {
                propre.append(remplacement);
            } else if (representable(Character.toString(c), police)) {
                propre.appendCodePoint(c);
            } else {
                propre.append('?');
                avertissements.add("caractère « " + Character.toString(c) + " » (U+" + String.format("%04X", c)
                        + ") non représentable dans la police, remplacé par « ? »");
            }
        });
        return propre.toString();
    }

    private static boolean representable(String caractere, PDFont police) {
        try {
            police.encode(caractere);
            return true;
        } catch (IllegalArgumentException | IOException e) {
            return false;
        }
    }
}
