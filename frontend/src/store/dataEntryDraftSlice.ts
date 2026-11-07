import { createSlice, type PayloadAction } from '@reduxjs/toolkit'

import type { DataEntryRow } from '@/features/data-entry/rows'
import type { DataEntryCorrection } from '@/lib/types'
import { isUuid } from '@/lib/uuid'

/**
 * Korekta wczytana do tabeli: co koryguje i bon, do którego należy. `delivery`: `undefined` = jeszcze nie wybrany
 * (bon faktury korygowanej podpina się sam), `null` = do wybrania, `id` puste = numer wpisany ręcznie, spoza raportów.
 */
export type DraftCorrection = DataEntryCorrection & {
  delivery?: { id: string | null; number: string; date: string | null; supplierName: string | null } | null
}

/**
 * Faktura, z której pochodzą wiersze (do nazwy pliku i podpowiedzi dostawcy dla AI). Sumy netto i VAT z dokumentu
 * porównujemy z sumami tabeli; przy dopisaniu kolejnej faktury się sumują. Brak w starszych szkicach. Dokument
 * wczytany z pliku XML bez faktury z KSeF ma `id` null, a numer, NIP i datę z pliku (`fromXml` = nazwa pliku).
 * `supplier` to dostawca z listy sklepu wybrany zamiast sprzedawcy: idzie do pliku XML, a `number` to wtedy numer wpisany
 * ręcznie albo pusty; `sellerNip` zostaje NIP-em sprzedawcy z faktury (przypięcia kodów pozycji są u niego).
 */
export type DraftInvoice = {
  id: string | null
  number: string | null
  sellerName: string | null
  sellerNip: string | null
  issueDate?: string | null
  fromXml?: string | null
  netTotal?: number | null
  vatTotal?: number | null
  supplier?: { id: string; name: string; nip: string | null } | null
  correction?: DraftCorrection | null
}

/**
 * Tabela wprowadzania danych w jednym sklepie, zanim trafi do pliku. `deliOrderId` to zamówienie garmażu wstawione do
 * tabeli: pasek nad tabelą kończy je ilościami z wierszy. Brak w starszych szkicach.
 */
export type DataEntryDraft = { invoice: DraftInvoice | null; rows: DataEntryRow[]; deliOrderId?: string | null }

/**
 * Ostatni zapis pliku XML tabeli w sklepie: kiedy, nazwa pliku i `signature` (pozycje i nagłówek pliku jako JSON),
 * po którym widać zmiany po zapisie. `document` to dostawca i numer dokumentu z chwili zapisu: po ich zmianie tabela jest
 * innym dokumentem i zapis jej nie dotyczy (brak w starszych zapisach). Osobno od szkicu, żeby cofanie zmian (Ctrl+Z)
 * nie gubiło informacji o zapisie; znika po wczytaniu innego dokumentu albo wyczyszczeniu tabeli.
 */
export type SavedXml = { at: string; fileName: string; signature: string; document?: string }

type DraftState = { drafts: Record<string, DataEntryDraft>; saved: Record<string, SavedXml> }

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
        saved: Object.fromEntries(
          Object.entries(value.saved ?? {}).filter(
            ([store, saved]) => isUuid(store) && typeof saved?.signature === 'string' && typeof saved.at === 'string',
          ),
        ),
      }
    }
  } catch {
    // Brak localStorage albo zepsuty JSON.
  }
  return { drafts: {}, saved: {} }
}

/** Kopia w przeglądarce, bez pustych tabel. */
export function persistDataEntryDrafts(state: DraftState) {
  const drafts = Object.fromEntries(Object.entries(state.drafts).filter(([, draft]) => draft.rows.length > 0))
  const saved = Object.fromEntries(Object.entries(state.saved).filter(([store]) => store in drafts))
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ drafts, saved }))
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
    setDataEntrySaved(state, action: PayloadAction<{ storeId: string; saved: SavedXml | null }>) {
      if (action.payload.saved) state.saved[action.payload.storeId] = action.payload.saved
      else delete state.saved[action.payload.storeId]
    },
  },
})

export const { setDataEntryDraft, setDataEntrySaved } = dataEntryDraftSlice.actions
export default dataEntryDraftSlice.reducer
