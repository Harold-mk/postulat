package postulat.cli;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import postulat.cas.Refus;
import postulat.libelles.Libelles;
import postulat.stockage.Base;
import postulat.stockage.Numerotee;
import postulat.suivi.Canal;
import postulat.suivi.Candidature;
import postulat.suivi.Contact;
import postulat.suivi.Echeance;
import postulat.suivi.Echeances;
import postulat.suivi.Etape;
import postulat.suivi.TypeEntretien;

/** Commandes qui enregistrent ce qui s'est passé. Toutes datent l'événement du jour réel (UC-10, UC-11). */
final class Saisies {

    private Saisies() {}

    static final String AIDE_DATE = "Jour où c'est arrivé : 2026-10-01, 01/10/2026, hier… Par défaut : aujourd'hui.";

    /** Gabarit commun : lire, appliquer l'opération du domaine, enregistrer, rendre compte. */
    abstract static class Saisie extends Commande.SurCandidature {

        @Option(names = "--le", paramLabel = "DATE", description = AIDE_DATE)
        LocalDate le;

        abstract Candidature appliquer(Candidature c, LocalDate date, LocalDate aujourdhui);

        /** Ce qui a été enregistré, en quelques mots. */
        abstract String bilan(Candidature apres, LocalDate date);

        /** La suite logique, s'il y en a une à suggérer. */
        Optional<String> conseil() {
            return Optional.empty();
        }

        @Override
        public Integer call() {
            LocalDate aujourdhui = aujourdhui();
            LocalDate date = le == null ? aujourdhui : le;
            try (Base base = ouvrirBase()) {
                Numerotee n = trouver(base);
                Candidature apres = appliquer(n.candidature(), date, aujourdhui);
                base.enregistrer(apres);
                sortie().println(Libelles.titre(n.numero(), apres) + " : " + bilan(apres, date) + ".");
                afficherEcheances(this, apres);
                conseil().ifPresent(sortie()::println);
            }
            return 0;
        }
    }

    /** Après chaque saisie, ce qui est désormais attendu : le système rappelle (US-7). */
    static void afficherEcheances(Commande commande, Candidature c) {
        List<Echeance> echeances = Echeances.echeances(c, commande.aujourdhui(), commande.racine().donnees().reglages().relance())
                .stream().sorted(Comparator.comparing(Echeance::date)).toList();
        for (Echeance e : echeances) {
            commande.sortie().println("  échéance le " + Libelles.date(e.date()) + " : " + Libelles.motif(e.motif()));
        }
    }

    @Command(name = "ajouter", description = "Ajoute une candidature à l'étape atteinte, à sa date réelle (UC-10).")
    static final class Ajouter extends Commande {

        @Parameters(index = "0", paramLabel = "ENTREPRISE")
        String entreprise;

        @Parameters(index = "1", paramLabel = "POSTE")
        String poste;

        @Option(names = "--etape", paramLabel = "ETAPE", defaultValue = "repérée",
                description = "Étape atteinte : repérée, envoyée, entretien… Par défaut : ${DEFAULT-VALUE}.")
        Etape etape;

        @Option(names = "--le", paramLabel = "DATE", description = AIDE_DATE)
        LocalDate le;

        @Option(names = "--distincte",
                description = "Ajouter même si une candidature porte la même entreprise et le même poste : c'est une autre offre.")
        boolean distincte;

        @Override
        public Integer call() {
            LocalDate aujourdhui = aujourdhui();
            LocalDate date = le == null ? aujourdhui : le;
            try (Base base = ouvrirBase()) {
                if (!distincte) {
                    refuserDoublon(base);
                }
                Candidature c = Candidature.creer(entreprise, poste, etape, date, aujourdhui);
                Numerotee n = base.creer(c);
                sortie().println("Candidature " + Libelles.titre(n.numero(), c) + " ajoutée à l'étape "
                        + Libelles.etape(etape) + " le " + Libelles.date(date) + ".");
                afficherEcheances(this, c);
            }
            return 0;
        }

