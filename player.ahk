#Requires AutoHotkey v2.0
;@Ahk2Exe-SetMainIcon player.ico
;@Ahk2Exe-SetName Intermanage Makra
;@Ahk2Exe-SetDescription Intermanage - odtwarzacz makr
;@Ahk2Exe-SetCompanyName Intermanage
;@Ahk2Exe-SetVersion 41.0.0.0
#SingleInstance Force
SendMode "Event"
SetKeyDelay 30, 30
CoordMode "Mouse", "Screen"
SetTitleMatchMode 2 ; WEBCLICK: wystarczy fragment tytułu okna
global PlayerVersion := 41 ; podbijamy przy każdej zmianie (też SetVersion wyżej), widać go w menu przy zegarze i w raporcie skanowania
global AlwaysOnTop := " 262144" ; opcja MsgBox: wynik musi być widać, nawet gdy po ostatnim zamknięciu otworzy się PDF bonu

global MacroRunning := false ; Esc działa tylko w trakcie makra
global ScanMode := false ; Alt+R: następny klik skanuje okno zamiast w nie trafić
global AutostartKey := "HKCU\Software\Microsoft\Windows\CurrentVersion\Run"
global AutostartName := "IntermanageMakra"
global SettingsKey := "HKCU\Software\Intermanage\Makra"
global IdleTip := "Intermanage Makra " PlayerVersion

SetupTray()
if A_IsCompiled {
    SyncAutostart()
    SyncClipboardPrivacy()
}
; Przy starcie z Windows bez komunikatu; po ręcznym uruchomieniu trzeba wiedzieć, że program już działa.
if (A_Args.Length = 0 || A_Args[1] != "autostart")
    TrayTip "Działa w tle, ikona jest przy zegarze.`nSkopiuj makro ze strony i naciśnij Alt+E.", "Intermanage Makra", 1

; --- Praca w tle: menu przy zegarze i start z Windows ---

SetupTray() {
    A_IconTip := IdleTip
    tray := A_TrayMenu
    tray.Delete()
    tray.Add("Intermanage Makra, wersja " PlayerVersion, ShowHelp)
    tray.Add()
    tray.Add("Uruchamiaj z Windows", ToggleAutostart)
    tray.Add()
    tray.Add("Zakończ", (*) => ExitApp())
    tray.Default := "Intermanage Makra, wersja " PlayerVersion
    if !A_IsCompiled
        tray.Disable("Uruchamiaj z Windows")
    else if AutostartEnabled()
        tray.Check("Uruchamiaj z Windows")
}

ShowHelp(*) {
    MsgBox "Program działa w tle i nie trzeba go uruchamiać przed makrem.`n`n"
        . "Alt+E: wykonuje makro skopiowane ze strony. Makro działa raz, potem Schowek jest pusty.`n"
        . "Esc w trakcie makra: zatrzymuje makro.`n"
        . "Alt+R nad oknem Mercalys: tryb skanowania, klik w okno daje listę przycisków strony w Schowku, Esc anuluje.", "Intermanage Makra " PlayerVersion, "Iconi"
}

AutostartEnabled() => RegRead(AutostartKey, AutostartName, "") != ""

; Start z Windows jest domyślnie włączony, a ścieżka odświeża się po przeniesieniu programu. Wyłączenie zostaje zapamiętane.
SyncAutostart() {
    if (RegRead(SettingsKey, "AutostartOff", 0) = 1)
        return
    try {
        RegWrite "`"" A_ScriptFullPath "`" autostart", "REG_SZ", AutostartKey, AutostartName
        A_TrayMenu.Check("Uruchamiaj z Windows")
    }
}

; Makro skopiowane w przeglądarce trafia do Historii Schowka (Win+V) i do chmury Microsoftu, więc samo
; A_Clipboard := "" w odtwarzaczu go stamtąd nie usuwa. Na stacji wyłączamy jedno i drugie, żeby makro nie
; zostawało nigdzie poza jednym wklejeniem. Wyłączenie Historii czyści też wpisy już w niej zebrane.
SyncClipboardPrivacy() {
    ClipKey := "HKCU\Software\Microsoft\Clipboard"
    try RegWrite 0, "REG_DWORD", ClipKey, "EnableClipboardHistory"
    try RegWrite 0, "REG_DWORD", ClipKey, "CloudClipboardAutomaticUpload"
}

SetAutostart(enabled) {
    if enabled {
        RegWrite 0, "REG_DWORD", SettingsKey, "AutostartOff"
        RegWrite "`"" A_ScriptFullPath "`" autostart", "REG_SZ", AutostartKey, AutostartName
    } else {
        RegWrite 1, "REG_DWORD", SettingsKey, "AutostartOff"
        try RegDelete AutostartKey, AutostartName
    }
}

ToggleAutostart(name, *) {
    try {
        SetAutostart(!AutostartEnabled())
        A_TrayMenu.ToggleCheck(name)
    } catch as problem
        MsgBox "Nie udało się zmienić startu z Windows: " problem.Message, "Intermanage Makra", "Icon!"
}

; --- Skróty: program działa stale, więc poza swoimi przypadkami nie może zabierać klawiszy innym programom ---

; Makro z pierwszej linii poleceń; inny tekst w Schowku zostawia Alt+E programowi, w którym się go nacisnęło.
IsMacro(text) => RegExMatch(text, "i)^(\s*(#[^\n]*)?\r?\n)*\s*(#[^\n]*\r?\n\s*)*(ASK|CLICK|TEXT|KEY|SLEEP|WEB[A-Z]+|WIN[A-Z]+)\s*[|;]") = 1

ClipboardHasMacro() {
    try return IsMacro(A_Clipboard)
    return false
}

MouseOverMercalys() {
    try {
        MouseGetPos , , &hwnd
        return WinGetProcessName(hwnd) = "Mercalys.exe"
    }
    return false
}

; Alt+R nad oknem Mercalys włącza tryb skanowania. Klik jest przechwycony, więc nie uruchomi przycisku pod kursorem.
#HotIf !MacroRunning && !ScanMode && MouseOverMercalys()
<!r:: {
    global ScanMode := true
    KeyWait "LAlt"
    SetTimer ShowScanTip, 50
}

#HotIf ScanMode
LButton:: {
    EndScan()
    KeyWait "LButton"
    ScanWindow()
}

Esc:: EndScan()
#HotIf

ShowScanTip() {
    CoordMode "ToolTip", "Screen"
    MouseGetPos &x, &y
    ToolTip "Kliknij okno do zeskanowania. Esc anuluje.", x + 18, y + 18
}

EndScan() {
    global ScanMode := false
    SetTimer ShowScanTip, 0
    ToolTip
}

; Wynik trafia do Schowka (zastępuje skopiowane makro).
ScanWindow() {
    MouseGetPos , , &hwnd
    report := "Odtwarzacz w wersji " PlayerVersion "`nOkno: " WinGetTitle(hwnd) "`nKlasa: " WinGetClass(hwnd) "`nProgram: " WinGetProcessName(hwnd) "`n"
    docs := HtmlDocuments(hwnd)
    if (docs.Length = 0)
        report .= "`nNie znaleziono strony HTML w tym oknie."
    for doc in docs {
        visible := ""
        for element in ClickableElements(doc) {
            if IsShown(element)
                visible .= DescribeElement(element) "`n"
        }
        for element in FormElements(doc) {
            if IsShown(element)
                visible .= DescribeField(element) "`n"
        }
        if (visible != "")
            report .= "`nStrona: " WebProp(doc, "URL") "`n" visible
    }
    A_Clipboard := report
    MsgBox report, "Przyciski strony (skopiowano do Schowka)"
}

; Lewy Alt+E na jedną rękę; AltGr+E zostaje literą ę. Makro działa raz: przypadkowy skrót w innym programie nie może
; powtórzyć zamknięcia dostawy ani przeceny.
#HotIf !MacroRunning && ClipboardHasMacro()
<!e:: {
    global MacroRunning
    macro := Trim(A_Clipboard)
    A_Clipboard := ""
    MacroRunning := true
    KeyWait "LAlt" ; wciśnięty Alt zmieszałby się z klawiszami makra
    KeyWait "e"
    A_IconTip := "Intermanage Makra: makro w toku, Esc zatrzymuje"
    try PlayMacro(macro)
    finally {
        MacroRunning := false
        A_IconTip := IdleTip
    }
}

; Stop awaryjny: okno wstrzymuje makro, a ponowne uruchomienie programu je kończy, nawet gdy czeka na stronę.
#HotIf MacroRunning
Esc:: {
    MsgBox "Makro zatrzymane. Sprawdź w Mercalys, co zostało zrobione.", "Makro zatrzymane", "Icon!" AlwaysOnTop
    Reload
}
#HotIf

