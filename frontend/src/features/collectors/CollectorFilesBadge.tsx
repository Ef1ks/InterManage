import { useWaitingCollectorFiles } from '@/features/collectors/files-api'
import { useCurrentStore } from '@/features/stores/current-store'
import type { CollectorFile } from '@/lib/types'

/** Licznik w menu: inwentaryzacje albo zamówienia z kolektora, które czekają na wgranie do Mercalys. */
export function CollectorFilesBadge({ kind }: { kind: CollectorFile['kind'] }) {
  const store = useCurrentStore()
  const { data } = useWaitingCollectorFiles(store?.id ?? '', kind, store !== null)
  const count = data?.waiting ?? 0
  if (count === 0) return null
  return (
    <span
      aria-label={`${count} czeka na wgranie`}
      className="rounded-full bg-amber-500 px-1.5 text-[0.7rem] leading-[1.125rem] font-semibold text-white tabular-nums animate-in zoom-in-50 fade-in-0 duration-200 motion-reduce:animate-none"
    >
      {count}
    </span>
  )
}
