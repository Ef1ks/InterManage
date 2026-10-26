import { configureStore } from '@reduxjs/toolkit'
import { useDispatch, useSelector } from 'react-redux'

import deliOrderDraftReducer, { persistDrafts } from './deliOrderDraftSlice'
import uiReducer from './uiSlice'

export const store = configureStore({
  reducer: {
    ui: uiReducer,
    deliOrderDraft: deliOrderDraftReducer,
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

export type RootState = ReturnType<typeof store.getState>
export type AppDispatch = typeof store.dispatch

export const useAppDispatch = useDispatch.withTypes<AppDispatch>()
export const useAppSelector = useSelector.withTypes<RootState>()
