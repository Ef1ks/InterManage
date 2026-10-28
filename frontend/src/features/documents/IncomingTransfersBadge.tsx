import { useIncomingTransfersCount } from '@/features/documents/api'
import { useCurrentStore } from '@/features/stores/current-store'

/** Licznik w menu: ruchy międzymagazynowe innych sklepów, które wybrany sklep powinien wprowadzić u siebie. */
export function IncomingTransfersBadge() {
  const store = useCurrentStore()
  const { data: count = 0 } = useIncomingTransfersCount(store?.id ?? '', store !== null)
  if (count === 0) return null
  return (
    <span
      aria-label={`${count} dokumentów do wprowadzenia`}
      className="rounded-full bg-amber-500 px-1.5 text-[0.7rem] leading-[1.125rem] font-semibold text-white tabular-nums animate-in zoom-in-50 fade-in-0 duration-200 motion-reduce:animate-none"
    >
      {count}
    </span>
  )
}
