-- Open Food Facts porzucone: w teście na 234 pozycjach z faktur nie poprawiło podrodzin, a trafność wpisanych do
-- tabeli lekko obniżyło. Wyciąg (dane zewnętrzne, do odtworzenia z pliku OFF) i znacznik w propozycjach znikają;
-- V47 zostaje, bo jest już w historii Flyway na produkcji.

drop table off_products;
drop table off_imports;
alter table data_entry_suggestions drop column off_found;
