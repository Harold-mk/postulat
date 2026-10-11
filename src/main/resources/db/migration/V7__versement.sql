-- V7 : une phrase de l'auteur versée au profil (itération 8, UC-04). Elle reste d'origine AUTEUR — le fait
-- n'existait pas quand elle a été écrite (RG-23) —, mais retient le fait qu'on en a tiré : la régénération
-- suivante ne la reporte plus, elle s'appuie sur lui.

ALTER TABLE dossier_phrase ADD COLUMN versee_dans TEXT;
