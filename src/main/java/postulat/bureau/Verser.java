package postulat.bureau;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javafx.scene.Node;
import javafx.scene.control.TextInputControl;
import postulat.bureau.Ui.Ton;
import postulat.cas.CasDossier;
import postulat.cas.Refus;
import postulat.production.Assertion;
import postulat.production.Competence;
import postulat.production.Dossier;
import postulat.production.Fait;
import postulat.production.Profil;
import postulat.production.Realisation;
import postulat.production.Recoupement;

/**
 * UC-04 : faire d'une phrase de votre main un fait du profil, que les prochaines générations pourront
 * citer. La phrase reste de votre main dans ce dossier (RG-23). Un fait qui semble redire un fait
 * existant est signalé avant d'être créé (A2) : on choisit alors d'enrichir l'existant, ou de créer
 * quand même un fait distinct.
 */
final class Verser {

    private Verser() {}

    private static final String EXPLICATION = "La phrase reste de votre main dans ce dossier (RG-23) ; la prochaine génération "
            + "s'appuiera sur le fait, avec toutes les vérifications ordinaires.";

    static void realisation(Fenetre f, Session s, long numero, Dossier d, int phrase) {
        Profil profil = profil(s);
        Assertion a = d.phrase(phrase);
        List<String> parents = CasDossier.parents(profil);
        Formulaire fo = new Formulaire("Verser la phrase n° " + phrase + " comme réalisation", EXPLICATION);
        var dans = fo.choix("Sous", parents, d.parentPropose(phrase).filter(parents::contains)
                .orElse(parents.isEmpty() ? null : parents.getFirst()), id -> libelle(profil, id));
        var objectif = fo.texte("Objectif", "");
        objectif.setPromptText("le but poursuivi — obligatoire");
        var tache = fo.paragraphe("Tâche", a.texte(), 2);
        var resultat = fo.texte("Résultat", "");
        resultat.setPromptText("chiffré s'il l'est");
        var competences = fo.texte("Compétences", "");
        competences.setPromptText("séparées par des virgules");
        var consigne = fo.texte("Consigne", "");
        consigne.setPromptText("de présentation, facultative");
        var distinct = fo.caseACocher("Créer le fait même s'il semble redire un fait existant");
        CasDossier.Versement[] v = {null};
        if (fo.afficher(() -> {
            if (dans.getValue() == null) {
                throw new IllegalArgumentException("Le profil n'a ni expérience ni formation où ranger une réalisation.");
            }
            if (objectif.getText().isBlank()) {
                throw new IllegalArgumentException("Il manque l'objectif : le but que poursuivait cette réalisation.");
            }
            if (!distinct.isSelected()) {
                Optional<Realisation> proche = Recoupement.realisationProche(profil, dans.getValue(),
                        saisi(tache).orElse(a.texte()) + " " + saisi(resultat).orElse(""));
                if (proche.isPresent()) {
                    throw new IllegalArgumentException("Cette phrase semble redire le fait « " + proche.get().id() + " » : "
                            + proche.get().tache() + (proche.get().resultat().isBlank() ? "" : " ; " + proche.get().resultat()) + "."
                            + System.lineSeparator() + "Pour le compléter plutôt que d'en créer un second : « Verser au profil pour "
                            + "enrichir un fait ». Pour créer quand même un fait distinct : cochez la case.");
                }
            }
            v[0] = s.dossiers().verser(numero, phrase, new CasDossier.Demande(Optional.of(dans.getValue()), saisi(objectif),
                    saisi(tache), saisi(resultat), liste(competences), saisi(consigne), false, Optional.empty(), Optional.empty(),
                    Optional.empty(), true));
        })) {
            annoncer(f, phrase, v[0]);
        }
    }

