import { useCurrentStore } from '@/features/stores/current-store'

import { useOpenSupplierReturns } from './api'

/** Licznik w menu: zwroty do dostawcy jeszcze nierozliczone. */
export function SupplierReturnsBadge() {
  const store = useCurrentStore()
  const { data } = useOpenSupplierReturns(store?.id ?? '', store !== null)
  const count = data?.open ?? 0
  if (count === 0) return null
  return (
    <span
      aria-label={`${count} otwartych zwrotów`}
      className="rounded-full bg-amber-500 px-1.5 text-[0.7rem] leading-[1.125rem] font-semibold text-white tabular-nums animate-in zoom-in-50 fade-in-0 duration-200 motion-reduce:animate-none"
    >
      {count}
    </span>
  )
}
