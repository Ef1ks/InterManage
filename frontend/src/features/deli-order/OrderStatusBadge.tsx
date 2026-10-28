import { Badge } from '@/components/ui/badge'
import type { DeliOrderSummary } from '@/lib/types'

type Props = {
  order: Pick<DeliOrderSummary, 'status' | 'storeDeletedAt' | 'formStoreDeletedAt'>
  incoming: boolean
}

/**
 * Dla dostawcy nowe zamówienie czeka na otwarcie, dla zamawiającego jest wysłane; otwarte u dostawcy jest odczytane.
 * Usunięte po drugiej stronie pokazujemy z informacją, kto je usunął.
 */
export function OrderStatusBadge({ order, incoming }: Props) {
  if (order.storeDeletedAt || order.formStoreDeletedAt) {
    const label = order.storeDeletedAt
      ? incoming
        ? 'Usunięte przez sklep'
        : 'Usunięte'
      : incoming
        ? 'Usunięte'
        : 'Usunięte przez dostawcę'
    return (
      <Badge variant="outline" className="gap-1.5 border-destructive/30 text-destructive">
        <span aria-hidden className="size-1.5 rounded-full bg-destructive" />
        {label}
      </Badge>
    )
  }
  if (order.status === 'FULFILLED') {
    return (
      <Badge variant="outline" className="gap-1.5">
        <span aria-hidden className="size-1.5 rounded-full bg-emerald-500" />
        Zrealizowane
      </Badge>
    )
  }
  if (order.status === 'READ') {
    return (
      <Badge variant="outline" className="gap-1.5">
        <span aria-hidden className="size-1.5 rounded-full bg-sky-500" />
        Odczytane
      </Badge>
    )
  }
  return incoming ? (
    <Badge variant="outline" className="border-amber-300 bg-amber-50 text-amber-800 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-300">
      Nowe
    </Badge>
  ) : (
    <Badge variant="secondary">Wysłane</Badge>
  )
}
