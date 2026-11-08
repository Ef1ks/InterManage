package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.intermanage.backend.auth.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class StaffTimeIntegrationTests {

    private static final String DAY = "2026-12-01";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    private Cookie owner;
    private String store;
    private String department;
    private String position;
    private String employee;

    @BeforeEach
    void setUp() throws Exception {
        owner = login("wlasciciel", "haslo-wlasciciela");
        store = createStore(randomNip());
        String role = createRole("{\"SALES\":\"VIEW\"}");
        String departments = send(owner, post(staff("/departments")), "{\"name\":\"Kasy\"}")
                .andReturn().getResponse().getContentAsString();
        department = JsonPath.read(departments, "$[0].id");
        String positions = send(owner, post(staff("/positions")), "{\"departmentId\":\"" + department
                + "\",\"name\":\"Kasjer\",\"roleId\":\"" + role + "\",\"shifts\":[]}")
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(positions, "$[?(@.name=='Kasy')].positions[0].id");
        position = ids.getFirst();
        employee = invite();
        send(owner, post(staff("/records/" + employee + "/contracts")),
                "{\"kind\":\"EMPLOYMENT\",\"employmentType\":\"INDEFINITE\",\"startDate\":\"2026-01-01\","
                        + "\"fteNumerator\":1,\"fteDenominator\":1,\"fixedTermExempt\":false}")
                .andExpect(status().isOk());
        send(owner, put(staff("/schedule/entries")), "{\"departmentId\":\"" + department
                + "\",\"month\":\"2026-12\",\"changes\":[{\"employeeId\":\"" + employee + "\",\"date\":\"" + DAY
                + "\",\"start\":\"06:00\",\"end\":\"14:00\",\"storeId\":null}]}")
                .andExpect(status().isOk());
    }

    @Test
    void timeOutsideScheduleWaitsForDecisionAndPrints() throws Exception {
        simulate("05:55");
        simulate("14:05");
        day().andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].days[0].worked").value(480))
                .andExpect(jsonPath("$.rows[0].days[0].pending").value(10))
                .andExpect(jsonPath("$.rows[0].days[0].punches", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].days[0].segments[0].part").value("OVERTIME"))
                .andExpect(jsonPath("$.rows[0].days[0].segments[0].status").value("PENDING"));

        String id = JsonPath.read(decide("OVERTIME", "05:55", "06:00", "EMPLOYER_ORDER")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        decide("OVERTIME", "14:00", "14:05", "REJECTED").andExpect(status().isOk());
        day().andExpect(jsonPath("$.rows[0].days[0].worked").value(485))
                .andExpect(jsonPath("$.rows[0].days[0].overtime").value(5))
                .andExpect(jsonPath("$.rows[0].days[0].pending").value(0))
                .andExpect(jsonPath("$.rows[0].totals.worked").value(485));

        // Bez danych pracodawcy (a w testach bez klucza GUS) wniosek nie dostaje nazwy sklepu z aplikacji.
        send(owner, get(staff("/time/settings")), null)
                .andExpect(jsonPath("$.employer.saved").value(false))
                .andExpect(jsonPath("$.employer.fromGus").value(false))
                .andExpect(jsonPath("$.employer.name").isEmpty());
        send(owner, get(staff("/time/decisions/" + id + "/pdf")), null).andExpect(status().isConflict());
        send(owner, put(staff("/time/settings/employer")),
                "{\"name\":\"TRYTOMA Sp. z o.o.\",\"address\":\"ul. Polna 1, 65-001 Zielona Góra\"}")
                .andExpect(jsonPath("$.employer.saved").value(true))
                .andExpect(jsonPath("$.employer.name").value("TRYTOMA Sp. z o.o."));
        send(owner, get(staff("/time/decisions/" + id + "/pdf")), null)
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));

        // Cofnięcie decyzji to znacznik: odcinek znów czeka, a przywrócenie go przyjmuje.
        send(owner, delete(staff("/time/decisions/" + id)), null).andExpect(status().isNoContent());
        day().andExpect(jsonPath("$.rows[0].days[0].pending").value(5));
        send(owner, post(staff("/time/decisions/" + id + "/restore")), null).andExpect(status().isNoContent());
        day().andExpect(jsonPath("$.rows[0].days[0].worked").value(485));

        // Odcinka, którego nie ma w karcie, nie da się rozstrzygnąć.
        decide("OVERTIME", "15:00", "16:00", "EMPLOYER_ORDER").andExpect(status().isConflict());
    }

    @Test
    void signedRequestIsAttachedReplacedAndRestored() throws Exception {
        simulate("05:50");
        simulate("14:10");
        String request = JsonPath.read(decide("OVERTIME", "05:50", "06:00", "EMPLOYEE_REQUEST")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        String rejected = JsonPath.read(decide("OVERTIME", "14:00", "14:10", "REJECTED")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        byte[] pdf = "%PDF-1.4 podpisany wniosek".getBytes();

        // Plik dołącza się tylko do polecenia albo wniosku; nie-PDF i nie-zdjęcie jest odrzucany.
        attach(rejected, "wniosek.pdf", pdf).andExpect(status().isBadRequest());
        attach(request, "wniosek.txt", "zwykly tekst".getBytes()).andExpect(status().isBadRequest());

        String first = JsonPath.read(attach(request, "wniosek.pdf", pdf).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");
        day().andExpect(jsonPath("$.rows[0].days[0].segments[0].signedFile.id").value(first))
                .andExpect(jsonPath("$.rows[0].days[0].segments[0].signedFile.fileName").value("wniosek.pdf"))
                .andExpect(jsonPath("$.rows[0].days[0].segments[2].signedFile").doesNotExist());
        send(owner, get(staff("/time/decisions/" + request + "/file")), null)
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(pdf));

        // Wymiana zostawia poprzedni plik ze znacznikiem.
        attach(request, "skan.pdf", pdf).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("""
                select count(*) from staff_time_decision_files where decision_id = ?::uuid and removed_at is not null""",
                Long.class, request)).isOne();

        send(owner, delete(staff("/time/decisions/" + request + "/file")), null).andExpect(status().isNoContent());
        send(owner, get(staff("/time/decisions/" + request + "/file")), null).andExpect(status().isNotFound());
        send(owner, post(staff("/time/decisions/" + request + "/file/" + first + "/restore")), null)
                .andExpect(status().isNoContent());
        day().andExpect(jsonPath("$.rows[0].days[0].segments[0].signedFile.id").value(first));
    }

    @Test
    void mandateGetsHourRecordFromPunchesAndOthersAreListedWithReason() throws Exception {
        String mandate = invite();
        send(owner, post(staff("/records/" + mandate + "/contracts")),
                "{\"kind\":\"MANDATE\",\"startDate\":\"2026-01-01\",\"fteNumerator\":1,\"fteDenominator\":1,"
                        + "\"fixedTermExempt\":false}")
                .andExpect(status().isOk());
        invite();
        // Zlecenie: ewidencja godzin z samych odbić (art. 8a ustawy o minimalnym wynagrodzeniu), bez grafiku nie ma
        // czasu czekającego na decyzję.
        for (String time : List.of("08:00", "12:30")) {
            send(owner, post(staff("/time/punches")), "{\"employeeId\":\"" + mandate + "\",\"at\":\"" + DAY + "T"
                    + time + ":00\"}").andExpect(status().isNoContent());
        }
        day().andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[?(@.civil == true)].employeeId").value(mandate))
                .andExpect(jsonPath("$.rows[?(@.civil == true)].days[0].worked").value(270))
                .andExpect(jsonPath("$.rows[?(@.civil == true)].days[0].pending").value(0))
                .andExpect(jsonPath("$.withoutCard", hasSize(1)))
                .andExpect(jsonPath("$.withoutCard[0].reason").value("brak umowy w Kadrach"));
    }

    private ResultActions attach(String decisionId, String name, byte[] bytes) throws Exception {
        return mvc.perform(multipart(HttpMethod.PUT, staff("/time/decisions/" + decisionId + "/file"))
                .file(new MockMultipartFile("file", name, "application/octet-stream", bytes))
                .cookie(owner).with(csrf()));
    }

    @Test
    void lateArrivalCanBeJustifiedAndToleranceSkipsSmallDifferences() throws Exception {
        simulate("06:10");
        simulate("13:57");
        day().andExpect(jsonPath("$.rows[0].days[0].worked").value(467))
                .andExpect(jsonPath("$.rows[0].days[0].shortfall").value(13));
        decide("SHORTFALL", "06:00", "06:10", "JUSTIFIED").andExpect(status().isOk());
        day().andExpect(jsonPath("$.rows[0].days[0].worked").value(477));

        send(owner, put(staff("/schedule/settings/" + department)),
                "{\"managerIds\":[],\"minimums\":[],\"toleranceMinutes\":5}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toleranceMinutes").value(5));
        day().andExpect(jsonPath("$.toleranceMinutes").value(5))
                .andExpect(jsonPath("$.rows[0].days[0].worked").value(480))
                .andExpect(jsonPath("$.rows[0].days[0].shortfall").value(0));
    }

    @Test
    void lateArrivalIsBalancedAndAttentionCountsOpenDays() throws Exception {
        // Licznik w menu liczy ostatnie 31 dni do dziś: wczoraj spóźnienie i dłuższe zostanie czekają na reakcję.
        String yesterday = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Warsaw")).minusDays(1).toString();
        send(owner, put(staff("/schedule/entries")), "{\"departmentId\":\"" + department + "\",\"month\":\""
                + yesterday.substring(0, 7) + "\",\"changes\":[{\"employeeId\":\"" + employee + "\",\"date\":\""
                + yesterday + "\",\"start\":\"06:00\",\"end\":\"14:00\",\"storeId\":null}]}")
                .andExpect(status().isOk());
        for (String time : List.of("07:00", "15:00")) {
            send(owner, post(staff("/time/punches")), "{\"employeeId\":\"" + employee + "\",\"at\":\""
                    + yesterday + "T" + time + ":00\"}").andExpect(status().isNoContent());
        }
        send(owner, get(staff("/time/attention")), null)
                .andExpect(jsonPath("$.departments[0].departmentId").value(department))
                .andExpect(jsonPath("$.departments[0].count").value(1));

        simulate("07:00");
        simulate("15:00");
        day().andExpect(jsonPath("$.rows[0].days[0].shortfall").value(60))
                .andExpect(jsonPath("$.rows[0].days[0].pending").value(60));

        String body = "{\"employeeId\":\"" + employee + "\",\"kind\":\"BALANCE\",\"start\":\"" + DAY
                + "T00:00:00\",\"end\":\"2026-12-02T00:00:00\",\"decision\":\"OFFSET\"}";
        send(owner, post(staff("/time/decisions?departmentId=" + department)), body).andExpect(status().isOk());
        day().andExpect(jsonPath("$.rows[0].days[0].worked").value(480))
                .andExpect(jsonPath("$.rows[0].days[0].shortfall").value(0))
                .andExpect(jsonPath("$.rows[0].days[0].pending").value(0))
                .andExpect(jsonPath("$.rows[0].days[0].overtime").value(0));
        // Drugi raz nie ma już czego równoważyć.
        send(owner, post(staff("/time/decisions?departmentId=" + department)), body).andExpect(status().isConflict());
    }

    @Test
    void readerPunchesWithCardAndKey() throws Exception {
        String settings = send(owner, post(staff("/time/settings/readers")), "{\"name\":\"Wejście\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(settings, "$.readers[0].token");
        send(owner, put(staff("/time/settings/cards/" + employee)), "{\"cardUid\":\"04:a2:b3:c4\"}")
                .andExpect(status().isOk());
        send(owner, get(staff("/time/settings")), null)
                .andExpect(jsonPath("$.readers[0].token").doesNotExist())
                .andExpect(jsonPath("$.cards[0].cardUid").value("04A2B3C4"));

        reader(token, "04A2B3C4", "2026-12-01T04:58:00Z").andExpect(status().isOk())
                .andExpect(jsonPath("$.direction").value("IN"));
        reader(token, "04A2B3C4", "2026-12-01T13:02:00Z").andExpect(status().isOk())
                .andExpect(jsonPath("$.direction").value("OUT"));
        day().andExpect(jsonPath("$.rows[0].days[0].punches[0].source").value("READER"))
                .andExpect(jsonPath("$.rows[0].days[0].punches[0].at").value("2026-12-01T05:58:00"));

        reader("zly-klucz", "04A2B3C4", null).andExpect(status().isForbidden());
        reader(token, "FFFF", null).andExpect(status().isNotFound());
    }

    /**
     * Karta ewidencji i zestawienia miesiąca: dzień, który minął, ma czas z odbić, w tym pora nocna (5-6). Excel i
     * plik R2Płatnika wskazują pracownika numerem karty RCP nadanym przy zakładaniu, a godziny Excel ma dziesiętne.
     */
    @Test
    void monthlySheetPrintsCardAndExportsHoursWithRcpNumber() throws Exception {
        String past = "2026-11-02";
        send(owner, put(staff("/schedule/entries")), "{\"departmentId\":\"" + department
                + "\",\"month\":\"2026-11\",\"changes\":[{\"employeeId\":\"" + employee + "\",\"date\":\"" + past
                + "\",\"start\":\"05:00\",\"end\":\"13:00\",\"storeId\":null}]}")
                .andExpect(status().isOk());
        for (String time : List.of("05:00", "13:00")) {
            send(owner, post(staff("/time/punches")), "{\"employeeId\":\"" + employee + "\",\"at\":\"" + past + "T"
                    + time + ":00\"}").andExpect(status().isNoContent());
        }
        Integer rcp = jdbc.queryForObject("select rcp_number from staff_employees where id = ?::uuid", Integer.class,
                employee);
        assertThat(rcp).isGreaterThan(1000);
        send(owner, get(staff("/time/settings")), null).andExpect(jsonPath("$.cards[0].rcpNumber").value(rcp));

        send(owner, get(staff("/time/sheet/pdf?departmentId=" + department + "&month=2026-11&employeeId=" + employee)),
                null).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
        send(owner, get(staff("/time/sheet/pdf?departmentId=" + department + "&month=2026-11&employeeId="
                + UUID.randomUUID())), null).andExpect(status().isNotFound());

        byte[] xlsx = send(owner, get(staff("/time/sheet/xlsx?month=2026-11")), null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String sheet = sheetXml(xlsx);
        assertThat(sheet).contains("<v>" + rcp + "</v>").contains("<v>8</v>").contains("<v>1</v>");

        // Plik dla R2Płatnika: godziny zaliczone takie jak wejście i wyjście.
        String rcpFile = send(owner, get(staff("/time/sheet/rcp?month=2026-11")), null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.Charset.forName("windows-1250"));
        assertThat(rcpFile).isEqualTo(rcp + ";02-11-2026;5:00;13:00;5:00;13:00;\r\n");

        String timeOnly = createRole("{\"STAFF_TIME\":\"VIEW\"}");
        Cookie viewer = loginUser(user(timeOnly));
        send(viewer, get(staff("/time/sheet/rcp?month=2026-11")), null).andExpect(status().isOk());
        send(viewer, get(staff("/time/sheet/pdf?departmentId=" + department + "&month=2026-11")), null)
                .andExpect(status().isOk());
        send(loginUser(user(createRole("{\"SALES\":\"VIEW\"}"))), get(staff("/time/sheet/xlsx?month=2026-11")),
                null).andExpect(status().isForbidden());
    }

    private static String sheetXml(byte[] xlsx) throws Exception {
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(xlsx))) {
            for (java.util.zip.ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (entry.getName().equals("xl/worksheets/sheet1.xml")) {
                    return new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("Brak arkusza w pliku");
    }

    @Test
    void decisionsNeedTimePermissionAndOwnDepartment() throws Exception {
        simulate("05:30");
        simulate("14:00");
        String viewer = createRole("{\"STAFF_TIME\":\"VIEW\"}");
        Cookie viewerCookie = loginUser(user(viewer));
        send(viewerCookie, get(staff("/time?departmentId=" + department + "&from=" + DAY + "&to=" + DAY)), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canDecide").value(false))
                .andExpect(jsonPath("$.canSimulate").value(false));
        send(viewerCookie, post(staff("/time/punches")), "{\"employeeId\":\"" + employee + "\"}")
                .andExpect(status().isForbidden());

        String editor = createRole("{\"STAFF_TIME\":\"EDIT\"}");
        Cookie editorCookie = loginUser(user(editor));
        send(editorCookie, post(staff("/time/decisions?departmentId=" + department)), decision("OVERTIME", "05:30",
                "06:00", "EMPLOYER_ORDER")).andExpect(status().isForbidden());

        String none = createRole("{\"SALES\":\"VIEW\"}");
        send(loginUser(user(none)), get(staff("/time?departmentId=" + department + "&from=" + DAY + "&to=" + DAY)),
                null).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from staff_time_decisions where employee_id = ?::uuid",
                Long.class, employee)).isZero();
    }

    /**
     * Saldo okresu w grafiku: za dzień, który minął, liczy się karta (praca i zatwierdzone nadgodziny), a godzina ponad
     * 8 h to nadgodziny dobowe poza saldem. Nadwyżka z listopada obniża cel grudnia.
     */
    @Test
    void scheduleBalanceUsesTimeCardsOfPastDays() throws Exception {
        String past = "2026-11-02";
        send(owner, put(staff("/schedule/entries")), "{\"departmentId\":\"" + department
                + "\",\"month\":\"2026-11\",\"changes\":[{\"employeeId\":\"" + employee + "\",\"date\":\"" + past
                + "\",\"start\":\"06:00\",\"end\":\"12:00\",\"storeId\":null}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].periodMinutes").value(360 + 480));
        for (String time : List.of("05:00", "14:00")) {
            send(owner, post(staff("/time/punches")), "{\"employeeId\":\"" + employee + "\",\"at\":\"" + past + "T"
                    + time + ":00\"}").andExpect(status().isNoContent());
        }
        for (String[] segment : List.of(new String[] {"05:00", "06:00"}, new String[] {"12:00", "14:00"})) {
            send(owner, post(staff("/time/decisions?departmentId=" + department)), "{\"employeeId\":\"" + employee
                    + "\",\"kind\":\"OVERTIME\",\"start\":\"" + past + "T" + segment[0] + ":00\",\"end\":\"" + past
                    + "T" + segment[1] + ":00\",\"decision\":\"EMPLOYER_ORDER\"}").andExpect(status().isOk());
        }

        String november = send(owner, get(staff("/schedule?departmentId=" + department + "&month=2026-11")), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].periodMinutes").value(480 + 480))
                .andExpect(jsonPath("$.rows[0].monthMinutes").value(480))
                .andExpect(jsonPath("$.rows[0].dailyOvertimeMinutes").value(60))
                .andExpect(jsonPath("$.rows[0].actualUntil").isNotEmpty())
                .andExpect(jsonPath("$.rows[0].carriedMinutes").isEmpty())
                .andReturn().getResponse().getContentAsString();
        List<String> messages = JsonPath.read(november, "$.rows[0].warnings[*].message");
        assertThat(messages).anyMatch(message -> message.startsWith("Nadgodziny dobowe do odebrania: 1 h"));
        int novemberNorm = JsonPath.read(november, "$.rows[0].monthNormMinutes");
        int periodNorm = JsonPath.read(november, "$.rows[0].periodNormMinutes");

        String december = send(owner, get(staff("/schedule?departmentId=" + department + "&month=2026-12")), null)
                .andReturn().getResponse().getContentAsString();
        int decemberNorm = JsonPath.read(december, "$.rows[0].monthNormMinutes");
        int carried = JsonPath.read(december, "$.rows[0].carriedMinutes");
        // Listopad w toku nie przenosi niedoboru (zakończony przenosi całe saldo), a pusty styczeń nie zrzuca wymiaru.
        assertThat(carried).isEqualTo(java.time.YearMonth.now(com.intermanage.backend.common.component.StoreTime.ZONE)
                .isAfter(java.time.YearMonth.of(2026, 11)) ? 480 - novemberNorm : 0);
        assertThat((int) JsonPath.read(december, "$.rows[0].monthTargetMinutes")).isEqualTo(decemberNorm - carried);

        // Saldo do okna decyzji w Czasie pracy liczy się tak samo.
        send(owner, get(staff("/time/balance?employeeId=" + employee + "&date=" + past)), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workedMinutes").value(960))
                .andExpect(jsonPath("$.normMinutes").value(periodNorm))
                .andExpect(jsonPath("$.toCompensateMinutes").value(60))
                .andExpect(jsonPath("$.dailyLimitMinutes").value(480));

        // Czas wolny na wniosek rozlicza godzinę za godzinę i liczy się do wymiaru jak praca.
        timeOff(60, "EMPLOYEE").andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].timeOffMinutes").value(60))
                .andExpect(jsonPath("$.rows[0].toCompensateMinutes").value(0))
                .andExpect(jsonPath("$.rows[0].periodMinutes").value(960 + 60))
                .andExpect(jsonPath("$.rows[0].cells[0].timeOffMinutes").value(60))
                .andExpect(jsonPath("$.rows[0].cells[0].timeOffInitiative").value("EMPLOYEE"));
        // Z inicjatywy pracodawcy półtorej godziny za godzinę: 60 minut rozlicza 40.
        timeOff(60, "EMPLOYER").andExpect(jsonPath("$.rows[0].toCompensateMinutes").value(20));
        timeOff(null, null).andExpect(jsonPath("$.rows[0].toCompensateMinutes").value(60))
                .andExpect(jsonPath("$.rows[0].timeOffMinutes").value(0));
        Integer removed = jdbc.queryForObject(
                "select count(*) from staff_overtime_time_off where employee_id = ?::uuid and deleted_at is not null",
                Integer.class, employee);
        assertThat(removed).isEqualTo(2);

        // W dzień wolny W5 czasu wolnego za nadgodziny się nie wpisuje.
        send(owner, put(staff("/schedule/entries")), "{\"departmentId\":\"" + department
                + "\",\"month\":\"2026-12\",\"changes\":[{\"employeeId\":\"" + employee + "\",\"date\":\"2026-12-02"
                + "\",\"start\":null,\"end\":null,\"storeId\":null,\"dayOff\":\"W5\"}]}").andExpect(status().isOk());
        send(owner, put(staff("/schedule/time-off")), "{\"departmentId\":\"" + department
                + "\",\"month\":\"2026-12\",\"employeeId\":\"" + employee
                + "\",\"date\":\"2026-12-02\",\"minutes\":60,\"initiative\":\"EMPLOYEE\"}")
                .andExpect(status().isConflict());
    }

    /**
     * Zlecenie u własnego pracodawcy ma osobny wiersz grafiku: jego godziny nie wchodzą do wymiaru umowy o pracę, dzień
     * z obiema umowami daje ostrzeżenie, a odbicia w godzinach zlecenia nie są nadgodzinami w karcie.
     */
    @Test
    void mandateHoursHaveOwnRow() throws Exception {
        civilShift("14:00", "18:00").andExpect(status().isBadRequest());
        send(owner, post(staff("/records/" + employee + "/contracts")),
                "{\"kind\":\"MANDATE\",\"startDate\":\"2026-01-01\",\"fteNumerator\":1,\"fteDenominator\":1,"
                        + "\"fixedTermExempt\":false}")
                .andExpect(status().isOk());
        String body = civilShift("14:00", "18:00").andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].civil").value(false))
                .andExpect(jsonPath("$.rows[0].monthMinutes").value(480))
                .andExpect(jsonPath("$.rows[1].civil").value(true))
                .andExpect(jsonPath("$.rows[1].monthMinutes").value(240))
                .andExpect(jsonPath("$.rows[1].cells[0].start").value("14:00"))
                .andReturn().getResponse().getContentAsString();
        List<String> messages = JsonPath.read(body, "$.rows[0].warnings[*].message");
        assertThat(messages).anyMatch(message -> message.startsWith("Umowa o pracę i zlecenie tego dnia: razem 12 h"));
        // Ta sama osoba: obsada liczy ją raz.
        List<Integer> counts = JsonPath.read(body, "$.positions[0].counts");
        assertThat(counts.getFirst()).isEqualTo(1);

        civilShift("13:00", "15:00").andExpect(status().isConflict());

        simulate("06:00");
        simulate("18:00");
        day().andExpect(jsonPath("$.rows[0].days[0].worked").value(480))
                .andExpect(jsonPath("$.rows[0].days[0].pending").value(0))
                .andExpect(jsonPath("$.rows[0].days[0].civilStart").value(DAY + "T14:00:00"));
    }

    /** Wynagrodzenia w grafiku: tylko w dziale, który je liczy, ze stawką od miesiąca. */
    @Test
    void payIsShownOnlyWhenDepartmentCountsIt() throws Exception {
        rate("30.00").andExpect(status().isConflict());
        send(owner, put(staff("/schedule/settings/" + department)),
                "{\"managerIds\":[],\"minimums\":[],\"payEnabled\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.payEnabled").value(true));
        rate("30.00").andExpect(status().isOk())
                .andExpect(jsonPath("$.showsPay").value(true))
                .andExpect(jsonPath("$.rows[0].hourlyRate").value(30.0))
                .andExpect(jsonPath("$.rows[0].pay").value(240.0));
        // Stawka od grudnia nie dotyczy listopada.
        send(owner, get(staff("/schedule?departmentId=" + department + "&month=2026-11")), null)
                .andExpect(jsonPath("$.rows[0].hourlyRate").isEmpty());
        send(owner, put(staff("/schedule/settings/" + department)),
                "{\"managerIds\":[],\"minimums\":[],\"payEnabled\":false}").andExpect(status().isOk());
        send(owner, get(staff("/schedule?departmentId=" + department + "&month=2026-12")), null)
                .andExpect(jsonPath("$.showsPay").value(false))
                .andExpect(jsonPath("$.rows[0].pay").isEmpty());
    }

    private ResultActions rate(String rate) throws Exception {
        return send(owner, put(staff("/schedule/rates")), "{\"departmentId\":\"" + department
                + "\",\"month\":\"2026-12\",\"employeeId\":\"" + employee + "\",\"civil\":false,\"rate\":" + rate + "}");
    }

    private ResultActions civilShift(String start, String end) throws Exception {
        return send(owner, put(staff("/schedule/entries")), "{\"departmentId\":\"" + department
                + "\",\"month\":\"2026-12\",\"changes\":[{\"employeeId\":\"" + employee + "\",\"date\":\"" + DAY
                + "\",\"start\":\"" + start + "\",\"end\":\"" + end + "\",\"storeId\":null,\"civil\":true}]}");
    }

    private ResultActions timeOff(Integer minutes, String initiative) throws Exception {
        return send(owner, put(staff("/schedule/time-off")), "{\"departmentId\":\"" + department
                + "\",\"month\":\"2026-12\",\"employeeId\":\"" + employee + "\",\"date\":\"" + DAY + "\",\"minutes\":"
                + minutes + ",\"initiative\":" + (initiative == null ? "null" : "\"" + initiative + "\"") + "}");
    }

    private ResultActions day() throws Exception {
        return send(owner, get(staff("/time?departmentId=" + department + "&from=" + DAY + "&to=" + DAY)), null);
    }

    private void simulate(String time) throws Exception {
        send(owner, post(staff("/time/punches")), "{\"employeeId\":\"" + employee + "\",\"at\":\"" + DAY + "T"
                + time + ":00\"}").andExpect(status().isNoContent());
    }

    private ResultActions decide(String kind, String start, String end, String decision) throws Exception {
        return send(owner, post(staff("/time/decisions?departmentId=" + department)),
                decision(kind, start, end, decision));
    }

    private String decision(String kind, String start, String end, String decision) {
        return "{\"employeeId\":\"" + employee + "\",\"kind\":\"" + kind + "\",\"start\":\"" + DAY + "T" + start
                + ":00\",\"end\":\"" + DAY + "T" + end + ":00\",\"decision\":\"" + decision + "\"}";
    }

    private ResultActions reader(String token, String card, String at) throws Exception {
        return mvc.perform(post("/api/time-clock/punches").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cardUid\":\"" + card + "\"" + (at == null ? "" : ",\"punchedAt\":\"" + at + "\"") + "}"));
    }

    private String invite() throws Exception {
        String login = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String json = send(owner, post(staff("/employees")), "{\"displayName\":\"Jan " + login + "\",\"email\":\""
                + login + "@sklep.pl\",\"positionId\":\"" + position + "\",\"shifts\":[]}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private String staff(String suffix) {
        return "/api/stores/" + store + "/staff" + suffix;
    }

    private String createRole(String permissions) throws Exception {
        String json = send(owner, post("/api/settings/roles"),
                "{\"name\":\"Rola " + UUID.randomUUID() + "\",\"permissions\":" + permissions + "}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private String createStore(String storeNip) throws Exception {
        String number = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999_999L));
        String json = send(owner, post("/api/settings/stores"), "{\"number\":\"" + number + "\",\"name\":\"Sklep "
                + number + "\",\"nip\":\"" + storeNip + "\",\"active\":true}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    /** Losowy NIP z poprawną sumą kontrolną. */
    private static String randomNip() {
        int[] weights = {6, 5, 7, 2, 3, 4, 5, 6, 7};
        while (true) {
            StringBuilder digits = new StringBuilder();
            int sum = 0;
            for (int weight : weights) {
                int digit = ThreadLocalRandom.current().nextInt(1, 10);
                digits.append(digit);
                sum += digit * weight;
            }
            if (sum % 11 != 10) {
                return digits.append(sum % 11).toString();
            }
        }
    }

    private String user(String roleId) {
        String username = "konto" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String userId = jdbc.queryForObject("""
                insert into users (username, display_name, password_hash, role_id)
                values (?, 'Pracownik', ?, ?::uuid) returning id""", String.class,
                username, passwordEncoder.encode("haslo-pracownika"), roleId);
        jdbc.update("insert into user_stores (user_id, store_id) values (?::uuid, ?::uuid)", userId, store);
        return userId;
    }

    private Cookie loginUser(String userId) throws Exception {
        String username = jdbc.queryForObject("select username from users where id = ?::uuid", String.class, userId);
        return login(username, "haslo-pracownika");
    }

    private ResultActions send(Cookie cookie, MockHttpServletRequestBuilder request, String body) throws Exception {
        request = request.cookie(cookie).with(csrf());
        return mvc.perform(body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Cookie login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }
}
