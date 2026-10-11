import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Dessine l'icône de postulat : un « p » blanc sur un carré arrondi, du bleu du thème Primer. Écrit
 * l'icône Windows des lanceurs ({@code src/paquet/postulat.ico}) et les images de la fenêtre
 * ({@code src/main/resources/postulat/bureau/icone-*.png}). Les fichiers produits sont versionnés ; ce
 * programme dit seulement d'où ils viennent, et les refait si l'on change le dessin.
 *
 * <p>Depuis la racine du projet : {@code java src/paquet/Icone.java}
 */
public final class Icone {

    private static final Color BLEU = new Color(0x09, 0x69, 0xDA);
    private static final int[] TAILLES_ICO = {16, 24, 32, 48, 64, 128, 256};
    private static final int[] TAILLES_FENETRE = {16, 32, 48, 256};

    public static void main(String[] args) throws IOException {
        List<byte[]> images = new ArrayList<>();
        for (int t : TAILLES_ICO) {
            images.add(png(dessiner(t)));
        }
        Files.write(Path.of("src", "paquet", "postulat.ico"), ico(TAILLES_ICO, images));
        Path ressources = Path.of("src", "main", "resources", "postulat", "bureau");
        Files.createDirectories(ressources);
        for (int t : TAILLES_FENETRE) {
            Files.write(ressources.resolve("icone-" + t + ".png"), png(dessiner(t)));
        }
        System.out.println("Icône écrite : src/paquet/postulat.ico et " + TAILLES_FENETRE.length + " images dans " + ressources);
    }

    static BufferedImage dessiner(int t) {
        BufferedImage image = new BufferedImage(t, t, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double arrondi = t * 0.44;
        g.setColor(BLEU);
        g.fill(new RoundRectangle2D.Double(0, 0, t, t, arrondi, arrondi));
        // Le « p » occupe les trois quarts de la hauteur, centré sur sa forme réelle, jambage compris.
        Font police = new Font("Segoe UI", Font.BOLD, 100);
        GlyphVector forme = police.createGlyphVector(g.getFontRenderContext(), "p");
        Rectangle2D contour = forme.getVisualBounds();
        double echelle = t * 0.74 / contour.getHeight();
        g.translate(t / 2.0, t / 2.0);
        g.scale(echelle, echelle);
        g.translate(-contour.getCenterX(), -contour.getCenterY());
        g.setColor(Color.WHITE);
        g.fill(forme.getOutline());
        g.dispose();
        return image;
    }

    static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        ImageIO.write(image, "png", sortie);
        return sortie.toByteArray();
    }

    /** Un fichier ICO dont chaque image est un PNG, ce que Windows lit depuis Vista. */
    static byte[] ico(int[] tailles, List<byte[]> images) {
        int entete = 6 + 16 * images.size();
        int total = entete + images.stream().mapToInt(i -> i.length).sum();
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0).putShort((short) 1).putShort((short) images.size());
        int position = entete;
        for (int i = 0; i < images.size(); i++) {
            int t = tailles[i];
            b.put((byte) (t >= 256 ? 0 : t)).put((byte) (t >= 256 ? 0 : t)).put((byte) 0).put((byte) 0);
            b.putShort((short) 1).putShort((short) 32).putInt(images.get(i).length).putInt(position);
            position += images.get(i).length;
        }
        images.forEach(b::put);
        return b.array();
    }
}
