package com.intermanage.backend.component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Czyta fakturę ustrukturyzowaną KSeF (schemat FA(3), starsze FA(2) też) do postaci gotowej do wydruku: kody
 * z XML zamienia na opisy po polsku, pomija puste pola. Elementy szuka po nazwie bez przestrzeni nazw, bo każda
 * wersja schematu ma inną.
 */
@Component
public class KsefInvoiceReader {

    private static final Locale POLISH = Locale.of("pl", "PL");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Faktura do wydruku. Listy są puste, gdy w XML nie było danej sekcji; pola tekstowe mogą być {@code null}. */
    public record Invoice(
            String formCode,
            String kind,
            boolean correction,
            String number,
            String currency,
            LocalDate issueDate,
            String issuePlace,
            LocalDate saleDate,
            LocalDate periodFrom,
            LocalDate periodTo,
            Party seller,
            Party buyer,
            List<Party> otherParties,
            List<Entry> correctionDetails,
            List<Line> lines,
            List<TaxRow> taxes,
            BigDecimal total,
            List<String> annotations,
            List<Entry> payment,
            List<Entry> settlement,
            List<Entry> transaction,
            List<Entry> additional,
            List<Line> orderLines,
            BigDecimal orderValue,
            List<Block> attachment,
            List<String> footerNotes,
            List<Entry> registries,
            OffsetDateTime createdAt,
            String system) {
    }

    /** Sprzedawca, nabywca albo podmiot trzeci; {@code role} to opis roli (np. "Odbiorca"). */
    public record Party(String role, String name, List<String> identifiers, List<String> address,
            List<String> details) {
    }

    public record Entry(String label, String value) {
    }

    /** Pozycja faktury (albo zamówienia przy zaliczce); {@code beforeCorrection}: wiersz "przed korektą". */
    public record Line(String number, String name, List<String> details, BigDecimal quantity, String unit,
            BigDecimal unitNet, BigDecimal unitGross, BigDecimal discount, String rate, BigDecimal net,
            BigDecimal vat, BigDecimal gross, boolean beforeCorrection) {
    }

    /** Podsumowanie stawki: netto, VAT i VAT w złotych przy fakturze w obcej walucie. */
    public record TaxRow(String rate, BigDecimal net, BigDecimal vat, BigDecimal vatPln) {
    }

    /** Blok danych załącznika faktury (FA(3)): metadane, akapity i tabele. */
    public record Block(String title, List<Entry> meta, List<String> paragraphs, List<Table> tables) {
    }

    public record Table(String description, List<Entry> meta, List<String> header, List<List<String>> rows,
            List<String> sum) {
    }

    public static class InvalidInvoiceException extends RuntimeException {
        InvalidInvoiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final Map<String, String> KINDS = Map.of(
            "VAT", "Faktura VAT",
            "KOR", "Faktura korygująca",
            "ZAL", "Faktura zaliczkowa",
            "ROZ", "Faktura rozliczeniowa",
            "UPR", "Faktura uproszczona",
            "KOR_ZAL", "Faktura korygująca fakturę zaliczkową",
            "KOR_ROZ", "Faktura korygująca fakturę rozliczeniową");

    private static final Map<String, String> THIRD_PARTY_ROLES = Map.ofEntries(
            Map.entry("1", "Faktor"),
            Map.entry("2", "Odbiorca"),
            Map.entry("3", "Podmiot pierwotny"),
            Map.entry("4", "Dodatkowy nabywca"),
            Map.entry("5", "Wystawca faktury"),
            Map.entry("6", "Dokonujący płatności"),
            Map.entry("7", "JST – wystawca"),
            Map.entry("8", "JST – odbiorca"),
            Map.entry("9", "Członek grupy VAT – wystawca"),
            Map.entry("10", "Członek grupy VAT – odbiorca"),
            Map.entry("11", "Pracownik"));

    private static final Map<String, String> PAYMENT_METHODS = Map.of(
            "1", "gotówka",
            "2", "karta",
            "3", "bon",
            "4", "czek",
            "5", "kredyt",
            "6", "przelew",
            "7", "płatność mobilna");

