-- Średnia waga jednej sztuki produktu liczonego na sztuki (warzywa "SZT": ogórek, sałata), wpisana w kalkulatorze
-- cen garmażu. Kartoteka jej nie zna (WAGA = 1 oznacza tam 1 sztukę), a bez niej taki składnik nie wlicza się do
-- wagi surowców przepisu. Zapamiętana dla produktu w sklepie, więc kolejne kalkulacje jej nie pytają; import
-- raportu jej nie rusza.
alter table products
    add column piece_weight        numeric(10, 3) check (piece_weight > 0),
    add column piece_weight_set_by uuid references users (id) on delete set null,
    add column piece_weight_set_at timestamptz;
