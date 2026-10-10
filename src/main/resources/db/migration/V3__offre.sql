-- V3 : l'offre d'une candidature, son analyse et son rapprochement avec le profil (itération 6).
-- L'offre est conservée même écartée (RG-17), son texte brut tel quel pour que l'analyse reste
-- rejouable (CA-2.5). Analyse et rapprochement ne sont pas un journal : ils se refont quand le modèle
-- redevient disponible ou que le profil change. Les dossiers en garderont un instantané (itération 8).

CREATE TABLE offre (
    candidature_id  TEXT    PRIMARY KEY REFERENCES candidature (id),
    source          TEXT    NOT NULL,
    texte_brut      TEXT    NOT NULL,
    recue_le        TEXT    NOT NULL,
    analysee_le     TEXT,
    poste           TEXT,
    entreprise      TEXT,
    lieu            TEXT,
    contrat         TEXT,
    debut           TEXT,
    duree           TEXT,
    niveau          TEXT,
    rapprochee_le   TEXT,
    note            INTEGER,
    justification   TEXT
);

CREATE TABLE offre_mission (
    candidature_id  TEXT    NOT NULL REFERENCES offre (candidature_id),
    rang            INTEGER NOT NULL,
    texte           TEXT    NOT NULL,
    PRIMARY KEY (candidature_id, rang)
);

-- justification est nulle tant que le rapprochement n'a pas eu lieu ; une exigence sans fait rattaché
-- est alors une lacune.
CREATE TABLE exigence (
    candidature_id  TEXT    NOT NULL REFERENCES offre (candidature_id),
    rang            INTEGER NOT NULL,
    libelle         TEXT    NOT NULL,
    obligatoire     INTEGER NOT NULL,
    justification   TEXT,
    PRIMARY KEY (candidature_id, rang)
);

CREATE TABLE exigence_fait (
    candidature_id  TEXT    NOT NULL,
    rang            INTEGER NOT NULL,
    fait_id         TEXT    NOT NULL,
    PRIMARY KEY (candidature_id, rang, fait_id),
    FOREIGN KEY (candidature_id, rang) REFERENCES exigence (candidature_id, rang)
);