PlayMacro(rawClip) {
    lines := StrSplit(rawClip, "`n", "`r")
    wanted := Map() ; bony z linii WEBLIST
    results := Map() ; Nr Zamów bonów z listy, z WEBROWS
    report := "" ; podsumowania etapów, pokazane na końcu makra
    closingRan := false ; czy w tym przebiegu był krok WEBCLOSE
    closedCount := 0 ; dostawy zamknięte przez WEBCLOSE w tym przebiegu
    vars := Map() ; wartości odczytane przez WEBREAD, wstawiane w kolejne linie jako %nazwa%

    index := 0
    while (index < lines.Length) { ; pętla po numerze linii, bo WEBIFROW i WEBIFVALUE przeskakują do LABEL
        index += 1
        line := Trim(lines[index])
        if (line = "" || SubStr(line, 1, 1) = "#")
            continue
        ; Tylko nazwy już odczytane: znak % w zwykłej linii zostaje bez zmian.
        for name, value in vars
            line := StrReplace(line, "%" name "%", value)

        sep := InStr(line, "|") ? "|" : ";"
        parts := StrSplit(line, sep)
        cmd := StrUpper(Trim(parts[1]))

        switch cmd {
            case "CLICK":
                if (parts.Length >= 3) {
                    x := Integer(Trim(parts[2]))
                    y := Integer(Trim(parts[3]))
                    Click x, y
                }
            case "TEXT":
                if (parts.Length >= 2) {
                    SendText parts[2]
                }
            case "KEY":
                if (parts.Length >= 2) {
                    Send Trim(parts[2])
                }
            case "SLEEP":
                if (parts.Length >= 2) {
                    Sleep Integer(Trim(parts[2]))
                }
            case "WEBCLICK", "WEBCHECK":
                ; WEBCLICK|fragment tytułu okna|id, name, value albo tekst przycisku|limit w ms (domyślnie 10000)
                ; WEBCHECK to samo dla pola wyboru: klika tylko, gdy nie jest zaznaczone.
                problem := parts.Length < 3 ? "Linia " cmd " wymaga tytułu okna i elementu."
                    : WebClick(Trim(parts[2]), Trim(parts[3]), parts.Length >= 4 ? Integer(Trim(parts[4])) : 10000, cmd = "WEBCHECK")
                if (problem != "") {
                    MsgBox problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!"
                    return
                }
            case "WEBROW":
                ; WEBROW|fragment tytułu okna|siatka|numer kolumny od 0|limit w ms: zaznacza pierwszy wiersz z pustą kolumną
                if (parts.Length < 4) {
                    MsgBox "Linia WEBROW wymaga okna, siatki i kolumny.", "Błąd makra", "Icon!"
                    return
                }
                problem := WebSelectRow(Trim(parts[2]), Trim(parts[3]), Integer(Trim(parts[4])), parts.Length >= 5 ? Integer(Trim(parts[5])) : 10000)
                if (problem = "BRAK") {
                    MsgBox "Na tej stronie listy nie ma już wierszy do obsłużenia.", "Makro zakończone", "Iconi"
                    return
                }
                if (problem != "") {
                    MsgBox problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!"
                    return
                }
            case "WEBLIST":
                ; WEBLIST|kod dostawcy (może być pusty)|numer dokumentu: bon, który WEBROWS ma obsłużyć; reszta linii to numer, bo może mieć |
                if (parts.Length < 3) {
                    MsgBox "Linia WEBLIST wymaga kodu dostawcy i numeru.`n`n" line, "Błąd makra", "Icon!"
                    return
                }
                parts.RemoveAt(1)
                code := parts.RemoveAt(1)
                number := ""
                for piece in parts
                    number .= (A_Index > 1 ? "|" : "") piece
                wanted[ListKey(number, code)] := {number: Trim(number), code: Trim(code)}
            case "WEBROWS":
                ; WEBROWS|okno|siatka|kolumna od 0|przycisk|następna strona|najwyżej ile wierszy|okno potwierdzenia|oczekiwany tekst|przycisk potwierdzenia:
                ; wiersz z listy WEBLIST i pustą kolumną dostaje kliknięcie przycisku; trzy ostatnie pola są opcjonalne
                if (parts.Length < 7) {
                    MsgBox "Linia WEBROWS wymaga okna, siatki, kolumny, przycisku, następnej strony i limitu.", "Błąd makra", "Icon!"
                    return
                }
                if (wanted.Count = 0) {
                    MsgBox "Makro nie ma listy bonów (linie WEBLIST), więc niczego nie kliknie.", "Błąd makra", "Icon!"
                    return
                }
                confirm := parts.Length >= 10 ? {title: Trim(parts[8]), expected: Trim(parts[9]), button: Trim(parts[10])} : ""
                summary := WebEachRow(Trim(parts[2]), Trim(parts[3]), Integer(Trim(parts[4])), Trim(parts[5]), Trim(parts[6]), Integer(Trim(parts[7])), wanted, confirm, results)
                report .= summary "`n"
                ; Kolejne etapy tylko, gdy każdy bon z listy ma numer: reszta makra potrzebuje wszystkich par.
                if (results.Count < wanted.Count) {
                    MsgBox report, "Makro zakończone", "Iconi" AlwaysOnTop
                    return
                }
            case "ASK":
                ; ASK|pytanie: Tak idzie dalej, Nie kończy makro bez błędu, np. pracownik sprawdza dokument przed zamknięciem
                if (parts.Length < 2) {
                    MsgBox "Linia ASK wymaga pytania.`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                if (MsgBox(report Trim(parts[2]), "Intermanage Makra", "YesNo Icon?" AlwaysOnTop) != "Yes") {
                    TrayTip "Makro zakończone bez tego kroku.", "Intermanage Makra", 1
                    return
                }
            case "WINOPEN":
                ; WINOPEN|okno: program już uruchomiony wraca z paska zadań na wierzch; bez niego makro staje
                problem := parts.Length < 2 ? "Linia WINOPEN wymaga okna." : WinOpen(Trim(parts[2]))
                if (problem != "") {
                    MsgBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
            case "WINCLOSEALL":
                ; WINCLOSEALL|okno|limit w ms: zamyka wszystkie takie okna. Po WEBCLOSE czeka najpierw na ostatni PDF,
                ; a gdy żadna dostawa się nie zamknęła (np. odpowiedź Nie), zostawia okna. Bez WEBCLOSE czeka tylko z podanym limitem.
                if (parts.Length >= 2 && (!closingRan || closedCount > 0)) {
                    text := WinCloseAll(Trim(parts[2]), parts.Length >= 3 ? Integer(Trim(parts[3])) : closingRan ? 20000 : 0)
                    if (text != "")
                        report .= "`n" text "`n"
                }
            case "WINMIN":
                ; WINMIN|okno: chowa program na pasek zadań; brak okna nie jest błędem, bo praca jest już skończona
                if (parts.Length >= 2) {
                    for hwnd in WinGetList(Trim(parts[2]))
                        try WinMinimize hwnd
                }
            case "WEBENSURE":
                ; WEBENSURE|okno|element|przycisk: klika przycisk tylko, gdy elementu nie widać, np. zakładka menu pod innym ekranem
                problem := parts.Length < 4 ? "Linia WEBENSURE wymaga okna, elementu i przycisku." : WebEnsure(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]))
                if (problem != "") {
                    MsgBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
            case "WEBOPEN":
                ; WEBOPEN|okno|rozwinięcie menu|pozycja menu|element otwartego ekranu: rozwija tylko, gdy pozycji nie widać, bo drugie
                ; kliknięcie zwinęłoby menu. Z elementem klika pozycję ponownie (najwyżej 4 razy), dopóki ekran się nie otworzy.
                problem := parts.Length < 4 ? "Linia WEBOPEN wymaga okna, rozwinięcia i pozycji."
                    : WebOpen(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), parts.Length >= 5 ? Trim(parts[5]) : "")
                if (problem != "") {
                    MsgBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!"
                    return
                }
            case "WEBHEADERS":
                ; WEBHEADERS|okno|siatka|kolumna numeru|kolumna identyfikacji|przycisk nagłówka|pole|zapis|okno pytania|tekst pytania|odpowiedź|pierwsza strona|następna strona
                if (parts.Length < 11 || results.Count = 0) {
                    MsgBox report "Linia WEBHEADERS wymaga wszystkich pól i wyników wcześniejszego WEBROWS.", "Błąd makra", "Icon!"
                    return
                }
                step := {title: Trim(parts[2]), grid: Trim(parts[3]), numberColumn: Integer(Trim(parts[4])), fieldColumn: Integer(Trim(parts[5])),
                    open: Trim(parts[6]), field: Trim(parts[7]), save: Trim(parts[8]), dialog: Trim(parts[9]), dialogText: Trim(parts[10]), dialogButton: Trim(parts[11]),
                    firstPage: parts.Length >= 13 ? Trim(parts[12]) : "", nextPage: parts.Length >= 13 ? Trim(parts[13]) : ""}
                summary := WebHeaders(step, wanted, results)
                report .= "`n" summary.text "`n"
                if !summary.finished {
                    MsgBox report, "Makro zatrzymane", "Icon!" AlwaysOnTop
                    return
                }
            case "WEBPICK":
                ; WEBPICK|okno|siatka|kolumna od 0|tekst|limit w ms: zaznacza wiersz z tym tekstem w kolumnie
                problem := parts.Length < 5 ? "Linia WEBPICK wymaga okna, siatki, kolumny i tekstu."
                    : WebPickRow(Trim(parts[2]), Trim(parts[3]), Integer(Trim(parts[4])), Trim(parts[5]), parts.Length >= 6 ? Integer(Trim(parts[6])) : 10000)
                if (problem != "") {
                    MsgBox problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!"
                    return
                }
            case "WEBPICKRE":
                ; WEBPICKRE|okno|siatka|wyrażenie regularne|limit w ms: zaznacza pierwszy wiersz, którego tekst pasuje (np. nazwa pliku z numerem bonu)
                problem := parts.Length < 4 ? "Linia WEBPICKRE wymaga okna, siatki i wyrażenia."
                    : WebPickRowMatching(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), parts.Length >= 5 ? Integer(Trim(parts[5])) : 10000)
                if (problem != "") {
                    MsgBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
            case "WEBCOLLECT":
                ; WEBCOLLECT|okno|siatka|tekst wiersza|kolumna kodu od 0 albo auto|następna strona|lista stron|1 = stop na stronie bez takiego wiersza:
                ; ze stron listy zbiera kody z wierszy z tym tekstem (np. „ITM nie istnieje”), bez powtórzeń i zer wiodących, i kładzie je do Schowka.
                ; Mercalys stawia wiersze z błędami na początku listy, więc strona bez błędu kończy przegląd.
                if (parts.Length < 6) {
                    MsgBox report "Linia WEBCOLLECT wymaga okna, siatki, tekstu, kolumny i następnej strony.", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                summary := WebCollect(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), Trim(parts[5]), Trim(parts[6]), parts.Length >= 7 ? Trim(parts[7]) : "",
                    parts.Length >= 8 && Trim(parts[8]) = "1")
                if (summary.problem != "") {
                    MsgBox report summary.problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                report .= summary.text "`n"
            case "WEBSET":
                ; WEBSET|okno|id pola albo listy|wartość|limit w ms: wpisuje wartość, a lista wybiera pozycję i wywołuje swoją zmianę
                problem := parts.Length < 4 ? "Linia WEBSET wymaga okna, pola i wartości."
                    : WebSetValue(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), parts.Length >= 5 ? Integer(Trim(parts[5])) : 10000)
                if (problem != "") {
                    MsgBox problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!"
                    return
                }
            case "WEBWAIT":
                ; WEBWAIT|okno|element|tekst na stronie (opcjonalny, kilka możliwych po średniku)|limit w ms: czeka, aż element będzie widoczny, a strona ma ten tekst
                problem := parts.Length < 3 ? "Linia WEBWAIT wymaga okna i elementu."
                    : WebWait(Trim(parts[2]), Trim(parts[3]), parts.Length >= 4 ? Trim(parts[4]) : "", parts.Length >= 5 ? Integer(Trim(parts[5])) : 15000)
                if (problem != "") {
                    MsgBox problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!"
                    return
                }
            case "WEBDIALOG":
                ; WEBDIALOG|okno pytania|tekst (kilka po średniku)|przycisk|limit w ms: pytanie, które pojawia się tylko czasem
                ; (np. sprzedaż ze stratą przy przecenie). Z tym tekstem klika przycisk, bez okna albo z innym pytaniem idzie dalej.
                problem := parts.Length < 4 ? "Linia WEBDIALOG wymaga okna, tekstu i przycisku."
                    : OptionalDialog(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), parts.Length >= 5 ? Integer(Trim(parts[5])) : 3000)
                if (problem != "") {
                    MsgBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
            case "WEBVALUE":
                ; WEBVALUE|okno|pole|wyrażenie regularne|limit w ms: czeka, aż pole ma pasującą wartość (pole wyboru: tak / nie), bo WEBWAIT nie widzi wartości pól
                problem := parts.Length < 4 ? "Linia WEBVALUE wymaga okna, pola i wyrażenia."
                    : WebValue(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), parts.Length >= 5 ? Integer(Trim(parts[5])) : 5000)
                if (problem != "") {
                    MsgBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!"
                    return
                }
            case "WEBREAD":
                ; WEBREAD|okno|pole|nazwa|wyrażenie regularne|limit w ms: czeka, aż pole ma pasującą wartość, i zapamiętuje ją;
                ; kolejne linie dostają ją w miejscu %nazwa%. Np. numer klawisza wagi przepisywany do kodu PLU i komentarza.
                if (parts.Length < 5) {
                    MsgBox report "Linia WEBREAD wymaga okna, pola, nazwy i wyrażenia.`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                value := ""
                problem := WebValue(Trim(parts[2]), Trim(parts[3]), Trim(parts[5]), parts.Length >= 6 ? Integer(Trim(parts[6])) : 10000, &value)
                if (problem != "") {
                    MsgBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                vars[Trim(parts[4])] := value
            case "WEBDBLROW":
                ; WEBDBLROW|okno|siatka albo *|tekst|limit w ms: klika dwa razy pierwszy wiersz tabeli z tym tekstem
                ; (liczba stoi osobno, więc 2405610000000 nie trafi w dłuższy kod), np. EAN na liście kodów artykułu.
                problem := parts.Length < 4 ? "Linia WEBDBLROW wymaga okna, siatki i tekstu."
                    : WebDoubleClickRow(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), parts.Length >= 5 ? Integer(Trim(parts[5])) : 10000)
                if (problem != "") {
                    MsgBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
            case "WEBKEY":
                ; WEBKEY|okno|pole|klawisze|limit w ms: ustawia fokus w polu i naciska prawdziwe klawisze (np. {Enter}, {Tab})
                problem := parts.Length < 4 ? "Linia WEBKEY wymaga okna, pola i klawiszy."
                    : WebKey(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), parts.Length >= 5 ? Integer(Trim(parts[5])) : 10000)
                if (problem != "") {
                    MsgBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!"
                    return
                }
            case "WEBCLOSE":
                ; WEBCLOSE|okno|siatka|kolumna numeru|kolumna identyfikacji|przycisk zamknięcia|okno pytania|tekst pytania|odpowiedź|pole wydruku|zatwierdzenie|pierwsza strona|następna strona
                if (parts.Length < 11 || results.Count = 0) {
                    MsgBox report "Linia WEBCLOSE wymaga wszystkich pól i wyników wcześniejszego WEBROWS.", "Błąd makra", "Icon!"
                    return
                }
                step := {title: Trim(parts[2]), grid: Trim(parts[3]), numberColumn: Integer(Trim(parts[4])), fieldColumn: Integer(Trim(parts[5])),
                    close: Trim(parts[6]), dialog: Trim(parts[7]), dialogText: Trim(parts[8]), dialogButton: Trim(parts[9]), tick: Trim(parts[10]), save: Trim(parts[11]),
                    firstPage: parts.Length >= 13 ? Trim(parts[12]) : "", nextPage: parts.Length >= 13 ? Trim(parts[13]) : ""}
                summary := WebCloseRows(step, wanted, results)
                closingRan := true
                closedCount += summary.closed
                report .= "`n" summary.text "`n"
                if !summary.finished {
                    MsgBox report, "Makro zatrzymane", "Icon!" AlwaysOnTop
                    return
                }
            case "WEBMARK":
                ; WEBMARK|okno|element strony: oznacza bieżącą stronę, żeby WEBIFROW brał pod uwagę dopiero stronę po przeładowaniu
                if (parts.Length < 3 || !MarkPage(Trim(parts[2]), Trim(parts[3]))) {
                    MsgBox report "Nie ma strony z elementem do oznaczenia.`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
            case "LABEL":
                ; LABEL|nazwa: cel przeskoku z WEBIFROW i WEBIFVALUE, sama nic nie robi
            case "WEBIFROW":
                ; WEBIFROW|okno|element strony listy|siatka|przycisk w wierszu|numer wiersza od 1|tekst|etykieta|limit w ms:
                ; czeka, aż lista pokaże tylko wiersze z tekstem (np. kodem MEA wpisanym w filtr). Gdy widoczny wiersz o tym numerze
                ; jest, idzie dalej, a id jego przycisku jest w kolejnych liniach jako %ROW%; gdy lista ma mniej wierszy albo
                ; żadnego, przeskakuje do LABEL|etykieta.
                if (parts.Length < 8) {
                    MsgBox report "Linia WEBIFROW wymaga okna, strony, siatki, przycisku, numeru, tekstu i etykiety.", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                rowButton := ""
                seen := ""
                problem := WebIfRow(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), Trim(parts[5]), Integer(Trim(parts[6])), Trim(parts[7]),
                    parts.Length >= 9 ? Integer(Trim(parts[9])) : 30000, &rowButton, &seen)
                if (problem = "")
                    vars["ROW"] := rowButton ; kolejne linie klikają przycisk tego wiersza jako %ROW%
                if (problem = "SKIP") {
                    ; Podsumowanie na końcu: ile plików obsłużono i co było widać na liście przy przeskoku.
                    report .= Trim(parts[7]) ": obsłużone pliki: " (Integer(Trim(parts[6])) - 1) " (" seen ")`n"
                    target := LabelLine(lines, Trim(parts[8]), index)
                    if (target = 0) {
                        MsgBox report "Brak linii LABEL|" Trim(parts[8]) " po linii " index ".", "Błąd makra", "Icon!" AlwaysOnTop
                        return
                    }
                    index := target
                    continue
                }
                if (problem != "") {
                    MsgBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
            case "WEBIFVALUE":
                ; WEBIFVALUE|okno|pole|wyrażenie regularne|etykieta|limit w ms: czeka do limitu, aż pole ma pasującą wartość.
                ; Pasuje: idzie dalej; nie pasuje (albo pola nie ma): przeskakuje do LABEL|etykieta. Np. wypełniony panel
                ; istniejącej operacji promocji, którą trzeba najpierw usunąć.
                if (parts.Length < 5) {
                    MsgBox report "Linia WEBIFVALUE wymaga okna, pola, wyrażenia i etykiety.", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                if (WebValue(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), parts.Length >= 6 ? Integer(Trim(parts[6])) : 3000) != "") {
                    target := LabelLine(lines, Trim(parts[5]), index)
                    if (target = 0) {
                        MsgBox report "Brak linii LABEL|" Trim(parts[5]) " po linii " index ".", "Błąd makra", "Icon!" AlwaysOnTop
                        return
                    }
                    index := target
                    continue
                }
            case "WEBFREECODE":
                ; WEBFREECODE|okno|przycisk świeżej karty|pole kodu|pole sprawdzane|pierwszy kod|ile prób|czas na wczytanie karty w ms:
                ; dla kolejnych kodów od pierwszego otwiera świeżą kartę (pole kodu = 0), wpisuje kod z Tab i czeka, czy program
                ; wczyta istniejącą kartę (pole sprawdzane niepuste). Pierwszy kod bez karty zostaje w polu i makro idzie dalej;
                ; wszystkie zajęte zatrzymują makro. Np. nowy dostawca w Mercalys, gdy lista w aplikacji jest nieaktualna.
                if (parts.Length < 7) {
                    MsgBox report "Linia WEBFREECODE wymaga okna, przycisku, pola kodu, pola sprawdzanego, kodu i liczby prób.", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                found := ""
                problem := WebFreeCode(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), Trim(parts[5]), Integer(Trim(parts[6])),
                    Integer(Trim(parts[7])), parts.Length >= 8 ? Integer(Trim(parts[8])) : 3000, &found)
                if (problem != "") {
                    MsgBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                ; Bez wpisu do podsumowania: na końcu zwykłe „Zakończono”, jak przy innych makrach.
            case "WEBSHOW":
                ; WEBSHOW|fragment tytułu okna|elementy po przecinku: tylko pokazuje ich HTML (do Schowka), niczego nie klika
                if (parts.Length >= 3)
                    MsgBox WebShow(Trim(parts[2]), Trim(parts[3])), "HTML elementów (skopiowano do Schowka)"
                return
            default:
                MsgBox "Nieznane polecenie: " cmd "`n`nMakro zatrzymane na linii " index ".`nByć może ten odtwarzacz jest za stary.", "Błąd makra", "Icon!"
                return
        }
        Sleep 50
    }
    
    if (report != "")
        MsgBox report, "Makro zakończone", "Iconi" AlwaysOnTop
    else
        TrayTip "Zakończono", "Makro ze schowka zostało wykonane.", 1
}


; --- Strony w Internet Explorerze: czekanie na okno i klikanie przycisków przez HTML, bez współrzędnych ---

; Zwraca "", gdy kliknięto, a inaczej opis problemu. Okno i przycisk mogą pojawić się w trakcie czekania.
WebClick(title, button, timeoutMs, onlyTick := false) {
    deadline := A_TickCount + timeoutMs
    windowFound := false
    Loop {
        for hwnd in WinGetList(title) {
            windowFound := true
            for item in FindElements(HtmlDocuments(hwnd), button) {
                element := item.element
                if (!WebProp(element, "disabled") && IsShown(element)) {
                    if (onlyTick && WebProp(element, "checked"))
                        return ""
                    try WinActivate hwnd
                    try {
                        ClickWithoutWaiting(item.doc, element)
                        return ""
                    }
                }
            }
        }
        if (A_TickCount >= deadline)
            break
        Sleep 100
    }
    return windowFound
        ? "Nie ma przycisku `"" button "`" w oknie `"" title "`"."
        : "Nie pojawiło się okno `"" title "`"."
}

; Zaznacza pierwszy wiersz siatki z pustą kolumną. "" = zaznaczony i potwierdzony, "BRAK" = lista jest, ale nie ma takiego wiersza.
WebSelectRow(title, grid, column, timeoutMs, &key := "", handled := Map(), accepts := "") {
    deadline := A_TickCount + timeoutMs
    Loop {
        gridFound := false
        for hwnd in WinGetList(title) {
            for doc in HtmlDocuments(hwnd) {
                rows := GridRows(doc, grid)
                if (rows.Length > 0)
                    gridFound := true
                for item in rows {
                    if (CellText(item.row, column) = "" && (!IsObject(accepts) || accepts(item.row))) {
                        key := RowKey(item.row)
                        if handled.Has(key)
                            return "Wiersz " key " nadal jest pusty po kliknięciu."
                        try WinActivate hwnd
                        try {
                            ClickWithoutWaiting(doc, item.row)
                            return WaitForSelection(doc, grid, item, deadline)
                        }
                    }
                }
            }
        }
        if gridFound
            return "BRAK"
        if (A_TickCount >= deadline)
            return "Nie ma listy `"" grid "`" w oknie `"" title "`"."
        Sleep 100
    }
}

; Ostatni PDF przychodzi po chwili: zamykamy dopiero, gdy okna i ich tytuły nie zmieniają się przez 4 sekundy.
WinCloseAll(title, timeoutMs) {
    deadline := A_TickCount + timeoutMs
    last := ""
    changedAt := A_TickCount
    Loop {
        signature := ""
        for hwnd in WinGetList(title)
            try signature .= hwnd ":" WinGetTitle(hwnd) "`n"
        if (signature != last) {
            last := signature
            changedAt := A_TickCount
        }
        if ((signature != "" && A_TickCount - changedAt >= 4000) || A_TickCount >= deadline)
            break
        Sleep 200
    }
    found := WinGetList(title)
    for hwnd in found
        try WinClose hwnd
    left := 0
    for hwnd in found {
        if !WinWaitClose("ahk_id " hwnd, , 3)
            left += 1
    }
    return left = 0 ? "" : "Acrobat nie zamknął się sam, zamknij PDF-y ręcznie."
}

WinOpen(title) {
    hwnd := WinExist(title)
    if !hwnd
        return "Nie znaleziono okna `"" title "`". Uruchom program i spróbuj ponownie."
    if (WinGetMinMax(hwnd) = -1)
        WinRestore hwnd
    WinActivate hwnd
    return WinWaitActive("ahk_id " hwnd, , 5) ? "" : "Okno `"" title "`" nie stało się aktywne."
}

WebEnsure(title, element, button) {
    if (WebWait(title, element, "", 500) = "")
        return ""
    problem := WebClick(title, button, 10000)
    return problem != "" ? problem : WebWait(title, element, "", 30000)
}

WebOpen(title, expander, target, expected := "") {
    if (WebWait(title, target, "", 500) != "") {
        problem := WebClick(title, expander, 10000)
        if (problem != "")
            return problem
    }
    if (expected = "")
        return WebClick(title, target, 10000)
    ; Mercalys po cichu odrzuca klik w menu, gdy strona obok jeszcze pracuje (np. wynik integracji wielu plików),
    ; więc bez oczekiwanego ekranu klikamy ponownie.
    Loop 4 {
        problem := WebClick(title, target, 10000)
        if (problem != "")
            return problem
        if (WebWait(title, expected, "", 5000) = "")
            return ""
    }
    return "Po kliknięciu `"" target "`" nie pojawił się element `"" expected "`"."
}

; Wpisuje Nr BL do nagłówka każdej dostawy z listy. Wiersz z tym samym numerem jest pomijany, więc makro można powtórzyć,
; a innej identyfikacji nie nadpisuje. Wpis idzie dopiero, gdy nagłówek pokazuje właściwy numer, a po zapisie lista musi go mieć.
WebHeaders(step, wanted, results) {
    written := ""
    existing := ""
    others := ""
    Done(ending, finished) => {finished: finished, text: "Nagłówki wpisane: " (written = "" ? "brak`n" : "`n" written)
        . (existing != "" ? "Już były: `n" existing : "") . (others != "" ? "INNA identyfikacja, nie zmieniono:`n" others : "") . ending}
    for key, item in wanted {
        order := results[key]
        problem := WebWait(step.title, step.open, "", 30000)
        if (problem = "")
            problem := ShowOrderRow(step, order)
        if (problem != "")
            return Done("ZATRZYMANE przy " order ": " problem, false)
        current := GridCell(step, order, step.fieldColumn)
        if (current = "?")
            return Done("ZATRZYMANE: nie ma dostawy " order " na liście.", false)
        if (ListKey(current, "") = ListKey(item.number, "")) {
            existing .= order " " item.number "`n"
            continue
        }
        if (current != "") {
            others .= order ": jest " current ", miało być " item.number "`n"
            continue
        }
        problem := WriteHeader(step, order, item.number)
        if (problem = "")
            problem := ShowOrderRow(step, order)
        if (problem != "")
            return Done("ZATRZYMANE przy " order ": " problem, false)
        current := GridCell(step, order, step.fieldColumn)
        if (ListKey(current, "") != ListKey(item.number, ""))
            return Done("ZATRZYMANE przy " order ": po zapisie lista pokazuje `"" current "`" zamiast " item.number ".", false)
        written .= order " " item.number "`n"
    }
    return Done(others = "" ? "Wszystkie nagłówki są wpisane." : "Sprawdź dostawy z inną identyfikacją.", others = "")
}

WriteHeader(step, order, number) {
    problem := WebPickRow(step.title, step.grid, step.numberColumn, order, 10000)
    if (problem = "")
        problem := WebClick(step.title, step.open, 10000)
    if (problem = "")
        problem := WebWait(step.title, step.field, order, 30000)
    if (problem = "")
        problem := WebSetValue(step.title, step.field, number, 10000)
    if (problem = "")
        problem := WebClick(step.title, step.save, 10000)
    if (problem = "")
        problem := WebWait(step.dialog, step.dialogButton, step.dialogText, 30000)
    if (problem = "")
        problem := WebClick(step.dialog, step.dialogButton, 10000)
    if (problem = "")
        problem := WebWait(step.title, step.open, "", 30000)
    return problem
}

; Zamyka dostawy z tego przebiegu z poprawnym nagłówkiem, po jednym potwierdzeniu, bo zamknięcia nie da się cofnąć.
; Dostawa musi zniknąć z listy: inaczej pętla staje, a kolejne zostają otwarte.
WebCloseRows(step, wanted, results) {
    names := ""
    for key, item in wanted
        names .= results[key] " " item.number "`n"
    if (MsgBox("Zamknąć dostawy: " wanted.Count "?`nTego nie da się cofnąć.`n`n" names, "Zamknięcie dostaw", "YesNo Icon? Default2" AlwaysOnTop) != "Yes")
        return {finished: true, closed: 0, text: "Zamknięcia pominięte na życzenie."}
    closed := ""
    skipped := ""
    count := 0
    Done(ending, finished) => {finished: finished, closed: count, text: "Zamknięte: " (closed = "" ? "brak`n" : "`n" closed)
        . (skipped != "" ? "POMINIĘTE:`n" skipped : "") . ending}
    for key, item in wanted {
        order := results[key]
        problem := WebWait(step.title, step.close, "", 30000)
        if (problem = "")
            problem := ShowOrderRow(step, order)
        if (problem != "")
            return Done("ZATRZYMANE przy " order ": " problem, false)
        current := GridCell(step, order, step.fieldColumn)
        if (current = "?") {
            skipped .= order ": nie ma na liście`n"
            continue
        }
        if (ListKey(current, "") != ListKey(item.number, "")) {
            skipped .= order ": w nagłówku jest `"" current "`" zamiast " item.number "`n"
            continue
        }
        problem := CloseRow(step, order)
        if (problem != "")
            return Done("ZATRZYMANE przy " order ": " problem, false)
        closed .= order " " item.number "`n"
        count += 1
    }
    return Done(skipped = "" ? "Wszystkie dostawy z listy są zamknięte." : "Sprawdź pominięte dostawy.", skipped = "")
}

CloseRow(step, order) {
    problem := WebPickRow(step.title, step.grid, step.numberColumn, order, 10000)
    marked := problem = "" && MarkPage(step.title, step.tick)
    if (problem = "")
        problem := WebClick(step.title, step.close, 10000)
    if (problem = "")
        problem := WebWait(step.dialog, step.dialogButton, step.dialogText, 30000)
    if (problem = "")
        problem := WebClick(step.dialog, step.dialogButton, 10000)
    if (problem = "")
        problem := TickAfterReload(step.title, step.tick, 30000, marked)
    if (problem = "")
        problem := WebClick(step.title, step.save, 10000)
    if (problem = "")
        problem := WaitForRowGone(step, order, 60000)
    return problem
}

; Pole i przycisk panelu są na stronie także przed jego otwarciem, a przeładowanie kasuje zaznaczenie.
; Znacznik na stronie mówi, kiedy przeładowanie już było: wtedy wystarczy krótkie sprawdzenie zaznaczenia.
; Bez wykrytego przeładowania panel uznajemy za gotowy dopiero po 4 sekundach; za krótko = klik przed otwarciem panelu.
TickAfterReload(title, box, timeoutMs, marked) {
    deadline := A_TickCount + timeoutMs
    reloaded := marked && WaitForReload(title, box, 5000)
    checks := reloaded ? 1 : 4
    pause := reloaded ? 200 : 1000
    stable := 0
    Loop {
        problem := WebClick(title, box, 5000, true)
        if (problem != "")
            return problem
        Sleep pause
        stable := IsTicked(title, box) ? stable + 1 : 0
        if (stable >= checks)
            return ""
        if (A_TickCount >= deadline)
            return "Pole `"" box "`" nie zostaje zaznaczone."
    }
}

MarkPage(title, box) {
    marked := false
    for doc in PagesWith(title, box) {
        try {
            doc.body.setAttribute("macroMark", "1")
            marked := true
        }
    }
    return marked
}

WaitForReload(title, box, timeoutMs) {
    deadline := A_TickCount + timeoutMs
    Loop {
        for doc in PagesWith(title, box) {
            try {
                if (WebProp(doc.body, "macroMark") = "")
                    return true
            }
        }
        if (A_TickCount >= deadline)
            return false
        Sleep 50
    }
}

; Wczytane strony z tym elementem, także gdy jest jeszcze niewidoczny.
PagesWith(title, name) {
    pages := []
    for hwnd in WinGetList(title) {
        for item in FindElements(HtmlDocuments(hwnd), name) {
            if PageLoaded(item.doc)
                pages.Push(item.doc)
        }
    }
    return pages
}

; Strona w trakcie przeładowania odmawia dostępu: to znaczy tylko, że jeszcze jej nie ma.
PageLoaded(doc) {
    try return doc.readyState = "complete"
    return false
}

; Najpierw po id: przegląd wszystkich elementów to osobne zapytanie o każdy i na Mercalys zajmuje sekundy.
; Pełny przegląd zostaje dla przycisków szukanych po napisie albo wartości.
FindElements(docs, name) {
    found := []
    for doc in docs {
        try {
            element := doc.getElementById(name)
            if (WebProp(element, "tagName") != "")
                found.Push({doc: doc, element: element})
        }
    }
    if (found.Length > 0)
        return found
    for doc in docs {
        for element in AllElements(doc) {
            if IsButton(element, name)
                found.Push({doc: doc, element: element})
        }
    }
    return found
}

IsTicked(title, box) {
    for hwnd in WinGetList(title) {
        for item in FindElements(HtmlDocuments(hwnd), box) {
            if WebProp(item.element, "checked")
                return true
        }
    }
    return false
}

WaitForRowGone(step, order, timeoutMs) {
    deadline := A_TickCount + timeoutMs
    Loop {
        if (WebWait(step.title, step.close, "", 500) = "" && GridCell(step, order, step.numberColumn) = "?")
            return ""
        if (A_TickCount >= deadline)
            return "Dostawa nie zniknęła z listy po zatwierdzeniu."
        Sleep 300
    }
}

; Dostawa bywa na innej stronie listy, a lista zostaje na stronie, na której była. Szukamy od pierwszej, bo dostawa może
; być przed bieżącą. "" też wtedy, gdy nie ma jej na żadnej stronie: to rozstrzyga GridCell w miejscu wywołania.
ShowOrderRow(step, order) {
    if (step.firstPage = "" || GridCell(step, order, step.numberColumn) != "?")
        return ""
    if PagerAvailable(step.title, step.firstPage) {
        problem := TurnPage(step, step.firstPage)
        if (problem != "")
            return problem
    }
    Loop {
        if (GridCell(step, order, step.numberColumn) != "?" || !PagerAvailable(step.title, step.nextPage))
            return ""
        problem := TurnPage(step, step.nextPage)
        if (problem != "")
            return problem
    }
}

; Na pierwszej stronie nie ma przycisku pierwszej, a na ostatniej następnej.
PagerAvailable(title, button) {
    for hwnd in WinGetList(title) {
        for item in FindElements(HtmlDocuments(hwnd), button) {
            if (!WebProp(item.element, "disabled") && IsShown(item.element))
                return true
        }
    }
    return false
}

TurnPage(step, button) {
    first := FirstRowKey(step.title, step.grid)
    problem := WebClick(step.title, button, 5000)
    if (problem != "")
        return problem
    return WaitForNewPage(step.title, step.grid, first, 15000) ? "" : "Lista nie przeszła na inną stronę."
}

; Wartość kolumny w wierszu z numerem; "?", gdy takiego wiersza nie ma.
GridCell(step, order, column) {
    for hwnd in WinGetList(step.title) {
        for doc in HtmlDocuments(hwnd) {
            for item in GridRows(doc, step.grid) {
                if (ListKey(CellText(item.row, step.numberColumn), "") = ListKey(order, ""))
                    return CellText(item.row, column)
            }
        }
    }
    return "?"
}

; Pola własne Mercalys reagują na klawisze, a nie na zmianę wartości: potrzebny jest fokus i prawdziwe naciśnięcie.
WebKey(title, field, keys, timeoutMs) {
    deadline := A_TickCount + timeoutMs
    found := false
    Loop {
        for hwnd in WinGetList(title) {
            for doc in HtmlDocuments(hwnd) {
                for element in FormElements(doc) {
                    if ((WebProp(element, "id") = field || WebProp(element, "name") = field) && IsShown(element)) {
                        found := true
                        try WinActivate hwnd
                        ; Klawisze idą do aktywnego okna: bez tego trafiłyby np. w otwarty PDF.
                        if !WinWaitActive("ahk_id " hwnd, , 2)
                            return "Okno `"" title "`" nie stało się aktywne, klawisze trafiłyby w inne okno."
                        ; Pola z własnym skryptem przy wejściu (np. NIP w Mercalys) potrafią rzucić błąd z focus(), choć
                        ; kursor w nich staje. Liczy się, czy pole jest aktywne; bez tego próbujemy kliknięcia.
                        focused := true
                        try element.focus()
                        catch
                            focused := false
                        Sleep 100
                        if (!focused && !HasFocus(doc, element)) {
                            try element.click()
                            Sleep 100
                        }
                        if (focused || HasFocus(doc, element)) {
                            Send keys
                            return ""
                        }
                    }
                }
            }
        }
        if (A_TickCount >= deadline)
            return found ? "Nie udało się ustawić kursora w polu `"" field "`" w oknie `"" title "`"."
                : "Nie ma pola `"" field "`" w oknie `"" title "`"."
        Sleep 100
    }
}

