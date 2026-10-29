import { createSlice, type PayloadAction } from '@reduxjs/toolkit'

import { isUuid } from '@/lib/uuid'

/** Stan interfejsu (nie danych z API - te trzyma TanStack Query). */
type UiState = {
  mobileNavOpen: boolean
  /** Sklep wybrany w przełączniku. Lista dostępnych sklepów przychodzi z /auth/me. */
  currentStoreId: string | null
  /** Schowane podpowiedzi "jak to działa" (identyfikatory), zapamiętane w przeglądarce. */
  hiddenHints: string[]
  /**
   * Kody dostawców ukrytych na listach dokumentów, osobno dla każdego sklepu i typu dokumentów (klucz
   * `hiddenSuppliersKey`), zapamiętane w przeglądarce.
   */
  hiddenDocumentSuppliers: Record<string, string[]>
}

const CURRENT_STORE_KEY = 'intermanage.currentStoreId'
const HIDDEN_HINTS_KEY = 'intermanage.hiddenHints'
const HIDDEN_DOCUMENT_SUPPLIERS_KEY = 'intermanage.hiddenDocumentSuppliers'

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

/** Klucz ukrytych dostawców: sklep i typ dokumentów (kod typu, np. PZ). */
export const hiddenSuppliersKey = (storeId: string, typeCode: string) => `${storeId}:${typeCode.toUpperCase()}`

function readHiddenDocumentSuppliers(): Record<string, string[]> {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(HIDDEN_DOCUMENT_SUPPLIERS_KEY) ?? '{}')
    if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
    const entries = Object.entries(value).map(([key, codes]): [string, string[]] => [
      key,
      Array.isArray(codes) ? codes.filter((code) => typeof code === 'string') : [],
    ])
    // Zapis sprzed podziału na typy (klucz = sam sklep) był ustawiany głównie na przyjęciach, więc trafia do PZ.
    const result: Record<string, string[]> = {}
    entries.filter(([key]) => !key.includes(':')).forEach(([storeId, codes]) => (result[hiddenSuppliersKey(storeId, 'PZ')] = codes))
    entries.filter(([key]) => key.includes(':')).forEach(([key, codes]) => (result[key] = codes))
    return result
  } catch {
    return {}
  }
}

export function persistHiddenDocumentSuppliers(suppliers: Record<string, string[]>) {
  try {
    localStorage.setItem(HIDDEN_DOCUMENT_SUPPLIERS_KEY, JSON.stringify(suppliers))
  } catch {
    // Bez localStorage ukryci dostawcy wrócą po odświeżeniu strony.
  }
}

const initialState: UiState = {
  mobileNavOpen: false,
  currentStoreId: readStoredStoreId(),
  hiddenHints: readHiddenHints(),
  hiddenDocumentSuppliers: readHiddenDocumentSuppliers(),
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
    setHiddenDocumentSuppliers(state, action: PayloadAction<{ key: string; codes: string[] }>) {
      state.hiddenDocumentSuppliers[action.payload.key] = action.payload.codes
    },
  },
})

export const { toggleMobileNav, closeMobileNav, setCurrentStore, setHintHidden, setHiddenDocumentSuppliers } =
  uiSlice.actions
export default uiSlice.reducer
