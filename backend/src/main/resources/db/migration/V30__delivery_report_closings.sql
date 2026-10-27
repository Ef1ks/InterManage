-- Zamknięty raport przyjęć PZ za okres (dni wg daty bonu). Zamknięcie zapisuje, co poszło w raporcie, więc późniejsze
-- korekty i importy przyjęć z zamkniętego okresu nie zmieniają go, tylko trafiają do następnego raportu jako zmiany.
-- Aktywne zamknięcia jednego sklepu nie nachodzą na siebie (pilnuje serwis). Cofnięcie to znacznik (cancelled_at/by):
-- zmiany przeniesione do cofniętego zamknięcia wracają wtedy do oczekujących.
create table delivery_report_closings (
    id           uuid primary key,
    store_id     uuid        not null references stores (id),
    period_from  date        not null,
    period_to    date        not null,
    closed_by    uuid references users (id),
    closed_at    timestamptz not null,
    cancelled_by uuid references users (id),
    cancelled_at timestamptz,
    check (period_to >= period_from)
);

create index delivery_report_closings_store_idx on delivery_report_closings (store_id, period_from);

-- Przyjęcie w zamkniętym raporcie. origin_closing_id null: przyjęcie z okresu tego zamknięcia, kwoty takie, jakie
-- poszły w raporcie. Inaczej to zmiana przyjęcia z wcześniej zamkniętego okresu (origin_closing_id), która weszła do
-- tego raportu: kwoty są różnicami. Korekty to pełna lista przypiętych korekt w chwili zamknięcia.
create table delivery_report_closing_deliveries (
    id                uuid primary key,
    closing_id        uuid           not null references delivery_report_closings (id),
    delivery_id       uuid           not null references documents (id),
    origin_closing_id uuid references delivery_report_closings (id),
    purchase_value    numeric(16, 4) not null,
    correction_value  numeric(16, 4) not null
);

create index delivery_report_closing_deliveries_closing_idx on delivery_report_closing_deliveries (closing_id);
create index delivery_report_closing_deliveries_delivery_idx on delivery_report_closing_deliveries (delivery_id);

-- Zakup netto po korektach według rejonu nomenklatury (pierwsze dwie cyfry kodu; null = linie bez nomenklatury).
create table delivery_report_closing_regions (
    closing_delivery_id uuid           not null references delivery_report_closing_deliveries (id),
    region_code         varchar(2),
    purchase_value      numeric(16, 4) not null
);

create index delivery_report_closing_regions_idx on delivery_report_closing_regions (closing_delivery_id);

create table delivery_report_closing_corrections (
    closing_delivery_id uuid not null references delivery_report_closing_deliveries (id),
    correction_id       uuid not null references documents (id),
    primary key (closing_delivery_id, correction_id)
);
