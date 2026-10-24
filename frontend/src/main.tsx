import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClientProvider } from '@tanstack/react-query'
import { ReactQueryDevtools } from '@tanstack/react-query-devtools'
import { Provider } from 'react-redux'

import { App } from '@/App'
import { Toaster } from '@/components/ui/sonner'
import { queryClient } from '@/lib/query-client'
import { store } from '@/store'

import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Provider store={store}>
      <QueryClientProvider client={queryClient}>
        <App />
        <Toaster position="top-right" richColors />
        <ReactQueryDevtools buttonPosition="bottom-left" />
      </QueryClientProvider>
    </Provider>
  </StrictMode>,
)
