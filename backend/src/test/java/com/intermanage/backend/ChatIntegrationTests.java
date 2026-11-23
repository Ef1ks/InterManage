package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
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

/**
 * Czat: czat sklepu dla każdego z dostępem, grupy zakłada i ich członków zmienia zarządzający (CHAT:EDIT), rozmowy
 * dwóch osób, pliki, odczyty, cofnięcie dla wszystkich (moderator widzi treść, inni nie) i panel osób.
 */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        "app.ksef.sync-enabled=false"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class ChatIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    private Cookie owner;

    @BeforeEach
    void setUp() throws Exception {
        owner = login("wlasciciel", "haslo-wlasciciela");
    }

    @Test
    void storeChatGroupsDirectMessagesAndRetraction() throws Exception {
        String storeId = store();
        String otherStore = store();
        Account manager = account(storeId, "MANAGER");
        Account anna = account(storeId, "EMPLOYEE");
        Account piotr = account(storeId, "EMPLOYEE");
        Account stranger = account(otherStore, "EMPLOYEE");

        // Czat sklepu powstaje sam i widzi go każdy z dostępem do sklepu.
        String list = mvc.perform(get("/api/chat/conversations").param("storeId", storeId).cookie(anna.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> storeChats = JsonPath.read(list, "$[?(@.kind == 'STORE')].id");
        assertThat(storeChats).hasSize(1);
        String storeChat = storeChats.getFirst();
        assertThat((List<Boolean>) JsonPath.read(list, "$[?(@.kind == 'STORE')].local")).containsExactly(true);
        mvc.perform(get("/api/chat/conversations/" + storeChat + "/messages").cookie(stranger.cookie()))
                .andExpect(status().isNotFound());

        // Wiadomość z plikiem; druga osoba ma nieprzeczytaną, a po odczycie autor widzi to w odczytach.
        String sent = mvc.perform(multipart("/api/chat/conversations/" + storeChat + "/messages")
                        .file(new MockMultipartFile("files", "grafik.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[] {1, 2, 3}))
                        .param("body", "  Dzień dobry  ").cookie(anna.cookie()).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String messageId = JsonPath.read(sent, "$.id");
        String attachmentId = JsonPath.read(sent, "$.attachments[0].id");
        assertThat((String) JsonPath.read(sent, "$.body")).isEqualTo("Dzień dobry");
        String unread = mvc.perform(get("/api/chat/unread").cookie(piotr.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(unread, "$.total")).isEqualTo(1);
        mvc.perform(post("/api/chat/conversations/" + storeChat + "/read").cookie(piotr.cookie()).with(csrf()))
                .andExpect(status().isNoContent());
        String thread = mvc.perform(get("/api/chat/conversations/" + storeChat + "/messages").cookie(anna.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(thread, "$.reads[*].userId")).contains(piotr.id());
        byte[] file = mvc.perform(get("/api/chat/attachments/" + attachmentId).cookie(piotr.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(file).containsExactly(1, 2, 3);
        mvc.perform(get("/api/chat/attachments/" + attachmentId).cookie(stranger.cookie()))
                .andExpect(status().isNotFound());
        mvc.perform(multipart("/api/chat/conversations/" + storeChat + "/messages").param("body", " ")
                        .cookie(anna.cookie()).with(csrf()))
                .andExpect(status().isBadRequest());

        // Cofnięcie dla wszystkich: cudzej nie cofnie pracownik, inni widzą bez treści, kierownik z treścią.
        mvc.perform(post("/api/chat/messages/" + messageId + "/retract").cookie(piotr.cookie()).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/chat/messages/" + messageId + "/retract").cookie(anna.cookie()).with(csrf()))
                .andExpect(status().isOk());
        String forPiotr = mvc.perform(get("/api/chat/conversations/" + storeChat + "/messages").cookie(piotr.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(forPiotr, "$.messages[0].retracted")).isTrue();
        assertThat((Object) JsonPath.read(forPiotr, "$.messages[0].body")).isNull();
        assertThat((List<?>) JsonPath.read(forPiotr, "$.messages[0].attachments")).isEmpty();
        mvc.perform(get("/api/chat/attachments/" + attachmentId).cookie(piotr.cookie()))
                .andExpect(status().isNotFound());
        String forManager = mvc.perform(get("/api/chat/conversations/" + storeChat + "/messages")
                        .cookie(manager.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(forManager, "$.messages[0].body")).isEqualTo("Dzień dobry");
        assertThat((Boolean) JsonPath.read(forManager, "$.moderator")).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from chat_messages where id = ?::uuid", Integer.class,
                messageId)).isEqualTo(1);
        mvc.perform(post("/api/chat/messages/" + messageId + "/restore").cookie(anna.cookie()).with(csrf()))
                .andExpect(status().isOk());

        // Grupę zakłada kierownik, nie pracownik; osoby spoza sklepu nie doda.
        mvc.perform(post("/api/chat/groups").cookie(anna.cookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Nabiał", "storeId", storeId, "memberIds", List.of(piotr.id())))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/chat/groups").cookie(manager.cookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Nabiał", "storeId", storeId,
                                "memberIds", List.of(stranger.id())))))
                .andExpect(status().isBadRequest());
        String group = mvc.perform(post("/api/chat/groups").cookie(manager.cookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Nabiał", "storeId", storeId, "memberIds", List.of(anna.id())))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String groupId = JsonPath.read(group, "$.id");
        assertThat((Integer) JsonPath.read(group, "$.memberCount")).isEqualTo(2);
        mvc.perform(get("/api/chat/conversations/" + groupId + "/messages").cookie(piotr.cookie()))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/chat/conversations/" + groupId + "/members").cookie(anna.cookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userIds", List.of(anna.id(), piotr.id())))))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/chat/conversations/" + groupId + "/members").cookie(manager.cookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userIds", List.of(manager.id(), piotr.id())))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/chat/conversations/" + groupId + "/messages").cookie(piotr.cookie()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/chat/conversations/" + groupId + "/messages").cookie(anna.cookie()))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("""
                select count(*) from chat_members where conversation_id = ?::uuid and removed_at is not null""",
                Integer.class, groupId)).isEqualTo(1);

        // Telefon wysyła sam tekst jako JSON.
        String text = mvc.perform(post("/api/chat/conversations/" + storeChat + "/messages").cookie(piotr.cookie())
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("body", "Jestem"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(text, "$.body")).isEqualTo("Jestem");

        // Rozmowa dwóch osób jest jedna na parę; z osobą spoza moich sklepów jej nie ma.
        String direct = mvc.perform(post("/api/chat/direct").param("storeId", storeId).cookie(anna.cookie())
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", piotr.id()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String again = mvc.perform(post("/api/chat/direct").cookie(piotr.cookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("userId", anna.id()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(again, "$.id")).isEqualTo(JsonPath.read(direct, "$.id"));
        assertThat((Boolean) JsonPath.read(direct, "$.local")).isTrue();
        mvc.perform(post("/api/chat/direct").cookie(anna.cookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("userId", stranger.id()))))
                .andExpect(status().isBadRequest());

        // Panel osób: obecność z ostatniego zgłoszenia.
        mvc.perform(post("/api/chat/presence").cookie(piotr.cookie()).with(csrf())).andExpect(status().isNoContent());
        String people = mvc.perform(get("/api/stores/" + storeId + "/chat/people").cookie(anna.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<Boolean>) JsonPath.read(people, "$.others[?(@.userId == '" + piotr.id() + "')].online"))
                .containsExactly(true);
        mvc.perform(get("/api/stores/" + storeId + "/chat/people").cookie(stranger.cookie()))
                .andExpect(status().isForbidden());

        // Właściciel widzi czaty obu sklepów, a czat drugiego sklepu jest dla niego niezależny od bieżącego.
        String ownerList = mvc.perform(get("/api/chat/conversations").param("storeId", storeId).cookie(owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<Boolean>) JsonPath.read(ownerList, "$[?(@.storeId == '" + otherStore + "')].local"))
                .containsExactly(false);
    }

    @Test
    void privateConversationsAndGroupsAreNotModeratedByOutsiders() throws Exception {
        String storeId = store();
        String otherStore = store();
        Account manager = account(storeId, "MANAGER");
        Account anna = account(storeId, "EMPLOYEE");
        Account piotr = account(storeId, "EMPLOYEE");
        Account otherManager = account(otherStore, "MANAGER");
        jdbc.update("insert into user_stores (user_id, store_id) values (?::uuid, ?::uuid)", anna.id(), otherStore);
        // Kierownik drugiego sklepu ma dostęp do pierwszego bez rangi: CHAT:EDIT ma tylko u siebie.
        jdbc.update("insert into user_stores (user_id, store_id) values (?::uuid, ?::uuid)", otherManager.id(),
                storeId);

        // Rozmowa dwóch osób: założyciel nie cofnie cudzej wiadomości i nie widzi tego, co druga osoba cofnęła.
        String direct = JsonPath.read(mvc.perform(post("/api/chat/direct").cookie(anna.cookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("userId", piotr.id()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        String fromPiotr = send(direct, piotr, "Prywatnie");
        mvc.perform(post("/api/chat/messages/" + fromPiotr + "/retract").cookie(anna.cookie()).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/chat/messages/" + fromPiotr + "/retract").cookie(piotr.cookie()).with(csrf()))
                .andExpect(status().isOk());
        String forAnna = mvc.perform(get("/api/chat/conversations/" + direct + "/messages").cookie(anna.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(forAnna, "$.moderator")).isFalse();
        assertThat((Object) JsonPath.read(forAnna, "$.messages[0].body")).isNull();

        // Kierownik w rozmowie z pracownikiem też nie jest moderatorem.
        String withManager = JsonPath.read(mvc.perform(post("/api/chat/direct").cookie(manager.cookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("userId", piotr.id()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        String toManager = send(withManager, piotr, "Mam sprawę");
        mvc.perform(post("/api/chat/messages/" + toManager + "/retract").cookie(manager.cookie()).with(csrf()))
                .andExpect(status().isForbidden());

        // Grupę niezależną od sklepu prowadzi założyciel, a nie członek z uprawnieniem do czatu w innym sklepie.
        String group = JsonPath.read(mvc.perform(post("/api/chat/groups").cookie(manager.cookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Wspólna", "memberIds", List.of(anna.id(), otherManager.id())))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String forOther = mvc.perform(get("/api/chat/conversations/" + group + "/messages")
                        .cookie(otherManager.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(forOther, "$.conversation.canManage")).isFalse();
        mvc.perform(put("/api/chat/conversations/" + group + "/members").cookie(otherManager.cookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userIds", List.of(otherManager.id())))))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/chat/conversations/" + group + "/name").cookie(otherManager.cookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("name", "Moja"))))
                .andExpect(status().isForbidden());
        String fromAnna = send(group, anna, "Cześć");
        mvc.perform(post("/api/chat/messages/" + fromAnna + "/retract").cookie(otherManager.cookie()).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/chat/messages/" + fromAnna + "/retract").cookie(manager.cookie()).with(csrf()))
                .andExpect(status().isOk());

        // Grupa sklepu: kto stracił dostęp do sklepu, nie czyta jej i nie jest liczony jako członek.
        String storeGroup = JsonPath.read(mvc.perform(post("/api/chat/groups").cookie(manager.cookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Kasa", "storeId", storeId, "memberIds", List.of(anna.id())))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        jdbc.update("delete from user_stores where user_id = ?::uuid and store_id = ?::uuid", anna.id(), storeId);
        mvc.perform(get("/api/chat/conversations/" + storeGroup + "/messages").cookie(anna.cookie()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/chat/conversations/" + storeGroup + "/messages").cookie(anna.cookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("body", "Jeszcze tu?"))))
                .andExpect(status().isNotFound());
        String annaList = mvc.perform(get("/api/chat/conversations").cookie(anna.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(annaList, "$[*].id")).doesNotContain(storeGroup).contains(group);
        String managerList = mvc.perform(get("/api/chat/conversations").cookie(manager.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<Integer>) JsonPath.read(managerList, "$[?(@.id == '" + storeGroup + "')].memberCount"))
                .containsExactly(1);

        // Panel osób: godziny zmian i odbić tylko z dostępem do Czasu pracy.
        String people = mvc.perform(get("/api/stores/" + storeId + "/chat/people").cookie(piotr.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(people, "$.hours")).isFalse();
        String forOwner = mvc.perform(get("/api/stores/" + storeId + "/chat/people").cookie(owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(forOwner, "$.hours")).isTrue();
    }

    @Test
    void bothPeopleOpeningConversationAtOnceGetTheSameOne() throws Exception {
        String storeId = store();
        for (int round = 0; round < 5; round++) {
            Account anna = account(storeId, "EMPLOYEE");
            Account piotr = account(storeId, "EMPLOYEE");
            java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.Callable<String> fromAnna = () -> openDirect(anna, piotr, start);
            java.util.concurrent.Callable<String> fromPiotr = () -> openDirect(piotr, anna, start);
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var first = pool.submit(fromAnna);
                var second = pool.submit(fromPiotr);
                assertThat(first.get()).isEqualTo(second.get());
            }
            assertThat(jdbc.queryForObject("""
                    select count(*) from chat_conversations where kind = 'DIRECT' and direct_key like ?""",
                    Integer.class, "%" + anna.id() + "%")).isEqualTo(1);
        }
    }

    @Test
    void mentionsPeopleDepartmentAndEveryone() throws Exception {
        String storeId = store();
        Account manager = account(storeId, "MANAGER");
        Account anna = account(storeId, "EMPLOYEE");
        Account piotr = account(storeId, "EMPLOYEE");
        Account kasia = account(storeId, "EMPLOYEE");
        Account stranger = account(store(), "EMPLOYEE");

        // Anna i Piotr pracują w garmażu (stałe stanowisko w Pracownikach), Kasia nie.
        String departments = mvc.perform(post("/api/stores/" + storeId + "/staff/departments").cookie(owner)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("name", "Garmaż"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String deli = JsonPath.<List<String>>read(departments, "$[?(@.name == 'Garmaż')].id").getFirst();
        String structure = mvc.perform(post("/api/stores/" + storeId + "/staff/positions").cookie(owner)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("departmentId", deli, "name", "Garmażer",
                                "roleId", TestRanks.defaultRank(jdbc, storeId, "EMPLOYEE"), "shifts", List.of()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String cook = JsonPath.<List<String>>read(structure, "$[*].positions[?(@.name == 'Garmażer')].id").getFirst();
        for (Account worker : List.of(anna, piotr)) {
            mvc.perform(post("/api/stores/" + storeId + "/staff/employees/existing").cookie(owner).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("userId", worker.id(), "positionId", cook, "shifts", List.of()))))
                    .andExpect(status().isCreated());
        }

        String list = mvc.perform(get("/api/chat/conversations").cookie(kasia.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String storeChat = JsonPath.<List<String>>read(list, "$[?(@.kind == 'STORE')].id").getFirst();
        String thread = mvc.perform(get("/api/chat/conversations/" + storeChat + "/messages").cookie(kasia.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(thread, "$.departments[*].name")).containsExactly("Garmaż");
        assertThat((Boolean) JsonPath.read(thread, "$.canMentionAll")).isFalse();

        // @dział oznacza członków działu, @osoba jedną osobę; autor nie jest oznaczony sam przez siebie.
        String sent = mvc.perform(multipart("/api/chat/conversations/" + storeChat + "/messages")
                        .param("body", "@Garmaż i @" + "Osoba proszę o sałatki")
                        .param("mentions", "department:" + deli, "user:" + manager.id())
                        .cookie(kasia.cookie()).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(sent, "$.mentions[*].kind")).containsExactly("DEPARTMENT", "USER");
        assertThat(JsonPath.<String>read(sent, "$.mentions[0].label")).isEqualTo("Garmaż");
        for (Account mentioned : List.of(anna, piotr, manager)) {
            String forThem = mvc.perform(get("/api/chat/conversations/" + storeChat + "/messages")
                            .cookie(mentioned.cookie()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat((Boolean) JsonPath.read(forThem, "$.messages[-1].mentionsMe")).isTrue();
        }
        String forKasia = mvc.perform(get("/api/chat/conversations/" + storeChat + "/messages").cookie(kasia.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(forKasia, "$.messages[-1].mentionsMe")).isFalse();
        String annaList = mvc.perform(get("/api/chat/conversations").cookie(anna.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(annaList, "$[?(@.id == '" + storeChat + "')].unreadMentions"))
                .containsExactly(1);

        // Osoby spoza rozmowy nie oznaczysz; @wszyscy w czacie sklepu tylko zarządzający czatem.
        mvc.perform(post("/api/chat/conversations/" + storeChat + "/messages").cookie(kasia.cookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("body", "Hej", "mentions", List.of("user:" + stranger.id())))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/chat/conversations/" + storeChat + "/messages").cookie(kasia.cookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("body", "@wszyscy", "mentions", List.of("all")))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/chat/conversations/" + storeChat + "/messages").cookie(manager.cookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("body", "@wszyscy zebranie", "mentions", List.of("all")))))
                .andExpect(status().isCreated());
        String forKasiaAll = mvc.perform(get("/api/chat/conversations/" + storeChat + "/messages")
                        .cookie(kasia.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(forKasiaAll, "$.messages[-1].mentionsMe")).isTrue();
    }

    @Test
    void storeChatNameAndLogoAreChangedByChatManager() throws Exception {
        String storeId = store();
        Account manager = account(storeId, "MANAGER");
        Account anna = account(storeId, "EMPLOYEE");
        String list = mvc.perform(get("/api/chat/conversations").cookie(anna.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String storeChat = JsonPath.<List<String>>read(list, "$[?(@.kind == 'STORE')].id").getFirst();
        assertThat(JsonPath.<List<Boolean>>read(list, "$[?(@.kind == 'STORE')].canEdit")).containsExactly(false);

        mvc.perform(put("/api/chat/conversations/" + storeChat + "/name").cookie(anna.cookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("name", "Moja nazwa"))))
                .andExpect(status().isForbidden());
        String renamed = mvc.perform(put("/api/chat/conversations/" + storeChat + "/name").cookie(manager.cookie())
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Intermarché Leszno"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(renamed, "$.name")).isEqualTo("Intermarché Leszno");

        // Logo: tylko obrazek, zwykły pracownik go nie zmieni, widzi go każdy z czatu.
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 0};
        mvc.perform(multipart(org.springframework.http.HttpMethod.PUT, "/api/chat/conversations/" + storeChat + "/logo")
                        .file(new MockMultipartFile("file", "logo.png", "image/png", png))
                        .cookie(anna.cookie()).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(multipart(org.springframework.http.HttpMethod.PUT, "/api/chat/conversations/" + storeChat + "/logo")
                        .file(new MockMultipartFile("file", "logo.txt", "text/plain", "tekst".getBytes()))
                        .cookie(manager.cookie()).with(csrf()))
                .andExpect(status().isBadRequest());
        String changed = mvc.perform(multipart(org.springframework.http.HttpMethod.PUT,
                                "/api/chat/conversations/" + storeChat + "/logo")
                        .file(new MockMultipartFile("file", "logo.png", "image/png", png))
                        .cookie(manager.cookie()).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String logoUrl = JsonPath.read(changed, "$.conversation.logoUrl");
        byte[] served = mvc.perform(get(logoUrl).cookie(anna.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(served).isEqualTo(png);

        // Usunięcie to znacznik: „Przywróć” oddaje to samo logo.
        String removed = mvc.perform(delete("/api/chat/conversations/" + storeChat + "/logo").cookie(manager.cookie())
                        .with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(removed, "$.conversation.logoUrl")).isNull();
        String previous = JsonPath.read(removed, "$.previousLogoId");
        String restored = mvc.perform(post("/api/chat/conversations/" + storeChat + "/logo/" + previous + "/restore")
                        .cookie(manager.cookie()).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(restored, "$.logoUrl")).isEqualTo(logoUrl);
    }

    private String openDirect(Account me, Account other, java.util.concurrent.CyclicBarrier start) throws Exception {
        start.await();
        return JsonPath.read(mvc.perform(post("/api/chat/direct").cookie(me.cookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("userId", other.id()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String send(String conversationId, Account author, String body) throws Exception {
        return JsonPath.read(mvc.perform(post("/api/chat/conversations/" + conversationId + "/messages")
                        .cookie(author.cookie()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("body", body))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private record Account(String id, Cookie cookie) {
    }

    private Account account(String storeId, String rankKey) throws Exception {
        String username = "chat" + UUID.randomUUID().toString().substring(0, 8);
        String userId = jdbc.queryForObject("""
                insert into users (username, display_name, password_hash, role_id)
                values (?, ?, ?, (select id from roles where system_key = 'ACCOUNT')) returning id""", String.class,
                username, "Osoba " + username, passwordEncoder.encode("haslo-pracownika"));
        jdbc.update("insert into user_stores (user_id, store_id) values (?::uuid, ?::uuid)", userId, storeId);
        TestRanks.assign(jdbc, userId, storeId, TestRanks.defaultRank(jdbc, storeId, rankKey));
        return new Account(userId, login(username, "haslo-pracownika"));
    }

    private Cookie login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }

    private String store() throws Exception {
        String number = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999_999L));
        String json = mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep " + number + "\",\"active\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private static String json(Map<String, ?> value) {
        return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(value);
    }
}