        /** RG-18 : une offre déjà connue ne crée pas de doublon ; on propose de reprendre l'existante (UC-10 E2). */
        private void refuserDoublon(Base base) {
            Optional<Numerotee> existante = base.toutes().stream()
                    .filter(n -> n.candidature().memeOffre(entreprise, poste))
                    .findFirst();
            if (existante.isPresent()) {
                Numerotee n = existante.get();
                Etape actuelle = n.candidature().etape();
                throw new Refus("Cette offre est déjà suivie : " + Libelles.titre(n.numero(), n.candidature())
                        + ", à l'étape " + Libelles.etape(actuelle) + ".\n"
                        + (actuelle.estTerminale()
                                ? "Pour la reprendre : postulat rouvrir " + n.numero() + " ETAPE"
                                : "Pour la faire avancer : postulat avancer " + n.numero() + " ETAPE")
                        + "\nS'il s'agit d'une autre offre : ajoutez --distincte.");
            }
        }
    }

    @Command(name = "avancer", description = "Fait passer une candidature à l'étape suivante (UC-11).")
    static final class Avancer extends Saisie {

        @Parameters(index = "1", paramLabel = "ETAPE",
                description = "Étape atteinte : à_préparer, prête, envoyée, entretien, offre_reçue, acceptée, refusée, sans_suite, "
                        + "retirée. Pour écarter une offre, avec son motif : postulat ecarter.")
        Etape vers;

        @Override
        Candidature appliquer(Candidature c, LocalDate date, LocalDate aujourdhui) {
            return c.avancer(vers, date, aujourdhui);
        }

        @Override
        String bilan(Candidature apres, LocalDate date) {
            return "passée à " + Libelles.etape(vers) + " le " + Libelles.date(date);
        }

        @Override
        Optional<String> conseil() {
            return vers == Etape.ENTRETIEN
                    ? Optional.of("Pour fixer la date de l'entretien : postulat planifier " + numero + " JOUR --type rh|technique|final")
                    : Optional.empty();
        }
    }

    @Command(name = "ecarter", description = "Décide de ne pas postuler, en disant pourquoi ; l'offre et son analyse restent "
            + "conservées (UC-06).")
    static final class Ecarter extends Saisie {

        @Parameters(index = "1..*", paramLabel = "MOTIF", description = "Pourquoi : il servira à savoir ce qui manque le plus souvent.")
        List<String> mots;

        @Override
        Candidature appliquer(Candidature c, LocalDate date, LocalDate aujourdhui) {
            return c.ecarter(String.join(" ", mots), date, aujourdhui);
        }

        @Override
        String bilan(Candidature apres, LocalDate date) {
            return "écartée le " + Libelles.date(date) + " : " + String.join(" ", mots).strip();
        }
    }

    @Command(name = "rouvrir", description = "Sort explicitement une candidature d'une étape terminale (CA-6.5).")
    static final class Rouvrir extends Saisie {

        @Parameters(index = "1", paramLabel = "ETAPE", description = "Étape ouverte où reprendre.")
        Etape vers;

        @Override
        Candidature appliquer(Candidature c, LocalDate date, LocalDate aujourdhui) {
            return c.reouvrir(vers, date, aujourdhui);
        }

        @Override
        String bilan(Candidature apres, LocalDate date) {
            return "rouverte à " + Libelles.etape(vers) + " le " + Libelles.date(date);
        }
    }

    @Command(name = "relancer",
            description = "Enregistre une relance que vous avez faite vous-même : postulat n'envoie rien (UC-15).")
    static final class Relancer extends Saisie {

        @Override
        Candidature appliquer(Candidature c, LocalDate date, LocalDate aujourdhui) {
            return c.relancer(date, aujourdhui);
        }

        @Override
        String bilan(Candidature apres, LocalDate date) {
            return "relance n° " + apres.relancesDepuisEnvoi().size() + " enregistrée le " + Libelles.date(date);
        }
    }

    @Command(name = "planifier", description = "Enregistre un entretien fixé à une date, même à venir (CA-7.4).")
    static final class Planifier extends Saisie {

        @Parameters(index = "1", paramLabel = "JOUR", description = "Jour de l'entretien.")
        LocalDate jour;

        @Option(names = "--type", required = true, paramLabel = "TYPE", description = "rh, technique ou final.")
        TypeEntretien type;

        @Override
        Candidature appliquer(Candidature c, LocalDate date, LocalDate aujourdhui) {
            return c.planifierEntretien(type, jour, date, aujourdhui);
        }

