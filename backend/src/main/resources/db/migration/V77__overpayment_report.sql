-- Raport przepłaconych artykułów wyjaśnia przyjęcia z dostawcą tak samo jak zakup bazowy poza bazą, ale to osobna
-- sprawa: to samo przyjęcie może być wyjaśnione w jednym raporcie, a czekać w drugim. Statusy są w tej samej tabeli,
-- a report mówi, którego raportu dotyczą.
alter table price_check_reviews add column report varchar(20) not null default 'BASE_PURCHASE';
alter table price_check_reviews alter column report drop default;
