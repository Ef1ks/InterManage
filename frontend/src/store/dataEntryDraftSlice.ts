import { createSlice, type PayloadAction } from '@reduxjs/toolkit'

import type { DataEntryRow } from '@/features/data-entry/rows'
import { isUuid } from '@/lib/uuid'

/** Faktura, z której pochodzą wiersze (do nazwy pliku i podpowiedzi dostawcy dla AI). */
export type DraftInvoice = { id: string; number: string; sellerName: string | null; sellerNip: string }

/** Tabela wprowadzania danych w jednym sklepie, zanim trafi do pliku. */
export type DataEntryDraft = { invoice: DraftInvoice | null; rows: DataEntryRow[] }

type DraftState = { drafts: Record<string, DataEntryDraft> }

export const EMPTY_DATA_ENTRY: DataEntryDraft = { invoice: null, rows: [] }

const STORAGE_KEY = 'intermanage.dataEntryDrafts'

function readStored(): DraftState {
  try {
    const value = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? 'null') as Partial<DraftState> | null
    if (value && typeof value.drafts === 'object' && value.drafts) {
      return {
        drafts: Object.fromEntries(
          Object.entries(value.drafts).filter(([store, draft]) => isUuid(store) && Array.isArray(draft?.rows)),
        ),
      }
    }
  } catch {
    // Brak localStorage albo zepsuty JSON.
  }
  return { drafts: {} }
}

/** Kopia w przeglądarce, bez pustych tabel. */
export function persistDataEntryDrafts(state: DraftState) {
  const drafts = Object.fromEntries(Object.entries(state.drafts).filter(([, draft]) => draft.rows.length > 0))
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ drafts }))
  } catch {
    // Tryb prywatny albo pełny dysk: tabela działa do odświeżenia strony.
  }
}

/**
 * Tabela wprowadzania danych per sklep: zostaje po przejściu do innej zakładki i odświeżeniu strony (kopia
 * w localStorage), znika po wyczyszczeniu ("Przywróć" w toaście oddaje ją z powrotem).
 */
const dataEntryDraftSlice = createSlice({
  name: 'dataEntryDraft',
  initialState: readStored(),
  reducers: {
    setDataEntryDraft(state, action: PayloadAction<{ storeId: string; draft: DataEntryDraft }>) {
      state.drafts[action.payload.storeId] = action.payload.draft
    },
  },
})

export const { setDataEntryDraft } = dataEntryDraftSlice.actions
export default dataEntryDraftSlice.reducer
