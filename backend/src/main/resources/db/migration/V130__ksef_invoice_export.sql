-- Eksport paczek faktur z KSeF (POST /invoices/exports): XML wszystkich faktur firmy przychodzi paczkami do 10 tys.,
-- zamiast pojedynczo (64 na godzinę). Z XML zapisujemy adres dostawy z Podmiotów 3, po którym sklep rozpoznaje swoje
-- faktury bez IDWew (np. faktury bazy SCA PR), i faktury korygowane każdej korekty.

-- Adresy z Podmiotów 3 faktury, znormalizowane (małe litery bez polskich znaków, bez kodów pocztowych, słowa
-- rozdzielone spacją, spacja na początku i końcu), do porównania z ulicą i numerem sklepu. Puste = bez Podmiotu 3
-- z adresem albo jeszcze nieodczytane (address_read_at).
alter table ksef_invoices
    add column delivery_address varchar(2000),
    add column address_read_at  timestamptz;

-- Postęp eksportu firmy: kursor po dacie trwałego zapisu (jak sync_cursor) i wynik ostatniego przebiegu.
alter table ksef_sync_states
    add column export_cursor  timestamptz,
    add column exported_at    timestamptz,
    add column export_ok      boolean,
    add column export_message varchar(1000);
