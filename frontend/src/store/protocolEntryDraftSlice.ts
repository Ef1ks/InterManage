import { createSlice, type PayloadAction } from '@reduxjs/toolkit'

import type { DataEntryRow } from '@/features/data-entry/rows'
import { isUuid } from '@/lib/uuid'

/** Tabela „Protokołów” w jednym sklepie: same artykuły z EAN-em i ilością. */
export type ProtocolEntryDraft = { rows: DataEntryRow[] }

export const EMPTY_PROTOCOL_ENTRY: ProtocolEntryDraft = { rows: [] }

type DraftState = { drafts: Record<string, ProtocolEntryDraft> }

const STORAGE_KEY = 'intermanage.protocolEntryDrafts'

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
export function persistProtocolEntryDrafts(state: DraftState) {
  const drafts = Object.fromEntries(
    Object.entries(state.drafts).filter(([, draft]) => draft.rows.some((row) => row.ean !== '' || row.name !== '')),
  )
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ drafts }))
  } catch {
    // Tryb prywatny albo pełny dysk: tabela działa do odświeżenia strony.
  }
}

/**
 * Tabela protokołów per sklep: zostaje po przejściu do innej zakładki i odświeżeniu strony (kopia w localStorage),
 * znika po wyczyszczeniu („Przywróć” w toaście oddaje ją z powrotem).
 */
const protocolEntryDraftSlice = createSlice({
  name: 'protocolEntryDraft',
  initialState: readStored(),
  reducers: {
    setProtocolEntryDraft(state, action: PayloadAction<{ storeId: string; draft: ProtocolEntryDraft }>) {
      state.drafts[action.payload.storeId] = action.payload.draft
    },
  },
})

export const { setProtocolEntryDraft } = protocolEntryDraftSlice.actions
export default protocolEntryDraftSlice.reducer
