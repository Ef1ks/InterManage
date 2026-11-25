import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { CollectorSettings } from '@/features/collectors/CollectorSettings'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'

/** Urządzenia: instalator i łączenie kolektorów ze sklepem (`INVENTORY:EDIT`). */
export function CollectorDevicesPage() {
  const store = useCurrentStore()
  return (
    <>
      <PageHeader title="Kolektory: urządzenia" description={store ? storeLabel(store) : undefined} />
      {store ? (
        <div className="max-w-2xl">
          <CollectorSettings key={store.id} storeId={store.id} />
        </div>
      ) : (
        <FullPageMessage title="Brak sklepu" description="Kolektor jest połączony z konkretnym sklepem." inline />
      )}
    </>
  )
}
