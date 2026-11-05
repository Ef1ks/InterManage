package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
import org.springframework.test.web.servlet.MockMvc;

import com.intermanage.backend.auth.security.JwtService;
import com.intermanage.backend.common.component.XlsxWriter;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/**
 * Opracowanie gazetki: wgranie pliku z centrali, artykuły z kartoteki po EAN-ie z gazetki, ręczna podmiana artykułu
 * zapamiętana w sklepie (z cofnięciem do poprzedniej), kody MEA z gazetki na liście do wpisania.
 */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        "app.ksef.sync-enabled=false"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class LeafletIntegrationTests {

    private static final List<String> HEADERS = List.of("LP", "ROK", "TYDZIEŃ", "DATA POCZĄTKU", "DATA KOŃCA",
            "NR MEA", "ITM 8", "ITM 8 PROMO", "EANY", "PRODUKT", "MARKA", "FORMA PROMOCJI", "RODZAJ MEGAPROMOCJI",
            "CENA DLA KLIENTA", "CENY DO SPRAWDZENIA W SYSTEMIE LUB WPROWADZENIA W SYSTEMIE KOMPUTEROWYM W KARTOTECE "
                    + "PRODUKTU\n",
            "OZNACZENIE PRZY PÓŁCE");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    XlsxWriter xlsxWriter;

    private Cookie owner;
    private String storeId;

    @BeforeEach
    void setUp() throws Exception {
        owner = login();
        String number = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999_999L));
        storeId = createStore(number);
        String productImport = jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, total_rows, created_count, updated_count,
                    unchanged_count, missing_count)
                values (?::uuid, 'test.xlsx', 3, 3, 0, 0, 0) returning id""", String.class, storeId);
        insertProduct(productImport, "0000000667810", "8711000517826", "KAWA VELVET 200G", "JACOBS", "32.99");
        insertProduct(productImport, "0000006100001", "2481500000000", "CIASTO WZ", "PIEKARNIA", "29.99");
        insertProduct(productImport, "0000006100002", "2192000000000", "BANAN LUZ", "OWOCE", "6.99");
    }

    @Test
    void uploadsLeafletMatchesArticlesAndListsMeaCodes() throws Exception {
        String json = mvc.perform(multipart(path()).file(file()).cookie(owner).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.year").value(2026))
                .andExpect(jsonPath("$.week").value(44))
                .andExpect(jsonPath("$.lines", hasSize(4)))
                .andExpect(jsonPath("$.lines[0].section").value("PRODUKTY KLUCZOWE"))
                .andExpect(jsonPath("$.lines[0].meaCode").value("81366"))
                .andExpect(jsonPath("$.lines[0].dateFrom").value("2026-10-29"))
                .andExpect(jsonPath("$.lines[0].product.itm8").value("0000000667810"))
                .andExpect(jsonPath("$.lines[0].regularPrice").value(32.99))
                .andExpect(jsonPath("$.lines[0].shelfLabel").value("Karteczka Produkt z gazetki z MERKALYS"))
                .andExpect(jsonPath("$.lines[1].meaCode").value("DDD"))
                .andExpect(jsonPath("$.lines[1].section").value("CUKIERNIA"))
                .andExpect(jsonPath("$.lines[1].product.itm8").value("0000006100001"))
                .andExpect(jsonPath("$.lines[1].price").value(26.99))
                .andExpect(jsonPath("$.lines[2].meaCode").doesNotExist())
                .andExpect(jsonPath("$.lines[2].ean").value("219200"))
                .andExpect(jsonPath("$.lines[2].product").doesNotExist())
                .andExpect(jsonPath("$.lines[3].price").value(-0.2))
                .andExpect(jsonPath("$.pendingMeaCodes", hasSize(1)))
                .andExpect(jsonPath("$.pendingMeaCodes[0]").value("81366"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(json, "$.id");

        mvc.perform(get("/api/stores/" + storeId + "/mea-codes").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending[?(@.code == '81366')].leaflet").value(hasItem(true)))
                .andExpect(jsonPath("$.pending[?(@.code == '81366')].deliveryNumber").value(hasItem("44/2026")));
        mvc.perform(get(path()).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].lineCount").value(4));

        // Banan z gazetki (kod 219200) ręcznie na BANAN LUZ: zapamiętane, widać po ponownym otwarciu.
        String linked = mvc.perform(put(path() + "/links").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ean\":\"219200\",\"itm8\":\"0000006100002\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ean").value("219200"))
                .andExpect(jsonPath("$.product.itm8").value("0000006100002"))
                .andExpect(jsonPath("$.regularPrice").value(6.99))
                .andReturn().getResponse().getContentAsString();
        String firstLink = JsonPath.read(linked, "$.linkId");
        mvc.perform(get(path() + "/" + id).cookie(owner))
                .andExpect(jsonPath("$.lines[2].product.itm8").value("0000006100002"))
                .andExpect(jsonPath("$.lines[2].linkId").value(firstLink));

        // Podmiana na inny artykuł i „Cofnij”: wraca poprzednie przypięcie.
        String replaced = mvc.perform(put(path() + "/links").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ean\":\"219200\",\"itm8\":\"0000006100001\"}"))
                .andExpect(jsonPath("$.product.itm8").value("0000006100001"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(delete(path() + "/links/" + JsonPath.read(replaced, "$.linkId")).cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkId").value(firstLink))
                .andExpect(jsonPath("$.product.itm8").value("0000006100002"));

        // Usunięcie przypięcia: EAN bez artykułu, „Przywróć” oddaje przypięcie.
        mvc.perform(delete(path() + "/links/" + firstLink).cookie(owner).with(csrf()))
                .andExpect(jsonPath("$.linkId").doesNotExist())
                .andExpect(jsonPath("$.product").doesNotExist());
        mvc.perform(post(path() + "/links/" + firstLink + "/restore").cookie(owner).with(csrf()))
                .andExpect(jsonPath("$.linkId").value(firstLink))
                .andExpect(jsonPath("$.product.itm8").value("0000006100002"));

        // Ponowne wgranie tej samej gazetki: nowy wpis, poprzedni ze znacznikiem.
        mvc.perform(multipart(path()).file(file()).cookie(owner).with(csrf())).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select count(*) from promo_leaflets where store_id = ?::uuid "
                + "and replaced_at is not null", Integer.class, storeId)).isEqualTo(1);
        mvc.perform(get(path() + "/" + id).cookie(owner)).andExpect(status().isNotFound());
    }

    @Test
    void olderLeafletDoesNotBecomeCurrentNorAddMeaCodes() throws Exception {
        mvc.perform(multipart(path()).file(file()).cookie(owner).with(csrf())).andExpect(status().isCreated());
        String json = mvc.perform(multipart(path()).file(leaflet(2026, 20)).cookie(owner).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pendingMeaCodes", hasSize(0)))
                .andReturn().getResponse().getContentAsString();

        // Na górze listy zostaje gazetka 44, choć 20 wgrano później.
        mvc.perform(get(path()).cookie(owner))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].week").value(44))
                .andExpect(jsonPath("$[1].id").value((String) JsonPath.read(json, "$.id")));
        mvc.perform(get("/api/stores/" + storeId + "/mea-codes").cookie(owner))
                .andExpect(jsonPath("$.pending[?(@.code == '81366')]", hasSize(1)))
                .andExpect(jsonPath("$.pending[?(@.code == '70000')]", hasSize(0)));
    }

    @Test
    void rejectsFileWithoutLeafletColumns() throws Exception {
        MockMultipartFile other = new MockMultipartFile("file", "inny.xlsx", "application/octet-stream",
                xlsxWriter.write("Arkusz1", List.of(new XlsxWriter.Column("Kod", 10)),
                        List.of(List.of("123"))));
        mvc.perform(multipart(path()).file(other).cookie(owner).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    private String path() {
        return "/api/stores/" + storeId + "/leaflets";
    }

    /**
     * Gazetka z działami i czterema pozycjami: kawa z kodem MEA, ciasto DDD, banan bez MEA z kodem wagi, którego nie
     * ma w kartotece, i wędzonka z rabatem -20%.
     */
    private MockMultipartFile file() {
        List<XlsxWriter.Column> columns = HEADERS.stream().map(header -> new XlsxWriter.Column(header, 12)).toList();
        List<List<Object>> rows = List.of(
                section("1", "PRODUKTY KLUCZOWE"),
                row("2", "29.10.2026", nextWeek(), "81366", "667810", "8711000517826", "FM.X.KAWA VELVET 200G INST.",
                        "JACOBS", new BigDecimal("24.99"), "Karteczka Produkt z gazetki z MERKALYS"),
                section("3", "CUKIERNIA"),
                row("4", "29.10.2026", "01.11.2026", "DDD", null, "2481500000000", "*CIASTO WZ", null,
                        new BigDecimal("26.99"), "PRODUKT Z GAZETKI \nlub jeśli jest cena skreślona \nKARTECZKA PROMO"),
                row("5", "30.10.2026", "31.10.2026", null, null, "219200", "FM.G.SC.X.M.BANAN LUZ", null,
                        new BigDecimal("2.99"), "Karteczka PROMO z GENERATORA OZNACZEŃ "),
                row("6", "29.10.2026", "04.11.2026", "DDD", null, "2440530000000", "WĘDZONKA KROTOSZYŃSKA", null,
                        new BigDecimal("-0.2"), "NADSTAWKA PROMO"));
        return new MockMultipartFile("file", "UTRZYMYWANIE_CEN_Gazetka_nr_44_2026_+_ceny.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsxWriter.write("Arkusz", columns, rows));
    }

    /** Koniec promocji za tydzień, żeby kod MEA z gazetki był do wpisania bez względu na dzień testu. */
    private static String nextWeek() {
        return LocalDate.now(ZoneId.of("Europe/Warsaw")).plusDays(7).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
    }

    /** Dawna gazetka z jedną, zakończoną promocją z kodem MEA 70000. */
    private MockMultipartFile leaflet(int year, int week) {
        List<XlsxWriter.Column> columns = HEADERS.stream().map(header -> new XlsxWriter.Column(header, 12)).toList();
        List<Object> line = new ArrayList<>(row("1", "14.05.2026", "20.05.2026", "70000", "667810", "8711000517826",
                "FM.X.KAWA VELVET 200G INST.", "JACOBS", new BigDecimal("24.99"), "NADSTAWKA PROMO"));
        line.set(1, String.valueOf(year));
        line.set(2, String.valueOf(week));
        return new MockMultipartFile("file", "UTRZYMYWANIE_CEN_Gazetka_nr_" + week + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsxWriter.write("Arkusz", columns, List.of(line)));
    }

    private static List<Object> section(String lp, String name) {
        return Arrays.asList(lp, null, null, null, null, null, null, null, null, name, null, null, null, null, null,
                null);
    }

    private static List<Object> row(String lp, String from, String to, String mea, String itm8, String ean,
            String name, String brand, BigDecimal price, String shelfLabel) {
        return Arrays.asList(lp, "2026", "44", from, to, mea, itm8, null, ean, name, brand, null, null, null, price,
                shelfLabel);
    }

    private void insertProduct(String importId, String itm8, String ean, String name, String brand,
            String salePrice) {
        jdbc.update("""
                insert into products (store_id, itm8, ean, name, brand, unit, net_content, sale_price, active,
                    first_seen_import_id, last_seen_import_id)
                values (?::uuid, ?, ?, ?, ?, 'U', 1, ?::numeric, true, ?::uuid, ?::uuid)""",
                storeId, itm8, ean, name, brand, salePrice, importId, importId);
    }

    private String createStore(String number) throws Exception {
        String json = mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep " + number + "\",\"active\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }
}
