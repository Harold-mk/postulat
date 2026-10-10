-- V4 : le dossier courant de chaque candidature (itération 8). Une génération le remplace en entier ; une
-- génération en échec n'en laisse rien (lacune L-07), seul le journal des appels garde trace de ce
-- qu'elle a coûté. Chaque phrase est conservée avec son origine (CA-3.7), pour qu'une correction
-- manuelle (UC-08) touche une phrase sans réécrire le reste.

CREATE TABLE dossier (
    candidature_id    TEXT    PRIMARY KEY REFERENCES candidature (id),
    genere_le         TEXT    NOT NULL,
    empreinte_profil  TEXT    NOT NULL,
    passes            INTEGER NOT NULL,
    -- l'en-tête des deux documents, tel que le profil le donnait
    nom               TEXT    NOT NULL,
    titre             TEXT    NOT NULL,
    contact           TEXT    NOT NULL,
    lieu              TEXT    NOT NULL,
    -- la lettre, hors phrases
    destinataire      TEXT    NOT NULL,
    lieu_et_date      TEXT    NOT NULL,
    objet             TEXT    NOT NULL,
    paragraphes       INTEGER NOT NULL,
    -- l'évaluation, et les règles sous lesquelles elle a été faite ; note_ats est nulle quand l'offre
    -- n'a aucune exigence
    note_ats          INTEGER,
    ats_lisible       INTEGER,
    seuil             INTEGER NOT NULL,
    poids_ats         INTEGER NOT NULL,
    revisions_max     INTEGER NOT NULL,
    mots_min          INTEGER NOT NULL,
    mots_max          INTEGER NOT NULL
);

CREATE TABLE dossier_section (
    candidature_id  TEXT    NOT NULL REFERENCES dossier (candidature_id),
    rang            INTEGER NOT NULL,
    titre           TEXT    NOT NULL,
    PRIMARY KEY (candidature_id, rang)
);

-- Chaque phrase, dans l'ordre de lecture. Dans le CV : sa section, son contenu (bloc ou ligne) et son
-- rôle ; une puce porte son rang de pertinence (CA-3.12). Dans la lettre : son paragraphe, 0 pour Vous,
-- 1 pour Moi, 2 pour Nous. Les puces retirées pour tenir sur une page sont gardées à part, document
-- RETIREE : le rapport les liste (CA-3.13).
CREATE TABLE dossier_phrase (
    candidature_id  TEXT    NOT NULL REFERENCES dossier (candidature_id),
    rang            INTEGER NOT NULL,
    document        TEXT    NOT NULL CHECK (document IN ('CV', 'LETTRE', 'RETIREE')),
    section         INTEGER NOT NULL,
    contenu         INTEGER NOT NULL,
    role            TEXT    NOT NULL CHECK (role IN ('INTITULE', 'PRECISION', 'PUCE', 'LIGNE', 'PHRASE')),
    pertinence      INTEGER,
    texte           TEXT    NOT NULL,
    origine         TEXT    NOT NULL CHECK (origine IN ('FAITS', 'OFFRE', 'AUTEUR')),
    PRIMARY KEY (candidature_id, rang)
);

CREATE TABLE dossier_phrase_fait (
    candidature_id  TEXT    NOT NULL,
    rang            INTEGER NOT NULL,
    position        INTEGER NOT NULL,
    fait_id         TEXT    NOT NULL,
    PRIMARY KEY (candidature_id, rang, position),
    FOREIGN KEY (candidature_id, rang) REFERENCES dossier_phrase (candidature_id, rang)
);

-- La note ATS exigence par exigence : retrouvée ou non dans le texte relu du CV (CA-5.1).
CREATE TABLE dossier_exigence (
    candidature_id  TEXT    NOT NULL REFERENCES dossier (candidature_id),
    rang            INTEGER NOT NULL,
    libelle         TEXT    NOT NULL,
    obligatoire     INTEGER NOT NULL,
    presente        INTEGER NOT NULL,
    PRIMARY KEY (candidature_id, rang)
);

-- L'avis du recruteur : une note par critère de la grille (CA-5.2).
CREATE TABLE dossier_critere (
    candidature_id  TEXT    NOT NULL REFERENCES dossier (candidature_id),
    rang            INTEGER NOT NULL,
    critere         TEXT    NOT NULL,
    note            INTEGER NOT NULL CHECK (note BETWEEN 0 AND 5),
    justification   TEXT    NOT NULL,
    PRIMARY KEY (candidature_id, rang),
    UNIQUE (candidature_id, critere)
);

-- Le reste de l'avis et du rapport, liste par liste (CA-5.5).
CREATE TABLE dossier_remarque (
    candidature_id  TEXT    NOT NULL REFERENCES dossier (candidature_id),
    nature          TEXT    NOT NULL CHECK (nature IN ('FORCE', 'FAIBLESSE', 'CORRECTION', 'CORRECTION_ECARTEE', 'AVERTISSEMENT')),
    rang            INTEGER NOT NULL,
    texte           TEXT    NOT NULL,
    PRIMARY KEY (candidature_id, nature, rang)
);
