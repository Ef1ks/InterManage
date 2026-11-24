-- Dostawcy (Roshen) wpisują w numery faktur w KSeF litery cyrylicy wyglądające jak łacińskie („FAN/ZА409”), a program
-- sklepu i przyjęcia PZ mają numer łaciński, więc faktury nie łączyły się z przyjęciami, a makro zamykania ich nie
-- znajdowało. Aplikacja zamienia je odtąd przy zapisie (LatinText, ta sama lista liter), a tu numery już zapisane.
-- Oryginał zostaje w XML faktury.
update ksef_invoices
set invoice_number = translate(invoice_number, 'АВЕЅІЈКМНОРСТХУаеѕіјорсхуԁһӏΑΒΕΖΗΙΚΜΝΟΡΤΥΧον', 'ABESIJKMHOPCTXYaesijopcxydhlABEZHIKMNOPTYXov')
where invoice_number <> translate(invoice_number, 'АВЕЅІЈКМНОРСТХУаеѕіјорсхуԁһӏΑΒΕΖΗΙΚΜΝΟΡΤΥΧον', 'ABESIJKMHOPCTXYaesijopcxydhlABEZHIKMNOPTYXov');

update ksef_invoice_corrected
set invoice_number = translate(invoice_number, 'АВЕЅІЈКМНОРСТХУаеѕіјорсхуԁһӏΑΒΕΖΗΙΚΜΝΟΡΤΥΧον', 'ABESIJKMHOPCTXYaesijopcxydhlABEZHIKMNOPTYXov')
where invoice_number <> translate(invoice_number, 'АВЕЅІЈКМНОРСТХУаеѕіјорсхуԁһӏΑΒΕΖΗΙΚΜΝΟΡΤΥΧον', 'ABESIJKMHOPCTXYaesijopcxydhlABEZHIKMNOPTYXov');

update data_entry_xml_exports
set document_number = translate(document_number, 'АВЕЅІЈКМНОРСТХУаеѕіјорсхуԁһӏΑΒΕΖΗΙΚΜΝΟΡΤΥΧον', 'ABESIJKMHOPCTXYaesijopcxydhlABEZHIKMNOPTYXov')
where document_number <> translate(document_number, 'АВЕЅІЈКМНОРСТХУаеѕіјорсхуԁһӏΑΒΕΖΗΙΚΜΝΟΡΤΥΧον', 'ABESIJKMHOPCTXYaesijopcxydhlABEZHIKMNOPTYXov');

update data_entry_invoice_closings c
set document_number = translate(c.document_number, 'АВЕЅІЈКМНОРСТХУаеѕіјорсхуԁһӏΑΒΕΖΗΙΚΜΝΟΡΤΥΧον', 'ABESIJKMHOPCTXYaesijopcxydhlABEZHIKMNOPTYXov')
where c.document_number <> translate(c.document_number, 'АВЕЅІЈКМНОРСТХУаеѕіјорсхуԁһӏΑΒΕΖΗΙΚΜΝΟΡΤΥΧον', 'ABESIJKMHOPCTXYaesijopcxydhlABEZHIKMNOPTYXov')
  and not exists (select 1 from data_entry_invoice_closings o
                  where o.store_id = c.store_id and o.supplier_code = c.supplier_code
                    and o.document_number = translate(c.document_number, 'АВЕЅІЈКМНОРСТХУаеѕіјорсхуԁһӏΑΒΕΖΗΙΚΜΝΟΡΤΥΧον', 'ABESIJKMHOPCTXYaesijopcxydhlABEZHIKMNOPTYXov'));
