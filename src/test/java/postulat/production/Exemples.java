package postulat.production;

import java.util.List;

/** Un profil fictif complet, partagé par les tests du domaine. */
final class Exemples {

    private Exemples() {}

    static Realisation dashboard() {
        return new Realisation("exp-acme-dashboard", "Suivre les ventes chaque semaine",
                "Concevoir un tableau de bord Power BI", "5 KPI, utilisé par 12 commerciaux",
                List.of("Power BI", "SQL"), "");
    }

    static Experience stage() {
        return new Experience("exp-acme", "Stagiaire data analyst", "Acme", "2025-01 à 2025-06", "6 mois",
                "Lyon", "", List.of(dashboard()));
    }

    static Formation master() {
        return new Formation("form-master", "Master data science", "Université Lumière", "2024 à 2026",
                "spécialité apprentissage automatique", "", List.of());
    }

    static Competence sql() {
        return new Competence("comp-sql", "SQL", "avancé", "toujours citer avec un exemple, 2 lignes au plus");
    }

    static Identite identite() {
        return new Identite("Camille Martin", "Étudiante en master data", "camille@exemple.test", "Lyon");
    }

    static Profil complet() {
        return new Profil(identite(), List.of(stage()), List.of(master()), List.of(sql()));
    }
}
