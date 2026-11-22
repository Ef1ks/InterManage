package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.intermanage.backend.auth.security.JwtService;
import com.intermanage.backend.common.component.StoreTime;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** Zamówienie MEA do ATHOS: ZIP R… z plikiem V…, potwierdzenie zapisu i blokada po terminie rezerwacji. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class MeaOrderIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void savesOrderFileAndMarksItAfterSave() throws Exception {
        Cookie owner = login();
        String storeNumber = String.valueOf(ThreadLocalRandom.current().nextInt(12_000, 19_999));
        String storeId = createStore(owner, storeNumber);
        String number = String.valueOf(ThreadLocalRandom.current().nextInt(70_000, 79_999));
        String document = jdbc.queryForObject("""
                insert into mea_documents (number, title, promo_from, promo_to, reservation_from, reservation_to,
                    dispatch_1, file_path, line_count, fetched_at, attempts)
                values (?, 'MEA TEST', current_date + 30, current_date + 36, current_date - 2, current_date + 2,
                    current_date + 27, 'x.xls', 2, now(), 1) returning id""", String.class, number);
        jdbc.update("""
                insert into mea_document_lines (document_id, lp, mea_itm8, name) values
                    (?::uuid, 1, '61665965', 'SER'), (?::uuid, 2, '61665966', 'CIASTO')""", document, document);

        MockHttpServletResponse response = mvc.perform(post("/api/stores/" + storeId + "/mea/documents/" + number
                        + "/athos-order").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itm8\":\"61665966\",\"colis\":[3]}]}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(
                        "R" + number + storeNumber.substring(0, 2) + "." + storeNumber.substring(2))))
                .andReturn().getResponse();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(zip.getNextEntry().getName())
                    .isEqualTo("V" + number + storeNumber.substring(0, 2) + "." + storeNumber.substring(2));
            assertThat(new String(zip.readAllBytes(), StandardCharsets.US_ASCII)).isEqualTo(
                    number + "61665965" + "0".repeat(32) + number + "61665966" + "0003" + "0".repeat(28));
        }
        String orderId = response.getHeader("X-Mea-Order-Id");

        // Bez potwierdzenia zapisu zamówienia nie ma w dokumencie.
        mvc.perform(get("/api/stores/" + storeId + "/mea/documents/" + number).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(0));
        mvc.perform(post("/api/stores/" + storeId + "/mea/athos-orders/" + orderId + "/saved").cookie(owner)
                        .with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/stores/" + storeId + "/mea/documents/" + number).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].positions").value(1))
                .andExpect(jsonPath("$.orders[0].colis").value(3));

        // Rozrzut bez daty i zamknięta rezerwacja.
        mvc.perform(post("/api/stores/" + storeId + "/mea/documents/" + number + "/athos-order").cookie(owner)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itm8\":\"61665966\",\"colis\":[0,2]}]}"))
                .andExpect(status().isBadRequest());
        jdbc.update("update mea_documents set reservation_to = current_date - 1 where id = ?::uuid", document);
        mvc.perform(post("/api/stores/" + storeId + "/mea/documents/" + number + "/athos-order").cookie(owner)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itm8\":\"61665966\",\"colis\":[1]}]}"))
                .andExpect(status().isConflict());
    }

    @Test
    void queuesOrdersPerStoreAndSendsThemLater() throws Exception {
        Cookie owner = login();
        String storeNumber = String.valueOf(ThreadLocalRandom.current().nextInt(12_000, 19_999));
        String storeId = createStore(owner, storeNumber);
        String otherStoreId = createStore(owner,
                String.valueOf(ThreadLocalRandom.current().nextInt(12_000, 19_999)));
        String number = String.valueOf(ThreadLocalRandom.current().nextInt(80_000, 89_999));
        String document = jdbc.queryForObject("""
                insert into mea_documents (number, title, promo_from, promo_to, reservation_from, reservation_to,
                    dispatch_1, file_path, line_count, fetched_at, attempts)
                values (?, 'MEA KOLEJKA', current_date + 30, current_date + 36, current_date - 2, current_date + 2,
                    current_date + 27, 'x.xls', 1, now(), 1) returning id""", String.class, number);
        jdbc.update("insert into mea_document_lines (document_id, lp, mea_itm8, name) values (?::uuid, 1, '61665965', 'SER')",
                document);
        String base = "/api/stores/" + storeId + "/mea";

        mvc.perform(post(base + "/documents/" + number + "/athos-queue").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itm8\":\"61665965\",\"colis\":[2]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("MEA KOLEJKA"))
                .andExpect(jsonPath("$.colis").value(2));
        // Drugi plik tego MEA zastępuje pierwszy.
        String second = mvc.perform(post(base + "/documents/" + number + "/athos-queue").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itm8\":\"61665965\",\"colis\":[5]}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(second, "$.id");
        mvc.perform(get(base + "/athos-queue").cookie(owner))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].colis").value(5));
        mvc.perform(get(base + "/documents/" + number).cookie(owner))
                .andExpect(jsonPath("$.queued.colis").value(5))
                .andExpect(jsonPath("$.orders.length()").value(0));
        // Kolejka jest osobna dla każdego sklepu.
        mvc.perform(get("/api/stores/" + otherStoreId + "/mea/athos-queue").cookie(owner))
                .andExpect(jsonPath("$.length()").value(0));

        // Usunięcie z kolejki i przywrócenie.
        mvc.perform(delete(base + "/athos-queue/" + orderId).cookie(owner).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get(base + "/athos-queue").cookie(owner)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post(base + "/athos-queue/" + orderId + "/restore").cookie(owner).with(csrf()))
                .andExpect(status().isNoContent());

        // Wysyłka: plik z kolejki, potem potwierdzenie zapisu w folderze.
        MockHttpServletResponse file = mvc.perform(get(base + "/athos-queue/" + orderId + "/file").cookie(owner))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file.getContentAsByteArray()))) {
            assertThat(zip.getNextEntry().getName())
                    .isEqualTo("V" + number + storeNumber.substring(0, 2) + "." + storeNumber.substring(2));
            assertThat(new String(zip.readAllBytes(), StandardCharsets.US_ASCII))
                    .isEqualTo(number + "61665965" + "0005" + "0".repeat(28));
        }
        mvc.perform(post(base + "/athos-orders/" + orderId + "/saved").cookie(owner).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get(base + "/athos-queue").cookie(owner)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(base + "/documents/" + number).cookie(owner))
                .andExpect(jsonPath("$.orders[0].colis").value(5))
                .andExpect(jsonPath("$.queued").doesNotExist());
        mvc.perform(get(base + "/athos-queue/" + orderId + "/file").cookie(owner))
                .andExpect(status().isConflict());

        // Po terminie rezerwacji plik z kolejki już nie wychodzi.
        String late = JsonPath.read(mvc.perform(post(base + "/documents/" + number + "/athos-queue").cookie(owner)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itm8\":\"61665965\",\"colis\":[1]}]}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        jdbc.update("update mea_documents set reservation_to = current_date - 1 where id = ?::uuid", document);
        mvc.perform(get(base + "/athos-queue/" + late + "/file").cookie(owner)).andExpect(status().isConflict());
    }

    @Test
    void articleCardShowsUpcomingMeaWithOrderedColis() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner, String.valueOf(ThreadLocalRandom.current().nextInt(12_000, 19_999)));
        String itm8 = String.valueOf(ThreadLocalRandom.current().nextInt(62_000_000, 62_999_999));
        String other = String.valueOf(Integer.parseInt(itm8) + 1);
        String number = String.valueOf(ThreadLocalRandom.current().nextInt(60_000, 69_999));
        String document = jdbc.queryForObject("""
                insert into mea_documents (number, title, promo_from, promo_to, reservation_from, reservation_to,
                    dispatch_1, dispatch_2, file_path, line_count, fetched_at, attempts)
                values (?, 'MEA KARTA', current_date + 30, current_date + 36, current_date - 2, current_date + 2,
                    current_date + 27, current_date + 29, 'x.xls', 2, now(), 1) returning id""", String.class, number);
        jdbc.update("""
                insert into mea_document_lines (document_id, lp, mea_itm8, name) values
                    (?::uuid, 1, ?, 'SER'), (?::uuid, 2, ?, 'CIASTO')""", document, itm8, document, other);
        // Zakończona promocja nie jest już nadchodzącą gazetką.
        String ended = jdbc.queryForObject("""
                insert into mea_documents (number, title, promo_from, promo_to, reservation_to, dispatch_1,
                    file_path, line_count, fetched_at, attempts)
                values (?, 'MEA STARE', current_date - 20, current_date - 1, current_date - 30, current_date - 22,
                    'x.xls', 1, now(), 1) returning id""", String.class, String.valueOf(Integer.parseInt(number) + 1));
        jdbc.update("insert into mea_document_lines (document_id, lp, mea_itm8, name) values (?::uuid, 1, ?, 'SER')",
                ended, itm8);
        String card = "/api/stores/" + storeId + "/reports/articles/00000" + itm8 + "/mea";

        mvc.perform(get(card).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].number").value(number))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].dispatches.length()").value(2))
                .andExpect(jsonPath("$[0].dispatches[0].colis").doesNotExist());

        String orderId = mvc.perform(post("/api/stores/" + storeId + "/mea/documents/" + number + "/athos-order")
                        .cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itm8\":\"" + itm8 + "\",\"colis\":[3,1]}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("X-Mea-Order-Id");
        mvc.perform(post("/api/stores/" + storeId + "/mea/athos-orders/" + orderId + "/saved").cookie(owner)
                        .with(csrf()))
                .andExpect(status().isNoContent());

        mvc.perform(get(card).cookie(owner))
                .andExpect(jsonPath("$[0].status").value("ORDERED"))
                .andExpect(jsonPath("$[0].dispatches[0].colis").value(3))
                .andExpect(jsonPath("$[0].dispatches[1].colis").value(1));
        mvc.perform(get("/api/stores/" + storeId + "/reports/articles/" + other + "/mea").cookie(owner))
                .andExpect(jsonPath("$[0].status").value("NOT_ORDERED"))
                .andExpect(jsonPath("$[0].dispatches[0].colis").value(0));
    }

    @Test
    void articleSalesComeInThursdayToWednesdayWeeksWithPromotions() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner, String.valueOf(ThreadLocalRandom.current().nextInt(12_000, 19_999)));
        String itm8 = "00000" + ThreadLocalRandom.current().nextInt(63_000_000, 63_999_999);
        LocalDate day = LocalDate.now(StoreTime.ZONE).minusDays(2);
        String salesImport = jdbc.queryForObject("""
                insert into sales_imports (store_id, file_name, report_generated_at, period_from, period_to,
                    total_rows, created_count, existing_count, conflict_count)
                values (?::uuid, 'sprzedaz.xlsx', now(), ?, ?, 1, 1, 0, 0) returning id""",
                String.class, storeId, day, day);
        jdbc.update("""
                insert into sales (store_id, sale_date, itm8, ean, quantity, cost_value, sale_value_gross, vat_value,
                    margin_value, import_id)
                values (?::uuid, ?, ?, '5900000000001', 3, 10, 30, 0, 20, ?::uuid)""",
                storeId, day, itm8, salesImport);
        jdbc.update("""
                insert into till_promotions (store_id, itm8, promo_from, promo_to, promo_price, regular_price, file_time)
                values (?::uuid, ?, ?, ?, 9.99, 12.99, now())""", storeId, itm8, day.minusDays(1), day);

        int toWednesday = (DayOfWeek.WEDNESDAY.getValue() - day.getDayOfWeek().getValue() + 7) % 7;
        LocalDate to = day.plusDays(toWednesday);
        // Bez zer wiodących, jak bywa w kartotece; odpowiedź ma ITM8 na 13 cyfrach.
        mvc.perform(get("/api/stores/" + storeId + "/mea/article-sales").param("itm8", itm8.replaceFirst("^0+", ""))
                        .param("weeks", "4").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.to").value(to.toString()))
                .andExpect(jsonPath("$.from").value(to.minusDays(27).toString()))
                .andExpect(jsonPath("$.lastSaleDate").value(day.toString()))
                .andExpect(jsonPath("$.articles[0].itm8").value(itm8))
                .andExpect(jsonPath("$.articles[0].days[0].date").value(day.toString()))
                .andExpect(jsonPath("$.articles[0].days[0].quantity").value(3))
                .andExpect(jsonPath("$.articles[0].days[0].gross").value(30))
                .andExpect(jsonPath("$.articles[0].promos[0].source").value("TILL"));
    }

    private String createStore(Cookie owner, String number) throws Exception {
        String json = mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep MEA " + number + "\",\"active\":true}"))
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