    private static final Map<String, String> CORRECTION_TYPES = Map.of(
            "1", "w dacie ujęcia faktury pierwotnej",
            "2", "w dacie wystawienia faktury korygującej",
            "3", "w dacie innej niż data wystawienia faktury korygującej i ujęcia faktury pierwotnej");

    private static final Map<String, String> TAXPAYER_STATUS = Map.of(
            "1", "w stanie likwidacji",
            "2", "w restrukturyzacji",
            "3", "w stanie upadłości",
            "4", "przedsiębiorstwo w spadku");

    public Invoice read(byte[] xml) {
        Xml root = parse(xml);
        if (!"Faktura".equals(root.name())) {
            throw new InvalidInvoiceException("To nie jest faktura KSeF (" + root.name() + ")", null);
        }
        Xml fa = root.child("Fa");
        String kindCode = fa.text("RodzajFaktury");
        List<Line> lines = fa.children("FaWiersz").stream().map(KsefInvoiceReader::line).toList();
        Xml order = fa.child("Zamowienie");
        return new Invoice(
                root.child("Naglowek").child("KodFormularza").attribute("kodSystemowy"),
                KINDS.getOrDefault(kindCode, kindCode != null ? "Faktura " + kindCode : "Faktura"),
                kindCode != null && kindCode.startsWith("KOR"),
                fa.text("P_2"),
                fa.text("KodWaluty"),
                fa.date("P_1"),
                fa.text("P_1M"),
                fa.date("P_6"),
                fa.child("OkresFa").date("P_6_Od"),
                fa.child("OkresFa").date("P_6_Do"),
                party(root.child("Podmiot1"), "Sprzedawca"),
                party(root.child("Podmiot2"), "Nabywca"),
                otherParties(root),
                correction(fa),
                lines,
                taxes(fa, lines),
                fa.decimal("P_15"),
                annotations(fa.child("Adnotacje")),
                payment(fa.child("Platnosc")),
                settlement(fa.child("Rozliczenie")),
                transaction(fa.child("WarunkiTransakcji")),
                additional(fa),
                order.children("ZamowienieWiersz").stream().map(KsefInvoiceReader::orderLine).toList(),
                order.decimal("WartoscZamowienia"),
                root.child("Zalacznik").children("BlokDanych").stream().map(KsefInvoiceReader::block).toList(),
                root.child("Stopka").children("Informacje").stream().map(info -> info.text("StopkaFaktury"))
                        .filter(Objects::nonNull).toList(),
                registries(root.child("Stopka")),
                root.child("Naglowek").dateTime("DataWytworzeniaFa"),
                root.child("Naglowek").text("SystemInfo"));
    }

    private static Party party(Xml subject, String role) {
        if (!subject.present()) {
            return null;
        }
        List<String> identifiers = identifiers(subject);
        String prefix = subject.text("PrefiksPodatnika");
        if (prefix != null && !identifiers.isEmpty() && identifiers.getFirst().startsWith("NIP ")) {
            identifiers.set(0, "NIP " + prefix + identifiers.getFirst().substring(4));
        }
        if (subject.text("NrEORI") != null) {
            identifiers.add("EORI " + subject.text("NrEORI"));
        }
        List<String> details = new ArrayList<>();
        List<String> correspondence = address(subject.child("AdresKoresp"));
        if (!correspondence.isEmpty()) {
            details.add("Adres do korespondencji: " + String.join(", ", correspondence));
        }
        for (Xml contact : subject.children("DaneKontaktowe")) {
            add(details, "e-mail: ", contact.text("Email"));
            add(details, "tel. ", contact.text("Telefon"));
        }
        add(details, "Nr klienta: ", subject.text("NrKlienta"));
        add(details, "ID nabywcy: ", subject.text("IDNabywcy"));
        add(details, "Udział: ", subject.text("Udzial") != null ? subject.text("Udzial") + "%" : null);
        String status = subject.text("StatusInfoPodatnika");
        if (status != null) {
            details.add("Podatnik " + TAXPAYER_STATUS.getOrDefault(status, status));
        }
        if ("1".equals(subject.text("JST"))) {
            details.add("Jednostka podrzędna JST");
        }
        if ("1".equals(subject.text("GV"))) {
            details.add("Członek grupy VAT");
        }
        return new Party(role, subject.child("DaneIdentyfikacyjne").text("Nazwa"), identifiers,
                address(subject.child("Adres")), details);
    }

