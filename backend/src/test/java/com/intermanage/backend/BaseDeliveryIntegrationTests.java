package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.intermanage.backend.auth.security.JwtService;
import com.intermanage.backend.common.component.XlsWriter;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/**
 * Dostawa z bazy: wgranie pliku .xls (czytnik BIFF8 na pliku z {@link XlsWriter}), artykuły z kartoteki po ITM8
 * i po EAN-ie, nośniki, ponowne wgranie (znacznik), raport krótkich terminów i kody MEA.
 */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        "app.ksef.sync-enabled=false"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class BaseDeliveryIntegrationTests {

    private static final List<String> HEADERS = List.of("Sklep", "Data_Bl", "Nr_Bl", "NrNosnika", "ITM8",
            "Nazwa_Produktu", "Marka", "PAK", "Kaucja", "Data_DLV", "Dni_dla_Sklepu", "EAN", "PKWiU", "Spolka",
            "Kod_MEA", "Nr_Zam_ITM", "Coli_Do_Przyg", "Coli_Przyg", "Ciezar_REEL", "C_Cesja", "C_Publi", "VAT",
            "Kwota_VAT", "Kwota_HT", "Kwota_TTC", "Kod_Sub", "Rejon", "Rodz", "Pod_Rodz", "Typ_Sprz", "Jed_Sprz", "CN",
            "GTU", "SP", "CNUF");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    XlsWriter xlsWriter;

    private Cookie owner;
    private String storeId;
    private String storeNumber;

    @BeforeEach
    void setUp() throws Exception {
        owner = login();
        storeNumber = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999_999L));
        storeId = createStore(storeNumber);
        String productImport = jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, total_rows, created_count, updated_count,
                    unchanged_count, missing_count)
                values (?::uuid, 'test.xlsx', 2, 2, 0, 0, 0) returning id""", String.class, storeId);
        // Piwo w puszce po ITM8 z pliku (kartoteka bez kaucji) i 7 UP pod innym ITM8 w kartotece (po EAN-ie).
        insertProduct(productImport, "0000061111111", "5901359174181", "PIWO TYSKIE 4X0,5L PUSZ", "TYSKIE", "L", "2",
                "17.96", "69 01 01", 0);
        insertProduct(productImport, "0000006893790", "5900497047708", "7 UP 0,5L PET", "7UP", "L", "0.5", "4.69",
                "66 10 05", 80);
    }

    @Test
    void uploadsDeliveryWithCatalogArticlesAndCarriers() throws Exception {
        String json = mvc.perform(multipart(path()).file(file(storeNumber)).cookie(owner).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value("09516050082"))
                .andExpect(jsonPath("$.deliveryDate").value("2026-11-02"))
                .andExpect(jsonPath("$.carriers", hasSize(2)))
                .andExpect(jsonPath("$.carriers[0].number").value("42799515"))
                .andExpect(jsonPath("$.carriers[0].lineCount").value(3))
                .andExpect(jsonPath("$.lines", hasSize(4)))
                .andExpect(jsonPath("$.lines[0].itm8").value("61111111"))
                .andExpect(jsonPath("$.lines[0].product.itm8").value("0000061111111"))
                .andExpect(jsonPath("$.lines[0].catalogDeposit").value(0))
                .andExpect(jsonPath("$.lines[0].deposit").value("PUS"))
                .andExpect(jsonPath("$.lines[0].quantity").value(24))
                .andExpect(jsonPath("$.lines[1].itm8").value("61652855"))
                .andExpect(jsonPath("$.lines[1].product.itm8").value("0000006893790"))
                .andExpect(jsonPath("$.lines[1].catalogDeposit").value(80))
                .andExpect(jsonPath("$.lines[2].product").doesNotExist())
                .andExpect(jsonPath("$.lines[2].shortDated").value(true))
                .andExpect(jsonPath("$.lines[2].requiredBestBefore").value("2026-12-02"))
                .andExpect(jsonPath("$.lines[2].regionCode").value("14"))
                .andExpect(jsonPath("$.lines[2].retailPrice").value(4.99))
                .andExpect(jsonPath("$.lines[3].quantity").value(0))
                .andExpect(jsonPath("$.lines[3].shortDated").value(false))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(json, "$.id");

        mvc.perform(get(path()).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].lineCount").value(4));
        mvc.perform(get(path() + "/" + id).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines", hasSize(4)));

        // Ponowne wgranie tego samego bonu: nowy wpis, poprzedni ze znacznikiem.
        String again = mvc.perform(multipart(path()).file(file(storeNumber)).cookie(owner).with(csrf()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(jdbc.queryForObject("select count(*) from base_deliveries where store_id = ?::uuid "
                + "and replaced_at is not null", Integer.class, storeId)).isEqualTo(1);
        mvc.perform(get(path() + "/" + id).cookie(owner)).andExpect(status().isNotFound());

        byte[] pdf = mvc.perform(get(path() + "/" + JsonPath.read(again, "$.id") + "/short-dates/pdf").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }

    /** Ten sam bon z nagłówkami ze spacjami i polskimi znakami („Data Bl”, „Nr Nośnika”, „Spółka”). */
    @Test
    void uploadsDeliveryWithSpacedPolishHeaders() throws Exception {
        List<String> headers = HEADERS.stream().map(header -> switch (header) {
            case "NrNosnika" -> "Nr Nośnika";
            case "Spolka" -> "Spółka";
            default -> header.replace('_', ' ');
        }).toList();
        // Pod liniami stopka z podsumowaniem stawek VAT, jak w pliku zapisanym przez Excela.
        List<List<Object>> rows = new ArrayList<>(rowsOf(storeNumber));
        rows.add(Arrays.asList((Object) null));
        rows.add(List.of("RAZEM", "VAT", "WART.NETT", "WART.VAT", "WART.BRUTT"));
        rows.add(Arrays.asList(null, "23", "115141.12", "26482.24", "141623.47"));
        rows.add(Arrays.asList(null, "SUMA", "189365.18", "30514.17", "219879.56"));
        mvc.perform(multipart(path()).file(new MockMultipartFile("file", "dostawa_baza.xls",
                        "application/vnd.ms-excel", xlsWriter.write("Arkusz1", headers, rows)))
                        .cookie(owner).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value("09516050082"))
                .andExpect(jsonPath("$.carriers", hasSize(2)))
                .andExpect(jsonPath("$.lines", hasSize(4)))
                .andExpect(jsonPath("$.lines[0].supplierCode").value("538"));
    }

    /** System bazy zapisuje bon jako tabelę HTML z końcówką .xls (Excel ostrzega o innym formacie). */
    @Test
    void uploadsDeliverySavedAsHtmlTable() throws Exception {
        mvc.perform(multipart(path()).file(htmlFile(storeNumber)).cookie(owner).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value("09516050082"))
                .andExpect(jsonPath("$.deliveryDate").value("2026-11-02"))
                .andExpect(jsonPath("$.carriers", hasSize(2)))
                .andExpect(jsonPath("$.lines", hasSize(4)))
                .andExpect(jsonPath("$.lines[0].product.itm8").value("0000061111111"))
                .andExpect(jsonPath("$.lines[0].quantity").value(24))
                .andExpect(jsonPath("$.lines[1].name").value("G.SC.X.RM.M.7 UP 0.5L PET"))
                .andExpect(jsonPath("$.lines[2].retailPrice").value(4.99))
                .andExpect(jsonPath("$.lines[3].quantity").value(0));

        // Sama ramka strony („Strona sieci Web” z Excela) nie ma danych: komunikat wskazuje plik arkusza.
        byte[] frameset = ("<html><head><meta name=ProgId content=Excel.Sheet></head><frameset rows=\"*,39\">"
                + "<frame src=\"bon_pliki/sheet001.htm\" name=\"frSheet\"></frameset></html>").getBytes();
        mvc.perform(multipart(path()).file(new MockMultipartFile("file", "bon.xls", "application/vnd.ms-excel",
                        frameset)).cookie(owner).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("bon_pliki/sheet001.htm")));
    }

    @Test
    void rejectsFileOfOtherStoreAndWrongColumns() throws Exception {
        mvc.perform(multipart(path()).file(file("99999")).cookie(owner).with(csrf()))
                .andExpect(status().isConflict());
        byte[] wrong = xlsWriter.write("Arkusz1", List.of("EAN", "Nazwa"), List.of(List.of("590", "X")));
        mvc.perform(multipart(path()).file(new MockMultipartFile("file", "inny.xls", "application/vnd.ms-excel",
                        wrong)).cookie(owner).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void confirmsMeaCodesAndUndoesConfirmation() throws Exception {
        mvc.perform(multipart(path()).file(file(storeNumber)).cookie(owner).with(csrf()))
                .andExpect(status().isCreated());
        String meaPath = "/api/stores/" + storeId + "/mea-codes";
        mvc.perform(get(meaPath).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending", hasSize(2)))
                .andExpect(jsonPath("$.pending[0].deliveryNumber").value("09516050082"));

        String json = mvc.perform(post(meaPath + "/confirmations").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"codes\":[\"81390\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending", hasSize(1)))
                .andExpect(jsonPath("$.pending[0].code").value("60003"))
                .andExpect(jsonPath("$.confirmations", hasSize(1)))
                .andExpect(jsonPath("$.confirmations[0].codes[0]").value("81390"))
                .andReturn().getResponse().getContentAsString();
        String confirmation = JsonPath.read(json, "$.confirmations[0].id");

        // Kod już wpisany nie trafia do kolejnego potwierdzenia.
        mvc.perform(post(meaPath + "/confirmations").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"codes\":[\"81390\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmations", hasSize(1)));

        mvc.perform(delete(meaPath + "/confirmations/" + confirmation).cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending", hasSize(2)))
                .andExpect(jsonPath("$.confirmations[0].deletedAt").exists());
        mvc.perform(post(meaPath + "/confirmations/" + confirmation + "/restore").cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending", hasSize(1)))
                .andExpect(jsonPath("$.confirmations[0].deletedAt").doesNotExist());
    }

    @Test
    void needsDataEntryAccess() throws Exception {
        Cookie dataEntry = employee("{\"DATA_ENTRY\":\"VIEW\"}");
        Cookie sales = employee("{\"SALES\":\"EDIT\"}");
        mvc.perform(get(path()).cookie(dataEntry)).andExpect(status().isOk());
        mvc.perform(multipart(path()).file(file(storeNumber)).cookie(dataEntry).with(csrf()))
                .andExpect(status().isCreated());
        mvc.perform(get(path()).cookie(sales)).andExpect(status().isForbidden());
        mvc.perform(get("/api/stores/" + storeId + "/mea-codes").cookie(sales)).andExpect(status().isForbidden());
    }

    private String path() {
        return "/api/stores/" + storeId + "/base-deliveries";
    }

    /**
     * Bon z czterema liniami: piwo po ITM8, 7 UP po EAN-ie, nowa sałata z krótkim terminem i towar, który nie
     * przyjechał (0 przygotowanych kartonów), na drugim nośniku.
     */
    private MockMultipartFile file(String store) {
        return new MockMultipartFile("file", "09516050082.xls", "application/vnd.ms-excel",
                xlsWriter.write("Arkusz1", HEADERS, rowsOf(store)));
    }

    private static List<List<Object>> rowsOf(String store) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(line(store, "42799515", "61111111", "PIWO TYSKIE 4X0.5L PU", "TYSKIE", "6", "PUS", "2027-04-30",
                "72", "5901359174181", "81390", "4", "4", "9.99", "17.96", "23", "69", "01", "01", "2", "L"));
        rows.add(line(store, "42799515", "61652855", "G.SC.X.RM.M.7 UP 0.5L PET", "7UP", "12", "PLA", "2027-03-31",
                "72", "5900497047708", null, "2", "2", "3.19", "4.69", "23", "66", "10", "05", "0.5", "L"));
        rows.add(line(store, "42799515", "61700001", "SALATA LODOWA", "", "10", "NIE", "2026-11-10", "30",
                "2000000000001", "81390", "1", "1", "2.10", "4.99", "5", "14", "01", "01", "1", "U"));
        rows.add(line(store, "42765167", "61700002", "KUBEK SWIATECZNY", "13 KAT", "6", "NIE", "0000-00-00", "0",
                "0000000000000", "60003", "1", "0", "12.07", "19.99", "23", "54", "06", "04", "1", "U"));
        return rows;
    }

    /** Te same linie co {@link #file(String)} jako tabela HTML (windows-1250, przecinek dziesiętny, encje). */
    private MockMultipartFile htmlFile(String store) {
        StringBuilder html = new StringBuilder("<html><head><meta http-equiv=Content-Type "
                + "content=\"text/html; charset=windows-1250\"></head><body><table border=1>\r\n<tr>");
        HEADERS.forEach(header -> html.append("<th>").append(header).append("</th>"));
        html.append("</tr>\r\n");
        for (List<Object> row : rowsOf(store)) {
            html.append("<tr>");
            for (Object value : row) {
                String text = value == null ? "" : value.toString().replace("&", "&amp;");
                if (value instanceof BigDecimal) {
                    text = text.replace('.', ',');
                }
                html.append("<td x:str>").append(text.isEmpty() ? "&nbsp;" : text).append("</td>");
            }
            html.append("</tr>\r\n");
        }
        html.append("</table></body></html>");
        return new MockMultipartFile("file", "09516050082.xls", "application/vnd.ms-excel",
                html.toString().getBytes(java.nio.charset.Charset.forName("windows-1250")));
    }

    private static List<Object> line(String store, String carrier, String itm8, String name, String brand, String pack,
            String deposit, String bestBefore, String days, String ean, String mea, String ordered, String prepared,
            String price, String retail, String vat, String region, String family, String subfamily, String content,
            String unit) {
        BigDecimal net = new BigDecimal(prepared).multiply(new BigDecimal(pack)).multiply(new BigDecimal(price));
        BigDecimal vatValue = net.multiply(new BigDecimal(vat)).movePointLeft(2).setScale(2,
                RoundingMode.HALF_UP);
        return Arrays.asList(new BigDecimal(store), "2026-11-02", "09516050082", carrier, itm8, name, brand, pack,
                deposit, bestBefore, new BigDecimal(days), ean, "----------", "538", mea, "10023304",
                new BigDecimal(ordered), new BigDecimal(prepared), BigDecimal.ZERO, new BigDecimal(price),
                new BigDecimal(retail), new BigDecimal(vat), vatValue, net, net.add(vatValue), null, region, family,
                subfamily, new BigDecimal(content), unit, "22030001", "-----", "N", "00538");
    }

    private void insertProduct(String importId, String itm8, String ean, String name, String brand, String unit,
            String netContent, String salePrice, String nomenclature, int deposit) {
        jdbc.update("""
                insert into products (store_id, itm8, ean, name, brand, unit, net_content, sale_price, nomenclature,
                    deposit_code, active, first_seen_import_id, last_seen_import_id)
                values (?::uuid, ?, ?, ?, ?, ?, ?::numeric, ?::numeric, ?, ?, true, ?::uuid, ?::uuid)""",
                storeId, itm8, ean, name, brand, unit, netContent, salePrice, nomenclature, deposit, importId,
                importId);
    }

    private String createStore(String number) throws Exception {
        String json = mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep " + number + "\",\"active\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private Cookie employee(String permissions) throws Exception {
        String roleJson = mvc.perform(post("/api/settings/roles").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rola " + UUID.randomUUID() + "\",\"permissions\":" + permissions + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String username = "bd" + UUID.randomUUID().toString().substring(0, 8);
        String userId = jdbc.queryForObject("""
                insert into users (username, display_name, password_hash, role_id)
                values (?, 'Pracownik', ?, ?::uuid) returning id""", String.class,
                username, passwordEncoder.encode("haslo-pracownika"), JsonPath.read(roleJson, "$.id"));
        jdbc.update("insert into user_stores (user_id, store_id) values (?::uuid, ?::uuid)", userId, storeId);
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
