package postulat.cli;

import java.io.PrintWriter;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import postulat.stockage.Base;
import postulat.stockage.DossierGenere;
import postulat.stockage.CoutModele;
import postulat.stockage.Numerotee;
import postulat.stockage.Offre;
import postulat.stockage.Reglages;
import postulat.suivi.Candidature;
import postulat.suivi.Contact;
import postulat.suivi.Echeance;
import postulat.suivi.Echeances;
import postulat.suivi.Etape;
import postulat.suivi.Evenement;
import postulat.suivi.TableauDeBord;

/** Commandes de lecture seule. Rien n'y est stocké : tout est recalculé à l'instant courant. */
final class Consultations {

    private Consultations() {}

    @Command(name = "tableau", description = "L'état de la campagne d'un coup d'œil (UC-16).")
    static final class Tableau extends Commande {

        @Override
        public Integer call() {
            LocalDate aujourdhui = aujourdhui();
            Reglages reglages = racine().donnees().reglages();
            List<Numerotee> toutes;
            try (Base base = ouvrirBase()) {
                toutes = base.toutes();
            }
            Map<UUID, Long> numeros = numeros(toutes);
            TableauDeBord t = TableauDeBord.calculer(candidatures(toutes), aujourdhui, reglages.relance(),
                    reglages.objectifHebdomadaire());
            PrintWriter out = sortie();

            out.println("Tableau de bord du " + Affichage.date(aujourdhui));
            out.println();
            out.println("Répartition par étape");
            out.println("  " + repartition(t, false));
            out.println("  terminées : " + repartition(t, true));
            out.println();
            out.println("Envois par semaine (objectif " + t.objectifHebdomadaire() + ")");
            LocalDate debut = toutes.stream().map(n -> n.candidature().dateCreation())
                    .min(Comparator.naturalOrder()).orElse(aujourdhui);
            for (TableauDeBord.Semaine s : t.semaines()) {
                out.printf("  semaine du %s  %3d / %d%s%n", Affichage.date(s.lundi()), s.envoyees(),
                        t.objectifHebdomadaire(), etat(s, debut));
            }
            out.println();
            titre(out, "En attente de réponse", t.enAttente().size(), "la plus ancienne d'abord");
            for (Candidature c : t.enAttente()) {
                out.println("  " + Affichage.titre(numeros.get(c.id()), c) + " · envoyée le "
                        + Affichage.date(c.dateEntreeEtape()) + ", " + Affichage.ilYA(c.dateEntreeEtape(), aujourdhui)
                        + ", " + relances(c.relancesDepuisEnvoi().size()));
            }
            out.println();
            titre(out, "Actions dues aujourd'hui", t.actionsDues().size(), "la plus urgente d'abord");
            for (Echeance e : t.actionsDues()) {
                long numero = numeros.get(e.candidatureId());
                out.println("  " + Affichage.titre(numero, e) + " · " + Affichage.motif(e.motif()) + ", "
                        + Affichage.retard(e, aujourdhui) + Affichage.suggestion(e, numero).map(s -> " → " + s).orElse(""));
            }
            out.println();
            titre(out, "Échéances des 7 prochains jours", t.aVenir().size(), "la plus proche d'abord");
            for (Echeance e : t.aVenir()) {
                out.println("  " + Affichage.date(e.date()) + " · " + Affichage.titre(numeros.get(e.candidatureId()), e)
                        + " · " + Affichage.motif(e.motif()));
            }
            out.println();
            titre(out, "Brouillons à « repérée »", t.brouillons().size(), "le plus ancien d'abord");
            for (Candidature c : t.brouillons()) {
                out.println("  " + Affichage.titre(numeros.get(c.id()), c) + " · repérée le "
                        + Affichage.date(c.dateEntreeEtape()) + ", " + Affichage.ilYA(c.dateEntreeEtape(), aujourdhui));
            }
            return 0;
        }

        private static String repartition(TableauDeBord t, boolean terminales) {
            return t.repartition().entrySet().stream()
                    .filter(e -> e.getKey().estTerminale() == terminales)
                    .map(e -> e.getKey() + " " + e.getValue())
                    .collect(Collectors.joining(" · "));
        }

