import { Route, Routes } from 'react-router'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { RecordPage } from '@/features/staff-records/RecordPage'
import { RecordsList } from '@/features/staff-records/RecordsList'
import { useCurrentStore } from '@/features/stores/current-store'

/** Kadry: osobne narzędzie z własnym uprawnieniem, bo profil ma dane wrażliwe. */
export function StaffRecordsPage() {
  const store = useCurrentStore()
  if (!store) {
    return (
      <>
        <PageHeader title="Kadry" />
        <FullPageMessage title="Brak sklepu" description="Pracownicy należą do firmy sklepu." inline />
      </>
    )
  }
  return (
    <Routes key={store.id}>
      <Route index element={<RecordsList storeId={store.id} />} />
      <Route path=":id" element={<RecordPage storeId={store.id} />} />
    </Routes>
  )
}
