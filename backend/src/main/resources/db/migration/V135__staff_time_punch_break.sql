-- Wyjście na przerwę: pracownik wybiera „Przerwa” na kiosku zaraz po odbiciu wyjścia. Czas do kolejnego wejścia jest
-- wtedy przerwą liczoną według przerw działu, a nie brakiem.
alter table staff_time_punches add column break_start boolean not null default false;
alter table staff_time_punches add column break_marked_at timestamptz;