; Czy kursor stoi w polu (aktywny element strony to ono).
HasFocus(doc, element) {
    try {
        active := doc.activeElement
        id := WebProp(element, "id")
        return id != "" ? WebProp(active, "id") = id : WebProp(active, "uniqueID") = WebProp(element, "uniqueID")
    }
    return false
}

; Pierwszy wolny kod od `first`, najwyżej `tries` prób. Każda próba na świeżej karcie (przycisk `fresh`, pole kodu 0 albo
; puste), bo po zajętym kodzie w polu sprawdzanym zostałaby nazwa poprzedniej karty i wolny kod wyglądałby na zajęty.
WebFreeCode(title, fresh, codeField, checkField, first, tries, loadMs, &found) {
    Loop tries {
        code := first + A_Index - 1
        problem := WebClick(title, fresh, 10000)
        if (problem = "")
            problem := WebWait(title, codeField, "", 30000)
        if (problem = "")
            problem := WebValue(title, codeField, "^0?$", 30000)
        ; Znacznik na stronie: po kodzie Mercalys przeładowuje kartę, więc nie czekamy pełnego limitu, tylko na przeładowanie.
        MarkPage(title, codeField)
        if (problem = "")
            problem := WebKey(title, codeField, "^a" code "{Tab}", 10000)
        if (problem != "")
            return problem
        ; Po przeładowaniu karta (albo jej brak) już jest: krótkie sprawdzenie. Bez przeładowania cały limit.
        reloaded := WaitForReload(title, codeField, loadMs)
        if (WebValue(title, checkField, "\S", reloaded ? 400 : 0) != "") {
            found := code
            return ""
        }
    }
    return "Kody od " first " do " (first + tries - 1) " są zajęte. Wgraj aktualną listę i wygeneruj makro ponownie."
}

