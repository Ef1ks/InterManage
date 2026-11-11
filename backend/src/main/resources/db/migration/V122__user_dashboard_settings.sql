-- Ustawienia pulpitu konta (aplikacja w przeglądarce i mobilna). sales_chart_metric: co pokazuje wykres wyników
-- sklepu (DashboardChartMetric: QUANTITY ilość, SALES sprzedaż brutto, MARGIN marża zł). Jeden wiersz na konto,
-- zmiana go nadpisuje, bo to sam wygląd.
create table user_dashboard_settings (
    id                 uuid primary key default gen_random_uuid(),
    user_id            uuid        not null references users (id),
    sales_chart_metric varchar(20) not null default 'SALES'
        constraint user_dashboard_settings_metric_ck check (sales_chart_metric in ('QUANTITY', 'SALES', 'MARGIN')),
    updated_at         timestamptz not null default now(),
    constraint user_dashboard_settings_user_uq unique (user_id)
);
