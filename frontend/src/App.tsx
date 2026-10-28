import type { ReactNode } from 'react'
import { createBrowserRouter, Navigate, RouterProvider } from 'react-router'

import { AppLayout } from '@/components/layout/AppLayout'
import { AccountPage } from '@/features/auth/AccountPage'
import { ActivationPage } from '@/features/auth/ActivationPage'
import { LoginPage } from '@/features/auth/LoginPage'
import { RequireAuth } from '@/features/auth/RequireAuth'
import { SetPasswordPage } from '@/features/auth/SetPasswordPage'
import { DeliOrderPage } from '@/features/deli-order/DeliOrderPage'
import { PriceCalculatorPage } from '@/features/deli-prices/PriceCalculatorPage'
import { SalesPage } from '@/features/sales/SalesPage'
import { DocumentsPage } from '@/features/documents/DocumentsPage'
import { ImportPage } from '@/features/imports/ImportPage'
import { KsefPage } from '@/features/ksef/KsefPage'
import { RolesPage } from '@/features/settings/RolesPage'
import { StoreSettingsPage } from '@/features/store-settings/StoreSettingsPage'
import { StoresPage } from '@/features/settings/StoresPage'
import { UsersPage } from '@/features/settings/UsersPage'
import { TOOL_ROUTES } from '@/features/tools/categories'
import { ToolPlaceholderPage } from '@/features/tools/ToolPlaceholderPage'
import type { PermissionCategory } from '@/lib/types'
import { DashboardPage } from '@/pages/DashboardPage'

/** Gotowe moduły; reszta kategorii pokazuje stronę "w przygotowaniu". */
const TOOL_PAGES: Partial<Record<PermissionCategory, ReactNode>> = {
  SALES: <SalesPage />,
  IMPORTS: <ImportPage />,
  DELI_ORDERS: <DeliOrderPage />,
  DOCUMENTS: <DocumentsPage />,
  DELI_PRICES: <PriceCalculatorPage />,
  KSEF_INVOICES: <KsefPage />,
}

const toolRoutes = (Object.keys(TOOL_ROUTES) as PermissionCategory[]).map((category) => ({
  // Gwiazdka: moduły mają własne podstrony (np. /zamowienie-garmazu/przychodzace).
  path: `${TOOL_ROUTES[category].path}/*`,
  element: (
    <RequireAuth permission={{ category }}>
      {TOOL_PAGES[category] ?? <ToolPlaceholderPage category={category} />}
    </RequireAuth>
  ),
}))

const router = createBrowserRouter([
  { path: '/logowanie', element: <LoginPage /> },
  { path: '/aktywacja', element: <ActivationPage /> },
  {
    path: '/ustaw-haslo',
    element: (
      <RequireAuth allowPasswordSetup>
        <SetPasswordPage />
      </RequireAuth>
    ),
  },
  {
    element: (
      <RequireAuth>
        <AppLayout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <DashboardPage /> },
      { path: '/konto', element: <AccountPage /> },
      ...toolRoutes,
      {
        // Ustawienia wybranego sklepu: sekcje według uprawnień (budżet rejonów, sklepy zaprzyjaźnione).
        path: '/ustawienia-sklepu/*',
        element: (
          <RequireAuth>
            <StoreSettingsPage />
          </RequireAuth>
        ),
      },
      {
        path: '/ustawienia/sklepy',
        element: (
          <RequireAuth ownerOnly>
            <StoresPage />
          </RequireAuth>
        ),
      },
      {
        path: '/ustawienia/role',
        element: (
          <RequireAuth ownerOnly>
            <RolesPage />
          </RequireAuth>
        ),
      },
      {
        path: '/ustawienia/uzytkownicy',
        element: (
          <RequireAuth ownerOnly>
            <UsersPage />
          </RequireAuth>
        ),
      },
      { path: '*', element: <Navigate to="/" replace /> },
    ],
  },
])

export function App() {
  return <RouterProvider router={router} />
}
