import { useWaitingCollectorLists } from '@/features/collectors/lists-api'
import { useCurrentStore } from '@/features/stores/current-store'

/** Licznik w menu: listy z kolektorów, które czekają na wybór protokołu. */
export function CollectorListsBadge() {
  const store = useCurrentStore()
  const { data } = useWaitingCollectorLists(store?.id ?? '', store !== null)
  const count = data?.waiting ?? 0
  if (count === 0) return null
  return (
    <span
      aria-label={`${count} list z kolektorów do opracowania`}
      className="rounded-full bg-amber-500 px-1.5 text-[0.7rem] leading-[1.125rem] font-semibold text-white tabular-nums animate-in zoom-in-50 fade-in-0 duration-200 motion-reduce:animate-none"
    >
      {count}
    </span>
  )
}
