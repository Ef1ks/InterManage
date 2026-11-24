#Requires AutoHotkey v2.0
;@Ahk2Exe-SetMainIcon player.ico
;@Ahk2Exe-SetName Intermanage Makra
;@Ahk2Exe-SetDescription Intermanage - odtwarzacz makr
;@Ahk2Exe-SetCompanyName Intermanage
;@Ahk2Exe-SetVersion 0.68.0.0
#SingleInstance Force
SendMode "Event"
SetKeyDelay 30, 30
CoordMode "Mouse", "Screen"
SetTitleMatchMode 2 ; WEBCLICK: wystarczy fragment tytułu okna
global PlayerVersion := "0.68" ; numeracja 0.NN (dawna wersja NN = 0.NN); podbijamy przy każdej zmianie (też SetVersion wyżej), widać go w menu przy zegarze i w raporcie skanowania
global AlwaysOnTop := " 262144" ; opcja MsgBox: wynik musi być widać, nawet gdy po ostatnim zamknięciu otworzy się PDF bonu

global MacroRunning := false ; Esc działa tylko w trakcie makra
; Makro zlecone przez agenta sklepu (od 0.64): pierwsza linia #RESULT|<id> i wynik w pliku <id>.txt w folderze agenta.
; Makro podaje sam identyfikator, nie ścieżkę, więc tekst ze Schowka nie wskaże cudzego pliku.
global RemoteId := ""
global RemoteFinished := false
global MacroCollected := {codes: [], seen: Map()} ; kody z WEBCOLLECT bieżącego makra, do wyniku
global ScanMode := false ; Alt+R: następny klik skanuje okno zamiast w nie trafić
global AutostartKey := "HKCU\Software\Microsoft\Windows\CurrentVersion\Run"
global AutostartName := "IntermanageMakra"
global SettingsKey := "HKCU\Software\Intermanage\Makra"
global IdleTip := "Intermanage Makra " PlayerVersion
; Odtwarzacz w agencie sklepu (od 0.63): agent wypakowuje go do swojego folderu, kładzie obok plik agent-mode,
; uruchamia i pilnuje. Wtedy bez własnej ikony i autostartu, bo jest tylko ikona agenta. Plik, a nie parametr,
; bo Reload (Esc w trakcie makra) nie przekazuje parametrów nowemu procesowi.
global AgentMode := FileExist(A_ScriptDir "\agent-mode") != ""

if AgentMode {
    A_IconHidden := true
    if A_IsCompiled {
        ; Dawny osobny odtwarzacz nie może startować z Windows obok agenta (dwa odtwarzacze = makro dwa razy).
        try RegDelete AutostartKey, AutostartName
        SyncClipboardPrivacy()
    }
} else {
    SetupTray()
    if A_IsCompiled {
        SyncAutostart()
        SyncClipboardPrivacy()
    }
    ; Przy starcie z Windows bez komunikatu; po ręcznym uruchomieniu trzeba wiedzieć, że program już działa.
    if (A_Args.Length = 0 || A_Args[1] != "autostart")
        TrayTip "Działa w tle, ikona jest przy zegarze.`nSkopiuj makro ze strony i naciśnij Alt+E.", "Intermanage Makra", 1
}

; Powiadomienie przy zegarze. W agencie ikona odtwarzacza jest schowana, a bez ikony Windows nie pokaże powiadomienia,
; więc pokazujemy ją na chwilę (i na czas makra, patrz Alt+E).
Notify(text, title := "Intermanage Makra") {
    if AgentMode {
        A_IconHidden := false
        SetTimer HideIdleIcon, -10000
    }
    TrayTip text, title, 1
}

HideIdleIcon() {
    if (AgentMode && !MacroRunning)
        A_IconHidden := true
}

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
IsMacro(text) => RegExMatch(text, "i)^(\s*(#[^\n]*)?\r?\n)*\s*(#[^\n]*\r?\n\s*)*(ASK|CLICK|TEXT|KEY|SLEEP|WEB[A-Z]+|WIN[A-Z]+|EXCEL[A-Z]+|PDF[A-Z]+)\s*[|;]") = 1

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
        visible .= DescribeGridRows(doc)
        if (visible != "")
            report .= "`nStrona: " WebProp(doc, "URL") "`n" visible
    }
    A_Clipboard := report
    MsgBox report, "Przyciski strony (skopiowano do Schowka)"
}

; Skan (od 0.57): tekst widocznych wierszy tabel z przyciskami w wierszu (`<siatka>__ctlN_…`), żeby było widać, po czym
; makro ma szukać wiersza (np. historia pobierania danych), bo same przyciski tego nie mówią.
DescribeGridRows(doc) {
    out := ""
    seen := Map()
    try {
        inputs := doc.getElementsByTagName("input")
        Loop inputs.length {
            element := inputs.item(A_Index - 1)
            id := WebProp(element, "id")
            if !(RegExMatch(id, "^(.+?)__ctl(\d+)_", &match) && IsShown(element))
                continue
            key := match[1] "#" match[2]
            if seen.Has(key)
                continue
            seen[key] := true
            row := GridRowOf(element, match[1])
            if IsObject(row)
                out .= "wiersz | siatka=" match[1] " | ctl" match[2] " | tekst=" RowStateText(row) "`n"
        }
    }
    return out
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
    if AgentMode
        A_IconHidden := false
    global RemoteId := RegExMatch(macro, "im)^#RESULT\|([0-9a-f]{32})\s*$", &remote) ? remote[1] : ""
    global RemoteFinished := false
    global MacroCollected := {codes: [], seen: Map()}
    if (RemoteId != "")
        WriteRemote("RUNNING", "")
    try {
        PlayMacro(macro)
        FinishRemote("OK", "")
    } catch as problem {
        if (RemoteId = "")
            throw problem
        FinishRemote("ERROR", problem.Message)
    } finally {
        RemoteId := ""
        MacroRunning := false
        A_IconTip := IdleTip
        if AgentMode
            SetTimer HideIdleIcon, -10000
    }
}

; Stop awaryjny: okno wstrzymuje makro, a ponowne uruchomienie programu je kończy, nawet gdy czeka na stronę.
#HotIf MacroRunning
Esc:: {
    FinishRemote("ERROR", "Makro zatrzymane klawiszem Esc na komputerze. Sprawdź w Mercalys, co zostało zrobione.")
    MsgBox "Makro zatrzymane. Sprawdź w Mercalys, co zostało zrobione.", "Makro zatrzymane", "Icon!" AlwaysOnTop
    Reload
}
#HotIf

; Okno makra. Makro zlecone przez agenta nie pokazuje okien (nikt by ich nie zamknął, a odtwarzacz czekałby w nieskończoność):
; treść idzie do pliku wyniku, a pokazuje ją agent i przeglądarka zlecającego.
MacroBox(text, title := "", options := "") {
    if (RemoteId != "") {
        FinishRemote(title = "Makro zakończone" ? "OK" : "ERROR", text)
        return
    }
    MsgBox text, title, options
}

; Wynik makra zleconego przez agenta, tylko pierwszy (błąd nie może zostać nadpisany końcem makra).
FinishRemote(state, text) {
    global RemoteFinished
    if (RemoteId = "" || RemoteFinished)
        return
    RemoteFinished := true
    WriteRemote(state, text)
}

; Plik wyniku: 1. linia stan (RUNNING, OK, ERROR), 2. zebrane kody po przecinku, dalej opis. Zapis przez plik tymczasowy,
; żeby agent nie przeczytał połowy.
WriteRemote(state, text) {
    list := ""
    for code in MacroCollected.codes
        list .= (A_Index = 1 ? "" : ",") code
    folder := EnvGet("LOCALAPPDATA") "\Intermanage\Agent\makra"
    try {
        DirCreate folder
        temp := folder "\" RemoteId ".tmp"
        try FileDelete temp
        FileAppend state "`n" list "`n" text, temp, "UTF-8-RAW"
        FileMove temp, folder "\" RemoteId ".txt", 1
    }
}

