package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.intermanage.backend.auth.security.JwtService;
import com.intermanage.backend.common.component.XlsWriter;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/**
 * Makra na wolnym komputerze sklepu: wybór komputera ze stanu zgłaszanego przez agenta (odtwarzacz, ekran,
 * bezczynność), odliczanie z odmową, która przekazuje zlecenie dalej, jedno makro naraz w sklepie, wynik z zebranymi
 * kodami i makro „Uzupełnij ITM8” składane przez serwer z linii dokumentu.
 */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        "app.ksef.sync-enabled=false"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class AgentMacroIntegrationTests {

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
    XlsWriter xlsWriter;

    @Autowired
    PasswordEncoder passwordEncoder;

    private Cookie owner;
    private String storeId;
    private String storeNumber;

    @BeforeEach
    void setUp() throws Exception {
        owner = login();
        storeNumber = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999_999L));
        String json = mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + storeNumber + "\",\"name\":\"Sklep makr\",\"active\":true}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        storeId = JsonPath.read(json, "$.id");
    }

    @Test
    void macroGoesToIdleComputerAndPassesOnWhenCancelled() throws Exception {
        String deliveryId = uploadDelivery();
        String kasa = addAgent("Kasa");
        String biuro = addAgent("Biuro");
        String zaplecze = addAgent("Zaplecze");

        // Bez zgłoszeń agentów nie ma wolnego komputera.
        collect(deliveryId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_COMPUTER"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("agent nie odpowiada")));

        // Kasa używana przed chwilą, biuro wolne od 10 min, zaplecze bez odtwarzacza makr.
        poll(kasa, 20, false, true).andExpect(jsonPath("$.offer").doesNotExist());
        poll(biuro, 600, false, true);
        poll(zaplecze, 3000, false, false);
        mvc.perform(get(base() + "/computers").cookie(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Biuro')].free").value(true))
                .andExpect(jsonPath("$[?(@.name == 'Kasa')].available").value(true))
                .andExpect(jsonPath("$[?(@.name == 'Kasa')].free").value(false))
                .andExpect(jsonPath("$[?(@.name == 'Zaplecze')].available").value(false));

        String job = JsonPath.read(collect(deliveryId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFERED"))
                .andExpect(jsonPath("$.computer").value("Biuro"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        // Drugie makro w tym czasie: sklep ma jeden Mercalys.
        collect(deliveryId).andExpect(status().isConflict());

        poll(kasa, 20, false, true).andExpect(jsonPath("$.offer").doesNotExist());
        poll(biuro, 600, false, true)
                .andExpect(jsonPath("$.offer.id").value(job))
                .andExpect(jsonPath("$.offer.countdownSeconds").value(5))
                .andExpect(jsonPath("$.offer.title").value("Zbierz brakujące ITM8 · bon 09516050082"));

        // Ktoś pracuje na biurze: anuluje, makro idzie na kasę.
        agentPost(biuro, "/macro-jobs/" + job + "/decline", "{}").andExpect(status().isNoContent());
        mvc.perform(get(base() + "/" + job).cookie(owner))
                .andExpect(jsonPath("$.status").value("OFFERED"))
                .andExpect(jsonPath("$.computer").value("Kasa"))
                .andExpect(jsonPath("$.declined[0]").value("Biuro"));
        agentPost(biuro, "/macro-jobs/" + job + "/start", "").andExpect(status().isConflict());

        String macro = JsonPath.read(agentPost(kasa, "/macro-jobs/" + job + "/start", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collectsCodes").value(true))
                .andExpect(jsonPath("$.openLine").value("WINOPEN|Mercalys ahk_exe Mercalys.exe"))
                .andReturn().getResponse().getContentAsString(), "$.macro");
        assertThat(macro).startsWith("# Zbierz brakujące ITM8: bon 09516050082, dostawcy 538")
                .contains("WINOPEN|Mercalys ahk_exe Mercalys.exe\r\n")
                .contains("WEBCOLLECT|Mercalys|dgArticles|ITM nie istnieje|0|")
                .endsWith("WINMIN|Mercalys ahk_exe Mercalys.exe\r\n");
        mvc.perform(get(base() + "/" + job).cookie(owner)).andExpect(jsonPath("$.status").value("RUNNING"));

        agentPost(kasa, "/macro-jobs/" + job + "/finish",
                "{\"ok\":true,\"message\":\"Zebrano 2 kody\",\"codes\":[\"0000061652855\",\"61700001\",\"x\"]}")
                .andExpect(status().isNoContent());
        mvc.perform(get(base() + "/" + job).cookie(owner))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.codes", org.hamcrest.Matchers.contains("61652855", "61700001")));

        // Wpisanie kodów: makro z linii dokumentu (dostawca, opakowanie, cena, data usunięcia = bon + 2 lata).
        // Komputer z zablokowanym ekranem odpada, więc idzie na kasę mimo dłuższej bezczynności biura.
        poll(biuro, 900, true, true);
        poll(kasa, 30, false, true);
        String apply = JsonPath.read(mvc.perform(post(base() + "/itm8-apply").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"deliveryId":"%s","codes":["61652855","99999999"]}""".formatted(deliveryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.computer").value("Kasa"))
                .andExpect(jsonPath("$.itemCount").value(1))
                .andReturn().getResponse().getContentAsString(), "$.id");
        String applyMacro = JsonPath.read(agentPost(kasa, "/macro-jobs/" + apply + "/start", "")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.macro");
        assertThat(applyMacro).contains("WEBKEY|Mercalys|txtZ4_CodeEAN_TxtBox|^a5900497047708{Enter}")
                .contains("WEBKEY|Mercalys|txtZ02_Itm_TxtBox|^a61652855{Tab}")
                .contains("WEBKEY|Mercalys|lstZ04_Fournisseur_TxtBox|^a538{Tab}")
                .contains("WEBKEY|Mercalys|txnZ07_Conditionnement_TxtBox|^a12{Tab}")
                .contains("WEBKEY|Mercalys|txdZ09_DateSuppr_TxtBox|^a021128{Tab}")
                .contains("WEBKEY|Mercalys|txtZ14_PANet_TxtBox|^a3,19{Tab}");
        agentPost(kasa, "/macro-jobs/" + apply + "/finish", "{\"ok\":false,\"message\":\"Błąd makra\"}")
                .andExpect(status().isNoContent());
        mvc.perform(get(base() + "/" + apply).cookie(owner))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.message").value("Błąd makra"));
    }

    @Test
    void meaCodesFromPendingListGoToComputer() throws Exception {
        uploadDelivery();
        String kasa = addAgent("Kasa");
        poll(kasa, 900, false, true);

        // Kod spoza listy do wpisania (także z poleceniem makra w treści) nie przejdzie.
        meaCodes("[\"52817\",\"99999\"]").andExpect(status().isBadRequest());
        meaCodes("[\"52817|x\"]").andExpect(status().isBadRequest());
        meaCodes("[]").andExpect(status().isBadRequest());

        String job = JsonPath.read(meaCodes("[\"52817\"]").andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("MEA_CODES"))
                .andExpect(jsonPath("$.itemCount").value(1))
                .andExpect(jsonPath("$.computer").value("Kasa"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        String macro = JsonPath.read(agentPost(kasa, "/macro-jobs/" + job + "/start", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collectsCodes").value(false))
                .andExpect(jsonPath("$.timeoutMinutes").value(360))
                .andReturn().getResponse().getContentAsString(), "$.macro");
        assertThat(macro).startsWith("# Kody MEA: 1\r\nWINOPEN|Mercalys ahk_exe Mercalys.exe\r\n")
                .contains("WEBKEY|Mercalys|txtZ1_LibelleCadencier_TxtBox|^a52817{Enter}")
                .contains("WEBIFROW|Mercalys|txtZ1_LibelleCadencier_TxtBox|dgDetail|ibtC11_Traitement|1|52817|mea-52817|30000")
                .endsWith("LABEL|mea-52817\r\nWINMIN|Mercalys ahk_exe Mercalys.exe\r\n");
        agentPost(kasa, "/macro-jobs/" + job + "/finish",
                "{\"ok\":true,\"message\":\"52817: obsłużone pliki: 2 (0 wierszy)\"}").andExpect(status().isNoContent());
        mvc.perform(get(base() + "/" + job).cookie(owner))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.message").value("52817: obsłużone pliki: 2 (0 wierszy)"));

        // Makro niczego nie potwierdza: kod zostaje na liście do „Potwierdź wpisanie”.
        mvc.perform(get("/api/stores/" + storeId + "/mea-codes").cookie(owner))
                .andExpect(jsonPath("$.pending[0].code").value("52817"));
    }

    @Test
    void baseDeliveryIsReceivedComparedAndClosed() throws Exception {
        String deliveryId = uploadDelivery();
        String kasa = addAgent("Kasa");
        String receiving = "/api/stores/" + storeId + "/base-deliveries/" + deliveryId + "/receiving";
        // Agent bez wersji odtwarzacza (sprzed 1.8.0) i starszy odtwarzacz nie wezmą tego makra.
        poll(kasa, 900, false, true);
        base("base-receive", deliveryId).andExpect(jsonPath("$.status").value("NO_COMPUTER"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("AutoJob 0.65")));
        poll(kasa, "0.64");
        base("base-receive", deliveryId).andExpect(jsonPath("$.status").value("NO_COMPUTER"));
        poll(kasa, "0.65");
        // Bez zamówień nie ma czego kończyć.
        base("base-close", deliveryId).andExpect(status().isConflict());

        String job = JsonPath.read(base("base-receive", deliveryId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFERED"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        String start = agentPost(kasa, "/macro-jobs/" + job + "/start", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.uploads[0].key").value("538"))
                .andExpect(jsonPath("$.uploads[0].path")
                        .value("P:\\Production\\INTERMANAGE\\EXCEL\\BON_9516050082_538.xlsx"))
                .andExpect(jsonPath("$.uploads[1].key").value("88110"))
                .andReturn().getResponse().getContentAsString();
        String macro = JsonPath.read(start, "$.macro");
        assertThat(macro).contains("WINOPEN|Mercalys ahk_exe Mercalys.exe\r\n")
                .contains("WEBPICKRE|Mercalys|dgDetail|(?<!\\d)0*9516050082\\D+0*538(?!\\d)|30000|brak-538")
                .contains("WEBORDERPICK|Mercalys|ibtB1_Ajouter_ImageButton1,ibtB1_Ajouter_LnkBouton|538|"
                        + "SCAPR POLSKA SP.Z.O.O.|Base;Baza|")
                .contains("WEBERRORS|Mercalys|btnB2_Supprimer_LnkBouton|niezgodne jednost;nieistniej|30000")
                .contains("EXCELSAVE|P:\\Production\\INTERMANAGE\\EXCEL\\BON_9516050082_88110.xlsx|180000")
                .doesNotContain("WEBORDERKNOWN")
                .endsWith("WINMIN|Mercalys ahk_exe Mercalys.exe\r\n");
        // Każdy przeskok ma swoją etykietę niżej w makrze.
        List<String> lines = macro.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String[] parts = lines.get(i).split("\\|");
            String target = switch (parts[0]) {
                case "WEBGOTO" -> parts[1];
                case "WEBIFORDER" -> parts[2];
                case "WEBORDERPICK" -> parts[7];
                case "WEBPAGE" -> parts.length > 8 ? parts[8] : null;
                case "WEBPICKRE" -> parts.length > 5 ? parts[5] : null;
                default -> null;
            };
            if (target != null) {
                assertThat(lines.subList(i + 1, lines.size())).contains("LABEL|" + target);
            }
        }

        // Bon tylko z listy startu; nieczytelny plik zostaje z opisem błędu.
        mvc.perform(multipart("/api/store-agent/macro-jobs/" + job + "/files")
                        .file(new MockMultipartFile("file", "x.xlsx", null, new byte[] {1})).param("key", "77")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + kasa)).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/store-agent/macro-jobs/" + job + "/files")
                        .file(new MockMultipartFile("file", "BON_9516050082_538.xls", null, note()))
                        .param("key", "538").header(HttpHeaders.AUTHORIZATION, "Bearer " + kasa))
                .andExpect(status().isNoContent());
        mvc.perform(multipart("/api/store-agent/macro-jobs/" + job + "/files")
                        .file(new MockMultipartFile("file", "BON_9516050082_88110.xlsx", null, new byte[] {1, 2, 3}))
                        .param("key", "88110").header(HttpHeaders.AUTHORIZATION, "Bearer " + kasa))
                .andExpect(status().isNoContent());
        // Makro padło po pierwszym dostawcy: jego numer i tak zostaje.
        agentPost(kasa, "/macro-jobs/" + job + "/finish",
                "{\"ok\":false,\"message\":\"Błąd\",\"codes\":[\"538=11233\",\"61652855\"]}")
                .andExpect(status().isNoContent());
        mvc.perform(get(receiving).cookie(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.suppliers[0].order.orderNumber").value("11233"))
                .andExpect(jsonPath("$.suppliers[0].note.content.number").value("11233"))
                .andExpect(jsonPath("$.suppliers[0].note.content.lines[0].quantity").value(12))
                .andExpect(jsonPath("$.suppliers[1].order").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.suppliers[1].note.content").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.suppliers[1].note.error").isNotEmpty());

        // Powtórka zna zamówienie z pierwszego przebiegu.
        poll(kasa, "0.65");
        String again = JsonPath.read(base("base-receive", deliveryId).andReturn().getResponse().getContentAsString(),
                "$.id");
        String second = JsonPath.read(agentPost(kasa, "/macro-jobs/" + again + "/start", "")
                .andReturn().getResponse().getContentAsString(), "$.macro");
        assertThat(second).contains("WEBORDERKNOWN|538|11233\r\n");
        agentPost(kasa, "/macro-jobs/" + again + "/finish", "{\"ok\":true,\"message\":\"\",\"codes\":[]}")
                .andExpect(status().isNoContent());
        // Numer drugiego dostawcy wpisany ręcznie.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(receiving + "/order")
                        .cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierCode\":\"88110\",\"orderNumber\":\"11240\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suppliers[1].order.enteredBy").isNotEmpty());

        poll(kasa, "0.65");
        String close = JsonPath.read(base("base-close", deliveryId).andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(2))
                .andReturn().getResponse().getContentAsString(), "$.id");
        String closeMacro = JsonPath.read(agentPost(kasa, "/macro-jobs/" + close + "/start", "")
                .andReturn().getResponse().getContentAsString(), "$.macro");
        assertThat(closeMacro).contains("WEBRESULT|11233|538|09516050082\r\n")
                .contains("WEBRESULT|11240|88110|09516050082\r\n")
                .contains("WEBHEADERS|Mercalys|dgDetail|1|12|")
                .contains("WEBCLOSE|Mercalys|dgDetail|1|12|");
        agentPost(kasa, "/macro-jobs/" + close + "/finish",
                "{\"ok\":false,\"message\":\"Błąd\",\"codes\":[\"zamkniete=11233\"]}")
                .andExpect(status().isNoContent());
        mvc.perform(get(receiving).cookie(owner))
                .andExpect(jsonPath("$.suppliers[0].order.closedAt").isNotEmpty())
                .andExpect(jsonPath("$.suppliers[1].order.closedAt").value(org.hamcrest.Matchers.nullValue()));
    }

    private byte[] note() {
        return xlsWriter.write("Arkusz1", List.of("SCAPR POLSKA SP.Z.O.O."), List.of(
                List.of("Dostawca 538"), List.of("Numer dostawy: 11233"), List.of("Data : 24/11/2026 13:21:00"),
                Arrays.<Object>asList("Kod ITM8", "EAN Prio", "Referencja dostawcy", "Nazwa artykulu", "VAT",
                        "Ilosc dost.", "Jednostk. cena zakupu", "Jednostkowa C.S.", "Wartosc zakupu netto", "Marza %"),
                Arrays.<Object>asList("0000061652855", "5900497047708", "61652855", "7 UP 0.5L PET",
                        new BigDecimal("23"), new BigDecimal("12"), new BigDecimal("3.19"), new BigDecimal("4.99"),
                        new BigDecimal("38.28"), new BigDecimal("20")),
                List.of("Ilosc linii: 000001")));
    }

    private ResultActions base(String macro, String deliveryId) throws Exception {
        return mvc.perform(post(base() + "/" + macro).cookie(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"deliveryId\":\"" + deliveryId + "\"}"));
    }

    private void poll(String key, String playerVersion) throws Exception {
        agentPost(key, "/macro-jobs/poll", ("{\"idleSeconds\":900,\"locked\":false,\"playerRunning\":true,"
                + "\"busy\":false,\"playerVersion\":\"%s\"}").formatted(playerVersion)).andExpect(status().isOk());
    }

    private ResultActions meaCodes(String codes) throws Exception {
        return mvc.perform(post(base() + "/mea-codes").cookie(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"codes\":" + codes + "}"));
    }

    @Test
    void chosenComputerOnlyAndCancelBeforeStart() throws Exception {
        String deliveryId = uploadDelivery();
        String kasa = addAgent("Kasa");
        String biuro = addAgent("Biuro");
        poll(kasa, 5, false, true);
        poll(biuro, 900, false, true);
        String kasaId = jdbc.queryForObject("select id from store_agents where store_id = ?::uuid and name = 'Kasa'",
                String.class, storeId);
        String job = JsonPath.read(mvc.perform(post(base() + "/itm8-collect").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deliveryId\":\"" + deliveryId + "\",\"agentId\":\"" + kasaId + "\"}"))
                .andExpect(jsonPath("$.computer").value("Kasa"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        // Wybrany komputer odmawia: innego nie szukamy.
        agentPost(kasa, "/macro-jobs/" + job + "/decline", "{}").andExpect(status().isNoContent());
        mvc.perform(get(base() + "/" + job).cookie(owner)).andExpect(jsonPath("$.status").value("NO_COMPUTER"));

        String second = JsonPath.read(collect(deliveryId).andExpect(jsonPath("$.computer").value("Biuro"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post(base() + "/" + second + "/cancel").cookie(owner).with(csrf()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        poll(biuro, 900, false, true).andExpect(jsonPath("$.offer").doesNotExist());
        agentPost(biuro, "/macro-jobs/" + second + "/start", "").andExpect(status().isConflict());

        // Bez prawa zmian we wprowadzaniu danych makra nie da się zlecić.
        Cookie viewer = employee("{\"DATA_ENTRY\":\"VIEW\"}");
        mvc.perform(get(base() + "/computers").cookie(viewer)).andExpect(status().isOk());
        mvc.perform(post(base() + "/itm8-collect").cookie(viewer).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"deliveryId\":\"" + deliveryId + "\"}"))
                .andExpect(status().isForbidden());
    }

    private ResultActions collect(String deliveryId) throws Exception {
        return mvc.perform(post(base() + "/itm8-collect").cookie(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"deliveryId\":\"" + deliveryId + "\"}"));
    }

    private ResultActions poll(String key, int idle, boolean locked, boolean player) throws Exception {
        return agentPost(key, "/macro-jobs/poll", "{\"idleSeconds\":%d,\"locked\":%s,\"playerRunning\":%s,\"busy\":false}"
                .formatted(idle, locked, player)).andExpect(status().isOk());
    }

    private ResultActions agentPost(String key, String path, String body) throws Exception {
        var request = post("/api/store-agent" + path).header(HttpHeaders.AUTHORIZATION, "Bearer " + key);
        if (!body.isEmpty()) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private String base() {
        return "/api/stores/" + storeId + "/agent-macros";
    }

    private String addAgent(String name) throws Exception {
        String body = mvc.perform(post("/api/stores/" + storeId + "/agents").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> keys = JsonPath.read(body, "$.agents[?(@.key)].key");
        return keys.getFirst();
    }

    private String uploadDelivery() throws Exception {
        List<List<Object>> rows = List.of(
                line("61652855", "7 UP 0.5L PET", "12", "5900497047708", "3.19", "66"),
                line("61700001", "SUROWKA Z KAPUSTY", "10", "2000000000001", "2.10", "14"));
        String json = mvc.perform(multipart("/api/stores/" + storeId + "/base-deliveries")
                        .file(new MockMultipartFile("file", "09516050082.xls", "application/vnd.ms-excel",
                                xlsWriter.write("Arkusz1", HEADERS, rows)))
                        .cookie(owner).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private List<Object> line(String itm8, String name, String pack, String ean, String price, String region) {
        BigDecimal net = new BigDecimal(pack).multiply(new BigDecimal(price));
        return Arrays.asList(new BigDecimal(storeNumber), "2026-11-02", "09516050082", "42799515", itm8, name, "",
                pack, "NIE", "2027-03-31", BigDecimal.ZERO, ean, "----------", "538", "52817", "10023304",
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal(price), new BigDecimal("4.99"),
                new BigDecimal("23"), BigDecimal.ZERO, net, net, null, region, "01", "01", BigDecimal.ONE, "U",
                "22030001", "-----", "N", "00538");
    }

    private Cookie employee(String permissions) throws Exception {
        String username = "am" + UUID.randomUUID().toString().substring(0, 8);
        String userId = jdbc.queryForObject("""
                insert into users (username, display_name, password_hash, role_id)
                values (?, 'Pracownik', ?, ?::uuid) returning id""", String.class,
                username, passwordEncoder.encode("haslo-pracownika"), TestRanks.role(jdbc, permissions).toString());
        TestRanks.join(jdbc, userId, storeId);
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"haslo-pracownika\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }
}
