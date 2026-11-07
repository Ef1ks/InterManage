#Requires AutoHotkey v2.0
#SingleInstance Force
SendMode "Event"
SetKeyDelay 30, 30
CoordMode "Mouse", "Screen"
SetTitleMatchMode 2 ; WEBCLICK: wystarczy fragment tytułu okna
global PlayerVersion := 11 ; podbijamy przy każdej zmianie, widać go w oknie startowym i w raporcie F7

TrayTip "Odtwarzacz Makr gotowy!", "F7 = Przyciski strony | F8 = Test | F9 = Start ze Schowka | ESC = Wyłącz", 1
MsgBox "Plik .exe działa poprawnie! Wersja " PlayerVersion "`n`nSkróty:`n[F7] - Lista przycisków strony pod kursorem (do Schowka)`n[F8] - Test ruchu myszy i pisania`n[F9] - Wykonaj makro ze Schowka`n[ESC] - Awaryjne wyłączenie", "Test środowiska OK", "Iconi"

; Najedź na okno Internet Explorera i naciśnij F7: wynik trafia do Schowka (zastępuje skopiowane makro).
F7:: {
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
        if (visible != "")
            report .= "`nStrona: " WebProp(doc, "URL") "`n" visible
    }
    A_Clipboard := report
    MsgBox report, "Przyciski strony (skopiowano do Schowka)"
}

F8:: {
    MouseGetPos &startX, &startY
    MouseMove startX + 80, startY + 80, 10
    Sleep 300
    MouseMove startX, startY, 10
    SendText "TEST_AHK_OK_123"
    Sleep 100
    Send "{Enter}"
}

F9:: {
    rawClip := Trim(A_Clipboard)
    if (rawClip = "") {
        MsgBox "Schowek jest pusty! Skopiuj makro ze strony webowej.", "Błąd", "Icon!"
        return
    }

    lines := StrSplit(rawClip, "`n", "`r")
    wanted := Map() ; bony z linii WEBLIST

    for index, line in lines {
        line := Trim(line)
        if (line = "" || SubStr(line, 1, 1) = "#")
            continue

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
                ; WEBROWS|okno|siatka|kolumna od 0|przycisk|następna strona|najwyżej ile wierszy: wiersz z listy WEBLIST i pustą kolumną dostaje kliknięcie przycisku
                if (parts.Length < 7) {
                    MsgBox "Linia WEBROWS wymaga okna, siatki, kolumny, przycisku, następnej strony i limitu.", "Błąd makra", "Icon!"
                    return
                }
                if (wanted.Count = 0) {
                    MsgBox "Makro nie ma listy bonów (linie WEBLIST), więc niczego nie kliknie.", "Błąd makra", "Icon!"
                    return
                }
                MsgBox WebEachRow(Trim(parts[2]), Trim(parts[3]), Integer(Trim(parts[4])), Trim(parts[5]), Trim(parts[6]), Integer(Trim(parts[7])), wanted), "Makro zakończone", "Iconi"
                return
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
    
    TrayTip "Zakończono", "Makro ze schowka zostało wykonane.", 1
}

Esc:: {
    ExitApp
}

; --- Strony w Internet Explorerze: czekanie na okno i klikanie przycisków przez HTML, bez współrzędnych ---

; Zwraca "", gdy kliknięto, a inaczej opis problemu. Okno i przycisk mogą pojawić się w trakcie czekania.
WebClick(title, button, timeoutMs, onlyTick := false) {
    deadline := A_TickCount + timeoutMs
    windowFound := false
    Loop {
        for hwnd in WinGetList(title) {
            windowFound := true
            for doc in HtmlDocuments(hwnd) {
                for element in ClickableElements(doc) {
                    if (IsButton(element, button) && !WebProp(element, "disabled") && IsShown(element)) {
                        if (onlyTick && WebProp(element, "checked"))
                            return ""
                        try WinActivate hwnd
                        try {
                            ClickWithoutWaiting(doc, element)
                            return ""
                        }
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
                        ClickWithoutWaiting(doc, item.row)
                        return WaitForSelection(doc, grid, item, deadline)
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

; Obsługuje bony z listy strona po stronie: tylko wiersz z listy i z pustą kolumną dostaje kliknięcie, najwyżej raz, a pętla
; czeka na wpisanie wyniku. Kończy się, gdy każdy bon z listy ma wynik, po czym podsumowanie mówi, czego nie było.
WebEachRow(title, grid, column, button, nextPage, maxCount, wanted) {
    handled := Map()
    results := Map() ; klucz z listy -> wartość kolumny
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
        if !WaitForRowResult(title, grid, column, key, 30000)
            return Summary("ZATRZYMANE: nie widać wyniku przy wierszu " key ". Sprawdź go ręcznie, makro nie kliknie go ponownie.")
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
GridRows(doc, grid) {
    rows := []
    try {
        if (doc.readyState != "complete")
            return rows
        found := doc.getElementsByTagName("tr")
        Loop found.length {
            row := found.item(A_Index - 1)
            if RegExMatch(WebProp(row, "outerHTML"), "setSelectedLigne\('" grid "',[^,]+,(\d+),", &match)
                rows.Push({row: row, index: match[1]})
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
                for element in ClickableElements(doc) {
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

; Linia raportu F7. Przyciski Mercalys to ikony bez tekstu, więc opis bywa tylko w title albo w obrazku.
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