; Sprawdza wartość pola po wyrażeniu regularnym. Mercalys formatuje pola po wyjściu z nich, więc wzorzec dopuszcza oba zapisy.
WebValue(title, field, pattern, timeoutMs, &value := "") {
    deadline := A_TickCount + timeoutMs
    last := "(brak pola)"
    Loop {
        for hwnd in WinGetList(title) {
            for item in FindElements(HtmlDocuments(hwnd), field) {
                if !(PageLoaded(item.doc) && IsShown(item.element))
                    continue
                last := WebProp(item.element, "type") = "checkbox"
                    ? (WebProp(item.element, "checked") ? "tak" : "nie")
                    : Trim(WebProp(item.element, "value"))
                if RegExMatch(last, pattern) {
                    value := last
                    return ""
                }
            }
        }
        if (A_TickCount >= deadline)
            return "Pole `"" field "`" ma wartość `"" last "`", a miało pasować do `"" pattern "`"."
        Sleep 100
    }
}

; Czeka na element i tekst na tej samej stronie: po przeładowaniu stara strona ma jeszcze stare wiersze, a nagłówek
; złej dostawy wygląda tak samo jak właściwej. Liczba w tekście musi stać osobno: 10851 nie pasuje do 108510.
; Kilka tekstów po średniku: wystarczy jeden z nich, np. różne pytania tego samego okna.
WebWait(title, element, text, timeoutMs) {
    deadline := A_TickCount + timeoutMs
    pattern := "i)(?<!\d)(?:\Q" StrReplace(text, ";", "\E|\Q") "\E)(?!\d)"
    Loop {
        for hwnd in WinGetList(title) {
            for item in FindElements(HtmlDocuments(hwnd), element) {
                if !(PageLoaded(item.doc) && IsShown(item.element))
                    continue
                if (text = "")
                    return ""
                try {
                    if RegExMatch(item.doc.body.innerText, pattern)
                        return ""
                }
            }
        }
        if (A_TickCount >= deadline)
            return text = "" ? "Nie pojawił się element `"" element "`"."
                : "Nie pojawił się element `"" element "`" z tekstem `"" text "`"."
        Sleep 100
    }
}

