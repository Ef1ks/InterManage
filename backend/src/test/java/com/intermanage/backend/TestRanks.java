package com.intermanage.backend;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Konta testowe w hierarchii sklepu. Testy opisują uprawnienia jak dawniej rolą ({@link #role}: JSON
 * {@code {"SALES":"VIEW"}}), a {@link #join} daje kontu dostęp do sklepu i rangę w nim z uprawnieniami tej roli
 * (ranga o kluczu {@code legacy:<id roli>}, najniżej w hierarchii sklepu).
 */
final class TestRanks {

    private static final Pattern PERMISSION = Pattern.compile("\"([A-Z_]+)\"\\s*:\\s*\"(VIEW|EDIT)\"");

    private TestRanks() {
    }

    /** Rola testowa z uprawnieniami; zwraca jej id do {@code users.role_id}. */
    static String role(JdbcTemplate jdbc, String permissionsJson) {
        String roleId = jdbc.queryForObject("insert into roles (name) values (?) returning id::text", String.class,
                "Rola " + UUID.randomUUID());
        Matcher matcher = PERMISSION.matcher(permissionsJson);
        while (matcher.find()) {
            jdbc.update("insert into role_permissions (role_id, category, level) values (?::uuid, ?, ?)", roleId,
                    matcher.group(1), matcher.group(2));
        }
        return roleId;
    }

    /**
     * Ranga sklepu z uprawnieniami roli testowej (zakładana raz na sklep); zwraca jej id. W hierarchii stoi pod
     * domyślnymi rangami, tym wyżej, im więcej ma uprawnień (edycja liczy się podwójnie), a przy równych wyżej
     * założona wcześniej, więc „kierownik” z większymi uprawnieniami zarządza „kasjerem”.
     */
    static String rank(JdbcTemplate jdbc, String roleId, String storeId) {
        String key = "legacy:" + roleId;
        var found = jdbc.queryForList("select id::text from store_roles where store_id = ?::uuid and role_key = ?"
                + " and deleted_at is null", String.class, storeId, key);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        String name = jdbc.queryForObject("select name from roles where id = ?::uuid", String.class, roleId);
        String rankId = UUID.randomUUID().toString();
        jdbc.update("""
                insert into store_roles (id, store_id, role_key, name, position, created_at, updated_at)
                values (?::uuid, ?::uuid, ?, ?,
                        100000 - 100 * (select coalesce(sum(case level when 'EDIT' then 2 else 1 end), 0)
                                        from role_permissions where role_id = ?::uuid)
                               + (select count(*) from store_roles where store_id = ?::uuid),
                        now(), now())""", rankId, storeId, key, name, roleId, storeId);
        jdbc.update("""
                insert into store_role_permissions (store_role_id, category, level)
                select ?::uuid, category, level from role_permissions where role_id = ?::uuid""", rankId, roleId);
        return rankId;
    }

    /** Ranga roli testowej w każdym sklepie (stanowiska są wspólne dla firmy); zwraca id rangi w {@code storeId}. */
    static String rankEverywhere(JdbcTemplate jdbc, String roleId, String storeId) {
        for (String other : jdbc.queryForList("""
                select s.id::text from stores s, stores mine
                where mine.id = ?::uuid and (s.id = mine.id or s.nip = mine.nip)""", String.class, storeId)) {
            rank(jdbc, roleId, other);
        }
        return rank(jdbc, roleId, storeId);
    }

    /** Dawna rola (id roli testowej) z rangi konta w sklepie, w którym konto ma dostęp; {@code null} = brak. */
    static String roleOf(JdbcTemplate jdbc, String userId) {
        var keys = jdbc.queryForList("""
                select r.role_key from user_store_roles a
                join store_roles r on r.id = a.store_role_id
                join user_stores s on s.user_id = a.user_id and s.store_id = a.store_id
                where a.user_id = ?::uuid order by a.assigned_at desc""", String.class, userId);
        return keys.isEmpty() ? null : keys.get(0).replace("legacy:", "");
    }

    /** Dostęp do sklepu i ranga z roli konta ({@code users.role_id}; właściciel i Konto sklepu bez rangi). */
    static void join(JdbcTemplate jdbc, String userId, String storeId) {
        jdbc.update("insert into user_stores (user_id, store_id) values (?::uuid, ?::uuid) on conflict do nothing",
                userId, storeId);
        var roleIds = jdbc.queryForList("""
                select r.id::text from users u join roles r on r.id = u.role_id
                where u.id = ?::uuid and not r.owner and r.system_key is null""", String.class, userId);
        if (!roleIds.isEmpty()) {
            assign(jdbc, userId, storeId, rank(jdbc, roleIds.get(0), storeId));
        }
    }

    static void assign(JdbcTemplate jdbc, String userId, String storeId, String rankId) {
        jdbc.update("""
                insert into user_store_roles (id, user_id, store_id, store_role_id, assigned_at)
                values (gen_random_uuid(), ?::uuid, ?::uuid, ?::uuid, now())
                on conflict (user_id, store_id) do update set store_role_id = excluded.store_role_id""",
                userId, storeId, rankId);
    }

    /** Id domyślnej rangi sklepu po kluczu (np. {@code MANAGER}). */
    static String defaultRank(JdbcTemplate jdbc, String storeId, String key) {
        return jdbc.queryForObject("select id::text from store_roles where store_id = ?::uuid and role_key = ?"
                + " and deleted_at is null", String.class, storeId, key);
    }
}
