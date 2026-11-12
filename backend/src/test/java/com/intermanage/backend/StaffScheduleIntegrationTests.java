package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
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
class StaffScheduleIntegrationTests {

    private static final String MONTH = "2026-12";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    private Cookie owner;
    private String nip;
    private String store;
    private String sister;
    private String role;
    private String department;
    private String otherDepartment;
    private String position;
    private String employee;

    @BeforeEach
    void setUp() throws Exception {
        owner = login("wlasciciel", "haslo-wlasciciela");
        nip = randomNip();
        store = createStore(nip);
        sister = createStore(nip);
        role = createRole("{\"SALES\":\"VIEW\"}");
        String departments = send(owner, post(staff("/departments")), "{\"name\":\"Kasy\"}")
                .andReturn().getResponse().getContentAsString();
        department = JsonPath.read(departments, "$[0].id");
        departments = send(owner, post(staff("/departments")), "{\"name\":\"Garmaż\"}")
                .andReturn().getResponse().getContentAsString();
        otherDepartment = first(departments, "$[?(@.name=='Garmaż')].id");
        String positions = send(owner, post(staff("/positions")), "{\"departmentId\":\"" + department
                + "\",\"name\":\"Kasjer\",\"roleId\":\"" + role + "\",\"shifts\":[]}")
                .andReturn().getResponse().getContentAsString();
        position = first(positions, "$[?(@.name=='Kasy')].positions[0].id");
        employee = invite();
    }

