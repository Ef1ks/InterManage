-- Ustawienia podatkowe i składkowe pracownika z Kadr: z nich grafik szacuje wypłatę netto.
-- pit2: oświadczenie PIT-2 (kwota zmniejszająca podatek 300 zł miesięcznie), domyślnie złożone, bo składa je prawie każdy.
-- higher_costs: podwyższone koszty uzyskania przychodu (dojazd z innej miejscowości, 300 zł zamiast 250 zł).
-- youth_relief_waived: rezygnacja z ulgi dla młodych (do 26 lat ulgę bierze się z daty urodzenia).
-- student: uczeń albo student do 26 lat: zlecenie bez składek ZUS i zdrowotnej.
-- voluntary_sickness: dobrowolne ubezpieczenie chorobowe przy zleceniu.
alter table staff_profiles
    add column pit2                boolean not null default true,
    add column higher_costs        boolean not null default false,
    add column youth_relief_waived boolean not null default false,
    add column student             boolean not null default false,
    add column voluntary_sickness  boolean not null default false;
