import { createSlice, type PayloadAction } from '@reduxjs/toolkit'

import type { OrderUnit, ProductSearchResult } from '@/lib/types'
import { isUuid } from '@/lib/uuid'

/** Pozycja spoza formularza: produkt z kartoteki dostawcy, dział, do którego trafi, i wpisana ilość. */
export type DraftExtra = { product: ProductSearchResult; sectionId: string; unit: OrderUnit; quantity: string }

/** Niewysłane zamówienie u jednego dostawcy. Ilości jako wpisany tekst, bo walidujemy je dopiero przy wysyłce. */
export type DeliOrderDraft = {
  quantities: Record<string, string>
  extras: DraftExtra[]
  deliveryDate: string
  activeSectionId: string | null
}

type DraftState = {
  /** Wybrany dostawca w danym sklepie (sklep -> sklep-dostawca). */
  supplierByStore: Record<string, string>
  /** Szkice po kluczu draftKey(sklep, dostawca). */
  drafts: Record<string, DeliOrderDraft>
}

export const EMPTY_DRAFT: DeliOrderDraft = { quantities: {}, extras: [], deliveryDate: '', activeSectionId: null }

export function draftKey(storeId: string, supplierId: string) {
  return `${storeId}:${supplierId}`
}

/** Czy w szkicu jest coś do stracenia (przycisk czyszczenia pokazujemy tylko wtedy). */
export function hasDraftContent(draft: DeliOrderDraft) {
  return (
    Object.values(draft.quantities).some((value) => value.trim() !== '') ||
    draft.extras.length > 0 ||
    draft.deliveryDate !== ''
  )
}

const STORAGE_KEY = 'intermanage.deliOrderDrafts'
/** Szkice z poprzedniej wizyty; uszkodzony albo niedostępny zapis oznacza start od zera. */
function readStoredDrafts(): DraftState {
  try {
    const value = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? 'null') as Partial<DraftState> | null
    if (value && typeof value.drafts === 'object' && typeof value.supplierByStore === 'object') {
      // Szkice sprzed przejścia na UUID (klucze z liczb, np. "1:2") nie pasują już do żadnego sklepu.
      const isUuidKey = (key: string) => key.split(':').every((part) => isUuid(part))
      return {
        supplierByStore: Object.fromEntries(
          Object.entries(value.supplierByStore ?? {}).filter(([store, supplier]) => isUuid(store) && isUuid(supplier)),
        ),
        drafts: Object.fromEntries(Object.entries(value.drafts ?? {}).filter(([key]) => isUuidKey(key))),
      }
    }
  } catch {
    // Brak localStorage albo zepsuty JSON.
  }
  return { supplierByStore: {}, drafts: {} }
}

/** Zapisuje szkice w przeglądarce, bez pustych (zostają tylko te, w których jest co stracić). */
export function persistDrafts(state: DraftState) {
  const drafts = Object.fromEntries(Object.entries(state.drafts).filter(([, draft]) => hasDraftContent(draft)))
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ supplierByStore: state.supplierByStore, drafts }))
  } catch {
    // Tryb prywatny albo pełny dysk: szkic działa do odświeżenia strony.
  }
}

const initialState: DraftState = readStoredDrafts()

/**
 * Zamówienie garmażu w trakcie wypełniania: zostaje po przejściu do innej zakładki, odświeżeniu strony
 * i zamknięciu karty (kopia w localStorage), znika po wysłaniu albo wyczyszczeniu.
 */
const deliOrderDraftSlice = createSlice({
  name: 'deliOrderDraft',
  initialState,
  reducers: {
    selectSupplier(state, action: PayloadAction<{ storeId: string; supplierId: string }>) {
      state.supplierByStore[action.payload.storeId] = action.payload.supplierId
    },
    updateDraft(state, action: PayloadAction<{ key: string; change: Partial<DeliOrderDraft> }>) {
      const { key, change } = action.payload
      state.drafts[key] = { ...(state.drafts[key] ?? EMPTY_DRAFT), ...change }
    },
    setDraftQuantity(state, action: PayloadAction<{ key: string; itemId: string; value: string }>) {
      const { key, itemId, value } = action.payload
      const draft = (state.drafts[key] ??= { ...EMPTY_DRAFT, quantities: {} })
      draft.quantities[itemId] = value
    },
    /** Czyści ilości, dopiski i datę; zostaje otwarty dział. */
    clearDraft(state, action: PayloadAction<string>) {
      const activeSectionId = state.drafts[action.payload]?.activeSectionId ?? null
      state.drafts[action.payload] = { ...EMPTY_DRAFT, activeSectionId }
    },
    restoreDraft(state, action: PayloadAction<{ key: string; draft: DeliOrderDraft }>) {
      state.drafts[action.payload.key] = action.payload.draft
    },
  },
})

export const { selectSupplier, updateDraft, setDraftQuantity, clearDraft, restoreDraft } = deliOrderDraftSlice.actions
export default deliOrderDraftSlice.reducer
