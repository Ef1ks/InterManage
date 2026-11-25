import { createSlice, type PayloadAction } from '@reduxjs/toolkit'

import { isUuid } from '@/lib/uuid'

/** Artykuł w koszyku zakupowym raportów: u jednego dostawcy, z ilością i ceną zakupu netto. */
export type BasketLine = {
  itm8: string
  ean: string | null
  name: string | null
  brand: string | null
  /** Kod dostawcy bez zer wiodących; pusty, gdy dostawca jest znany tylko z nazwy. */
  supplierCode: string
  supplierName: string
  /** Baza (538, 88110): zamówienie idzie plikiem do programu sklepu, ilość w kartonach. */
  base: boolean
  /** Cena zakupu netto z ostatniej dostawy od tego dostawcy (u bazy bez dostawy: z kartoteki). */
  purchasePrice: number | null
  quantity: number
}

/** Kolejność wierszy porównania: sprzedaż (jak z serwera), najpierw spadki albo najpierw wzrosty. */
export type ComparisonSort = 'default' | 'decline' | 'growth'
/** Zamówienia dla dostawców spoza bazy: plik Excel na dostawcę albo jeden plik ze wszystkimi. */
export type BasketFileMode = 'perSupplier' | 'single'

export type ComparisonSettings = { sort: ComparisonSort; basketFiles: BasketFileMode }

/** Najwięcej różnych dostawców w jednym koszyku. */
export const MAX_BASKET_SUPPLIERS = 10

export const DEFAULT_COMPARISON_SETTINGS: ComparisonSettings = { sort: 'default', basketFiles: 'perSupplier' }

type BasketState = { baskets: Record<string, BasketLine[]>; settings: ComparisonSettings }

const STORAGE_KEY = 'intermanage.salesBasket'

const sameLine = (a: BasketLine, b: { itm8: string; supplierCode: string; supplierName: string }) =>
  a.itm8 === b.itm8 && a.supplierCode === b.supplierCode && (a.supplierCode !== '' || a.supplierName === b.supplierName)

function readStored(): BasketState {
  try {
    const value = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? 'null') as Partial<BasketState> | null
    if (value) {
      const baskets =
        value.baskets && typeof value.baskets === 'object'
          ? Object.fromEntries(
              Object.entries(value.baskets).filter(([store, lines]) => isUuid(store) && Array.isArray(lines)),
            )
          : {}
      const settings = { ...DEFAULT_COMPARISON_SETTINGS, ...(value.settings ?? {}) }
      return { baskets, settings }
    }
  } catch {
    // Brak localStorage albo zepsuty JSON.
  }
  return { baskets: {}, settings: DEFAULT_COMPARISON_SETTINGS }
}

export function persistSalesBasket(state: BasketState) {
  const baskets = Object.fromEntries(Object.entries(state.baskets).filter(([, lines]) => lines.length > 0))
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ baskets, settings: state.settings }))
  } catch {
    // Tryb prywatny albo pełny dysk: koszyk działa do odświeżenia strony.
  }
}

/**
 * Koszyki zakupowe, po jednym na sklep, wspólne dla raportów (porównanie sprzedaży, braki towaru, brakujący
 * asortyment), i ustawienia porównania (kolejność wierszy, pliki zamówień). Zostają po odświeżeniu strony (kopia w localStorage); usunięcie wraca toastem „Przywróć”.
 */
const salesBasketSlice = createSlice({
  name: 'salesBasket',
  initialState: readStored(),
  reducers: {
    /** Dodaje artykuły u dostawców; ten sam artykuł u tego samego dostawcy dostaje ilość dodaną do dotychczasowej. */
    addBasketLines(state, action: PayloadAction<{ storeId: string; lines: BasketLine[] }>) {
      const lines = (state.baskets[action.payload.storeId] ??= [])
      for (const line of action.payload.lines) {
        const known = lines.find((candidate) => sameLine(candidate, line))
        if (known) {
          known.quantity = Math.round((known.quantity + line.quantity) * 1000) / 1000
          known.purchasePrice = line.purchasePrice
        } else {
          lines.push(line)
        }
      }
    },
    setBasketQuantity(
      state,
      action: PayloadAction<{
        storeId: string
        itm8: string
        supplierCode: string
        supplierName: string
        quantity: number
      }>,
    ) {
      const line = state.baskets[action.payload.storeId]?.find((candidate) => sameLine(candidate, action.payload))
      if (line) line.quantity = action.payload.quantity
    },
    /** Zastępuje cały koszyk sklepu (usunięcie, zamówienie i „Przywróć”). */
    setBasket(state, action: PayloadAction<{ storeId: string; lines: BasketLine[] }>) {
      state.baskets[action.payload.storeId] = action.payload.lines
    },
    setComparisonSettings(state, action: PayloadAction<Partial<ComparisonSettings>>) {
      state.settings = { ...state.settings, ...action.payload }
    },
  },
})

export const { addBasketLines, setBasketQuantity, setBasket, setComparisonSettings } = salesBasketSlice.actions
export default salesBasketSlice.reducer
