import { createSlice, type PayloadAction } from '@reduxjs/toolkit'

import { isUuid } from '@/lib/uuid'

/** Stan interfejsu (nie danych z API - te trzyma TanStack Query). */
type UiState = {
  mobileNavOpen: boolean
  /** Sklep wybrany w przełączniku. Lista dostępnych sklepów przychodzi z /auth/me. */
  currentStoreId: string | null
  /** Schowane podpowiedzi "jak to działa" (identyfikatory), zapamiętane w przeglądarce. */
  hiddenHints: string[]
}

const CURRENT_STORE_KEY = 'intermanage.currentStoreId'
const HIDDEN_HINTS_KEY = 'intermanage.hiddenHints'

/** Wybór sklepu przetrwa odświeżenie strony; bez dostępu do localStorage zaczynamy od pierwszego sklepu. */
function readStoredStoreId(): string | null {
  try {
    const value = localStorage.getItem(CURRENT_STORE_KEY)
    // Zapis sprzed przejścia na UUID (liczba) jest pomijany - wtedy startujemy od pierwszego sklepu.
    return value && isUuid(value) ? value : null
  } catch {
    return null
  }
}

export function persistCurrentStoreId(storeId: string) {
  try {
    localStorage.setItem(CURRENT_STORE_KEY, storeId)
  } catch {
    // Tryb prywatny albo zablokowane dane strony: wybór działa do odświeżenia.
  }
}

function readHiddenHints(): string[] {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(HIDDEN_HINTS_KEY) ?? '[]')
    return Array.isArray(value) ? value.filter((hint) => typeof hint === 'string') : []
  } catch {
    return []
  }
}

export function persistHiddenHints(hints: string[]) {
  try {
    localStorage.setItem(HIDDEN_HINTS_KEY, JSON.stringify(hints))
  } catch {
    // Bez localStorage podpowiedź wróci po odświeżeniu strony.
  }
}

const initialState: UiState = {
  mobileNavOpen: false,
  currentStoreId: readStoredStoreId(),
  hiddenHints: readHiddenHints(),
}

const uiSlice = createSlice({
  name: 'ui',
  initialState,
  reducers: {
    toggleMobileNav(state) {
      state.mobileNavOpen = !state.mobileNavOpen
    },
    closeMobileNav(state) {
      state.mobileNavOpen = false
    },
    setCurrentStore(state, action: PayloadAction<string>) {
      state.currentStoreId = action.payload
    },
    setHintHidden(state, action: PayloadAction<{ hint: string; hidden: boolean }>) {
      const { hint, hidden } = action.payload
      state.hiddenHints = state.hiddenHints.filter((h) => h !== hint)
      if (hidden) state.hiddenHints.push(hint)
    },
  },
})

export const { toggleMobileNav, closeMobileNav, setCurrentStore, setHintHidden } = uiSlice.actions
export default uiSlice.reducer
