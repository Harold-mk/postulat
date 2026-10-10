-- V1 : suivi des candidatures (itération 2).
-- Une migration appliquée ne se modifie jamais : toute évolution est une migration suivante (ADR-0004).

-- L'étape est dénormalisée pour les requêtes inter-candidatures. Un seul chemin de code l'écrit, dans la
-- même transaction que l'événement qui la produit (ADR-0005, RG-10). Le numéro est une poignée courte
-- pour la ligne de commande ; l'identité de la candidature reste son UUID.
CREATE TABLE candidature (
    numero      INTEGER PRIMARY KEY,
    id          TEXT    NOT NULL UNIQUE,
    entreprise  TEXT    NOT NULL,
    poste       TEXT    NOT NULL,
    etape       TEXT    NOT NULL
);

-- Journal en ajout seul (RG-11) : une ligne par événement, les colonnes sans objet pour un type restent
-- nulles. prevu_le porte la date d'un entretien planifié ou d'un retour promis.
CREATE TABLE evenement (
    candidature_id  TEXT    NOT NULL REFERENCES candidature (id),
    rang            INTEGER NOT NULL,
    type            TEXT    NOT NULL,
    survenu_le      TEXT    NOT NULL,
    etape           TEXT,
    entretien       TEXT,
    canal           TEXT,
    prevu_le        TEXT,
    texte           TEXT,
    PRIMARY KEY (candidature_id, rang)
);

CREATE INDEX evenement_par_date ON evenement (candidature_id, survenu_le);

CREATE TRIGGER evenement_jamais_modifie BEFORE UPDATE ON evenement
BEGIN
    SELECT RAISE(ABORT, 'RG-11 : un événement ne se modifie pas, une correction est un nouvel événement');
END;

CREATE TRIGGER evenement_jamais_supprime BEFORE DELETE ON evenement
BEGIN
    SELECT RAISE(ABORT, 'RG-11 : un événement ne se supprime pas');
END;

-- Contacts : des personnes, pas des événements (CA-8.1).
CREATE TABLE contact (
    candidature_id  TEXT    NOT NULL REFERENCES candidature (id),
    rang            INTEGER NOT NULL,
    nom             TEXT    NOT NULL,
    role            TEXT    NOT NULL,
    coordonnees     TEXT    NOT NULL,
    PRIMARY KEY (candidature_id, rang)
);
