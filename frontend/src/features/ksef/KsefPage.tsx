import type { ReactNode } from 'react'
import { Navigate, NavLink, Route, Routes } from 'react-router'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { useCan } from '@/features/auth/api'
import { InvoicesList } from '@/features/ksef/InvoicesList'
import { ksefPaths } from '@/features/ksef/format'
import { KsefSettingsPage } from '@/features/ksef/KsefSettingsPage'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import type { StoreSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

/** Faktury zakupowe z KSeF sklepu i (z uprawnieniem do edycji) ustawienia połączenia, w zakładkach-podstronach. */
export function KsefPage() {
  const store = useCurrentStore()
  if (!store) {
    return (
      <>
        <PageHeader title="Faktury KSeF" />
        <FullPageMessage title="Brak sklepu" description="Faktury KSeF są pobierane osobno dla każdego sklepu." inline />
      </>
    )
  }
  return <StoreKsef key={store.id} store={store} />
}

function StoreKsef({ store }: { store: StoreSummary }) {
  const canEdit = useCan('KSEF_INVOICES', 'EDIT')
  return (
    <>
      <PageHeader title="Faktury KSeF" description={storeLabel(store)} />
      {canEdit && (
        <nav aria-label="Faktury KSeF" className="mb-6 flex gap-1 overflow-x-auto rounded-xl bg-muted p-1 sm:w-fit">
          <SubNavLink to={ksefPaths.invoices} end>
            Faktury
          </SubNavLink>
          <SubNavLink to={ksefPaths.settings}>Ustawienia</SubNavLink>
        </nav>
      )}
      <Routes>
        <Route index element={<InvoicesList storeId={store.id} />} />
        {canEdit && <Route path="ustawienia" element={<KsefSettingsPage storeId={store.id} />} />}
        <Route path="*" element={<Navigate to={ksefPaths.invoices} replace />} />
      </Routes>
    </>
  )
}

function SubNavLink({ to, end, children }: { to: string; end?: boolean; children: ReactNode }) {
  return (
    <NavLink
      to={to}
      end={end}
      className={({ isActive }) =>
        cn(
          'flex shrink-0 items-center gap-2 rounded-lg px-3.5 py-1.5 text-sm font-medium whitespace-nowrap text-muted-foreground transition-[background-color,color,box-shadow] duration-150 outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50',
          isActive && 'bg-background text-foreground shadow-[0_1px_2px_rgb(0_0_0/0.08)]',
        )
      }
    >
      {children}
    </NavLink>
  )
}
