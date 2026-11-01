import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClientProvider } from '@tanstack/react-query'
import { ReactQueryDevtools } from '@tanstack/react-query-devtools'
import { Provider } from 'react-redux'
import { ThemeProvider } from 'next-themes'

import { App } from '@/App'
import { Toaster } from '@/components/ui/sonner'
import { queryClient } from '@/lib/query-client'
import { THEME_STORAGE_KEY } from '@/lib/theme'
import { store } from '@/store'

import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {/* Klasa .dark na <html>; domyślnie motyw z systemu. disableTransitionOnChange: przejścia kolorów w komponentach
        nie ciągną się przy zmianie, całość przenika useThemeChoice. */}
    <ThemeProvider attribute="class" defaultTheme="system" enableSystem storageKey={THEME_STORAGE_KEY} disableTransitionOnChange>
      <Provider store={store}>
        <QueryClientProvider client={queryClient}>
          <App />
          <Toaster position="top-right" richColors />
          <ReactQueryDevtools buttonPosition="bottom-left" />
        </QueryClientProvider>
      </Provider>
    </ThemeProvider>
  </StrictMode>,
)
