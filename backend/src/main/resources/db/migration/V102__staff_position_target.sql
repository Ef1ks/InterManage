-- Docelowa obsada stanowiska obok najmniejszej, osobno w każdym sklepie. Obie tylko podpowiadają w grafiku.
alter table staff_position_minimums alter column min_count drop not null;
alter table staff_position_minimums add column target_count integer;

alter table staff_position_minimums drop constraint staff_position_minimums_count_check;
alter table staff_position_minimums add constraint staff_position_minimums_count_check
    check ((min_count is null or min_count > 0)
        and (target_count is null or target_count > 0)
        and (min_count is not null or target_count is not null)
        and (min_count is null or target_count is null or target_count >= min_count));
