-- Płatna przerwa przy umowie B2B: Kodeks pracy jej nie daje, ale umowa B2B często przewiduje płatne dni wolne w roku.
-- Liczba dni z umowy; puste = umowa ich nie przewiduje. Wpisy przerwy idą do staff_leaves jako kind = 'B2B_PAID'.
alter table staff_contracts add column paid_leave_days integer;
alter table staff_contracts add constraint staff_contracts_paid_leave_check
    check (paid_leave_days is null or (kind = 'B2B' and paid_leave_days between 0 and 366));

alter table staff_contract_revisions add column paid_leave_days integer;
