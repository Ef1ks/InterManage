package com.intermanage.backend.agent.component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.intermanage.backend.importguide.component.ReportSaveMacro;

/**
 * Makro wczytujące do Mercalys pliki nowych i zmienionych artykułów (CADBEZP), które agent zapisał w folderze:
 * Cadencier → Cadencier Bezpośredni → Instalacja (dostawca, plik), na „Wszystkie artykuły” poprawka artykułów z błędem
 * (Ano. inne niż N: kod CN {@value #CN} i stawka VAT z pliku), Wybór wszystkich i Integracja zaznaczonych z zamknięciem
 * PDF-a. Polecenia odtwarzacza od 0.68. Niesprawdzone na Mercalys.
 */
public final class ProductFileMacro {

    /** Polecenia WEBFIXCN, WEBVATCODE i STOP. */
    public static final int PLAYER_MINOR = 68;
    static final String CN = "99999980";
    /** Kody stawek VAT w Mercalys. */
    private static final Map<String, String> VAT_CODES = Map.of("23", "1", "8", "2", "5", "6");

    private static final String FILE_PREFIX = "# Plik: ";
    private static final Pattern FILE_LINE = Pattern.compile("(?m)^" + FILE_PREFIX + "(.+?)\\r?$");

    private static final String WINDOW = "Mercalys";
    private static final String DIALOG = "Les Mousquetaires";
    /** Pytanie i komunikat (BoiteAlerte.aspx nie ma tytułu „Les Mousquetaires”). */
    private static final String ANY_DIALOG = "ahk_class Internet Explorer_TridentDlgFrame";
    private static final String ANY_ALERT = "BoiteAlerte";
    private static final int PAGE_TIMEOUT_MS = 30000;
    private static final int FILE_DIALOG_MS = 60000;
    /** Wczytanie dużego pliku, zaznaczenie wszystkich artykułów i integracja (raport do paru minut). */
    private static final int WORK_TIMEOUT_MS = 600000;
    private static final int RELOAD_MS = 1500;

    private static final String TAB = "mnTp_CADENCIER";
    private static final String MENU = "mnTv_CADENCIER_CadencierDirect";
    private static final String INSTALL_MENU = "mnTv_CADENCIER_ca-e010_InstallationDirect";
    private static final String ARTICLES_MENU = "mnTv_CADENCIER_ca-e013_TousLesArticles";
    private static final String INTEGRATE_MENU = "mnTv_CADENCIER_ca-e022_IntegrationDeLaSelectionDirect";
    private static final String SUPPLIER_FIELD = "lstZ4_Fournisseur_TxtBox";
    /** Cały cadencier dostawcy: zaznaczone usuwałoby jego artykuły spoza pliku, więc musi zostać odznaczone. */
    private static final String COMPLETE = "chkZ7_CadComplet_CheckBox1";
    private static final String APPROVE = "btnB2_Valider_LnkBouton";
    private static final String INSTALLED_TEXT = "Instalacja powiod";
    private static final String SELECT = "btnB4_Sel_LnkBouton";
    private static final String INTEGRATE = "btnB2_Integrer_LnkBouton";

    /** Plik do wczytania; {@code articles}: {@code EAN;procent VAT}. */
    public record File(String path, String supplierCode, List<String> articles) {
    }

    private ProductFileMacro() {
    }

    /** {@code files} od najstarszego. */
    public static String build(List<File> files) {
        List<String> out = new ArrayList<>();
        out.add("# Nowe artykuły, pliki: " + files.size());
        out.add(ReportSaveMacro.OPEN_LINE);
        for (int i = 0; i < files.size(); i++) {
            steps(out, files.get(i), i + 1);
        }
        out.add("WINMIN|" + ReportSaveMacro.PROGRAM_WINDOW);
        return String.join("\r\n", out) + "\r\n";
    }

    /** Pliki makra: agent sprawdza przed startem, czy wszystkie są w folderze. */
    public static List<String> files(String macro) {
        return FILE_LINE.matcher(macro).results().map(match -> match.group(1)).toList();
    }

    public static String path(String folder, String fileName) {
        return folder.strip().replaceAll("\\\\+$", "") + "\\" + fileName;
    }

    /** Kod dostawcy do makra (same cyfry bez zer wiodących) albo {@code null}. */
    public static String supplierCode(String code) {
        String digits = code == null ? "" : code.strip().replaceFirst("^0+", "");
        return digits.matches("\\d{1,8}") ? digits : null;
    }

