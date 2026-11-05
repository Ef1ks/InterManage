import { useMemo } from 'react'
import { CheckCheckIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { useCan } from '@/features/auth/api'
import { orderExportPath, useFulfillOrder, useOrder } from '@/features/deli-order/api'
import { storeLabel } from '@/features/stores/current-store'
import { ApiError, downloadFile } from '@/lib/api'
import { formatShortDate } from '@/lib/dates'

import { orderFulfilment } from './deli-order-rows'
import type { DataEntryRow } from './rows'

type Props = {
  storeId: string
  orderId: string
  rows: DataEntryRow[]
  /** Zamówienie zrealizowane: tabela przestaje być z nim związana. */
  onFulfilled: () => void
  /** „Nie”: zamówienie zostaje otwarte, a tabela przestaje być z nim związana. */
  onDismiss: () => void
}

/**
 * Pasek nad tabelą z wstawionym zamówieniem garmażu: „Tak” realizuje je ilościami z tabeli. Bez zapisu pliku PROTO6,
 * bo towar z tabeli idzie protokołem MM razem z tym, co jedzie obok garmażu, i drugi plik zdublowałby ruch.
 */
export function DeliOrderBanner({ storeId, orderId, rows, onFulfilled, onDismiss }: Props) {
  const canFulfil = useCan('DELI_ORDERS', 'EDIT')
  const { data: order } = useOrder(storeId, orderId)
  const fulfill = useFulfillOrder(storeId, orderId)
  const fulfilment = useMemo(() => (order ? orderFulfilment(order, rows) : null), [order, rows])

  const open =
    order &&
    order.status !== 'FULFILLED' &&
    !order.storeDeletedAt &&
    !order.formStoreDeletedAt &&
    !order.mergedIntoId &&
    !order.splitAt
  if (!canFulfil || !open || !fulfilment) return null

  const confirm = () =>
    fulfill.mutate(fulfilment.items, {
      onSuccess: () => {
        onFulfilled()
        const path = orderExportPath(storeId, orderId, 'proto')
        toast.success('Zamówienie zrealizowane', {
          description: 'Ilości z tabeli zapisane przy zamówieniu.',
          action: { label: 'Pobierz PROTO6', onClick: () => downloadFile(path, 'zamowienie.Traite0') },
          duration: 15_000,
        })
      },
      onError: (error) =>
        toast.error(error instanceof ApiError ? error.message : 'Nie udało się potwierdzić realizacji'),
    })

  const inTable = order.items.length - fulfilment.missing
  return (
    <div className="mb-4 flex flex-wrap items-center gap-x-4 gap-y-3 rounded-xl bg-amber-200 px-4 py-3 text-amber-950 dark:bg-amber-950/60 dark:text-amber-100">
      <CheckCheckIcon aria-hidden className="size-5 shrink-0" />
      <div className="min-w-0 flex-1">
        <p className="text-sm font-semibold">
          Czy zamówienie garmażu dla sklepu {storeLabel(order.store)} na {formatShortDate(order.deliveryDate)} zostało
          zakończone?
        </p>
        <p className="text-xs tabular-nums">
          Pozycje zamówienia w tabeli: {inTable} z {order.items.length}.
          {fulfilment.missing > 0 && ` Bez towaru w tabeli, zrealizowane jako 0: ${fulfilment.missing}.`}
          {fulfilment.outside > 0 && ` Spoza zamówienia, pominięte: ${fulfilment.outside}.`}
        </p>
        {fulfilment.errors.length > 0 && (
          <p className="mt-1 text-xs font-semibold text-red-800 dark:text-red-300">
            Popraw w tabeli: {fulfilment.errors.join('; ')}
          </p>
        )}
      </div>
      <div className="flex gap-2">
        <Button variant="outline" disabled={fulfill.isPending} onClick={onDismiss}>
          Nie
        </Button>
        <Button loading={fulfill.isPending} disabled={fulfilment.errors.length > 0} onClick={confirm}>
          Tak, zakończ
        </Button>
      </div>
    </div>
  )
}