        @Override
        String bilan(Candidature apres, LocalDate date) {
            return "entretien " + Libelles.entretien(type) + " planifié le " + Libelles.date(jour);
        }
    }

    @Command(name = "entretien", description = "Enregistre un entretien passé, sans changer l'étape (CA-6.13, CA-8.4).")
    static final class Entretien extends Saisie {

        @Option(names = "--type", required = true, paramLabel = "TYPE", description = "rh, technique ou final.")
        TypeEntretien type;

        @Option(names = "--compte-rendu", paramLabel = "TEXTE", description = "Compte rendu, joint en note.")
        String compteRendu;

        @Override
        Candidature appliquer(Candidature c, LocalDate date, LocalDate aujourdhui) {
            Candidature passe = c.noterEntretien(type, date, aujourdhui);
            return compteRendu == null || compteRendu.isBlank()
                    ? passe
                    : passe.noter("Compte rendu de l'entretien " + Libelles.entretien(type) + " : " + compteRendu.strip(),
                            date, aujourdhui);
        }

        @Override
        String bilan(Candidature apres, LocalDate date) {
            return "entretien " + Libelles.entretien(type) + " passé le " + Libelles.date(date)
                    + (compteRendu == null || compteRendu.isBlank() ? "" : ", compte rendu joint");
        }
    }

    @Command(name = "retour-promis", description = "Enregistre une réponse promise par l'entreprise pour une date (CA-7.5).")
    static final class RetourPromis extends Saisie {

        @Parameters(index = "1", paramLabel = "JOUR", description = "Jour pour lequel la réponse est promise.")
        LocalDate pour;

        @Override
        Candidature appliquer(Candidature c, LocalDate date, LocalDate aujourdhui) {
            return c.noterRetourPromis(pour, date, aujourdhui);
        }

        @Override
        String bilan(Candidature apres, LocalDate date) {
            return "retour promis pour le " + Libelles.date(pour);
        }
    }

    @Command(name = "interaction", description = "Enregistre un échange avec l'entreprise : courriel ou appel (CA-8.2).")
    static final class Interaction extends Saisie {

        @Parameters(index = "1", paramLabel = "CANAL", description = "courriel ou appel.")
        Canal canal;

        @Option(names = "--notes", paramLabel = "TEXTE", description = "Ce qui s'est dit.")
        String notes = "";

        @Override
        Candidature appliquer(Candidature c, LocalDate date, LocalDate aujourdhui) {
            return c.interagir(canal, notes, date, aujourdhui);
        }

        @Override
        String bilan(Candidature apres, LocalDate date) {
            return Libelles.canal(canal) + " enregistré le " + Libelles.date(date);
        }
    }

    @Command(name = "noter", description = "Ajoute une note libre, permise à toute étape (CA-6.5).")
    static final class Noter extends Saisie {

        @Parameters(index = "1..*", paramLabel = "TEXTE", description = "Texte de la note.")
        List<String> mots;

        @Override
        Candidature appliquer(Candidature c, LocalDate date, LocalDate aujourdhui) {
            return c.noter(String.join(" ", mots), date, aujourdhui);
        }

        @Override
        String bilan(Candidature apres, LocalDate date) {
            return "note ajoutée le " + Libelles.date(date);
        }
    }

    @Command(name = "contact", description = "Rattache un contact à une candidature (CA-8.1).")
    static final class AjouterContact extends Commande.SurCandidature {

        @Parameters(index = "1", paramLabel = "NOM")
        String nom;

        @Option(names = "--role", paramLabel = "ROLE", description = "RH, manager, ingénieur…")
        String role = "";

        @Option(names = "--coordonnees", paramLabel = "TEXTE", description = "Courriel, téléphone, profil…")
        String coordonnees = "";

        @Override
        public Integer call() {
            try (Base base = ouvrirBase()) {
                Numerotee n = trouver(base);
                Contact contact = new Contact(nom, role, coordonnees);
                base.ajouterContact(n.candidature().id(), contact);
                sortie().println(Libelles.titre(n.numero(), n.candidature()) + " : contact " + contact.nom() + " ajouté.");
            }
            return 0;
        }
    }
}
