package com.intermanage.backend.sales.component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Grupy rejonów nomenklatury w podsumowaniu sprzedaży i strat, jak w zestawieniu Intermarché ("kombajn"): artykuły
 * spożywcze (świeże tradycyjne, świeże samoobsługa, suche), przemysłowe, papierosy. Rejony spoza listy (paliwa,
 * opakowania zwrotne, materiały promocyjne, nowe rejony) i sprzedaż bez nomenklatury trafiają do "Pozostałe".
 */
public final class RegionGroups {

    /** Grupa: albo podgrupy, albo rejony (kody w kolejności z zestawienia). {@code rest} zbiera nieprzypisane. */
    public record Group(String name, List<Group> children, List<String> regions, boolean rest) {

        static Group of(String name, String... regions) {
            return new Group(name, List.of(), List.of(regions), false);
        }

        static Group of(String name, Group... children) {
            return new Group(name, List.of(children), List.of(), false);
        }

        public boolean leaf() {
            return children.isEmpty();
        }

        /** Grupy z rejonami (liście drzewa) w kolejności wydruku. */
        public Stream<Group> leaves() {
            return leaf() ? Stream.of(this) : children.stream().flatMap(Group::leaves);
        }
    }

    public static final List<Group> GROUPS = List.of(
            Group.of("Artykuły spożywcze",
                    Group.of("Artykuły świeże",
                            Group.of("Świeże tradycyjne", "14", "15", "31", "32", "33", "34", "35", "36"),
                            Group.of("Świeże samoobsługa", "19", "20", "21", "22", "23", "24", "25", "26", "27",
                                    "46")),
                    Group.of("Artykuły suche", "01", "02", "03", "04", "65", "66", "67", "68", "69", "70", "71",
                            "72", "73")),
            Group.of("Artykuły przemysłowe", "50", "51", "52", "53", "54", "55", "56", "57", "58", "60", "63",
                    "64"),
            Group.of("Papierosy", "39"),
            new Group("Pozostałe", List.of(), List.of("40", "90", "91", "61", "99"), true));

    private RegionGroups() {
    }

    /** Wszystkie rejony wymienione w grupach, w kolejności wydruku. */
    public static List<String> listedRegions() {
        List<String> codes = new ArrayList<>();
        GROUPS.stream().flatMap(Group::leaves).forEach(group -> codes.addAll(group.regions()));
        return codes;
    }

    /** Grupa z rejonami, do której należy rejon ("Pozostałe" dla nieprzypisanych). */
    public static Group leafOf(String regionCode) {
        return GROUPS.stream().flatMap(Group::leaves)
                .filter(group -> group.regions().contains(regionCode))
                .findFirst()
                .orElseGet(() -> GROUPS.stream().flatMap(Group::leaves).filter(Group::rest).findFirst()
                        .orElseThrow());
    }
}
