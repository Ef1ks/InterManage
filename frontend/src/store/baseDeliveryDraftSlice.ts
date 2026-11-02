import { createSlice, type PayloadAction } from '@reduxjs/toolkit'

import type { DataEntryRow } from '@/features/data-entry/rows'
import { isUuid } from '@/lib/uuid'

/**
 * Co pokazuje tabela dostawy z bazy: wybrane nośniki albo weryfikacja cen z całego dokumentu (warzywa: rejon 14,
 * mięso pakowane: rejon 26).
 */
export type BaseDeliveryView = 'carriers' | 'vegetables' | 'meat'

/** Otwarty dokument dostawy z bazy (bon). */
export type DraftDelivery = { id: string; number: string; deliveryDate: string }

/**
 * Tabela „Dostawy z bazy” w jednym sklepie. `previous` to tabela nośników sprzed weryfikacji warzyw albo mięsa:
 * „Wróć do nośników” ją przywraca.
 */
export type BaseDeliveryDraft = {
  delivery: DraftDelivery | null
  view: BaseDeliveryView
  carriers: string[]
  rows: DataEntryRow[]
  previous: { carriers: string[]; rows: DataEntryRow[] } | null
}

export const EMPTY_BASE_DELIVERY: BaseDeliveryDraft = {
  delivery: null,
  view: 'carriers',
  carriers: [],
  rows: [],
  previous: null,
}

type DraftState = { drafts: Record<string, BaseDeliveryDraft> }

const STORAGE_KEY = 'intermanage.baseDeliveryDrafts'

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
export function persistBaseDeliveryDrafts(state: DraftState) {
  const drafts = Object.fromEntries(Object.entries(state.drafts).filter(([, draft]) => draft.delivery !== null))
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ drafts }))
  } catch {
    // Tryb prywatny albo pełny dysk: tabela działa do odświeżenia strony.
  }
}

/**
 * Tabela dostawy z bazy per sklep: zostaje po przejściu do innej zakładki i odświeżeniu strony (kopia
 * w localStorage), znika po wyczyszczeniu („Przywróć” w toaście oddaje ją z powrotem).
 */
const baseDeliveryDraftSlice = createSlice({
  name: 'baseDeliveryDraft',
  initialState: readStored(),
  reducers: {
    setBaseDeliveryDraft(state, action: PayloadAction<{ storeId: string; draft: BaseDeliveryDraft }>) {
      state.drafts[action.payload.storeId] = action.payload.draft
    },
  },
})

export const { setBaseDeliveryDraft } = baseDeliveryDraftSlice.actions
export default baseDeliveryDraftSlice.reducer
