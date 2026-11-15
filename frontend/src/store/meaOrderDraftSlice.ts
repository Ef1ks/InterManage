import { createSlice, type PayloadAction } from '@reduxjs/toolkit'

import { isUuid } from '@/lib/uuid'

/**
 * Ilości jednej pozycji MEA: kartony (COLI) na każdy rozrzut R1-R4 i mnożnik „ile w COLI” (jak w eMEA, domyślnie 1;
 * wartość = kartony × sztuk w COLI × mnożnik × cena zakupu MEA).
 */
export type MeaLineDraft = { quantities: number[]; multiplier: number }

/** Szkic zamówienia jednego MEA: pozycje po ITM8 MEA. */
export type MeaOrderDraft = Record<string, MeaLineDraft>

type DraftState = { drafts: Record<string, MeaOrderDraft> }

export const EMPTY_LINE: MeaLineDraft = { quantities: [], multiplier: 1 }

export function meaDraftKey(storeId: string, number: string) {
  return `${storeId}:${number}`
}

function hasContent(draft: MeaOrderDraft) {
  return Object.values(draft).some((line) => line.quantities.some((value) => value > 0) || line.multiplier !== 1)
}

const STORAGE_KEY = 'intermanage.meaOrderDrafts'

function readStored(): DraftState {
  try {
    const value = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? 'null') as Partial<DraftState> | null
    if (value && typeof value.drafts === 'object' && value.drafts) {
      return {
        drafts: Object.fromEntries(Object.entries(value.drafts).filter(([key]) => isUuid(key.split(':')[0] ?? ''))),
      }
    }
  } catch {
    // Brak localStorage albo zepsuty JSON.
  }
  return { drafts: {} }
}

/** Zapisuje w przeglądarce tylko szkice z wpisanymi ilościami. */
export function persistMeaOrderDrafts(state: DraftState) {
  const drafts = Object.fromEntries(Object.entries(state.drafts).filter(([, draft]) => hasContent(draft)))
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ drafts }))
  } catch {
    // Tryb prywatny albo pełny dysk: szkic działa do odświeżenia strony.
  }
}

/**
 * Ilości wpisywane w dokumencie MEA, zanim pójdą do ATHOS: zostają po wyjściu ze strony i odświeżeniu (kopia
 * w localStorage), osobno dla każdego sklepu i numeru MEA.
 */
const meaOrderDraftSlice = createSlice({
  name: 'meaOrderDraft',
  initialState: readStored(),
  reducers: {
    setMeaQuantity(state, action: PayloadAction<{ key: string; itm8: string; dispatch: number; value: number }>) {
      const { key, itm8, dispatch, value } = action.payload
      const draft = (state.drafts[key] ??= {})
      const line = (draft[itm8] ??= { quantities: [], multiplier: 1 })
      while (line.quantities.length <= dispatch) line.quantities.push(0)
      line.quantities[dispatch] = Math.max(0, Math.min(9999, Math.round(value)))
    },
    setMeaMultiplier(state, action: PayloadAction<{ key: string; itm8: string; value: number }>) {
      const { key, itm8, value } = action.payload
      const draft = (state.drafts[key] ??= {})
      const line = (draft[itm8] ??= { quantities: [], multiplier: 1 })
      line.multiplier = value > 0 ? value : 1
    },
    clearMeaDraft(state, action: PayloadAction<string>) {
      delete state.drafts[action.payload]
    },
    restoreMeaDraft(state, action: PayloadAction<{ key: string; draft: MeaOrderDraft }>) {
      state.drafts[action.payload.key] = action.payload.draft
    },
  },
})

export const { setMeaQuantity, setMeaMultiplier, clearMeaDraft, restoreMeaDraft } = meaOrderDraftSlice.actions
export default meaOrderDraftSlice.reducer
