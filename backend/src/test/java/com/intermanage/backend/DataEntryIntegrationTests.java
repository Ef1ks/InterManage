package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.intermanage.backend.auth.security.JwtService;
import com.intermanage.backend.dataentry.component.SuggestionModel;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** Wprowadzanie danych: faktura z KSeF w tabeli, propozycje dla nowych artykułów (model podmieniony), eksport. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        "app.ksef.sync-enabled=false"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class DataEntryIntegrationTests {

    private static final String SELLER_NIP = "1111111111";
    /** Producent z pięcioma serami w jednej podrodzinie (prefiks EAN 590123456). */
    private static final String CHEESE_EAN = "5901234560009";
    private static final String CHIPS_EAN = "5907777000001";
    private static final String MARSHMALLOW_EAN = "5908888000001";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @MockitoBean
    SuggestionModel model;

    private Cookie owner;
    private String storeId;
    private String invoiceId;

    @BeforeEach
    void setUp() throws Exception {
        owner = login();
        storeId = createStore();
        String nip = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        jdbc.update("""
                insert into ksef_connections (store_id, nip, certificate_pem, private_key_pem, password_encrypted,
                    certificate_subject, certificate_valid_to, updated_at)
                values (?::uuid, ?, 'x', 'x', 'x', 'TEST', now() + interval '1 year', now())""", storeId, nip);
        invoiceId = jdbc.queryForObject("""
                insert into ksef_invoices (id, ksef_number, invoice_number, issue_date, invoicing_date,
                    permanent_storage_date, seller_nip, seller_name, net_amount, gross_amount, vat_amount, currency,
                    invoice_type, has_attachment, created_at, updated_at, nip)
                values (gen_random_uuid(), ?, 'FV/77/2026', date '2026-10-30', now(), now(), ?, 'SERY I CHIPSY SA',
                    100, 110, 10, 'PLN', 'Vat', false, now(), now(), ?) returning id""", String.class,
                SELLER_NIP + "-20261030-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(), SELLER_NIP,
                nip);
        jdbc.update("insert into ksef_invoice_xml (invoice_id, content, downloaded_at) values (?::uuid, ?, now())",
                UUID.fromString(invoiceId), invoiceXml().getBytes(StandardCharsets.UTF_8));

        String productImport = jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, total_rows, created_count, updated_count,
                    unchanged_count, missing_count)
                values (?::uuid, 'test.xlsx', 10, 10, 0, 0, 0) returning id""", String.class, storeId);
        // Znany artykuł po bieżącym EAN-ie i drugi po dawnym EAN-ie (para w product_eans).
        insertProduct(productImport, "0000000000101", "5900000000012", "MLEKO 3,2% 1L", "LAKA", "L", "1", "3.49",
                "25 42 01");
        String yogurt = insertProduct(productImport, "0000000000102", "5900000000036", "JOGURT NATURALNY 400G",
                "LAKA", "kg", "0.4", "4.99", "25 44 01");
        jdbc.update("""
                insert into product_eans (product_id, ean, first_seen_import_id, last_seen_import_id)
                values (?::uuid, '5900000000029', ?::uuid, ?::uuid)""", yogurt, productImport, productImport);
        // Jogurt bez CENA_CAD: cena z kartoteki zostaje, ale bez znacznika cadencier.
        jdbc.update("update products set cadencier_price = null where id = ?::uuid", yogurt);
        // EAN tylko w polu Indeks i tylko w dodatkowym opisie pozycji.
        insertProduct(productImport, "0000000000103", "5900000000043", "MASLO EKSTRA 200G", "LAKA", "kg", "0.2",
                "7.99", "25 42 01");
        insertProduct(productImport, "0000000000104", "5900000000050", "KEFIR 400G", "LAKA", "kg", "0.4", "3.49",
                "25 44 01");
        // Producent serów: pięć artykułów w jednej podrodzinie, nazwy podobne do sera z faktury.
        String[] cheeses = {"SER LILIPUT PLASTRY 150G", "SER LILIPUT KLINEK 300G", "SER GOUDA PLASTRY 150G",
                "SER EDAMSKI PLASTRY 150G", "SER LILIPUT WEDZONY 200G"};
        for (int i = 0; i < cheeses.length; i++) {
            insertProduct(productImport, "000000000020" + i, "590123456000" + (i + 1), cheeses[i], "SERPOL", "kg",
                    "0.15", "6.99", "25 43 02");
        }
        insertProduct(productImport, "0000000000301", "5905555000001", "CHIPSY PAPRYKOWE 100G", "LAJKONIK", "kg",
                "0.1", "5.49", "01 01 05");
        insertProduct(productImport, "0000000000302", "5905555000002", "CHIPSY SOLONE 100G", "LAJKONIK", "kg",
                "0.1", "5.49", "01 01 05");
        // Aktywne w cadencier tylko mleko: masło ma CENA_CAD, ale nie ma go na liście, więc bez znacznika.
        String cadencierImport = jdbc.queryForObject("""
                insert into cadencier_imports (store_id, file_name, total_rows)
                values (?::uuid, 'obecne w cad.xlsx', 1) returning id""", String.class, storeId);
        jdbc.update("""
                insert into cadencier_items (store_id, itm8, ean, import_id)
                values (?::uuid, '0000000000101', '5900000000012', ?::uuid)""", storeId, cadencierImport);

        String nomenclatureImport = jdbc.queryForObject("""
                insert into nomenclature_imports (store_id, file_name, total_rows, created_count, updated_count,
                    missing_count)
                values (?::uuid, 'nomenklatury.pdf', 10, 10, 0, 0) returning id""", String.class, storeId);
        String[][] nomenclature = {{"01", "REGION", "01_DELIKATESY"}, {"0101", "FAMILY", "CHRUPKI"},
                {"010105", "SUBFAMILY", "CHIPSY"}, {"010106", "SUBFAMILY", "INNE"}, {"0109", "FAMILY", "CUKIERKI"},
                {"010904", "SUBFAMILY", "ZELKI I PIANKI"}, {"010909", "SUBFAMILY", "INNE"},
                {"25", "REGION", "25_NABIAL"}, {"2542", "FAMILY", "MLEKO"}, {"254201", "SUBFAMILY", "MLEKO UHT"},
                {"2543", "FAMILY", "SERY"}, {"254302", "SUBFAMILY", "SERY ZOLTE PLASTRY"},
                {"2544", "FAMILY", "JOGURTY"}, {"254401", "SUBFAMILY", "JOGURTY NATURALNE"}};
        for (String[] item : nomenclature) {
            jdbc.update("""
                    insert into nomenclatures (store_id, code, level, name, last_import_id, parent_id)
                    values (?::uuid, ?, ?, ?, ?::uuid, (select n.id from nomenclatures n
                        where n.store_id = ?::uuid and n.code = left(?, length(?) - 2)))""",
                    storeId, item[0], item[1], item[2], nomenclatureImport, storeId, item[0], item[0]);
        }
    }

    @Test
    void loadsInvoiceLinesWithCatalogProducts() throws Exception {
        mvc.perform(get(url("/invoices/" + invoiceId)).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceNumber").value("FV/77/2026"))
                .andExpect(jsonPath("$.sellerNip").value(SELLER_NIP))
                // Bez podsumowania stawek sumy z pozycji (VAT wyliczony ze stawek), bez wiersza "przed korektą".
                .andExpect(jsonPath("$.netTotal").value(295.00))
                .andExpect(jsonPath("$.vatTotal").value(16.55))
                // Wiersz "przed korektą" pominięty.
                .andExpect(jsonPath("$.lines.length()").value(8))
                .andExpect(jsonPath("$.source").value("AUTO"))
                // GTIN-14 z zerem na początku = EAN-13 z kartoteki.
                .andExpect(jsonPath("$.lines[0].ean").value("5900000000012"))
                .andExpect(jsonPath("$.lines[0].product.itm8").value("0000000000101"))
                .andExpect(jsonPath("$.lines[0].product.salePrice").value(3.49))
                .andExpect(jsonPath("$.lines[0].fallbackCode").doesNotExist())
                .andExpect(jsonPath("$.lines[0].product.subfamilyCode").value("254201"))
                .andExpect(jsonPath("$.lines[0].unit").value("L"))
                .andExpect(jsonPath("$.lines[0].vatPercent").value(5))
                .andExpect(jsonPath("$.lines[0].unitNet").value(2.0))
                // Dawny EAN artykułu (para z product_eans).
                .andExpect(jsonPath("$.lines[1].product.itm8").value("0000000000102"))
                .andExpect(jsonPath("$.lines[0].product.cadencier").value(true))
                .andExpect(jsonPath("$.lines[1].product.salePrice").value(4.99))
                .andExpect(jsonPath("$.lines[1].product.cadencier").value(false))
                .andExpect(jsonPath("$.lines[1].netContent").value(0.4))
                // Nowy artykuł: miara z nazwy z faktury, karton "/12" nie zmienia sztuki.
                .andExpect(jsonPath("$.lines[2].product").doesNotExist())
                .andExpect(jsonPath("$.lines[2].unit").value("kg"))
                .andExpect(jsonPath("$.lines[2].netContent").value(0.35))
                .andExpect(jsonPath("$.lines[3].unit").value("kg"))
                .andExpect(jsonPath("$.lines[3].netContent").value(0.15))
                .andExpect(jsonPath("$.lines[3].invoiceUnit").value("op."))
                // Pozycja bez kodu: bez EAN-u, sztuka.
                .andExpect(jsonPath("$.lines[5].ean").doesNotExist())
                // Bez EAN-u do kolumny idzie inny kod z faktury (indeks dostawcy), żeby pozycję dało się rozpoznać.
                .andExpect(jsonPath("$.lines[5].fallbackCode").value("12345678"))
                .andExpect(jsonPath("$.lines[5].fallbackSource").value("Indeks dostawcy"))
                .andExpect(jsonPath("$.lines[5].unit").value("szt."))
                .andExpect(jsonPath("$.lines[5].netContent").value(1))
                .andExpect(jsonPath("$.lines[5].vatPercent").value(23))
                // EAN z innych miejsc niż GTIN; indeks dostawcy "12345678" tylko wygląda jak EAN-8 (zła cyfra kontrolna).
                .andExpect(jsonPath("$.lines[6].ean").value("5900000000043"))
                .andExpect(jsonPath("$.lines[6].eanSource").value("INDEX"))
                .andExpect(jsonPath("$.lines[6].product.itm8").value("0000000000103"))
                .andExpect(jsonPath("$.lines[6].product.cadencier").value(false))
                .andExpect(jsonPath("$.lines[6].product.cadencierPrice").doesNotExist())
                .andExpect(jsonPath("$.lines[7].ean").value("5900000000050"))
                .andExpect(jsonPath("$.lines[7].eanSource").value("EXTRA:EAN"))
                .andExpect(jsonPath("$.sources[0].key").value("AUTO"))
                .andExpect(jsonPath("$.sources[0].filled").value(7))
                .andExpect(jsonPath("$.sources[0].matched").value(4))
                .andExpect(jsonPath("$.sources[?(@.key == 'GTIN')].matched").value(hasItem(2)))
                .andExpect(jsonPath("$.sources[?(@.key == 'INDEX')].matched").value(hasItem(1)))
                // Indeks liczy też kody własne dostawcy ("KF-400"), więc da się go wybrać jak w piekarniach.
                .andExpect(jsonPath("$.sources[?(@.key == 'INDEX')].filled").value(hasItem(3)))
                .andExpect(jsonPath("$.sources[?(@.key == 'EXTRA:EAN')].label").value(hasItem("Dodatkowy opis: EAN")))
                .andExpect(jsonPath("$.sources[?(@.key == 'NAME')]").isEmpty());

        // Wybrane źródło: tylko GTIN - pozycje z EAN-em w indeksie albo opisie zostają bez kodu.
        mvc.perform(get(url("/invoices/" + invoiceId + "?source=GTIN")).cookie(owner))
                .andExpect(jsonPath("$.source").value("GTIN"))
                .andExpect(jsonPath("$.lines[6].ean").doesNotExist())
                .andExpect(jsonPath("$.lines[6].product").doesNotExist())
                .andExpect(jsonPath("$.lines[6].fallbackCode").value("5900000000043"))
                .andExpect(jsonPath("$.lines[7].fallbackCode").value("KF-400"))
                .andExpect(jsonPath("$.lines[0].product.itm8").value("0000000000101"));
        // Wybrany indeks: pozycja bez EAN-u w indeksie dostaje w kolumnie kod własny dostawcy, nie EAN z opisu.
        mvc.perform(get(url("/invoices/" + invoiceId + "?source=INDEX")).cookie(owner))
                .andExpect(jsonPath("$.lines[6].ean").value("5900000000043"))
                .andExpect(jsonPath("$.lines[7].ean").doesNotExist())
                .andExpect(jsonPath("$.lines[7].fallbackCode").value("KF-400"))
                .andExpect(jsonPath("$.lines[7].fallbackSource").value("Indeks dostawcy"));
        mvc.perform(get(url("/invoices/" + invoiceId + "?source=COS")).cookie(owner))
                .andExpect(status().isBadRequest());

        mvc.perform(get(url("/products?ean=05900000000012&ean=5900000000029&ean=123")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get(url("/nomenclatures")).cookie(owner))
                .andExpect(jsonPath("$[?(@.code == '254302')].name").value(hasItem("SERY ZOLTE PLASTRY")));
    }

    @Test
    void catalogListFiltersEachColumnWithWildcards() throws Exception {
        jdbc.update("update products set supplier = 'MLECZARNIA ŁAKA' where store_id = ?::uuid and brand = 'LAKA'",
                storeId);

        // Gwiazdka = dowolny ciąg między członami, bez wielkości liter.
        mvc.perform(get(url("/catalog?name=ser*plas")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].name").value(
                        contains("SER EDAMSKI PLASTRY 150G", "SER GOUDA PLASTRY 150G", "SER LILIPUT PLASTRY 150G")))
                .andExpect(jsonPath("$.hasMore").value(false));
        // Kilka kolumn naraz; dostawca bez polskich znaków, ITM8 i EAN jako fragment.
        mvc.perform(get(url("/catalog?supplier=laka&ean=0000004&itm8=103")).cookie(owner))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("MASLO EKSTRA 200G"))
                .andExpect(jsonPath("$.items[0].supplier").value("MLECZARNIA ŁAKA"))
                .andExpect(jsonPath("$.items[0].subfamilyName").value("MLEKO UHT"));
        // Znaki specjalne LIKE są dosłowne.
        mvc.perform(get(url("/catalog")).param("name", "3,2%").cookie(owner))
                .andExpect(jsonPath("$.items[*].name").value(contains("MLEKO 3,2% 1L")));
        mvc.perform(get(url("/catalog?name=_")).cookie(owner))
                .andExpect(jsonPath("$.items.length()").value(0));

        // Nomenklatura: kod (ze spacjami) jako początek kodu podrodziny albo nazwa dowolnego poziomu.
        mvc.perform(get(url("/catalog")).param("nomenclature", "25 44").cookie(owner))
                .andExpect(jsonPath("$.items[*].name").value(contains("JOGURT NATURALNY 400G", "KEFIR 400G")));
        mvc.perform(get(url("/catalog")).param("nomenclature", "chrup").cookie(owner))
                .andExpect(jsonPath("$.items[*].name").value(contains("CHIPSY PAPRYKOWE 100G", "CHIPSY SOLONE 100G")));
        // Przedział ceny sprzedaży z granicami włącznie i sortowanie po stanie (puste na końcu).
        mvc.perform(get(url("/catalog?saleFrom=3.49&saleTo=4.99")).cookie(owner))
                .andExpect(jsonPath("$.items[*].name")
                        .value(contains("JOGURT NATURALNY 400G", "KEFIR 400G", "MLEKO 3,2% 1L")));
        jdbc.update("update products set stock = 5, purchase_price = 2 where store_id = ?::uuid and itm8 = '0000000000101'",
                storeId);
        jdbc.update("update products set stock = -3, purchase_price = 3 where store_id = ?::uuid and itm8 = '0000000000104'",
                storeId);
        mvc.perform(get(url("/catalog?purchaseFrom=1&sort=stock&direction=desc")).cookie(owner))
                .andExpect(jsonPath("$.items[*].name").value(contains("MLEKO 3,2% 1L", "KEFIR 400G")));
        mvc.perform(get(url("/catalog?saleTo=3.49&sort=stock")).cookie(owner))
                .andExpect(jsonPath("$.items[*].name").value(contains("KEFIR 400G", "MLEKO 3,2% 1L")));
        // Sortowanie po kolumnie tekstowej malejąco; nieznana kolumna = po nazwie.
        mvc.perform(get(url("/catalog")).param("nomenclature", "2544").param("sort", "name")
                .param("direction", "desc").cookie(owner))
                .andExpect(jsonPath("$.items[*].name").value(contains("KEFIR 400G", "JOGURT NATURALNY 400G")));
        mvc.perform(get(url("/catalog")).param("nomenclature", "2544").param("sort", "p.name; drop").cookie(owner))
                .andExpect(jsonPath("$.items[*].name").value(contains("JOGURT NATURALNY 400G", "KEFIR 400G")));
    }

    @Test
    void linksInvoiceCodeToCatalogProductForSupplier() throws Exception {
        String productImport = jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, total_rows, created_count, updated_count,
                    unchanged_count, missing_count)
                values (?::uuid, 'test.xlsx', 2, 2, 0, 0, 0) returning id""", String.class, storeId);
        insertProduct(productImport, "0000000000601", "5906666000001", "SZY KRU WIEP 1KG", "MASARNIA", "kg", "1",
                "29.99", "25 42 01");
        insertProduct(productImport, "0000000000602", "5906666000002", "KRUCHE CIASTKA 200G", "WAFLE", "kg", "0.2",
                "4.99", "01 09 04");

        // Kartoteka skraca nazwy: początki słów z faktury trafiają w skrót, kraj pochodzenia jest pomijany.
        mvc.perform(get(url("/link-suggestions")).param("text", "Szynka krucha wieprzowa kr. poch. PL")
                        .param("sellerNip", SELLER_NIP).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].itm8").value("0000000000601"));

        // Ser z faktury (EAN spoza kartoteki) przypięty do sera z kartoteki u tego dostawcy.
        String link = mvc.perform(post(url("/links")).with(csrf()).cookie(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sellerNip":"%s","code":"%s","itm8":"0000000000200"}"""
                                .formatted(SELLER_NIP, CHEESE_EAN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.itm8").value("0000000000200"))
                .andReturn().getResponse().getContentAsString();
        String linkId = JsonPath.read(link, "$.id");

        mvc.perform(get(url("/invoices/" + invoiceId)).cookie(owner))
                .andExpect(jsonPath("$.lines[2].product.itm8").value("0000000000200"))
                .andExpect(jsonPath("$.lines[2].ean").value("5901234560001"))
                .andExpect(jsonPath("$.lines[2].eanSource").value("LINK"))
                .andExpect(jsonPath("$.lines[2].linkId").value(linkId))
                .andExpect(jsonPath("$.lines[2].linkedCode").value(CHEESE_EAN));

        // Kod własny dostawcy z indeksu przypięty przy wyborze "Indeks dostawcy".
        mvc.perform(post(url("/links")).with(csrf()).cookie(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sellerNip":"%s","code":"kf-400","itm8":"0000000000601"}""".formatted(SELLER_NIP)))
                .andExpect(status().isOk());
        mvc.perform(get(url("/invoices/" + invoiceId + "?source=INDEX")).cookie(owner))
                .andExpect(jsonPath("$.lines[7].product.itm8").value("0000000000601"))
                .andExpect(jsonPath("$.lines[7].eanSource").value("LINK"))
                .andExpect(jsonPath("$.lines[7].linkedCode").value("KF-400"));

        // Odpięcie to znacznik: faktura wczytuje się znowu bez artykułu.
        mvc.perform(delete(url("/links/" + linkId)).with(csrf()).cookie(owner))
                .andExpect(status().isNoContent());
        mvc.perform(get(url("/invoices/" + invoiceId)).cookie(owner))
                .andExpect(jsonPath("$.lines[2].product").doesNotExist())
                .andExpect(jsonPath("$.lines[2].linkId").doesNotExist());
        assertThat(jdbc.queryForObject("select count(*) from data_entry_product_links where deleted_at is not null",
                Integer.class)).isPositive();
    }

    @Test
    void looksUpScaleLabelCodesWithPurchasePriceAndVat() throws Exception {
        String productImport = jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, total_rows, created_count, updated_count,
                    unchanged_count, missing_count)
                values (?::uuid, 'test.xlsx', 2, 2, 0, 0, 0) returning id""", String.class, storeId);
        insertProduct(productImport, "0000000000501", "2404140000000", "KABANOS WIEPRZOWY", "MASARNIA", "kg", "1",
                "39.99", "25 42 01");
        insertProduct(productImport, "0000000000502", "2991150000000", "LOSOS FILET", "MORSKIE", "kg", "1",
                "89.90", "25 42 01");
        jdbc.update("update products set purchase_price = 26.5 where store_id = ?::uuid and itm8 = '0000000000501'",
                storeId);
        String documentImport = jdbc.queryForObject("""
                insert into document_imports (store_id, file_name, total_lines, saved_lines, created_count,
                    updated_count, skipped_lines)
                values (?::uuid, 'Zeszyt2.xlsx', 2, 2, 2, 0, 0) returning id""", String.class, storeId);
        // Starsza dostawa ze stawką 8%, nowsza z 5%: obowiązuje najnowsza.
        for (String[] doc : new String[][] {{"100", "2026-09-01", "8"}, {"200", "2026-10-01", "5"}}) {
            String documentId = jdbc.queryForObject("""
                    insert into documents (store_id, type, number, document_date, line_count, purchase_value,
                        sale_value_gross, first_import_id, last_import_id)
                    values (?::uuid, 'PZ', ?, ?::timestamp, 1, 0, 0, ?::uuid, ?::uuid) returning id""",
                    String.class, storeId, doc[0], doc[1], documentImport, documentImport);
            jdbc.update("""
                    insert into document_lines (document_id, line_number, itm8, quantity, vat_rate)
                    values (?::uuid, 1, '0000000000501', 1, ?::numeric)""", UUID.fromString(documentId), doc[2]);
        }

        // Etykieta ceny (24, 49,20 zł) z cyfrą kontrolną i bez niej, etykieta wagi (29, 1,234 kg).
        mvc.perform(get(url("/products?ean=2404140049205&ean=240414004920&ean=2991150012347")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.ean == '2404140049205')].catalogEan").value(hasItem("2404140000000")))
                .andExpect(jsonPath("$[?(@.ean == '240414004920')].itm8").value(hasItem("0000000000501")))
                .andExpect(jsonPath("$[?(@.ean == '2404140049205')].purchasePrice").value(hasItem(26.5)))
                .andExpect(jsonPath("$[?(@.ean == '2404140049205')].vatPercent").value(hasItem(5)))
                .andExpect(jsonPath("$[?(@.ean == '2991150012347')].itm8").value(hasItem("0000000000502")));
        // Inny artykuł z tym samym początkiem prefiksu nie łapie się.
        mvc.perform(get(url("/products?ean=2404150049205")).cookie(owner))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void suggestsWithModelAndExports() throws Exception {
        when(model.available()).thenReturn(true);
        when(model.name()).thenReturn("test-model");
        when(model.suggest(anyString(), anyString())).thenReturn(List.of(
                // Ser: warstwa bez modelu jest pewna, więc podrodzina modelu (inna) jej nie zmienia.
                new SuggestionModel.Answer(0, "Ser Liliput 350 g.", "serpol", "254401", List.of(), 0.7, "kg",
                        new BigDecimal("0.35")),
                // Chipsy: model zgodny z podobnymi nazwami i pewny; marka jak w kartotece, kod spoza nomenklatury
                // w alternatywach pominięty.
                new SuggestionModel.Answer(1, "Chipsy paprykowe 150g/12", "lajkonik", "010105",
                        List.of("999999", "010106"), 0.95, "kg", new BigDecimal("0.15")),
                // Pianki: model niepewny - podrodziny nie wpisujemy, są propozycje do wyboru.
                new SuggestionModel.Answer(2, "Pianki pasek pompon 30g", "Wisła", "010909", List.of("010904"),
                        0.6, "kg", new BigDecimal("0.03"))));

        String response = suggest(owner, items()).andExpect(status().isOk())
                .andExpect(jsonPath("$.modelUsed").value(true))
                .andExpect(jsonPath("$.items[0].key").value("a"))
                .andExpect(jsonPath("$.items[0].status").value("CERTAIN"))
                .andExpect(jsonPath("$.items[0].subfamilyCode").value("254302"))
                .andExpect(jsonPath("$.items[0].name").value("SER LILIPUT 350G"))
                .andExpect(jsonPath("$.items[0].brand").value("SERPOL"))
                .andExpect(jsonPath("$.items[0].proposals[0].code").value("254302"))
                .andExpect(jsonPath("$.items[0].proposals[0].sources").value(hasItem("PRODUCER")))
                .andExpect(jsonPath("$.items[1].status").value("AGREED"))
                .andExpect(jsonPath("$.items[1].subfamilyCode").value("010105"))
                .andExpect(jsonPath("$.items[1].name").value("CHIPSY PAPRYKOWE 150G"))
                .andExpect(jsonPath("$.items[1].brand").value("LAJKONIK"))
                .andExpect(jsonPath("$.items[1].proposals[*].code").value(not(hasItem("999999"))))
                .andExpect(jsonPath("$.items[1].proposals[1].code").value("010106"))
                .andExpect(jsonPath("$.items[2].status").value("TO_CHECK"))
                .andExpect(jsonPath("$.items[2].subfamilyCode").doesNotExist())
                .andExpect(jsonPath("$.items[2].proposals[0].code").value("010909"))
                .andExpect(jsonPath("$.items[2].proposals[1].code").value("010904"))
                .andExpect(jsonPath("$.items[2].name").value("PIANKI PASEK POMPON 30G"))
                .andExpect(jsonPath("$.items[2].brand").value("WISLA"))
                .andReturn().getResponse().getContentAsString();

        // Instrukcja ma drzewo nomenklatury, zapytanie wskazówki z kartoteki.
        ArgumentCaptor<String> instruction = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(model).suggest(instruction.capture(), prompt.capture());
        assertThat(instruction.getValue()).contains("    254302 SERY ZOLTE PLASTRY");
        assertThat(prompt.getValue()).contains("ten sam producent (prefiks EAN, 5 produktów")
                .contains("SER LILIPUT PLASTRY 150G | SERPOL | 254302")
                .contains("Mleko 3,2% 1 l  ->  MLEKO 3,2% 1L | LAKA");
        assertThat(jdbc.queryForObject("select count(*) from data_entry_suggestions where store_id = ?::uuid",
                Integer.class, storeId)).isEqualTo(3);

        // Eksport: plik Excel, a przy propozycjach to, co wyszło z tabeli.
        String chipsId = JsonPath.read(response, "$.items[1].id");
        byte[] file = mvc.perform(post(url("/export/xlsx")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invoiceNumber":"FV/77/2026","sellerName":"SERY I CHIPSY SA","rows":[
                                 {"suggestionId":"%s","ean":"%s","quantity":12,"unitNet":2.5,"invoiceUnit":"szt.",
                                  "name":"CHIPSY PAPRYKA 150G","brand":"LAJKONIK","vatPercent":5,"salePrice":4.99,
                                  "subfamilyCode":"010105","unit":"kg","netContent":0.15,"status":"AGREED",
                                  "known":false},
                                 {"ean":"5900000000012","quantity":1,"unitNet":2,"name":"MLEKO","vatPercent":5,
                                  "known":true}]}""".formatted(chipsId, CHIPS_EAN)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(".xlsx")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(file, 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");
        assertThat(jdbc.queryForObject("select final_name from data_entry_suggestions where id = ?::uuid",
                String.class, chipsId)).isEqualTo("CHIPSY PAPRYKA 150G");
    }

    @Test
    void invoiceXmlFile() throws Exception {
        String number = jdbc.queryForObject("select number from stores where id = ?::uuid", String.class, storeId);
        jdbc.update("""
                insert into suppliers (store_id, code, keyword, name, nip)
                values (?::uuid, '154', 'SERY', 'SERY I CHIPSY SP. Z O.O.', ?)""",
                storeId, SELLER_NIP);
        // Nagłówek z faktury KSeF i listy dostawców; kod spoza EAN-u (zastępczy z faktury) daje 13 zer.
        String table = """
                {"invoiceId":"%s","rows":[
                 {"ean":"%s","quantity":12,"unitNet":2.499,"name":"CHIPSY & PAPRYKA 150G",
                  "vatPercent":5,"known":false},
                 {"ean":"Gratis Lipton","quantity":1,"unitNet":0.01,"name":"GRATIS","vatPercent":23,
                  "known":false}]}""".formatted(invoiceId, CHIPS_EAN);
        var exported = mvc.perform(post(url("/export/xml")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(table))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        containsString("filename=\"" + number + "_154_FV_77_2026.xml\"")))
                .andReturn().getResponse();
        byte[] file = exported.getContentAsByteArray();
        String exportId = exported.getHeader("X-Xml-Export-Id");
        assertThat(new String(file, Charset.forName("windows-1250"))).isEqualTo("""
                <?xml version="1.0" encoding="windows-1250"?>
                <DOKUMENT>
                \t<NAGLOWEK>
                \t\t<NR_DOKUMENTU>FV/77/2026</NR_DOKUMENTU>
                \t\t<DATA_DOKUMENTU>2026.10.30</DATA_DOKUMENTU>
                \t\t<KOD_DOSTAWCY>SERY I CHIPSY SP. Z O.O.</KOD_DOSTAWCY>
                \t\t<KOD_ODBIORCY>%s</KOD_ODBIORCY>
                \t\t<ILOSC_POZYCJI>2</ILOSC_POZYCJI>
                \t\t<WART_NET>30.01</WART_NET>
                \t\t<NIP_DOSTAWCY>%s</NIP_DOSTAWCY>
                \t</NAGLOWEK>
                \t<POZYCJE>
                \t\t<POZYCJA>
                \t\t\t<OPERACJA>D</OPERACJA>
                \t\t\t<LP>1</LP>
                \t\t\t<NAZWA>CHIPSY &amp; PAPRYKA 150G</NAZWA>
                \t\t\t<KOD_KRESKOWY>%s</KOD_KRESKOWY>
                \t\t\t<CENA_NET>2.50</CENA_NET>
                \t\t\t<PROC_RABAT>0.00</PROC_RABAT>
                \t\t\t<PROC_VAT>5.00</PROC_VAT>
                \t\t\t<PKWiU />
                \t\t\t<ILOSC>12.000</ILOSC>
                \t\t</POZYCJA>
                \t\t<POZYCJA>
                \t\t\t<OPERACJA>D</OPERACJA>
                \t\t\t<LP>2</LP>
                \t\t\t<NAZWA>GRATIS</NAZWA>
                \t\t\t<KOD_KRESKOWY>0000000000000</KOD_KRESKOWY>
                \t\t\t<CENA_NET>0.01</CENA_NET>
                \t\t\t<PROC_RABAT>0.00</PROC_RABAT>
                \t\t\t<PROC_VAT>23.00</PROC_VAT>
                \t\t\t<PKWiU />
                \t\t\t<ILOSC>1.000</ILOSC>
                \t\t</POZYCJA>
                \t</POZYCJE>
                </DOKUMENT>
                """.formatted(number, SELLER_NIP, CHIPS_EAN).replace("\n", "\r\n"));

        // Inny dostawca niż sprzedawca z faktury: nazwa i NIP z listy dostawców, numer faktury znika, data zostaje.
        String otherSupplier = jdbc.queryForObject("""
                insert into suppliers (store_id, code, keyword, name, nip)
                values (?::uuid, '771', 'HURT', 'HURTOWNIA SP. Z O.O.', '5260250995') returning id::text""",
                String.class, storeId);
        mvc.perform(post(url("/export/xml")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(table.replaceFirst("\\{", "{\"supplierId\":\"" + otherSupplier + "\",")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        matchesPattern(".*filename=\"" + number + "_wprowadzanie_\\d{8}_\\d{6}\\.xml\".*")))
                .andExpect(content().string(containsString("<NR_DOKUMENTU />")))
                .andExpect(content().string(containsString("<DATA_DOKUMENTU>2026.10.30</DATA_DOKUMENTU>")))
                .andExpect(content().string(containsString("<KOD_DOSTAWCY>HURTOWNIA SP. Z O.O.</KOD_DOSTAWCY>")))
                .andExpect(content().string(containsString("<NIP_DOSTAWCY>5260250995</NIP_DOSTAWCY>")));

        // Tabela bez faktury: bez numeru i dostawcy, nazwa pliku z datą.
        mvc.perform(post(url("/export/xml")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"ean\":\"%s\",\"quantity\":1,\"unitNet\":2,\"name\":\"CHIPSY\",\"known\":false}]}"
                                .formatted(CHIPS_EAN)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        matchesPattern(".*filename=\"" + number + "_wprowadzanie_\\d{8}_\\d{6}\\.xml\".*")))
                .andExpect(content().string(containsString("<NR_DOKUMENTU />")));

        // Do historii trafia tylko plik, którego zapis na dysku przeglądarka potwierdziła; niepotwierdzony nie istnieje.
        mvc.perform(get(url("/xml-history")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(url("/xml-history/" + exportId)).cookie(owner)).andExpect(status().isNotFound());
        mvc.perform(post(url("/xml-history/" + exportId + "/saved")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(table))
                .andExpect(status().isNoContent());
        mvc.perform(get(url("/xml-history")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(exportId))
                .andExpect(jsonPath("$[0].documentNumber").value("FV/77/2026"))
                .andExpect(jsonPath("$[0].supplierName").value("SERY I CHIPSY SP. Z O.O."))
                .andExpect(jsonPath("$[0].lineCount").value(2))
                .andExpect(jsonPath("$[0].netValue").value(30.01));
        mvc.perform(get(url("/xml-history/" + exportId + "/file")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(content().bytes(file));
        mvc.perform(get(url("/xml-history/" + exportId)).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otherStore").value(false))
                .andExpect(jsonPath("$.document.invoiceId").value(invoiceId.toString()))
                .andExpect(jsonPath("$.document.lines[0].ean").value(CHIPS_EAN))
                .andExpect(jsonPath("$.document.lines[0].quantity").value(12))
                .andExpect(jsonPath("$.document.lines[0].unitNet").value(2.5))
                .andExpect(jsonPath("$.document.lines[1].ean").doesNotExist())
                .andExpect(jsonPath("$.document.lines[1].fallbackCode").value("GRATIS"))
                .andExpect(jsonPath("$.draft").doesNotExist());

        // Zapisy tymczasowe: z tabelą z przeglądarki, najwyżej 10 (starszy dostaje znacznik), usunięcie z "Przywróć".
        String first = null;
        for (int i = 0; i < 11; i++) {
            String saved = mvc.perform(post(url("/xml-temporary")).cookie(owner).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"export\":%s,\"draft\":\"{\\\"rows\\\":[%d]}\"}".formatted(table, i)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.documentNumber").value("FV/77/2026"))
                    .andReturn().getResponse().getContentAsString();
            if (i == 0) {
                first = JsonPath.read(saved, "$.id");
            }
        }
        String temporary = mvc.perform(get(url("/xml-temporary")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$[*].id", not(hasItem(first))))
                .andReturn().getResponse().getContentAsString();
        String newest = JsonPath.read(temporary, "$[0].id");
        mvc.perform(get(url("/xml-history/" + newest)).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft").value("{\"rows\":[10]}"))
                .andExpect(jsonPath("$.document.lines.length()").value(2));
        mvc.perform(delete(url("/xml-temporary/" + newest)).cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(9));
        mvc.perform(post(url("/xml-temporary/" + newest + "/restore")).cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$[0].id").value(newest));
        // Zapisy tymczasowe nie są w historii zapisanych na dysku.
        mvc.perform(get(url("/xml-history")).cookie(owner)).andExpect(jsonPath("$.length()").value(1));

        // Plik z dysku: faktura z KSeF po NIP-ie i numerze; plik innego sklepu jest oznaczony.
        mvc.perform(multipart(url("/xml-documents"))
                        .file(new MockMultipartFile("file", "faktura.xml", "application/xml", file))
                        .cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("faktura.xml"))
                .andExpect(jsonPath("$.document.invoiceId").value(invoiceId.toString()))
                .andExpect(jsonPath("$.document.lines.length()").value(2));
        byte[] otherStore = new String(file, Charset.forName("windows-1250"))
                .replace("<KOD_ODBIORCY>" + number, "<KOD_ODBIORCY>39270")
                .replace("FV/77/2026", "FS-1/26").getBytes(Charset.forName("windows-1250"));
        mvc.perform(multipart(url("/xml-documents"))
                        .file(new MockMultipartFile("file", "inny.xml", "application/xml", otherStore))
                        .cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otherStore").value(true))
                .andExpect(jsonPath("$.recipientCode").value("39270"))
                .andExpect(jsonPath("$.document.invoiceId").doesNotExist())
                .andExpect(jsonPath("$.document.invoiceNumber").value("FS-1/26"))
                .andExpect(jsonPath("$.document.sellerNip").value(SELLER_NIP))
                .andExpect(jsonPath("$.document.netTotal").value(30.01));
        mvc.perform(multipart(url("/xml-documents"))
                        .file(new MockMultipartFile("file", "zly.xml", "application/xml",
                                "<FAKTURA/>".getBytes(StandardCharsets.UTF_8)))
                        .cookie(owner).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shortensTooLongNames() throws Exception {
        when(model.available()).thenReturn(true);
        when(model.name()).thenReturn("test-model");
        when(model.suggest(anyString(), anyString())).thenReturn(List.of(
                new SuggestionModel.Answer(2, "Pianki pasek pompon truskawkowo-malinowe 30g", "Wisła", "010909",
                        List.of(), 0.6, "kg", new BigDecimal("0.03"))));
        when(model.shorten(anyString()))
                .thenReturn(List.of(new SuggestionModel.Shortened(0, "pianki pompon trusk-mal")));

        suggest(owner, items()).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[2].name").value("PIANKI POMPON TRUSK-MAL 30G"));

        // Skracana tylko nazwa za długa, z limitem bez miary (30 - "30G" - spacja).
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(model).shorten(prompt.capture());
        assertThat(prompt.getValue()).contains("obecnie: PIANKI PASEK POMPON TRUSKAWKOWO-MALINOWE (")
                .contains("limit: 26 znaków").doesNotContain("id=1");
    }

    @Test
    void withoutModelProposesFromCatalog() throws Exception {
        when(model.available()).thenReturn(true);
        when(model.name()).thenReturn("test-model");
        when(model.suggest(anyString(), anyString()))
                .thenThrow(new SuggestionModel.Unavailable("Model jest chwilowo przeciążony"));

        suggest(owner, items()).andExpect(status().isOk())
                .andExpect(jsonPath("$.modelUsed").value(false))
                .andExpect(jsonPath("$.message").value(containsString("przeciążony")))
                .andExpect(jsonPath("$.items[0].status").value("CERTAIN"))
                .andExpect(jsonPath("$.items[0].subfamilyCode").value("254302"))
                .andExpect(jsonPath("$.items[0].name").doesNotExist())
                .andExpect(jsonPath("$.items[0].unit").value("kg"))
                .andExpect(jsonPath("$.items[0].netContent").value(0.35))
                .andExpect(jsonPath("$.items[1].status").value("TO_CHECK"))
                .andExpect(jsonPath("$.items[1].proposals[0].code").value("010105"))
                .andExpect(jsonPath("$.items[1].proposals[0].sources").value(hasItem("NAMES")));

        // Model nieskonfigurowany: bez zapytania, z wyjaśnieniem.
        when(model.available()).thenReturn(false);
        suggest(owner, items()).andExpect(jsonPath("$.modelUsed").value(false))
                .andExpect(jsonPath("$.message").value(containsString("nie jest skonfigurowany")));

        // Bez nomenklatury AI nie ma z czego wybierać.
        jdbc.update("delete from nomenclatures where store_id = ?::uuid", storeId);
        suggest(owner, items()).andExpect(status().isConflict());
    }

    @Test
    void permissions() throws Exception {
        // Podgląd i eksport bez faktur KSeF i bez propozycji AI.
        Cookie viewer = employee("{\"DATA_ENTRY\":\"VIEW\"}", storeId);
        mvc.perform(get(url("/nomenclatures")).cookie(viewer)).andExpect(status().isOk());
        mvc.perform(get(url("/invoices/" + invoiceId)).cookie(viewer)).andExpect(status().isForbidden());
        suggest(viewer, items()).andExpect(status().isForbidden());
        verify(model, never()).suggest(anyString(), anyString());

        Cookie reader = employee("{\"DATA_ENTRY\":\"VIEW\",\"KSEF_INVOICES\":\"VIEW\"}", storeId);
        mvc.perform(get(url("/invoices/" + invoiceId)).cookie(reader)).andExpect(status().isOk());

        // Pracownik innego sklepu.
        Cookie stranger = employee("{\"DATA_ENTRY\":\"EDIT\",\"KSEF_INVOICES\":\"VIEW\"}", createStore());
        mvc.perform(get(url("/invoices/" + invoiceId)).cookie(stranger)).andExpect(status().isForbidden());
        suggest(stranger, items()).andExpect(status().isForbidden());
    }

    @Test
    void priceControlFile() throws Exception {
        String supplierId = jdbc.queryForObject("""
                insert into suppliers (store_id, code, keyword, name, nip)
                values (?::uuid, '98', 'GOBARTO', 'GOBARTO S.A.', '6991781489') returning id::text""",
                String.class, storeId);
        // Dostawca po NIP-ie sprzedawcy; EAN-8 dopełniony do 13 cyfr, kod zastępczy i cena 0 pominięte.
        String body = """
                {"sellerNip":"699-178-14-89","invoiceNumber":"FV/9","rows":[
                 {"ean":"2432710000000","salePrice":7.99},{"ean":"5904634010327","salePrice":4.99},
                 {"ean":"12345670","salePrice":23.49},{"ean":"SZYNKA","salePrice":1},
                 {"ean":"5900000000012","salePrice":0}]}""";
        byte[] file = mvc.perform(post(url("/export/price-control")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        matchesPattern(".*filename=\"CTRLPRIX_\\d{8}_\\d{6}_GOBARTO_wlasciciel\\.Traite1\".*")))
                .andExpect(header().string("X-Supplier-Code", "98"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(file, StandardCharsets.US_ASCII)).isEqualTo("""
                243271000000000000000000000000799\r
                590463401032700000000000000000499\r
                000001234567000000000000000002349\r
                """);

        // Historia: plik da się zapisać ponownie z tą samą nazwą i treścią.
        String id = jdbc.queryForObject(
                "select id::text from data_entry_price_control_files where store_id = ?::uuid", String.class, storeId);
        mvc.perform(get(url("/price-control-files")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].supplierCode").value("98"))
                .andExpect(jsonPath("$[0].invoiceNumber").value("FV/9"))
                .andExpect(jsonPath("$[0].lineCount").value(3));
        assertThat(mvc.perform(get(url("/price-control-files/" + id + "/file")).cookie(owner))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(file);

        // Dostawca bez hasła i dostawca spoza listy: brak pliku.
        jdbc.update("update suppliers set keyword = null where id = ?::uuid", supplierId);
        mvc.perform(post(url("/export/price-control")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url("/export/price-control")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellerNip\":\"5260250995\",\"rows\":[{\"ean\":\"5904634010327\",\"salePrice\":4.99}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void protocolFiles() throws Exception {
        String settings = "/api/stores/" + storeId + "/proto-settings";
        // Bez zapisanych ustawień: domyślny folder i wszystkie protokoły widoczne.
        mvc.perform(get(settings).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folderPath").value("N:\\avisoconfig"))
                .andExpect(jsonPath("$.defaultFolder").value(true))
                .andExpect(jsonPath("$.hiddenProtocols.length()").value(0))
                .andExpect(jsonPath("$.productFileFolderPath").doesNotExist());

        // Plik nowych i zmienionych artykułów (CADBEZP): stary Excel, nazwa z dostawcy i numeru faktury.
        byte[] products = mvc.perform(post(url("/export/product-file")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sellerName":"Impek","invoiceNumber":"FV/1","rows":[{
                                 "ean":"5901414299422","name":"ST.STAND CIAHIT+HIT MINIS","brand":"HIT","unit":"szt.",
                                 "netContent":1,"purchasePrice":0.01,"vatPercent":5,"salePrice":0.02,
                                 "subfamilyCode":"010205"}]}"""))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("CADBEZP-IMPEK-FV_1.xls")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(products).startsWith((byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0);
        mvc.perform(post(url("/export/product-file")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rows\":[]}"))
                .andExpect(status().isBadRequest());

        // Plik PROTO1 (likwidacje): ilości ze znakiem, 0 pominięte, w nazwie numer sklepu i login.
        String number = jdbc.queryForObject("select number from stores where id = ?::uuid", String.class, storeId);
        String lines = """
                {"lines":[{"ean":"5900000000012","quantity":2},{"ean":"5900000000036","quantity":-0.5},
                 {"ean":"5900000000043","quantity":0}]}""";
        byte[] file = mvc.perform(post(url("/protocols/LIQUIDATION")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(lines))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        matchesPattern(
                                ".*filename=\"PROTO1_\\d{8}_\\d{6}_" + number + "_wlasciciel\\.Traite0\".*")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(file, StandardCharsets.US_ASCII))
                .isEqualTo("5900000000012000002000\r\n5900000000036-00000500\r\n");
        mvc.perform(post(url("/protocols/TRANSFER")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(lines))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("PROTO6_")));

        // Ukryty protokół nie powstaje; folder sklepu wraca w ustawieniach.
        mvc.perform(put(settings).cookie(owner)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderPath\":\" D:\\\\proto \",\"hiddenProtocols\":[\"DONATION\",\"ADVERTISING\"],"
                                + "\"productFileFolderPath\":\"N:\\\\cadbezp\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folderPath").value("D:\\proto"))
                .andExpect(jsonPath("$.productFileFolderPath").value("N:\\cadbezp"))
                .andExpect(jsonPath("$.defaultFolder").value(false))
                .andExpect(jsonPath("$.hiddenProtocols", hasItem("DONATION")))
                .andExpect(jsonPath("$.updatedBy").isNotEmpty());
        mvc.perform(post(url("/protocols/DONATION")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(lines))
                .andExpect(status().isBadRequest());

        // Same zera albo kody niebędące EAN-em: brak pliku.
        mvc.perform(post(url("/protocols/LIQUIDATION")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"ean\":\"5900000000012\",\"quantity\":0}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url("/protocols/LIQUIDATION")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"ean\":\"KF-400\",\"quantity\":1}]}"))
                .andExpect(status().isBadRequest());

        // Ustawienia zmienia tylko osoba, która zapisuje pliki PROTO; odczyt każdy z dostępem do sklepu.
        Cookie viewer = employee("{\"DATA_ENTRY\":\"VIEW\"}", storeId);
        mvc.perform(get(settings).cookie(viewer)).andExpect(status().isOk());
        mvc.perform(put(settings).cookie(viewer)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderPath\":null,\"hiddenProtocols\":[]}"))
                .andExpect(status().isForbidden());
    }

    private String items() {
        return """
                {"invoiceId":"%s","sellerNip":"%s","sellerName":"SERY I CHIPSY SA",
                 "examples":[{"invoiceName":"Mleko 3,2%% 1 l","catalogName":"MLEKO 3,2%% 1L","brand":"LAKA"}],
                 "items":[
                  {"key":"a","ean":"%s","invoiceName":"Ser Liliput 350g /12/","invoiceUnit":"szt."},
                  {"key":"b","ean":"%s","invoiceName":"Chipsy paprykowe 150g/12","invoiceUnit":"szt."},
                  {"key":"c","ean":"%s","invoiceName":"Wisła Pianka Pasek Pom pon magic 30g/30","invoiceUnit":"szt."}
                 ]}""".formatted(invoiceId, SELLER_NIP, CHEESE_EAN, CHIPS_EAN, MARSHMALLOW_EAN);
    }

    @Test
    void invoicePickerTracksStatusesOtherStoresAndSkippedSuppliers() throws Exception {
        // Nowa faktura czeka na wczytanie; korekty to osobna kategoria.
        mvc.perform(get(url("/invoices")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoices.length()").value(1))
                .andExpect(jsonPath("$.invoices[0].invoice.id").value(invoiceId))
                .andExpect(jsonPath("$.invoices[0].status").doesNotExist())
                .andExpect(jsonPath("$.invoices[0].otherStores.length()").value(0));
        mvc.perform(get(url("/invoices?kind=CORRECTIONS")).cookie(owner))
                .andExpect(jsonPath("$.invoices.length()").value(0));

        // Wczytana w innym sklepie tej samej firmy: tutaj tylko informacja, dalej do wczytania.
        String other = createStore();
        jdbc.update("""
                insert into ksef_connections (store_id, nip, certificate_pem, private_key_pem, password_encrypted,
                    certificate_subject, certificate_valid_to, updated_at)
                select ?::uuid, nip, 'x', 'x', 'x', 'TEST', now() + interval '1 year', now()
                from ksef_connections where store_id = ?::uuid""", other, storeId);
        mvc.perform(post("/api/stores/" + other + "/data-entry/invoices/" + invoiceId + "/marks").with(csrf())
                        .cookie(owner).contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"LOADED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOADED"));
        mvc.perform(get(url("/invoices")).cookie(owner))
                .andExpect(jsonPath("$.invoices.length()").value(1))
                .andExpect(jsonPath("$.invoices[0].status").doesNotExist())
                .andExpect(jsonPath("$.invoices[0].otherStores[0].storeId").value(other))
                .andExpect(jsonPath("$.invoices[0].otherStores[0].kind").value("LOADED"));

        // Pobrana (wydruk podstawowy) to tylko informacja: faktura dalej czeka na wczytanie.
        mvc.perform(get(url("/invoices/" + invoiceId + "/pdf")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
        mvc.perform(get(url("/invoices")).cookie(owner))
                .andExpect(jsonPath("$.invoices.length()").value(1))
                .andExpect(jsonPath("$.invoices[0].status").doesNotExist())
                .andExpect(jsonPath("$.invoices[0].downloadedAt").exists());
        mvc.perform(get(url("/invoices?status=DONE")).cookie(owner))
                .andExpect(jsonPath("$.invoices.length()").value(0));

        // Wczytana tutaj: znika z listy do wczytania, jest wśród wczytanych.
        mark("LOADED").andExpect(jsonPath("$.status").value("LOADED"));
        mvc.perform(get(url("/invoices")).cookie(owner)).andExpect(jsonPath("$.invoices.length()").value(0));
        mvc.perform(get(url("/invoices?status=DONE")).cookie(owner))
                .andExpect(jsonPath("$.invoices[0].status").value("LOADED"));

        mvc.perform(get(url("/invoices/" + invoiceId + "/pdf?variant=FULL")).cookie(owner))
                .andExpect(status().isOk());
        mvc.perform(get(url("/invoices?status=DONE")).cookie(owner))
                .andExpect(jsonPath("$.invoices[0].status").value("LOADED"))
                .andExpect(jsonPath("$.invoices[0].downloadedAt").exists());

        // Powrót na listę (informacja o pobraniu zostaje), potem "nie moja faktura" w worku z wczytanymi.
        mvc.perform(delete(url("/invoices/" + invoiceId + "/marks")).with(csrf()).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.downloadedAt").exists());
        mvc.perform(get(url("/invoices")).cookie(owner)).andExpect(jsonPath("$.invoices.length()").value(1));
        mark("NOT_MINE").andExpect(jsonPath("$.status").value("NOT_MINE"));
        mvc.perform(get(url("/invoices")).cookie(owner)).andExpect(jsonPath("$.invoices.length()").value(0));
        mvc.perform(delete(url("/invoices/" + invoiceId + "/marks")).with(csrf()).cookie(owner))
                .andExpect(status().isOk());

        // Pomijany dostawca znika z okna; usunięcie z listy (znacznik) i przywrócenie.
        String skipped = JsonPath.read(mvc.perform(post(url("/skipped-suppliers")).with(csrf()).cookie(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellerNip\":\"" + SELLER_NIP + "\",\"sellerName\":\"SERY I CHIPSY SA\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(get(url("/invoices")).cookie(owner)).andExpect(jsonPath("$.invoices.length()").value(0));
        mvc.perform(get(url("/invoices?showSkipped=true")).cookie(owner))
                .andExpect(jsonPath("$.invoices.length()").value(1));
        mvc.perform(get(url("/skipped-suppliers")).cookie(owner))
                .andExpect(jsonPath("$[0].sellerName").value("SERY I CHIPSY SA"));
        mvc.perform(delete(url("/skipped-suppliers/" + skipped)).with(csrf()).cookie(owner))
                .andExpect(status().isNoContent());
        mvc.perform(get(url("/invoices")).cookie(owner)).andExpect(jsonPath("$.invoices.length()").value(1));
        mvc.perform(post(url("/skipped-suppliers/" + skipped + "/restore")).with(csrf()).cookie(owner))
                .andExpect(status().isOk());
        mvc.perform(get(url("/invoices")).cookie(owner)).andExpect(jsonPath("$.invoices.length()").value(0));

        // Odkładany dostawca: jego faktury są od razu w "Wczytane" (zmiana trybu zastępuje regułę pomijania).
        mvc.perform(post(url("/skipped-suppliers")).with(csrf()).cookie(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellerNip\":\"" + SELLER_NIP + "\",\"mode\":\"DONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("DONE"))
                .andExpect(jsonPath("$.sellerName").value("SERY I CHIPSY SA"));
        mvc.perform(get(url("/invoices")).cookie(owner)).andExpect(jsonPath("$.invoices.length()").value(0));
        String doneRule = JsonPath.read(mvc.perform(get(url("/skipped-suppliers")).cookie(owner))
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn().getResponse().getContentAsString(), "$[0].id");
        mvc.perform(get(url("/invoices?status=DONE")).cookie(owner))
                .andExpect(jsonPath("$.invoices.length()").value(1))
                .andExpect(jsonPath("$.invoices[0].supplierRule").value("DONE"))
                .andExpect(jsonPath("$.invoices[0].status").doesNotExist());
        mvc.perform(delete(url("/skipped-suppliers/" + doneRule)).with(csrf()).cookie(owner))
                .andExpect(status().isNoContent());

        // Zaznaczenie wszystkich wyfiltrowanych: "nie moje" zbiorczo i „Cofnij”.
        String bulk = mvc.perform(post(url("/invoices/bulk")).with(csrf()).cookie(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"NOT_MINE\",\"filter\":{\"kind\":\"INVOICES\",\"status\":\"NEW\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get(url("/invoices")).cookie(owner)).andExpect(jsonPath("$.invoices.length()").value(0));
        mvc.perform(post(url("/invoices/bulk/undo")).with(csrf()).cookie(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"createdMarkIds\":[\"" + JsonPath.read(bulk, "$.createdMarkIds[0]") + "\"]}"))
                .andExpect(status().isNoContent());
        mvc.perform(get(url("/invoices")).cookie(owner)).andExpect(jsonPath("$.invoices.length()").value(1));
        // Wykluczona faktura zostaje bez zmian; podane id - tylko one.
        mvc.perform(post(url("/invoices/bulk")).with(csrf()).cookie(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"LOADED\",\"filter\":{\"status\":\"NEW\"},\"excludeIds\":[\""
                                + invoiceId + "\"]}"))
                .andExpect(jsonPath("$.count").value(0));
        mvc.perform(post(url("/invoices/bulk")).with(csrf()).cookie(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"LOADED\",\"invoiceIds\":[\"" + invoiceId + "\"]}"))
                .andExpect(jsonPath("$.count").value(1));
        mvc.perform(post(url("/invoices/bulk")).with(csrf()).cookie(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"RESTORE\",\"invoiceIds\":[\"" + invoiceId + "\"]}"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.deletedMarkIds.length()").value(1));
        mvc.perform(get(url("/invoices")).cookie(owner)).andExpect(jsonPath("$.invoices.length()").value(1));
    }

    private ResultActions mark(String kind) throws Exception {
        return mvc.perform(post(url("/invoices/" + invoiceId + "/marks")).with(csrf()).cookie(owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"" + kind + "\"}"))
                .andExpect(status().isOk());
    }

    private ResultActions suggest(Cookie user, String json) throws Exception {
        return mvc.perform(post(url("/suggestions")).cookie(user).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String invoiceXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Faktura xmlns="http://crd.gov.pl/wzor/2025/06/25/13775/">
                  <Naglowek><KodFormularza kodSystemowy="FA (3)" wersjaSchemy="1-0E">FA</KodFormularza></Naglowek>
                  <Podmiot1><DaneIdentyfikacyjne><NIP>1111111111</NIP><Nazwa>SERY I CHIPSY SA</Nazwa>
                    </DaneIdentyfikacyjne></Podmiot1>
                  <Podmiot2><DaneIdentyfikacyjne><NIP>5265877635</NIP><Nazwa>TRYTOMA</Nazwa></DaneIdentyfikacyjne>
                    </Podmiot2>
                  <Fa>
                    <KodWaluty>PLN</KodWaluty><P_1>2026-10-30</P_1><P_2>FV/77/2026</P_2><P_15>110.00</P_15>
                    <RodzajFaktury>VAT</RodzajFaktury>
                    <DodatkowyOpis><NrWiersza>8</NrWiersza><Klucz>EAN</Klucz><Wartosc>5900000000050</Wartosc>
                      </DodatkowyOpis>
                    <FaWiersz><NrWierszaFa>1</NrWierszaFa><P_7>Mleko 3,2%% 1 l</P_7><GTIN>05900000000012</GTIN>
                      <P_8A>szt.</P_8A><P_8B>10</P_8B><P_9A>2.00</P_9A><P_11>20.00</P_11><P_12>5</P_12></FaWiersz>
                    <FaWiersz><NrWierszaFa>2</NrWierszaFa><P_7>Jogurt naturalny 400g</P_7><GTIN>5900000000029</GTIN>
                      <P_8A>szt.</P_8A><P_8B>6</P_8B><P_9A>3.00</P_9A><P_11>18.00</P_11><P_12>5</P_12></FaWiersz>
                    <FaWiersz><NrWierszaFa>3</NrWierszaFa><P_7>Ser Liliput 350g /12/</P_7><GTIN>%s</GTIN>
                      <P_8A>szt.</P_8A><P_8B>12</P_8B><P_9A>9.00</P_9A><P_11>108.00</P_11><P_12>5</P_12></FaWiersz>
                    <FaWiersz><NrWierszaFa>4</NrWierszaFa><P_7>Chipsy paprykowe 150g/12</P_7><GTIN>%s</GTIN>
                      <P_8A>op.</P_8A><P_8B>1</P_8B><P_9A>30.00</P_9A><P_11>30.00</P_11><P_12>5</P_12></FaWiersz>
                    <FaWiersz><NrWierszaFa>4</NrWierszaFa><P_7>Chipsy paprykowe 150g/12</P_7><GTIN>%s</GTIN>
                      <P_8A>op.</P_8A><P_8B>2</P_8B><P_9A>30.00</P_9A><P_11>60.00</P_11><P_12>5</P_12>
                      <StanPrzed>1</StanPrzed></FaWiersz>
                    <FaWiersz><NrWierszaFa>5</NrWierszaFa><P_7>Wisła Pianka Pasek Pom pon magic 30g/30</P_7>
                      <GTIN>%s</GTIN><P_8A>szt.</P_8A><P_8B>30</P_8B><P_9A>0.80</P_9A><P_11>24.00</P_11>
                      <P_12>5</P_12></FaWiersz>
                    <FaWiersz><NrWierszaFa>6</NrWierszaFa><P_7>Transport</P_7><Indeks>12345678</Indeks>
                      <P_8A>usł.</P_8A><P_8B>1</P_8B><P_9A>10.00</P_9A><P_11>10.00</P_11><P_12>23</P_12></FaWiersz>
                    <FaWiersz><NrWierszaFa>7</NrWierszaFa><P_7>Masło ekstra 200g</P_7><Indeks>5900000000043</Indeks>
                      <P_8A>szt.</P_8A><P_8B>10</P_8B><P_9A>6.00</P_9A><P_11>60.00</P_11><P_12>5</P_12></FaWiersz>
                    <FaWiersz><NrWierszaFa>8</NrWierszaFa><P_7>Kefir 400g</P_7><Indeks>KF-400</Indeks>
                      <P_8A>szt.</P_8A><P_8B>10</P_8B><P_9A>2.50</P_9A><P_11>25.00</P_11><P_12>5</P_12></FaWiersz>
                  </Fa>
                </Faktura>""".formatted(CHEESE_EAN, CHIPS_EAN, CHIPS_EAN, MARSHMALLOW_EAN);
    }

    private String insertProduct(String importId, String itm8, String ean, String name, String brand, String unit,
            String netContent, String salePrice, String nomenclature) {
        return jdbc.queryForObject("""
                insert into products (store_id, itm8, ean, name, brand, unit, net_content, sale_price, cadencier_price,
                    nomenclature, active, first_seen_import_id, last_seen_import_id)
                values (?::uuid, ?, ?, ?, ?, ?, ?::numeric, ?::numeric, ?::numeric, ?, true, ?::uuid, ?::uuid)
                returning id""",
                String.class, storeId, itm8, ean, name, brand, unit, netContent, salePrice, salePrice, nomenclature,
                importId, importId);
    }

    private String url(String path) {
        return "/api/stores/" + storeId + "/data-entry" + path;
    }

    private String createStore() throws Exception {
        String number = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999_999L));
        String json = mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep " + number + "\",\"active\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    /** Pracownik z rolą o podanych uprawnieniach, przypisany do sklepu. */
    private Cookie employee(String permissions, String store) throws Exception {
        String roleJson = mvc.perform(post("/api/settings/roles").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rola " + UUID.randomUUID() + "\",\"permissions\":" + permissions + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String username = "de" + UUID.randomUUID().toString().substring(0, 8);
        String userId = jdbc.queryForObject("""
                insert into users (username, display_name, password_hash, role_id)
                values (?, 'Pracownik', ?, ?::uuid) returning id""", String.class,
                username, passwordEncoder.encode("haslo-pracownika"), JsonPath.read(roleJson, "$.id"));
        jdbc.update("insert into user_stores (user_id, store_id) values (?::uuid, ?::uuid)", userId, store);
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"haslo-pracownika\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }
}
