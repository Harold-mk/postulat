-- V5 : la correction à la main d'un dossier (itération 8, UC-08). Une correction n'appelle aucun modèle :
-- l'avis du recruteur, rendu avant elle, est périmé jusqu'au prochain. Les puces retirées faute de
-- place gardent désormais leur bloc d'origine dans section et contenu, pour pouvoir y revenir.

ALTER TABLE dossier ADD COLUMN corrige_le TEXT;
ALTER TABLE dossier ADD COLUMN avis_perime INTEGER NOT NULL DEFAULT 0;
