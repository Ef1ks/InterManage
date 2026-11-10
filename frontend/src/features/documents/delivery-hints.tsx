import { useMemo } from 'react'

import { formatPercent, formatPrice } from '@/features/documents/format'
import { useDeliveryLineHints } from '@/features/sales/api'
import type { DeliveryLineHint } from '@/lib/types'
import { cn } from '@/lib/utils'

/**
 * Kolory na bonie przyjęcia PZ, liczone tak samo jak raport marż poza normą: marża z aktualnej ceny na kasie wobec
 * marży wymaganej (progi sklepu), promocja teraz i na przyjęciu, cadencier.
 */
export const HINT_STYLES = {
  low: 'bg-red-200 text-red-950 dark:bg-red-900/70 dark:text-red-100',
  high: 'bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-100',
  ok: 'bg-emerald-200 text-emerald-950 dark:bg-emerald-900/70 dark:text-emerald-100',
  promoNow: 'bg-orange-200 text-orange-950 dark:bg-orange-900/70 dark:text-orange-100',
  promoOnDelivery: 'bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100',
  cadencier: 'bg-indigo-200 text-indigo-950 dark:bg-indigo-900/70 dark:text-indigo-100',
} as const

const pill = 'inline-block rounded-md px-1.5 py-0.5 text-[0.7rem] font-medium whitespace-nowrap'

/** Dzień RRRR-MM-DD jako DD.MM. */
const formatDay = (day: string) => `${day.slice(8, 10)}.${day.slice(5, 7)}`

/** Ocena artykułów przyjęcia po ITM8 (bez zapytania dla innych typów dokumentów). */
export function useDeliveryHints(storeId: string, documentId: string, enabled: boolean) {
  const query = useDeliveryLineHints(storeId, documentId, enabled)
  const byItm8 = useMemo(() => new Map((query.data?.lines ?? []).map((hint) => [hint.line.itm8, hint])), [query.data])
  return { data: query.data, byItm8 }
}

/** Plakietki przy nazwie artykułu: promocja teraz, cena promocyjna na przyjęciu, cadencier. */
export function HintBadges({ hint }: { hint: DeliveryLineHint | undefined }) {
  if (!hint) return null
  const { line } = hint
  return (
    <>
      {line.currentPromoPrice != null && (
        <span className={cn(pill, HINT_STYLES.promoNow)} title="Artykuł jest teraz w promocji">
          teraz w promocji {formatPrice(line.currentPromoPrice)}
          {line.currentPromoTo && ` do ${formatDay(line.currentPromoTo)}`}
        </span>
      )}
      {line.promotion && (
        <span className={cn(pill, HINT_STYLES.promoOnDelivery)} title="Cena sprzedaży na bonie była promocyjna">
          na PZ w promocji
        </span>
      )}
      {hint.cadencier && (
        <span className={cn(pill, HINT_STYLES.cadencier)} title="Marża ustalona przez centralę, raport jej nie ocenia">
          cadencier
        </span>
      )}
    </>
  )
}

/** Aktualna cena na kasie pod ceną z bonu, gdy jest inna. */
export function CurrentPrice({
  hint,
  documentPrice,
}: {
  hint: DeliveryLineHint | undefined
  documentPrice: number | null
}) {
  if (!hint || documentPrice == null || hint.line.salePrice === documentPrice) return null
  return (
    <span className="block text-xs text-muted-foreground" title="Aktualna cena zwykła na kasie">
      teraz {formatPrice(hint.line.salePrice)}
    </span>
  )
}

/**
 * Marża z aktualnej ceny jako kolorowa pigułka: czerwona za niska, niebieska za wysoka, zielona w normie (przy marży
 * wymaganej); bez oceny (cadencier, brak marży wymaganej) szara.
 */
export function HintMargin({ hint }: { hint: DeliveryLineHint | undefined }) {
  if (!hint) return null
  const { line } = hint
  const judged = line.requiredMargin != null && !hint.cadencier
  const style = !judged
    ? 'bg-muted text-muted-foreground'
    : line.deviation === 'LOW'
      ? HINT_STYLES.low
      : line.deviation === 'HIGH'
        ? HINT_STYLES.high
        : HINT_STYLES.ok
  const title = [
    'Marża z aktualnej ceny na kasie',
    line.requiredMargin != null && `wymagana ${formatPercent(line.requiredMargin)}`,
    hint.cadencier && 'cadencier: bez oceny',
    hint.excludedSupplier && 'dostawca pomijany w raporcie marż',
  ]
    .filter(Boolean)
    .join(' · ')
  return (
    <span className={cn(pill, 'mt-0.5', style)} title={title}>
      teraz {formatPercent(line.marginPercent)}
    </span>
  )
}

/** Legenda kolorów nad liniami bonu. */
export function HintLegend({ lowGap, highGap, configured }: { lowGap: number; highGap: number; configured: boolean }) {
  return (
    <div className="mb-3 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
      <span>Marża z aktualnej ceny na kasie:</span>
      {configured ? (
        <>
          <span className={cn(pill, HINT_STYLES.low)}>
            za niska (o {formatPercent(lowGap).replace('%', '')} pkt i więcej)
          </span>
          <span className={cn(pill, HINT_STYLES.ok)}>w normie</span>
          <span className={cn(pill, HINT_STYLES.high)}>
            za wysoka (o {formatPercent(highGap).replace('%', '')} pkt i więcej)
          </span>
        </>
      ) : (
        <span>sklep nie ma marży wymaganej, więc bez oceny</span>
      )}
      <span className={cn(pill, HINT_STYLES.promoNow)}>teraz w promocji</span>
      <span className={cn(pill, HINT_STYLES.promoOnDelivery)}>na PZ w promocji</span>
      <span className={cn(pill, HINT_STYLES.cadencier)}>cadencier</span>
    </div>
  )
}
