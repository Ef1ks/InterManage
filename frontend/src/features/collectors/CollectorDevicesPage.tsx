import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { CollectorSettings } from '@/features/collectors/CollectorSettings'
import { PrinterSettings } from '@/features/collectors/PrinterSettings'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'

/** Urządzenia: instalator, łączenie kolektorów ze sklepem i drukarki Bluetooth (`INVENTORY:EDIT`). */
export function CollectorDevicesPage() {
  const store = useCurrentStore()
  return (
    <>
      <PageHeader title="Kolektory: urządzenia" description={store ? storeLabel(store) : undefined} />
      {store ? (
        <div className="grid max-w-2xl gap-4">
          <CollectorSettings key={store.id} storeId={store.id} />
          <PrinterSettings key={`printers-${store.id}`} storeId={store.id} />
        </div>
      ) : (
        <FullPageMessage title="Brak sklepu" description="Kolektor jest połączony z konkretnym sklepem." inline />
      )}
    </>
  )
}