    private static List<String> identifiers(Xml subject) {
        Xml data = subject.child("DaneIdentyfikacyjne");
        List<String> identifiers = new ArrayList<>();
        add(identifiers, "NIP ", data.text("NIP"));
        add(identifiers, "IDWew ", data.text("IDWew"));
        if (data.text("NrVatUE") != null) {
            identifiers.add("VAT UE " + Objects.toString(data.text("KodUE"), "") + data.text("NrVatUE"));
        }
        if (data.text("NrID") != null) {
            identifiers.add("Identyfikator podatkowy " + Objects.toString(data.text("KodKraju"), "") + " "
                    + data.text("NrID"));
        }
        if ("1".equals(data.text("BrakID"))) {
            identifiers.add("Bez identyfikatora podatkowego");
        }
        return identifiers;
    }

    private static List<String> address(Xml address) {
        List<String> lines = new ArrayList<>();
        add(lines, "", address.text("AdresL1"));
        add(lines, "", address.text("AdresL2"));
        String country = address.text("KodKraju");
        if (country != null && !"PL".equals(country)) {
            lines.add("Kraj: " + country);
        }
        add(lines, "GLN ", address.text("GLN"));
        return lines;
    }

    private static List<Party> otherParties(Xml root) {
        List<Party> parties = new ArrayList<>();
        for (Xml subject : root.children("Podmiot3")) {
            String role = subject.text("Rola");
            parties.add(party(subject, role != null ? THIRD_PARTY_ROLES.getOrDefault(role, "Rola " + role)
                    : Objects.toString(subject.text("OpisRoli"), "Podmiot trzeci")));
        }
        Xml authorized = root.child("PodmiotUpowazniony");
        if (authorized.present()) {
            String role = switch (Objects.toString(authorized.text("RolaPU"), "")) {
                case "1" -> "Organ egzekucyjny";
                case "2" -> "Komornik sądowy";
                case "3" -> "Przedstawiciel podatkowy";
                default -> "Podmiot upoważniony";
            };
            Party party = party(authorized, role);
            for (Xml contact : authorized.children("DaneKontaktowe")) {
                add(party.details(), "e-mail: ", contact.text("EmailPU"));
                add(party.details(), "tel. ", contact.text("TelefonPU"));
            }
            parties.add(party);
        }
        return parties;
    }

    private static List<Entry> correction(Xml fa) {
        List<Entry> entries = new ArrayList<>();
        entry(entries, "Przyczyna korekty", fa.text("PrzyczynaKorekty"));
        String type = fa.text("TypKorekty");
        if (type != null) {
            entries.add(new Entry("Skutek korekty", CORRECTION_TYPES.getOrDefault(type, type)));
        }
        for (Xml corrected : fa.children("DaneFaKorygowanej")) {
            String value = Objects.toString(corrected.text("NrFaKorygowanej"), "")
                    + (corrected.date("DataWystFaKorygowanej") != null
                            ? " z " + formatDate(corrected.date("DataWystFaKorygowanej")) : "");
            if (corrected.text("NrKSeFFaKorygowanej") != null) {
                value += "\nNr KSeF: " + corrected.text("NrKSeFFaKorygowanej");
            } else if ("1".equals(corrected.text("NrKSeFN"))) {
                value += "\nwystawiona poza KSeF";
            }
            entries.add(new Entry("Faktura korygowana", value));
        }
        entry(entries, "Okres, którego dotyczy korekta", fa.text("OkresFaKorygowanej"));
        entry(entries, "Poprawny numer faktury", fa.text("NrFaKorygowany"));
        Party seller = party(fa.child("Podmiot1K"), "Sprzedawca przed korektą");
        if (seller != null) {
            entries.add(new Entry(seller.role(), partySummary(seller)));
        }
        for (Xml buyer : fa.children("Podmiot2K")) {
            Party party = party(buyer, "Nabywca przed korektą");
            entries.add(new Entry(party.role(), partySummary(party)));
        }
        entry(entries, "Zapłacono przed korektą", money(fa.decimal("P_15ZK")));
        entry(entries, "Kurs waluty przed korektą", fa.text("KursWalutyZK"));
        return entries;
    }

