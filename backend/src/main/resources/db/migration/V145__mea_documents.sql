-- Formularze MEA z portalu Grupy Muszkieterów, wspólne dla wszystkich sklepów (numer MEA jest jeden w sieci).
-- Wiersz powstaje z listy MEA (tytuł, okresy, ścieżka pliku), a formularz pobiera się raz w tle (jak eMEA, które też
-- nie pobiera go drugi raz). fetched_at puste = jeszcze nie pobrany; nieudane próby liczy attempts (ponawiane
-- z przerwą, last_error dla nas). Z pozycji (mea_itm8 = ITM8 promocyjny MEA, którego nie ma w kartotece) rozpoznajemy
-- w przyjęciach PZ towar, który przyszedł na gazetkę.
create table mea_documents (
    id               uuid primary key default gen_random_uuid(),
    number           varchar(10)  not null,
    title            varchar(200),
    promo_from       date,
    promo_to         date,
    reservation_from date,
    reservation_to   date,
    dispatch_1       date,
    dispatch_2       date,
    dispatch_3       date,
    dispatch_4       date,
    file_path        varchar(200) not null,
    line_count       integer      not null default 0,
    created_at       timestamptz  not null default now(),
    fetched_at       timestamptz,
    attempts         integer      not null default 0,
    last_attempt_at  timestamptz,
    last_error       text,
    constraint mea_documents_number_uq unique (number)
);

create index mea_documents_pending on mea_documents (promo_to desc) where fetched_at is null;

create table mea_document_lines (
    id                      uuid primary key default gen_random_uuid(),
    document_id             uuid         not null references mea_documents (id),
    lp                      integer      not null,
    mea_itm8                varchar(13)  not null,
    parallel_itm8           varchar(13),
    ean                     varchar(20),
    name                    varchar(200),
    brand                   varchar(100),
    packs_per_pallet        integer,
    units_per_pack          integer,
    vat_percent             integer,
    purchase_price          numeric(12, 2),
    parallel_purchase_price numeric(12, 2),
    shelf_price             numeric(12, 2),
    parallel_shelf_price    numeric(12, 2),
    margin                  numeric(8, 2),
    parallel_margin         numeric(8, 2),
    guaranteed_days         integer,
    note                    text
);

create index mea_document_lines_document on mea_document_lines (document_id, lp);
-- ITM8 bez zer wiodących, jak w formularzu; przyjęcia mają go z zerami (13 cyfr), więc łączymy po ltrim.
create index mea_document_lines_itm8 on mea_document_lines (mea_itm8);
