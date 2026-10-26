import { useDeliOverview } from '@/features/deli-order/api'
import { useCurrentStore } from '@/features/stores/current-store'

/**
 * Licznik w menu: nowe zamówienia, które przyszły do wybranego sklepu, plus zaproszenia czekające na odpowiedź.
 * Dane odświeżają się co minutę (to samo zapytanie co na stronie zamówień).
 */
export function DeliNewOrdersBadge() {
  const store = useCurrentStore()
  const { data } = useDeliOverview(store?.id ?? '', store !== null)
  const count = (data?.newIncomingCount ?? 0) + (data?.pendingInvitations.length ?? 0)
  if (count === 0) return null
  return (
    <span
      aria-label={`${count} do sprawdzenia`}
      className="rounded-full bg-amber-500 px-1.5 text-[0.7rem] leading-[1.125rem] font-semibold text-white tabular-nums animate-in zoom-in-50 fade-in-0 duration-200 motion-reduce:animate-none"
    >
      {count}
    </span>
  )
}