    private static String partySummary(Party party) {
        return Stream.concat(Stream.of(party.name()), Stream.concat(party.identifiers().stream(),
                party.address().stream())).filter(Objects::nonNull).collect(Collectors.joining("\n"));
    }

    private static Line line(Xml row) {
        List<String> details = new ArrayList<>();
        add(details, "Indeks: ", row.text("Indeks"));
        add(details, "GTIN: ", row.text("GTIN"));
        add(details, "PKWiU: ", row.text("PKWiU"));
        add(details, "CN: ", row.text("CN"));
        add(details, "PKOB: ", row.text("PKOB"));
        add(details, "", row.text("GTU"));
        add(details, "Procedura: ", row.text("Procedura"));
        add(details, "Data dostawy: ", row.date("P_6A") != null ? formatDate(row.date("P_6A")) : null);
        add(details, "Akcyza: ", money(row.decimal("KwotaAkcyzy")));
        add(details, "Kurs waluty: ", row.text("KursWaluty"));
        if ("1".equals(row.text("P_12_Zal_15"))) {
            details.add("Towar z załącznika 15 (podzielona płatność)");
        }
        String rate = row.text("P_12") != null ? row.text("P_12") : row.text("P_12_XII");
        return new Line(row.text("NrWierszaFa"), row.text("P_7"), details, row.decimal("P_8B"), row.text("P_8A"),
                row.decimal("P_9A"), row.decimal("P_9B"), row.decimal("P_10"), rateLabel(rate, row.text("P_12_XII")),
                row.decimal("P_11"), row.decimal("P_11Vat"), row.decimal("P_11A"), "1".equals(row.text("StanPrzed")));
    }

    private static Line orderLine(Xml row) {
        List<String> details = new ArrayList<>();
        add(details, "Indeks: ", row.text("IndeksZ"));
        add(details, "GTIN: ", row.text("GTINZ"));
        add(details, "PKWiU: ", row.text("PKWiUZ"));
        add(details, "CN: ", row.text("CNZ"));
        add(details, "", row.text("GTUZ"));
        String rate = row.text("P_12Z") != null ? row.text("P_12Z") : row.text("P_12Z_XII");
        return new Line(row.text("NrWierszaZam"), row.text("P_7Z"), details, row.decimal("P_8BZ"),
                row.text("P_8AZ"), row.decimal("P_9AZ"), null, null, rateLabel(rate, row.text("P_12Z_XII")),
                row.decimal("P_11NettoZ"), row.decimal("P_11VatZ"), null, "1".equals(row.text("StanPrzedZ")));
    }

    /** Stawka z P_12 do wydruku: "23" → "23%", "0 WDT" → "0% WDT", "zw" → "zw.", "oo" → "odwr. obc.". */
    static String rateLabel(String code, String ossRate) {
        if (code == null) {
            return null;
        }
        if (code.equals(ossRate)) {
            return code + "% OSS";
        }
        if (code.chars().allMatch(Character::isDigit)) {
            return code + "%";
        }
        return switch (code) {
            case "0 KR" -> "0%";
            case "0 WDT" -> "0% WDT";
            case "0 EX" -> "0% eksp.";
            case "zw" -> "zw.";
            case "oo" -> "odwr. obc.";
            case "np", "np I", "np II" -> "np.";
            default -> code;
        };
    }

