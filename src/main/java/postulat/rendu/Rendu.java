package postulat.rendu;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import postulat.production.Assertion;
import postulat.production.Cv;
import postulat.production.Identite;
import postulat.production.Lettre;

/**
 * Rend un document produit sur une page A4 (RG-05), et relit le texte du PDF obtenu : c'est ce texte,
 * non celui qu'on a voulu y mettre, que la note ATS examine (CA-5.1).
 *
 * <p>Pour un CV qui déborde, l'ordre est fixé par RG-26 : compacter la mise en page d'abord, puis, au
 * plus serré, retirer la puce la moins pertinente, et recommencer — du plus aéré — jusqu'à ce que le CV
 * tienne. Le modèle ordonne, le code tranche : on ne demande jamais au modèle de raccourcir, ce qui
 * reviendrait à l'inviter à fusionner deux faits en une phrase.
 */
public final class Rendu {

    /**
     * @param gabarit le niveau de compaction retenu, du plus aéré au plus serré
     * @param retirees les puces retirées pour tenir sur une page, de la première retirée à la dernière :
     *     le rapport les liste toutes (CA-3.13)
     * @param texte le texte relu dans le PDF, tel qu'un filtre ATS le lirait
     */
    public record Resultat(byte[] pdf, String gabarit, List<Cv.Puce> retirees, List<String> avertissements, String texte) {

        public Resultat {
            Objects.requireNonNull(pdf);
            retirees = List.copyOf(retirees);
            avertissements = List.copyOf(avertissements);
        }
    }

    private Rendu() {}

    public static Resultat cv(Cv cv) {
        List<Cv.Puce> retirees = new ArrayList<>();
        Cv courant = cv;
        while (true) {
            Composition plusSerree = null;
            for (Gabarit g : Gabarit.DU_PLUS_AERE_AU_PLUS_SERRE) {
                Composition c = composer(courant, g);
                if (c.tient()) {
                    return terminer(c, titre("CV", cv.entete()), cv.entete().nom(), retirees, courant.assertions());
                }
                plusSerree = c;
            }
            Cv.Puce derniere = courant.moinsPertinente().orElse(null);
            if (derniere == null) {
                throw new ErreurRendu("Même sans aucune puce, le CV dépasse une page de " + debord(plusSerree)
                        + " : trop d'expériences ou de formations pour une seule page.");
            }
            retirees.add(derniere);
            courant = courant.sans(derniere);
        }
    }

    /**
     * Rend le CV tel quel, sans rien en retirer : après une correction à la main, c'est au candidat de
     * choisir ce qui part. S'il ne tient pas sur une page, même au plus serré, c'est un refus (UC-08 E1).
     */
    public static Resultat cvTelQuel(Cv cv) {
        Composition plusSerree = null;
        for (Gabarit g : Gabarit.DU_PLUS_AERE_AU_PLUS_SERRE) {
            Composition c = composer(cv, g);
            if (c.tient()) {
                return terminer(c, titre("CV", cv.entete()), cv.entete().nom(), List.of(), cv.assertions());
            }
            plusSerree = c;
        }
        throw new ErreurRendu("Le CV dépasserait une page de " + debord(plusSerree) + ", même au plus serré.");
    }

    /** Une lettre ne se coupe pas phrase à phrase : si elle déborde au plus serré, elle est à raccourcir. */
    public static Resultat lettre(Lettre lettre) {
        Composition plusSerree = null;
        for (Gabarit g : Gabarit.DU_PLUS_AERE_AU_PLUS_SERRE) {
            Composition c = composer(lettre, g);
            if (c.tient()) {
                return terminer(c, titre("Lettre", lettre.expediteur()), lettre.expediteur().nom(), List.of(), lettre.assertions());
            }
            plusSerree = c;
        }
        throw new ErreurRendu("La lettre dépasse une page de " + debord(plusSerree) + ", même au plus serré : elle est "
                + "à raccourcir (CA-4.4), pas à tronquer.");
    }

