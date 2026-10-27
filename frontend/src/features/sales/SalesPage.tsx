import { Navigate, Route, Routes } from 'react-router'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { ReportsGrid } from '@/features/sales/ReportsGrid'
import { SalesComparisonReport } from '@/features/sales/SalesComparisonReport'
import { salesPaths } from '@/features/sales/paths'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import type { StoreSummary } from '@/lib/types'

/** Sprzedaż i raporty: kafelek na każdy raport, żeby nie zaśmiecać ekranu; raport otwiera się na osobnej podstronie. */
export function SalesPage() {
  const store = useCurrentStore()
  if (!store) {
    return (
      <>
        <PageHeader title="Sprzedaż i raporty" />
        <FullPageMessage title="Brak sklepu" description="Raporty liczą się ze sprzedaży konkretnego sklepu." inline />
      </>
    )
  }
  return <StoreReports key={store.id} store={store} />
}

function StoreReports({ store }: { store: StoreSummary }) {
  return (
    <>
      <PageHeader title="Sprzedaż i raporty" description={storeLabel(store)} />
      <Routes>
        <Route index element={<ReportsGrid storeId={store.id} />} />
        <Route path="porownanie" element={<SalesComparisonReport storeId={store.id} />} />
        <Route path="*" element={<Navigate to={salesPaths.overview} replace />} />
      </Routes>
    </>
  )
}