; Numer linii LABEL|nazwa za linią start; 0, gdy jej nie ma.
LabelLine(lines, name, start) {
    Loop lines.Length - start {
        parts := StrSplit(Trim(lines[start + A_Index]), "|")
        if (parts.Length >= 2 && StrUpper(Trim(parts[1])) = "LABEL" && Trim(parts[2]) = name)
            return start + A_Index
    }
    return 0
}

; Tekst wiersza tabeli, w którym stoi element.
RowTextOf(element) {
    try {
        node := element
        Loop 10 {
            node := node.parentNode
            if (WebProp(node, "tagName") = "TR")
                return node.innerText
        }
    }
    return ""
}

; "" = widoczny wiersz o tym numerze jest (id jego przycisku w buttonId), "SKIP" = przefiltrowana lista ma mniej wierszy,
; inaczej opis problemu. Filtr listy cadencier działa na stronie, bez przeładowania (onpropertychange), chowa wiersze
; i przepuszcza też tekst wewnątrz dłuższej liczby, więc liczą się widoczne wiersze z tekstem jako osobną liczbą, a numer
; w id (ctl2, ctl3) nie mówi, który to widoczny wiersz. Lista jest gotowa, gdy pole strony (filtr) ma ten tekst,
; a widoczne wiersze nie zmieniają się przez 0,5 s.
WebIfRow(title, page, grid, button, number, text, timeoutMs, &buttonId := "", &seen := "") {
    deadline := A_TickCount + timeoutMs
    pattern := "i)(?<!\d)\Q" text "\E(?!\d)"
    lastState := ""
    stableSince := 0
    seen := "nie ma strony z polem `"" page "`"" ; opis do błędu: co makro widziało ostatnio
    Loop {
        state := ""
        found := ""
        for doc in PagesWith(title, page) {
            filter := ""
            try filter := doc.getElementById(page).value
            if !InStr(filter, text) {
                seen := "pole `"" page "`" ma wartość `"" filter "`""
                continue
            }
            visible := 0
            rows := []
            first := ""
            for item in GridRows(doc, grid) {
                if !IsShown(item.row)
                    continue
                visible += 1
                if (first = "")
                    first := RowText(item.row)
                if RegExMatch(RowText(item.row), pattern)
                    rows.Push(item.row)
            }
            seen := "widoczne wiersze: " visible ", z tekstem: " rows.Length (first != "" ? ", pierwszy: `"" first "`"" : "")
            if (rows.Length >= number) {
                try {
                    inputs := rows[number].getElementsByTagName("input")
                    Loop inputs.length {
                        id := WebProp(inputs.item(A_Index - 1), "id")
                        if (SubStr(id, -StrLen(button) - 1) = "_" button)
                            found := id
                    }
                }
                if (found = "")
                    return "Wiersz " number " listy nie ma przycisku `"" button "`"."
            }
            state := visible ":" rows.Length ":" found
            break
        }
        if (state != "" && state = lastState) {
            ; Przeskok do następnego kodu dopiero po 2 s takiej samej listy: chwilowo pusta lista w trakcie filtrowania
            ; albo przeładowania nie może zakończyć kodu.
            if (A_TickCount - stableSince >= (found = "" ? 2000 : 500)) {
                if (found = "")
                    return "SKIP"
                buttonId := found
                return ""
            }
        } else {
            lastState := state
            stableSince := A_TickCount
        }
        if (A_TickCount >= deadline)
            return "Lista nie pokazała wierszy z tekstem `"" text "`" (" seen ")."
        Sleep 100
    }
}

; Zaznacza wiersz z tekstem w kolumnie (bez spacji i wielkości liter) i sprawdza, że zaznaczenie się przyjęło.
WebPickRow(title, grid, column, text, timeoutMs) {
    deadline := A_TickCount + timeoutMs
    wanted := ListKey(text, "")
    Loop {
        for hwnd in WinGetList(title) {
            for doc in HtmlDocuments(hwnd) {
                for item in GridRows(doc, grid) {
                    if (ListKey(CellText(item.row, column), "") = wanted) {
                        try WinActivate hwnd
                        try {
                            ClickWithoutWaiting(doc, item.row)
                            return WaitForSelection(doc, grid, item, deadline)
                        }
                    }
                }
            }
        }
        if (A_TickCount >= deadline)
            return "Nie ma wiersza `"" text "`" na liście `"" grid "`"."
        Sleep 100
    }
}

