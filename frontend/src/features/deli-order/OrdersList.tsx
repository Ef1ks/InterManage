import { ChevronRightIcon } from 'lucide-react'
import { useNavigate } from 'react-router'

import { FullPageMessage } from '@/components/FullPageMessage'
import { TableSkeletonRows } from '@/components/TableSkeleton'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useOrders } from '@/features/deli-order/api'
import { OrderDownloads } from '@/features/deli-order/OrderDownloads'
import { OrderStatusBadge } from '@/features/deli-order/OrderStatusBadge'
import { deliPaths } from '@/features/deli-order/paths'
import { storeLabel } from '@/features/stores/current-store'
import { formatShortDate } from '@/lib/dates'
import type { DeliOrderSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

const dateTime = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'short' })

/**
 * Historia zamówień: wysłane przez sklep albo przychodzące do niego jako dostawcy.
 * Przychodzące nowe są na górze i wyróżnione, dopóki ktoś nie oznaczy ich jako zrealizowane.
 */
export function OrdersList({ storeId, kind }: { storeId: string; kind: 'sent' | 'incoming' }) {
  const { data: orders, isPending, isError, refetch } = useOrders(storeId, kind)
  const navigate = useNavigate()
  const incoming = kind === 'incoming'

  if (isError) {
    return (
      <FullPageMessage
        title="Nie udało się wczytać zamówień"
        action={{ label: 'Spróbuj ponownie', onClick: () => refetch() }}
        inline
      />
    )
  }
  if (!isPending && orders.length === 0) {
    return (
      <Card className="items-center px-6 py-10 text-center">
        <p className="font-medium">{incoming ? 'Nie przyszło jeszcze żadne zamówienie' : 'Nie wysłano jeszcze żadnego zamówienia'}</p>
        <p className="max-w-sm text-sm text-muted-foreground">
          {incoming
            ? 'Zamówienia ze sklepów, które przyjęły zaproszenie do Twojego formularza, pojawią się tutaj.'
            : 'Złóż zamówienie w zakładce Zamów.'}
        </p>
      </Card>
    )
  }

  const open = (order: DeliOrderSummary) => navigate(deliPaths.orderDetail(order.id))

  return (
    <>
      <p className="mb-3 text-sm text-muted-foreground">
        {incoming
          ? 'Nowe zamówienia są na górze, na żółtym tle. Kliknij wiersz, żeby wydrukować zamówienie, poprawić ilości i oznaczyć je jako zrealizowane.'
          : 'Kliknij wiersz, żeby zobaczyć zamówienie i ilości poprawione przez dostawcę.'}
      </p>
      <Card className="py-0">
        <Table>
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead className="pl-4">{incoming ? 'Od sklepu' : 'Do sklepu'}</TableHead>
              <TableHead>Dostawa</TableHead>
              <TableHead>Złożone</TableHead>
              <TableHead className="text-right">Pozycje</TableHead>
              <TableHead>Status</TableHead>
              <TableHead className="w-20 pr-4">
                <span className="sr-only">Pobierz</span>
              </TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isPending ? (
              <TableSkeletonRows columns={6} />
            ) : (
              orders.map((order) => {
                const deleted = Boolean(order.storeDeletedAt || order.formStoreDeletedAt)
                // Nowe do realizacji: wyróżnione, dopóki nikt ich nie zrealizował ani nie usunął.
                const fresh = incoming && order.status === 'NEW' && !deleted
                return (
                  <TableRow
                    key={order.id}
                    tabIndex={0}
                    role="link"
                    aria-label={`Zamówienie ${storeLabel(incoming ? order.store : order.formStore)}, dostawa ${formatShortDate(order.deliveryDate)}`}
                    onClick={() => open(order)}
                    onKeyDown={(event) => event.key === 'Enter' && open(order)}
                    className={cn(
                      'cursor-pointer tabular-nums outline-none focus-visible:bg-muted',
                      deleted && 'text-muted-foreground',
                      fresh && 'bg-amber-50/70 hover:bg-amber-50 dark:bg-amber-500/5 dark:hover:bg-amber-500/10',
                    )}
                  >
                    <TableCell className="py-3 pl-4">
                      <div className={cn(fresh && 'font-semibold')}>{storeLabel(incoming ? order.store : order.formStore)}</div>
                      {order.submittedBy && <div className="text-xs text-muted-foreground">{order.submittedBy}</div>}
                    </TableCell>
                    <TableCell className={cn(fresh && 'font-semibold')}>{formatShortDate(order.deliveryDate)}</TableCell>
                    <TableCell className="text-muted-foreground">{dateTime.format(new Date(order.submittedAt))}</TableCell>
                    <TableCell className="text-right">{order.itemCount}</TableCell>
                    <TableCell>
                      <div className="flex items-center gap-2">
                        <OrderStatusBadge order={order} incoming={incoming} />
                        {order.corrected && <span className="text-xs text-muted-foreground">poprawione</span>}
                      </div>
                    </TableCell>
                    {/* Menu pobierania nie otwiera zamówienia. */}
                    <TableCell className="pr-4 text-right" onClick={(event) => event.stopPropagation()} onKeyDown={(event) => event.stopPropagation()}>
                      <div className="flex items-center justify-end gap-1">
                        <OrderDownloads storeId={storeId} orderId={order.id} compact />
                        <ChevronRightIcon aria-hidden className="size-4 text-muted-foreground" />
                      </div>
                    </TableCell>
                  </TableRow>
                )
              })
            )}
          </TableBody>
        </Table>
      </Card>
    </>
  )
}
