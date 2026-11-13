-- Faktura korygowana sprzed KSeF (bez numeru KSeF) łączy się z korektą po NIP-ie sprzedawcy i numerze. Bez indeksu
-- filtr "Moje" przeglądał całą tabelę faktur przy każdym takim odwołaniu (ok. 8,6 tys. na produkcji): lista nie
-- ładowała się w kilka minut.
create index ksef_invoices_nip_seller_number on ksef_invoices (nip, seller_nip, upper(invoice_number));

-- Wersja reguły, którą policzono quantity_change (CorrectionKind.RULE): po zmianie reguły korekty z zapisanym XML
-- liczą się od nowa w tle. Puste = jeszcze nie liczone.
alter table ksef_invoices
    add column quantity_change_rule smallint;
