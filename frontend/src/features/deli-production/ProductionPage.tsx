import type { ReactNode } from 'react'
import { NavLink, Navigate, Route, Routes } from 'react-router'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { productionPaths } from '@/features/deli-production/api'
import { BaseEditor } from '@/features/deli-production/BaseEditor'
import { CardEditor } from '@/features/deli-production/CardEditor'
import { CardsList } from '@/features/deli-production/CardsList'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import type { StoreSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

/**
 * Karty produkcji garmażu (CCP1): formularz do wydruku, karta z ilościami wpisanymi po zeskanowaniu kodów, baza
 * artykułów ze schematami parametrów i karty sklepów zaprzyjaźnionych do wydruku.
 */
export function ProductionPage() {
  const store = useCurrentStore()
  if (!store) {
    return (
      <>
        <PageHeader title="Karty produkcji" />
        <FullPageMessage title="Brak sklepu" description="Karty produkcji prowadzi się w konkretnym sklepie." inline />
      </>
    )
  }
  return <StoreProduction key={store.id} store={store} />
}

function StoreProduction({ store }: { store: StoreSummary }) {
  return (
    <Routes>
      <Route path="karta/nowa" element={<CardEditor store={store} />} />
      <Route path="karta/:cardId" element={<CardEditor store={store} />} />
      <Route
        path="*"
        element={
          <>
            <PageHeader title="Karty produkcji" description={storeLabel(store)} />
            <nav aria-label="Karty produkcji" className="mb-6 flex gap-1 overflow-x-auto rounded-xl bg-muted p-1 sm:w-fit">
              <SubNavLink to={productionPaths.cards} end>
                Karty
              </SubNavLink>
              <SubNavLink to={productionPaths.partners}>Sklepy zaprzyjaźnione</SubNavLink>
              <SubNavLink to={productionPaths.base}>Baza artykułów</SubNavLink>
            </nav>
            <Routes>
              <Route index element={<CardsList store={store} scope="OWN" />} />
              <Route path="sklepy-zaprzyjaznione" element={<CardsList store={store} scope="PARTNERS" />} />
              <Route path="baza" element={<BaseEditor store={store} />} />
              <Route path="*" element={<Navigate to={productionPaths.cards} replace />} />
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