PlayMacro(rawClip) {
    lines := StrSplit(rawClip, "`n", "`r")
    wanted := Map() ; bony z linii WEBLIST
    results := Map() ; Nr Zamów bonów z listy, z WEBROWS
    report := "" ; podsumowania etapów, pokazane na końcu makra
    closingRan := false ; czy w tym przebiegu był krok WEBCLOSE
    closedCount := 0 ; dostawy zamknięte przez WEBCLOSE w tym przebiegu
    ; EXCELSAVE: skoroszyty otwarte przed makrem nie są „nowe”, więc makro nie zapisze cudzego pliku
    excelSeen := InStr(rawClip, "EXCELSAVE") ? ExcelSnapshot() : Map()
    pdfSeen := InStr(rawClip, "PDFSAVE") ? PdfSnapshot() : Map() ; to samo dla okien PDF
    orders := Map() ; dostawca -> numer zamówienia dostawy z bazy (WEBORDERREAD, WEBORDERKNOWN)
    pickedOrder := "" ; zamówienie przeniesione do wybranych przez ostatni WEBORDERPICK
    vars := Map() ; wartości odczytane przez WEBREAD, wstawiane w kolejne linie jako %nazwa%
    vatCodes := Map() ; WEBVATCODE: EAN bez zer wiodących -> kod stawki VAT w Mercalys
    handledRows := Map() ; WEBIFROW: ile wierszy obsłużono pod daną etykietą (np. pliki jednego kodu MEA)
    global MacroCollected
    collected := MacroCollected ; WEBCOLLECT: kody ze wszystkich list tego makra (np. pliki bonu od 538 i 88110)
    collectRuns := 0

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
                    MacroBox problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!"
                    return
                }
            case "WEBROW":
                ; WEBROW|fragment tytułu okna|siatka|numer kolumny od 0|limit w ms: zaznacza pierwszy wiersz z pustą kolumną
                if (parts.Length < 4) {
                    MacroBox "Linia WEBROW wymaga okna, siatki i kolumny.", "Błąd makra", "Icon!"
                    return
                }
                problem := WebSelectRow(Trim(parts[2]), Trim(parts[3]), Integer(Trim(parts[4])), parts.Length >= 5 ? Integer(Trim(parts[5])) : 10000)
                if (problem = "BRAK") {
                    MacroBox "Na tej stronie listy nie ma już wierszy do obsłużenia.", "Makro zakończone", "Iconi"
                    return
                }
                if (problem != "") {
                    MacroBox problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!"
                    return
                }
            case "WEBLIST":
                ; WEBLIST|kod dostawcy (może być pusty)|numer dokumentu: bon, który WEBROWS ma obsłużyć; reszta linii to numer, bo może mieć |
                if (parts.Length < 3) {
                    MacroBox "Linia WEBLIST wymaga kodu dostawcy i numeru.`n`n" line, "Błąd makra", "Icon!"
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
                    MacroBox "Linia WEBROWS wymaga okna, siatki, kolumny, przycisku, następnej strony i limitu.", "Błąd makra", "Icon!"
                    return
                }
                if (wanted.Count = 0) {
                    MacroBox "Makro nie ma listy bonów (linie WEBLIST), więc niczego nie kliknie.", "Błąd makra", "Icon!"
                    return
                }
                confirm := parts.Length >= 10 ? {title: Trim(parts[8]), expected: Trim(parts[9]), button: Trim(parts[10])} : ""
                summary := WebEachRow(Trim(parts[2]), Trim(parts[3]), Integer(Trim(parts[4])), Trim(parts[5]), Trim(parts[6]), Integer(Trim(parts[7])), wanted, confirm, results)
                report .= summary "`n"
                ; Kolejne etapy tylko, gdy każdy bon z listy ma numer: reszta makra potrzebuje wszystkich par.
                if (results.Count < wanted.Count) {
                    MacroBox report, "Makro zakończone", "Iconi" AlwaysOnTop
                    return
                }
            case "ASK":
                ; ASK|pytanie: Tak idzie dalej, Nie kończy makro bez błędu, np. pracownik sprawdza dokument przed zamknięciem
                if (parts.Length < 2) {
                    MacroBox "Linia ASK wymaga pytania.`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                if (MsgBox(report Trim(parts[2]), "Intermanage Makra", "YesNo Icon?" AlwaysOnTop) != "Yes") {
                    Notify "Makro zakończone bez tego kroku."
                    return
                }
            case "WINOPEN":
                ; WINOPEN|okno|hasło: program już uruchomiony wraca z paska zadań na wierzch. Wyłączony Mercalys odtwarzacz
                ; uruchamia sam (od 0.54) i loguje hasłem z linii; bez hasła czeka, aż pracownik zaloguje się ręcznie.
                ; Starsze odtwarzacze pomijają hasło i bez programu stają jak dawniej.
                problem := parts.Length < 2 ? "Linia WINOPEN wymaga okna."
                    : WinOpen(Trim(parts[2]), parts.Length >= 3 ? parts[3] : "")
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
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
            case "PDFPRINTALL":
                ; PDFPRINTALL|okno|limit w ms: jak WINCLOSEALL, ale każdy PDF przed zamknięciem idzie w Acrobacie do drukarki
                ; domyślnej (Ctrl+P, Enter). PDF, którego nie udało się wydrukować, zostaje otwarty i trafia do podsumowania.
                if (parts.Length >= 2 && (!closingRan || closedCount > 0))
                    report .= "`n" PdfPrintAll(Trim(parts[2]), parts.Length >= 3 ? Integer(Trim(parts[3])) : closingRan ? 20000 : 0) "`n"
            case "WINMIN":
                ; WINMIN|okno: chowa program na pasek zadań; brak okna nie jest błędem, bo praca jest już skończona
                if (parts.Length >= 2) {
                    for hwnd in WinGetList(Trim(parts[2]))
                        try WinMinimize hwnd
                }
            case "EXCELSAVE":
                ; EXCELSAVE|ścieżka pliku|limit w ms: czeka na nowy skoroszyt w Excelu (np. po przycisku Excel w Mercalys),
                ; zapisuje go pod tą ścieżką (format z końcówki: xlsx, xlsm, xlsb, xls, csv; istniejący plik jest nadpisywany) i zamyka
                problem := parts.Length < 2 ? "Linia EXCELSAVE wymaga ścieżki pliku."
                    : ExcelSave(Trim(parts[2]), parts.Length >= 3 ? Integer(Trim(parts[3])) : 120000, excelSeen)
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                report .= "Zapisano: " Trim(parts[2]) "`n"
            case "PDFSAVE":
                ; PDFSAVE|ścieżka pliku .pdf|limit w ms: czeka na nowe okno PDF (np. raport drukowany z Mercalys), zapisuje
                ; ten dokument pod tą ścieżką (istniejący plik jest zastępowany) i zamyka okno PDF
                problem := parts.Length < 2 ? "Linia PDFSAVE wymaga ścieżki pliku."
                    : PdfSave(Trim(parts[2]), parts.Length >= 3 ? Integer(Trim(parts[3])) : 300000, pdfSeen)
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                report .= "Zapisano: " Trim(parts[2]) "`n"
            case "WEBENSURE":
                ; WEBENSURE|okno|element|przycisk: klika przycisk tylko, gdy elementu nie widać, np. zakładka menu pod innym ekranem
                problem := parts.Length < 4 ? "Linia WEBENSURE wymaga okna, elementu i przycisku." : WebEnsure(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]))
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
            case "WEBOPEN":
                ; WEBOPEN|okno|rozwinięcie menu|pozycja menu|element otwartego ekranu: rozwija tylko, gdy pozycji nie widać, bo drugie
                ; kliknięcie zwinęłoby menu. Z elementem klika pozycję ponownie (najwyżej 4 razy), dopóki ekran się nie otworzy.
                problem := parts.Length < 4 ? "Linia WEBOPEN wymaga okna, rozwinięcia i pozycji."
                    : WebOpen(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), parts.Length >= 5 ? Trim(parts[5]) : "")
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!"
                    return
                }
            case "WEBHEADERS":
                ; WEBHEADERS|okno|siatka|kolumna numeru|kolumna identyfikacji|przycisk nagłówka|pole|zapis|okno pytania|tekst pytania|odpowiedź|pierwsza strona|następna strona
                ; Od 0.67 opcjonalnie |pole daty|data DDMMRR|wyrażenie regularne daty: przy wpisywaniu nagłówka ustawia też datę (spodziewana dostawa).
                if (parts.Length < 11 || results.Count = 0) {
                    MacroBox report "Linia WEBHEADERS wymaga wszystkich pól i wyników wcześniejszego WEBROWS.", "Błąd makra", "Icon!"
                    return
                }
                step := {title: Trim(parts[2]), grid: Trim(parts[3]), numberColumn: Integer(Trim(parts[4])), fieldColumn: Integer(Trim(parts[5])),
                    open: Trim(parts[6]), field: Trim(parts[7]), save: Trim(parts[8]), dialog: Trim(parts[9]), dialogText: Trim(parts[10]), dialogButton: Trim(parts[11]),
                    firstPage: parts.Length >= 13 ? Trim(parts[12]) : "", nextPage: parts.Length >= 13 ? Trim(parts[13]) : "",
                    dateField: parts.Length >= 16 ? Trim(parts[14]) : "", date: parts.Length >= 16 ? Trim(parts[15]) : "", datePattern: parts.Length >= 16 ? Trim(parts[16]) : ""}
                summary := WebHeaders(step, wanted, results)
                report .= "`n" summary.text "`n"
                if !summary.finished {
                    MacroBox report, "Makro zatrzymane", "Icon!" AlwaysOnTop
                    return
                }
            case "WEBPICK":
                ; WEBPICK|okno|siatka|kolumna od 0|tekst|limit w ms: zaznacza wiersz z tym tekstem w kolumnie
                problem := parts.Length < 5 ? "Linia WEBPICK wymaga okna, siatki, kolumny i tekstu."
                    : WebPickRow(Trim(parts[2]), Trim(parts[3]), Integer(Trim(parts[4])), Trim(parts[5]), parts.Length >= 6 ? Integer(Trim(parts[6])) : 10000)
                if (problem != "") {
                    MacroBox problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!"
                    return
                }
            case "WEBPICKRE":
                ; WEBPICKRE|okno|siatka|wyrażenie regularne|limit w ms|etykieta: zaznacza pierwszy wiersz, którego tekst pasuje
                ; (np. nazwa pliku z numerem bonu i dostawcą). Z etykietą lista bez takiego wiersza nie jest błędem: przeskok do
                ; LABEL|etykieta (np. bon bez pliku alkoholu 88110).
                missing := false
                problem := parts.Length < 4 ? "Linia WEBPICKRE wymaga okna, siatki i wyrażenia."
                    : WebPickRowMatching(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), parts.Length >= 5 && Trim(parts[5]) != "" ? Integer(Trim(parts[5])) : 10000, &missing)
                if (missing && parts.Length >= 6 && Trim(parts[6]) != "") {
                    report .= "Brak pliku: " Trim(parts[6]) "`n"
                    target := LabelLine(lines, Trim(parts[6]), index)
                    if (target = 0) {
                        MacroBox report "Brak linii LABEL|" Trim(parts[6]) " po linii " index ".", "Błąd makra", "Icon!" AlwaysOnTop
                        return
                    }
                    index := target
                    continue
                }
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
            case "WEBCOLLECT":
                ; WEBCOLLECT|okno|siatka|tekst wiersza|kolumna kodu od 0 albo auto|następna strona|lista stron|1 = stop na stronie bez takiego wiersza:
                ; ze stron listy zbiera kody z wierszy z tym tekstem (np. „ITM nie istnieje”), bez powtórzeń i zer wiodących, i kładzie je do Schowka.
                ; Mercalys stawia wiersze z błędami na początku listy, więc strona bez błędu kończy przegląd.
                ; Opcjonalnie |kolumna numeru bonu|numer bonu: każdy wiersz listy musi być z tego bonu, inaczej makro staje
                ; (otwarty zły plik). Kody z kolejnych WEBCOLLECT w jednym makrze sumują się w Schowku.
                if (parts.Length < 6) {
                    MacroBox report "Linia WEBCOLLECT wymaga okna, siatki, tekstu, kolumny i następnej strony.", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                summary := WebCollect(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), Trim(parts[5]), Trim(parts[6]), parts.Length >= 7 ? Trim(parts[7]) : "",
                    parts.Length >= 8 && Trim(parts[8]) = "1", collected,
                    parts.Length >= 10 ? Trim(parts[9]) : "", parts.Length >= 10 ? Trim(parts[10]) : "")
                collectRuns += 1
                if (summary.problem != "") {
                    MacroBox report summary.problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                report .= summary.text "`n"
            case "WEBCOLLECTED":
                ; WEBCOLLECTED|komunikat: zatrzymuje makro z komunikatem, gdy żaden WEBCOLLECT się nie wykonał (np. nie było pliku bonu)
                if (collectRuns = 0) {
                    MacroBox report (parts.Length >= 2 ? Trim(parts[2]) : "Nie zebrano żadnej listy."), "Makro zatrzymane", "Icon!" AlwaysOnTop
                    return
                }
            case "WEBSET":
                ; WEBSET|okno|id pola albo listy|wartość|limit w ms: wpisuje wartość, a lista wybiera pozycję i wywołuje swoją zmianę
                problem := parts.Length < 4 ? "Linia WEBSET wymaga okna, pola i wartości."
                    : WebSetValue(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), parts.Length >= 5 ? Integer(Trim(parts[5])) : 10000)
                if (problem != "") {
                    MacroBox problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!"
                    return
                }
            case "WEBWAIT":
                ; WEBWAIT|okno|element|tekst na stronie (opcjonalny, kilka możliwych po średniku)|limit w ms: czeka, aż element będzie widoczny, a strona ma ten tekst
                problem := parts.Length < 3 ? "Linia WEBWAIT wymaga okna i elementu."
                    : WebWait(Trim(parts[2]), Trim(parts[3]), parts.Length >= 4 ? Trim(parts[4]) : "", parts.Length >= 5 ? Integer(Trim(parts[5])) : 15000)
                if (problem != "") {
                    MacroBox problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!"
                    return
                }
            case "WEBPAGE":
                ; WEBPAGE|okno|fragment adresu strony|element|limit w ms: czeka na tę stronę (np. na-e015, wynik integracji) z widocznym
                ; elementem. Sam WEBWAIT na Powrót przechodził od razu, bo Powrót ma też strona sprzed integracji.
                ; Opcjonalnie |okno komunikatu|tekst (kilka po średniku)|przycisk|etykieta: gdy zamiast strony pojawi się taki
                ; komunikat (np. „brak PZ bezpośrednich do integracji”), zamyka go przyciskiem i przeskakuje do LABEL|etykieta.
                ; Od 0.66 takich czwórek może być kilka (różne komunikaty, każdy z własną etykietą; ich tekst idzie do podsumowania),
                ; a zamiast jednego elementu kilka po przecinku (wystarczy jeden widoczny).
                dialogs := []
                Loop Max(0, (parts.Length - 5) // 4) {
                    first := 2 + A_Index * 4
                    dialogs.Push({title: Trim(parts[first]), expected: Trim(parts[first + 1]), button: Trim(parts[first + 2]), label: Trim(parts[first + 3]), shown: ""})
                }
                hit := ""
                problem := parts.Length < 4 ? "Linia WEBPAGE wymaga okna, adresu i elementu."
                    : WebPage(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), parts.Length >= 5 ? Integer(Trim(parts[5])) : 30000, dialogs, &hit)
                if (problem = "DIALOG") {
                    if (dialogs.Length > 1 && hit.shown != "")
                        report .= "Komunikat Mercalys: " hit.shown "`n"
                    target := LabelLine(lines, hit.label, index)
                    if (target = 0) {
                        MacroBox report "Brak linii LABEL|" hit.label " po linii " index ".", "Błąd makra", "Icon!" AlwaysOnTop
                        return
                    }
                    index := target
                    continue
                }
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
            case "WEBDIALOG":
                ; WEBDIALOG|okno pytania|tekst (kilka po średniku)|przycisk|limit w ms: pytanie, które pojawia się tylko czasem
                ; (np. sprzedaż ze stratą przy przecenie). Z tym tekstem klika przycisk, bez okna albo z innym pytaniem idzie dalej.
                problem := parts.Length < 4 ? "Linia WEBDIALOG wymaga okna, tekstu i przycisku."
                    : OptionalDialog(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), parts.Length >= 5 ? Integer(Trim(parts[5])) : 3000)
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
            case "WEBVALUE":
                ; WEBVALUE|okno|pole|wyrażenie regularne|limit w ms: czeka, aż pole ma pasującą wartość (pole wyboru: tak / nie), bo WEBWAIT nie widzi wartości pól
                problem := parts.Length < 4 ? "Linia WEBVALUE wymaga okna, pola i wyrażenia."
                    : WebValue(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), parts.Length >= 5 ? Integer(Trim(parts[5])) : 5000)
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!"
                    return
                }
            case "WEBREAD":
                ; WEBREAD|okno|pole|nazwa|wyrażenie regularne|limit w ms: czeka, aż pole ma pasującą wartość, i zapamiętuje ją;
                ; kolejne linie dostają ją w miejscu %nazwa%. Np. numer klawisza wagi przepisywany do kodu PLU i komentarza.
                if (parts.Length < 5) {
                    MacroBox report "Linia WEBREAD wymaga okna, pola, nazwy i wyrażenia.`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                value := ""
                problem := WebValue(Trim(parts[2]), Trim(parts[3]), Trim(parts[5]), parts.Length >= 6 ? Integer(Trim(parts[6])) : 10000, &value)
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                vars[Trim(parts[4])] := value
            case "WEBDBLROW":
                ; WEBDBLROW|okno|siatka albo *|tekst|limit w ms: klika dwa razy pierwszy wiersz tabeli z tym tekstem
                ; (liczba stoi osobno, więc 2405610000000 nie trafi w dłuższy kod), np. EAN na liście kodów artykułu.
                problem := parts.Length < 4 ? "Linia WEBDBLROW wymaga okna, siatki i tekstu."
                    : WebDoubleClickRow(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), parts.Length >= 5 ? Integer(Trim(parts[5])) : 10000)
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
            case "WEBKEY":
                ; WEBKEY|okno|pole|klawisze|limit w ms: ustawia fokus w polu i naciska prawdziwe klawisze (np. {Enter}, {Tab})
                problem := parts.Length < 4 ? "Linia WEBKEY wymaga okna, pola i klawiszy."
                    : WebKey(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), parts.Length >= 5 ? Integer(Trim(parts[5])) : 10000)
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!"
                    return
                }
            case "WEBCLOSE":
                ; WEBCLOSE|okno|siatka|kolumna numeru|kolumna identyfikacji|przycisk zamknięcia|okno pytania|tekst pytania|odpowiedź|pole wydruku|zatwierdzenie|pierwsza strona|następna strona
                if (parts.Length < 11 || results.Count = 0) {
                    MacroBox report "Linia WEBCLOSE wymaga wszystkich pól i wyników wcześniejszego WEBROWS.", "Błąd makra", "Icon!"
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
                    MacroBox report, "Makro zatrzymane", "Icon!" AlwaysOnTop
                    return
                }
            case "WEBMARK":
                ; WEBMARK|okno|element strony: oznacza bieżącą stronę, żeby WEBIFROW brał pod uwagę dopiero stronę po przeładowaniu
                if (parts.Length < 3 || !MarkPage(Trim(parts[2]), Trim(parts[3]))) {
                    MacroBox report "Nie ma strony z elementem do oznaczenia.`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
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
                    MacroBox report "Linia WEBIFROW wymaga okna, strony, siatki, przycisku, numeru, tekstu i etykiety.", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                rowButton := ""
                seen := ""
                problem := WebIfRow(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), Trim(parts[5]), Integer(Trim(parts[6])), Trim(parts[7]),
                    parts.Length >= 9 ? Integer(Trim(parts[9])) : 30000, &rowButton, &seen)
                rowLabel := Trim(parts[8])
                if (problem = "") {
                    vars["ROW"] := rowButton ; kolejne linie klikają przycisk tego wiersza jako %ROW%
                    handledRows[rowLabel] := handledRows.Get(rowLabel, 0) + 1
                }
                if (problem = "SKIP") {
                    ; Podsumowanie na końcu: ile plików obsłużono (obsłużony plik znika z listy, więc liczymy przejścia,
                    ; nie numer wiersza) i co było widać na liście przy przeskoku.
                    report .= Trim(parts[7]) ": obsłużone pliki: " handledRows.Get(rowLabel, 0) " (" seen ")`n"
                    target := LabelLine(lines, Trim(parts[8]), index)
                    if (target = 0) {
                        MacroBox report "Brak linii LABEL|" Trim(parts[8]) " po linii " index ".", "Błąd makra", "Icon!" AlwaysOnTop
                        return
                    }
                    index := target
                    continue
                }
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
            case "WEBIFVALUE":
                ; WEBIFVALUE|okno|pole|wyrażenie regularne|etykieta|limit w ms: czeka do limitu, aż pole ma pasującą wartość.
                ; Pasuje: idzie dalej; nie pasuje (albo pola nie ma): przeskakuje do LABEL|etykieta. Np. wypełniony panel
                ; istniejącej operacji promocji, którą trzeba najpierw usunąć.
                if (parts.Length < 5) {
                    MacroBox report "Linia WEBIFVALUE wymaga okna, pola, wyrażenia i etykiety.", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                if (WebValue(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), parts.Length >= 6 ? Integer(Trim(parts[6])) : 3000) != "") {
                    target := LabelLine(lines, Trim(parts[5]), index)
                    if (target = 0) {
                        MacroBox report "Brak linii LABEL|" Trim(parts[5]) " po linii " index ".", "Błąd makra", "Icon!" AlwaysOnTop
                        return
                    }
                    index := target
                    continue
                }
            case "WEBIFSEEN":
                ; WEBIFSEEN|okno|element|etykieta|limit w ms|opis (od 0.55): element widoczny w limicie = idzie dalej; bez niego
                ; dopisuje opis do podsumowania i przeskakuje do LABEL|etykieta. Np. pusty wynik statystyki sprzedaży
                ; (AfficheResultat bez przycisku Excel): ta część okresu nie ma pliku, makro bierze następną.
                if (parts.Length < 4) {
                    MacroBox report "Linia WEBIFSEEN wymaga okna, elementu i etykiety.", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                if (WebWait(Trim(parts[2]), Trim(parts[3]), "", parts.Length >= 5 ? Integer(Trim(parts[5])) : 3000) != "") {
                    if (parts.Length >= 6 && Trim(parts[6]) != "")
                        report .= Trim(parts[6]) "`n"
                    target := LabelLine(lines, Trim(parts[4]), index)
                    if (target = 0) {
                        MacroBox report "Brak linii LABEL|" Trim(parts[4]) " po linii " index ".", "Błąd makra", "Icon!" AlwaysOnTop
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
                    MacroBox report "Linia WEBFREECODE wymaga okna, przycisku, pola kodu, pola sprawdzanego, kodu i liczby prób.", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                found := ""
                problem := WebFreeCode(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), Trim(parts[5]), Integer(Trim(parts[6])),
                    Integer(Trim(parts[7])), parts.Length >= 8 ? Integer(Trim(parts[8])) : 3000, &found)
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                ; Bez wpisu do podsumowania: na końcu zwykłe „Zakończono”, jak przy innych makrach.
            case "FILEOPEN":
                ; FILEOPEN|ścieżka pliku|limit w ms: czeka na okno wyboru pliku Windows (np. po Import w Mercalys), wpisuje pełną
                ; ścieżkę prawdziwymi klawiszami i zatwierdza. Brak pliku na dysku zatrzymuje makro przed kliknięciem.
                ; Zapamiętuje otwarte okna PDF, żeby PDFCLOSE zamknął tylko nowy.
                pdfSeen := PdfSnapshot()
                problem := parts.Length < 2 ? "Linia FILEOPEN wymaga ścieżki pliku."
                    : FileOpenDialog(Trim(parts[2]), parts.Length >= 3 ? Integer(Trim(parts[3])) : 30000)
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
            case "PDFCLOSE":
                ; PDFCLOSE|limit w ms: czeka na nowe okno PDF (niewidoczne przy starcie makra albo przy ostatnim FILEOPEN) i je zamyka
                problem := PdfCloseNew(parts.Length >= 2 ? Integer(Trim(parts[2])) : 120000, pdfSeen)
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
            case "IFDIALOG":
                ; IFDIALOG|okno|tekst (kilka po średniku)|przycisk|etykieta|limit w ms: jak WEBDIALOG, ale gdy okno z tym tekstem
                ; się nie pojawi, przeskakuje do LABEL|etykieta. Np. „Niektóre przesłane artykuły są w stanie błędu” po imporcie.
                if (parts.Length < 5) {
                    MacroBox report "Linia IFDIALOG wymaga okna, tekstu, przycisku i etykiety.", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                clicked := false
                problem := OptionalDialog(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), parts.Length >= 6 ? Integer(Trim(parts[6])) : 3000, &clicked)
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                if !clicked {
                    target := LabelLine(lines, Trim(parts[5]), index)
                    if (target = 0) {
                        MacroBox report "Brak linii LABEL|" Trim(parts[5]) " po linii " index ".", "Błąd makra", "Icon!" AlwaysOnTop
                        return
                    }
                    index := target
                    continue
                }
            case "WEBIFDBLROW":
                ; WEBIFDBLROW|okno|siatka albo *|tekst|etykieta|limit w ms: jak WEBDBLROW, ale bez wiersza z tym tekstem
                ; przeskakuje do LABEL|etykieta. Np. kolejny błąd „marża ujemna” na liście błędów importu.
                if (parts.Length < 5) {
                    MacroBox report "Linia WEBIFDBLROW wymaga okna, siatki, tekstu i etykiety.", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                if (WebDoubleClickRow(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), parts.Length >= 6 ? Integer(Trim(parts[6])) : 3000) != "") {
                    target := LabelLine(lines, Trim(parts[5]), index)
                    if (target = 0) {
                        MacroBox report "Brak linii LABEL|" Trim(parts[5]) " po linii " index ".", "Błąd makra", "Icon!" AlwaysOnTop
                        return
                    }
                    index := target
                    continue
                }
            case "WEBTEXTCLICK":
                ; WEBTEXTCLICK|okno|tekst|limit w ms: klika element strony z tym tekstem, który nie ma id ani nie jest przyciskiem
                ; (np. napis przy polu wyboru). Gdy obok napisu jest jedno pole wyboru, zaznacza je (zaznaczonego nie odznacza).
                problem := parts.Length < 3 ? "Linia WEBTEXTCLICK wymaga okna i tekstu."
                    : WebTextClick(Trim(parts[2]), Trim(parts[3]), parts.Length >= 4 ? Integer(Trim(parts[4])) : 10000)
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
            case "WEBROWBUTTON":
                ; WEBROWBUTTON|okno|siatka|wyrażenie regularne wiersza|przycisk w wierszu|etykieta|opis|następna strona|ile stron|limit w ms
                ; (od 0.56): klika przycisk (koniec id `<siatka>__ctlN_<przycisk>`) w pierwszym widocznym wierszu, którego tekst
                ; pasuje. Bez takiego wiersza przechodzi kolejne strony (najwyżej „ile stron”), a potem dopisuje opis do
                ; podsumowania i przeskakuje do LABEL|etykieta. Np. plik zmiany cen w historii pobierania danych.
                if (parts.Length < 7) {
                    MacroBox report "Linia WEBROWBUTTON wymaga okna, siatki, wyrażenia, przycisku, etykiety i opisu.", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                seenRows := ""
                problem := WebRowButton(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), Trim(parts[5]),
                    parts.Length >= 8 ? Trim(parts[8]) : "", parts.Length >= 9 && Trim(parts[9]) != "" ? Integer(Trim(parts[9])) : 1,
                    parts.Length >= 10 ? Integer(Trim(parts[10])) : 30000, &seenRows)
                if (problem = "SKIP") {
                    report .= "Nie znaleziono: " Trim(parts[7]) (seenRows != "" ? "`n" seenRows : "") "`n"
                    target := LabelLine(lines, Trim(parts[6]), index)
                    if (target = 0) {
                        MacroBox report "Brak linii LABEL|" Trim(parts[6]) " po linii " index ".", "Błąd makra", "Icon!" AlwaysOnTop
                        return
                    }
                    index := target
                    continue
                }
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                report .= "Obsłużono: " Trim(parts[7]) "`n"
            case "WEBGRIDWAIT":
                ; WEBGRIDWAIT|okno|element strony|warunek|wyrażenie|etykieta|opis|limit w ms (od 0.56): czeka na stan wierszy tabeli
                ; (wiersze z setSelectedLigne) na stronie z elementem. `all` = co najmniej jeden wiersz i każdy pasuje do wyrażenia
                ; (np. Stan „Zaznaczony”); pusta tabela przez 3 s = opis do podsumowania i przeskok do LABEL|etykieta.
                ; `none` = tabela bez wierszy przez 1 s (np. Różne ceny po wyrównaniu), a z wyrażeniem: każdy pozostały wiersz
                ; do niego pasuje (artykuły w promocji zostają w tabeli). `page` (od 0.60) = jak `all`, ale wyrażenie sprawdza
                ; tekst całej strony (np. „Suma wyboru” równa „Suma linii”), bo zaznaczenie wiersza widać tylko kolorem.
                if (parts.Length < 4) {
                    MacroBox report "Linia WEBGRIDWAIT wymaga okna, elementu i warunku.", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                problem := WebGridWait(Trim(parts[2]), Trim(parts[3]), StrLower(Trim(parts[4])), parts.Length >= 5 ? Trim(parts[5]) : "",
                    parts.Length >= 8 ? Integer(Trim(parts[8])) : 60000)
                if (problem = "EMPTY" && parts.Length >= 6 && Trim(parts[6]) != "") {
                    if (parts.Length >= 7 && Trim(parts[7]) != "")
                        report .= Trim(parts[7]) "`n"
                    target := LabelLine(lines, Trim(parts[6]), index)
                    if (target = 0) {
                        MacroBox report "Brak linii LABEL|" Trim(parts[6]) " po linii " index ".", "Błąd makra", "Icon!" AlwaysOnTop
                        return
                    }
                    index := target
                    continue
                }
                if (problem = "EMPTY")
                    problem := "Tabela jest pusta."
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
            case "WEBGOTO":
                ; WEBGOTO|etykieta (od 0.65): przeskok do LABEL|etykieta niżej w makrze
                target := parts.Length >= 2 ? LabelLine(lines, Trim(parts[2]), index) : 0
                if (target = 0) {
                    MacroBox report "Brak linii LABEL dla: " line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                index := target
                continue
            case "WEBNOROW":
                ; WEBNOROW|okno|siatka|wyrażenie regularne|komunikat|limit w ms (od 0.65): zatrzymuje makro z komunikatem, gdy
                ; lista ma wiersz pasujący do wyrażenia (np. po integracji został plik naszego bonu)
                if (parts.Length < 5) {
                    MacroBox report "Linia WEBNOROW wymaga okna, siatki, wyrażenia i komunikatu.", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                missing := false
                problem := WebPickRowMatching(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), parts.Length >= 6 ? Integer(Trim(parts[6])) : 30000, &missing)
                if (problem = "" || !missing) {
                    MacroBox report (problem = "" ? Trim(parts[5]) : problem) "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
            case "WEBALERT":
                ; WEBALERT|okno|tekst (kilka po średniku)|przycisk|limit w ms (od 0.65): czeka na okno komunikatu z tym tekstem (w treści
                ; albo w adresie) i zamyka je przyciskiem; bez okna w limicie makro staje
                if (parts.Length < 4) {
                    MacroBox report "Linia WEBALERT wymaga okna, tekstu i przycisku.", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                shown := ""
                problem := WebAlert(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), parts.Length >= 5 ? Integer(Trim(parts[5])) : 60000, &shown)
                if (shown != "")
                    report .= "Komunikat Mercalys: " shown "`n"
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
            case "WEBORDERKNOWN":
                ; WEBORDERKNOWN|dostawca|numer (od 0.65): zamówienie dostawy znane z wcześniejszego przebiegu (plik bonu już wszedł)
                if (parts.Length >= 3 && RegExMatch(Trim(parts[3]), "^\d+$")) {
                    orders[Trim(parts[2])] := Trim(parts[3])
                    vars["ORDER" Trim(parts[2])] := Trim(parts[3])
                }
            case "WEBORDERPICK":
                ; WEBORDERPICK|okno|przyciski strzałki po przecinku|dostawca|nazwa|pochodzenie (kilka po średniku)|dziś RRRRMMDD|etykieta|limit w ms
                ; (od 0.65): na liście zamówień oczekujących wybiera zamówienie dostawcy o tym pochodzeniu z datą dostawy dziś, a bez niego
                ; z najbliższą wcześniejszą, potem najbliższą późniejszą, przenosi je strzałką do wybranych i sprawdza, że stoi tam samo. Bez takiego zamówienia
                ; przeskakuje do LABEL|etykieta. Lista, której nie umie odczytać, zatrzymuje makro (pusty kod tworzymy tylko na pewno).
                if (parts.Length < 8) {
                    MacroBox report "Linia WEBORDERPICK wymaga okna, przycisków, dostawcy, nazwy, pochodzenia, daty i etykiety.", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                order := ""
                note := ""
                problem := WebOrderPick(Trim(parts[2]), StrSplit(Trim(parts[3]), ","), Trim(parts[4]), Trim(parts[5]), Trim(parts[6]), Trim(parts[7]),
                    parts.Length >= 9 ? Integer(Trim(parts[9])) : 30000, &order, &note)
                if (note != "")
                    report .= note "`n"
                if (problem = "NONE") {
                    target := LabelLine(lines, Trim(parts[8]), index)
                    if (target = 0) {
                        MacroBox report "Brak linii LABEL|" Trim(parts[8]) " po linii " index ".", "Błąd makra", "Icon!" AlwaysOnTop
                        return
                    }
                    index := target
                    continue
                }
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ".", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                pickedOrder := order
            case "WEBORDERREAD":
                ; WEBORDERREAD|okno|przycisk strzałki|dostawca|nazwa|limit w ms (od 0.65): w wybranych zamówieniach musi być dokładnie jedno,
                ; tego dostawcy (wybrane wcześniej albo utworzone pustym kodem); jego numer idzie do wyniku (dostawca=numer)
                ; i do kolejnych linii jako %ORDER<dostawca>%.
                if (parts.Length < 5) {
                    MacroBox report "Linia WEBORDERREAD wymaga okna, przycisku, dostawcy i nazwy.", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                order := ""
                problem := WebOrderRead(Trim(parts[2]), Trim(parts[3]), Trim(parts[4]), Trim(parts[5]), pickedOrder,
                    parts.Length >= 6 ? Integer(Trim(parts[6])) : 30000, &order)
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ".", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                orders[Trim(parts[4])] := order
                vars["ORDER" Trim(parts[4])] := order
                MacroCollected.codes.Push(Trim(parts[4]) "=" order)
                report .= "Dostawca " Trim(parts[4]) ": " (pickedOrder = "" ? "utworzone puste zamówienie " : "zamówienie ") order "`n"
                pickedOrder := ""
            case "WEBIFORDER":
                ; WEBIFORDER|dostawca|etykieta (od 0.65): bez zamówienia tego dostawcy przeskakuje do LABEL|etykieta
                if (parts.Length < 3) {
                    MacroBox report "Linia WEBIFORDER wymaga dostawcy i etykiety.", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                if !orders.Has(Trim(parts[2])) {
                    target := LabelLine(lines, Trim(parts[3]), index)
                    if (target = 0) {
                        MacroBox report "Brak linii LABEL|" Trim(parts[3]) " po linii " index ".", "Błąd makra", "Icon!" AlwaysOnTop
                        return
                    }
                    index := target
                    continue
                }
            case "WEBORDERS":
                ; WEBORDERS|komunikat (od 0.65): zatrzymuje makro z komunikatem, gdy nie ma żadnego zamówienia
                if (orders.Count = 0) {
                    MacroBox report (parts.Length >= 2 ? Trim(parts[2]) : "Nie ma żadnego zamówienia."), "Makro zatrzymane", "Icon!" AlwaysOnTop
                    return
                }
            case "WEBORDERROW":
                ; WEBORDERROW|okno|siatka|kolumna numeru|dostawca|nazwa|pierwsza strona|następna strona|limit w ms (od 0.65): zaznacza na liście
                ; zamówienie tego dostawcy (strona po stronie) i sprawdza, że wiersz jest tego dostawcy
                supplierKey := parts.Length >= 8 ? Trim(parts[5]) : ""
                if (parts.Length < 8 || !orders.Has(supplierKey)) {
                    MacroBox report "Linia WEBORDERROW wymaga wszystkich pól i numeru zamówienia dostawcy.", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                step := {title: Trim(parts[2]), grid: Trim(parts[3]), numberColumn: Integer(Trim(parts[4])), firstPage: Trim(parts[7]), nextPage: Trim(parts[8])}
                problem := WebOrderRow(step, orders[supplierKey], supplierKey, Trim(parts[6]), parts.Length >= 9 ? Integer(Trim(parts[9])) : 10000)
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ".", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
            case "WEBERRORS":
                ; WEBERRORS|okno|element strony|dozwolone (fragmenty po średniku)|limit w ms (od 0.65): każdy wiersz listy błędów musi mieć
                ; któryś z fragmentów (bez wielkości liter); inny błąd zatrzymuje makro i trafia do opisu
                if (parts.Length < 4) {
                    MacroBox report "Linia WEBERRORS wymaga okna, elementu i dozwolonych błędów.", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                summary := ""
                problem := WebErrors(Trim(parts[2]), Trim(parts[3]), StrSplit(Trim(parts[4]), ";"), parts.Length >= 5 ? Integer(Trim(parts[5])) : 30000, &summary)
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ".", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                report .= summary "`n"
            case "WEBRESULT":
                ; WEBRESULT|numer zamówienia|dostawca|Nr BL (od 0.65): para dla WEBHEADERS i WEBCLOSE bez wcześniejszego WEBROWS
                if (parts.Length < 4) {
                    MacroBox report "Linia WEBRESULT wymaga numeru zamówienia, dostawcy i Nr BL.", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                wanted[ListKey(parts[4], parts[3])] := {number: Trim(parts[4]), code: Trim(parts[3])}
                results[ListKey(parts[4], parts[3])] := Trim(parts[2])
            case "STOP":
                ; STOP|komunikat (od 0.68): kończy makro błędem, np. po komunikacie programu, który wymaga człowieka
                MacroBox report (parts.Length >= 2 ? Trim(parts[2]) : "Makro zatrzymane.") "`n`nMakro zatrzymane na linii " index ".", "Błąd makra", "Icon!" AlwaysOnTop
                return
            case "WEBVATCODE":
                ; WEBVATCODE|EAN|kod stawki (od 0.68): stawka artykułu dla WEBFIXCN; pusta linia bez EAN-u czyści listę
                if (parts.Length < 3)
                    vatCodes := Map()
                else
                    vatCodes[LTrim(Trim(parts[2]), "0")] := Trim(parts[3])
            case "WEBFIXCN":
                ; WEBFIXCN|okno|adres listy|element listy|nagłówek kolumny|dobra wartość|adres karty|pole EAN|pole CN|kod CN|pole VAT|
                ; zatwierdzenie|okno pytania|tekst pytania|odpowiedź|powrót (od 0.68): na liście artykułów każdy wiersz, którego
                ; kolumna ma inną wartość niż dobra (Ano. inne niż N), otwiera dwuklikiem, wpisuje kod CN z Enterem i kod stawki
                ; z WEBVATCODE, sprawdza oba pola, zatwierdza i wraca na listę, aż błędów nie ma. Artykuł spoza listy stawek
                ; albo z błędem także po poprawce zatrzymuje makro.
                if (parts.Length < 16) {
                    MacroBox report "Linia WEBFIXCN wymaga wszystkich pól.", "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
                step := {title: Trim(parts[2]), grid: "*", list: Trim(parts[3]), listElement: Trim(parts[4]), header: Trim(parts[5]), good: Trim(parts[6]),
                    card: Trim(parts[7]), ean: Trim(parts[8]), cn: Trim(parts[9]), cnCode: Trim(parts[10]), vat: Trim(parts[11]),
                    save: Trim(parts[12]), dialog: Trim(parts[13]), dialogText: Trim(parts[14]), dialogButton: Trim(parts[15]), back: Trim(parts[16])}
                fixedCount := 0
                problem := WebFixCn(step, vatCodes, &fixedCount)
                if (fixedCount > 0)
                    report .= "Poprawione artykuły z błędem: " fixedCount "`n"
                if (problem != "") {
                    MacroBox report problem "`n`nMakro zatrzymane na linii " index ":`n" line, "Błąd makra", "Icon!" AlwaysOnTop
                    return
                }
            case "WEBSHOW":
                ; WEBSHOW|fragment tytułu okna|elementy po przecinku: tylko pokazuje ich HTML (do Schowka), niczego nie klika
                if (parts.Length >= 3)
                    MacroBox WebShow(Trim(parts[2]), Trim(parts[3])), "HTML elementów (skopiowano do Schowka)"
                return
            default:
                MacroBox "Nieznane polecenie: " cmd "`n`nMakro zatrzymane na linii " index ".`nByć może ten odtwarzacz jest za stary.", "Błąd makra", "Icon!"
                return
        }
        Sleep 50
    }
    
    if (report != "")
        MacroBox report, "Makro zakończone", "Iconi" AlwaysOnTop
    else
        Notify "Zakończono", "Makro ze schowka zostało wykonane."
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
    WaitForStableWindows(title, timeoutMs)
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

; Jak WinCloseAll, ale każdy dokument najpierw idzie do drukarki domyślnej przez okno drukowania Acrobata (Ctrl+P, Enter),
; a potem jest zamykany (Ctrl+W): tak samo dla osobnych okien i dla kart w jednym oknie.
PdfPrintAll(title, timeoutMs) {
    WaitForStableWindows(title, timeoutMs)
    printed := 0
    failed := ""
    for hwnd in WinGetList(title) {
        Loop 100 {
            if !WinExist("ahk_id " hwnd)
                break
            name := ""
            try name := WinGetTitle(hwnd)
            ; Po zamknięciu ostatniej karty Acrobat zostaje pustym oknem („Adobe Acrobat Reader”): to nie bon, tylko je zamykamy.
            if !PdfHasDocument(name) {
                try WinClose hwnd
                WinWaitClose("ahk_id " hwnd, , 5)
                break
            }
            problem := PrintActivePdf(hwnd)
            if (problem != "") {
                failed .= name ": " problem "`n"
                break
            }
            printed += 1
            try WinActivate hwnd
            Send "^w"
            ; Ten sam tytuł po zamknięciu karty = dokument się nie zamknął; dalej nie drukujemy go drugi raz.
            changed := false
            Loop 30 {
                Sleep 100
                if !WinExist("ahk_id " hwnd) {
                    changed := true
                    break
                }
                current := ""
                try current := WinGetTitle(hwnd)
                if (current != name) {
                    changed := true
                    break
                }
            }
            if !changed
                break
        }
    }
    text := "Wydrukowane bony: " printed
    if (failed != "")
        text .= "`nNIE WYDRUKOWANE (PDF zostaje otwarty):`n" failed
    return text
}

; Okno z dokumentem ma w tytule nazwę pliku przed „Adobe Acrobat…”; puste okno ma sam tytuł programu.
PdfHasDocument(name) => Trim(name) != "" && !RegExMatch(name, "i)^\s*Adobe Acrobat")

PrintActivePdf(hwnd) {
    try {
        WinActivate hwnd
        WinWaitActive "ahk_id " hwnd, , 3
        pid := WinGetPID(hwnd)
    } catch
        return "nie da się przełączyć na okno PDF"
    dialogs := "ahk_class #32770 ahk_pid " pid
    Send "^p"
    dialog := WinWait(dialogs, , 15)
    if !dialog
        return "nie otworzyło się okno drukowania"
    Sleep 700 ; okno drukowania wczytuje drukarki, zanim przyjmie Enter
    try WinActivate "ahk_id " dialog
    Send "{Enter}"
    if !WinWaitClose("ahk_id " dialog, , 30)
        return "okno drukowania się nie zamknęło"
    ; Postęp drukowania to też okno Acrobata: zamknięcie dokumentu przed jego końcem przerwałoby wydruk.
    deadline := A_TickCount + 120000
    while (WinExist(dialogs) && A_TickCount < deadline)
        Sleep 300
    return ""
}

WaitForStableWindows(title, timeoutMs) {
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
}

; --- Excel: skoroszyt otwarty przez Mercalys zapisany pod podaną ścieżką ---

ExcelFormat(ext) {
    static formats := Map("xlsx", 51, "xlsm", 52, "xlsb", 50, "xls", 56, "csv", 6)
    ext := StrLower(ext)
    return formats.Has(ext) ? formats[ext] : 0
}

; Obiekt okna skoroszytu (klasa EXCEL7) przez OBJID_NATIVEOM: tak widać każdy uruchomiony Excel,
; a ComObjActive zwraca tylko pierwszy (Mercalys może otworzyć raport we własnym Excelu).
ExcelWindowObject(ctrl) {
    iid := Buffer(16)
    DllCall("ole32\CLSIDFromString", "WStr", "{00020400-0000-0000-C000-000000000046}", "Ptr", iid) ; IDispatch
    ptr := 0
    if (DllCall("oleacc\AccessibleObjectFromWindow", "Ptr", ctrl, "UInt", 0xFFFFFFF0, "Ptr", iid, "Ptr*", &ptr) != 0 || !ptr)
        return ""
    return ComValue(9, ptr)
}

; Otwarte skoroszyty wszystkich Exceli: {key, book, app}; key = okno Excela + pełna nazwa skoroszytu.
ExcelWorkbooks() {
    books := []
    apps := Map()
    for hwnd in WinGetList("ahk_class XLMAIN") {
        try controls := WinGetControlsHwnd(hwnd)
        catch
            continue
        for ctrl in controls {
            try {
                if (WinGetClass(ctrl) != "EXCEL7")
                    continue
                win := ExcelWindowObject(ctrl)
                if !IsObject(win)
                    continue
                app := win.Application
                appKey := app.Hwnd
                if !apps.Has(appKey) {
                    apps[appKey] := true
                    for wb in app.Workbooks
                        books.Push({key: appKey ":" wb.FullName, book: wb, app: app})
                }
                break
            }
        }
    }
    return books
}

ExcelSnapshot() {
    seen := Map()
    for item in ExcelWorkbooks()
        seen[item.key] := true
    return seen
}

; Zwraca "", gdy zapisano, a inaczej opis problemu.
; Folder zapisu raportu (od 0.61): brakujący zakładamy razem z folderami nad nim. Zwraca "" albo opis problemu.
EnsureFolder(dir) {
    if (dir = "")
        return "Ścieżka zapisu nie ma folderu. Podaj pełną ścieżkę pliku, np. N:\Raporty\sprzedaz.xlsx."
    if DirExist(dir)
        return ""
    drive := ""
    SplitPath dir, , , , , &drive
    if (drive != "" && !DirExist(drive "\"))
        return "Nie ma dysku `"" drive "`" albo nie ma do niego dostępu, więc nie da się założyć folderu `"" dir "`"."
    ; Ten sam folder może w tej chwili zakładać inne stanowisko: liczy się to, że po próbie folder jest
    try DirCreate dir
    catch as err {
        if DirExist(dir)
            return ""
        return "Nie udało się założyć folderu `"" dir "`": " err.Message
    }
    return DirExist(dir) ? "" : "Nie udało się założyć folderu `"" dir "`" (brak uprawnień?)."
}

ExcelSave(path, timeoutMs, seen) {
    SplitPath path, , &dir, &ext
    format := ExcelFormat(ext)
    if !format
        return "Nieznany format pliku `"" path "`". Użyj końcówki .xlsx, .xlsm, .xlsb, .xls albo .csv."
    problem := EnsureFolder(dir)
    if (problem != "")
        return problem
    deadline := A_TickCount + timeoutMs
    Loop {
        for item in ExcelWorkbooks() {
            if seen.Has(item.key)
                continue
            ready := false
            try ready := item.app.Ready
            if ready
                return ExcelSaveWorkbook(item, path, format, seen)
        }
        if (A_TickCount >= deadline)
            return "Excel nie otworzył nowego skoroszytu w ciągu " Round(timeoutMs / 1000) " s."
        Sleep 500
    }
}

ExcelSaveWorkbook(item, path, format, seen) {
    seen[item.key] := true ; nawet po błędzie kolejny EXCELSAVE nie bierze tego samego skoroszytu
    app := item.app
    wb := item.book
    Sleep 1000 ; skoroszyt dopiero się wczytał
    alerts := true
    try alerts := app.DisplayAlerts
    message := ""
    Loop 5 { ; zajęty Excel odrzuca wywołanie, więc kilka prób
        try {
            app.DisplayAlerts := false ; bez pytania o nadpisanie i format
            wb.SaveAs(path, format)
            message := ""
            break
        } catch as err {
            message := err.Message
            Sleep 1000
        }
    }
    if (message != "") {
        try app.DisplayAlerts := alerts
        return "Excel nie zapisał pliku `"" path "`":`n" message
    }
    try seen[app.Hwnd ":" wb.FullName] := true
    try wb.Close(false)
    wb := ""
    try app.DisplayAlerts := alerts
    ; Excel otwarty dla raportu zostaje pusty: bez innych skoroszytów zamykamy go, a cudzych plików nie ruszamy
    count := -1
    try count := app.Workbooks.Count
    if (count = 0)
        try app.Quit()
    return ""
}

; --- PDF: raport z Mercalys otwarty w Acrobacie zapisany pod podaną ścieżką ---

PdfWindows() => WinGetList("ahk_class AcrobatSDIWindow")

; Nowe okno PDF (spoza seen) zamknięte, gdy się wczyta. Zwraca "" albo opis problemu.
PdfCloseNew(timeoutMs, seen) {
    deadline := A_TickCount + timeoutMs
    Loop {
        for hwnd in PdfWindows() {
            title := ""
            try title := WinGetTitle(hwnd)
            if (title = "" || seen.Has(hwnd ":" title))
                continue
            seen[hwnd ":" title] := true
            Sleep 1500 ; dokument jeszcze się wczytuje, a zamknięcie w trakcie potrafi zostawić puste okno
            try WinClose hwnd
            return WinWaitClose("ahk_id " hwnd, , 10) ? "" : "PDF `"" title "`" się nie zamknął, zamknij go ręcznie."
        }
        if (A_TickCount >= deadline)
            return "Nie otworzył się nowy PDF w ciągu " Round(timeoutMs / 1000) " s."
        Sleep 300
    }
}

; Okno wyboru pliku Windows (#32770 z polem nazwy) programu Mercalys albo Internet Explorera: pełna ścieżka prawdziwymi
; klawiszami (tekst ustawiony z boku okno pokazuje, ale go nie bierze) i Enter. Zwraca "" albo opis problemu.
FileOpenDialog(path, timeoutMs) {
    if !FileExist(path)
        return "Nie ma pliku `"" path "`". Zapisz go najpierw w folderze (albo popraw ścieżkę folderu w aplikacji)."
    deadline := A_TickCount + timeoutMs
    dialog := 0
    Loop {
        for hwnd in WinGetList("ahk_class #32770") {
            exe := ""
            try exe := StrLower(WinGetProcessName(hwnd))
            if (exe != "mercalys.exe" && exe != "iexplore.exe")
                continue
            try {
                ControlGetHwnd("Edit1", hwnd)
                dialog := hwnd
                break
            }
        }
        if dialog
            break
        if (A_TickCount >= deadline)
            return "Nie otworzyło się okno wyboru pliku w ciągu " Round(timeoutMs / 1000) " s."
        Sleep 200
    }
    try {
        WinActivate dialog
        WinWaitActive("ahk_id " dialog, , 5)
        Sleep 300 ; okno wczytuje folder, zanim przyjmie tekst
        ControlFocus "Edit1", dialog
        Sleep 200
        Send "^a"
        SendText path
        Sleep 300
        if (ControlGetText("Edit1", dialog) != path)
            return "Nie udało się wpisać ścieżki w okno wyboru pliku."
        Send "{Enter}"
    } catch as err {
        return "Nie udało się wpisać ścieżki w okno wyboru pliku: " err.Message
    }
    return WinWaitClose("ahk_id " dialog, , 15) ? "" : "Okno wyboru pliku się nie zamknęło (plik nie został przyjęty?)."
}
PdfMercalysTemp() => "N:\AVISOCONFIG\TEMP"

PdfSnapshot() {
    seen := Map()
    for hwnd in PdfWindows()
        try seen[hwnd ":" WinGetTitle(hwnd)] := true
    return seen
}

; Zwraca "", gdy zapisano, a inaczej opis problemu.
PdfSave(path, timeoutMs, seen) {
    SplitPath path, , &dir, &ext
    if (StrLower(ext) != "pdf")
        return "Plik `"" path "`" musi kończyć się na .pdf."
    problem := EnsureFolder(dir)
    if (problem != "")
        return problem
    since := DateAdd(A_Now, -60, "Seconds") ; plik PDF powstaje dopiero po kliknięciu w Mercalys
    deadline := A_TickCount + timeoutMs
    hwnd := 0
    title := ""
    Loop {
        for h in PdfWindows() {
            t := ""
            try t := WinGetTitle(h)
            if (t != "" && !seen.Has(h ":" t)) {
                hwnd := h
                title := t
                break
            }
        }
        if hwnd
            break
        if (A_TickCount >= deadline)
            return "Nie otworzył się nowy PDF w ciągu " Round(timeoutMs / 1000) " s."
        Sleep 500
    }
    seen[hwnd ":" title] := true
    Sleep 2000 ; dokument jeszcze się wczytuje

    ; Najpierw kopia pliku, który otworzył Acrobat (bez okien), a bez niego „Zapisz jako”.
    source := PdfSourceFile(title, since)
    copied := false
    if (source != "") {
        try {
            FileCopy source, path, 1
            copied := true
        }
    }
    if !copied {
        problem := PdfSaveAs(hwnd, path)
        if (problem != "")
            return problem "`nOkno PDF: " title
    }
    if !FileExist(path) || DateDiff(FileGetTime(path, "M"), since, "Seconds") < 0
        return "Nie powstał plik `"" path "`".`nOkno PDF: " title
    try WinClose hwnd
    WinWaitClose("ahk_id " hwnd, , 5)
    return ""
}

; Plik PDF o nazwie z tytułu okna (np. „ar-e079[1].pdf - Adobe Acrobat Reader”), zapisany po `since` w folderach
; tymczasowych Windows i Internet Explorera; "" = nie znaleziono.
PdfSourceFile(title, since) {
    if !RegExMatch(title, "i)^(.+?\.pdf)\b", &m)
        return ""
    name := m[1]
    newest := ""
    newestTime := ""
    appData := EnvGet("LOCALAPPDATA")
    ; Mercalys zapisuje wydruki w N:\AVISOCONFIG\TEMP (tam bez podfolderów, bo plików jest tam bardzo dużo)
    folders := [[PdfMercalysTemp(), ""], [A_Temp, "R"], [appData "\Microsoft\Windows\INetCache", "R"]
        , [appData "\Microsoft\Windows\Temporary Internet Files", "R"]]
    for folder in folders {
        dir := folder[1]
        if !DirExist(dir)
            continue
        try {
            Loop Files dir "\" name, folder[2] {
                time := A_LoopFileTimeModified
                if (DateDiff(time, since, "Seconds") >= 0 && (newestTime = "" || DateDiff(time, newestTime, "Seconds") > 0)) {
                    newest := A_LoopFileFullPath
                    newestTime := time
                }
            }
        }
    }
    return newest
}

; Ctrl+Shift+S w oknie PDF i ścieżka w polu nazwy pliku. Stary plik usuwamy wcześniej, żeby nie było pytania o zamianę.
PdfSaveAs(hwnd, path) {
    try WinActivate hwnd
    if !WinWaitActive("ahk_id " hwnd, , 5)
        return "Okno PDF nie stało się aktywne."
    exe := ""
    try exe := WinGetProcessName(hwnd)
    if FileExist(path) {
        try FileDelete path
        catch
            return "Nie da się zastąpić pliku `"" path "`" (otwarty w innym programie?)."
    }
    Send "^+s"
    dialog := WinWait("ahk_class #32770 ahk_exe " exe, , 20)
    if !dialog
        return "Nie otworzyło się okno „Zapisz jako” (Ctrl+Shift+S). W Acrobacie: Edycja → Preferencje → Ogólne, wyłącz „Pokaż magazyn online przy zapisywaniu plików”."
    ; Prawdziwe klawisze: tekst ustawiony z boku (ControlSetText) okno pokazuje, ale zapisuje pod starą nazwą
    try {
        WinActivate dialog
        WinWaitActive("ahk_id " dialog, , 5)
        ControlFocus "Edit1", dialog
        Sleep 200
        Send "^a"
        SendText path
        Sleep 300
        if (ControlGetText("Edit1", dialog) != path)
            return "Nie udało się wpisać ścieżki w okno „Zapisz jako”."
        Send "{Enter}"
    } catch as err {
        return "Nie udało się wpisać ścieżki w okno „Zapisz jako”: " err.Message
    }
    deadline := A_TickCount + 60000
    while (A_TickCount < deadline) {
        if (FileExist(path) && !WinExist("ahk_id " dialog))
            return ""
        ; Pytanie o zamianę pliku tu nie powinno paść (stary plik usunięty): odpowiadamy Nie i przerywamy zapis
        for other in WinGetList("ahk_class #32770 ahk_exe " exe) {
            if (other != dialog && WinExist("ahk_id " dialog)) {
                try WinActivate other
                Send "{Esc}"
                Sleep 300
                try WinClose dialog
                return "Okno „Zapisz jako” zapytało o zamianę innego pliku, zapis przerwany."
            }
        }
        Sleep 300
    }
    return "Acrobat nie zapisał pliku w ciągu 60 s."
}

WinOpen(title, password := "") {
    hwnd := WinExist(title)
    started := false
    if !hwnd {
        if !InStr(title, "Mercalys.exe")
            return "Nie znaleziono okna `"" title "`". Uruchom program i spróbuj ponownie."
        problem := StartMercalys(title)
        if (problem != "")
            return problem
        started := true
        hwnd := WinExist(title)
    }
    if (WinGetMinMax(hwnd) = -1)
        WinRestore hwnd
    WinActivate hwnd
    if !WinWaitActive("ahk_id " hwnd, , 5)
        return "Okno `"" title "`" nie stało się aktywne."
    if InStr(title, "Mercalys.exe") {
        RememberMercalysPath(hwnd)
        return MercalysLogin(title, password, started)
    }
    return ""
}

; --- Mercalys wyłączony: uruchomienie i logowanie ---

MercalysPasswordField() => "txtZ2_MotDePasse_TxtBox"
MercalysMenu() => "mnTp_ACHAT" ; menu główne po zalogowaniu (Zakup)

; Ścieżka do Mercalys.exe zapamiętywana, gdy program działa, więc zwykle nie trzeba jej podawać.
RememberMercalysPath(hwnd) {
    try {
        path := WinGetProcessPath(hwnd)
        if (path != "" && path != RegRead(SettingsKey, "MercalysPath", ""))
            RegWrite path, "REG_SZ", SettingsKey, "MercalysPath"
    }
}

StartMercalys(title) {
    path := RegRead(SettingsKey, "MercalysPath", "")
    if (path = "" || !FileExist(path)) {
        if (RemoteId != "")
            return "Mercalys jest na tym komputerze wyłączony, a odtwarzacz nie zna jeszcze jego położenia. Uruchom tam Mercalys."
        MsgBox "Mercalys jest wyłączony, a odtwarzacz nie zna jeszcze jego położenia.`n`n"
            . "W następnym oknie wskaż Mercalys.exe albo skrót, którym go uruchamiasz. Wystarczy raz.", "Intermanage Makra", "Iconi" AlwaysOnTop
        path := FileSelect(3, , "Wskaż Mercalys.exe albo jego skrót", "Program albo skrót (*.exe; *.lnk)")
        if (path = "")
            return "Mercalys jest wyłączony. Uruchom go i spróbuj ponownie."
        RegWrite path, "REG_SZ", SettingsKey, "MercalysPath"
    }
    Notify "Uruchamiam Mercalys…"
    try Run path
    catch as error
        return "Nie udało się uruchomić Mercalys (" path "): " error.Message
    if !WinWait(title, , 120)
        return "Mercalys nie otworzył się w ciągu 2 minut."
    return ""
}

; Ekran logowania (login wpisany przez Mercalys) albo już menu główne. Po Enterze program startuje 10-15 s,
; więc czekamy na menu i chwilę dłużej, żeby kolejne kliknięcia trafiały w gotowy program. Działający Mercalys bez
; ekranu logowania idzie dalej od razu, jak dawniej (np. otwarte okno komunikatu, w którym menu nie widać).
MercalysLogin(title, password, started) {
    if !started {
        if (WebWait(title, MercalysPasswordField(), "", 0) != "")
            return ""
    } else {
        deadline := A_TickCount + 120000
        Loop {
            if (WebWait(title, MercalysMenu(), "", 0) = "") {
                Sleep 5000
                return ""
            }
            if (WebWait(title, MercalysPasswordField(), "", 0) = "")
                break
            if (A_TickCount >= deadline)
                return "Mercalys nie pokazał ani logowania, ani menu głównego w ciągu 2 minut."
            Sleep 300
        }
    }
    if (password = "") {
        Notify "Zaloguj się w Mercalys, makro ruszy samo po zalogowaniu."
        problem := WebWait(title, MercalysMenu(), "", 300000)
        if (problem != "")
            return "Nie zalogowano się w Mercalys w ciągu 5 minut."
    } else {
        problem := WebKey(title, MercalysPasswordField(), "^a{Del}", 10000)
        if (problem = "") {
            SendText password
            Send "{Enter}"
        } else
            return problem
        if (WebWait(title, MercalysMenu(), "", 90000) != "")
            return WebWait(title, MercalysPasswordField(), "", 0) = ""
                ? "Mercalys nie przyjął hasła. Popraw je w aplikacji (Do pobrania → Logowanie do Mercalys)."
                : "Mercalys nie pokazał menu głównego w ciągu 90 s od logowania."
    }
    Sleep 5000
    return ""
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
    missing := ""
    Done(ending, finished) => {finished: finished, text: "Nagłówki wpisane: " (written = "" ? "brak`n" : "`n" written)
        . (existing != "" ? "Już były: `n" existing : "") . (missing != "" ? "Nie ma na liście (już zakończone?):`n" missing : "") . (others != "" ? "INNA identyfikacja, nie zmieniono:`n" others : "") . ending}
    for key, item in wanted {
        order := results[key]
        problem := WebWait(step.title, step.open, "", 30000)
        if (problem = "")
            problem := ShowOrderRow(step, order)
        if (problem != "")
            return Done("ZATRZYMANE przy " order ": " problem, false)
        current := GridCell(step, order, step.fieldColumn)
        if (current = "?") {
            ; Powtórka zdalnego makra po przerwaniu: zakończonej już dostawy nie ma na liście, pozostałe idą dalej.
            if (RemoteId = "")
                return Done("ZATRZYMANE: nie ma dostawy " order " na liście.", false)
            missing .= order "`n"
            continue
        }
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
    return Done(others = "" ? (missing = "" ? "Wszystkie nagłówki są wpisane." : "Pozostałe nagłówki są wpisane.") : "Sprawdź dostawy z inną identyfikacją.", others = "")
}

; Data w nagłówku: prawdziwe klawisze i Tab, bo pole Mercalys formatuje wpis; po nim strona ma dalej pokazywać to zamówienie.
WriteHeaderDate(step, order) {
    if (WebValue(step.title, step.dateField, step.datePattern, 0) = "")
        return ""
    ; Klawisze bywają zgubione, gdy okno dopiero staje się aktywne; powtórka jest bezpieczna, bo zaczyna od zaznaczenia całego pola.
    Loop 3 {
        problem := WebKey(step.title, step.dateField, "^a" step.date "{Tab}", 10000)
        if (problem = "")
            problem := WebValue(step.title, step.dateField, step.datePattern, 3000)
        if (problem = "")
            return WebWait(step.title, step.field, order, 30000)
    }
    return problem
}

WriteHeader(step, order, number) {
    problem := WebPickRow(step.title, step.grid, step.numberColumn, order, 10000)
    if (problem = "")
        problem := WebClick(step.title, step.open, 10000)
    if (problem = "")
        problem := WebWait(step.title, step.field, order, 30000)
    if (problem = "" && step.dateField != "")
        problem := WriteHeaderDate(step, order)
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
    ; Makro z przeglądarki zatwierdził już zlecający, a przy komputerze nikt by okna nie zamknął.
    if (RemoteId = "" && MsgBox("Zamknąć dostawy: " wanted.Count "?`nTego nie da się cofnąć.`n`n" names, "Zamknięcie dostaw", "YesNo Icon? Default2" AlwaysOnTop) != "Yes")
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
        if (RemoteId != "")
            MacroCollected.codes.Push("zamkniete=" order)
        count += 1
    }
    return Done(skipped = "" ? "Wszystkie dostawy z listy są zamknięte." : "Sprawdź pominięte dostawy.", skipped = "")
}

; Zamknięcie dostawy z bazy (kilkaset pozycji) trwa w Mercalys dużo dłużej niż faktury: panel z wydrukiem przychodził po ponad
; 30 s, dlatego limity są długie. Krótka dostawa i tak idzie od razu dalej.
CloseRow(step, order) {
    problem := WebPickRow(step.title, step.grid, step.numberColumn, order, 10000)
    marked := problem = "" && MarkPage(step.title, step.tick)
    if (problem = "")
        problem := WebClick(step.title, step.close, 10000)
    if (problem = "")
        problem := WebWait(step.dialog, step.dialogButton, step.dialogText, 120000)
    if (problem = "")
        problem := WebClick(step.dialog, step.dialogButton, 10000)
    if (problem = "")
        problem := TickAfterReload(step.title, step.tick, 600000, marked)
    if (problem = "")
        problem := WebClick(step.title, step.save, 10000)
    if (problem = "")
        problem := WaitForRowGone(step, order, 600000)
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
        ; Panel z polem bywa wolny (ostatnia dostawa z listy pojawiła się po ponad 5 s), więc na pole czekamy do limitu kroku.
        problem := WebClick(title, box, Max(deadline - A_TickCount, 1000), true)
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

; Czeka na wczytaną stronę, której adres zawiera fragment, z widocznym elementem (albo jednym z kilku po przecinku).
; Komunikat z listy dialogs zamyka i zwraca "DIALOG" (hit = który). Komunikaty idą pierwsze: strona pod komunikatem też jest wczytana.
WebPage(title, address, element, timeoutMs, dialogs, &hit := "") {
    deadline := A_TickCount + timeoutMs
    Loop {
        for dialog in dialogs {
            shown := ""
            problem := CloseAlert(dialog.title, dialog.expected, dialog.button, &shown)
            if (problem != "") {
                dialog.shown := shown
                hit := dialog
                return problem
            }
        }
        for hwnd in WinGetList(title) {
            docs := HtmlDocuments(hwnd)
            for name in StrSplit(element, ",", " ") {
                for item in FindElements(docs, name) {
                    if (PageLoaded(item.doc) && InStr(WebProp(item.doc, "URL"), address) && IsShown(item.element))
                        return ""
                }
            }
        }
        if (A_TickCount >= deadline)
            return "Nie pojawiła się strona `"" address "`" z elementem `"" element "`"."
        Sleep 100
    }
}

; Komunikat zamiast oczekiwanej strony: okno z przyciskiem i tekstem (w treści albo w adresie, np. BoiteAlerte.aspx?Msg=brak+PZ…).
; Zamyka je i zwraca "DIALOG"; bez takiego okna "".
CloseAlert(title, expected, button, &shown := "") {
    for hwnd in WinGetList(title) {
        for item in FindElements(HtmlDocuments(hwnd), button) {
            if !PageLoaded(item.doc)
                continue
            text := ""
            try text := item.doc.body.innerText
            url := StrReplace(WebProp(item.doc, "URL"), "+", " ")
            matched := false
            for piece in StrSplit(expected, ";")
                if (Trim(piece) != "" && (InStr(text, Trim(piece)) || InStr(url, Trim(piece))))
                    matched := true
            if !matched
                continue
            shown := RegExReplace(Trim(text), "\s+", " ")
            try WinActivate hwnd
            ClickWithoutWaiting(item.doc, item.element)
            return WinWaitClose("ahk_id " hwnd, , 10) ? "DIALOG" : "Okno komunikatu `"" expected "`" się nie zamknęło."
        }
    }
    return ""
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

; Przyciski w widocznych wierszach siatki: wiersz to TR nad przyciskiem z id `<siatka>__ctlN_<przycisk>`, więc siatka nie
; musi mieć setSelectedLigne (historia pobierania danych). Zwraca {doc, hwnd, id, text} na każdy wiersz.
RowButtons(title, grid, button) {
    found := []
    for hwnd in WinGetList(title) {
        for doc in HtmlDocuments(hwnd) {
            if !PageLoaded(doc)
                continue
            try {
                inputs := doc.getElementsByTagName("input")
                Loop inputs.length {
                    element := inputs.item(A_Index - 1)
                    id := WebProp(element, "id")
                    if !(InStr(id, grid "__ctl") = 1 && SubStr(id, -StrLen(button) - 1) = "_" button && IsShown(element))
                        continue
                    row := GridRowOf(element, grid)
                    if IsObject(row)
                        found.Push({doc: doc, hwnd: hwnd, id: id, text: RowText(row)})
                }
            }
        }
    }
    return found
}

; "" = kliknięto przycisk wiersza pasującego do wyrażenia, "SKIP" = żadna z przejrzanych stron go nie ma.
; {@code seen}: przejrzane strony z pierwszym i ostatnim wierszem, do podsumowania, gdy wiersza nie ma.
WebRowButton(title, grid, pattern, button, nextPage, maxPages, timeoutMs, &seen := "") {
    seen := ""
    deadline := A_TickCount + timeoutMs
    page := 1
    signature := ""
    stableSince := 0
    Loop {
        rows := RowButtons(title, grid, button)
        for row in rows {
            if RegExMatch(row.text, pattern) {
                try WinActivate row.hwnd
                try {
                    ClickWithoutWaiting(row.doc, row.doc.getElementById(row.id))
                    return ""
                }
            }
        }
        current := rows.Length ":" (rows.Length > 0 ? rows[1].text : "")
        if (current != signature) {
            signature := current
            stableSince := A_TickCount
        } else if (rows.Length > 0 && A_TickCount - stableSince >= 1000) {
            ; Strona ustała i nie ma wiersza: następna strona albo koniec.
            seen .= "  strona " page ": " rows[1].text (rows.Length > 1 ? "  …  " rows[rows.Length].text : "") "`n"
            if (page >= maxPages || nextPage = "" || !PagerAvailable(title, nextPage))
                return "SKIP"
            if (WebClick(title, nextPage, 5000) != "")
                return "SKIP"
            page += 1
            first := rows[1].text
            waitUntil := A_TickCount + 15000
            Loop {
                Sleep 200
                fresh := RowButtons(title, grid, button)
                if (fresh.Length > 0 && fresh[1].text != first)
                    break
                ; Ta sama strona po kliknięciu: to była ostatnia (przycisk nie zawsze jest wyłączony), więc wiersza nie ma.
                if (A_TickCount >= waitUntil)
                    return "SKIP"
            }
            signature := ""
            continue
        }
        if (A_TickCount >= deadline)
            return rows.Length = 0 ? "Nie pojawiła się lista `"" grid "`" z przyciskami `"" button "`"." : "SKIP"
        Sleep 100
    }
}

; "" = warunek spełniony, "EMPTY" = przy `all` tabela pusta przez 3 s, inaczej opis problemu.
WebGridWait(title, element, condition, pattern, timeoutMs) {
    deadline := A_TickCount + timeoutMs
    since := 0
    lastState := ""
    seen := "nie ma strony z elementem `"" element "`""
    Loop {
        state := ""
        for doc in PagesWith(title, element) {
            total := 0
            matching := 0
            first := ""
            for item in GridRows(doc, "*") {
                if !IsShown(item.row)
                    continue
                total += 1
                text := RowStateText(item.row)
                if (first = "")
                    first := text
                if (pattern != "" && RegExMatch(text, pattern))
                    matching += 1
            }
            ; `page`: wyrażenie sprawdza tekst całej strony (np. „Suma wyboru” równa „Suma linii”), a nie wiersze.
            if (condition = "page") {
                body := ""
                try body := RegExReplace(StrReplace(doc.body.innerText, Chr(160), " "), "[ \t]+", " ")
                matching := RegExMatch(body, pattern) ? total : 0
            }
            state := total ":" matching
            ; Pierwszy wiersz w opisie: bez niego błąd nie mówi, jak Mercalys pokazuje stan wiersza.
            seen := "wiersze: " total ", pasujące: " matching (first != "" ? ", pierwszy: " SubStr(first, 1, 200) : "")
            break
        }
        if (state != lastState) {
            lastState := state
            since := A_TickCount
        }
        if (state != "") {
            parts := StrSplit(state, ":")
            total := Integer(parts[1])
            ; `none` z wyrażeniem: zostały tylko wiersze, które do niego pasują (np. artykuły w promocji, których MSI nie rusza).
            if (condition = "none" && (pattern = "" ? total = 0 : Integer(parts[2]) = total) && A_TickCount - since >= 1000)
                return ""
            if (condition = "all" || condition = "page") {
                if (total > 0 && Integer(parts[2]) = total && A_TickCount - since >= 500)
                    return ""
                if (total = 0 && A_TickCount - since >= 3000)
                    return "EMPTY"
            }
        }
        if (A_TickCount >= deadline)
            return "Tabela nie doszła do stanu `"" condition "`" (" seen ")."
        Sleep 200
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
                        DoubleClickRow(doc, row)
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

; Mercalys: klik zaznacza wiersz (setSelectedLigne), dwuklik to __doPostBack z atrybutu ondblclick wiersza.
; Kod z atrybutu uruchamiamy wprost; bez niego zdarzenie idzie do pierwszej komórki i wypływa do wiersza.
DoubleClickRow(doc, row) {
    dbl := ""
    if RegExMatch(WebProp(row, "outerHTML"), "i)ondblclick=`"([^`"]+)`"", &match)
        dbl := StrReplace(StrReplace(StrReplace(match[1], "&quot;", "`""), "&#39;", "'"), "&amp;", "&")
    window := doc.parentWindow
    window.__macroTarget := row
    window.setTimeout("var t = window.__macroTarget; t.click(); "
        . (dbl != "" ? dbl : "t.cells[0].fireEvent('ondblclick');"), 0, "JScript")
}

; Najgłębszy widoczny element z tekstem (bez wielkości liter). Jedno pole wyboru obok napisu (w nim, w jego rodzicu
; albo dziadku) dostaje klik, gdy nie jest zaznaczone; bez pola klik idzie w sam napis.
WebTextClick(title, text, timeoutMs) {
    deadline := A_TickCount + timeoutMs
    Loop {
        for hwnd in WinGetList(title) {
            for doc in HtmlDocuments(hwnd) {
                if !PageLoaded(doc)
                    continue
                target := ""
                try {
                    all := doc.all
                    Loop all.length {
                        element := all.item(A_Index - 1)
                        tag := StrUpper(WebProp(element, "tagName"))
                        if (tag = "HTML" || tag = "BODY" || tag = "SCRIPT" || tag = "STYLE" || tag = "FORM")
                            continue
                        ; Kolejność dokumentu: potomek stoi po przodku, więc ostatni pasujący jest najgłębszy.
                        if (InStr(WebProp(element, "innerText"), text) && IsShown(element))
                            target := element
                    }
                }
                if !IsObject(target)
                    continue
                box := ""
                node := target
                Loop 3 {
                    boxes := []
                    try {
                        inputs := node.getElementsByTagName("input")
                        Loop inputs.length {
                            input := inputs.item(A_Index - 1)
                            if InStr(" checkbox radio ", " " StrLower(WebProp(input, "type")) " ")
                                boxes.Push(input)
                        }
                    }
                    if (boxes.Length = 1) {
                        box := boxes[1]
                        break
                    }
                    if (boxes.Length > 1)
                        break
                    try node := node.parentElement
                    catch
                        break
                    if !IsObject(node)
                        break
                }
                try WinActivate hwnd
                if IsObject(box) {
                    if !WebProp(box, "checked")
                        ClickWithoutWaiting(doc, box)
                } else
                    ClickWithoutWaiting(doc, target)
                return ""
            }
        }
        if (A_TickCount >= deadline)
            return "Nie ma napisu `"" text "`" w oknie `"" title "`"."
        Sleep 100
    }
}

; Tekst wiersza razem z jego stanem, którego nie ma w samym tekście: zaznaczone pole wyboru dopisuje „[zaznaczone]”,
; a obrazki i przyciski swój opis (alt, title), bo Mercalys pokazuje zaznaczenie wiersza także ikoną.
RowStateText(row) {
    text := RowText(row)
    extra := ""
    try {
        inputs := row.getElementsByTagName("input")
        Loop inputs.length {
            item := inputs.item(A_Index - 1)
            kind := StrLower(WebProp(item, "type"))
            if ((kind = "checkbox" || kind = "radio") && WebProp(item, "checked"))
                extra .= " [zaznaczone]"
            for name in ["alt", "title"]
                if ((value := Trim(WebProp(item, name))) != "")
                    extra .= " " value
        }
        images := row.getElementsByTagName("img")
        Loop images.length {
            item := images.item(A_Index - 1)
            for name in ["alt", "title"]
                if ((value := Trim(WebProp(item, name))) != "")
                    extra .= " " value
        }
    }
    return Trim(text extra)
}

; Wiersz siatki, w którym stoi element. Przycisk Mercalys (ImgButton) ma własną tabelkę, więc najbliższy <tr> nad nim to
; wiersz tej tabelki, bez tekstu wiersza listy (historia pobierania danych: puste wiersze dgHisto). Szukamy <tr>, którego
; tabela ma id siatki albo który ma kliknięcie wiersza siatki (setSelectedLigne); bez takiego najbliższy <tr>.
GridRowOf(element, grid) {
    nearest := ""
    node := element
    Loop 20 {
        try node := node.parentNode
        catch
            break
        if !IsObject(node) || WebProp(node, "tagName") = ""
            break
        if (WebProp(node, "tagName") != "TR")
            continue
        if !IsObject(nearest)
            nearest := node
        table := ""
        try table := node.parentNode
        if (WebProp(table, "tagName") = "TBODY")
            try table := table.parentNode
        if (WebProp(table, "id") = grid)
            return node
        ; Idziemy w górę, więc pierwszy <tr> z kliknięciem wiersza to wiersz siatki, a nie ramka całej listy.
        if InStr(WebProp(node, "outerHTML"), "setSelectedLigne('" grid "'")
            return node
    }
    return nearest
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
WebPickRowMatching(title, grid, pattern, timeoutMs, &missing := false) {
    missing := false
    deadline := A_TickCount + timeoutMs
    gridSince := 0
    pageSince := 0
    Loop {
        gridFound := false
        pageLoaded := false
        for hwnd in WinGetList(title) {
            for doc in HtmlDocuments(hwnd) {
                try {
                    if (doc.readyState = "complete")
                        pageLoaded := true
                }
                rows := GridRows(doc, grid)
                ; Pusta lista (siatka bez wierszy, np. bon bez pliku 88110) też jest listą: wiersza na niej nie będzie.
                if (rows.Length > 0 || GridPresent(doc, grid))
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
            else if (A_TickCount - gridSince >= 2000) {
                missing := true
                return "Na liście nie ma wiersza pasującego do `"" pattern "`"."
            }
        } else if pageLoaded {
            ; Strona wczytana, a listy nie ma (Mercalys nie rysuje pustej siatki): pliku też nie ma.
            if (pageSince = 0)
                pageSince := A_TickCount
            else if (A_TickCount - pageSince >= 4000) {
                missing := true
                return "Nie ma listy `"" grid "`" w oknie `"" title "`"."
            }
        }
        if (A_TickCount >= deadline) {
            missing := gridFound
            return gridFound ? "Na liście nie ma wiersza pasującego do `"" pattern "`"." : "Nie ma listy `"" grid "`" w oknie `"" title "`"."
        }
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

; Numer bonu do porównania: same cyfry bez zer wiodących.
BonDigits(text) {
    return LTrim(RegExReplace(text, "\D"), "0")
}

; Ten sam bon: numery równe albo jeden jest końcówką drugiego (lista może pokazywać numer bez przedrostka), od 6 cyfr.
SameBon(shown, wanted) {
    if (shown = "" || wanted = "")
        return false
    if (shown = wanted)
        return true
    shorter := StrLen(shown) < StrLen(wanted) ? shown : wanted
    longer := StrLen(shown) < StrLen(wanted) ? wanted : shown
    return StrLen(shorter) >= 6 && SubStr(longer, -StrLen(shorter)) = shorter
}

WebCollect(title, grid, text, column, nextPage, pageField, stopOnEmptyPage := false, collected := "", bonColumn := "", bonNumber := "") {
    if !IsObject(collected)
        collected := {codes: [], seen: Map()}
    codes := []
    seen := Map()
    pages := 0
    matched := 0
    withoutCode := 0
    wanted := RegExReplace(text, "\s+", " ")
    wantedBon := BonDigits(bonNumber)
    Loop {
        rows := VisibleGridRows(title, grid, 30000)
        if (rows.Length = 0)
            return {problem: "Nie ma listy `"" grid "`" w oknie `"" title "`" (strona " (pages + 1) ").", text: ""}
        pages += 1
        pageMatched := 0
        if (wantedBon != "" && IsInteger(bonColumn)) {
            for item in rows {
                shown := CellText(item.row, Integer(bonColumn))
                if !SameBon(BonDigits(shown), wantedBon)
                    return {problem: "Otworzył się inny bon: na liście jest `"" shown "`", a miał być " bonNumber ". Nic nie zebrano z tego pliku.", text: ""}
            }
        }
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
    ; Kody z tej listy dochodzą do zebranych wcześniej w tym makrze; Schowek ma zawsze wszystkie.
    for code in codes {
        if !collected.seen.Has(code) {
            collected.seen[code] := true
            collected.codes.Push(code)
        }
    }
    list := ""
    for code in collected.codes
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
    result := (bonNumber != "" ? "Bon " bonNumber ":`n" : "") "Przejrzane strony: " pages "`nWiersze z błędem `"" text "`": " matched "`n"
    if (withoutCode > 0)
        result .= "BEZ KODU ITM8 (sprawdź ręcznie): " withoutCode "`n"
    result .= codes.Length = 0 ? "Nie ma kodów w tym pliku.`n"
        : "Kody z tego pliku: " codes.Length "`n" shown
    result .= collected.codes.Length = 0 ? "`nSchowek jest pusty.`n"
        : "`nKody w Schowku razem: " collected.codes.Length ". Wklej je w Intermanage (Uzupełnij ITM8).`n"
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
    ; Przed podsumowaniem czekamy jeszcze na wyniki klikniętych bonów: przy ostatnim bonie pętla kończyła się zaraz po
    ; kliknięciu, zanim Mercalys wpisał Nr Zamów w ostatecznej postaci, więc poprawnie utworzony bon trafiał do „bez wyniku”.
    Summary(ending) {
        AwaitHandledResults(title, grid, column, wanted, handled, results, 15000)
        return ListSummary(wanted, handled, results, count, ending)
    }
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
; Liczą się tylko strony z przyciskiem okna (np. „Fermer”): główne okno Mercalys też ma w tytule „Les Mousquetaires”
; i trzyma schowane pytanie „Drukowanie przebiegło prawidłowo ?”, które brane za komunikat zatrzymywało makro.
ConfirmDialog(title, expected, button, timeoutMs) {
    deadline := A_TickCount + timeoutMs
    Loop {
        for hwnd in WinGetList(title) {
            for doc in HtmlDocuments(hwnd) {
                text := ""
                try {
                    if (doc.readyState = "complete" && FindElements([doc], button).Length > 0)
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
OptionalDialog(title, expected, button, timeoutMs, &clicked := false) {
    clicked := false
    deadline := A_TickCount + timeoutMs
    Loop {
        for hwnd in WinGetList(title) {
            for doc in HtmlDocuments(hwnd) {
                element := ""
                text := ""
                try {
                    ; Przycisk po id, a bez niego po name, value albo tekście (np. „Fermer” w oknie komunikatu bez id).
                    if (doc.readyState = "complete") {
                        found := FindElements([doc], button)
                        if (found.Length > 0) {
                            element := found[1].element
                            text := Trim(doc.body.innerText)
                        }
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
                clicked := true
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

; Czyta listę, aż każdy kliknięty bon ma wynik albo minie limit (strona po kliknięciu jeszcze się przeładowuje).
AwaitHandledResults(title, grid, column, wanted, handled, results, timeoutMs) {
    deadline := A_TickCount + timeoutMs
    Loop {
        ReadResults(title, grid, column, wanted, results)
        waiting := false
        for rowKey in handled {
            ; handled trzyma klucz wiersza („numer / kod”), results klucz z listy WEBLIST
            cells := StrSplit(rowKey, " / ")
            key := ListKey(cells[1], cells.Length >= 2 ? cells[2] : "")
            if !wanted.Has(key)
                key := ListKey(cells[1], "")
            if (wanted.Has(key) && !results.Has(key))
                waiting := true
        }
        if (!waiting || A_TickCount >= deadline)
            return
        Sleep 300
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
                        value := CellText(item.row, column)
                        if (value != "" && value != "?") ; „?” to jeszcze nie Nr Zamów (ReadResults też go pomija)
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
; Czy strona ma siatkę (także pustą): jej ukryte pole fokusu albo tabela o tym id.
GridPresent(doc, grid) {
    if (grid = "*")
        return false
    try {
        if (doc.readyState != "complete")
            return false
        return WebProp(doc.getElementById(grid "_hiddenFocus"), "tagName") != ""
            || WebProp(doc.getElementById(grid), "tagName") != ""
    }
    return false
}

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

; --- Dostawa z bazy: zamówienia oczekujące i wybrane (od 0.65) ---
; Budowy obu list nie znamy ze skanu (Alt+R pokazuje tylko przyciski i pola), więc wiersze rozpoznajemy po treści,
; a każdy krok sprawdzamy po skutku. Nierozpoznana lista zatrzymuje makro z opisem wierszy, zanim cokolwiek się zapisze.

; Czeka na okno komunikatu z tekstem i je zamyka. "" albo opis problemu.
WebAlert(title, expected, button, timeoutMs, &shown := "") {
    deadline := A_TickCount + timeoutMs
    Loop {
        problem := CloseAlert(title, expected, button, &shown)
        if (problem = "DIALOG")
            return ""
        if (problem != "")
            return problem
        if (A_TickCount >= deadline)
            return "Nie pojawiło się okno komunikatu `"" expected "`"."
        Sleep 100
    }
}

; Nazwa do porównania: same wielkie litery łacińskie i cyfry.
NameKey(text) => RegExReplace(StrUpper(text), "[^A-Z0-9]")

; Nazwa dostawcy w wierszu (lista może ją uciąć, więc wystarczy początek).
HasSupplierName(text, name) => NameKey(name) != "" && InStr(NameKey(text), SubStr(NameKey(name), 1, 10))

; Wiersze tabel bez wierszy w środku, z co najmniej czterema komórkami.
LeafRows(doc) {
    rows := []
    try {
        found := doc.getElementsByTagName("tr")
        Loop found.length {
            row := found.item(A_Index - 1)
            nested := 1
            cells := 0
            try nested := row.getElementsByTagName("tr").length
            try cells := row.cells.length
            if (nested = 0 && cells >= 4 && IsShown(row))
                rows.Push(row)
        }
    }
    return rows
}

; Data DD/MM/RRRR albo DD/MM/RR jako RRRRMMDD; "" bez daty.
DateKey(text) {
    if !RegExMatch(text, "(?<!\d)(\d{2})[/.\-](\d{2})[/.\-](\d{4}|\d{2})(?!\d)", &found)
        return ""
    return (StrLen(found[3]) = 2 ? "20" found[3] : found[3]) found[2] found[1]
}

; Położenie kolumn z nagłówka listy (numer zamówienia, data dostawy); -1 = nie ma.
OrderColumns(rows) {
    best := {number: -1, date: -1, count: 0}
    for row in rows {
        number := -1
        date := -1
        cells := row.cells
        Loop cells.length {
            text := CellText(row, A_Index - 1)
            if (number < 0 && RegExMatch(text, "i)^(nr|numer)\.?\s*(zam|kod)"))
                number := A_Index - 1
            if (date < 0 && RegExMatch(text, "i)^data\s*dost"))
                date := A_Index - 1
        }
        if (number >= 0 || date >= 0)
            best := {number: number, date: date, count: cells.length}
        if (number >= 0 && date >= 0)
            return best
    }
    return best
}

; Czy wiersz stoi na liście wybranych: po id pojemnika listy, bez niego po położeniu tabeli pod strzałką (strzałka w dół).
InChosenList(row, arrow) {
    table := ""
    node := row
    Loop 30 {
        node := WebProp(node, "parentElement")
        if !IsObject(node)
            break
        if (!IsObject(table) && WebProp(node, "tagName") = "TABLE")
            table := node
        id := WebProp(node, "id")
        if InStr(id, "CdeSelection")
            return true
        if InStr(id, "CdeEnAtt")
            return false
    }
    try return (IsObject(table) ? table : row).getBoundingClientRect().top > arrow.getBoundingClientRect().top
    return false
}

; Strona z listami zamówień: {doc, hwnd, waiting, chosen, rows}; "" = strona jeszcze się nie wczytała.
OrderPage(title, arrowId, code, name) {
    try return ReadOrderPage(title, arrowId, code, name) ; strona w trakcie przeładowania odmawia odczytu
    return ""
}

ReadOrderPage(title, arrowId, code, name) {
    for hwnd in WinGetList(title) {
        for item in FindElements(HtmlDocuments(hwnd), arrowId) {
            if !(PageLoaded(item.doc) && IsShown(item.element))
                continue
            page := {doc: item.doc, hwnd: hwnd, waiting: [], chosen: [], rows: LeafRows(item.doc)}
            grid := Map() ; wiersze z setSelectedLigne: numer do sprawdzenia zaznaczenia
            for entry in GridRows(item.doc, "*")
                grid[WebProp(entry.row, "sourceIndex")] := entry
            columns := OrderColumns(page.rows)
            for row in page.rows {
                info := OrderInfo(row, columns, code)
                info.named := HasSupplierName(info.text, name)
                if (info.number = "" || (info.date = "" && !info.named))
                    continue
                info.entry := grid.Get(WebProp(row, "sourceIndex"), "")
                (InChosenList(row, item.element) ? page.chosen : page.waiting).Push(info)
            }
            return page
        }
    }
    return ""
}

OrderInfo(row, columns, code) {
    texts := []
    cells := row.cells
    Loop cells.length
        texts.Push(CellText(row, A_Index - 1))
    sameLayout := columns.count = texts.Length
    number := ""
    if (sameLayout && columns.number >= 0 && RegExMatch(texts[columns.number + 1], "^0*(\d{2,10})$", &found))
        number := found[1]
    if (number = "") {
        ; Bez nagłówka: pierwsza komórka z samym numerem, która nie jest kodem dostawcy.
        for text in texts {
            if (RegExMatch(text, "^\d{4,10}$") && LTrim(text, "0") != LTrim(code, "0")) {
                number := LTrim(text, "0")
                break
            }
        }
    }
    date := sameLayout && columns.date >= 0 ? DateKey(texts[columns.date + 1]) : ""
    if (date = "" && !(sameLayout && columns.date >= 0)) {
        ; Bez nagłówka najpóźniejsza data wiersza: dostawa jest po utworzeniu i przesłaniu zamówienia.
        for text in texts {
            key := DateKey(text)
            if (key != "" && (date = "" || Integer(key) > Integer(date)))
                date := key
        }
    }
    return {row: row, texts: texts, text: RowText(row), number: number, date: date}
}

HasOrigin(info, origins) {
    for text in info.texts {
        for origin in StrSplit(origins, ";") {
            if (Trim(origin) != "" && text = Trim(origin))
                return true
        }
    }
    return false
}

; Opis wierszy strony do wyniku, gdy listy nie da się odczytać: tabela, kliknięcie i treść.
DescribeOrderRows(page) {
    if !IsObject(page)
        return ""
    text := "`nWiersze strony:"
    for row in page.rows {
        if (A_Index > 14 || StrLen(text) > 2200)
            break
        id := ""
        node := row
        Loop 12 {
            node := WebProp(node, "parentElement")
            if !IsObject(node)
                break
            id := WebProp(node, "id")
            if (id != "")
                break
        }
        click := RegExMatch(WebProp(row, "outerHTML"), "i)onclick=`"?([^`">]{1,60})", &found) ? found[1] : ""
        text .= "`n[" id "] {" click "} " SubStr(RowText(row), 1, 110)
    }
    return text
}

DescribeOrders(list) {
    text := ""
    for info in list
        text .= (A_Index = 1 ? "" : ", ") info.number " (" info.date ")"
    return text
}

; "" = zamówienie przeniesione do wybranych, "NONE" = dostawca nie ma takiego zamówienia, inaczej opis problemu.
WebOrderPick(title, arrows, code, name, origins, today, timeoutMs, &order, &note) {
    deadline := A_TickCount + timeoutMs
    page := ""
    Loop {
        page := OrderPage(title, Trim(arrows[1]), code, name)
        if (IsObject(page) && page.waiting.Length + page.chosen.Length > 0)
            break
        if (A_TickCount >= deadline)
            return "Nie widzę żadnego zamówienia na liście oczekujących. Wybierz zamówienie albo utwórz kod ręcznie." DescribeOrderRows(page)
        Sleep 300
    }
    Sleep 1000 ; lista mogła się dopiero dopełniać
    page := OrderPage(title, Trim(arrows[1]), code, name)
    if !IsObject(page)
        return "Lista zamówień zniknęła w trakcie odczytu."
    if (page.chosen.Length > 0)
        return "W wybranych zamówieniach już coś jest: " DescribeOrders(page.chosen) ". Zdejmij je i uruchom makro ponownie." DescribeOrderRows(page)
    best := ""
    later := "" ; najbliższe późniejsze: tylko, gdy nie ma żadnego do dziś
    for info in page.waiting {
        if !(info.named && HasOrigin(info, origins))
            continue
        if (info.date = "")
            return "Zamówienie " info.number " dostawcy " code " nie ma czytelnej daty dostawy: " info.text DescribeOrderRows(page)
        if (Integer(info.date) > Integer(today)) {
            if (!IsObject(later) || Integer(info.date) < Integer(later.date))
                later := info
            continue
        }
        if (!IsObject(best) || Integer(info.date) > Integer(best.date))
            best := info
    }
    if !IsObject(best)
        best := later
    if !IsObject(best) {
        note := "Dostawca " code ": brak zamówienia z bazy (zamówień na liście: " page.waiting.Length ")"
        return "NONE"
    }
    order := best.number
    note := "Dostawca " code ": wybrane zamówienie " order ", data dostawy " best.date
    try WinActivate page.hwnd
    if IsObject(best.entry) {
        ClickWithoutWaiting(page.doc, best.row)
        problem := WaitForSelection(page.doc, best.entry.grid, best.entry, A_TickCount + 8000)
        if (problem != "")
            return problem
    } else {
        target := best.row
        try target := best.row.cells.item(0)
        ClickWithoutWaiting(page.doc, target)
        Sleep 1500
    }
    for arrow in arrows {
        problem := WebClick(title, Trim(arrow), 5000)
        if (problem != "")
            continue
        moveDeadline := A_TickCount + 15000
        Loop {
            Sleep 400
            page := OrderPage(title, Trim(arrows[1]), code, name)
            if (IsObject(page) && page.chosen.Length > 0)
                return page.chosen.Length = 1 && page.chosen[1].number = order ? ""
                    : "Do wybranych trafiło inne zamówienie (" DescribeOrders(page.chosen) ") zamiast " order ". Zdejmij je z wybranych." DescribeOrderRows(page)
            if (A_TickCount >= moveDeadline)
                break
        }
    }
    return "Zamówienie " order " nie przeszło do wybranych." DescribeOrderRows(page)
}

; Jedyne wybrane zamówienie, tego dostawcy (i to samo, które przenieśliśmy). "" = numer w order.
WebOrderRead(title, arrow, code, name, expected, timeoutMs, &order) {
    deadline := A_TickCount + timeoutMs
    page := ""
    Loop {
        page := OrderPage(title, arrow, code, name)
        if (IsObject(page) && page.chosen.Length > 0) {
            Sleep 700
            page := OrderPage(title, arrow, code, name)
            if (IsObject(page) && page.chosen.Length > 0)
                break
        }
        if (A_TickCount >= deadline)
            return "Nie ma zamówienia w wybranych." DescribeOrderRows(page)
        Sleep 300
    }
    if (page.chosen.Length != 1)
        return "W wybranych jest kilka zamówień: " DescribeOrders(page.chosen) "." DescribeOrderRows(page)
    info := page.chosen[1]
    if !info.named
        return "Wybrane zamówienie " info.number " nie jest dostawcy " code " (" name "): " info.text
    if (expected != "" && info.number != expected)
        return "W wybranych jest zamówienie " info.number " zamiast " expected "."
    order := info.number
    return ""
}

; Zaznacza zamówienie na liście Wprowadzania zamówień i sprawdza dostawcę w jego wierszu.
WebOrderRow(step, order, code, name, timeoutMs) {
    problem := ShowOrderRow(step, order)
    if (problem != "")
        return problem
    for hwnd in WinGetList(step.title) {
        for doc in HtmlDocuments(hwnd) {
            for item in GridRows(doc, step.grid) {
                if (ListKey(CellText(item.row, step.numberColumn), "") != ListKey(order, ""))
                    continue
                supplier := HasSupplierName(RowText(item.row), name)
                cells := item.row.cells
                Loop cells.length
                    supplier := supplier || LTrim(CellText(item.row, A_Index - 1), "0") = code
                if !supplier
                    return "Zamówienie " order " na liście nie jest dostawcy " code ": " RowText(item.row)
                return WebPickRow(step.title, step.grid, step.numberColumn, order, timeoutMs)
            }
        }
    }
    return "Nie ma zamówienia " order " na liście zamówień oczekujących na dostawę."
}

; Lista błędów pobrania: "" = każdy wiersz to dozwolony błąd, inaczej opis z niedozwolonymi.
WebErrors(title, element, allowed, timeoutMs, &summary) {
    deadline := A_TickCount + timeoutMs
    previous := -1
    Loop {
        texts := []
        for hwnd in WinGetList(title) {
            for item in FindElements(HtmlDocuments(hwnd), element) {
                if !(PageLoaded(item.doc) && IsShown(item.element))
                    continue
                for entry in GridRows(item.doc, "*")
                    texts.Push(RowText(entry.row))
                if (texts.Length = 0) {
                    ; Siatka bez setSelectedLigne: wiersze danych mają kod (od 5 cyfr), nagłówek nie.
                    for row in LeafRows(item.doc) {
                        if RegExMatch(RowText(row), "(?<!\d)\d{5,13}(?!\d)")
                            texts.Push(RowText(row))
                    }
                }
            }
            if (texts.Length > 0)
                break
        }
        ; Dwa takie same odczyty: lista już się nie dopełnia.
        if (texts.Length > 0 && texts.Length = previous)
            break
        previous := texts.Length
        if (A_TickCount >= deadline)
            return "Nie udało się odczytać listy błędów pobrania. Sprawdź ją w Mercalys."
        Sleep 500
    }
    ; Kolejne strony listy (od 0.67): Mercalys pokazuje po 20 błędów, a wyczyszczenie usuwa wszystkie.
    step := {title: title, grid: "*"}
    Loop 100 {
        if !PagerAvailable(title, "pgrDetail_ibtSuivant")
            break
        problem := TurnPage(step, "pgrDetail_ibtSuivant")
        if (problem != "")
            return "Lista błędów pobrania: " problem " Sprawdź ją w Mercalys."
        for item in VisibleGridRows(title, "*", 10000)
            texts.Push(RowText(item.row))
    }
    bad := ""
    count := 0
    for text in texts {
        matched := false
        for piece in allowed
            matched := matched || (Trim(piece) != "" && InStr(text, Trim(piece)))
        if !matched {
            count += 1
            if (count <= 10)
                bad .= "`n" text
        }
    }
    if (count > 0)
        return "Lista błędów pobrania ma inne błędy niż dozwolone (" count " z " texts.Length "), sprawdź je w Mercalys:" bad
    summary := "Błędy pobrania (tylko dozwolone): " texts.Length
    return ""
}

; --- Cadencier bezpośredni: artykuły z błędem na liście (od 0.68) ---
; Siatki listy (ca-e013) nie znamy ze skanu, więc kolumnę znajdujemy po nagłówku, a nierozpoznana lista zatrzymuje makro
; z opisem wierszy, zanim cokolwiek się zmieni.

WebFixCn(step, vatCodes, &fixedCount) {
    fixed := Map()
    Loop 2000 {
        found := ""
        problem := FindAnomaly(step, &found)
        if (problem != "")
            return problem
        if !IsObject(found)
            return ""
        DoubleClickRow(found.doc, found.row)
        problem := WebPage(step.title, step.card, step.ean, 30000, [])
        if (problem != "")
            return "Artykuł z błędem (" found.text ") się nie otworzył. " problem
        ean := ""
        problem := WebValue(step.title, step.ean, "^\s*\d+\s*$", 10000, &ean)
        if (problem != "")
            return "Artykuł z błędem (" found.text ") nie ma EAN-u. " problem
        key := LTrim(Trim(ean), "0")
        if fixed.Has(key)
            return "Artykuł " ean " dalej ma błąd po wpisaniu kodu CN i VAT. Popraw go w Mercalys."
        if !vatCodes.Has(key)
            return "Artykuł " ean " z błędem nie jest z tego pliku (albo plik nie ma jego stawki VAT). Popraw go w Mercalys."
        problem := FillCnCard(step, vatCodes[key])
        if (problem != "")
            return "Artykuł " ean ": " problem
        fixed[key] := true
        fixedCount += 1
    }
    return "Za dużo artykułów z błędem."
}

; Kod CN z Enterem (Mercalys sprawdza go i potrafi przeładować kartę), potem stawka; zatwierdza dopiero przy obu wartościach.
FillCnCard(step, vatCode) {
    problem := WebKey(step.title, step.cn, "^a" step.cnCode "{Enter}", 10000)
    if (problem != "")
        return problem
    Sleep 1500
    OptionalDialog("okno dialogowe strony", "stawk;CN", "Fermer", 500)
    problem := WebPage(step.title, step.card, step.vat, 30000, [])
    if (problem = "")
        problem := WebValue(step.title, step.cn, "^\s*" step.cnCode "\s*$", 5000)
    if (problem = "")
        problem := WebKey(step.title, step.vat, "^a" vatCode "{Tab}", 10000)
    if (problem != "")
        return problem
    Sleep 800
    OptionalDialog("okno dialogowe strony", "stawk;CN", "Fermer", 500)
    problem := WebValue(step.title, step.vat, "^\s*0*" vatCode "\s*$", 5000)
    if (problem = "")
        problem := WebValue(step.title, step.cn, "^\s*" step.cnCode "\s*$", 5000)
    if (problem = "")
        problem := WebClick(step.title, step.save, 10000)
    if (problem = "")
        problem := ConfirmDialog(step.dialog, step.dialogText, step.dialogButton, 30000)
    if (problem != "")
        return problem
    Sleep 1000
    if (WebPage(step.title, step.list, step.listElement, 10000, []) = "")
        return ""
    ; Karta została na ekranie po zapisie: wraca się przyciskiem.
    if (WebPage(step.title, step.card, step.back, 500, []) = "") {
        problem := WebClick(step.title, step.back, 5000)
        if (problem != "")
            return problem
    }
    return WebPage(step.title, step.list, step.listElement, 30000, [])
}

; Pierwszy wiersz z błędem na wszystkich stronach listy (found = "" bez błędów).
FindAnomaly(step, &found) {
    found := ""
    if PagerAvailable(step.title, "pgrDetail_ibtPremier") {
        problem := TurnPage(step, "pgrDetail_ibtPremier")
        if (problem != "")
            return problem
    }
    Loop 200 {
        problem := PageAnomaly(step, &found)
        if (problem != "" || IsObject(found))
            return problem
        if !PagerAvailable(step.title, "pgrDetail_ibtSuivant")
            return ""
        problem := TurnPage(step, "pgrDetail_ibtSuivant")
        if (problem != "")
            return problem
    }
    return "Lista artykułów ma za dużo stron."
}

; Strona listy: kolumna po nagłówku, wiersze danych stabilne w dwóch odczytach.
PageAnomaly(step, &found) {
    deadline := A_TickCount + 30000
    previous := ""
    reason := "Nie pojawiła się lista artykułów (" step.list ")."
    Loop {
        for doc in PagesWith(step.title, step.listElement) {
            if !InStr(WebProp(doc, "URL"), step.list)
                continue
            rows := []
            for item in GridRows(doc, "*") {
                if IsShown(item.row)
                    rows.Push(item.row)
            }
            if (rows.Length = 0) {
                for row in LeafRows(doc) {
                    if RegExMatch(RowText(row), "(?<!\d)\d{8,13}(?!\d)")
                        rows.Push(row)
                }
            }
            signature := rows.Length
            for row in rows
                signature .= "|" RowText(row)
            if (rows.Length = 0) {
                reason := "Lista artykułów jest pusta albo ma nieznaną budowę." DescribeLeafRows(doc)
                continue
            }
            if (signature != previous) {
                previous := signature
                reason := "Lista artykułów się zmienia."
                continue
            }
            column := -1
            width := 0
            HeaderColumn(doc, step.header, &column, &width)
            if (column < 0) {
                reason := "Na liście artykułów nie ma kolumny `"" step.header "`"." DescribeLeafRows(doc)
                continue
            }
            bad := ""
            for row in rows {
                cells := 0
                try cells := row.cells.length
                value := CellText(row, column)
                ; Krótka litera w każdym wierszu: inna szerokość albo długi tekst = zła kolumna, niczego nie klikamy.
                if (cells != width || value = "" || value = "?" || StrLen(value) > 3) {
                    return "Kolumna `"" step.header "`" listy artykułów ma nieoczekiwaną wartość `"" value "`" (nagłówek " width " kolumn, wiersz " cells ")." DescribeLeafRows(doc)
                }
                if (StrUpper(value) != StrUpper(step.good) && !IsObject(bad))
                    bad := {doc: doc, row: row, text: RowText(row)}
            }
            found := bad
            return ""
        }
        if (A_TickCount >= deadline)
            return reason
        Sleep 500
    }
}

; Numer kolumny z nagłówkiem (z kropką albo bez) i liczba kolumn nagłówka.
HeaderColumn(doc, header, &column, &width) {
    wanted := StrUpper(RTrim(header, "."))
    try {
        found := doc.getElementsByTagName("tr")
        Loop found.length {
            cells := found.item(A_Index - 1).cells
            Loop cells.length {
                text := ""
                try text := Trim(StrReplace(cells.item(A_Index - 1).innerText, Chr(160), " "))
                if (StrUpper(RTrim(text, ". ")) = wanted) {
                    column := A_Index - 1
                    width := cells.length
                    return
                }
            }
        }
    }
}

; Początek listy do opisu błędu (komórki oddzielone kreską), żeby było widać budowę nieznanej tabeli.
DescribeLeafRows(doc) {
    out := ""
    count := 0
    for row in LeafRows(doc) {
        count += 1
        if (count > 4)
            break
        text := ""
        try {
            cells := row.cells
            Loop cells.length
                text .= (A_Index > 1 ? " ¦ " : "") Trim(RegExReplace(StrReplace(cells.item(A_Index - 1).innerText, Chr(160), " "), "\s+", " "))
        }
        out .= "`n" SubStr(text, 1, 300)
    }
    return out = "" ? "" : "`nWiersze:" out
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