import { MergeIcon } from 'lucide-react'
import { useNavigate } from 'react-router'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { useMergeOrders } from '@/features/deli-order/api'
import { deliPaths } from '@/features/deli-order/paths'
import { storeLabel } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import { formatShortDate } from '@/lib/dates'
import type { DeliOrderSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

/** "2 zamówienia", "5 zamówień". */
export function ordersCount(count: number) {
  return `${count} ${count === 1 ? 'zamówienie' : count % 10 >= 2 && count % 10 <= 4 && (count % 100 < 12 || count % 100 > 14) ? 'zamówienia' : 'zamówień'}`
}

/** Niezrealizowane, nieusunięte zamówienia jednego sklepu na ten sam dzień, gdy jest ich co najmniej dwa. */
export function mergeableGroups(orders: DeliOrderSummary[]) {
  const groups = new Map<string, DeliOrderSummary[]>()
  for (const order of orders) {
    if (order.status === 'FULFILLED' || order.storeDeletedAt || order.formStoreDeletedAt) continue
    const key = `${order.store.id}:${order.deliveryDate}`
    groups.set(key, [...(groups.get(key) ?? []), order])
  }
  return [...groups.values()].filter((group) => group.length > 1)
}

/**
 * Łączenie z toastem "Cofnij" (cofnięcie przywraca zamówienia źródłowe). `open` przechodzi do połączonego zamówienia,
 * np. ze szczegółów jednego ze źródłowych, które po połączeniu znika z list.
 */
export function useMergeWithUndo(storeId: string) {
  const { merge, split } = useMergeOrders(storeId)
  const navigate = useNavigate()

  const undo = (orderId: string) =>
    toast.promise(split.mutateAsync(orderId), {
      loading: 'Cofanie połączenia…',
      success: (sources) => `Zamówienia znów są osobno (${sources.length})`,
      error: (error) => (error instanceof ApiError ? error.message : 'Nie udało się cofnąć połączenia'),
    })

  const run = (orderIds: string[], open: boolean) =>
    merge.mutate(orderIds, {
      onSuccess: (order) => {
        toast.success(`Połączono ${ordersCount(orderIds.length)}`, {
          description: `Od sklepu ${storeLabel(order.store)} na ${formatShortDate(order.deliveryDate)}: ${order.items.length} pozycji.`,
          action: { label: 'Cofnij', onClick: () => undo(order.id) },
          duration: 10_000,
        })
        if (open) navigate(deliPaths.orderDetail(order.id))
      },
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się połączyć zamówień'),
    })

  return { run, pending: merge.isPending, undo, undoPending: split.isPending }
}

/** Na liście przychodzących: każda grupa zamówień, które da się połączyć, z przyciskiem. */
export function MergeSuggestions({ storeId, orders }: { storeId: string; orders: DeliOrderSummary[] }) {
  const { run, pending } = useMergeWithUndo(storeId)
  const groups = mergeableGroups(orders)
  if (groups.length === 0) return null
  return (
    <div className="mb-4 grid gap-2">
      {groups.map((group) => (
        <MergeBanner
          key={`${group[0].store.id}:${group[0].deliveryDate}`}
          text={`Sklep ${storeLabel(group[0].store)} wysłał ${ordersCount(group.length)} na ${formatShortDate(group[0].deliveryDate)} (pozycje: ${group.map((order) => order.itemCount).join(' + ')}).`}
          pending={pending}
          onMerge={() => run(group.map((order) => order.id), false)}
        />
      ))}
    </div>
  )
}

/** W szczegółach zamówienia: pozostałe zamówienia tego sklepu na ten sam dzień. */
export function MergeWithSiblings({ storeId, orderId, siblings }: { storeId: string; orderId: string; siblings: DeliOrderSummary[] }) {
  const { run, pending } = useMergeWithUndo(storeId)
  if (siblings.length === 0) return null
  return (
    <MergeBanner
      className="mb-6"
      text={`Na ten dzień sklep wysłał jeszcze ${ordersCount(siblings.length)} (pozycje: ${siblings.map((order) => order.itemCount).join(' + ')}).`}
      pending={pending}
      onMerge={() => run([orderId, ...siblings.map((order) => order.id)], true)}
    />
  )
}

function MergeBanner({ text, pending, onMerge, className }: { text: string; pending: boolean; onMerge: () => void; className?: string }) {
  return (
    <div className={cn('flex flex-wrap items-center gap-x-4 gap-y-3 rounded-xl border bg-card px-4 py-3', className)}>
      <MergeIcon aria-hidden className="size-5 shrink-0 text-muted-foreground" />
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium">{text}</p>
        <p className="text-xs text-muted-foreground">
          Połącz je w jedno: te same produkty się zsumują, a towar wydasz na jednym zamówieniu. Połączenie da się cofnąć.
        </p>
      </div>
      <Button variant="outline" loading={pending} onClick={onMerge}>
        <MergeIcon />
        Połącz w jedno
      </Button>
    </div>
  )
}