    /** Sumy z P_13_x / P_14_x. Stawki 23/22, 8/7 i 4/3 dzielą pole - opis bierzemy ze stawek pozycji. */
    private static List<TaxRow> taxes(Xml fa, List<Line> lines) {
        List<String> rates = lines.stream().map(Line::rate).filter(Objects::nonNull).toList();
        List<TaxRow> rows = new ArrayList<>();
        tax(rows, fa, "1", rates.contains("22%") && !rates.contains("23%") ? "22%" : "23%");
        tax(rows, fa, "2", rates.contains("7%") && !rates.contains("8%") ? "7%" : "8%");
        tax(rows, fa, "3", "5%");
        tax(rows, fa, "4", rates.contains("3%") && !rates.contains("4%") ? "3% (ryczałt)" : "4% (ryczałt)");
        tax(rows, fa, "5", "Procedura OSS");
        tax(rows, fa, "6_1", "0% (kraj)");
        tax(rows, fa, "6_2", "0% (WDT)");
        tax(rows, fa, "6_3", "0% (eksport)");
        tax(rows, fa, "6", "0%");
        tax(rows, fa, "7", "Zwolnione");
        tax(rows, fa, "8", "Niepodlegające (poza krajem)");
        tax(rows, fa, "9", "Niepodlegające (art. 100 ust. 1 pkt 4)");
        tax(rows, fa, "10", "Odwrotne obciążenie");
        tax(rows, fa, "11", "Procedura marży");
        return rows;
    }

    private static void tax(List<TaxRow> rows, Xml fa, String field, String label) {
        BigDecimal net = fa.decimal("P_13_" + field);
        if (net != null) {
            rows.add(new TaxRow(label, net, fa.decimal("P_14_" + field), fa.decimal("P_14_" + field + "W")));
        }
    }

    private static List<String> annotations(Xml notes) {
        List<String> list = new ArrayList<>();
        if ("1".equals(notes.text("P_16"))) {
            list.add("Metoda kasowa");
        }
        if ("1".equals(notes.text("P_17"))) {
            list.add("Samofakturowanie");
        }
        if ("1".equals(notes.text("P_18"))) {
            list.add("Odwrotne obciążenie");
        }
        if ("1".equals(notes.text("P_18A"))) {
            list.add("Mechanizm podzielonej płatności");
        }
        Xml exemption = notes.child("Zwolnienie");
        if ("1".equals(exemption.text("P_19"))) {
            String basis = exemption.text("P_19A") != null ? "przepis ustawy: " + exemption.text("P_19A")
                    : exemption.text("P_19B") != null ? "przepis dyrektywy: " + exemption.text("P_19B")
                    : exemption.text("P_19C") != null ? "podstawa: " + exemption.text("P_19C") : null;
            list.add("Zwolnienie z VAT" + (basis != null ? " – " + basis : ""));
        }
        if ("1".equals(notes.child("NoweSrodkiTransportu").text("P_22"))) {
            list.add("Wewnątrzwspólnotowa dostawa nowych środków transportu");
        }
        if ("1".equals(notes.text("P_23"))) {
            list.add("Procedura uproszczona – transakcja trójstronna (drugi w kolejności podatnik)");
        }
        Xml margin = notes.child("PMarzy");
        if ("1".equals(margin.text("P_PMarzy"))) {
            String kind = "1".equals(margin.text("P_PMarzy_2")) ? " – biura podróży"
                    : "1".equals(margin.text("P_PMarzy_3_1")) ? " – towary używane"
                    : "1".equals(margin.text("P_PMarzy_3_2")) ? " – dzieła sztuki"
                    : "1".equals(margin.text("P_PMarzy_3_3")) ? " – przedmioty kolekcjonerskie i antyki" : "";
            list.add("Procedura marży" + kind);
        }
        return list;
    }