    @Test
    void cellIsSavedAndCountsTowardsNorm() throws Exception {
        send(owner, post(staff("/records/" + employee + "/contracts")),
                "{\"kind\":\"EMPLOYMENT\",\"employmentType\":\"INDEFINITE\",\"startDate\":\"2026-01-01\","
                        + "\"fteNumerator\":1,\"fteDenominator\":2,\"fixedTermExempt\":false}")
                .andExpect(status().isOk());

        change(owner, cell(employee, "2026-12-01", "06:00", "14:00", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.periodStart").value("2026-11-01"))
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].contract").value("1/2"))
                .andExpect(jsonPath("$.rows[0].monthMinutes").value(480))
                .andExpect(jsonPath("$.rows[0].monthNormMinutes").value(80 * 60))
                .andExpect(jsonPath("$.rows[0].periodNormMinutes").value(236 * 60))
                .andExpect(jsonPath("$.rows[0].cells[0].start").value("06:00"))
                .andExpect(jsonPath("$.positions[0].counts[0]").value(1));
    }

    @Test
    void changingHoursKeepsPreviousEntryAsDeleted() throws Exception {
        change(owner, cell(employee, "2026-12-01", "06:00", "14:00", null)).andExpect(status().isOk());
        change(owner, cell(employee, "2026-12-01", "07:00", "15:00", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].cells[0].start").value("07:00"));
        change(owner, cell(employee, "2026-12-01", null, null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].cells", hasSize(0)));

        assertThat(jdbc.queryForObject("select count(*) from staff_schedule_entries where employee_id = ?::uuid",
                Long.class, employee)).isEqualTo(2);
    }

    @Test
    void singleDayInAnotherStoreShowsInBothSchedules() throws Exception {
        change(owner, cell(employee, "2026-12-03", "06:00", "14:00", sister))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].guest").value(false))
                .andExpect(jsonPath("$.rows[0].cells[0].storeId").value(sister))
                .andExpect(jsonPath("$.rows[0].monthMinutes").value(480))
                .andExpect(jsonPath("$.positions[0].counts[2]").value(0));

        send(owner, get("/api/stores/" + sister + "/staff/schedule?departmentId=" + department + "&month=" + MONTH), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].guest").value(true))
                .andExpect(jsonPath("$.rows[0].cells[0].store").doesNotExist())
                .andExpect(jsonPath("$.positions[0].counts[2]").value(1));

        // Ten sam dzień u siebie zastępuje dzień w innym sklepie, bo pracownik ma jedną zmianę dziennie.
        change(owner, cell(employee, "2026-12-03", "08:00", "16:00", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].cells[0].storeId").doesNotExist());
    }

    @Test
    void dayInAnotherDepartmentIsNotOverwritten() throws Exception {
        change(owner, cell(employee, "2026-12-03", "06:00", "14:00", null)).andExpect(status().isOk());
        send(owner, put(schedule("/entries")), body(otherDepartment, cell(employee, "2026-12-03", "08:00", "16:00", null)))
                .andExpect(status().isConflict());
    }

    @Test
    void storeOutsideTheCompanyIsRejected() throws Exception {
        String foreign = createStore(randomNip());
        change(owner, cell(employee, "2026-12-03", "06:00", "14:00", foreign)).andExpect(status().isBadRequest());
    }

    @Test
    void firstVersionStaysAfterLaterChanges() throws Exception {
        change(owner, cell(employee, "2026-12-01", "06:00", "14:00", null)).andExpect(status().isOk());
        publish(owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.versions", hasSize(1)));
        publish(owner).andExpect(status().isConflict());

        change(owner, cell(employee, "2026-12-01", "08:00", "16:00", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHANGED"));
        publish(owner).andExpect(status().isOk()).andExpect(jsonPath("$.versions", hasSize(2)));

        send(owner, get(schedule("?departmentId=" + department + "&month=" + MONTH + "&version=1")), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewedVersion").value(1))
                .andExpect(jsonPath("$.canEdit").value(false))
                .andExpect(jsonPath("$.rows[0].cells[0].start").value("06:00"));
    }

    @Test
    void databaseRefusesToChangePublishedVersion() throws Exception {
        change(owner, cell(employee, "2026-12-01", "06:00", "14:00", null)).andExpect(status().isOk());
        publish(owner).andExpect(status().isOk());

        assertThatThrownBy(() -> jdbc.update("update staff_schedule_version_entries set start_time = '09:00'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from staff_schedule_versions"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void viewerSeesOnlyWhatWasPublished() throws Exception {
        Cookie viewer = worker(createRole("{\"STAFF_SCHEDULE\":\"VIEW\"}"));
        change(owner, cell(employee, "2026-12-01", "06:00", "14:00", null)).andExpect(status().isOk());

        read(viewer).andExpect(status().isOk())
                .andExpect(jsonPath("$.canEdit").value(false))
                .andExpect(jsonPath("$.rows[0].cells", hasSize(0)));

        publish(owner).andExpect(status().isOk());
        change(owner, cell(employee, "2026-12-02", "06:00", "14:00", null)).andExpect(status().isOk());

        read(viewer).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].cells", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].monthMinutes").doesNotExist())
                .andExpect(jsonPath("$.rows[0].warnings", hasSize(0)));
        change(viewer, cell(employee, "2026-12-03", "06:00", "14:00", null)).andExpect(status().isForbidden());
    }

    @Test
    void managerArrangesOnlyOwnDepartment() throws Exception {
        String managerRole = createRole("{\"STAFF_SCHEDULE\":\"EDIT\"}");
        String userId = user(managerRole);
        Cookie manager = loginUser(userId);

        change(manager, cell(employee, "2026-12-01", "06:00", "14:00", null)).andExpect(status().isForbidden());

        String managerEmployee = assign(userId);
        send(owner, put(schedule("/settings/" + department)), "{\"managerIds\":[\"" + userId + "\"],\"minimums\":[]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managerIds", hasSize(1)));
        assertThat(managerEmployee).isNotNull();

        change(manager, cell(employee, "2026-12-01", "06:00", "14:00", null)).andExpect(status().isOk());
        send(manager, put(schedule("/entries")), body(otherDepartment, cell(employee, "2026-12-02", "06:00", "14:00", null)))
                .andExpect(status().isForbidden());
        send(manager, get(schedule("/departments")), null)
                .andExpect(jsonPath("$[?(@.name=='Kasy')].canEdit").value(true))
                .andExpect(jsonPath("$[?(@.name=='Garmaż')].canEdit").value(false));
        // Ustawienia działu kierownik tylko czyta (odczyt nie wymaga EDIT), zmienia je STAFF:EDIT.
        send(manager, get(schedule("/settings/" + department)), null).andExpect(status().isOk());
        send(manager, put(schedule("/settings/" + department)), "{\"managerIds\":[],\"minimums\":[]}")
                .andExpect(status().isForbidden());
    }

    @Test
    void minimumIsPerStore() throws Exception {
        send(owner, put(schedule("/settings/" + department)), "{\"managerIds\":[],\"minimums\":[{\"positionId\":\""
                + position + "\",\"minCount\":2}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minimums[0].minCount").value(2));
        read(owner).andExpect(jsonPath("$.positions[0].minCount").value(2));
        send(owner, get("/api/stores/" + sister + "/staff/schedule?departmentId=" + department + "&month=" + MONTH), null)
                .andExpect(jsonPath("$.positions[0].minCount").doesNotExist());

        send(owner, put(schedule("/settings/" + department)), "{\"managerIds\":[],\"minimums\":[{\"positionId\":\""
                + position + "\",\"minCount\":null}]}")
                .andExpect(status().isOk());
        read(owner).andExpect(jsonPath("$.positions[0].minCount").doesNotExist());
    }

    @Test
    void targetStaffingSitsNextToMinimum() throws Exception {
        send(owner, put(schedule("/settings/" + department)), "{\"managerIds\":[],\"minimums\":[{\"positionId\":\""
                + position + "\",\"minCount\":2,\"targetCount\":4}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minimums[0].minCount").value(2))
                .andExpect(jsonPath("$.minimums[0].targetCount").value(4));
        read(owner).andExpect(jsonPath("$.positions[0].targetCount").value(4));

        send(owner, put(schedule("/settings/" + department)), "{\"managerIds\":[],\"minimums\":[{\"positionId\":\""
                + position + "\",\"minCount\":null,\"targetCount\":3}]}")
                .andExpect(status().isOk());
        read(owner).andExpect(jsonPath("$.positions[0].minCount").doesNotExist())
                .andExpect(jsonPath("$.positions[0].targetCount").value(3));

        send(owner, put(schedule("/settings/" + department)), "{\"managerIds\":[],\"minimums\":[{\"positionId\":\""
                + position + "\",\"minCount\":5,\"targetCount\":3}]}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void absenceLowersNormAndHidesItsKind() throws Exception {
        send(owner, post(staff("/records/" + employee + "/contracts")),
                "{\"kind\":\"EMPLOYMENT\",\"employmentType\":\"INDEFINITE\",\"startDate\":\"2026-01-01\","
                        + "\"fteNumerator\":1,\"fteDenominator\":1,\"fixedTermExempt\":false}")
                .andExpect(status().isOk());
        change(owner, cell(employee, "2026-12-01", "06:00", "12:00", null)).andExpect(status().isOk());
        send(owner, post(staff("/records/" + employee + "/leaves")),
                "{\"kind\":\"REHABILITATION\",\"startDate\":\"2026-12-01\",\"endDate\":\"2026-12-02\",\"days\":2}")
                .andExpect(status().isOk());

        // Dzień urlopu ze zmianą obniża wymiar o pełny dobowy wymiar (8 h), także pod krótszą zmianą 6-12;
        // dzień urlopu bez zaplanowanej pracy nic.
        read(owner)
                .andExpect(jsonPath("$.rows[0].cells[0].absent").value(true))
                .andExpect(jsonPath("$.rows[0].cells[0].leave.kind").value("REHABILITATION"))
                .andExpect(jsonPath("$.rows[0].cells[0].leave.endDate").value("2026-12-02"))
                .andExpect(jsonPath("$.rows[0].monthMinutes").value(0))
                .andExpect(jsonPath("$.rows[0].monthNormMinutes").value((160 - 8) * 60))
                .andExpect(jsonPath("$.positions[0].counts[0]").value(0));
    }

    @Test
    void leaveCanBeMovedAndCancelledFromSchedule() throws Exception {
        send(owner, post(staff("/records/" + employee + "/contracts")),
                "{\"kind\":\"EMPLOYMENT\",\"employmentType\":\"INDEFINITE\",\"startDate\":\"2026-01-01\","
                        + "\"fteNumerator\":1,\"fteDenominator\":1,\"fixedTermExempt\":false}")
                .andExpect(status().isOk());
        send(owner, post(staff("/records/" + employee + "/leaves")),
                "{\"kind\":\"ANNUAL\",\"startDate\":\"2026-12-01\",\"endDate\":\"2026-12-01\",\"days\":1}")
                .andExpect(status().isOk());
        String leaveId = JsonPath.read(read(owner).andExpect(jsonPath("$.rows[0].cells[0].date").value("2026-12-01"))
                .andReturn().getResponse().getContentAsString(), "$.rows[0].cells[0].leave.id");

        send(owner, put(staff("/records/" + employee + "/leaves/" + leaveId)),
                "{\"kind\":\"ANNUAL\",\"startDate\":\"2026-12-03\",\"endDate\":\"2026-12-03\",\"days\":1}")
                .andExpect(status().isOk());
        read(owner).andExpect(jsonPath("$.rows[0].cells[0].date").value("2026-12-03"))
                .andExpect(jsonPath("$.rows[0].cells[0].leave.id").value(leaveId));

        send(owner, delete(staff("/records/" + employee + "/leaves/" + leaveId)), null).andExpect(status().isOk());
        read(owner).andExpect(jsonPath("$.rows[0].cells", hasSize(0)));
        send(owner, post(staff("/records/" + employee + "/leaves/" + leaveId + "/restore")), null)
                .andExpect(status().isOk());
        read(owner).andExpect(jsonPath("$.rows[0].cells[0].absent").value(true));
    }

    @Test
    void leaveUsesWorkDaysFromSchedule() throws Exception {
        send(owner, post(staff("/records/" + employee + "/contracts")),
                "{\"kind\":\"EMPLOYMENT\",\"employmentType\":\"INDEFINITE\",\"startDate\":\"2026-01-01\","
                        + "\"fteNumerator\":1,\"fteDenominator\":1,\"fixedTermExempt\":false}")
                .andExpect(status().isOk());
        // Pracuje w sobotę 5.12, a czwartek i piątek nie są jeszcze ułożone: urlop czw.-niedz. to jeden dzień.
        change(owner, cell(employee, "2026-12-05", "06:00", "14:00", null)).andExpect(status().isOk());
        send(owner, post(staff("/records/" + employee + "/leaves")),
                "{\"kind\":\"ANNUAL\",\"startDate\":\"2026-12-03\",\"endDate\":\"2026-12-06\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaves[0].days").value(1));
        read(owner).andExpect(jsonPath("$.rows[0].monthMinutes").value(0))
                .andExpect(jsonPath("$.rows[0].monthNormMinutes").value((160 - 8) * 60));

        // Godziny ułożone pod urlopem w piątek robią z niego dzień urlopu i zdejmują pełny dzień wymiaru.
        change(owner, cell(employee, "2026-12-04", "07:00", "13:00", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].monthMinutes").value(0))
                .andExpect(jsonPath("$.rows[0].monthNormMinutes").value((160 - 8 - 8) * 60));
        send(owner, get(staff("/records/" + employee + "?year=2026")), null)
                .andExpect(jsonPath("$.leaves[0].days").value(2));

        // Wyczyszczony dzień wraca do wymiaru.
        change(owner, cell(employee, "2026-12-04", null, null, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].monthNormMinutes").value((160 - 8) * 60));
    }

    @Test
    void onDemandLeaveStopsAtFourPlannedDays() throws Exception {
        send(owner, post(staff("/records/" + employee + "/contracts")),
                "{\"kind\":\"EMPLOYMENT\",\"employmentType\":\"INDEFINITE\",\"startDate\":\"2026-01-01\","
                        + "\"fteNumerator\":1,\"fteDenominator\":1,\"fixedTermExempt\":false}")
                .andExpect(status().isOk());
        for (String day : List.of("2026-12-07", "2026-12-08", "2026-12-09", "2026-12-10", "2026-12-11")) {
            change(owner, cell(employee, day, "06:00", "14:00", null)).andExpect(status().isOk());
        }
        // Art. 167² KP: najwyżej 4 dni na żądanie w roku, a w grafiku jest tu 5 dni pracy.
        send(owner, post(staff("/records/" + employee + "/leaves")),
                "{\"kind\":\"ON_DEMAND\",\"startDate\":\"2026-12-07\",\"endDate\":\"2026-12-11\"}")
                .andExpect(status().isConflict());
        send(owner, post(staff("/records/" + employee + "/leaves")),
                "{\"kind\":\"ON_DEMAND\",\"startDate\":\"2026-12-07\",\"endDate\":\"2026-12-10\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaves[0].days").value(4));
        // Kolejny dzień na żądanie przekroczyłby limit także wtedy, gdy godziny dojdą później.
        send(owner, post(staff("/records/" + employee + "/leaves")),
                "{\"kind\":\"ON_DEMAND\",\"startDate\":\"2026-12-14\",\"endDate\":\"2026-12-14\"}")
                .andExpect(status().isOk());
        change(owner, cell(employee, "2026-12-14", "06:00", "14:00", null)).andExpect(status().isConflict());
    }

    @Test
    void breaksArePerStoreAndKeptWhenOmitted() throws Exception {
        String breaks = "[{\"start\":\"10:00\",\"end\":\"10:15\",\"paid\":true},"
                + "{\"start\":\"13:00\",\"end\":\"13:30\",\"paid\":false}]";
        send(owner, put(schedule("/settings/" + department)), "{\"managerIds\":[],\"minimums\":[],\"breaks\":" + breaks + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.breaks", hasSize(2)))
                .andExpect(jsonPath("$.breaks[0].start").value("10:00"))
                .andExpect(jsonPath("$.breaks[1].paid").value(false));

        // Bez przerw w żądaniu zostają, jak były.
        send(owner, put(schedule("/settings/" + department)), "{\"managerIds\":[],\"minimums\":[]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.breaks", hasSize(2)));
        send(owner, get("/api/stores/" + sister + "/staff/schedule/settings/" + department), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.breaks", hasSize(0)));

        send(owner, put(schedule("/settings/" + department)), "{\"managerIds\":[],\"minimums\":[],\"breaks\":["
                + "{\"start\":\"10:00\",\"end\":\"10:30\",\"paid\":true},{\"start\":\"10:15\",\"end\":\"10:45\",\"paid\":true}]}")
                .andExpect(status().isBadRequest());
        send(owner, put(schedule("/settings/" + department)), "{\"managerIds\":[],\"minimums\":[],\"breaks\":["
                + "{\"start\":\"12:00\",\"end\":\"11:00\",\"paid\":true}]}")
                .andExpect(status().isBadRequest());

        send(owner, put(schedule("/settings/" + department)), "{\"managerIds\":[],\"minimums\":[],\"breaks\":["
                + "{\"start\":\"10:00\",\"end\":\"10:15\",\"paid\":true}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.breaks", hasSize(1)));
    }

    @Test
    void openHolidayIsPerStoreAndKeepsNorm() throws Exception {
        send(owner, post(staff("/records/" + employee + "/contracts")),
                "{\"kind\":\"EMPLOYMENT\",\"employmentType\":\"INDEFINITE\",\"startDate\":\"2026-01-01\","
                        + "\"fteNumerator\":1,\"fteDenominator\":1,\"fixedTermExempt\":false}")
                .andExpect(status().isOk());
        // Zamknięte święto to dzień wolny: zmiany nie da się ułożyć, dopóki sklep nie jest czynny.
        change(owner, cell(employee, "2026-12-25", "06:00", "14:00", null)).andExpect(status().isConflict());

        openHoliday("2026-12-25", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[24].closed").value(false))
                .andExpect(jsonPath("$.days[24].holiday").value("Boże Narodzenie"));
        change(owner, cell(employee, "2026-12-25", "06:00", "14:00", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].warnings[0].message").value("Święto 25.12: brak WN"))
                .andExpect(jsonPath("$.rows[0].monthNormMinutes").value(160 * 60));
        send(owner, get("/api/stores/" + sister + "/staff/schedule?departmentId=" + department + "&month=" + MONTH), null)
                .andExpect(jsonPath("$.days[24].closed").value(true));

        // Zamknięcie po ułożeniu zostawia zmianę z ostrzeżeniem, żeby ją zdjąć.
        openHoliday("2026-12-25", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[24].closed").value(true))
                .andExpect(jsonPath("$.rows[0].warnings[0].message").value("Boże Narodzenie: sklep oznaczony jako zamknięty"));
        openHoliday("2026-12-23", true).andExpect(status().isBadRequest());
    }

    @Test
    void flexibleTimeOfStoreRemovesEarlierStartWarning() throws Exception {
        change(owner, cell(employee, "2026-12-06", "08:00", "16:00", null)).andExpect(status().isOk());
        change(owner, cell(employee, "2026-12-07", "06:00", "14:00", null))
                .andExpect(jsonPath("$.rows[0].warnings[*].message",
                        hasItem("Wcześniejszy start: w tej samej dobie 10 h pracy")));

        send(owner, put(schedule("/settings/" + department)),
                "{\"managerIds\":[],\"minimums\":[],\"flexibleTime\":true}")
                .andExpect(jsonPath("$.flexibleTime").value(true));
        change(owner, cell(employee, "2026-12-07", "06:00", "14:00", null))
                .andExpect(jsonPath("$.rows[0].warnings[*].message",
                        not(hasItem("Wcześniejszy start: w tej samej dobie 10 h pracy"))));

        // Bez pola ustawienie zostaje, a false je wyłącza (znacznik).
        send(owner, put(schedule("/settings/" + department)), "{\"managerIds\":[],\"minimums\":[]}")
                .andExpect(jsonPath("$.flexibleTime").value(true));
        send(owner, put(schedule("/settings/" + department)),
                "{\"managerIds\":[],\"minimums\":[],\"flexibleTime\":false}")
                .andExpect(jsonPath("$.flexibleTime").value(false));
        assertThat(jdbc.queryForObject("select count(*) from staff_flexible_time_settings where deleted_at is not null",
                Integer.class)).isPositive();
    }

    @Test
    void dayOffIsMarkedWithItsReason() throws Exception {
        change(owner, cell(employee, "2026-12-06", "06:00", "14:00", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].owedDays[0]").value("2026-12-06"))
                .andExpect(jsonPath("$.rows[0].warnings[0].message").value("Niedziela 06.12: brak WN"));

        change(owner, off(employee, "2026-12-09", "WN", "2026-12-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].owedDays", hasSize(0)))
                .andExpect(jsonPath("$.rows[0].warnings", hasSize(0)))
                .andExpect(jsonPath("$.rows[0].cells[1].dayOff").value("WN"))
                .andExpect(jsonPath("$.rows[0].cells[1].forDate").value("2026-12-06"))
                .andExpect(jsonPath("$.rows[0].monthMinutes").value(480));
        change(owner, off(employee, "2026-12-10", "W5", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].cells[2].dayOff").value("W5"));

        // Za jedną niedzielę jeden dzień, a W5 nie ma daty.
        change(owner, off(employee, "2026-12-11", "WN", "2026-12-06")).andExpect(status().isConflict());
        change(owner, off(employee, "2026-12-11", "W5", "2026-12-06")).andExpect(status().isBadRequest());
        change(owner, off(employee, "2026-12-11", "WN", null)).andExpect(status().isBadRequest());
        change(owner, off(employee, "2026-12-11", "WN", "2026-12-07")).andExpect(status().isBadRequest());
        change(owner, off(employee, "2026-12-13", "W5", null)).andExpect(status().isBadRequest());

        // Zwykłe W można postawić także w niedzielę: pracownik widzi, że ma wolne. Nie ma daty i nie rozlicza niedzieli.
        change(owner, off(employee, "2026-12-13", "W", "2026-12-06")).andExpect(status().isBadRequest());
        change(owner, off(employee, "2026-12-13", "W", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].cells[3].dayOff").value("W"))
                .andExpect(jsonPath("$.rows[0].warnings", hasSize(0)));

        // Zmiana wpisana na dzień wolny go zastępuje.
        change(owner, cell(employee, "2026-12-10", "06:00", "14:00", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].cells[2].dayOff").doesNotExist())
                .andExpect(jsonPath("$.rows[0].cells[2].start").value("06:00"));
    }

    @Test
    void publishedVersionKeepsDaysOff() throws Exception {
        change(owner, off(employee, "2026-12-10", "W5", null)).andExpect(status().isOk());
        publish(owner).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));

        change(owner, cell(employee, "2026-12-10", null, null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHANGED"))
                .andExpect(jsonPath("$.rows[0].cells", hasSize(0)));
        publish(owner).andExpect(status().isOk());

        send(owner, get(schedule("?departmentId=" + department + "&month=" + MONTH + "&version=1")), null)
                .andExpect(jsonPath("$.rows[0].cells[0].dayOff").value("W5"));
        assertThatThrownBy(() -> jdbc.update("update staff_schedule_version_days_off set kind = 'WN'"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void daysAfterContractEndAreMarkedAndWarned() throws Exception {
        change(owner, cell(employee, "2026-12-01", "06:00", "14:00", null))
                .andExpect(jsonPath("$.rows[0].outOfContractDays", hasSize(0)));

        send(owner, post(staff("/records/" + employee + "/contracts")),
                "{\"kind\":\"EMPLOYMENT\",\"employmentType\":\"FIXED\",\"startDate\":\"2026-01-01\",\"endDate\":"
                        + "\"2026-12-10\",\"fteNumerator\":1,\"fteDenominator\":1,\"fixedTermExempt\":false}")
                .andExpect(status().isOk());

        change(owner, cell(employee, "2026-12-10", "06:00", "14:00", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].outOfContractDays", hasSize(21)))
                .andExpect(jsonPath("$.rows[0].outOfContractDays[0]").value("2026-12-11"))
                .andExpect(jsonPath("$.rows[0].warnings", hasSize(0)));
        change(owner, cell(employee, "2026-12-11", "06:00", "14:00", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].warnings[0].date").value("2026-12-11"))
                .andExpect(jsonPath("$.rows[0].warnings[0].message").value("Zmiana poza umową"));
    }

    @Test
    void layoutGroupsAndOrdersEmployees() throws Exception {
        String second = invite();
        String body = "{\"departmentId\":\"" + department + "\",\"month\":\"" + MONTH + "\",\"ungrouped\":[\"" + second
                + "\"],\"groups\":[{\"name\":\"Zmiana A\",\"employeeIds\":[\"" + employee + "\"]}]}";
        String json = send(owner, put(schedule("/layout")), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].name").value("Zmiana A"))
                .andExpect(jsonPath("$.rows[0].employeeId").value(second))
                .andExpect(jsonPath("$.rows[0].groupId").doesNotExist())
                .andExpect(jsonPath("$.rows[1].employeeId").value(employee))
                .andReturn().getResponse().getContentAsString();
        String group = JsonPath.read(json, "$.groups[0].id");

        // Zmiana nazwy i odwrotna kolejność na tej samej grupie, a inny sklep ma swój układ.
        send(owner, put(schedule("/layout")), "{\"departmentId\":\"" + department + "\",\"month\":\"" + MONTH
                + "\",\"ungrouped\":[],\"groups\":[{\"id\":\"" + group + "\",\"name\":\"Rano\",\"employeeIds\":[\""
                + employee + "\",\"" + second + "\"]}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].id").value(group))
                .andExpect(jsonPath("$.groups[0].name").value("Rano"))
                .andExpect(jsonPath("$.rows[0].employeeId").value(employee))
                .andExpect(jsonPath("$.rows[1].groupId").value(group));
        send(owner, get("/api/stores/" + sister + "/staff/schedule?departmentId=" + department + "&month=" + MONTH), null)
                .andExpect(jsonPath("$.groups", hasSize(0)));

        // Usunięcie grupy to znacznik, a jej ludzie wracają poza grupy.
        send(owner, put(schedule("/layout")), "{\"departmentId\":\"" + department + "\",\"month\":\"" + MONTH
                + "\",\"ungrouped\":[\"" + employee + "\",\"" + second + "\"],\"groups\":[]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups", hasSize(0)))
                .andExpect(jsonPath("$.rows[0].groupId").doesNotExist());
        assertThat(jdbc.queryForObject("select deleted_at is not null from staff_schedule_groups where id = ?::uuid",
                Boolean.class, group)).isTrue();

        send(owner, put(schedule("/layout")), "{\"departmentId\":\"" + department + "\",\"month\":\"" + MONTH
                + "\",\"ungrouped\":[\"" + UUID.randomUUID() + "\"],\"groups\":[]}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void employeeSeesOnlyOwnPublishedSchedule() throws Exception {
        String userId = user(role);
        String own = assign(userId);
        change(owner, cell(own, "2026-12-01", "06:00", "14:00", null)).andExpect(status().isOk());
        change(owner, off(own, "2026-12-02", "W5", null)).andExpect(status().isOk());
        change(owner, cell(employee, "2026-12-01", "08:00", "16:00", null)).andExpect(status().isOk());
        publish(owner).andExpect(status().isOk());
        // Poprawka po publikacji czeka na kolejną publikację, więc pracownik widzi jeszcze stare godziny.
        change(owner, cell(own, "2026-12-01", "07:00", "15:00", null)).andExpect(status().isOk());

        Cookie worker = loginUser(userId);
        send(worker, get("/api/me/schedule?from=2026-12-01&to=2026-12-07"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employee").value(true))
                .andExpect(jsonPath("$.days", hasSize(7)))
                .andExpect(jsonPath("$.days[0].shifts", hasSize(1)))
                .andExpect(jsonPath("$.days[0].shifts[0].start").value("06:00:00"))
                .andExpect(jsonPath("$.days[0].shifts[0].department").value("Kasy"))
                .andExpect(jsonPath("$.days[1].dayOff.kind").value("W5"))
                .andExpect(jsonPath("$.days[2].shifts", hasSize(0)));

        send(worker, get("/api/me/schedule?from=2026-12-01&to=2027-03-01"), null)
                .andExpect(status().isBadRequest());
        send(worker(role), get("/api/me/schedule?from=2026-12-01&to=2026-12-07"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employee").value(false))
                .andExpect(jsonPath("$.days", hasSize(0)));
    }

    @Test
    void dashboardSettingsAreKeptPerAccount() throws Exception {
        send(owner, get("/api/auth/dashboard-settings"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesChartMetric").value("SALES"));
        send(owner, put("/api/auth/dashboard-settings"), "{\"salesChartMetric\":\"MARGIN\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesChartMetric").value("MARGIN"));
        send(owner, get("/api/auth/dashboard-settings"), null)
                .andExpect(jsonPath("$.salesChartMetric").value("MARGIN"));
        send(owner, put("/api/auth/dashboard-settings"), "{\"salesChartMetric\":null}")
                .andExpect(status().isBadRequest());
        send(worker(role), get("/api/auth/dashboard-settings"), null)
                .andExpect(jsonPath("$.salesChartMetric").value("SALES"));
        // Przywrócenie, bo konto właściciela jest wspólne dla testów.
        send(owner, put("/api/auth/dashboard-settings"), "{\"salesChartMetric\":\"SALES\"}")
                .andExpect(status().isOk());
    }

    @Test
    void sameDayTwiceInOneRequestIsRejected() throws Exception {
        send(owner, put(schedule("/entries")), body(department, cell(employee, "2026-12-01", "06:00", "14:00", null) + ","
                + cell(employee, "2026-12-01", "07:00", "15:00", null)))
                .andExpect(status().isBadRequest());
    }

    private static String first(String json, String path) {
        List<String> found = JsonPath.read(json, path);
        return found.getFirst();
    }

    private ResultActions change(Cookie cookie, String cell) throws Exception {
        return send(cookie, put(schedule("/entries")), body(department, cell));
    }

    private ResultActions openHoliday(String date, boolean open) throws Exception {
        return send(owner, put(schedule("/open-holidays")), "{\"departmentId\":\"" + department + "\",\"month\":\"" + MONTH
                + "\",\"date\":\"" + date + "\",\"open\":" + open + "}");
    }

    private ResultActions publish(Cookie cookie) throws Exception {
        return send(cookie, post(schedule("/publish")),
                "{\"departmentId\":\"" + department + "\",\"month\":\"" + MONTH + "\"}");
    }

    private ResultActions read(Cookie cookie) throws Exception {
        return send(cookie, get(schedule("?departmentId=" + department + "&month=" + MONTH)), null);
    }

    private static String body(String departmentId, String cells) {
        return "{\"departmentId\":\"" + departmentId + "\",\"month\":\"" + MONTH + "\",\"changes\":[" + cells + "]}";
    }

    private static String cell(String employeeId, String date, String start, String end, String storeId) {
        return "{\"employeeId\":\"" + employeeId + "\",\"date\":\"" + date + "\","
                + (start == null ? "" : "\"start\":\"" + start + "\",\"end\":\"" + end + "\",")
                + "\"storeId\":" + (storeId == null ? "null" : "\"" + storeId + "\"") + "}";
    }

    private static String off(String employeeId, String date, String kind, String forDate) {
        return "{\"employeeId\":\"" + employeeId + "\",\"date\":\"" + date + "\",\"dayOff\":\"" + kind + "\""
                + (forDate == null ? "" : ",\"forDate\":\"" + forDate + "\"") + "}";
    }

    private String invite() throws Exception {
        String login = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String json = send(owner, post(staff("/employees")), "{\"displayName\":\"Jan " + login + "\",\"email\":\""
                + login + "@sklep.pl\",\"positionId\":\"" + position + "\",\"shifts\":[]}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    /** Konto z dostępem do sklepu zostaje pracownikiem, więc można je wskazać jako układającego. */
    private String assign(String userId) throws Exception {
        return jdbc.queryForObject("""
                insert into staff_employees (id, store_id, user_id, position_id, created_at)
                values (gen_random_uuid(), ?::uuid, ?::uuid, ?::uuid, now()) returning id""", String.class,
                store, userId, position);
    }

    private String staff(String suffix) {
        return "/api/stores/" + store + "/staff" + suffix;
    }

    private String schedule(String suffix) {
        return staff("/schedule" + suffix);
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
        String json = send(owner, post("/api/settings/stores"), "{\"number\":\"" + number + "\",\"name\":\"Sklep " + number
                + "\",\"nip\":\"" + storeNip + "\",\"active\":true}")
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

    private Cookie worker(String roleId) throws Exception {
        return loginUser(user(roleId));
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
