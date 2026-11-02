-- Program sklepu wczytuje tylko pliki CTRLPRIX z końcówką .traite0 (.Traite1 nadaje sam po wczytaniu), więc pliki
-- z historii zapisane jako .Traite1 dostają końcówkę, z którą „Zapisz ponownie” trafi do programu.
update data_entry_price_control_files
set file_name = left(file_name, length(file_name) - length('.Traite1')) || '.traite0'
where file_name like '%.Traite1';