    /** Le texte d'un PDF, tel qu'un filtre ATS l'extrait. */
    public static String texte(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        } catch (IOException e) {
            throw new ErreurRendu("Texte illisible dans ce PDF : " + e.getMessage(), e);
        }
    }

    /** Nombre de pages d'un PDF : la vérification de RG-05 sur le fichier lui-même. */
    public static int pages(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        } catch (IOException e) {
            throw new ErreurRendu("PDF illisible : " + e.getMessage(), e);
        }
    }

    // --- composition ---

    static Composition composer(Cv cv, Gabarit g) {
        Composition c = new Composition(g);
        entete(c, cv.entete(), g);
        for (Cv.Section s : cv.sections()) {
            if (s.contenus().isEmpty()) {
                continue; // vidée par une correction : un titre seul ne dit rien
            }
            c.espace(g.espace());
            c.paragraphe(s.titre().toUpperCase(Locale.FRENCH), c.grasse, g.corps() + 1, 0, null);
            c.filet();
            boolean premier = true;
            for (Cv.Contenu contenu : s.contenus()) {
                if (!premier) {
                    c.espace(g.espace() / 2);
                }
                premier = false;
                switch (contenu) {
                    case Cv.Bloc b -> {
                        c.intitule(b.intitule().texte(), b.precision().map(Assertion::texte).orElse(null), g.corps());
                        for (Cv.Puce p : b.puces()) {
                            c.paragraphe(p.assertion().texte(), c.normale, g.corps(), 6, "•");
                        }
                    }
                    case Cv.Ligne l -> c.paragraphe(l.assertion().texte(), c.normale, g.corps(), 0, null);
                }
            }
        }
        return c;
    }

    static Composition composer(Lettre lettre, Gabarit g) {
        Composition c = new Composition(g);
        entete(c, lettre.expediteur(), g);
        c.espace(3 * g.espace());
        c.paragraphe(lettre.destinataire(), c.normale, g.corps(), 0, null);
        c.espace(2 * g.espace());
        c.paragraphe(lettre.lieuEtDate(), c.normale, g.corps(), 0, null);
        c.espace(2 * g.espace());
        c.paragraphe("Objet : " + lettre.objet(), c.grasse, g.corps(), 0, null);
        c.espace(2 * g.espace());
        c.paragraphe(Lettre.APPEL, c.normale, g.corps(), 0, null);
        for (Lettre.Paragraphe p : lettre.paragraphes()) {
            c.espace(1.5f * g.espace());
            c.paragraphe(String.join(" ", p.phrases().stream().map(Assertion::texte).toList()), c.normale, g.corps(), 0, null);
        }
        c.espace(1.5f * g.espace());
        c.paragraphe(Lettre.FORMULE, c.normale, g.corps(), 0, null);
        c.espace(3 * g.espace());
        c.paragraphe(lettre.expediteur().nom(), c.normale, g.corps(), 0, null);
        return c;
    }

    private static void entete(Composition c, Identite identite, Gabarit g) {
        c.paragraphe(identite.nom(), c.grasse, g.corps() + 8, 0, null);
        if (!identite.titre().isBlank()) {
            c.paragraphe(identite.titre(), c.normale, g.corps() + 1.5f, 0, null);
        }
        String contact = identite.lieu().isBlank() ? identite.contact() : identite.contact() + "  ·  " + identite.lieu();
        if (!contact.isBlank()) {
            c.paragraphe(contact, c.normale, g.corps() - 0.5f, 0, null);
        }
    }

    // --- fin ---

    /**
     * Rend le PDF, puis le relit : il doit tenir sur une page, et chaque phrase doit s'y retrouver telle
     * quelle. Une phrase introuvable dans le texte extrait serait invisible pour un filtre ATS : c'est
     * signalé.
     */
    private static Resultat terminer(Composition c, String titre, String auteur, List<Cv.Puce> retirees, List<Assertion> assertions) {
        byte[] pdf = c.pdf(titre, auteur);
        if (pages(pdf) != 1) {
            throw new ErreurRendu("Le PDF produit compte " + pages(pdf) + " pages au lieu d'une : défaut du rendu.");
        }
        String texte = texte(pdf);
        String relu = aplatir(texte);
        List<String> avertissements = new ArrayList<>(c.avertissements());
        for (Assertion a : assertions) {
            if (!relu.contains(aplatir(c.nettoyer(a.texte(), c.normale)))) {
                avertissements.add("phrase introuvable dans le texte relu, donc invisible pour un filtre ATS : « " + a.texte() + " »");
            }
        }
        return new Resultat(pdf, c.gabarit().nom(), retirees, avertissements, texte);
    }

    private static String aplatir(String texte) {
        return texte.replaceAll("\\s+", " ").strip();
    }

    private static String debord(Composition c) {
        return Math.round((c.hauteur() - c.hauteurUtile()) / 72f * 25.4f) + " mm";
    }

    private static String titre(String nature, Identite identite) {
        return nature + " — " + identite.nom();
    }
}
