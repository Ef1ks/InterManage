#Requires AutoHotkey v2.0
#SingleInstance Force
 SendMode "Event" ; Tryb Event ze wsparciem opóźnień (najlepszy dla starszych aplikacji okienkowych)
SetKeyDelay 30, 30
CoordMode "Mouse", "Screen" ; Współrzędne względem całego ekranu

; Powiadomienie przy starcie - od razu wiesz, że .exe przeszedł przez AppLocker
TrayTip "Odtwarzacz Makr gotowy!", "F8 = Test | F9 = Start ze Schowka | ESC = Wyłącz", 1
MsgBox "Plik .exe działa poprawnie!`n`nSkróty:`n[F8] - Test ruchu myszy i pisania`n[F9] - Wykonaj makro ze Schowka`n[ESC] - Awaryjne wyłączenie", "Test środowiska OK", "Iconi"

; ==============================================================================
; [F8] - SZYBKI TEST: Sprawdza czy klikanie i pisanie działa w docelowym programie
; ==============================================================================
F8:: {
    MouseGetPos &startX, &startY
    ; Przesuń kursor lekko w prawo i w dół, żeby wizualnie potwierdzić sterowanie myszą
    MouseMove startX + 80, startY + 80, 10
    Sleep 300
    MouseMove startX, startY, 10
    
    ; Wpisz tekst testowy i naciśnij Enter w aktywnym polu (np. w Notatniku lub programie)
    SendText "TEST_AHK_OK_123"
    Sleep 100
    Send "{Enter}"
}

; ==============================================================================
; [F9] - WYKONAJ MAKRO ZE SCHOWKA (Wyeksportowane ze strony webowej)
; Format w schowku (każda instrukcja w nowej linii, separator to średnik ';'):
; CLICK;500;300
; SLEEP;500
; TEXT;Przykładowy towar
; KEY;{Tab}
; ==============================================================================
F9:: {
    rawClip := Trim(A_Clipboard)
    if (rawClip = "") {
        MsgBox "Schowek jest pusty! Skopiuj makro ze strony webowej.", "Błąd", "Icon!"
        return
    }

    lines := StrSplit(rawClip, "`n", "`r")
    
    for index, line in lines {
        line := Trim(line)
        if (line = "" || SubStr(line, 1, 1) = "#") ; Ignoruj puste linie i komentarze
            continue

        parts := StrSplit(line, ";")
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
                    ; SendText wpisuje ciąg znaków dosłownie (nie interpretuje znaków ! + ^ # jako skrótów)
                    SendText parts[2]
                }
            case "KEY":
                if (parts.Length >= 2) {
                    ; Send obsługuje klawisze specjalne np. {Enter}, {Tab}, {Down}, ^a (Ctrl+A)
                    Send Trim(parts[2])
                }
            case "SLEEP":
                if (parts.Length >= 2) {
                    Sleep Integer(Trim(parts[2]))
                }
        }
        Sleep 50 ; Bezpieczny bufor między krokami
    }
    
    TrayTip "Zakończono", "Makro ze schowka zostało wykonane.", 1
}

; ==============================================================================
; [ESC] - AWARYJNE ZATRZYMANIE (Kill-switch)
; ==============================================================================
Esc:: {
    ExitApp
}