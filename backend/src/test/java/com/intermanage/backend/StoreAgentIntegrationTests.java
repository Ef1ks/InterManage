package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.intermanage.backend.agent.component.AgentPresenceJob;
import com.intermanage.backend.auth.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.Cookie;

/**
 * Agent sklepu: klucz, czekanie na pliki (long polling), przydział pliku przed zapisem (bez podwójnego zapisu),
 * zdarzenia z folderów i wiadomości na żywo dla przeglądarek.
 */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        "app.auth.token-refresh-after=0s"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class StoreAgentIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AgentPresenceJob presence;

    @Test
    void keyWorksUntilAgentIsRemoved() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String body = mvc.perform(post("/api/stores/" + storeId + "/agents").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Komputer biuro\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.agents[0].key").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String key = JsonPath.read(body, "$.agents[0].key");
        String agentId = JsonPath.read(body, "$.agents[0].id");
        // Klucz jest pokazywany tylko raz.
        mvc.perform(get("/api/stores/" + storeId + "/agents").cookie(owner))
                .andExpect(jsonPath("$.agents[0].key").doesNotExist());

        hello(key).andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(storeId))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.folders['deli-order-proto']").value("N:\\AVISOCONFIG\\BACKUP"))
                .andExpect(jsonPath("$.watches[0].kind").value("TRACEART"))
                .andExpect(jsonPath("$.longPollSeconds").value(25));
        hello("ima_zly-klucz").andExpect(status().isForbidden());
        mvc.perform(post("/api/store-agent/hello").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/stores/" + storeId + "/agents").cookie(owner))
                .andExpect(jsonPath("$.agents[0].online").value(true))
                .andExpect(jsonPath("$.agents[0].machineName").value("KASA-1"));
        // Wybór sklepu: dopiero włączona praca przez agenta czyni go aktywnym dla przeglądarek.
        mvc.perform(get("/api/stores/" + storeId + "/agents/status").cookie(owner))
                .andExpect(jsonPath("$.online").value(true))
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(put("/api/stores/" + storeId + "/agents/settings").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(get("/api/stores/" + storeId + "/agents/status").cookie(owner))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.version").value("1.0.0"));

        mvc.perform(delete("/api/stores/" + storeId + "/agents/" + agentId).cookie(owner).with(csrf()))
                .andExpect(status().isOk());
        hello(key).andExpect(status().isForbidden());
        mvc.perform(post("/api/stores/" + storeId + "/agents/" + agentId + "/restore").cookie(owner).with(csrf()))
                .andExpect(status().isOk());
        hello(key).andExpect(status().isOk());
    }

    @Test
    void waitingAgentGetsFileAddedMeanwhileAndTimesOutEmpty() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String key = addAgent(owner, storeId, "Biuro");

        // Sklep pracuje przez przeglądarkę: agent nie bierze plików z kolejki.
        String early = enqueue(owner, storeId, "PROTO9_20261120_101400_10023_jan.Traite0");
        MvcResult disabled = wait(key);
        assertThatThrownBy(() -> disabled.getAsyncResult(300)).isInstanceOf(IllegalStateException.class);
        timeOut(disabled);
        enable(owner, storeId);
        assertThat(wait(key).getAsyncResult(10_000).toString()).contains(early);

        MvcResult waiting = mvc.perform(get("/api/store-agent/file-queue/wait?timeout=25").header(
                        HttpHeaders.AUTHORIZATION, "Bearer " + key))
                .andExpect(request().asyncStarted()).andReturn();
        String fileId = enqueue(owner, storeId, "PROTO1_20261120_101500_10023_jan.Traite0");
        Object result = waiting.getAsyncResult(10_000);
        assertThat(result.toString()).contains(fileId).contains("PROTO1_20261120_101500_10023_jan.Traite0");
        mvc.perform(asyncDispatch(waiting)).andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].id").value(fileId))
                .andExpect(jsonPath("$.files[0].folderKey").value("deli-order-proto"));

        // Bez plików: czas mija, pusta lista.
        MvcResult empty = mvc.perform(get("/api/store-agent/file-queue/wait?timeout=1").header(
                        HttpHeaders.AUTHORIZATION, "Bearer " + key))
                .andExpect(request().asyncStarted()).andReturn();
        timeOut(empty);
        mvc.perform(asyncDispatch(empty)).andExpect(status().isOk())
                .andExpect(jsonPath("$.files.length()").value(0));
    }

    @Test
    void fileIsClaimedByOneAndSavedOnlyByHolder() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String first = addAgent(owner, storeId, "Biuro");
        String second = addAgent(owner, storeId, "Zaplecze");
        enable(owner, storeId);
        String fileId = enqueue(owner, storeId, "CTRLPRIX_20261120_101500_GOBARTO_jan.traite0");

        MvcResult won = wait(first);
        assertThat(won.getAsyncResult(10_000).toString()).contains(fileId);
        // Drugi agent nic nie dostaje, a przeglądarka nie przydzieli sobie pliku, który zapisuje agent.
        MvcResult lost = wait(second);
        assertThatThrownBy(() -> lost.getAsyncResult(300)).isInstanceOf(IllegalStateException.class);
        timeOut(lost);
        mvc.perform(post(queue(storeId, fileId) + "/claim").cookie(owner).with(csrf()))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/store-agent/file-queue/" + fileId + "/saved")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + second))
                .andExpect(status().isConflict());
        mvc.perform(post(queue(storeId, fileId) + "/saved").cookie(owner).with(csrf()))
                .andExpect(status().isConflict());

        // Wygasły przydział wraca: plik można przydzielić od nowa.
        jdbc.update("update store_file_queue set claim_expires_at = now() - interval '1 second' where id = ?::uuid",
                fileId);
        mvc.perform(post(queue(storeId, fileId) + "/claim").cookie(owner).with(csrf()))
                .andExpect(status().isOk());
        // Teraz agent, który go miał, nie potwierdzi zapisu.
        mvc.perform(post("/api/store-agent/file-queue/" + fileId + "/saved")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + first))
                .andExpect(status().isConflict());
        mvc.perform(post(queue(storeId, fileId) + "/saved").cookie(owner).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get(queue(storeId, fileId)).cookie(owner))
                .andExpect(jsonPath("$.state").value("SAVED"))
                .andExpect(jsonPath("$.savedByAgent").value(false));

        // Zapis przez agenta i nieudany zapis: plik po błędzie nie wraca sam do agenta, zapisuje go przeglądarka.
        String saved = enqueue(owner, storeId, "PROTO2_20261120_101600_10023_jan.Traite0");
        assertThat(wait(first).getAsyncResult(10_000).toString()).contains(saved);
        mvc.perform(get("/api/store-agent/file-queue/" + saved + "/file")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + first))
                .andExpect(status().isOk());
        mvc.perform(post("/api/store-agent/file-queue/" + saved + "/saved")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + first))
                .andExpect(status().isNoContent());
        mvc.perform(get(queue(storeId, saved)).cookie(owner))
                .andExpect(jsonPath("$.state").value("SAVED"))
                .andExpect(jsonPath("$.savedByAgent").value(true));

        String failed = enqueue(owner, storeId, "PROTO3_20261120_101700_10023_jan.Traite0");
        assertThat(wait(first).getAsyncResult(10_000).toString()).contains(failed);
        mvc.perform(post("/api/store-agent/file-queue/" + failed + "/failed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + first)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"error\":\"Plik już jest w folderze\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get(queue(storeId, failed)).cookie(owner))
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.error").value("Plik już jest w folderze"));
        MvcResult again = wait(first);
        assertThatThrownBy(() -> again.getAsyncResult(300)).isInstanceOf(IllegalStateException.class);
        timeOut(again);
        mvc.perform(post(queue(storeId, failed) + "/claim").cookie(owner).with(csrf()))
                .andExpect(status().isOk());
    }

    /**
     * Agent na każdym komputerze sklepu: pilnowanie folderów i aktualizację danych robi jeden, główny agent. Rolę
     * przejmuje inny, gdy dzierżawa wygaśnie albo główny zostanie usunięty, a preferowany odbiera ją od razu.
     */
    @Test
    void oneLeaderWatchesFoldersAndPreferredTakesOver() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String kasa = addAgent(owner, storeId, "Kasa 1");
        String biuro = addAgent(owner, storeId, "Biuro");
        String biuroId = jdbc.queryForObject("select id::text from store_agents where store_id = ?::uuid and name = ?",
                String.class, storeId, "Biuro");

        hello(kasa).andExpect(jsonPath("$.leader").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Agent-Leader", "true"));
        hello(biuro).andExpect(jsonPath("$.leader").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Agent-Leader", "false"));
        String traceart = """
                {"events":[{"kind":"CREATED","folderKey":"TRACEART",
                  "fileName":"TRACEART_20261120_101500_SR1_UT1.Traite0"}]}""";
        // Te same pliki cen z kasy z drugiego komputera: serwer o nie nie prosi.
        events(biuro, traceart).andExpect(jsonPath("$.requests.length()").value(0));
        events(kasa, traceart).andExpect(jsonPath("$.requests.length()").value(1));

        // Aktualizacja danych: automatyczna tylko na głównym, a ręczna z menu najwyżej na jednym komputerze naraz.
        reportMacro(biuro, null).andExpect(status().isConflict());
        reportMacro(biuro, false).andExpect(status().isOk());
        reportMacro(kasa, null).andExpect(status().isConflict());
        mvc.perform(post("/api/store-agent/report-macro/finished").header(HttpHeaders.AUTHORIZATION, "Bearer " + biuro))
                .andExpect(status().isNoContent());
        reportMacro(kasa, null).andExpect(status().isOk());

        // Preferowany odbiera rolę przy najbliższym żądaniu.
        mvc.perform(put("/api/stores/" + storeId + "/agents/" + biuroId + "/preferred").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"preferred\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents[?(@.name == 'Biuro')].preferred").value(true))
                .andExpect(jsonPath("$.agents[?(@.name == 'Kasa 1')].leader").value(true));
        hello(biuro).andExpect(jsonPath("$.leader").value(true)).andExpect(jsonPath("$.preferred").value(true));
        hello(kasa).andExpect(jsonPath("$.leader").value(false));
        mvc.perform(get("/api/stores/" + storeId + "/agents/status").cookie(owner))
                .andExpect(jsonPath("$.name").value("Biuro"));

        // Preferowany jest najwyżej jeden: wybór drugiego zdejmuje pierwszego.
        String kasaId = jdbc.queryForObject(
                "select id::text from store_agents where store_id = ?::uuid and name = 'Kasa 1'", String.class, storeId);
        for (String chosen : List.of(kasaId, biuroId)) {
            mvc.perform(put("/api/stores/" + storeId + "/agents/" + chosen + "/preferred").cookie(owner).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"preferred\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.agents[?(@.preferred == true)].id").value(chosen));
        }

        // Wygasła dzierżawa: rolę bierze pierwszy zgłaszający się, a preferowany wraca do niej po powrocie.
        jdbc.update("update store_agent_leaders set expires_at = now() - interval '1 minute' where store_id = ?::uuid",
                storeId);
        hello(kasa).andExpect(jsonPath("$.leader").value(true));
        hello(biuro).andExpect(jsonPath("$.leader").value(true));

        // Usunięty główny oddaje rolę od razu.
        mvc.perform(delete("/api/stores/" + storeId + "/agents/" + biuroId).cookie(owner).with(csrf()))
                .andExpect(status().isOk());
        hello(kasa).andExpect(jsonPath("$.leader").value(true));
    }

    @Test
    void agentGetsNewVersionFromAgentPackageInDownloads() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String key = addAgent(owner, storeId, "Komputer biuro");

        // Plik bez agenta w środku i bez wersji nie może być paczką agenta.
        mvc.perform(multipart("/api/settings/downloads").cookie(owner).with(csrf())
                        .file(new MockMultipartFile("file", "agent.zip", "application/zip", agentZip("inny.exe")))
                        .param("title", "Agent sklepu").param("version", "1.9.0").param("agentPackage", "true"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/settings/downloads").cookie(owner).with(csrf())
                        .file(new MockMultipartFile("file", "agent.zip", "application/zip",
                                agentZip("net48/IntermanageAgent.exe")))
                        .param("title", "Agent sklepu").param("version", "nowa").param("agentPackage", "true"))
                .andExpect(status().isBadRequest());

        byte[] zip = agentZip("net48/IntermanageAgent.exe");
        mvc.perform(multipart("/api/settings/downloads").cookie(owner).with(csrf())
                        .file(new MockMultipartFile("file", "agent.zip", "application/zip", zip))
                        .param("title", "Agent sklepu").param("version", "v9.1.0").param("agentPackage", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == 'Agent sklepu')].agentPackage").value(true));

        hello(key).andExpect(jsonPath("$.update.version").value("9.1.0"))
                .andExpect(jsonPath("$.update.sizeBytes").value(zip.length));
        mvc.perform(get("/api/stores/" + storeId + "/agents").cookie(owner))
                .andExpect(jsonPath("$.latestVersion").value("9.1.0"));
        byte[] file = mvc.perform(get("/api/store-agent/update/file").header(HttpHeaders.AUTHORIZATION, "Bearer " + key))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(file).isEqualTo(zip);
        mvc.perform(get("/api/store-agent/update/file").header(HttpHeaders.AUTHORIZATION, "Bearer ima_zly"))
                .andExpect(status().isForbidden());

        // Istniejąca pozycja z innym plikiem: oznaczenie razem z nowym plikiem sprawdza nowy plik, nie stary.
        String body = mvc.perform(multipart("/api/settings/downloads").cookie(owner).with(csrf())
                        .file(new MockMultipartFile("file", "stary.txt", "text/plain", "stary".getBytes()))
                        .param("title", "Agent 2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$[?(@.title == 'Agent 2')].id");
        mvc.perform(multipart("/api/settings/downloads/" + ids.getFirst() + "/file").cookie(owner).with(csrf())
                        .file(new MockMultipartFile("file", "agent.zip", "application/zip", zip))
                        .param("version", "9.2.0").param("agentPackage", "true")
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        }))
                .andExpect(status().isOk());
        hello(key).andExpect(jsonPath("$.update.version").value("9.2.0"));
    }

    private static byte[] agentZip(String exeName) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            entry(zip, exeName, "MZ agent");
            entry(zip, exeName.substring(0, exeName.lastIndexOf('/') + 1) + "System.Text.Json.dll", "MZ dll");
        }
        return bytes.toByteArray();
    }

    private org.springframework.test.web.servlet.ResultActions events(String key, String body) throws Exception {
        return mvc.perform(post("/api/store-agent/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private org.springframework.test.web.servlet.ResultActions reportMacro(String key, Boolean automatic)
            throws Exception {
        return mvc.perform(post("/api/store-agent/report-macro").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kinds\":[\"CADENCIER\"]" + (automatic == null ? "" : ",\"automatic\":" + automatic) + "}"));
    }

    @Test
    void folderEventsGoToPriceControlTraceartAndAthos() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String key = addAgent(owner, storeId, "Biuro");
        String disposition = mvc.perform(post("/api/stores/" + storeId + "/data-entry/export/price-control")
                        .cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"ean\":\"5904634010327\",\"salePrice\":4.99}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        String fileName = disposition.replaceAll(".*filename=\"([^\"]+)\".*", "$1");
        String loaded = fileName.replaceAll("\\.traite0$", ".Traite1");

        MvcResult stream = mvc.perform(get("/api/stores/" + storeId + "/live").cookie(owner))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(post("/api/store-agent/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"events":[
                                  {"kind":"RENAMED","folderKey":"CTRLPRIX","fileName":"%s"},
                                  {"kind":"CREATED","folderKey":"TRACEART",
                                   "fileName":"TRACEART_20261120_101500_SR1_UT1.Traite0"},
                                  {"kind":"PRESENT","folderKey":"TRACEART","fileName":"TRACEART_20251120_101500.Traite0"},
                                  {"kind":"CHANGED","folderKey":"ATHOS","fileName":"Histo.Log",
                                   "modified":"2026-11-20T09:00:00Z"}
                                ]}""".formatted(loaded)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests.length()").value(2))
                .andExpect(jsonPath("$.requests[0].folderKey").value("TRACEART"))
                .andExpect(jsonPath("$.requests[0].fileName").value("TRACEART_20261120_101500_SR1_UT1.Traite0"))
                .andExpect(jsonPath("$.requests[1].folderKey").value("ATHOS"));
        mvc.perform(get("/api/stores/" + storeId + "/data-entry/price-control-files").cookie(owner))
                .andExpect(jsonPath("$.pending.length()").value(0))
                .andExpect(jsonPath("$.loaded[0].processedCount").value(1));
        awaitContent(stream, "\"topic\":\"price-control\"");
        assertThat(stream.getResponse().getContentAsString()).contains("\"action\":\"loaded\"");

        // Wolne łącze: końcówka dziennika ATHOS spakowana gzipem. Bez wysyłek to nie błąd, a odczyt zapamiętuje datę
        // pliku, więc to samo zdarzenie nie prosi już o plik.
        java.io.ByteArrayOutputStream packed = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream zip = new java.util.zip.GZIPOutputStream(packed)) {
            zip.write("linia bez wysylek\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        mvc.perform(multipart("/api/store-agent/athos-history")
                        .file(new MockMultipartFile("file", "Histo.Log.gz", "application/octet-stream",
                                packed.toByteArray()))
                        .param("modified", String.valueOf(Instant.parse("2026-11-20T09:00:00Z").toEpochMilli()))
                        .param("partial", "true")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true))
                .andExpect(jsonPath("$.fileName").value("Histo.Log"));
        mvc.perform(post("/api/store-agent/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"events":[{"kind":"CHANGED","folderKey":"ATHOS","fileName":"Histo.Log",
                                  "modified":"2026-11-20T09:00:00Z"}]}"""))
                .andExpect(jsonPath("$.requests.length()").value(0));
    }

    @Test
    void liveStreamReachesOnlyItsStoreAndDoesNotRefreshToken() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String otherStoreId = createStore(owner);

        // Zwykłe żądanie odnawia token (w teście od razu), strumień na żywo nie.
        assertThat(mvc.perform(get("/api/auth/me").cookie(owner)).andReturn().getResponse()
                .getCookie(JwtService.COOKIE_NAME)).isNotNull();
        MvcResult stream = mvc.perform(get("/api/stores/" + storeId + "/live").cookie(owner))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(stream.getResponse().getCookie(JwtService.COOKIE_NAME)).isNull();
        assertThat(stream.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        MvcResult other = mvc.perform(get("/api/stores/" + otherStoreId + "/live").cookie(owner))
                .andExpect(request().asyncStarted()).andReturn();

        String fileId = enqueue(owner, storeId, "PROTO4_20261120_101800_10023_jan.Traite0");
        awaitContent(stream, fileId);
        assertThat(stream.getResponse().getContentAsString()).contains("\"topic\":\"file-queue\"")
                .contains("\"action\":\"added\"");
        assertThat(other.getResponse().getContentAsString()).doesNotContain("file-queue");

        // Bez dostępu do sklepu nie ma strumienia.
        mvc.perform(get("/api/stores/" + storeId + "/live")).andExpect(status().isUnauthorized());
        presence.check();
    }

    @Test
    void foldersHaveAppDefaultsAndOwnPathOnlyForItsStore() throws Exception {
        Cookie owner = login();
        String first = createStore(owner);
        String second = createStore(owner);
        // Bez wpisu: domyślne ścieżki aplikacji, także folder ATHOS pod kluczem z id sklepu.
        mvc.perform(get("/api/stores/" + second + "/file-locations").cookie(owner))
                .andExpect(jsonPath("$.locations['folder:data-entry-xml']").value("H:\\GCOM\\Aviso\\BLdirect"))
                .andExpect(jsonPath("$.locations['folder:mea-comrnis:" + second + "']").value("N:\\comrnis"));
        // Własna ścieżka tylko w pierwszym sklepie; ścieżka równa domyślnej to domyślna.
        mvc.perform(put("/api/stores/" + first + "/file-locations").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"locations":{"folder:data-entry-xml":"P:\\\\XML",
                                  "folder:data-entry-lst":"n:\\\\aloa\\\\lst\\\\import\\\\"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locations['folder:data-entry-xml']").value("P:\\XML"));
        mvc.perform(get("/api/stores/" + second + "/file-locations").cookie(owner))
                .andExpect(jsonPath("$.locations['folder:data-entry-xml']").value("H:\\GCOM\\Aviso\\BLdirect"));
        mvc.perform(get("/api/stores/" + first + "/file-locations/folders").cookie(owner))
                .andExpect(jsonPath("$[?(@.key == 'data-entry-xml')].custom").value(true))
                .andExpect(jsonPath("$[?(@.key == 'data-entry-xml')].path").value("P:\\XML"))
                .andExpect(jsonPath("$[?(@.key == 'data-entry-lst')].custom").value(false))
                .andExpect(jsonPath("$[?(@.key == 'deli-order-proto')].path").value("N:\\AVISOCONFIG\\BACKUP"));
        String key = addAgent(owner, first, "Biuro");
        hello(key).andExpect(jsonPath("$.folders['data-entry-xml']").value("P:\\XML"))
                .andExpect(jsonPath("$.folders['data-entry-product-file']").value("H:\\GCOM\\INTERMANAGE\\CAD_BEZP"))
                .andExpect(jsonPath("$.watches[?(@.kind == 'ATHOS')].path").value("N:\\ATHOS2000"));
        // Przywrócenie domyślnej: pusta ścieżka.
        mvc.perform(put("/api/stores/" + first + "/file-locations").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"locations\":{\"folder:data-entry-xml\":\"\"}}"))
                .andExpect(jsonPath("$.locations['folder:data-entry-xml']").value("H:\\GCOM\\Aviso\\BLdirect"));
        // Folder plików PROTO: własny tylko w sklepie.
        mvc.perform(put("/api/stores/" + first + "/proto-settings").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderPath\":\"N:\\\\PROTO\",\"hiddenProtocols\":[]}"))
                .andExpect(jsonPath("$.folderPath").value("N:\\PROTO"));
        mvc.perform(get("/api/stores/" + second + "/proto-settings").cookie(owner))
                .andExpect(jsonPath("$.folderPath").value("N:\\AVISOCONFIG\\BACKUP"))
                .andExpect(jsonPath("$.defaultFolder").value(true));
    }


    private void awaitContent(MvcResult stream, String text) throws Exception {
        Instant deadline = Instant.now().plusSeconds(10);
        while (!stream.getResponse().getContentAsString().contains(text) && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
        }
        assertThat(stream.getResponse().getContentAsString()).contains(text);
    }

    private static void timeOut(MvcResult result) throws IOException {
        MockAsyncContext context = (MockAsyncContext) result.getRequest().getAsyncContext();
        for (AsyncListener listener : List.copyOf(context.getListeners())) {
            listener.onTimeout(new AsyncEvent(context));
        }
    }

    /**
     * Raport Importu danych przez agenta: folder ze ścieżki zapisu makra, prośba o nowy plik, wysyłka w kawałkach,
     * import w tle tą samą drogą co z przeglądarki, a następnego dnia polecenie usunięcia wgranego pliku z folderu.
     */
    @Test
    void agentImportsReportFromFolderAndDeletesItNextDay() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String storeNumber = jdbc.queryForObject("select number from stores where id = ?::uuid", String.class,
                storeId);
        String key = addAgent(owner, storeId, "Biuro");
        // Bez pracy przez agenta raportów nie pilnuje.
        hello(key).andExpect(jsonPath("$.watches[?(@.kind == 'IMPORT_CADENCIER')]").isEmpty());
        enable(owner, storeId);
        mvc.perform(put("/api/stores/" + storeId + "/file-locations").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"locations":{"import:CADENCIER:path":"P:\\\\Raporty\\\\obecne w cad.xlsx",
                                  "import:CADENCIER:mask":"obecne w cad*"}}"""))
                .andExpect(status().isOk());
        hello(key)
                .andExpect(jsonPath("$.watches[?(@.kind == 'IMPORT_CADENCIER')].path").value("P:\\Raporty"))
                .andExpect(jsonPath("$.watches[?(@.kind == 'IMPORT_CADENCIER')].pattern")
                        .value("^(?!~\\$)obecne w cad.*\\.(?:xlsx|xlsm)$"))
                // Lista dostawców ma ścieżkę zapisu z góry, bez ustawień sklepu.
                .andExpect(jsonPath("$.watches[?(@.kind == 'IMPORT_SUPPLIERS')].path")
                        .value("P:\\Production\\INTERMANAGE\\DOSTAWCY"));
        mvc.perform(get("/api/stores/" + storeId + "/imports/report-macro?kinds=SUPPLIERS").cookie(owner))
                .andExpect(jsonPath("$.reports[0]").value("SUPPLIERS"))
                .andExpect(jsonPath("$.files[0].fileName").value("DOSTAWCY.xlsx"));
        // Własna ścieżka sklepu, a pusta wraca do domyślnej.
        for (String[] path : new String[][] {{"P:\\\\Inny\\\\dost.xlsx", "P:\\Inny"},
                {"", "P:\\Production\\INTERMANAGE\\DOSTAWCY"}}) {
            mvc.perform(put("/api/stores/" + storeId + "/file-locations").cookie(owner).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"locations\":{\"import:SUPPLIERS:path\":\"" + path[0] + "\"}}"))
                    .andExpect(status().isOk());
            hello(key).andExpect(jsonPath("$.watches[?(@.kind == 'IMPORT_SUPPLIERS')].path").value(path[1]));
        }

        Instant modified = Instant.now().minus(java.time.Duration.ofDays(2)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String event = """
                {"events":[
                  {"kind":"PRESENT","folderKey":"IMPORT_CADENCIER","fileName":"obecne w cad.xlsx","size":%d,
                   "modified":"%s"},
                  {"kind":"PRESENT","folderKey":"IMPORT_CADENCIER","fileName":"~$obecne w cad.xlsx","size":1,
                   "modified":"%s"}
                ]}""";
        byte[] report = cadencierReport(storeNumber, "28/10/2026 15:02:59", "0000000001001", "0000000001002");
        // Excel dopiero założył pusty plik: serwer go nie chce, a próba wysłania go (starszy agent) nic nie zapisuje.
        Instant created = modified.minusMillis(200);
        mvc.perform(post("/api/store-agent/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content(event.formatted(0, created, created)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests.length()").value(0));
        mvc.perform(post("/api/store-agent/reports").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"folderKey":"IMPORT_CADENCIER","fileName":"obecne w cad.xlsx","size":0,
                                 "modified":"%s"}""".formatted(created)))
                .andExpect(status().isConflict());
        // Odrzucony niedopisany plik z datą o ułamek sekundy wcześniejszą nie blokuje gotowego.
        jdbc.update("""
                insert into agent_report_files (id, store_id, kind, file_name, file_modified, file_size, status, error,
                    retryable, started_at, finished_at)
                values (gen_random_uuid(), ?::uuid, 'CADENCIER', 'obecne w cad.xlsx', ?::timestamptz, 10, 'FAILED', 'x',
                    false, now(), now())""", storeId, created.toString());
        mvc.perform(post("/api/store-agent/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event.formatted(report.length, modified, modified)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests.length()").value(1))
                .andExpect(jsonPath("$.requests[0].folderKey").value("IMPORT_CADENCIER"))
                .andExpect(jsonPath("$.requests[0].fileName").value("obecne w cad.xlsx"))
                .andExpect(jsonPath("$.deletes.length()").value(0));

        String start = mvc.perform(post("/api/store-agent/reports").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"folderKey":"IMPORT_CADENCIER","fileName":"obecne w cad.xlsx","size":%d,
                                 "modified":"%s"}""".formatted(report.length, modified)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(start, "$.id");
        // Ten sam plik w trakcie wysyłki: serwer o niego nie prosi i drugiej wysyłki nie przyjmuje.
        mvc.perform(post("/api/store-agent/reports").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"folderKey":"IMPORT_CADENCIER","fileName":"obecne w cad.xlsx","size":%d,
                                 "modified":"%s"}""".formatted(report.length, modified)))
                .andExpect(status().isConflict());
        int half = report.length / 2;
        mvc.perform(put("/api/store-agent/reports/" + id + "/chunks/0").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(java.util.Arrays.copyOfRange(report, 0, half)))
                .andExpect(status().isNoContent());
        mvc.perform(put("/api/store-agent/reports/" + id + "/chunks/1").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(java.util.Arrays.copyOfRange(report, half, report.length)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/store-agent/reports/" + id + "/finish").header(HttpHeaders.AUTHORIZATION, "Bearer " + key))
                .andExpect(status().isAccepted());

        String status = null;
        for (int attempt = 0; attempt < 100 && !"IMPORTED".equals(status) && !"FAILED".equals(status); attempt++) {
            Thread.sleep(100);
            String files = mvc.perform(get("/api/stores/" + storeId + "/imports/agent-files").cookie(owner))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            status = JsonPath.read(files, "$[0].status");
        }
        assertThat(status).isEqualTo("IMPORTED");
        assertThat(jdbc.queryForObject("select count(*) from cadencier_items where store_id = ?::uuid",
                Integer.class, storeId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select imported_by from cadencier_imports where store_id = ?::uuid",
                String.class, storeId)).isNull();

        // Wgrany dziś: nie prosi o niego drugi raz i jeszcze go nie usuwa.
        mvc.perform(post("/api/store-agent/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event.formatted(report.length, modified, modified)))
                .andExpect(jsonPath("$.requests.length()").value(0))
                .andExpect(jsonPath("$.deletes.length()").value(0));

        // Następnego dnia: usunąć ten plik, ale nie nowszy zapisany pod tą samą nazwą.
        jdbc.update("update agent_report_files set finished_at = now() - interval '1 day' where id = ?::uuid", id);
        mvc.perform(post("/api/store-agent/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event.formatted(report.length, modified, modified)))
                .andExpect(jsonPath("$.requests.length()").value(0))
                .andExpect(jsonPath("$.deletes.length()").value(1))
                .andExpect(jsonPath("$.deletes[0].fileName").value("obecne w cad.xlsx"));
        Instant newer = Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        mvc.perform(post("/api/store-agent/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event.formatted(report.length, newer, newer)))
                .andExpect(jsonPath("$.requests.length()").value(1))
                .andExpect(jsonPath("$.deletes.length()").value(0));

        mvc.perform(post("/api/store-agent/reports/removed").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"folderKey":"IMPORT_CADENCIER","fileName":"obecne w cad.xlsx","modified":"%s"}"""
                                .formatted(modified)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/stores/" + storeId + "/imports/agent-files").cookie(owner))
                .andExpect(jsonPath("$[0].removedFromDiskAt").isNotEmpty());

        // Lista dostawców idzie tą samą drogą i tym samym importem co z przeglądarki.
        byte[] list = supplierReport(storeNumber);
        mvc.perform(post("/api/store-agent/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"events":[{"kind":"PRESENT","folderKey":"IMPORT_SUPPLIERS","fileName":"DOSTAWCY.xlsx",
                                  "size":%d,"modified":"%s"}]}""".formatted(list.length, modified)))
                .andExpect(jsonPath("$.requests[0].folderKey").value("IMPORT_SUPPLIERS"));
        String supplierUpload = JsonPath.read(mvc.perform(post("/api/store-agent/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"folderKey":"IMPORT_SUPPLIERS","fileName":"DOSTAWCY.xlsx","size":%d,"modified":"%s"}"""
                                .formatted(list.length, modified)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(put("/api/store-agent/reports/" + supplierUpload + "/chunks/0")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(list))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/store-agent/reports/" + supplierUpload + "/finish")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key))
                .andExpect(status().isAccepted());
        String supplierStatus = null;
        String supplierError = null;
        for (int attempt = 0; attempt < 100 && !"IMPORTED".equals(supplierStatus)
                && !"FAILED".equals(supplierStatus); attempt++) {
            Thread.sleep(100);
            supplierStatus = jdbc.queryForObject("select status from agent_report_files where id = ?::uuid",
                    String.class, supplierUpload);
            supplierError = jdbc.queryForObject("select error from agent_report_files where id = ?::uuid",
                    String.class, supplierUpload);
        }
        assertThat(supplierStatus).as(supplierError).isEqualTo("IMPORTED");
        assertThat(jdbc.queryForObject("select count(*) from suppliers where store_id = ?::uuid and code = '538'",
                Integer.class, storeId)).isEqualTo(1);
        mvc.perform(post("/api/store-agent/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"events":[{"kind":"PRESENT","folderKey":"IMPORT_SUPPLIERS","fileName":"DOSTAWCY.xlsx",
                                  "size":%d,"modified":"%s"}]}""".formatted(list.length, modified)))
                .andExpect(jsonPath("$.requests.length()").value(0));

        // Aktualizacja przy starcie komputera: agent widzi swoje próby i dostaje makro z Mercalys dla raportów
        // z ustawieniami (przeglądarka dostaje to samo makro); raport bez numeru wydruku jest pominięty z powodem.
        mvc.perform(get("/api/store-agent/reports").header(HttpHeaders.AUTHORIZATION, "Bearer " + key))
                .andExpect(jsonPath("$[?(@.fileName == 'obecne w cad.xlsx' && @.status == 'IMPORTED')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.fileName == 'DOSTAWCY.xlsx' && @.status == 'IMPORTED')]").isNotEmpty());
        mvc.perform(post("/api/store-agent/report-macro").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kinds\":[\"CADENCIER\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports.length()").value(0))
                .andExpect(jsonPath("$.skipped[0].kind").value("CADENCIER"))
                .andExpect(jsonPath("$.openLine").value("WINOPEN|Mercalys ahk_exe Mercalys.exe"));
        mvc.perform(put("/api/stores/" + storeId + "/file-locations").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locations\":{\"import:CADENCIER:print\":\"7\",\"import:CADENCIER:list\":\"12\"}}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/stores/" + storeId + "/imports/report-macro?kinds=CADENCIER").cookie(owner))
                .andExpect(jsonPath("$.reports[0]").value("CADENCIER"))
                .andExpect(jsonPath("$.files[0].fileName").value("obecne w cad.xlsx"))
                .andExpect(jsonPath("$.macro").value(org.hamcrest.Matchers.containsString("EXCELSAVE|P:\\Raporty\\obecne w cad.xlsx|")));
        mvc.perform(post("/api/store-agent/report-macro").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kinds\":[\"NOPE\"]}"))
                .andExpect(status().isBadRequest());
    }

    /** Lista „OBECNE W CADENCIER” jak z systemu sklepu: minimalny skoroszyt .xlsx z tekstami. */
    private static byte[] cadencierReport(String storeNumber, String date, String... itm8s) throws IOException {
        List<String[]> rows = new java.util.ArrayList<>();
        rows.add(new String[] {"TRYTOMA  Sp. z o.o. - Numer punktu sprzedaży " + storeNumber});
        rows.add(new String[] {"Data : " + date});
        rows.add(new String[] {"OBECNE W CAD"});
        rows.add(new String[] {});
        rows.add(new String[] {"Lista Automatyczna - 1996, OBECNE W CADENCIER (posort. wg. ITM8 Prio)"});
        rows.add(new String[] {});
        rows.add(new String[] {"ITM", "EAN"});
        for (String itm8 : itm8s) {
            rows.add(new String[] {itm8, "590" + itm8.substring(Math.max(0, itm8.length() - 10))});
        }
        rows.add(new String[] {});
        rows.add(new String[] {"Ilość linii: :" + String.format("%06d", itm8s.length)});
        return workbook(rows);
    }

    /** „Lista dostawców” jak z systemu sklepu. */
    private static byte[] supplierReport(String storeNumber) throws IOException {
        return workbook(List.of(
                new String[] {"TRYTOMA  Sp. z o.o. - Numer punktu sprzedaży " + storeNumber},
                new String[] {"Data : 25/11/2026 07:01:02"},
                new String[] {"LISTA DOSTAWCÓW"},
                new String[] {"Kod dost.", "Słowo klucz", "Nazwa firmy 1", "Nazwa firmy 2", "Kod NIP"},
                new String[] {"538", "ITM", "ITM POLSKA", "", "7821977018"},
                new String[] {"Ilość linii:  1"}));
    }

    private static byte[] workbook(List<String[]> rows) throws IOException {
        List<String> shared = new java.util.ArrayList<>();
        StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for (int r = 0; r < rows.size(); r++) {
            sheet.append("<row r=\"").append(r + 1).append("\">");
            String[] cells = rows.get(r);
            for (int c = 0; c < cells.length; c++) {
                sheet.append("<c r=\"").append((char) ('A' + c)).append(r + 1).append("\" t=\"s\"><v>")
                        .append(shared.size()).append("</v></c>");
                shared.add(cells[c]);
            }
            sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");
        StringBuilder strings = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        shared.forEach(s -> strings.append("<si><t xml:space=\"preserve\">").append(s).append("</t></si>"));
        strings.append("</sst>");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            entry(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"Mercalys\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            entry(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>"
                    + "</Relationships>");
            entry(zip, "xl/worksheets/sheet1.xml", sheet.toString());
            entry(zip, "xl/sharedStrings.xml", strings.toString());
        }
        return bytes.toByteArray();
    }

    private static void entry(java.util.zip.ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new java.util.zip.ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private MvcResult wait(String key) throws Exception {
        return mvc.perform(get("/api/store-agent/file-queue/wait?timeout=25")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key))
                .andExpect(request().asyncStarted()).andReturn();
    }

    private void enable(Cookie owner, String storeId) throws Exception {
        mvc.perform(put("/api/stores/" + storeId + "/agents/settings").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk());
    }

    private static String queue(String storeId, String fileId) {
        return "/api/stores/" + storeId + "/file-queue/" + fileId;
    }

    private org.springframework.test.web.servlet.ResultActions hello(String key) throws Exception {
        return mvc.perform(post("/api/store-agent/hello").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":\"1.0.0\",\"machineName\":\"KASA-1\",\"user\":\"sklep\"}"));
    }

    private String addAgent(Cookie owner, String storeId, String name) throws Exception {
        String body = mvc.perform(post("/api/stores/" + storeId + "/agents").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> keys = JsonPath.read(body, "$.agents[?(@.key)].key");
        return keys.getFirst();
    }

    private String enqueue(Cookie owner, String storeId, String fileName) throws Exception {
        String body = mvc.perform(multipart("/api/stores/" + storeId + "/file-queue").cookie(owner).with(csrf())
                        .file(new MockMultipartFile("file", fileName, MediaType.APPLICATION_OCTET_STREAM_VALUE,
                                "5900000000001000001000\r\n".getBytes(StandardCharsets.UTF_8)))
                        .param("folderKey", "deli-order-proto")
                        .param("folderLabel", "Protokół")
                        .param("fileName", fileName))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }

    private String createStore(Cookie cookie) throws Exception {
        String number = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 999_999));
        String body = mvc.perform(post("/api/settings/stores").cookie(cookie).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep agenta\",\"active\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }
}