    static void competence(Fenetre f, Session s, long numero, Dossier d, int phrase) {
        Profil profil = profil(s);
        Assertion a = d.phrase(phrase);
        Formulaire fo = new Formulaire("Verser la phrase n° " + phrase + " comme compétence", EXPLICATION);
        var nom = fo.texte("Nom", a.texte());
        var niveau = fo.texte("Niveau", "");
        niveau.setPromptText("facultatif");
        var consigne = fo.texte("Consigne", "");
        consigne.setPromptText("de présentation, facultative");
        var distinct = fo.caseACocher("Créer la compétence même si elle porte le nom d'une compétence existante");
        CasDossier.Versement[] v = {null};
        if (fo.afficher(() -> {
            Optional<Competence> homonyme = Recoupement.competenceHomonyme(profil, saisi(nom).orElse(a.texte()));
            if (homonyme.isPresent() && !distinct.isSelected()) {
                throw new IllegalArgumentException("La compétence « " + homonyme.get().nom() + " » est déjà au profil ("
                        + homonyme.get().id() + "). Pour la compléter : « Verser au profil pour enrichir un fait ». Pour en créer une "
                        + "seconde : cochez la case.");
            }
            v[0] = s.dossiers().verser(numero, phrase, new CasDossier.Demande(Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), saisi(consigne), true, saisi(nom), saisi(niveau), Optional.empty(), true));
        })) {
            annoncer(f, phrase, v[0]);
        }
    }

    /** UC-04 A2 : les champs remplis remplacent ceux du fait ; les autres gardent sa valeur. */
    static void enrichir(Fenetre f, Session s, long numero, Dossier d, int phrase) {
        Profil profil = profil(s);
        Assertion a = d.phrase(phrase);
        List<Fait> faits = profil.faits().stream().filter(x -> x instanceof Realisation || x instanceof Competence).toList();
        if (faits.isEmpty()) {
            throw new Refus("Le profil n'a ni réalisation ni compétence à enrichir.");
        }
        Formulaire fo = new Formulaire("Enrichir un fait avec la phrase n° " + phrase, "« " + a.texte() + " »"
                + System.lineSeparator() + "Les champs remplis remplacent ceux du fait ; les champs laissés vides gardent sa valeur. "
                + EXPLICATION);
        Fait propose = d.parentPropose(phrase).flatMap(p -> Recoupement.realisationProche(profil, p, a.texte())).map(Fait.class::cast)
                .orElse(faits.getFirst());
        var fait = fo.choix("Fait", faits, propose, x -> resume(x));
        var objectif = fo.texte("Objectif", "");
        var tache = fo.texte("Tâche", "");
        var resultat = fo.texte("Résultat", "");
        var competences = fo.texte("Compétences", "");
        competences.setPromptText("séparées par des virgules ; remplacent la liste");
        var nom = fo.texte("Nom", "");
        var niveau = fo.texte("Niveau", "");
        var consigne = fo.texte("Consigne", "");
        List<Node> deRealisation = List.of(objectif, tache, resultat, competences);
        List<Node> deCompetence = List.of(nom, niveau);
        Runnable ajuster = () -> {
            boolean r = fait.getValue() instanceof Realisation;
            deRealisation.forEach(x -> fo.montrer(x, r));
            deCompetence.forEach(x -> fo.montrer(x, !r));
            if (r) {
                Realisation x = (Realisation) fait.getValue();
                objectif.setPromptText(x.objectif());
                tache.setPromptText(x.tache());
                resultat.setPromptText(x.resultat().isBlank() ? "chiffré s'il l'est" : x.resultat());
            } else {
                Competence x = (Competence) fait.getValue();
                nom.setPromptText(x.nom());
                niveau.setPromptText(x.niveau());
            }
            consigne.setPromptText(fait.getValue() instanceof Realisation x ? x.consigne() : ((Competence) fait.getValue()).consigne());
        };
        fait.valueProperty().addListener((o, avant, apres) -> ajuster.run());
        ajuster.run();
        CasDossier.Versement[] v = {null};
        if (fo.afficher(() -> {
            boolean r = fait.getValue() instanceof Realisation;
            CasDossier.Demande demande = r
                    ? new CasDossier.Demande(Optional.empty(), saisi(objectif), saisi(tache), saisi(resultat), liste(competences),
                            saisi(consigne), false, Optional.empty(), Optional.empty(), Optional.of(fait.getValue().id()), false)
                    : new CasDossier.Demande(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                            saisi(consigne), false, saisi(nom), saisi(niveau), Optional.of(fait.getValue().id()), false);
            if (demande.objectif().isEmpty() && demande.tache().isEmpty() && demande.resultat().isEmpty()
                    && demande.competences().isEmpty() && demande.consigne().isEmpty() && demande.nom().isEmpty()
                    && demande.niveau().isEmpty()) {
                throw new IllegalArgumentException("Dites ce que la phrase apporte au fait « " + fait.getValue().id()
                        + " » : remplissez au moins un champ.");
            }
            v[0] = s.dossiers().verser(numero, phrase, demande);
        })) {
            annoncer(f, phrase, v[0]);
        }
    }

    private static Profil profil(Session s) {
        return s.profil().orElseThrow(() -> new Refus("Le profil est absent, ou il a des problèmes : corrigez-le avant d'y verser "
                + "une phrase."));
    }

    private static void annoncer(Fenetre f, int phrase, CasDossier.Versement v) {
        f.annoncer((v.enrichi() ? "Fait « " + v.verse().id() + " » enrichi dans le profil : " : "Fait « " + v.verse().id()
                + " » ajouté au profil : ") + resume(v.verse()) + "." + System.lineSeparator() + "La phrase n° " + phrase
                + " reste de votre main dans ce dossier (RG-23). Ancienne version du profil conservée : " + v.ancienneVersion()
                + System.lineSeparator() + "Si profil.yaml est ouvert dans un éditeur, rechargez-le avant d'y retoucher.", Ton.SUCCES);
        f.rafraichir();
    }

    /** « exp-acme — Stagiaire data analyst · Acme · 2025-01 à 2025-06 ». */
    private static String libelle(Profil profil, String id) {
        return profil.fait(id).map(Verser::resume).orElse(id);
    }

    private static String resume(Fait fait) {
        String texte = Arrays.stream(fait.texte().split("\n")).filter(l -> !l.isBlank()).collect(Collectors.joining(" · "));
        return fait.id() + " — " + (texte.length() > 90 ? texte.substring(0, 89) + "…" : texte);
    }

    private static Optional<String> saisi(TextInputControl champ) {
        return VueOffre.saisi(champ.getText());
    }

    private static Optional<List<String>> liste(TextInputControl champ) {
        List<String> l = Arrays.stream(champ.getText().split(",")).map(String::strip).filter(x -> !x.isEmpty()).toList();
        return l.isEmpty() ? Optional.empty() : Optional.of(l);
    }
}
