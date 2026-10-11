package postulat.suivi;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Fait daté et immuable survenu sur une candidature (CA-6.2, 6.3).
 *
 * <p>Le journal d'événements est la seule source de vérité : l'étape courante n'est jamais stockée,
 * elle est recalculée en rejouant le journal ({@link Candidature#etape()}).
 */
public sealed interface Evenement {

    LocalDate date();

    /** Premier événement de tout journal : entrée de la candidature dans le suivi. */
    record Creation(LocalDate date, Etape etape) implements Evenement {
        public Creation {
            Objects.requireNonNull(date);
            Objects.requireNonNull(etape);
        }
    }

    /** Passage d'une étape à une autre, selon la table de {@link Etape#suivantes()}. */
    record ChangementEtape(LocalDate date, Etape vers) implements Evenement {
        public ChangementEtape {
            Objects.requireNonNull(date);
            Objects.requireNonNull(vers);
        }
    }

    /**
     * Décision de ne pas postuler (UC-06), avec son motif : c'est la matière qui dira plus tard quelles
     * lacunes reviennent le plus dans les offres visées (CA-10.6). Un écartement sans motif n'existe pas.
     */
    record Ecartement(LocalDate date, String motif) implements Evenement {
        public Ecartement {
            Objects.requireNonNull(date);
            if (motif == null || motif.isBlank()) {
                throw new RefusMetier("Écarter une offre demande un motif : il servira à savoir ce qui manque le plus souvent.");
            }
            motif = motif.strip();
        }
    }

    /** Sortie explicite d'une étape terminale (CA-6.5). L'historique antérieur reste intact. */
    record Reouverture(LocalDate date, Etape vers) implements Evenement {
        public Reouverture {
            Objects.requireNonNull(date);
            Objects.requireNonNull(vers);
        }
    }

    /** Relance envoyée par le candidat lui-même, hors application (CA-7.9). */
    record RelanceEffectuee(LocalDate date) implements Evenement {
        public RelanceEffectuee {
            Objects.requireNonNull(date);
        }
    }

    /** Un entretien passé. Plusieurs peuvent se succéder sans changer l'étape (CA-6.13). */
    record EntretienPasse(LocalDate date, TypeEntretien type) implements Evenement {
        public EntretienPasse {
            Objects.requireNonNull(date);
            Objects.requireNonNull(type);
        }
    }

    /**
     * Un entretien fixé pour le jour {@code le}, qui peut être à venir (CA-7.4). {@code date} est le
     * jour où il a été fixé. Un entretien à venir est une échéance, pas une interaction (UC-13 E1).
     */
    record EntretienPlanifie(LocalDate date, TypeEntretien type, LocalDate le) implements Evenement {
        public EntretienPlanifie {
            Objects.requireNonNull(date);
            Objects.requireNonNull(type);
            Objects.requireNonNull(le);
            if (le.isBefore(date)) {
                throw new RefusMetier("Un entretien se planifie pour une date postérieure à sa prise de rendez-vous.");
            }
        }
    }

    /** Échange daté avec l'entreprise hors entretien, avec des notes libres (CA-8.2). */
    record Interaction(LocalDate date, Canal canal, String notes) implements Evenement {
        public Interaction {
            Objects.requireNonNull(date);
            Objects.requireNonNull(canal);
            Objects.requireNonNull(notes);
        }
    }

    /** L'entreprise a promis une réponse pour le jour {@code pour} (CA-7.5). */
    record RetourPromis(LocalDate date, LocalDate pour) implements Evenement {
        public RetourPromis {
            Objects.requireNonNull(date);
            Objects.requireNonNull(pour);
            if (pour.isBefore(date)) {
                throw new RefusMetier("Un retour se promet pour une date postérieure à la promesse.");
            }
        }
    }

    /**
     * Une version du dossier est validée, donc figée (UC-09, RG-15). Elle ne change pas l'étape : c'est
     * le changement d'étape qui l'accompagne, la première fois, qui fait entrer la candidature à « prête ».
     * Rejoué, le journal dit ainsi sans ambiguïté quelle version a été envoyée (CA-6.12).
     */
    record DossierValide(LocalDate date, int version) implements Evenement {
        public DossierValide {
            Objects.requireNonNull(date);
            if (version < 1) {
                throw new IllegalArgumentException("Les versions validées se numérotent à partir de 1 : " + version);
            }
        }
    }

    /** Note libre. Seule saisie permise, avec la réouverture, sur une étape terminale (CA-6.5). */
    record Note(LocalDate date, String texte) implements Evenement {
        public Note {
            Objects.requireNonNull(date);
            Objects.requireNonNull(texte);
            if (texte.isBlank()) {
                throw new RefusMetier("Une note ne peut pas être vide.");
            }
        }
    }
}
