package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.intermanage.backend.auth.security.JwtService;
import com.intermanage.backend.cash.service.CashClosingService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/**
 * Raport kasowy: pieczątka wymagana do wystawienia, numeracja KP i KW w roku, stan kasy nie może spaść poniżej zera,
 * anulowanie z historią, raport ze stanem początkowym i wydruki.
 */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class CashIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    CashClosingService closings;

    private Cookie owner;
    private String store;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        owner = login();
        store = createStore();
        today = LocalDate.now(ZoneId.of("Europe/Warsaw"));
    }

    @Test
    void documentsReportAndPrints() throws Exception {
        // Bez pieczątki nie da się wystawić dokumentu.
        mvc.perform(post(url("/documents")).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(document("KP", today, "100.00")))
                .andExpect(status().isConflict());

        mvc.perform(multipart(HttpMethod.PUT, url("/stamp")).file(stamp()).cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stamp.fileName").value("pieczatka.png"));

        // Stan początkowy wczoraj: 50 zł.
        LocalDate yesterday = today.minusDays(1);
        mvc.perform(put(url("/opening-balance")).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + yesterday + "\",\"amount\":50.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance.amount").value(50.00));

        // Wypłata większa niż stan kasy: 409.
        mvc.perform(post(url("/documents")).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(document("KW", yesterday, "80.00")))
                .andExpect(status().isConflict());

        String kp = create("KP", yesterday, "100.00");
        String kw = create("KW", today, "80.00");
        create("KP", today, "20.50");
        assertThat(JsonPath.<String>read(kp, "$.number")).isEqualTo(number("KP", 1, yesterday));
        assertThat(JsonPath.<String>read(kw, "$.number")).isEqualTo(number("KW", 1, today));
        assertThat(JsonPath.<String>read(kw, "$.content")).isEqualTo("Zwrot gotówki STACJA PALIW");

        // Dokument z przyszłą datą i sprzed stanu początkowego jest odrzucany.
        mvc.perform(post(url("/documents")).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(document("KP", today.plusDays(1), "10.00")))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url("/documents")).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(document("KP", yesterday.minusDays(1), "10.00")))
                .andExpect(status().isBadRequest());

        mvc.perform(get(url("/report")).param("from", today.toString()).param("to", today.toString()).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance").value(150.00))
                .andExpect(jsonPath("$.receipts").value(20.50))
                .andExpect(jsonPath("$.payments").value(80.00))
                .andExpect(jsonPath("$.closingBalance").value(90.50))
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].balance").value(70.00));

        // Anulowanie KP z wczoraj zabrałoby kasie gotówkę na wypłatę: 409.
        String kpId = JsonPath.read(kp, "$.id");
        mvc.perform(post(url("/documents/" + kpId + "/cancel")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"pomyłka\"}"))
                .andExpect(status().isConflict());

        // Anulowany KW zostaje w historii i raporcie, bez wpływu na stan; „Przywróć” go przywraca.
        String kwId = JsonPath.read(kw, "$.id");
        mvc.perform(post(url("/documents/" + kwId + "/cancel")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"zła kwota\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelReason").value("zła kwota"));
        mvc.perform(get(url("/report")).param("from", today.toString()).param("to", today.toString()).cookie(owner))
                .andExpect(jsonPath("$.closingBalance").value(170.50))
                .andExpect(jsonPath("$.rows[0].balance").value(nullValue()));
        mvc.perform(get(url("/documents")).cookie(owner))
                .andExpect(jsonPath("$", hasSize(3)));
        mvc.perform(get(url("/documents/" + kwId + "/pdf")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"));
        mvc.perform(post(url("/documents/" + kwId + "/restore")).cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledAt").value(nullValue()));

        // Kolejny KW ma kolejny numer, także po anulowaniu.
        assertThat(JsonPath.<String>read(create("KW", today, "1.00"), "$.number"))
                .isEqualTo(number("KW", 2, today));

        mvc.perform(get(url("/report/pdf")).param("from", yesterday.toString()).param("to", today.toString())
                        .cookie(owner))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"));

        // O 23:00 kończą się raporty wczoraj i dziś: zrzut z numerem RK = dzień, dni zamknięte dla zmian.
        assertThat(closings.closeDue(today.atTime(23, 5))).isEqualTo(2);
        assertThat(closings.closeDue(today.atTime(23, 15))).isZero();
        String list = mvc.perform(get(url("/closings")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].number").value("RK " + today.getDayOfMonth() + "/" + today.getMonthValue()
                        + "/" + today.getYear()))
                .andExpect(jsonPath("$[0].openingBalance").value(150.00))
                .andExpect(jsonPath("$[0].closingBalance").value(89.50))
                .andExpect(jsonPath("$[0].receiptCount").value(1))
                .andExpect(jsonPath("$[0].paymentCount").value(2))
                .andExpect(jsonPath("$[0].closedBy").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        String closingId = JsonPath.read(list, "$[0].id");
        mvc.perform(get(url("/closings/" + closingId)).cookie(owner))
                .andExpect(jsonPath("$.rows", hasSize(3)))
                .andExpect(jsonPath("$.rows[0].content").value("Zwrot gotówki STACJA PALIW"));
        mvc.perform(get(url("/closings/" + closingId + "/pdf")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"));
        mvc.perform(get(url("/settings")).cookie(owner))
                .andExpect(jsonPath("$.lastClosedDate").value(today.toString()));
        mvc.perform(post(url("/documents")).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(document("KP", today, "10.00")))
                .andExpect(status().isConflict());
        mvc.perform(post(url("/documents/" + kwId + "/cancel")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"po zamknięciu\"}"))
                .andExpect(status().isConflict());
        mvc.perform(put(url("/opening-balance")).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + yesterday + "\",\"amount\":60.00}"))
                .andExpect(status().isConflict());

        // Usunięta pieczątka blokuje kolejne dokumenty, ale wystawione drukują się ze swoją.
        mvc.perform(delete(url("/stamp")).cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stamp").value(nullValue()));
        mvc.perform(get(url("/documents/" + kpId + "/pdf")).cookie(owner))
                .andExpect(status().isOk());
        mvc.perform(post(url("/documents")).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(document("KP", today, "10.00")))
                .andExpect(status().isConflict());
    }

    private String create(String kind, LocalDate date, String amount) throws Exception {
        return mvc.perform(post(url("/documents")).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(document(kind, date, amount)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private static String document(String kind, LocalDate date, String amount) {
        String operation = kind.equals("KP") ? "Utarg z dnia" : "Zwrot gotówki";
        return "{\"kind\":\"" + kind + "\",\"issueDate\":\"" + date + "\",\"amount\":" + amount
                + ",\"operation\":\"" + operation + "\",\"title\":\"STACJA PALIW\""
                + ",\"counterparty\":null,\"counterpartyAddress\":null}";
    }

    private static String number(String kind, int sequence, LocalDate date) {
        return kind + " " + sequence + "/" + date.getMonthValue() + "/" + date.getYear();
    }

    private static MockMultipartFile stamp() throws Exception {
        BufferedImage image = new BufferedImage(200, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 200, 80);
        graphics.setColor(Color.BLUE);
        graphics.drawRect(4, 4, 190, 70);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new MockMultipartFile("file", "pieczatka.png", "image/png", out.toByteArray());
    }

    private String url(String suffix) {
        return "/api/stores/" + store + "/cash" + suffix;
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

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }
}
