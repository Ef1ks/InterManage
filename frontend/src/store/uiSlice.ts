import { createSlice } from '@reduxjs/toolkit'

/** Stan interfejsu (nie danych z API - te trzyma TanStack Query). */
type UiState = {
  mobileNavOpen: boolean
}

const initialState: UiState = {
  mobileNavOpen: false,
}

const uiSlice = createSlice({
  name: 'ui',
  initialState,
  reducers: {
    toggleMobileNav(state) {
      state.mobileNavOpen = !state.mobileNavOpen
    },
    closeMobileNav(state) {
      state.mobileNavOpen = false
    },
  },
})

export const { toggleMobileNav, closeMobileNav } = uiSlice.actions
export default uiSlice.reducer