    private static List<Entry> payment(Xml payment) {
        List<Entry> entries = new ArrayList<>();
        if ("1".equals(payment.text("Zaplacono"))) {
            entries.add(new Entry("Zapłacono", payment.date("DataZaplaty") != null
                    ? formatDate(payment.date("DataZaplaty")) : "tak"));
        }
        String partial = payment.text("ZnacznikZaplatyCzesciowej");
        if (partial != null) {
            entries.add(new Entry("Zapłata częściowa", "1".equals(partial) ? "zapłacono część należności"
                    : "należność zapłacona w całości w częściach"));
        }
        for (Xml part : payment.children("ZaplataCzesciowa")) {
            String method = part.text("FormaPlatnosci");
            entries.add(new Entry("Wpłata", money(part.decimal("KwotaZaplatyCzesciowej"))
                    + (part.date("DataZaplatyCzesciowej") != null
                            ? " · " + formatDate(part.date("DataZaplatyCzesciowej")) : "")
                    + (method != null ? " · " + PAYMENT_METHODS.getOrDefault(method, method) : "")));
        }
        for (Xml term : payment.children("TerminPlatnosci")) {
            String value = term.date("Termin") != null ? formatDate(term.date("Termin")) : null;
            Xml description = term.child("TerminOpis");
            if (description.present()) {
                // FA(3): ilość, jednostka i zdarzenie; FA(2): sam tekst.
                String text = description.hasChildElements()
                        ? Stream.of(description.text("Ilosc"), description.text("Jednostka"),
                                description.text("ZdarzeniePoczatkowe")).filter(Objects::nonNull)
                                .collect(Collectors.joining(" "))
                        : description.text();
                value = value != null ? value + " (" + text + ")" : text;
            }
            entry(entries, "Termin płatności", value);
        }
        String method = payment.text("FormaPlatnosci");
        if (method != null) {
            entries.add(new Entry("Forma płatności", PAYMENT_METHODS.getOrDefault(method, method)));
        } else {
            entry(entries, "Forma płatności", payment.text("OpisPlatnosci"));
        }
        for (Xml account : payment.children("RachunekBankowy")) {
            entries.add(new Entry("Rachunek bankowy", account(account)));
        }
        for (Xml account : payment.children("RachunekBankowyFaktora")) {
            entries.add(new Entry("Rachunek faktora", account(account)));
        }
        Xml discount = payment.child("Skonto");
        if (discount.present()) {
            entries.add(new Entry("Skonto", Stream.of(discount.text("WarunkiSkonta"), discount.text("WysokoscSkonta"))
                    .filter(Objects::nonNull).collect(Collectors.joining(": "))));
        }
        entry(entries, "Link do płatności", payment.text("LinkDoPlatnosci"));
        entry(entries, "Identyfikator płatności KSeF", payment.text("IPKSeF"));
        return entries;
    }

    private static String account(Xml account) {
        String bank = Stream.of(account.text("NazwaBanku"), account.text("SWIFT") != null
                        ? "SWIFT " + account.text("SWIFT") : null)
                .filter(Objects::nonNull).collect(Collectors.joining(" · "));
        return Stream.of(accountNumber(account.text("NrRB")), bank.isEmpty() ? null : bank, account.text("OpisRachunku"))
                .filter(Objects::nonNull).collect(Collectors.joining("\n"));
    }

    /** Polski numer rachunku (26 cyfr) w grupach jak na przelewie: 12 3456 7890 ... */
    static String accountNumber(String number) {
        if (number == null || !number.matches("\\d{26}")) {
            return number;
        }
        StringBuilder grouped = new StringBuilder(number.substring(0, 2));
        for (int i = 2; i < 26; i += 4) {
            grouped.append(' ').append(number, i, i + 4);
        }
        return grouped.toString();
    }

    private static List<Entry> settlement(Xml settlement) {
        List<Entry> entries = new ArrayList<>();
        for (Xml charge : settlement.children("Obciazenia")) {
            entries.add(new Entry("Obciążenie: " + Objects.toString(charge.text("Powod"), ""),
                    money(charge.decimal("Kwota"))));
        }
        entry(entries, "Suma obciążeń", money(settlement.decimal("SumaObciazen")));
        for (Xml deduction : settlement.children("Odliczenia")) {
            entries.add(new Entry("Odliczenie: " + Objects.toString(deduction.text("Powod"), ""),
                    money(deduction.decimal("Kwota"))));
        }
        entry(entries, "Suma odliczeń", money(settlement.decimal("SumaOdliczen")));
        entry(entries, "Do zapłaty", money(settlement.decimal("DoZaplaty")));
        entry(entries, "Do rozliczenia", money(settlement.decimal("DoRozliczenia")));
        return entries;
    }

