import { Navigate, Route, Routes } from 'react-router'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { useCurrentStore } from '@/features/stores/current-store'
import { SupplierCard } from '@/features/suppliers/SupplierCard'
import { SuppliersList } from '@/features/suppliers/SuppliersList'
import { supplierPaths } from '@/features/suppliers/format'

/**
 * Dostawcy sklepu: z listy z systemu sklepu (Import danych) i dodani ręcznie. Karta dostawcy zbiera to, czego nie ma
 * w raporcie: stronę internetową, telefon, e-mail, notatki i kontakty do przedstawicieli, biura i księgowości.
 */
export function SuppliersPage() {
  const store = useCurrentStore()
  if (!store) {
    return (
      <>
        <PageHeader title="Dostawcy" />
        <FullPageMessage title="Brak sklepu" description="Dostawcy są prowadzeni osobno dla każdego sklepu." inline />
      </>
    )
  }
  return (
    <Routes key={store.id}>
      <Route index element={<SuppliersList store={store} />} />
      <Route path=":supplierId" element={<SupplierCard storeId={store.id} />} />
      <Route path="*" element={<Navigate to={supplierPaths.list} replace />} />
    </Routes>
  )
}
