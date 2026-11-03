#Requires AutoHotkey v2.0
#SingleInstance Force
SendMode "Event"
SetKeyDelay 30, 30

; Współrzędne względem całego EKRANU
CoordMode "Mouse", "Screen"
CoordMode "ToolTip", "Screen"

global radarActive := false
global recordedPoints := ""

TrayTip "Tryb Screen gotowy!", "F6 = Celownik | F7 = Zapisz punkt | F9 = Odtwórz | ESC = Wyjdź", 1

; ==============================================================================
; [F6] - CELOWNIK (Pokazuje aktualne X, Y na ekranie na żywo)
; ==============================================================================
F6:: {
    global radarActive
    radarActive := !radarActive
    if (radarActive) {
        SetTimer UpdateRadar, 50
    } else {
        SetTimer UpdateRadar, 0
        ToolTip()
    }
}

UpdateRadar() {
    MouseGetPos &screenX, &screenY, &winId, &ctrlName
    
    info := "TRYB: EKRAN (Screen)`n"
    info .= "X: " . screenX . " | Y: " . screenY . "`n"
    info .= "Kontrolka: " . (ctrlName != "" ? ctrlName : "brak") . "`n"
    info .= "[F7] = Zapisz ten punkt"
    
    ToolTip info, screenX + 20, screenY + 20
}

; ==============================================================================
; [F7] - ZAPISZ PUNKT EKRANU DO SCHOWKA
; Zapisuje w bezpiecznym formacie: CLICK|X|Y
; ==============================================================================
F7:: {
    global recordedPoints
    MouseGetPos &x, &y, &winId, &ctrlName
    
    line := "CLICK|" . x . "|" . y . "`r`n"
    recordedPoints .= line
    A_Clipboard := recordedPoints
    
    ToolTip "Zapisano punkt: X=" . x . " Y=" . y, x + 20, y - 20
    SetTimer () => ToolTip(), -800
}

; ==============================================================================
; [Shift + F7] - WYCZYŚĆ LISTĘ ZAPISANYCH PUNKTÓW
; ==============================================================================
+F7:: {
    global recordedPoints := ""
    A_Clipboard := ""
    TrayTip "Wyczyszczono", "Lista punktów wyzerowana.", 1
}

; ==============================================================================
; [F8] - TEST PISANIA
; ==============================================================================
F8:: {
    SendText "TEST_AHK_OK_123"
    Sleep 100
    Send "{Enter}"
}

; ==============================================================================
; [F9] - WYKONAJ MAKRO ZE SCHOWKA
; Obsługuje zarówno separator '|' jak i ';'
; ==============================================================================
F9:: {
    rawClip := Trim(A_Clipboard)
    if (rawClip = "") {
        MsgBox "Schowek jest pusty! Skopiuj makro.", "Błąd", "Icon!"
        return
    }

    lines := StrSplit(rawClip, "`n", "`r")
    
    for index, line in lines {
        line := Trim(line)
        if (line = "" || SubStr(line, 1, 1) = "#")
            continue

        ; Wykryj separator (| lub ;)
        sep := InStr(line, "|") ? "|" : ";"
        parts := StrSplit(line, sep)
        cmd := StrUpper(Trim(parts[1]))

        switch cmd {
            case "CLICK":
                if (parts.Length >= 3) {
                    Click Integer(Trim(parts[2])), Integer(Trim(parts[3]))
                }
            case "RCLICK":
                if (parts.Length >= 3) {
                    Click Integer(Trim(parts[2])), Integer(Trim(parts[3])), "Right"
                }
            case "DBLCLICK":
                if (parts.Length >= 3) {
                    Click Integer(Trim(parts[2])), Integer(Trim(parts[3])), 2
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
        }
        Sleep 50
    }
    
    TrayTip "Zakończono", "Makro wykonane.", 1
}

; ==============================================================================
; [ESC] - AWARYJNE ZATRZYMANIE
; ==============================================================================
Esc:: {
    ExitApp
}