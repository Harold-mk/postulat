package postulat.cli;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import postulat.ia.Gamme;
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
