package postulat.cli;

import static postulat.libelles.Libelles.date;
import static postulat.libelles.Libelles.phrase;

import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import postulat.production.Assertion;
import postulat.production.AvisRecruteur;
import postulat.production.Couverture;
import postulat.production.Cv;
import postulat.production.Dossier;
import postulat.production.Evaluation;
import postulat.production.Exigence;
import postulat.production.Lettre;
import postulat.production.OffreAnalysee;
import postulat.production.Rapprochement;
import postulat.stockage.Offre;
import postulat.suivi.Echeance;

/**
 * Les blocs que la ligne de commande affiche : l'offre, le dossier, l'évaluation, les suggestions de
 * commande. Les mots eux-mêmes — dates, étapes, échéances — viennent de {@link postulat.libelles.Libelles}.
 */
final class Affichage {

    private Affichage() {}

    /**
     * L'offre telle qu'on la lit pour décider (UC-06) : sa fiche, chaque exigence couverte ou en lacune
     * avec sa justification, la note d'adéquation. Ce qui manque encore est dit, pas tu.
     */
    static void offre(PrintWriter out, Offre offre, ZoneId zone, long numero) {
        out.println("Offre (texte conservé : " + offre.texteBrut().length() + " caractères, reçu le "
                + date(LocalDate.ofInstant(offre.recueLe(), zone)) + ")");
        if (offre.analyse().isEmpty()) {
            out.println("  Non analysée. Pour l'analyser : postulat offre analyser " + numero);
            return;
        }
        OffreAnalysee a = offre.analyse().get();
        String fiche = Stream.of(
                        champ("Poste", a.poste()), champ("Entreprise", a.entreprise()), champ("Lieu", a.lieu()),
                        champ("Contrat", a.contrat()), champ("Début", a.debut()), champ("Durée", a.duree()),
                        champ("Niveau", a.niveau()))
                .filter(c -> !c.isEmpty())
                .collect(Collectors.joining(" · "));
        out.println("  " + fiche);
        if (!a.missions().isEmpty()) {
            out.println("  Missions");
            a.missions().forEach(m -> out.println("    - " + m));
        }
        if (a.exigences().isEmpty()) {
            out.println("  Aucune exigence relevée.");
            return;
        }
        out.println("  Exigences ([O] obligatoire, [S] souhaitée)");
        if (offre.rapprochement().isEmpty()) {
            a.exigences().forEach(e -> out.println("    " + marque(e) + " " + e.libelle()));
            out.println("  Rapprochement avec le profil non fait. Profil prêt ? postulat offre analyser " + numero);
            return;
        }
        Rapprochement r = offre.rapprochement().get();
        for (Couverture c : r.couvertures()) {
            out.println("    " + (c.couverte() ? "couverte " : "lacune   ") + marque(c.exigence()) + " " + c.exigence().libelle()
                    + (c.couverte() ? " — " + String.join(", ", c.faits()) : "")
                    + (c.justification().isEmpty() ? "" : " : " + c.justification()));
        }
        out.println("  Adéquation : " + r.note() + "/100. " + r.justification());
    }

    /**
     * Le dossier tel qu'on le relit avant de l'envoyer : chaque phrase numérotée — c'est par ce numéro qu'on
     * la corrige — et suivie de son origine (CA-3.7), puis l'évaluation et le rapport (CA-5.5). Ce qui a
     * été retiré ou écarté est dit, pas tu.
     *
     * @param avisPerime vrai si le dossier a été corrigé depuis l'avis du recruteur
     */
    static void dossier(PrintWriter out, Dossier d, boolean avisPerime) {
        Evaluation e = d.evaluation();
        out.println("Note globale : " + e.noteGlobale() + "/100, seuil " + e.regles().seuil() + (e.seuilAtteint() ? " atteint" : " non atteint")
                + " — ATS " + e.ats().map(a -> a.note() + "/100").orElse("sans objet") + " · recruteur " + e.avis().note() + "/100"
                + (avisPerime ? ", avis antérieur à vos corrections" : ""));
        out.println();
        int[] numero = {1};
        out.println("CV");
        for (Cv.Section s : d.cv().sections()) {
            if (s.contenus().isEmpty()) {
                continue;
            }
            out.println("        " + s.titre().toUpperCase(Locale.FRENCH));
            for (Cv.Contenu c : s.contenus()) {
                switch (c) {
                    case Cv.Bloc b -> {
                        out.println(numerotee(numero, "", b.intitule()));
                        b.precision().ifPresent(p -> out.println(numerotee(numero, "", p)));
                        b.puces().forEach(p -> out.println(numerotee(numero, "• ", p.assertion()) + " (pertinence " + p.rang() + ")"));
                    }
                    case Cv.Ligne l -> out.println(numerotee(numero, "", l.assertion()));
                }
            }
        }
        out.println();
        Lettre l = d.lettre();
        out.println("Lettre — " + l.destinataire() + " · " + l.lieuEtDate() + " · " + l.mots() + " mots");
        out.println("        Objet : " + l.objet());
        String[] parties = {"Vous", "Moi", "Nous"};
        for (int i = 0; i < l.paragraphes().size(); i++) {
            out.println("        " + (i < parties.length ? parties[i] : "Paragraphe " + (i + 1)));
            l.paragraphes().get(i).phrases().forEach(p -> out.println(numerotee(numero, "", p)));
        }
        out.println();
        evaluation(out, d, avisPerime);
        out.println();
        Dossier.Rapport r = d.rapport();
        out.println("Rapport : " + (r.passes() == 1 ? "une passe" : r.passes() + " passes") + ", " + d.assertions().size()
                + " phrases dont " + d.assertionsDeLAuteur() + " de votre main");
        if (!r.retirees().isEmpty()) {
            out.println("  Retirées pour tenir sur une page, les moins pertinentes d'abord (RG-26)");
            for (int i = 0; i < r.retirees().size(); i++) {
                out.println(String.format("  R%-3d  ", i + 1) + phrase(r.retirees().get(i).puce().assertion()));
            }
        }
        r.avertissements().forEach(a -> out.println("  Attention : " + a));
    }

