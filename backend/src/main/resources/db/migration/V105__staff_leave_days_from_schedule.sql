-- Dni urlopu liczą się z grafiku (dni ze zmianą, bez ułożenia pon.-pt. bez świąt) i przeliczają się przy jego zmianie,
-- więc urlop, którego dni grafik przestawił na wolne (W5), może mieć 0 dni.
alter table staff_leaves drop constraint staff_leaves_days_check;
alter table staff_leaves add constraint staff_leaves_days_check check (days >= 0);