; Podwójny klik w wiersz z tekstem. Siatka "*" = każdy najgłębszy wiersz tabeli strony, bo nazwy listy kodów EAN
; karty artykułu (ar-e005) nie znamy. Klik i dwuklik idą do pierwszej komórki i wypływają do wiersza, jak u człowieka.
WebDoubleClickRow(title, grid, text, timeoutMs) {
    deadline := A_TickCount + timeoutMs
    pattern := "(?<!\d)\Q" text "\E(?!\d)"
    Loop {
        for hwnd in WinGetList(title) {
            for doc in HtmlDocuments(hwnd) {
                if !PageLoaded(doc)
                    continue
                candidates := []
                if (grid != "*") {
                    for item in GridRows(doc, grid)
                        candidates.Push(item.row)
                } else {
                    try {
                        found := doc.getElementsByTagName("tr")
                        Loop found.length {
                            row := found.item(A_Index - 1)
                            if (row.getElementsByTagName("tr").length = 0)
                                candidates.Push(row)
                        }
                    }
                }
                for row in candidates {
                    if !(IsShown(row) && RegExMatch(RowText(row), pattern))
                        continue
                    try WinActivate hwnd
                    try {
                        ; Mercalys: klik zaznacza wiersz (setSelectedLigne), dwuklik to __doPostBack z atrybutu ondblclick wiersza.
                        ; Kod z atrybutu uruchamiamy wprost; bez niego zdarzenie idzie do pierwszej komórki i wypływa do wiersza.
                        dbl := ""
                        if RegExMatch(WebProp(row, "outerHTML"), "i)ondblclick=`"([^`"]+)`"", &match)
                            dbl := StrReplace(StrReplace(StrReplace(match[1], "&quot;", "`""), "&#39;", "'"), "&amp;", "&")
                        window := doc.parentWindow
                        window.__macroTarget := row
                        window.setTimeout("var t = window.__macroTarget; t.click(); "
                            . (dbl != "" ? dbl : "t.cells[0].fireEvent('ondblclick');"), 0, "JScript")
                        return ""
                    }
                }
            }
        }
        if (A_TickCount >= deadline)
            return "Nie ma wiersza z `"" text "`" w oknie `"" title "`"."
        Sleep 100
    }
}

; Tekst całego wiersza z pojedynczymi spacjami. Komórki rozdziela spacja: innerText wiersza w IE skleja je
; („… 52817” + „19/11/2026” = „5281719/11/2026”), a liczba musi stać osobno.
RowText(row) {
    text := ""
    try {
        cells := row.cells
        Loop cells.length
            text .= " " cells.item(A_Index - 1).innerText
    }
    if (text = "")
        text := WebProp(row, "innerText")
    return Trim(RegExReplace(StrReplace(text, Chr(160), " "), "\s+", " "))
}

; Zaznacza pierwszy wiersz, którego tekst pasuje do wyrażenia. Lista bez takiego wiersza przez 2 s to błąd, bez czekania do limitu.
WebPickRowMatching(title, grid, pattern, timeoutMs) {
    deadline := A_TickCount + timeoutMs
    gridSince := 0
    Loop {
        gridFound := false
        for hwnd in WinGetList(title) {
            for doc in HtmlDocuments(hwnd) {
                rows := GridRows(doc, grid)
                if (rows.Length > 0)
                    gridFound := true
                for item in rows {
                    if RegExMatch(RowText(item.row), pattern) {
                        try WinActivate hwnd
                        try {
                            ClickWithoutWaiting(doc, item.row)
                            return WaitForSelection(doc, item.grid, item, deadline)
                        }
                    }
                }
            }
        }
        if gridFound {
            if (gridSince = 0)
                gridSince := A_TickCount
            else if (A_TickCount - gridSince >= 2000)
                return "Na liście nie ma wiersza pasującego do `"" pattern "`"."
        }
        if (A_TickCount >= deadline)
            return gridFound ? "Na liście nie ma wiersza pasującego do `"" pattern "`"." : "Nie ma listy `"" grid "`" w oknie `"" title "`"."
        Sleep 100
    }
}

; Kod z wiersza: z podanej kolumny albo (auto) z pierwszej komórki z samym kodem do 8 cyfr, także dopełnionym zerami do 13.
; Bez zer wiodących; "" = wiersz nie ma kodu.
RowCode(row, column) {
    if IsInteger(column) {
        text := CellText(row, Integer(column))
        return RegExMatch(text, "^\d+$") ? LTrim(text, "0") : ""
    }
    try {
        cells := row.cells
        Loop cells.length {
            text := Trim(StrReplace(cells.item(A_Index - 1).innerText, Chr(160), " "))
            if RegExMatch(text, "^0*\d{5,8}$")
                return LTrim(text, "0")
        }
    }
    return ""
}

; Wiersze siatki z pierwszej strony okna, która ją ma; czeka, aż strona się wczyta.
VisibleGridRows(title, grid, timeoutMs) {
    deadline := A_TickCount + timeoutMs
    Loop {
        for hwnd in WinGetList(title) {
            for doc in HtmlDocuments(hwnd) {
                rows := GridRows(doc, grid)
                if (rows.Length > 0)
                    return rows
            }
        }
        if (A_TickCount >= deadline)
            return []
        Sleep 200
    }
}

; Numer bieżącej strony i ostatniej z listy stron (pgrDetail_ddlGoTo); puste, gdy listy stron nie ma.
PagerState(title, field) {
    if (field = "")
        return {current: "", last: ""}
    for hwnd in WinGetList(title) {
        for item in FindElements(HtmlDocuments(hwnd), field) {
            try {
                options := item.element.options
                return {current: WebProp(item.element, "value"), last: WebProp(options.item(options.length - 1), "value")}
            }
        }
    }
    return {current: "", last: ""}
}

PageSignature(title, grid, field) {
    rows := VisibleGridRows(title, grid, 0)
    return PagerState(title, field).current "|" (rows.Length > 0 ? RowText(rows[1].row) : "")
}

WebCollect(title, grid, text, column, nextPage, pageField, stopOnEmptyPage := false) {
    codes := []
    seen := Map()
    pages := 0
    matched := 0
    withoutCode := 0
    wanted := RegExReplace(text, "\s+", " ")
    Loop {
        rows := VisibleGridRows(title, grid, 30000)
        if (rows.Length = 0)
            return {problem: "Nie ma listy `"" grid "`" w oknie `"" title "`" (strona " (pages + 1) ").", text: ""}
        pages += 1
        pageMatched := 0
        for item in rows {
            if !InStr(RowText(item.row), wanted)
                continue
            matched += 1
            pageMatched += 1
            code := RowCode(item.row, column)
            if (code = "") {
                withoutCode += 1
                continue
            }
            if !seen.Has(code) {
                seen[code] := true
                codes.Push(code)
            }
        }
        if (stopOnEmptyPage && pageMatched = 0)
            break
        state := PagerState(title, pageField)
        if (state.current != "" && state.current = state.last)
            break
        if (pages >= 500 || !PagerAvailable(title, nextPage))
            break
        before := PageSignature(title, grid, pageField)
        problem := WebClick(title, nextPage, 5000)
        if (problem != "")
            return {problem: problem, text: ""}
        deadline := A_TickCount + 20000
        Loop {
            Sleep 200
            after := PageSignature(title, grid, pageField)
            if (after != before && VisibleGridRows(title, grid, 0).Length > 0)
                break
            if (A_TickCount >= deadline)
                return {problem: "Lista nie przeszła na stronę " (pages + 1) ".", text: ""}
        }
    }
    list := ""
    for code in codes
        list .= code "`r`n"
    A_Clipboard := list
    shown := ""
    for code in codes {
        if (A_Index > 40) {
            shown .= "… i " (codes.Length - 40) " więcej`n"
            break
        }
        shown .= code "`n"
    }
    result := "Przejrzane strony: " pages "`nWiersze z błędem `"" text "`": " matched "`n"
    if (withoutCode > 0)
        result .= "BEZ KODU ITM8 (sprawdź ręcznie): " withoutCode "`n"
    result .= codes.Length = 0 ? "`nNie ma kodów do zebrania, Schowek jest pusty.`n"
        : "`nKody w Schowku: " codes.Length ". Wklej je w Intermanage (Uzupełnij ITM8).`n`n" shown
    return {problem: "", text: result}
}

; Ustawia pole albo listę i sprawdza wynik; lista bez takiej pozycji to błąd, nie cicha zmiana na inną.
WebSetValue(title, field, value, timeoutMs) {
    deadline := A_TickCount + timeoutMs
    Loop {
        for hwnd in WinGetList(title) {
            for doc in HtmlDocuments(hwnd) {
                for element in FormElements(doc) {
                    if (WebProp(element, "id") != field && WebProp(element, "name") != field)
                        continue
                    if (WebProp(element, "disabled") || !IsShown(element))
                        continue
                    if (WebProp(element, "value") = value)
                        return ""
                    limit := WebProp(element, "maxLength")
                    if (limit != "" && limit > 0 && limit < 100000 && StrLen(value) > limit)
                        return "Wartość `"" value "`" jest dłuższa niż pole `"" field "`" (najwyżej " limit " znaków)."
                    try {
                        previous := WebProp(element, "value")
                        element.value := value
                        if (WebProp(element, "value") != value) {
                            element.value := previous ; lista bez tej pozycji zostałaby pusta
                            return "Pole `"" field "`" nie przyjęło wartości `"" value "`"."
                        }
                        try WinActivate hwnd
                        window := doc.parentWindow
                        window.__macroTarget := element
                        window.setTimeout("window.__macroTarget.fireEvent('onchange')", 0, "JScript")
                        return ""
                    }
                }
            }
        }
        if (A_TickCount >= deadline)
            return "Nie ma pola `"" field "`" w oknie `"" title "`"."
        Sleep 100
    }
}

; Obsługuje bony z listy strona po stronie: tylko wiersz z listy i z pustą kolumną dostaje kliknięcie, najwyżej raz, a pętla
; czeka na wpisanie wyniku. Kończy się, gdy każdy bon z listy ma wynik, po czym podsumowanie mówi, czego nie było.
WebEachRow(title, grid, column, button, nextPage, maxCount, wanted, confirm := "", results := Map()) {
    handled := Map()
    count := 0
    Accepts(row) => WantedKey(row, wanted) != ""
    Summary(ending) => ListSummary(wanted, handled, results, count, ending)
    Loop {
        ReadResults(title, grid, column, wanted, results)
        if (results.Count >= wanted.Count)
            return Summary("Wszystkie bony z listy mają wynik.")
        if (count >= maxCount)
            return Summary("Osiągnięto limit " maxCount ".")
        key := ""
        problem := WebSelectRow(title, grid, column, 15000, &key, handled, Accepts)
        if (problem = "BRAK") {
            first := FirstRowKey(title, grid)
            if (WebClick(title, nextPage, 2000) != "")
                return Summary("Koniec listy.")
            if !WaitForNewPage(title, grid, first, 8000)
                return Summary("Koniec listy.")
            continue
        }
        if (problem != "")
            return Summary("ZATRZYMANE: " problem)
        problem := WebClick(title, button, 10000)
        if (problem != "")
            return Summary("ZATRZYMANE: " problem)
        handled[key] := true
        count += 1
        if IsObject(confirm) {
            problem := ConfirmDialog(confirm.title, confirm.expected, confirm.button, 30000)
            if (problem != "")
                return Summary("ZATRZYMANE przy wierszu " key ": " problem)
        }
        if !WaitForRowResult(title, grid, column, key, 30000)
            return Summary("ZATRZYMANE: nie widać wyniku przy wierszu " key ". Sprawdź go ręcznie, makro nie kliknie go ponownie.")
    }
}

; Okno z wynikiem blokuje główną stronę. Zamyka je tylko oczekiwany komunikat, każdy inny zatrzymuje pętlę, żeby było widać, co się stało.
ConfirmDialog(title, expected, button, timeoutMs) {
    deadline := A_TickCount + timeoutMs
    Loop {
        for hwnd in WinGetList(title) {
            for doc in HtmlDocuments(hwnd) {
                text := ""
                try {
                    if (doc.readyState = "complete")
                        text := Trim(doc.body.innerText)
                }
                if (text = "")
                    continue
                if !InStr(text, expected)
                    return "Mercalys pokazał inny komunikat: " text
                problem := WebClick(title, button, 5000)
                if (problem != "")
                    return problem
                return WinWaitClose("ahk_id " hwnd, , 10) ? "" : "Okno potwierdzenia się nie zamknęło."
            }
        }
        if (A_TickCount >= deadline)
            return "Nie pojawiło się okno potwierdzenia `"" expected "`"."
        Sleep 100
    }
}

; Pytanie, które pojawia się tylko czasem. Liczą się tylko strony z przyciskiem o tym id (główne okno Mercalys też ma
; w tytule „Les Mousquetaires”). Okno z innym tekstem zostaje dla kolejnych linii makra (np. „Utworzyć zapis ?”),
; więc nie czekamy wtedy do końca limitu.
OptionalDialog(title, expected, button, timeoutMs) {
    deadline := A_TickCount + timeoutMs
    Loop {
        for hwnd in WinGetList(title) {
            for doc in HtmlDocuments(hwnd) {
                element := ""
                text := ""
                try {
                    if (doc.readyState = "complete") {
                        element := doc.getElementById(button)
                        if (WebProp(element, "tagName") != "")
                            text := Trim(doc.body.innerText)
                    }
                }
                if (text = "")
                    continue
                matched := false
                for piece in StrSplit(expected, ";")
                    if (Trim(piece) != "" && InStr(text, Trim(piece)))
                        matched := true
                if !matched
                    return ""
                try WinActivate hwnd
                ClickWithoutWaiting(doc, element)
                return WinWaitClose("ahk_id " hwnd, , 10) ? "" : "Okno pytania `"" expected "`" się nie zamknęło."
            }
        }
        if (A_TickCount >= deadline)
            return ""
        Sleep 100
    }
}

; Spacje i &nbsp; nie liczą się w numerze, bo w Mercalys i w pliku bywają różne; kod dostawcy bez zer wiodących.
ListKey(number, code) => StrUpper(RegExReplace(StrReplace(number, Chr(160), ""), "\s")) "|" LTrim(Trim(code), "0")

; Klucz z listy dla wiersza; pusty kod na liście oznacza dowolnego dostawcę.
WantedKey(row, wanted) {
    key := ListKey(CellText(row, 0), CellText(row, 1))
    if wanted.Has(key)
        return key
    key := ListKey(CellText(row, 0), "")
    return wanted.Has(key) ? key : ""
}

ReadResults(title, grid, column, wanted, results) {
    for hwnd in WinGetList(title) {
        for doc in HtmlDocuments(hwnd) {
            for item in GridRows(doc, grid) {
                key := WantedKey(item.row, wanted)
                value := CellText(item.row, column)
                if (key != "" && value != "" && value != "?")
                    results[key] := value
            }
        }
    }
}

ListSummary(wanted, handled, results, count, ending) {
    report := "Kody utworzone teraz: " count "`nBony z listy: " wanted.Count "`n"
    found := ""
    missing := ""
    for key, item in wanted {
        if results.Has(key)
            found .= item.number " -> " results[key] "`n"
        else
            missing .= item.number (item.code != "" ? " (dostawca " item.code ")" : "") "`n"
    }
    if (found != "")
        report .= "`nNr BL -> Nr Zamów:`n" found
    if (missing != "")
        report .= "`nBEZ WYNIKU, sprawdź ręcznie:`n" missing
    return report "`n" ending
}

; Wynik widać, gdy kolumna wiersza się wypełni albo lista wróciła bez niego (inna strona, nowe sortowanie).
WaitForRowResult(title, grid, column, key, timeoutMs) {
    deadline := A_TickCount + timeoutMs
    Loop {
        for hwnd in WinGetList(title) {
            for doc in HtmlDocuments(hwnd) {
                rows := GridRows(doc, grid)
                if (rows.Length = 0)
                    continue
                present := false
                for item in rows {
                    if (RowKey(item.row) = key) {
                        present := true
                        if (CellText(item.row, column) != "")
                            return true
                    }
                }
                if !present
                    return true
            }
        }
        if (A_TickCount >= deadline)
            return false
        Sleep 200
    }
}

WaitForNewPage(title, grid, previous, timeoutMs) {
    deadline := A_TickCount + timeoutMs
    Loop {
        first := FirstRowKey(title, grid)
        if (first != "" && first != previous)
            return true
        if (A_TickCount >= deadline)
            return false
        Sleep 200
    }
}

FirstRowKey(title, grid) {
    for hwnd in WinGetList(title) {
        for doc in HtmlDocuments(hwnd) {
            rows := GridRows(doc, grid)
            if (rows.Length > 0)
                return RowKey(rows[1].row)
        }
    }
    return ""
}

; Numer dokumentu i kod dostawcy: sam numer bywa taki sam u dwóch dostawców.
RowKey(row) => CellText(row, 0) " / " CellText(row, 1)

; Klik w wiersz zapisuje jego numer w ukrytym polu siatki: bez tego kolejny przycisk trafiłby w poprzednio zaznaczony wiersz.
WaitForSelection(doc, grid, item, deadline) {
    Loop {
        try {
            if (doc.getElementById(grid "_hiddenFocus").value = item.index)
                return ""
        }
        if (A_TickCount >= deadline)
            return "Nie udało się zaznaczyć wiersza `"" CellText(item.row, 0) "`"."
        Sleep 100
    }
}

; Wiersze danych siatki z numerami z setSelectedLigne; pusto, gdy strona jeszcze się ładuje.
; Siatka "*" = każda siatka strony (gdy jej nazwy nie znamy); wiersz niesie wtedy nazwę swojej siatki.
GridRows(doc, grid) {
    rows := []
    try {
        if (doc.readyState != "complete" || (grid != "*" && WebProp(doc.getElementById(grid "_hiddenFocus"), "tagName") = ""))
            return rows
        name := grid = "*" ? "[^']+" : "\Q" grid "\E"
        found := doc.getElementsByTagName("tr")
        Loop found.length {
            row := found.item(A_Index - 1)
            ; Wiersz z tabelą w środku (ramka listy, np. Lista Cadencier) ma w HTML kliknięcia swoich wierszy, ale sam nim nie jest.
            nested := 0
            try nested := row.getElementsByTagName("tr").length
            if (nested > 0)
                continue
            if RegExMatch(WebProp(row, "outerHTML"), "setSelectedLigne\('(" name ")',[^,]+,(\d+),", &match)
                rows.Push({row: row, index: match[2], grid: match[1]})
        }
    }
    return rows
}

; Tekst komórki bez spacji i &nbsp;; komórki spoza wiersza nie są puste, żeby krótszy wiersz nie został wybrany.
CellText(row, column) {
    try {
        cells := row.cells
        if (column < cells.length)
            return Trim(StrReplace(cells.item(column).innerText, Chr(160), " "))
    }
    return "?"
}

WebShow(title, buttons) {
    report := "Odtwarzacz w wersji " PlayerVersion "`n"
    for button in StrSplit(buttons, ",") {
        found := false
        for hwnd in WinGetList(title) {
            for doc in HtmlDocuments(hwnd) {
                for element in AllElements(doc) {
                    if IsButton(element, Trim(button)) {
                        found := true
                        holder := element
                        Loop 2 {
                            parent := WebProp(holder, "parentElement")
                            if IsObject(parent)
                                holder := parent
                        }
                        report .= "`n" Trim(button) " na " WebProp(doc, "URL") ":`n" SubStr(WebProp(holder, "outerHTML"), 1, 6000) "`n"
                    }
                }
            }
        }
        if !found {
            ; Wiersz listy to nie przycisk: szukamy komórki o tym tekście i pokazujemy jej wiersz.
            for hwnd in WinGetList(title) {
                for doc in HtmlDocuments(hwnd) {
                    try {
                        cells := doc.getElementsByTagName("td")
                        Loop cells.length {
                            cell := cells.item(A_Index - 1)
                            if (!found && Trim(WebProp(cell, "innerText")) = Trim(button)) {
                                found := true
                                report .= "`nWiersz " Trim(button) " na " WebProp(doc, "URL") ":`n" SubStr(WebProp(WebProp(cell, "parentElement"), "outerHTML"), 1, 6000) "`n"
                            }
                        }
                    }
                }
            }
        }
        if !found
            report .= "`n" Trim(button) ": nie znaleziono`n"
    }
    A_Clipboard := report
    return report
}

; Zwykłe click() wraca dopiero po zamknięciu okna, które przycisk otworzy (alert, okno dialogowe), więc makro by stanęło.
ClickWithoutWaiting(doc, element) {
    try {
        window := doc.parentWindow
        href := WebProp(element, "href")
        if (SubStr(href, 1, 11) = "javascript:") {
            ; Mercalys: click() pokazałby wynik funkcji z href jako stronę ("false"). canSubmit sprawdza onclick przycisku.
            window.setTimeout("if (typeof canSubmit == 'undefined' || canSubmit) { " SubStr(href, 12) " }", 0, "JScript")
            return
        }
        window.__macroTarget := element
        window.setTimeout("window.__macroTarget.click()", 0, "JScript")
    } catch {
        element.click()
    }
}

IsButton(element, button) {
    for name in ["id", "name", "value", "innerText"] {
        if (Trim(WebProp(element, name)) = button)
            return true
    }
    return false
}

; Przyciski Mercalys to puste odnośniki o szerokości 0, więc o widoczności decyduje styl elementu i jego rodziców.
IsShown(element) {
    try {
        if (WebProp(element.currentStyle, "visibility") = "hidden")
            return false
        node := element
        while IsObject(node) {
            if (WebProp(node.currentStyle, "display") = "none")
                return false
            node := WebProp(node, "parentElement")
        }
        return true
    }
    return false
}

; Właściwość elementu; pusta, gdy element jej nie ma (np. <a> nie ma value).
WebProp(element, name) {
    try return element.%name%
    return ""
}

ClickableElements(doc) {
    elements := []
    ; Strona w trakcie przeładowania odmawia dostępu: pusta lista, a WebClick sprawdzi jeszcze raz.
    try {
        for tag in ["button", "input", "a"] {
            found := doc.getElementsByTagName(tag) ; starsze tryby IE nie dają przejść po liście pętlą for
            Loop found.length {
                element := found.item(A_Index - 1)
                if (tag != "input" || InStr(" button submit reset image checkbox radio ", " " StrLower(WebProp(element, "type")) " "))
                    elements.Push(element)
            }
        }
    }
    return elements
}

AllElements(doc) {
    elements := ClickableElements(doc)
    elements.Push(FormElements(doc)*)
    return elements
}

; Pola do wypełniania: skanowanie pokazuje je obok przycisków, a WEBSET szuka w nich po id albo name.
FormElements(doc) {
    elements := []
    try {
        for tag in ["select", "input", "textarea"] {
            found := doc.getElementsByTagName(tag)
            Loop found.length {
                element := found.item(A_Index - 1)
                if (tag != "input" || InStr(" text password ", " " StrLower(WebProp(element, "type")) " "))
                    elements.Push(element)
            }
        }
    }
    return elements
}

DescribeField(element) {
    line := StrLower(WebProp(element, "tagName")) " " WebProp(element, "type") " | id=" WebProp(element, "id")
        . " | name=" WebProp(element, "name") " | value=" WebProp(element, "value")
    maxLength := WebProp(element, "maxLength")
    if (maxLength != "" && maxLength < 100000)
        line .= " | max=" maxLength
    try {
        options := element.options
        line .= " | pozycje="
        Loop options.length {
            option := options.item(A_Index - 1)
            line .= WebProp(option, "value") "=" WebProp(option, "text") "; "
        }
    }
    return line
}

; Linia raportu skanowania. Przyciski Mercalys to ikony bez tekstu, więc opis bywa tylko w title albo w obrazku.
DescribeElement(element) {
    line := StrLower(WebProp(element, "tagName")) " " WebProp(element, "type") " | id=" WebProp(element, "id")
        . " | name=" WebProp(element, "name") " | value=" WebProp(element, "value")
        . " | tekst=" Trim(WebProp(element, "innerText")) " | opis=" ElementCaption(element)
    if InStr(" checkbox radio ", " " StrLower(WebProp(element, "type")) " ")
        line .= " | zaznaczone=" (WebProp(element, "checked") ? "tak" : "nie")
    return line
}

ElementCaption(element) {
    caption := WebProp(element, "title")
    if (caption != "")
        return caption
    try {
        images := element.getElementsByTagName("img")
        if images.length {
            image := images.item(0)
            caption := WebProp(image, "alt")
            return caption != "" ? caption : WebProp(image, "title")
        }
    }
    return ""
}

; Dokumenty wszystkich widocznych kart okna razem z ramkami. Działa też dla okien dialogowych strony.
HtmlDocuments(hwnd) {
    docs := []
    try {
        for control in WinGetControlsHwnd(hwnd) {
            if (WinGetClass(control) = "Internet Explorer_Server" && ControlGetVisible(control)) {
                doc := DocumentOf(control)
                if IsObject(doc)
                    AddWithFrames(doc, docs)
            }
        }
    }
    return docs
}

AddWithFrames(doc, docs) {
    docs.Push(doc)
    try {
        frames := doc.frames
        Loop frames.length
            try AddWithFrames(frames.item(A_Index - 1).document, docs)
    }
}

DocumentOf(control) {
    static message := DllCall("RegisterWindowMessage", "Str", "WM_HTML_GETOBJECT", "UInt")
    static SMTO_ABORTIFHUNG := 2
    result := 0
    if !DllCall("SendMessageTimeout", "Ptr", control, "UInt", message, "Ptr", 0, "Ptr", 0, "UInt", SMTO_ABORTIFHUNG, "UInt", 1000, "Ptr*", &result)
        return ""
    if !result
        return ""
    iid := Buffer(16)
    DllCall("ole32\CLSIDFromString", "Str", "{332C4425-26CB-11D0-B483-00C04FD90119}", "Ptr", iid) ; IHTMLDocument2
    pointer := 0
    if (DllCall("oleacc\ObjectFromLresult", "Ptr", result, "Ptr", iid, "Ptr", 0, "Ptr*", &pointer, "Int") != 0 || !pointer)
        return ""
    return ComValue(9, pointer)
}