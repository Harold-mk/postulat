package postulat.cli;

import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import postulat.ia.Gamme;
import postulat.production.Assertion;
import postulat.production.AvisRecruteur;
import postulat.production.Couverture;
import postulat.production.Cv;
import postulat.production.Dossier;
import postulat.production.Evaluation;
import postulat.production.Exigence;
import postulat.production.Lettre;
import postulat.production.OffreAnalysee;
import postulat.production.Origine;
import postulat.production.Rapprochement;
import postulat.stockage.Offre;
import postulat.suivi.Canal;
import postulat.suivi.Candidature;
import postulat.suivi.Echeance;
import postulat.suivi.Etape;
import postulat.suivi.Evenement;
import postulat.suivi.TypeEntretien;

/** Mise en mots des objets du domaine, en français. */
final class Affichage {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");

    private Affichage() {}

    static String date(LocalDate jour) {
        return DATE.format(jour);
    }

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

    /** « Automatisé le reporting [r-acme-1] », « Votre équipe… [offre] » */
    static String phrase(Assertion a) {
        return a.texte() + " " + switch (a.origine()) {
            case Origine.Faits f -> "[" + String.join(", ", f.ids()) + "]";
            case Origine.Offre o -> "[offre]";
            case Origine.Auteur au -> "[de votre main]";
        };
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

    /** « 0,0110 $ » ; un coût non nul trop petit pour quatre décimales ne s'affiche pas comme nul. */
    static String dollars(long microDollars) {
        if (microDollars > 0 && microDollars < 100) {
            return "< 0,0001 $";
        }
        return String.format(Locale.FRANCE, "%.4f $", microDollars / 1_000_000.0);
    }

    static String appels(int n) {
        return n + (n > 1 ? " appels" : " appel");
    }

    static String gamme(Gamme gamme) {
        return switch (gamme) {
            case ECONOMIQUE -> "économique";
            case CAPABLE -> "capable";
        };
    }

    static String jours(long n) {
        return n + (Math.abs(n) > 1 ? " jours" : " jour");
    }

    /** « aujourd'hui », « il y a 1 jour », « il y a 12 jours ». */
    static String ilYA(LocalDate jour, LocalDate aujourdhui) {
        long n = ChronoUnit.DAYS.between(jour, aujourdhui);
        return n == 0 ? "aujourd'hui" : "il y a " + jours(n);
    }

    /** « #3 Acme — Stage data » */
    static String titre(long numero, Candidature c) {
        return "#" + numero + " " + c.entreprise() + " — " + c.poste();
    }

    static String titre(long numero, Echeance e) {
        return "#" + numero + " " + e.entreprise() + " — " + e.poste();
    }

    static String etape(Etape etape) {
        return "« " + etape + " »";
    }

    static String entretien(TypeEntretien type) {
        return switch (type) {
            case RH -> "RH";
            case TECHNIQUE -> "technique";
            case FINAL -> "final";
        };
    }

    static String canal(Canal canal) {
        return switch (canal) {
            case COURRIEL -> "courriel";
            case APPEL -> "appel";
        };
    }

    static String evenement(Evenement e) {
        return switch (e) {
            case Evenement.Creation c -> "créée à l'étape " + etape(c.etape());
            case Evenement.ChangementEtape c -> "passée à " + etape(c.vers());
            case Evenement.Ecartement ec -> "écartée : " + ec.motif();
            case Evenement.Reouverture r -> "rouverte à " + etape(r.vers());
            case Evenement.RelanceEffectuee r -> "relance effectuée";
            case Evenement.EntretienPasse ep -> "entretien " + entretien(ep.type()) + " passé";
            case Evenement.EntretienPlanifie ep -> "entretien " + entretien(ep.type()) + " planifié le " + date(ep.le());
            case Evenement.Interaction i -> canal(i.canal()) + (i.notes().isEmpty() ? "" : " : " + i.notes());
            case Evenement.RetourPromis rp -> "retour promis pour le " + date(rp.pour());
            case Evenement.Note n -> "note : " + n.texte();
        };
    }

    static String motif(Echeance.Motif motif) {
        return switch (motif) {
            case Echeance.Motif.Relancer r -> "relancer (relance n° " + r.rang() + ")";
            case Echeance.Motif.ProposerSansSuite p -> "relances épuisées sans réponse : passer à « sans_suite » ?";
            case Echeance.Motif.PreparerEntretien p ->
                    "préparer l'entretien " + entretien(p.type()) + " du " + date(p.le());
            case Echeance.Motif.Remercier r ->
                    "remercier après l'entretien " + entretien(r.type()) + " du " + date(r.le());
            case Echeance.Motif.RetourPromis r -> "retour promis attendu : sans nouvelles, relancer ?";
        };
    }

    /** « aujourd'hui », « en retard de 3 jours », « dans 2 jours ». */
    static String retard(Echeance e, LocalDate aujourdhui) {
        long n = e.joursDeRetard(aujourdhui);
        if (n == 0) {
            return "aujourd'hui";
        }
        return n > 0 ? "en retard de " + jours(n) : "dans " + jours(-n);
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