    private static void steps(List<String> out, File file, int number) {
        String supplier = supplierCode(file.supplierCode());
        if (supplier == null) {
            throw new IllegalArgumentException("Nieprawidłowy numer dostawcy: " + file.supplierCode());
        }
        String installed = "ZAINSTALOWANE_" + number;
        String stopped = "KOMUNIKAT_" + number;
        out.add(FILE_PREFIX + file.path());
        out.addAll(List.of(
                "WEBENSURE|" + WINDOW + "|" + MENU + "|" + TAB,
                "WEBOPEN|" + WINDOW + "|" + MENU + "|" + INSTALL_MENU + "|" + SUPPLIER_FIELD,
                "WEBPAGE|" + WINDOW + "|ca-e010|" + SUPPLIER_FIELD + "|" + PAGE_TIMEOUT_MS,
                "SLEEP|" + RELOAD_MS,
                "WEBKEY|" + WINDOW + "|" + SUPPLIER_FIELD + "|^a" + supplier + "{Enter}",
                "SLEEP|" + RELOAD_MS,
                "WEBPAGE|" + WINDOW + "|ca-e010|" + APPROVE + "|" + PAGE_TIMEOUT_MS,
                "WEBVALUE|" + WINDOW + "|" + SUPPLIER_FIELD + "|^0*" + supplier + "$|10000",
                "WEBVALUE|" + WINDOW + "|" + COMPLETE + "|^nie$|5000",
                "WEBCLICK|" + WINDOW + "|" + APPROVE,
                "FILEOPEN|" + file.path() + "|" + FILE_DIALOG_MS,
                // Po wczytaniu zawsze komunikat: z pytaniem o konflikty (Nie) albo sam wynik; inny wymaga człowieka.
                "WEBPAGE|" + WINDOW + "|ca-e013|" + SELECT + "|" + WORK_TIMEOUT_MS
                        + "|" + ANY_DIALOG + "|" + INSTALLED_TEXT + "|Non|" + installed
                        + "|" + ANY_DIALOG + "|" + INSTALLED_TEXT + "|Fermer|" + installed
                        + "|" + ANY_DIALOG + "|" + ANY_ALERT + "|Fermer|" + stopped,
                "WEBGOTO|" + installed,
                "LABEL|" + stopped,
                "STOP|Mercalys nie wczytał pliku " + file.path().substring(file.path().lastIndexOf('\\') + 1),
                "LABEL|" + installed,
                "SLEEP|" + RELOAD_MS,
                "WEBOPEN|" + WINDOW + "|" + MENU + "|" + ARTICLES_MENU + "|" + SELECT,
                "WEBPAGE|" + WINDOW + "|ca-e013|" + SELECT + "|" + PAGE_TIMEOUT_MS,
                "WEBVATCODE"));
        for (String article : file.articles()) {
            String[] parts = article.split(";", 2);
            String vat = parts.length < 2 ? null : VAT_CODES.get(parts[1]);
            if (vat != null && parts[0].matches("\\d{1,14}")) {
                out.add("WEBVATCODE|" + parts[0] + "|" + vat);
            }
        }
        out.addAll(List.of(
                "WEBFIXCN|" + WINDOW + "|ca-e013|" + SELECT + "|Ano.|N|ca-e018|txtZ03_CodeEAN_TxtBox|txt_CodeCn_TxtBox|"
                        + CN + "|lstZ35_TVA_TxtBox|btnB3_Valider_LnkBouton|" + DIALOG + "|zachowa|Oui|"
                        + "btnB1_Retour_LnkBouton",
                "WEBCLICK|" + WINDOW + "|" + SELECT,
                "WEBPAGE|" + WINDOW + "|ca-e016|" + APPROVE + "|" + PAGE_TIMEOUT_MS,
                "WEBCLICK|" + WINDOW + "|" + APPROVE,
                "WEBPAGE|" + WINDOW + "|ca-e013|" + SELECT + "|" + WORK_TIMEOUT_MS,
                "SLEEP|" + RELOAD_MS,
                "WEBOPEN|" + WINDOW + "|" + MENU + "|" + INTEGRATE_MENU + "|" + INTEGRATE,
                "WEBPAGE|" + WINDOW + "|ca-e022|" + INTEGRATE + "|" + PAGE_TIMEOUT_MS,
                "WEBCLICK|" + WINDOW + "|" + INTEGRATE,
                // Na tym samym przycisku w kodach MEA bywa pytanie o integrację.
                "WEBDIALOG|" + DIALOG + "|integracj|Oui|3000",
                "PDFCLOSE|" + WORK_TIMEOUT_MS));
    }
}
