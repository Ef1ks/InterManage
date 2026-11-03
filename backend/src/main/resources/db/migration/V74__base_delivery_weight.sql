-- Ciezar_REEL: faktyczna waga towaru na wagę (warzywa, owoce w kg). Gdy jest > 0, to ona jest ilością przyjętą,
-- a wartość linii (Kwota_HT) = waga × C_Cesja, nie kartony × PAK × C_Cesja. Linie wgrane wcześniej jej nie mają.
alter table base_delivery_lines add column weight numeric(12, 3);
