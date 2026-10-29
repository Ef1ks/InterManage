-- Gratis ma rodzaj: za handel / ekspozycję (TRADE) albo pokrycie strat (LOSSES); dotychczasowe były za handel.
-- report_month to miesiąc, którego gratis dotyczy (pierwszy dzień miesiąca), wybrany ręcznie; pusty = miesiąc daty
-- dokumentu. Raport przyjętych gratisów liczy go po tym miesiącu.
alter table document_gratis add column kind varchar(20) not null default 'TRADE';
alter table document_gratis alter column kind drop default;
alter table document_gratis add column report_month date;
alter table document_gratis add constraint document_gratis_report_month_first_day
    check (report_month is null or extract(day from report_month) = 1);