    /** L'évaluation seule : le filtre ATS, puis le recruteur, critère par critère. */
    static void evaluation(PrintWriter out, Dossier d, boolean avisPerime) {
        Evaluation e = d.evaluation();
        e.ats().ifPresent(a -> {
            out.println("Filtre ATS : " + a.note() + "/100" + (a.lisible() ? "" : ", texte illisible"));
            if (!a.presentes().isEmpty()) {
                out.println("  retrouvées : " + a.presentes().stream().map(x -> marque(x) + " " + x.libelle()).collect(Collectors.joining(", ")));
            }
            if (!a.absentes().isEmpty()) {
                out.println("  absentes : " + a.absentes().stream().map(x -> marque(x) + " " + x.libelle()).collect(Collectors.joining(", ")));
            }
        });
        AvisRecruteur avis = e.avis();
        out.println("Recruteur : " + avis.note() + "/100" + (avisPerime ? " — avis antérieur à vos corrections" : ""));
        avis.criteres().forEach(n -> out.println("  " + n.critere().libelle() + " " + n.note() + "/5 — " + n.justification()));
        liste(out, "Forces", avis.forces());
        liste(out, "Faiblesses", avis.faiblesses());
        liste(out, "Corrections proposées", avis.corrections());
        liste(out, "Corrections écartées : elles demandaient un fait absent du profil (RG-07)", d.rapport().correctionsEcartees());
    }

    /** «   12  • Conçu un tableau de bord [exp-acme-r1] » : le numéro, puis la phrase, et le compteur avance. */
    private static String numerotee(int[] numero, String marque, Assertion a) {
        return String.format("%4d    ", numero[0]++) + marque + phrase(a);
    }

    private static void liste(PrintWriter out, String titre, List<String> elements) {
        if (!elements.isEmpty()) {
            out.println("  " + titre);
            elements.forEach(x -> out.println("    - " + x));
        }
    }

    /** UC-06 : la décision revient au candidat ; sans décision, l'offre reste un brouillon (A1). */
    static void decision(PrintWriter out, long numero) {
        out.println("Décision : postulat avancer " + numero + " à_préparer · postulat ecarter " + numero
                + " MOTIF · sans décision, l'offre reste un brouillon du tableau de bord.");
    }

    private static String champ(String nom, String valeur) {
        return valeur.isBlank() ? "" : nom + " : " + valeur;
    }

    private static String marque(Exigence e) {
        return e.obligatoire() ? "[O]" : "[S]";
    }

    /** La commande qui traite l'action, quand il y en a une. */
    static Optional<String> suggestion(Echeance e, long numero) {
        return switch (e.motif()) {
            case Echeance.Motif.Relancer r -> Optional.of("postulat relancer " + numero);
            case Echeance.Motif.ProposerSansSuite p -> Optional.of("postulat avancer " + numero + " sans_suite");
            case Echeance.Motif.PreparerEntretien p -> Optional.of("postulat voir " + numero);
            case Echeance.Motif.Remercier r -> Optional.empty();
            case Echeance.Motif.RetourPromis r -> Optional.empty();
        };
    }
}