        /** Une semaine partielle ou en cours ne se lit pas comme un retard sur l'objectif (UC-16 A1, RG-22). */
        private static String etat(TableauDeBord.Semaine s, LocalDate debut) {
            String partielle = s.partielle() ? "partielle : suivi commencé le " + Affichage.date(debut) : "";
            if (s.enCours()) {
                return "   en cours" + (partielle.isEmpty() ? "" : ", " + partielle);
            }
            return partielle.isEmpty() ? "" : "   " + partielle;
        }

        private static void titre(PrintWriter out, String titre, int nombre, String ordre) {
            out.println(titre + " : " + (nombre == 0 ? "aucune" : nombre + ", " + ordre));
        }

        private static String relances(int n) {
            return n == 0 ? "aucune relance" : n + (n > 1 ? " relances" : " relance");
        }
    }

    @Command(name = "actions", description = "Ce qu'il y a à faire aujourd'hui, le plus urgent d'abord (UC-14).")
    static final class Actions extends Commande {

        @Override
        public Integer call() {
            LocalDate aujourdhui = aujourdhui();
            List<Numerotee> toutes;
            try (Base base = ouvrirBase()) {
                toutes = base.toutes();
            }
            Map<UUID, Long> numeros = numeros(toutes);
            List<Candidature> candidatures = candidatures(toutes);
            var regles = racine().donnees().reglages().relance();
            List<Echeance> dues = Echeances.listeDeTravail(candidatures, aujourdhui, regles);
            PrintWriter out = sortie();

            if (dues.isEmpty()) {
                // UC-14 A1 : le dire, plutôt qu'afficher une liste vide.
                out.println("Aucune action due aujourd'hui (" + Affichage.date(aujourdhui) + ").");
                candidatures.stream()
                        .flatMap(c -> Echeances.echeances(c, aujourdhui, regles).stream())
                        .min(Comparator.comparing(Echeance::date))
                        .ifPresent(e -> out.println("Prochaine échéance le " + Affichage.date(e.date()) + " : "
                                + Affichage.titre(numeros.get(e.candidatureId()), e) + " · " + Affichage.motif(e.motif())));
                return 0;
            }
            out.println("Actions dues le " + Affichage.date(aujourdhui) + " : " + dues.size() + ", la plus urgente d'abord");
            for (Echeance e : dues) {
                long numero = numeros.get(e.candidatureId());
                out.println("  " + Affichage.titre(numero, e) + " (" + e.etape() + ")");
                out.println("     " + Affichage.motif(e.motif()) + ", " + Affichage.retard(e, aujourdhui)
                        + Affichage.suggestion(e, numero).map(s -> " → " + s).orElse(""));
            }
            return 0;
        }
    }

    @Command(name = "liste", description = "Les candidatures, regroupées par entreprise (CA-6.7).")
    static final class Liste extends Commande {

        @Option(names = "--etape", paramLabel = "ETAPE", description = "Seulement celles à cette étape.")
        Etape etape;

        @Option(names = "--entreprise", paramLabel = "TEXTE", description = "Seulement les entreprises dont le nom contient ce texte.")
        String entreprise;

        @Option(names = "--toutes", description = "Inclure les candidatures terminées.")
        boolean toutes;

        @Override
        public Integer call() {
            List<Numerotee> liste;
            try (Base base = ouvrirBase()) {
                liste = etape != null ? base.aLEtape(etape) : base.toutes();
            }
            List<Numerotee> retenues = liste.stream()
                    .filter(n -> etape != null || toutes || !n.candidature().etape().estTerminale())
                    .filter(n -> entreprise == null
                            || Conversions.cle(n.candidature().entreprise()).contains(Conversions.cle(entreprise)))
                    .sorted(Comparator.comparing((Numerotee n) -> Conversions.cle(n.candidature().entreprise()))
                            .thenComparing(Numerotee::numero))
                    .toList();
            PrintWriter out = sortie();
            if (retenues.isEmpty()) {
                out.println(liste.isEmpty() ? "Aucune candidature pour l'instant : postulat ajouter ENTREPRISE POSTE"
                        : "Aucune candidature ne correspond." + (toutes ? "" : " Les terminées : --toutes"));
                return 0;
            }
            String groupe = null;
            for (Numerotee n : retenues) {
                Candidature c = n.candidature();
                if (!Conversions.cle(c.entreprise()).equals(groupe)) {
                    groupe = Conversions.cle(c.entreprise());
                    out.println(c.entreprise());
                }
                out.println("  #" + n.numero() + " " + c.poste() + " · " + c.etape() + " depuis le "
                        + Affichage.date(c.dateEntreeEtape()));
            }
            return 0;
        }
    }

