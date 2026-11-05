import { createSlice, type PayloadAction } from '@reduxjs/toolkit'

import { addDays, todayIso } from '@/lib/dates'
import type { PromotionSetupArticle } from '@/lib/types'
import { isUuid } from '@/lib/uuid'

/** Wiersz promocji: wpisany kod, artykuł ({@code null} = kodu nie ma w kartotece), cena i wybrana przecena w %. */
export type PromoRow = {
  id: string
  code: string
  article: PromotionSetupArticle | null
  price: number | null
  discount: number | null
}

/** Kafelek: osobne artykuły i okres. {@code number} to dwie ostatnie cyfry kodu taryfy, nadawany raz i nie wraca. */
export type PromoTile = { id: string; number: number; dateFrom: string; dateTo: string; rows: PromoRow[] }

/** {@code operation}: cztery losowe cyfry kodu taryfy, wspólne dla kafelków tej pracy. */
export type PromoSetupDraft = { operation: string; nextNumber: number; tiles: PromoTile[] }

type DraftState = {
  drafts: Record<string, PromoSetupDraft>
  /** Stopnie automatycznych przecen w % sklepu; bez wpisu domyślne. */
  levels: Record<string, number[]>
}

export const DEFAULT_LEVELS = [30, 50, 70]

const STORAGE_KEY = 'intermanage.promoSetup'

const randomOperation = () => String(1000 + Math.floor(Math.random() * 9000))

export const newTile = (number: number): PromoTile => ({
  id: crypto.randomUUID(),
  number,
  dateFrom: addDays(todayIso(), 1),
  dateTo: '',
  rows: [],
})

/** Nowa praca: jeden pusty kafelek i świeże cyfry kodu taryfy. */
export const newPromoDraft = (): PromoSetupDraft => ({
  operation: randomOperation(),
  nextNumber: 2,
  tiles: [newTile(1)],
})

function readStored(): DraftState {
  try {
    const value = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? 'null') as Partial<DraftState> | null
    if (value && typeof value.drafts === 'object' && value.drafts) {
      return {
        drafts: Object.fromEntries(
          Object.entries(value.drafts).filter(([store, draft]) => isUuid(store) && Array.isArray(draft?.tiles)),
        ),
        levels: value.levels ?? {},
      }
    }
  } catch {
    // Brak localStorage albo zepsuty JSON.
  }
  return { drafts: {}, levels: {} }
}

/** Kopia w przeglądarce. */
export function persistPromoSetup(state: DraftState) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
  } catch {
    // Tryb prywatny albo pełny dysk: praca trwa do odświeżenia strony.
  }
}

type TileAction = { storeId: string; tileId: string }

/**
 * Promocje w przygotowaniu per sklep: zostają po przejściu do innej zakładki i odświeżeniu strony (kopia
 * w localStorage). Zmiany idą osobnymi akcjami, bo artykuły dochodzą po odpowiedzi serwera, gdy czytnik skanuje
 * już kolejny kod.
 */
const promoSetupDraftSlice = createSlice({
  name: 'promoSetupDraft',
  initialState: readStored(),
  reducers: {
    setPromoDraft(state, action: PayloadAction<{ storeId: string; draft: PromoSetupDraft }>) {
      state.drafts[action.payload.storeId] = action.payload.draft
    },
    addPromoTile(state, action: PayloadAction<{ storeId: string }>) {
      const draft = (state.drafts[action.payload.storeId] ??= newPromoDraft())
      draft.tiles.push(newTile(draft.nextNumber))
      draft.nextNumber += 1
    },
    updatePromoTile(
      state,
      action: PayloadAction<TileAction & { changes: Partial<Pick<PromoTile, 'dateFrom' | 'dateTo'>> }>,
    ) {
      const { storeId, tileId, changes } = action.payload
      const tile = state.drafts[storeId]?.tiles.find((candidate) => candidate.id === tileId)
      if (tile) Object.assign(tile, changes)
    },
    addPromoRows(state, action: PayloadAction<TileAction & { rows: PromoRow[] }>) {
      const { storeId, tileId, rows } = action.payload
      state.drafts[storeId]?.tiles.find((tile) => tile.id === tileId)?.rows.push(...rows)
    },
    updatePromoRow(
      state,
      action: PayloadAction<TileAction & { rowId: string; changes: Pick<PromoRow, 'price' | 'discount'> }>,
    ) {
      const { storeId, tileId, rowId, changes } = action.payload
      const tile = state.drafts[storeId]?.tiles.find((candidate) => candidate.id === tileId)
      const row = tile?.rows.find((candidate) => candidate.id === rowId)
      if (row) Object.assign(row, changes)
    },
    setPromoLevels(state, action: PayloadAction<{ storeId: string; levels: number[] }>) {
      state.levels[action.payload.storeId] = action.payload.levels
    },
  },
})

export const { setPromoDraft, addPromoTile, updatePromoTile, addPromoRows, updatePromoRow, setPromoLevels } =
  promoSetupDraftSlice.actions
export default promoSetupDraftSlice.reducer
