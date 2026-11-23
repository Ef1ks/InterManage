package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** Zadania sklepu: właściciel zleca, kierownik odhacza pozycje, właściciel przyjmuje albo odsyła. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class StoreTaskIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    private Cookie owner;
    private String store;
    private String managerId;
    private Cookie manager;

    @BeforeEach
    void setUp() throws Exception {
        owner = login("wlasciciel", "haslo-wlasciciela");
        store = createStore();
        managerId = user(store);
        manager = login(username(managerId), "haslo-pracownika");
    }

    @Test
    void managerChecksItemsAndOwnerAcceptsOrSendsBack() throws Exception {
        String task = create("""
                {"title":"Sprawdź ujemne stany","priority":"URGENT","assigneeId":"%s","dueDate":"2026-12-01",
                 "sourceKind":"NEGATIVE_STOCK","sourceLabel":"Ujemne stany","sourceUrl":"/sprzedaz/ujemne-stany?nom=14",
                 "items":[{"itm8":"0000000000001","ean":"5900000000001","name":"MLEKO","details":"stan -12 szt."},
                          {"itm8":"0000000000002","name":"SER","details":"stan -3 szt."}]}""".formatted(managerId));

        mvc.perform(get(path("/mine")).cookie(manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].itemCount").value(2))
                .andExpect(jsonPath("$[0].canComplete").value(true))
                .andExpect(jsonPath("$[0].canManage").value(false))
                .andExpect(jsonPath("$[0].sourceUrl").value("/sprzedaz/ujemne-stany?nom=14"));
        mvc.perform(get(path("/counts")).cookie(manager)).andExpect(jsonPath("$.open").value(1));

        // Kierownik nie zleca i nie przyjmuje.
        mvc.perform(get(path("")).cookie(manager)).andExpect(status().isForbidden());
        mvc.perform(post(path("/" + task + "/accept")).cookie(manager).with(csrf()))
                .andExpect(status().isForbidden());

        String json = mvc.perform(get(path("/" + task)).cookie(manager)).andReturn().getResponse()
                .getContentAsString();
        String first = JsonPath.read(json, "$.items[0].id");
        String second = JsonPath.read(json, "$.items[1].id");

        check(task, first, true).andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.checkedCount").value(1));
        // Ostatnia pozycja kończy zadanie, a odznaczenie przywraca je do zrobienia.
        check(task, second, true).andExpect(jsonPath("$.status").value("DONE"));
        check(task, second, false).andExpect(jsonPath("$.status").value("OPEN"));
        check(task, second, true).andExpect(jsonPath("$.status").value("DONE"));

        mvc.perform(get(path("/counts")).cookie(owner)).andExpect(jsonPath("$.waiting").value(1));

        mvc.perform(post(path("/" + task + "/return")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Ser dalej na minusie\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.returnComment").value("Ser dalej na minusie"));

        mvc.perform(post(path("/" + task + "/done")).cookie(manager).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Poprawione\"}"))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.doneComment").value("Poprawione"));
        mvc.perform(post(path("/" + task + "/accept")).cookie(owner).with(csrf()))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        check(task, first, false).andExpect(status().isConflict());

        mvc.perform(get(path("/" + task + "/history")).cookie(owner))
                .andExpect(jsonPath("$[0].kind").value("CREATED"))
                .andExpect(jsonPath("$[-1].kind").value("ACCEPTED"));
    }

    @Test
    void ownerEditsDeletesAndRestores() throws Exception {
        String task = create("""
                {"title":"Przelicz regał","priority":"NORMAL","assigneeId":"%s"}""".formatted(managerId));
        mvc.perform(put(path("/" + task)).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Przelicz regał z kawą","priority":"NORMAL","assigneeId":"%s"}"""
                                .formatted(managerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Przelicz regał z kawą"));

        mvc.perform(delete(path("/" + task)).cookie(owner).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get(path("/mine")).cookie(manager)).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(post(path("/" + task + "/restore")).cookie(owner).with(csrf())).andExpect(status().isOk());
        mvc.perform(get(path("/mine")).cookie(manager)).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void screenshotsAreAddedViewedAndRemovedByAuthorOrOwner() throws Exception {
        String task = create("""
                {"title":"Popraw cenówki","priority":"NORMAL","assigneeId":"%s"}""".formatted(managerId));
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

        String json = mvc.perform(multipart(path("/" + task + "/attachments"))
                        .file(new MockMultipartFile("file", "blob", "image/png", png)).cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments", hasSize(1)))
                .andExpect(jsonPath("$.attachments[0].fileName").value("zrzut-ekranu.png"))
                .andExpect(jsonPath("$.attachments[0].canRemove").value(true))
                .andReturn().getResponse().getContentAsString();
        String ownerShot = JsonPath.read(json, "$.attachments[0].id");

        // Tekst udający zdjęcie nie przechodzi.
        mvc.perform(multipart(path("/" + task + "/attachments"))
                        .file(new MockMultipartFile("file", "a.png", "image/png", "hej".getBytes()))
                        .cookie(manager).with(csrf()))
                .andExpect(status().isBadRequest());

        json = mvc.perform(multipart(path("/" + task + "/attachments"))
                        .file(new MockMultipartFile("file", "półka.png", "image/png", png)).cookie(manager)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments", hasSize(2)))
                .andExpect(jsonPath("$.attachments[0].canRemove").value(false))
                .andExpect(jsonPath("$.attachments[1].canRemove").value(true))
                .andReturn().getResponse().getContentAsString();
        String managerShot = JsonPath.read(json, "$.attachments[1].id");

        mvc.perform(get(path("/" + task + "/attachments/" + ownerShot)).cookie(manager))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentType("image/png"));
        mvc.perform(get(path("/mine")).cookie(manager)).andExpect(jsonPath("$[0].attachments", hasSize(2)));

        // Kierownik nie usuwa zrzutu właściciela, swój tak (i przywraca).
        mvc.perform(delete(path("/" + task + "/attachments/" + ownerShot)).cookie(manager).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(delete(path("/" + task + "/attachments/" + managerShot)).cookie(manager).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments", hasSize(1)));
        mvc.perform(get(path("/" + task + "/attachments/" + managerShot)).cookie(owner))
                .andExpect(status().isNotFound());
        mvc.perform(post(path("/" + task + "/attachments/" + managerShot + "/restore")).cookie(manager)
                        .with(csrf()))
                .andExpect(jsonPath("$.attachments", hasSize(2)));
        mvc.perform(get(path("/" + task + "/history")).cookie(owner))
                .andExpect(jsonPath("$[-1].kind").value("ATTACHMENT_RESTORED"));
    }

    @Test
    void ownerEnablesAssigningForUserWhoManagesOnlyOwnTasks() throws Exception {
        String ownerTask = create("""
                {"title":"Zadanie właściciela","priority":"NORMAL","assigneeId":"%s"}""".formatted(managerId));
        String assignerId = user(store);
        Cookie assigner = login(username(assignerId), "haslo-pracownika");

        // Bez włączonego zlecania kierownik nie zleca.
        mvc.perform(post(path("")).cookie(assigner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Policz kawę","priority":"NORMAL","assigneeId":"%s"}""".formatted(managerId)))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/settings/users/" + assignerId).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Kierownik","owner":false,"enabled":true,"canAssignTasks":true}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canAssignTasks").value(true));
        mvc.perform(get("/api/auth/me").cookie(assigner)).andExpect(jsonPath("$.canAssignTasks").value(true));

        String json = mvc.perform(post(path("")).cookie(assigner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Policz kawę","priority":"NORMAL","assigneeId":"%s"}""".formatted(managerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.canManage").value(true))
                .andReturn().getResponse().getContentAsString();
        String own = JsonPath.read(json, "$.id");

        // „Zlecone” zlecającego to tylko jego zadania; właściciel widzi oba.
        mvc.perform(get(path("")).cookie(assigner))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(own));
        mvc.perform(get(path("")).cookie(owner)).andExpect(jsonPath("$", hasSize(2)));

        mvc.perform(post(path("/" + ownerTask + "/accept")).cookie(assigner).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(get(path("/" + ownerTask)).cookie(assigner)).andExpect(status().isNotFound());
        mvc.perform(post(path("/" + own + "/accept")).cookie(assigner).with(csrf()))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void sourceMustBePageOfApplication() throws Exception {
        for (String url : new String[] {"https://obca.strona", "//obca.strona", "/\\obca.strona", "javascript:alert(1)"}) {
            mvc.perform(post(path("")).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title":"Zadanie","priority":"NORMAL","assigneeId":"%s","sourceUrl":"%s"}"""
                                    .formatted(managerId, url.replace("\\", "\\\\"))))
                    .andExpect(status().isBadRequest());
        }
        create("""
                {"title":"Zadanie","priority":"NORMAL","assigneeId":"%s","sourceKind":"PAGE","sourceLabel":"Pulpit",
                 "sourceUrl":"/sprzedaz/porownanie?okres=dzien&data=2026-11-19"}""".formatted(managerId));
    }

    @Test
    void assigneeMustHaveAccessToStore() throws Exception {
        String other = createStore();
        String stranger = user(other);
        mvc.perform(get(path("/assignees")).cookie(owner))
                .andExpect(jsonPath("$[?(@.id == '" + managerId + "')]").exists())
                .andExpect(jsonPath("$[?(@.id == '" + stranger + "')]").doesNotExist());
        mvc.perform(post(path("")).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Coś","priority":"NORMAL","assigneeId":"%s"}""".formatted(stranger)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void taskForSeveralPeopleAndForWholeStore() throws Exception {
        String secondId = user(store);
        Cookie second = login(username(secondId), "haslo-pracownika");

        // Kilka osób: każda dostaje swoją kopię, kopie łączy grupa z postępem.
        String json = mvc.perform(post(path("")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"title":"Przeczytaj regulamin","priority":"NORMAL","assigneeIds":["%s","%s"]}"""
                                .formatted(managerId, secondId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.group.total").value(2))
                .andExpect(jsonPath("$.group.done").value(0))
                .andReturn().getResponse().getContentAsString();
        String groupId = JsonPath.read(json, "$.group.id");
        assertThat(jdbc.queryForObject("select count(*) from store_tasks where group_id = ?::uuid", Integer.class,
                groupId)).isEqualTo(2);
        String managerCopy = JsonPath.<List<String>>read(mvc.perform(get(path("/mine")).cookie(manager))
                .andReturn().getResponse().getContentAsString(), "$[?(@.group != null)].id").getFirst();

        // Jedna osoba odhacza swoją, a potem oznacza „wszyscy już zrobili”: druga kopia też jest wykonana.
        mvc.perform(post(path("/" + managerCopy + "/done")).cookie(manager).with(csrf()))
                .andExpect(jsonPath("$.group.done").value(1));
        String secondCopy = JsonPath.<List<String>>read(mvc.perform(get(path("/mine")).cookie(second))
                .andReturn().getResponse().getContentAsString(), "$[?(@.group != null)].id").getFirst();
        mvc.perform(post(path("/" + secondCopy + "/done")).cookie(second).with(csrf())).andExpect(status().isOk());
        mvc.perform(post(path("/" + secondCopy + "/undone")).cookie(second).with(csrf())).andExpect(status().isOk());
        mvc.perform(post(path("/" + managerCopy + "/done-all")).cookie(manager).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.group.done").value(2));
        mvc.perform(get(path("/" + secondCopy)).cookie(second)).andExpect(jsonPath("$.status").value("DONE"));
        mvc.perform(post(path("/" + managerCopy + "/done-all")).cookie(manager).with(csrf()))
                .andExpect(status().isConflict());

        // Cały sklep: bez wykonawcy, widzi i odhacza każdy ze sklepu; zleca tylko ten, kto może zlecać wszystkim.
        String forStore = create("""
                {"title":"Posprzątaj zaplecze","priority":"NORMAL","forStore":true}""");
        mvc.perform(get(path("/mine")).cookie(second))
                .andExpect(jsonPath("$[?(@.id == '" + forStore + "')].forStore").value(true));
        mvc.perform(get(path("/counts")).cookie(second)).andExpect(jsonPath("$.storeOpen").value(1));
        mvc.perform(post(path("/" + forStore + "/done")).cookie(second).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doneBy.id").value(secondId));
        mvc.perform(post(path("")).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Błąd","priority":"NORMAL","forStore":true,"assigneeId":"%s"}"""
                                .formatted(managerId)))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions check(String task, String item, boolean checked)
            throws Exception {
        return mvc.perform(post(path("/" + task + "/items/" + item)).cookie(manager).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"checked\":" + checked + "}"));
    }

    private String create(String body) throws Exception {
        String json = mvc.perform(post(path("")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private String path(String suffix) {
        return "/api/stores/" + store + "/tasks" + suffix;
    }

    private String user(String storeId) throws Exception {
        String roleJson = "{\"id\":\"" + TestRanks.role(jdbc, "{}") + "\"}";
        String username = "kz" + UUID.randomUUID().toString().substring(0, 8);
        String userId = jdbc.queryForObject("""
                insert into users (username, display_name, password_hash, role_id)
                values (?, 'Kierownik', ?, ?::uuid) returning id""", String.class,
                username, passwordEncoder.encode("haslo-pracownika"), JsonPath.read(roleJson, "$.id"));
        TestRanks.join(jdbc, userId, storeId);
        return userId;
    }

    private String username(String userId) {
        return jdbc.queryForObject("select username from users where id = ?::uuid", String.class, userId);
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

    private Cookie login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }
}
