import { Navigate, Route, Routes } from 'react-router'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { GratisReport } from '@/features/sales/GratisReport'
import { LowMarginReport } from '@/features/sales/LowMarginReport'
import { OverpaymentReport } from '@/features/sales/OverpaymentReport'
import { PeriodSummaryReport } from '@/features/sales/PeriodSummaryReport'
import { PriceCheckReport } from '@/features/sales/PriceCheckReport'
import { PromotionReport } from '@/features/sales/PromotionReport'
import { ProtocolReport } from '@/features/sales/ProtocolReport'
import { ReportsGrid } from '@/features/sales/ReportsGrid'
import { SalesComparisonReport } from '@/features/sales/SalesComparisonReport'
import { TopSalesReport } from '@/features/sales/TopSalesReport'
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
        <Route path="podsumowanie" element={<PeriodSummaryReport storeId={store.id} />} />
        <Route path="topy" element={<TopSalesReport storeId={store.id} />} />
        <Route path="koszty-garmazu" element={<SalesComparisonReport key="deli" storeId={store.id} kind="deli" />} />
        <Route path="zakup-bazowy-poza-baza" element={<PriceCheckReport storeId={store.id} />} />
        <Route path="przeplacone-artykuly" element={<OverpaymentReport storeId={store.id} />} />
        <Route path="za-niskie-marze" element={<LowMarginReport storeId={store.id} />} />
        <Route path="gratisy" element={<GratisReport storeId={store.id} />} />
        <Route path="protokoly" element={<ProtocolReport storeId={store.id} />} />
        <Route path="promocje" element={<PromotionReport storeId={store.id} />} />
        <Route path="*" element={<Navigate to={salesPaths.overview} replace />} />
      </Routes>
    </>
  )
}
