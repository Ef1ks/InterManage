-- Godziny na umowie zlecenia u tego samego pracodawcy w osobnym wierszu grafiku, pod umową o pracę. Pracownik może mieć
-- tego samego dnia zmianę na umowie o pracę i drugą na zleceniu, ale każda umowa najwyżej jedną. Godziny zlecenia nie
-- liczą się do wymiaru umowy o pracę, a grafik ostrzega, gdy są tego samego dnia (art. 22 § 1¹, art. 151 § 1 KP) i gdy
-- razem łamią odpoczynek dobowy (art. 132). Dawne wpisy to umowa o pracę.
alter table staff_schedule_entries add column civil boolean not null default false;
drop index staff_schedule_entries_uq;
create unique index staff_schedule_entries_uq on staff_schedule_entries (employee_id, work_date, civil)
    where deleted_at is null;

alter table staff_schedule_version_entries add column civil boolean not null default false;
alter table staff_schedule_version_entries drop constraint staff_schedule_version_entries_uq;
alter table staff_schedule_version_entries
    add constraint staff_schedule_version_entries_uq unique (version_id, employee_id, work_date, civil);
