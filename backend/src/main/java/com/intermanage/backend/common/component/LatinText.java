package com.intermanage.backend.common.component;

/**
 * Litery cyrylicy i greki wyglądające jak łacińskie zamienione na łacińskie. Dostawcy wpisują je w numery faktur w KSeF
 * (Roshen: „FAN/ZА664” z cyrylickim А), a program sklepu zna numer łaciński: windows-1250 ich nie ma, a numery z dwoma
 * różnymi „A” nigdy by się nie połączyły.
 */
public final class LatinText {

    private static final String FROM = "АВЕЅІЈКМНОРСТХУаеѕіјорсхуԁһӏΑΒΕΖΗΙΚΜΝΟΡΤΥΧον";
    private static final String TO = "ABESIJKMHOPCTXYaesijopcxydhlABEZHIKMNOPTYXov";

    private LatinText() {
    }

    public static String of(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            int index = FROM.indexOf(c);
            out.append(index >= 0 ? TO.charAt(index) : c);
        }
        return out.toString();
    }
}
