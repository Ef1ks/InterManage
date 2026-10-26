import { Navigate, Route, Routes } from 'react-router'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { DocumentDetail } from '@/features/documents/DocumentDetail'
import { DocumentsList } from '@/features/documents/DocumentsList'
import { DocumentTypesGrid } from '@/features/documents/DocumentTypesGrid'
import { documentPaths } from '@/features/documents/format'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import type { StoreSummary } from '@/lib/types'

/**
 * Dokumenty magazynowe sklepu z raportu Wejścia/Wyjścia: kafelki typów (przyjęcia, likwidacje, zwroty...),
 * w każdym lista dokumentów tego typu, a w dokumencie jego linie.
 */
export function DocumentsPage() {
  const store = useCurrentStore()
  if (!store) {
    return (
      <>
        <PageHeader title="Dokumenty" />
        <FullPageMessage title="Brak sklepu" description="Dokumenty należą do konkretnego sklepu." inline />
      </>
    )
  }
  return <StoreDocuments key={store.id} store={store} />
}

function StoreDocuments({ store }: { store: StoreSummary }) {
  return (
    <>
      <PageHeader title="Dokumenty" description={storeLabel(store)} />
      <Routes>
        <Route index element={<DocumentTypesGrid storeId={store.id} />} />
        <Route path=":type" element={<DocumentsList storeId={store.id} />} />
        <Route path=":type/:documentId" element={<DocumentDetail storeId={store.id} />} />
        <Route path="*" element={<Navigate to={documentPaths.overview} replace />} />
      </Routes>
    </>
  )
}
