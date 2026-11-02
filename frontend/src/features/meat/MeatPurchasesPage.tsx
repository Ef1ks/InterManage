import type { ReactNode } from 'react'
import { NavLink, Navigate, Route, Routes } from 'react-router'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { meatPaths } from '@/features/meat/api'
import { DeliveriesList } from '@/features/meat/DeliveriesList'
import { DeliveryEditor } from '@/features/meat/DeliveryEditor'
import { MeatReportView } from '@/features/meat/MeatReportView'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import type { StoreSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

/**
 * Zakup mięsa: ceny zakupu netto za kg z dokumentów WZ dostawców (faktura od SCA PR przychodzi po kilku dniach)
 * i porównanie z ceną sprzedaży w sklepach, do których pracownik ma dostęp.
 */
export function MeatPurchasesPage() {
  const store = useCurrentStore()
  if (!store) {
    return (
      <>
        <PageHeader title="Zakup mięsa" />
        <FullPageMessage title="Brak sklepu" description="Dokumenty WZ są wprowadzane w konkretnym sklepie." inline />
      </>
    )
  }
  return <StoreMeatPurchases key={store.id} store={store} />
}

function StoreMeatPurchases({ store }: { store: StoreSummary }) {
  return (
    <Routes>
      <Route path="wz/nowy" element={<DeliveryEditor store={store} />} />
      <Route path="wz/:deliveryId" element={<DeliveryEditor store={store} />} />
      <Route
        path="*"
        element={
          <>
            <PageHeader title="Zakup mięsa" description={storeLabel(store)} />
            <nav aria-label="Zakup mięsa" className="mb-6 flex gap-1 overflow-x-auto rounded-xl bg-muted p-1 sm:w-fit">
              <SubNavLink to={meatPaths.deliveries} end>
                Dokumenty WZ
              </SubNavLink>
              <SubNavLink to={meatPaths.report}>Porównanie cen</SubNavLink>
            </nav>
            <Routes>
              <Route index element={<DeliveriesList store={store} />} />
              <Route path="porownanie" element={<MeatReportView store={store} />} />
              <Route path="*" element={<Navigate to={meatPaths.deliveries} replace />} />
            </Routes>
          </>
        }
      />
    </Routes>
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
