-- Klucze API portalu Grupy Muszkieterów (Moje konto -> API) dla sklepu: KEY do logowania i opcjonalny SECRET
-- (potrzebny dopiero do wysyłki zamówienia MEA). Oba szyfrowane APP_SECRET_KEY, jak hasło do klucza KSeF. Jeden
-- wiersz na sklep, nadpisywany. shopID do API bierzemy z numeru punktu sprzedaży sklepu.
create table mea_api_credentials (
    id         uuid primary key default gen_random_uuid(),
    store_id   uuid        not null references stores (id),
    api_key    text        not null,
    api_secret text,
    updated_by uuid references users (id) on delete set null,
    updated_at timestamptz not null default now(),
    constraint mea_api_credentials_store_uq unique (store_id)
);
