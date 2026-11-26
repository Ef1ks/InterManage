package com.intermanage.backend.dataentry.component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.intermanage.backend.importguide.component.ReportSaveMacro;

/**
 * Makro zamknięcia faktur składane przez serwer (agent sklepu wykonuje tylko makra z serwera): te same polecenia co
 * {@code buildDeliveryMacro} w {@code mercalys-macro.ts}; zmiana ekranów Mercalys = zmiana w obu miejscach.
 *
 * <p>Integracja plików PZ, kody na bonach z listy bez Nr Zamów, Nr BL w nagłówkach i zamknięcie dostaw. Okno
 * „Zamknąć dostawy?” odtwarzacz pokazuje tylko przy makrze ze schowka: makro z agenta (z {@code #RESULT}) zatwierdził
 * już zlecający na stronie, a przy komputerze nikt by okna nie zamknął. Wymaga odtwarzacza od 0.67.
 */
public final class InvoiceCloseMacro {

    private static final String WINDOW = "Mercalys";
    private static final String PROGRAM_WINDOW = ReportSaveMacro.PROGRAM_WINDOW;
    private static final String PDF_WINDOW = "ahk_class AcrobatSDIWindow";
    private static final int PDF_TIMEOUT_MS = 20000;
    private static final String DIALOG = "Les Mousquetaires";
    private static final String GRID = "dgDetail";
    private static final int INTEGRATION_TIMEOUT_MS = 120000;
    private static final int PAGE_TIMEOUT_MS = 30000;
    private static final int AFTER_RESULT_BACK_MS = 1500;

    private static final String PURCHASES_TAB = "mnTp_ACHAT";
    private static final String DIRECT_DELIVERIES = "mnTv_ACHAT_BlFournisseursDirects";
    private static final String INTEGRATION = "mnTv_ACHAT_na-e014_IntegrationFichiersBl";
    private static final String BL_SCREEN = "mnTv_ACHAT_ac-e082_GestionDesBl";
    private static final String ORDERS = "mnTv_ACHAT_Commande";
    private static final String ORDER_ENTRY = "mnTv_ACHAT_ac-e011_SaisieCommandeCompleteCmd";

    private static final String INTEGRATION_CONFIRM_TEXT = "interacj";
    private static final String INTEGRATION_RESULT_PAGE = "na-e015";
    private static final String INTEGRATION_RESULT = "btnB1_Retour_LnkBouton";
    private static final String INTEGRATION_EMPTY_WINDOW = "ahk_class Internet Explorer_TridentDlgFrame";
    private static final String INTEGRATION_EMPTY_TEXT = "brak PZ";
    private static final String INTEGRATION_EMPTY_BUTTON = "Fermer";

    private static final int BL_ORDER_COLUMN = 10;
    private static final String BL_CREATE_CODE = "btnB4_CreationCommande_LnkBouton";
    private static final String BL_NEXT_PAGE = "pgrDetail_ibtSuivant";
    private static final String BL_CONFIRM_TEXT = "wygenerowane";
    private static final String BL_CONFIRM_BUTTON = "Fermer";

    private static final String ORDER_CRITERION = "lstZ1_Critere_TxtBox";
    private static final String ORDER_CRITERION_KEYS = "3{Enter}";
    private static final int ORDER_NUMBER_COLUMN = 1;
    private static final int ORDER_IDENTIFICATION_COLUMN = 12;
    private static final String ORDER_HEADER = "btnB11_Entete_LnkBouton";
    private static final String ORDER_IDENTIFICATION_FIELD = "txtZ16_IdentificationCde_TxtBox";
    private static final String ORDER_SAVE_HEADER = "btnB4_Valider_LnkBouton";
    private static final String ORDER_SAVE_HEADER_TEXT = "zachowa";
    private static final String ORDER_CLOSE = "btnB3_Cloturer_LnkBouton";
    private static final String ORDER_CLOSE_TEXT = "zamkn";
    private static final String ORDER_PRINT_CHECKBOX = "chkP1_BonValo_CheckBox1";
    private static final String ORDER_APPROVE_CLOSE = "btnP1_Valider_LnkBouton";
    private static final String ORDER_FIRST_PAGE = "pgrDetail_ibtPremier";
    private static final String ORDER_NEXT_PAGE = "pgrDetail_ibtSuivant";

    public static final int PLAYER_MINOR = 67;

    /** Numer faktury i kod dostawcy trafiają do linii makra: bez separatora pól i końców linii. */
    private static final Pattern UNSAFE = Pattern.compile("[|\\r\\n]");

