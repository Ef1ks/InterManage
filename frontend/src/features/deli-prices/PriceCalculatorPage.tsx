import { Navigate, Route, Routes, useNavigate, useParams } from 'react-router'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { useCalculation } from '@/features/deli-prices/api'
import { CalculationEditor } from '@/features/deli-prices/CalculationEditor'
import { CalculationsList } from '@/features/deli-prices/CalculationsList'
import { pricePaths } from '@/features/deli-prices/format'
import { useCurrentStore } from '@/features/stores/current-store'
import type { StoreSummary } from '@/lib/types'

/**
 * Kalkulator cen garmażu: lista kalkulacji z wyszukiwarką i kreator, w którym przepis składa się z produktów
 * kartoteki sklepu. Koszt 1 kg liczy się z bieżących cen zakupu.
 */
export function PriceCalculatorPage() {
  const store = useCurrentStore()
  if (!store) {
    return (
      <>
        <PageHeader title="Kalkulator cen garmażu" />
        <FullPageMessage
          title="Brak sklepu"
          description="Kalkulacje korzystają z kartoteki i cen zakupu konkretnego sklepu."
          inline
        />
      </>
    )
  }
  return <StoreCalculator key={store.id} store={store} />
}

function StoreCalculator({ store }: { store: StoreSummary }) {
  return (
    <Routes>
      <Route index element={<CalculationsList store={store} />} />
      <Route path="nowa" element={<CalculationEditor storeId={store.id} />} />
      <Route path=":calculationId" element={<SavedCalculation storeId={store.id} />} />
      <Route path="*" element={<Navigate to={pricePaths.list} replace />} />
    </Routes>
  )
}

function SavedCalculation({ storeId }: { storeId: string }) {
  const { calculationId } = useParams()
  const { data: calculation, isPending, isError } = useCalculation(storeId, calculationId)
  const navigate = useNavigate()

  if (isError) {
    return (
      <FullPageMessage
        title="Nie znaleziono kalkulacji"
        description="Mogła zostać usunięta albo należy do innego sklepu."
        action={{ label: 'Wróć do listy', onClick: () => navigate(pricePaths.list) }}
        inline
      />
    )
  }
  if (isPending) {
    return <div aria-hidden className="h-64 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
  }
  // Po zapisie i odświeżeniu cen kreator startuje od nowa z zapisanego stanu (i bez niezapisanych zmian).
  return (
    <CalculationEditor
      key={`${calculation.updatedAt}-${calculation.pricesRefreshedAt}`}
      storeId={storeId}
      calculation={calculation}
    />
  )
}
