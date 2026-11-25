import { useCurrentStore } from '@/features/stores/current-store'

import { useWaitingCollectorPromotions } from './promotions-api'

/** Licznik w menu: promocje z kolektorów, których nikt nie wstawił ani nie ustawił makrem. */
export function CollectorPromotionsBadge() {
  const store = useCurrentStore()
  const { data } = useWaitingCollectorPromotions(store?.id ?? '', store !== null)
  const count = data?.waiting ?? 0
  if (count === 0) return null
  return (
    <span
      aria-label={`${count} promocji z kolektorów do ustawienia`}
      className="rounded-full bg-amber-500 px-1.5 text-[0.7rem] leading-[1.125rem] font-semibold text-white tabular-nums animate-in zoom-in-50 fade-in-0 duration-200 motion-reduce:animate-none"
    >
      {count}
    </span>
  )
}
