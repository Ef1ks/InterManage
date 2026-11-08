import { useCan } from '@/features/auth/api'
import { useTimeAttention } from '@/features/staff-time/api'
import { useCurrentStore } from '@/features/stores/current-store'

/** Licznik w menu: dni z ostatnich 31, w których kierownik musi zareagować (nadgodziny, braki, brak wyjścia). */
export function TimeAttentionBadge() {
  const store = useCurrentStore()
  const canDecide = useCan('STAFF_TIME', 'EDIT')
  const { data } = useTimeAttention(store?.id ?? '', store !== null && canDecide)
  const count = data?.total ?? 0
  if (count === 0) return null
  return (
    <span
      aria-label={`${count} dni do sprawdzenia w czasie pracy`}
      className="rounded-full bg-amber-500 px-1.5 text-[0.7rem] leading-[1.125rem] font-semibold text-white tabular-nums animate-in zoom-in-50 fade-in-0 duration-200 motion-reduce:animate-none"
    >
      {count}
    </span>
  )
}
