import { Route, Routes } from 'react-router'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { CheckEditor } from '@/features/bakery-check/CheckEditor'
import { CheckStart } from '@/features/bakery-check/CheckStart'
import { useCurrentStore } from '@/features/stores/current-store'

/**
 * Weryfikacja piekarni: faktura zbiorcza podaje ilości zsumowane z kilku WZ, więc błąd pojedynczego dokumentu widać
 * dopiero po wpisaniu ilości z każdego z nich. Start: dostawca i jego bony, potem kafelki WZ i podsumowanie.
 */
export function BakeryCheckPage() {
  const store = useCurrentStore()
  if (!store) {
    return (
      <>
        <PageHeader title="Weryfikacja piekarni" />
        <FullPageMessage title="Brak sklepu" description="Weryfikację prowadzi się w konkretnym sklepie." inline />
      </>
    )
  }
  return (
    <Routes>
      <Route index element={<CheckStart storeId={store.id} />} />
      <Route path=":checkId" element={<CheckEditor key={store.id} storeId={store.id} />} />
    </Routes>
  )
}