    /** Faktura do zamknięcia: numer i kod dostawcy; pusty kod = makro nie sprawdza dostawcy. */
    public record Document(String number, String supplierCode) {
    }

    private InvoiceCloseMacro() {
    }

    /** Czy numer i kod dostawcy da się wpisać w makro. */
    public static boolean fits(Document document) {
        return document.number() != null && !document.number().isBlank()
                && !UNSAFE.matcher(document.number()).find()
                && (document.supplierCode() == null || !UNSAFE.matcher(document.supplierCode()).find());
    }

    public static String build(List<Document> documents, boolean print) {
        List<String> out = new ArrayList<>();
        out.add("# Zamknięcie faktur: " + documents.size());
        for (Document document : documents) {
            if (!fits(document)) {
                throw new IllegalArgumentException("Niedozwolony numer faktury: " + document.number());
            }
            out.add("WEBLIST|" + (document.supplierCode() == null ? "" : document.supplierCode()) + "|"
                    + document.number());
        }
        out.addAll(List.of(
                ReportSaveMacro.OPEN_LINE,
                "WEBENSURE|" + WINDOW + "|" + DIRECT_DELIVERIES + "|" + PURCHASES_TAB,
                "WEBOPEN|" + WINDOW + "|" + DIRECT_DELIVERIES + "|" + INTEGRATION,
                "WEBWAIT|" + DIALOG + "|Oui|" + INTEGRATION_CONFIRM_TEXT + "|" + PAGE_TIMEOUT_MS,
                "WEBCLICK|" + DIALOG + "|Oui",
                "WEBPAGE|" + WINDOW + "|" + INTEGRATION_RESULT_PAGE + "|" + INTEGRATION_RESULT + "|"
                        + INTEGRATION_TIMEOUT_MS + "|" + INTEGRATION_EMPTY_WINDOW + "|" + INTEGRATION_EMPTY_TEXT + "|"
                        + INTEGRATION_EMPTY_BUTTON + "|bez-integracji",
                "WEBCLICK|" + WINDOW + "|" + INTEGRATION_RESULT,
                "LABEL|bez-integracji",
                "SLEEP|" + AFTER_RESULT_BACK_MS,
                "WEBOPEN|" + WINDOW + "|" + DIRECT_DELIVERIES + "|" + BL_SCREEN + "|" + BL_CREATE_CODE,
                "WEBWAIT|" + WINDOW + "|" + BL_CREATE_CODE + "||" + PAGE_TIMEOUT_MS,
                "WEBROWS|" + WINDOW + "|" + GRID + "|" + BL_ORDER_COLUMN + "|" + BL_CREATE_CODE + "|" + BL_NEXT_PAGE
                        + "|" + documents.size() + "|" + DIALOG + "|" + BL_CONFIRM_TEXT + "|" + BL_CONFIRM_BUTTON,
                "WEBOPEN|" + WINDOW + "|" + ORDERS + "|" + ORDER_ENTRY + "|" + ORDER_CRITERION,
                "WEBWAIT|" + WINDOW + "|" + ORDER_CRITERION,
                "WEBKEY|" + WINDOW + "|" + ORDER_CRITERION + "|" + ORDER_CRITERION_KEYS,
                "WEBWAIT|" + WINDOW + "|" + ORDER_HEADER,
                "WEBHEADERS|" + WINDOW + "|" + GRID + "|" + ORDER_NUMBER_COLUMN + "|" + ORDER_IDENTIFICATION_COLUMN
                        + "|" + ORDER_HEADER + "|" + ORDER_IDENTIFICATION_FIELD + "|" + ORDER_SAVE_HEADER + "|"
                        + DIALOG + "|" + ORDER_SAVE_HEADER_TEXT + "|Oui|" + ORDER_FIRST_PAGE + "|" + ORDER_NEXT_PAGE,
                "WEBCLOSE|" + WINDOW + "|" + GRID + "|" + ORDER_NUMBER_COLUMN + "|" + ORDER_IDENTIFICATION_COLUMN
                        + "|" + ORDER_CLOSE + "|" + DIALOG + "|" + ORDER_CLOSE_TEXT + "|Oui|" + ORDER_PRINT_CHECKBOX
                        + "|" + ORDER_APPROVE_CLOSE + "|" + ORDER_FIRST_PAGE + "|" + ORDER_NEXT_PAGE,
                (print ? "PDFPRINTALL" : "WINCLOSEALL") + "|" + PDF_WINDOW + "|" + PDF_TIMEOUT_MS,
                "WINMIN|" + PROGRAM_WINDOW));
        return String.join("\r\n", out) + "\r\n";
    }
}