    private static List<Entry> transaction(Xml terms) {
        List<Entry> entries = new ArrayList<>();
        for (Xml contract : terms.children("Umowy")) {
            entry(entries, "Umowa", numberAndDate(contract.text("NrUmowy"), contract.date("DataUmowy")));
        }
        for (Xml order : terms.children("Zamowienia")) {
            entry(entries, "Zamówienie", numberAndDate(order.text("NrZamowienia"), order.date("DataZamowienia")));
        }
        for (Xml batch : terms.children("NrPartiiTowaru")) {
            entry(entries, "Partia towaru", batch.text());
        }
        entry(entries, "Warunki dostawy", terms.text("WarunkiDostawy"));
        if (terms.text("KursUmowny") != null) {
            entries.add(new Entry("Kurs umowny", terms.text("KursUmowny") + " "
                    + Objects.toString(terms.text("WalutaUmowna"), "")));
        }
        for (Xml transport : terms.children("Transport")) {
            Xml carrier = transport.child("Przewoznik").child("DaneIdentyfikacyjne");
            entry(entries, "Przewoźnik", carrier.text("Nazwa"));
            entry(entries, "Zlecenie transportu", transport.text("NrZleceniaTransportu"));
        }
        if ("1".equals(terms.text("PodmiotPosredniczacy"))) {
            entries.add(new Entry("Podmiot pośredniczący", "tak"));
        }
        return entries;
    }

    private static List<Entry> additional(Xml fa) {
        List<Entry> entries = new ArrayList<>();
        List<String> deliveryNotes = fa.children("WZ").stream().map(Xml::text).filter(Objects::nonNull).toList();
        if (!deliveryNotes.isEmpty()) {
            entries.add(new Entry("Dokumenty WZ", String.join(", ", deliveryNotes)));
        }
        for (Xml description : fa.children("DodatkowyOpis")) {
            String row = description.text("NrWiersza");
            entries.add(new Entry(Objects.toString(description.text("Klucz"), "Informacja")
                    + (row != null ? " (poz. " + row + ")" : ""), description.text("Wartosc")));
        }
        for (Xml advance : fa.children("FakturaZaliczkowa")) {
            entry(entries, "Faktura zaliczkowa", advance.text("NrKSeFFaZaliczkowej") != null
                    ? "Nr KSeF " + advance.text("NrKSeFFaZaliczkowej") : advance.text("NrFaZaliczkowej"));
        }
        for (Xml partial : fa.children("ZaliczkaCzesciowa")) {
            entry(entries, "Zaliczka częściowa", money(partial.decimal("P_15Z"))
                    + (partial.date("P_6Z") != null ? " z " + formatDate(partial.date("P_6Z")) : ""));
        }
        entry(entries, "Kurs waluty", fa.text("KursWalutyZ"));
        if ("1".equals(fa.text("FP"))) {
            entries.add(new Entry("Faktura do paragonu", "tak"));
        }
        if ("1".equals(fa.text("TP"))) {
            entries.add(new Entry("Powiązania między nabywcą a dostawcą", "tak"));
        }
        if ("1".equals(fa.text("ZwrotAkcyzy"))) {
            entries.add(new Entry("Zwrot akcyzy", "dotyczy oleju napędowego do produkcji rolnej"));
        }
        return entries;
    }

    private static Block block(Xml block) {
        List<Table> tables = block.children("Tabela").stream().map(table -> new Table(
                table.text("Opis"),
                keyValues(table.children("TMetaDane"), "TKlucz", "TWartosc"),
                table.child("TNaglowek").children("Kol").stream().map(column -> column.text("NKom")).toList(),
                table.children("Wiersz").stream()
                        .map(row -> row.children("WKom").stream().map(Xml::text).toList()).toList(),
                table.child("Suma").children("SKom").stream().map(Xml::text).toList())).toList();
        return new Block(block.text("ZNaglowek"), keyValues(block.children("MetaDane"), "ZKlucz", "ZWartosc"),
                block.child("Tekst").children("Akapit").stream().map(Xml::text).filter(Objects::nonNull).toList(),
                tables);
    }

