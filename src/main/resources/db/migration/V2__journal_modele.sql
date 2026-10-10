-- V2 : journal des appels au modèle de langage (itération 5).
-- Chaque appel, réussi ou non, est reconstituable (EN-12) et son coût mesuré (EN-5). Ajout seul, comme le
-- journal des événements : un appel passé ne se réécrit pas.

CREATE TABLE appel_modele (
    numero              INTEGER PRIMARY KEY,
    survenu_le          TEXT    NOT NULL,
    tache               TEXT    NOT NULL,
    gamme               TEXT    NOT NULL,
    candidature_id      TEXT    REFERENCES candidature (id),
    modele              TEXT    NOT NULL,
    jetons_entree       INTEGER NOT NULL,
    jetons_sortie       INTEGER NOT NULL,
    jetons_cache_lus    INTEGER NOT NULL,
    jetons_cache_ecrits INTEGER NOT NULL,
    cout_micro_dollars  INTEGER NOT NULL,
    duree_ms            INTEGER NOT NULL,
    issue               TEXT    NOT NULL,
    consignes           TEXT    NOT NULL,
    contenu             TEXT    NOT NULL,
    reponse             TEXT    NOT NULL
);

CREATE INDEX appel_modele_par_candidature ON appel_modele (candidature_id);

CREATE TRIGGER appel_modele_jamais_modifie BEFORE UPDATE ON appel_modele
BEGIN
    SELECT RAISE(ABORT, 'Un appel journalisé ne se modifie pas');
END;

CREATE TRIGGER appel_modele_jamais_supprime BEFORE DELETE ON appel_modele
BEGIN
    SELECT RAISE(ABORT, 'Un appel journalisé ne se supprime pas');
END;
