import { configureStore } from '@reduxjs/toolkit'
import { useDispatch, useSelector } from 'react-redux'

import baseDeliveryDraftReducer, { persistBaseDeliveryDrafts } from './baseDeliveryDraftSlice'
import dataEntryDraftReducer, { persistDataEntryDrafts } from './dataEntryDraftSlice'
import deliOrderDraftReducer, { persistDrafts } from './deliOrderDraftSlice'
import meaOrderDraftReducer, { persistMeaOrderDrafts } from './meaOrderDraftSlice'
import protocolEntryDraftReducer, { persistProtocolEntryDrafts } from './protocolEntryDraftSlice'
import promoSetupDraftReducer, { persistPromoSetup } from './promoSetupDraftSlice'
import salesBasketReducer, { persistSalesBasket } from './salesBasketSlice'
import uiReducer from './uiSlice'

/**
 * Zapis dużych szkiców (tabele z tysiącem wierszy) po chwili bez zmian, a nie przy każdej zmianie: zapis całej tabeli do
 * localStorage przy każdej edycji komórki zacinał tabelę. Przed zamknięciem albo schowaniem karty zapis idzie od razu.
 */
const pendingWrites = new Map<string, () => void>()
let writeTimer: ReturnType<typeof setTimeout> | undefined
function flushWrites() {
  clearTimeout(writeTimer)
  writeTimer = undefined
  const writes = [...pendingWrites.values()]
  pendingWrites.clear()
  for (const write of writes) write()
}
function persistLater(key: string, write: () => void) {
  pendingWrites.set(key, write)
  clearTimeout(writeTimer)
  writeTimer = setTimeout(flushWrites, 600)
}
if (typeof window !== 'undefined') {
  window.addEventListener('pagehide', flushWrites)
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') flushWrites()
  })
}

export const store = configureStore({
  reducer: {
    ui: uiReducer,
    deliOrderDraft: deliOrderDraftReducer,
    dataEntryDraft: dataEntryDraftReducer,
    baseDeliveryDraft: baseDeliveryDraftReducer,
    promoSetupDraft: promoSetupDraftReducer,
    protocolEntryDraft: protocolEntryDraftReducer,
    meaOrderDraft: meaOrderDraftReducer,
    salesBasket: salesBasketReducer,
  },
})

// Koszyk zakupowy porównania sprzedaży i ustawienia raportu też przetrwają odświeżenie strony.
let savedSalesBasket = store.getState().salesBasket
store.subscribe(() => {
  const state = store.getState().salesBasket
  if (state !== savedSalesBasket) {
    savedSalesBasket = state
    persistSalesBasket(state)
  }
})

// Niewysłane zamówienie garmażu ma przetrwać odświeżenie strony: zapisujemy je przy każdej zmianie.
let savedDrafts = store.getState().deliOrderDraft
store.subscribe(() => {
  const drafts = store.getState().deliOrderDraft
  if (drafts !== savedDrafts) {
    savedDrafts = drafts
    persistDrafts(drafts)
  }
})

// Tabela wprowadzania danych też przetrwa odświeżenie strony.
let savedDataEntry = store.getState().dataEntryDraft
store.subscribe(() => {
  const drafts = store.getState().dataEntryDraft
  if (drafts !== savedDataEntry) {
    savedDataEntry = drafts
    persistLater('dataEntry', () => persistDataEntryDrafts(drafts))
  }
})

// Tabela dostawy z bazy też.
let savedBaseDelivery = store.getState().baseDeliveryDraft
store.subscribe(() => {
  const drafts = store.getState().baseDeliveryDraft
  if (drafts !== savedBaseDelivery) {
    savedBaseDelivery = drafts
    persistLater('baseDelivery', () => persistBaseDeliveryDrafts(drafts))
  }
})

// Tabela protokołów też.
let savedProtocolEntry = store.getState().protocolEntryDraft
store.subscribe(() => {
  const drafts = store.getState().protocolEntryDraft
  if (drafts !== savedProtocolEntry) {
    savedProtocolEntry = drafts
    persistLater('protocolEntry', () => persistProtocolEntryDrafts(drafts))
  }
})

let savedPromoSetup = store.getState().promoSetupDraft
store.subscribe(() => {
  const state = store.getState().promoSetupDraft
  if (state !== savedPromoSetup) {
    savedPromoSetup = state
    persistPromoSetup(state)
  }
})

// Ilości wpisane w dokumentach MEA też.
let savedMeaOrder = store.getState().meaOrderDraft
store.subscribe(() => {
  const state = store.getState().meaOrderDraft
  if (state !== savedMeaOrder) {
    savedMeaOrder = state
    persistMeaOrderDrafts(state)
  }
})

export type RootState = ReturnType<typeof store.getState>
export type AppDispatch = typeof store.dispatch

export const useAppDispatch = useDispatch.withTypes<AppDispatch>()
export const useAppSelector = useSelector.withTypes<RootState>()
