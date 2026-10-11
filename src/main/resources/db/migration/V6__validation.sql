-- V6 : la validation du dossier (itération 8, UC-09). Chaque table du dossier prend une version : 0 pour
-- le brouillon courant, que la génération remplace et que la correction modifie ; 1, 2… pour les
-- versions validées, figées. Une version validée ne se modifie ni ne se supprime : des triggers le
-- refusent, comme pour le journal (RG-15). Elle garde l'instantané des faits qu'elle cite (RG-16),
-- le répertoire où ses fichiers ont été écrits (CA-6.15) et le modèle qui l'a produite (CA-6.8).
--
-- SQLite ne sait pas changer une clé primaire : chaque table est recréée, ses lignes recopiées en
-- version 0. Les clés étrangères ne sont vérifiées qu'à la fin de la transaction.

PRAGMA defer_foreign_keys = ON;

CREATE TABLE dossier_v6 (
    candidature_id    TEXT    NOT NULL REFERENCES candidature (id),
    version           INTEGER NOT NULL CHECK (version >= 0),
    genere_le         TEXT    NOT NULL,
    empreinte_profil  TEXT    NOT NULL,
    modele            TEXT    NOT NULL DEFAULT '',
    passes            INTEGER NOT NULL,
    nom               TEXT    NOT NULL,
    titre             TEXT    NOT NULL,
    contact           TEXT    NOT NULL,
    lieu              TEXT    NOT NULL,
    destinataire      TEXT    NOT NULL,
    lieu_et_date      TEXT    NOT NULL,
    objet             TEXT    NOT NULL,
    paragraphes       INTEGER NOT NULL,
    note_ats          INTEGER,
    ats_lisible       INTEGER,
    seuil             INTEGER NOT NULL,
    poids_ats         INTEGER NOT NULL,
    revisions_max     INTEGER NOT NULL,
    mots_min          INTEGER NOT NULL,
    mots_max          INTEGER NOT NULL,
    corrige_le        TEXT,
    avis_perime       INTEGER NOT NULL DEFAULT 0,
    -- une version validée a sa date et son répertoire ; le brouillon n'a ni l'un ni l'autre
    valide_le         TEXT,
    repertoire        TEXT,
    PRIMARY KEY (candidature_id, version),
    CHECK ((version = 0) = (valide_le IS NULL))
);
INSERT INTO dossier_v6 (candidature_id, version, genere_le, empreinte_profil, passes, nom, titre, contact, lieu, destinataire,
        lieu_et_date, objet, paragraphes, note_ats, ats_lisible, seuil, poids_ats, revisions_max, mots_min, mots_max, corrige_le,
        avis_perime)
    SELECT candidature_id, 0, genere_le, empreinte_profil, passes, nom, titre, contact, lieu, destinataire, lieu_et_date, objet,
        paragraphes, note_ats, ats_lisible, seuil, poids_ats, revisions_max, mots_min, mots_max, corrige_le, avis_perime
    FROM dossier;

CREATE TABLE dossier_section_v6 (
    candidature_id  TEXT    NOT NULL,
    version         INTEGER NOT NULL,
    rang            INTEGER NOT NULL,
    titre           TEXT    NOT NULL,
    PRIMARY KEY (candidature_id, version, rang),
    FOREIGN KEY (candidature_id, version) REFERENCES dossier_v6 (candidature_id, version)
);
INSERT INTO dossier_section_v6 SELECT candidature_id, 0, rang, titre FROM dossier_section;

CREATE TABLE dossier_phrase_v6 (
    candidature_id  TEXT    NOT NULL,
    version         INTEGER NOT NULL,
    rang            INTEGER NOT NULL,
    document        TEXT    NOT NULL CHECK (document IN ('CV', 'LETTRE', 'RETIREE')),
    section         INTEGER NOT NULL,
    contenu         INTEGER NOT NULL,
    role            TEXT    NOT NULL CHECK (role IN ('INTITULE', 'PRECISION', 'PUCE', 'LIGNE', 'PHRASE')),
    pertinence      INTEGER,
    texte           TEXT    NOT NULL,
    origine         TEXT    NOT NULL CHECK (origine IN ('FAITS', 'OFFRE', 'AUTEUR')),
    PRIMARY KEY (candidature_id, version, rang),
    FOREIGN KEY (candidature_id, version) REFERENCES dossier_v6 (candidature_id, version)
);
INSERT INTO dossier_phrase_v6
    SELECT candidature_id, 0, rang, document, section, contenu, role, pertinence, texte, origine FROM dossier_phrase;

