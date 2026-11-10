import { createSlice, type PayloadAction } from '@reduxjs/toolkit'

import { addDays, todayIso } from '@/lib/dates'
import type { PromotionSetupArticle } from '@/lib/types'
import { isUuid } from '@/lib/uuid'

/**
 * Wiersz promocji: wpisany kod, artykuł ({@code null} = kodu nie ma w kartotece), cena i wybrana przecena w %.
 * {@code dateTo}: własny koniec promocji artykułu, tylko w trybie makra (w plikach .PRO okres ma kafelek).
 */
export type PromoRow = {
  id: string
  code: string
  article: PromotionSetupArticle | null
  price: number | null
  discount: number | null
  dateTo?: string
}

/** {@code macro}: jeden kafelek, każdy artykuł z własnym końcem, promocje ustawia makro; {@code pro}: pliki .PRO. */
export type PromoMode = 'macro' | 'pro'

/** Kafelek: osobne artykuły i okres. {@code number} to dwie ostatnie cyfry kodu taryfy, nadawany raz i nie wraca. */
export type PromoTile = { id: string; number: number; dateFrom: string; dateTo: string; rows: PromoRow[] }

/** {@code operation}: cztery losowe cyfry kodu taryfy, wspólne dla kafelków tej pracy. */
export type PromoSetupDraft = { operation: string; nextNumber: number; tiles: PromoTile[] }

type DraftState = {
  /** Tryb plików .PRO. */
  drafts: Record<string, PromoSetupDraft>
  /** Tryb makra: osobna praca, zawsze z jednym kafelkiem; jego {@code dateTo} to koniec dla nowych artykułów. */
  macroDrafts: Record<string, PromoSetupDraft>
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

const validDrafts = (drafts: Record<string, PromoSetupDraft>) =>
  Object.fromEntries(Object.entries(drafts).filter(([store, draft]) => isUuid(store) && Array.isArray(draft?.tiles)))

function readStored(): DraftState {
  try {
    const value = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? 'null') as Partial<DraftState> | null
    if (value && typeof value.drafts === 'object' && value.drafts) {
      return {
        drafts: validDrafts(value.drafts),
        macroDrafts: validDrafts(value.macroDrafts ?? {}),
        levels: value.levels ?? {},
      }
    }
  } catch {
    // Brak localStorage albo zepsuty JSON.
  }
  return { drafts: {}, macroDrafts: {}, levels: {} }
}

/** Kopia w przeglądarce. */
export function persistPromoSetup(state: DraftState) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
  } catch {
    // Tryb prywatny albo pełny dysk: praca trwa do odświeżenia strony.
  }
}

type ModeAction = { storeId: string; mode: PromoMode }
type TileAction = ModeAction & { tileId: string }

const draftsOf = (state: DraftState, mode: PromoMode) => (mode === 'macro' ? state.macroDrafts : state.drafts)

const tileOf = (state: DraftState, { storeId, mode, tileId }: TileAction) =>
  draftsOf(state, mode)[storeId]?.tiles.find((tile) => tile.id === tileId)

/**
 * Promocje w przygotowaniu per sklep: zostają po przejściu do innej zakładki i odświeżeniu strony (kopia
 * w localStorage). Zmiany idą osobnymi akcjami, bo artykuły dochodzą po odpowiedzi serwera, gdy czytnik skanuje
 * już kolejny kod.
 */
const promoSetupDraftSlice = createSlice({
  name: 'promoSetupDraft',
  initialState: readStored(),
  reducers: {
    setPromoDraft(state, action: PayloadAction<ModeAction & { draft: PromoSetupDraft }>) {
      draftsOf(state, action.payload.mode)[action.payload.storeId] = action.payload.draft
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
      const tile = tileOf(state, action.payload)
      if (tile) Object.assign(tile, action.payload.changes)
    },
    addPromoRows(state, action: PayloadAction<TileAction & { rows: PromoRow[] }>) {
      tileOf(state, action.payload)?.rows.push(...action.payload.rows)
    },
    updatePromoRow(
      state,
      action: PayloadAction<
        TileAction & { rowId: string; changes: Partial<Pick<PromoRow, 'price' | 'discount' | 'dateTo'>> }
      >,
    ) {
      const row = tileOf(state, action.payload)?.rows.find((candidate) => candidate.id === action.payload.rowId)
      if (row) Object.assign(row, action.payload.changes)
    },
    setPromoLevels(state, action: PayloadAction<{ storeId: string; levels: number[] }>) {
      state.levels[action.payload.storeId] = action.payload.levels
    },
  },
})

export const { setPromoDraft, addPromoTile, updatePromoTile, addPromoRows, updatePromoRow, setPromoLevels } =
  promoSetupDraftSlice.actions
export default promoSetupDraftSlice.reducer
