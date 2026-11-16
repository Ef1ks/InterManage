import { useCurrentStore } from '@/features/stores/current-store'

import { usePriceControlPendingCount } from './api'

/** Licznik w menu przy wprowadzaniu danych: zmiany cen, których program sklepu jeszcze nie wczytał. */
export function PriceControlPendingBadge() {
  const store = useCurrentStore()
  const { data: count = 0 } = usePriceControlPendingCount(store?.id ?? '', store !== null)
  if (count === 0) return null
  return (
    <span
      aria-label={`Niewgrane zmiany cen: ${count}`}
      title={`Niewgrane zmiany cen: ${count}`}
      className="rounded-full bg-amber-500 px-1.5 text-[0.7rem] leading-[1.125rem] font-semibold text-white tabular-nums animate-in zoom-in-50 fade-in-0 duration-200 motion-reduce:animate-none"
    >
      {count}
    </span>
  )
}
