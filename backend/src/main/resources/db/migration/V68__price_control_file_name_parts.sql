-- Program sklepu dzieli nazwę pliku .traite0 po „_”, więc hasło dostawcy i login mogą mieć tylko litery i cyfry
-- (ProtoFile.namePart): „DR LEKS” trafiał do nazwy jako DR_LEKS, login pdv_10023 z podkreślnikiem. Pliki z historii
-- dostają nazwę złożoną tak jak nowe, żeby „Zapisz ponownie” dawało plik, który program wczyta.
update data_entry_price_control_files f
set file_name = left(f.file_name, length('CTRLPRIX_20261102_133100_'))
        || regexp_replace(f.supplier_keyword, '[^A-Za-z0-9]', '', 'g') || '_'
        || coalesce(nullif(regexp_replace(u.username, '[^A-Za-z0-9]', '', 'g'), ''), 'user') || '.traite0'
from data_entry_price_control_files source
         left join users u on u.id = source.created_by
where source.id = f.id
  and f.file_name like 'CTRLPRIX\_%';