    private static List<Entry> keyValues(List<Xml> items, String key, String value) {
        return items.stream().map(item -> new Entry(item.text(key), item.text(value))).toList();
    }

    private static List<Entry> registries(Xml footer) {
        List<Entry> entries = new ArrayList<>();
        for (Xml registry : footer.children("Rejestry")) {
            entry(entries, "Pełna nazwa", registry.text("PelnaNazwa"));
            entry(entries, "KRS", registry.text("KRS"));
            entry(entries, "REGON", registry.text("REGON"));
            entry(entries, "BDO", registry.text("BDO"));
        }
        return entries;
    }

    private static String numberAndDate(String number, LocalDate date) {
        if (number == null && date == null) {
            return null;
        }
        return Objects.toString(number, "") + (date != null ? (number != null ? " z " : "") + formatDate(date) : "");
    }

    private static void entry(List<Entry> entries, String label, String value) {
        if (value != null && !value.isBlank()) {
            entries.add(new Entry(label, value));
        }
    }

    private static void add(List<String> list, String prefix, String value) {
        if (value != null && !value.isBlank()) {
            list.add(prefix + value);
        }
    }

    static String formatDate(LocalDate date) {
        return date != null ? DATE.format(date) : null;
    }

    /** Kwota po polsku, z dwoma miejscami: 1 234,50. */
    static String money(BigDecimal amount) {
        return amount != null ? new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(POLISH)).format(amount)
                : null;
    }

    private static Xml parse(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // Plik przychodzi z zewnątrz: bez DTD i encji zewnętrznych (XXE).
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return new Xml(factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml)).getDocumentElement());
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new InvalidInvoiceException("Nie udało się odczytać XML faktury", e);
        }
    }

    /** Element XML, po którym chodzimy bez sprawdzania nulli: brakujący element jest pusty. */
    private record Xml(Element element) {

        private static final Xml EMPTY = new Xml(null);

        boolean present() {
            return element != null;
        }

        String name() {
            return element != null ? element.getLocalName() : null;
        }

        Xml child(String name) {
            if (element != null) {
                for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
                    if (node instanceof Element child && name.equals(child.getLocalName())) {
                        return new Xml(child);
                    }
                }
            }
            return EMPTY;
        }

        List<Xml> children(String name) {
            List<Xml> list = new ArrayList<>();
            if (element != null) {
                for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
                    if (node instanceof Element child && name.equals(child.getLocalName())) {
                        list.add(new Xml(child));
                    }
                }
            }
            return list;
        }

        boolean hasChildElements() {
            if (element != null) {
                for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
                    if (node instanceof Element) {
                        return true;
                    }
                }
            }
            return false;
        }

        String text() {
            if (element == null) {
                return null;
            }
            String text = element.getTextContent().strip();
            return text.isEmpty() ? null : text;
        }

        String text(String name) {
            return child(name).text();
        }

        String attribute(String name) {
            return element != null && element.hasAttribute(name) ? element.getAttribute(name) : null;
        }

        BigDecimal decimal(String name) {
            String text = text(name);
            try {
                return text != null ? new BigDecimal(text) : null;
            } catch (NumberFormatException e) {
                throw new InvalidInvoiceException("Niepoprawna liczba w " + name + ": " + text, e);
            }
        }

        LocalDate date(String name) {
            String text = text(name);
            try {
                return text != null ? LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text) : null;
            } catch (DateTimeParseException e) {
                throw new InvalidInvoiceException("Niepoprawna data w " + name + ": " + text, e);
            }
        }

        OffsetDateTime dateTime(String name) {
            String text = text(name);
            try {
                return text != null ? OffsetDateTime.parse(text) : null;
            } catch (DateTimeParseException e) {
                return null;
            }
        }
    }
}
