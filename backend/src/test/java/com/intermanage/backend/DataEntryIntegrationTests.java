package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
                // Wiersz "przed korektą" pominięty.
                .andExpect(jsonPath("$.lines.length()").value(6))
                // GTIN-14 z zerem na początku = EAN-13 z kartoteki.
                .andExpect(jsonPath("$.lines[0].ean").value("5900000000012"))
                .andExpect(jsonPath("$.lines[0].product.itm8").value("0000000000101"))
                .andExpect(jsonPath("$.lines[0].product.salePrice").value(3.49))
                .andExpect(jsonPath("$.lines[0].product.subfamilyCode").value("254201"))
                .andExpect(jsonPath("$.lines[0].unit").value("L"))
                .andExpect(jsonPath("$.lines[0].vatPercent").value(5))
                .andExpect(jsonPath("$.lines[0].unitNet").value(2.0))
                // Dawny EAN artykułu (para z product_eans).
                .andExpect(jsonPath("$.lines[1].product.itm8").value("0000000000102"))
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
                .andExpect(jsonPath("$.lines[5].unit").value("szt."))
                .andExpect(jsonPath("$.lines[5].netContent").value(1))
                .andExpect(jsonPath("$.lines[5].vatPercent").value(23));

        mvc.perform(get(url("/products?ean=05900000000012&ean=5900000000029&ean=123")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get(url("/nomenclatures")).cookie(owner))
                .andExpect(jsonPath("$[?(@.code == '254302')].name").value(hasItem("SERY ZOLTE PLASTRY")));
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
                    <FaWiersz><NrWierszaFa>6</NrWierszaFa><P_7>Transport</P_7><P_8A>usł.</P_8A><P_8B>1</P_8B>
                      <P_9A>10.00</P_9A><P_11>10.00</P_11><P_12>23</P_12></FaWiersz>
                  </Fa>
                </Faktura>""".formatted(CHEESE_EAN, CHIPS_EAN, CHIPS_EAN, MARSHMALLOW_EAN);
    }

    private String insertProduct(String importId, String itm8, String ean, String name, String brand, String unit,
            String netContent, String salePrice, String nomenclature) {
        return jdbc.queryForObject("""
                insert into products (store_id, itm8, ean, name, brand, unit, net_content, sale_price, nomenclature,
                    active, first_seen_import_id, last_seen_import_id)
                values (?::uuid, ?, ?, ?, ?, ?, ?::numeric, ?::numeric, ?, true, ?::uuid, ?::uuid) returning id""",
                String.class, storeId, itm8, ean, name, brand, unit, netContent, salePrice, nomenclature, importId,
                importId);
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
