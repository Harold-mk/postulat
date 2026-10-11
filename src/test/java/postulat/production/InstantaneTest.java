package postulat.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** RG-16 : un dossier figé garde le contenu des faits qu'il cite, jamais un renvoi vers le profil. */
class InstantaneTest {

    static Dossier dossier(Cv cv) {
        AvisRecruteur avis = new AvisRecruteur(
                Arrays.stream(Critere.values()).map(c -> new AvisRecruteur.NoteCritere(c, 4, "")).toList(), List.of(), List.of(), List.of());
        Lettre lettre = new Lettre(Exemples.identite(), "Globex", "Lyon, le 10 octobre 2026", "Candidature", List.of(
                new Lettre.Paragraphe(List.of(new Assertion("Globex recrute.", new Origine.Offre()))),
                new Lettre.Paragraphe(List.of(new Assertion("Je maîtrise SQL.", Origine.Faits.de("comp-sql", "exp-acme")),
                        new Assertion("J'ai animé un atelier.", new Origine.Auteur())))));
        return new Dossier(cv, lettre, new Evaluation(Optional.empty(), avis, ReglesGeneration.PAR_DEFAUT),
                new Dossier.Rapport(1, List.of(), List.of(), List.of()));
    }

    @Test
    void chaque_fait_cite_est_copie_une_fois_dans_l_ordre_de_sa_premiere_citation() {
        Profil profil = Exemples.complet();

        List<Instantane> faits = Instantane.des(dossier(Apercu.de(profil)), profil);

        assertThat(faits).extracting(Instantane::fait).containsExactly("exp-acme", "exp-acme-dashboard", "form-master", "comp-sql");
        assertThat(faits.get(1).contenu()).isEqualTo(Exemples.dashboard().texte()).contains("5 KPI, utilisé par 12 commerciaux");
    }

    @Test
    void un_fait_qui_n_est_plus_dans_le_profil_ne_se_copie_pas() {
        Profil sansSql = new Profil(Exemples.identite(), List.of(Exemples.stage()), List.of(Exemples.master()), List.of());

        assertThatThrownBy(() -> Instantane.des(dossier(Apercu.de(Exemples.complet())), sansSql))
                .hasMessageContaining("« comp-sql » n'est plus dans le profil");
    }
}