CREATE TABLE dossier_phrase_fait_v6 (
    candidature_id  TEXT    NOT NULL,
    version         INTEGER NOT NULL,
    rang            INTEGER NOT NULL,
    position        INTEGER NOT NULL,
    fait_id         TEXT    NOT NULL,
    PRIMARY KEY (candidature_id, version, rang, position),
    FOREIGN KEY (candidature_id, version, rang) REFERENCES dossier_phrase_v6 (candidature_id, version, rang)
);
INSERT INTO dossier_phrase_fait_v6 SELECT candidature_id, 0, rang, position, fait_id FROM dossier_phrase_fait;

CREATE TABLE dossier_exigence_v6 (
    candidature_id  TEXT    NOT NULL,
    version         INTEGER NOT NULL,
    rang            INTEGER NOT NULL,
    libelle         TEXT    NOT NULL,
    obligatoire     INTEGER NOT NULL,
    presente        INTEGER NOT NULL,
    PRIMARY KEY (candidature_id, version, rang),
    FOREIGN KEY (candidature_id, version) REFERENCES dossier_v6 (candidature_id, version)
);
INSERT INTO dossier_exigence_v6 SELECT candidature_id, 0, rang, libelle, obligatoire, presente FROM dossier_exigence;

CREATE TABLE dossier_critere_v6 (
    candidature_id  TEXT    NOT NULL,
    version         INTEGER NOT NULL,
    rang            INTEGER NOT NULL,
    critere         TEXT    NOT NULL,
    note            INTEGER NOT NULL CHECK (note BETWEEN 0 AND 5),
    justification   TEXT    NOT NULL,
    PRIMARY KEY (candidature_id, version, rang),
    UNIQUE (candidature_id, version, critere),
    FOREIGN KEY (candidature_id, version) REFERENCES dossier_v6 (candidature_id, version)
);
INSERT INTO dossier_critere_v6 SELECT candidature_id, 0, rang, critere, note, justification FROM dossier_critere;

CREATE TABLE dossier_remarque_v6 (
    candidature_id  TEXT    NOT NULL,
    version         INTEGER NOT NULL,
    nature          TEXT    NOT NULL CHECK (nature IN ('FORCE', 'FAIBLESSE', 'CORRECTION', 'CORRECTION_ECARTEE', 'AVERTISSEMENT')),
    rang            INTEGER NOT NULL,
    texte           TEXT    NOT NULL,
    PRIMARY KEY (candidature_id, version, nature, rang),
    FOREIGN KEY (candidature_id, version) REFERENCES dossier_v6 (candidature_id, version)
);
INSERT INTO dossier_remarque_v6 SELECT candidature_id, 0, nature, rang, texte FROM dossier_remarque;

-- L'instantané de chaque fait cité par une version validée, dans l'ordre de première citation (RG-16).
CREATE TABLE dossier_fait_v6 (
    candidature_id  TEXT    NOT NULL,
    version         INTEGER NOT NULL CHECK (version > 0),
    rang            INTEGER NOT NULL,
    fait_id         TEXT    NOT NULL,
    contenu         TEXT    NOT NULL,
    PRIMARY KEY (candidature_id, version, rang),
    UNIQUE (candidature_id, version, fait_id),
    FOREIGN KEY (candidature_id, version) REFERENCES dossier_v6 (candidature_id, version)
);

DROP TABLE dossier_phrase_fait;
DROP TABLE dossier_phrase;
DROP TABLE dossier_section;
DROP TABLE dossier_exigence;
DROP TABLE dossier_critere;
DROP TABLE dossier_remarque;
DROP TABLE dossier;

ALTER TABLE dossier_v6 RENAME TO dossier;
ALTER TABLE dossier_section_v6 RENAME TO dossier_section;
ALTER TABLE dossier_phrase_v6 RENAME TO dossier_phrase;
ALTER TABLE dossier_phrase_fait_v6 RENAME TO dossier_phrase_fait;
ALTER TABLE dossier_exigence_v6 RENAME TO dossier_exigence;
ALTER TABLE dossier_critere_v6 RENAME TO dossier_critere;
ALTER TABLE dossier_remarque_v6 RENAME TO dossier_remarque;
ALTER TABLE dossier_fait_v6 RENAME TO dossier_fait;

