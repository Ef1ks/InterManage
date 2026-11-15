import { configureStore } from '@reduxjs/toolkit'
import { useDispatch, useSelector } from 'react-redux'

import baseDeliveryDraftReducer, { persistBaseDeliveryDrafts } from './baseDeliveryDraftSlice'
import dataEntryDraftReducer, { persistDataEntryDrafts } from './dataEntryDraftSlice'
import deliOrderDraftReducer, { persistDrafts } from './deliOrderDraftSlice'
import meaOrderDraftReducer, { persistMeaOrderDrafts } from './meaOrderDraftSlice'
import protocolEntryDraftReducer, { persistProtocolEntryDrafts } from './protocolEntryDraftSlice'
import promoSetupDraftReducer, { persistPromoSetup } from './promoSetupDraftSlice'
import uiReducer from './uiSlice'

export const store = configureStore({
  reducer: {
    ui: uiReducer,
    deliOrderDraft: deliOrderDraftReducer,
    dataEntryDraft: dataEntryDraftReducer,
    baseDeliveryDraft: baseDeliveryDraftReducer,
    promoSetupDraft: promoSetupDraftReducer,
    protocolEntryDraft: protocolEntryDraftReducer,
    meaOrderDraft: meaOrderDraftReducer,
  },
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
    persistDataEntryDrafts(drafts)
  }
})

// Tabela dostawy z bazy też.
let savedBaseDelivery = store.getState().baseDeliveryDraft
store.subscribe(() => {
  const drafts = store.getState().baseDeliveryDraft
  if (drafts !== savedBaseDelivery) {
    savedBaseDelivery = drafts
    persistBaseDeliveryDrafts(drafts)
  }
})

// Tabela protokołów też.
let savedProtocolEntry = store.getState().protocolEntryDraft
store.subscribe(() => {
  const drafts = store.getState().protocolEntryDraft
  if (drafts !== savedProtocolEntry) {
    savedProtocolEntry = drafts
    persistProtocolEntryDrafts(drafts)
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
