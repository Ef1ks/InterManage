-- Przy korekcie: czy zmienia ilości (zwrot, brak, zamiana artykułu), czy tylko kwoty (obniżka ceny, rabat), odczytane
-- z XML (eksport paczek, pobranie faktury). Puste = XML jeszcze nieodczytany albo nie korekta.
alter table ksef_invoices
    add column quantity_change boolean;