    @Command(name = "voir", description = "Tout sur une candidature : étape, contacts, historique, échéances (UC-12).")
    static final class Voir extends Commande.SurCandidature {

        @Override
        public Integer call() {
            LocalDate aujourdhui = aujourdhui();
            Numerotee n;
            List<Contact> contacts;
            CoutModele cout;
            Optional<Offre> offre;
            Optional<DossierGenere> dossier;
            try (Base base = ouvrirBase()) {
                n = trouver(base);
                contacts = base.contacts(n.candidature().id());
                cout = base.coutModele(n.candidature().id());
                offre = base.offre(n.candidature().id());
                dossier = base.dossier(n.candidature().id());
            }
            Candidature c = n.candidature();
            PrintWriter out = sortie();

            out.println(Affichage.titre(n.numero(), c));
            out.println("Étape : " + c.etape() + ", depuis le " + Affichage.date(c.dateEntreeEtape()) + " ("
                    + Affichage.ilYA(c.dateEntreeEtape(), aujourdhui) + ")");
            // UC-12 A1 : l'absence de dossier se dit, elle ne se devine pas.
            out.println(dossier.map(d -> "Dossier : généré le "
                            + Affichage.date(LocalDate.ofInstant(d.genereLe(), racine().horloge().getZone())) + ", note globale "
                            + d.dossier().evaluation().noteGlobale() + "/100, non validé. Pour le relire : postulat dossier voir " + n.numero())
                    .orElse("Dossier : aucun dossier rattaché."));
            // EN-5 : le coût par candidature s'affiche.
            out.println("Modèle de langage : " + (cout.appels() == 0 ? "aucun appel"
                    : Affichage.appels(cout.appels()) + ", " + Affichage.dollars(cout.coutMicroDollars())));
            out.println();
            if (offre.isPresent()) {
                Affichage.offre(out, offre.get(), racine().horloge().getZone(), n.numero());
            } else {
                out.println("Offre : aucune, candidature saisie à la main.");
            }
            out.println();
            out.println("Contacts");
            if (contacts.isEmpty()) {
                out.println("  aucun");
            }
            for (Contact contact : contacts) {
                out.println("  " + Stream.of(contact.nom(), contact.role(), contact.coordonnees())
                        .filter(s -> !s.isEmpty()).collect(Collectors.joining(" · ")));
            }
            out.println();
            out.println("Historique");
            for (Evenement e : c.journal()) {
                out.println("  " + Affichage.date(e.date()) + "  " + Affichage.evenement(e));
            }
            out.println();
            out.println("Échéances");
            List<Echeance> echeances = Echeances.echeances(c, aujourdhui, racine().donnees().reglages().relance())
                    .stream().sorted(Comparator.comparing(Echeance::date)).toList();
            if (echeances.isEmpty()) {
                out.println("  aucune");
            }
            for (Echeance e : echeances) {
                out.println("  " + Affichage.date(e.date()) + "  " + Affichage.motif(e.motif()) + " ("
                        + Affichage.retard(e, aujourdhui) + ")"
                        + Affichage.suggestion(e, n.numero())
                                .filter(s -> e.estAtteinte(aujourdhui) && !s.startsWith("postulat voir"))
                                .map(s -> " → " + s).orElse(""));
            }
            return 0;
        }
    }

    static List<Candidature> candidatures(List<Numerotee> numerotees) {
        return numerotees.stream().map(Numerotee::candidature).toList();
    }
}
