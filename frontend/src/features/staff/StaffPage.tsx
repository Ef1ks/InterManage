import { Navigate, Route, Routes } from 'react-router'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { SubNavLink } from '@/components/SubNavLink'
import { staffPaths, useCompanyStores } from '@/features/staff/api'
import { EmployeesList } from '@/features/staff/EmployeesList'
import { StructurePanel } from '@/features/staff/StructurePanel'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import type { StoreSummary } from '@/lib/types'

/** Pracownicy firmy ze sklepów dostępnych dla użytkownika oraz wspólne działy i stanowiska. */
export function StaffPage() {
  const store = useCurrentStore()
  if (!store) {
    return (
      <>
        <PageHeader title="Pracownicy" />
        <FullPageMessage title="Brak sklepu" description="Pracownicy należą do firmy sklepu." inline />
      </>
    )
  }
  return <CompanyStaff key={store.id} store={store} />
}

function CompanyStaff({ store }: { store: StoreSummary }) {
  const { data: stores = [] } = useCompanyStores(store.id)
  return (
    <>
      <PageHeader
        title="Pracownicy"
        description={
          // Lista startuje od bieżącego sklepu, więc opis mówi o nim, a pozostałe sklepy firmy są obok.
          stores.length > 1
            ? `${storeLabel(store)} · inne sklepy firmy: ${stores
                .filter((item) => item.id !== store.id)
                .map((item) => item.number)
                .join(', ')}`
            : storeLabel(store)
        }
      />
      <nav aria-label="Pracownicy" className="mb-6 flex gap-1 overflow-x-auto rounded-xl bg-muted p-1 sm:w-fit">
        <SubNavLink to={staffPaths.employees} end>
          Pracownicy
        </SubNavLink>
        <SubNavLink to={staffPaths.structure}>Działy i stanowiska</SubNavLink>
      </nav>
      <Routes>
        <Route index element={<EmployeesList storeId={store.id} stores={stores} />} />
        <Route path="dzialy" element={<StructurePanel storeId={store.id} stores={stores} />} />
        <Route path="*" element={<Navigate to={staffPaths.employees} replace />} />
      </Routes>
    </>
  )
}
