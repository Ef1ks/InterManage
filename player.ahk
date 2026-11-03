#Requires AutoHotkey v2.0
#SingleInstance Force
SendMode "Event"
SetKeyDelay 30, 30
CoordMode "Mouse", "Screen"

TrayTip "Odtwarzacz Makr gotowy!", "F8 = Test | F9 = Start ze Schowka | ESC = Wyłącz", 1
MsgBox "Plik .exe działa poprawnie!`n`nSkróty:`n[F8] - Test ruchu myszy i pisania`n[F9] - Wykonaj makro ze Schowka`n[ESC] - Awaryjne wyłączenie", "Test środowiska OK", "Iconi"

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
        }
        Sleep 50
    }
    
    TrayTip "Zakończono", "Makro ze schowka zostało wykonane.", 1
}

Esc:: {
    ExitApp
}