-- RG-15 : une version validée est immuable.
CREATE TRIGGER dossier_valide_jamais_modifie BEFORE UPDATE ON dossier WHEN OLD.version > 0
BEGIN
    SELECT RAISE(ABORT, 'Un dossier validé ne se modifie pas');
END;
CREATE TRIGGER dossier_valide_jamais_supprime BEFORE DELETE ON dossier WHEN OLD.version > 0
BEGIN
    SELECT RAISE(ABORT, 'Un dossier validé ne se supprime pas');
END;
CREATE TRIGGER dossier_section_validee_jamais_modifiee BEFORE UPDATE ON dossier_section WHEN OLD.version > 0
BEGIN
    SELECT RAISE(ABORT, 'Un dossier validé ne se modifie pas');
END;
CREATE TRIGGER dossier_section_validee_jamais_supprimee BEFORE DELETE ON dossier_section WHEN OLD.version > 0
BEGIN
    SELECT RAISE(ABORT, 'Un dossier validé ne se supprime pas');
END;
CREATE TRIGGER dossier_phrase_validee_jamais_modifiee BEFORE UPDATE ON dossier_phrase WHEN OLD.version > 0
BEGIN
    SELECT RAISE(ABORT, 'Un dossier validé ne se modifie pas');
END;
CREATE TRIGGER dossier_phrase_validee_jamais_supprimee BEFORE DELETE ON dossier_phrase WHEN OLD.version > 0
BEGIN
    SELECT RAISE(ABORT, 'Un dossier validé ne se supprime pas');
END;
CREATE TRIGGER dossier_phrase_fait_valide_jamais_modifie BEFORE UPDATE ON dossier_phrase_fait WHEN OLD.version > 0
BEGIN
    SELECT RAISE(ABORT, 'Un dossier validé ne se modifie pas');
END;
CREATE TRIGGER dossier_phrase_fait_valide_jamais_supprime BEFORE DELETE ON dossier_phrase_fait WHEN OLD.version > 0
BEGIN
    SELECT RAISE(ABORT, 'Un dossier validé ne se supprime pas');
END;
CREATE TRIGGER dossier_exigence_validee_jamais_modifiee BEFORE UPDATE ON dossier_exigence WHEN OLD.version > 0
BEGIN
    SELECT RAISE(ABORT, 'Un dossier validé ne se modifie pas');
END;
CREATE TRIGGER dossier_exigence_validee_jamais_supprimee BEFORE DELETE ON dossier_exigence WHEN OLD.version > 0
BEGIN
    SELECT RAISE(ABORT, 'Un dossier validé ne se supprime pas');
END;
CREATE TRIGGER dossier_critere_valide_jamais_modifie BEFORE UPDATE ON dossier_critere WHEN OLD.version > 0
BEGIN
    SELECT RAISE(ABORT, 'Un dossier validé ne se modifie pas');
END;
CREATE TRIGGER dossier_critere_valide_jamais_supprime BEFORE DELETE ON dossier_critere WHEN OLD.version > 0
BEGIN
    SELECT RAISE(ABORT, 'Un dossier validé ne se supprime pas');
END;
CREATE TRIGGER dossier_remarque_validee_jamais_modifiee BEFORE UPDATE ON dossier_remarque WHEN OLD.version > 0
BEGIN
    SELECT RAISE(ABORT, 'Un dossier validé ne se modifie pas');
END;
CREATE TRIGGER dossier_remarque_validee_jamais_supprimee BEFORE DELETE ON dossier_remarque WHEN OLD.version > 0
BEGIN
    SELECT RAISE(ABORT, 'Un dossier validé ne se supprime pas');
END;
CREATE TRIGGER dossier_fait_jamais_modifie BEFORE UPDATE ON dossier_fait
BEGIN
    SELECT RAISE(ABORT, 'Un instantané de fait ne se modifie pas');
END;
CREATE TRIGGER dossier_fait_jamais_supprime BEFORE DELETE ON dossier_fait
BEGIN
    SELECT RAISE(ABORT, 'Un instantané de fait ne se supprime pas');
END;
