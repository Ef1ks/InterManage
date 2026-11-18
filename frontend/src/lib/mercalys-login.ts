/**
 * Hasło do Mercalys na tym komputerze (localStorage). Wspólne hasło stanowisk, nie tajne: idzie w linii WINOPEN makra,
 * a odtwarzacz od 0.54 uruchamia nim wyłączony Mercalys i loguje się sam (login wpisuje Mercalys).
 */
const KEY = 'intermanage.mercalysPassword'

export const MERCALYS_LOGIN_PLAYER_VERSION = '0.54'

export function getMercalysPassword() {
  try {
    return localStorage.getItem(KEY) ?? ''
  } catch {
    return ''
  }
}

export function setMercalysPassword(password: string) {
  try {
    if (password) localStorage.setItem(KEY, password)
    else localStorage.removeItem(KEY)
  } catch {
    // tryb prywatny: bez zapisu makro poczeka na ręczne logowanie
  }
}

/** Błąd hasła albo null. `|` rozdziela pola linii makra, a Mercalys przyjmuje najwyżej 20 znaków. */
export function mercalysPasswordError(password: string) {
  if (password.includes('|')) return 'Hasło nie może zawierać znaku |'
  if (password.length > 20) return 'Mercalys przyjmuje najwyżej 20 znaków'
  return null
}

/** Pierwsza linia makra: przywraca Mercalys, a wyłączony uruchamia i loguje zapisanym hasłem. */
export function mercalysOpenLine(window: string) {
  const password = getMercalysPassword()
  return password && !mercalysPasswordError(password) ? `WINOPEN|${window}|${password}` : `WINOPEN|${window}`
}
