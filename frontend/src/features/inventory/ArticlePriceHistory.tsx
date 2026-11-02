import type { ReactNode } from 'react'

import { formatDocumentDate, formatMoney, formatPercent } from '@/features/documents/format'
import { useProductPrices } from '@/features/inventory/api'
import { parseIsoDate, todayIso } from '@/lib/dates'
import type { ProductPriceChange, ProductPromotion } from '@/lib/types'
import { cn } from '@/lib/utils'

const SOURCE_LABELS: Record<ProductPriceChange['source'], string> = {
  TILL: 'Kasa',
  REPORT: 'Kartoteka',
  DATA_ENTRY: 'Wprowadzanie danych',
}

/**
 * Historia cen artykułu pod wykresem: zmiany ceny sprzedaży (pliki z kasy, importy kartoteki, pliki zmiany cen
 * z wprowadzania danych) i przeceny z listy promocji, od najnowszych.
 */
export function ArticlePriceHistory({ storeId, itm8 }: { storeId: string; itm8: string }) {
  const prices = useProductPrices(storeId, itm8)
  const data = prices.data

  return (
    <>
      <HistorySection
        id="price-changes"
        title="Zmiany cen"
        note="cena sprzedaży brutto, od najnowszej"
        isError={prices.isError}
        isPending={prices.isPending}
        empty={
          data?.changes.length === 0
            ? 'Brak zapisanych zmian ceny. Zmiany z kasy i importów kartoteki zapisujemy od 2 listopada 2026, zmiany z wprowadzania danych od pierwszego pliku zmiany cen.'
            : null
        }
      >
        {data && <PriceChangesTable changes={data.changes} />}
      </HistorySection>
      <HistorySection
        id="price-promotions"
        title="Przeceny"
        note="promocje z listy promocji, od najpóźniej kończącej się"
        isError={prices.isError}
        isPending={prices.isPending}
        empty={data?.promotions.length === 0 ? 'Artykułu nie ma we wgranych listach promocji.' : null}
      >
        {data && <PromotionsTable promotions={data.promotions} />}
      </HistorySection>
    </>
  )
}

function HistorySection({
  id,
  title,
  note,
  isError,
  isPending,
  empty,
  children,
}: {
  id: string
  title: string
  note: string
  isError: boolean
  isPending: boolean
  empty: string | null
  children: ReactNode
}) {
  return (
    <section className="grid min-w-0 gap-2" aria-labelledby={id}>
      <div className="flex items-baseline justify-between gap-3">
        <h3 id={id} className="text-sm font-semibold">
          {title}
        </h3>
        <p className="text-xs text-muted-foreground">{note}</p>
      </div>
      {isError ? (
        <p className="rounded-lg bg-muted px-4 py-3 text-sm text-muted-foreground">
          Nie udało się wczytać historii cen.
        </p>
      ) : isPending ? (
        <div aria-hidden className="h-24 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
      ) : empty ? (
        <p className="rounded-lg bg-muted px-4 py-3 text-sm text-muted-foreground">{empty}</p>
      ) : (
        <div className="max-h-80 min-w-0 overflow-auto rounded-lg border">{children}</div>
      )}
    </section>
  )
}

function PriceChangesTable({ changes }: { changes: ProductPriceChange[] }) {
  return (
    <table className="w-full min-w-[40rem] text-sm tabular-nums">
      <thead className="sticky top-0 bg-muted text-xs text-muted-foreground">
        <tr>
          <th className="px-3 py-2 text-left font-medium">Kiedy</th>
          <th className="px-3 py-2 text-left font-medium">Skąd</th>
          <th className="px-3 py-2 text-right font-medium">Cena</th>
          <th className="px-3 py-2 text-left font-medium">Promocja</th>
          <th className="px-3 py-2 text-left font-medium">Szczegóły</th>
        </tr>
      </thead>
      <tbody>
        {changes.map((change, index) => (
          <tr key={index} className="border-t align-top">
            <td className="px-3 py-1.5 whitespace-nowrap">
              {change.changedAt ? formatDateTime(change.changedAt) : '—'}
            </td>
            <td className="px-3 py-1.5 whitespace-nowrap">{SOURCE_LABELS[change.source]}</td>
            <td className="px-3 py-1.5 text-right whitespace-nowrap">
              <PriceStep before={change.previousSalePrice} after={change.salePrice} />
            </td>
            <td className="px-3 py-1.5 whitespace-nowrap">
              <PromoCell change={change} />
            </td>
            <td className="max-w-64 truncate px-3 py-1.5 text-muted-foreground" title={details(change)}>
              {details(change)}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

/** Cena przed i po ze zmianą %; bez ceny poprzedniej (plik zmiany cen) sama nowa. */
function PriceStep({ before, after }: { before: number | null; after: number | null }) {
  if (after == null) return <>—</>
  if (before == null || Math.abs(before - after) < 0.005)
    return <span className="font-medium">{formatMoney(after)} zł</span>
  const change = before !== 0 ? ((after - before) / before) * 100 : null
  return (
    <span className="inline-flex items-baseline gap-1.5">
      <span className="text-muted-foreground line-through">{formatMoney(before)}</span>
      <span className="font-medium">{formatMoney(after)} zł</span>
      {change != null && (
        <span className={cn('text-xs', change > 0 ? 'text-foreground' : 'text-muted-foreground')}>
          {change > 0 ? '+' : '−'}
          {formatPercent(Math.abs(change))}
        </span>
      )}
    </span>
  )
}

function PromoCell({ change }: { change: ProductPriceChange }) {
  if (change.source === 'DATA_ENTRY') return <span className="text-muted-foreground">—</span>
  if (change.promoPrice == null) {
    return change.previousPromoPrice != null ? (
      <span className="text-muted-foreground">koniec promocji ({formatMoney(change.previousPromoPrice)} zł)</span>
    ) : (
      <span className="text-muted-foreground">—</span>
    )
  }
  return (
    <span>
      <span className="font-semibold text-orange-600 dark:text-orange-400">{formatMoney(change.promoPrice)} zł</span>
      {change.promoFrom && change.promoTo && (
        <span className="ml-1.5 text-xs text-muted-foreground">
          {formatDocumentDate(change.promoFrom)} – {formatDocumentDate(change.promoTo)}
        </span>
      )}
    </span>
  )
}

function details(change: ProductPriceChange) {
  return [change.description, change.user].filter(Boolean).join(' · ')
}

function PromotionsTable({ promotions }: { promotions: ProductPromotion[] }) {
  const today = todayIso()
  return (
    <table className="w-full min-w-[40rem] text-sm tabular-nums">
      <thead className="sticky top-0 bg-muted text-xs text-muted-foreground">
        <tr>
          <th className="px-3 py-2 text-left font-medium">Okres</th>
          <th className="px-3 py-2 text-right font-medium">Cena regularna</th>
          <th className="px-3 py-2 text-right font-medium">Cena promocyjna</th>
          <th className="px-3 py-2 text-right font-medium">Rabat</th>
          <th className="px-3 py-2 text-right font-medium">Marża</th>
          <th className="px-3 py-2 text-left font-medium">Status</th>
        </tr>
      </thead>
      <tbody>
        {promotions.map((promotion) => {
          const discount =
            promotion.regularSalePrice && promotion.promoPrice != null
              ? ((promotion.regularSalePrice - promotion.promoPrice) / promotion.regularSalePrice) * 100
              : null
          return (
            <tr key={`${promotion.startDate}:${promotion.endDate}`} className="border-t">
              <td className="px-3 py-1.5 whitespace-nowrap">
                {formatDocumentDate(promotion.startDate)} – {formatDocumentDate(promotion.endDate)}
              </td>
              <td className="px-3 py-1.5 text-right whitespace-nowrap">
                {promotion.regularSalePrice == null ? '—' : `${formatMoney(promotion.regularSalePrice)} zł`}
              </td>
              <td className="px-3 py-1.5 text-right font-semibold whitespace-nowrap text-orange-600 dark:text-orange-400">
                {promotion.promoPrice == null ? '—' : `${formatMoney(promotion.promoPrice)} zł`}
              </td>
              <td className="px-3 py-1.5 text-right whitespace-nowrap">
                {discount == null ? '—' : `−${formatPercent(discount)}`}
              </td>
              <td className="px-3 py-1.5 text-right whitespace-nowrap">
                {promotion.marginPercent == null ? '—' : formatPercent(promotion.marginPercent)}
              </td>
              <td className="px-3 py-1.5 whitespace-nowrap">
                <PromotionStatus promotion={promotion} today={today} />
              </td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}

function PromotionStatus({ promotion, today }: { promotion: ProductPromotion; today: string }) {
  const [label, style] = promotion.cancelled
    ? ['odwołana', 'bg-muted text-muted-foreground line-through']
    : promotion.startDate > today
      ? ['zaplanowana', 'bg-sky-200 text-sky-950 dark:bg-sky-900 dark:text-sky-50']
      : promotion.endDate >= today
        ? ['trwa', 'bg-orange-200 text-orange-950 dark:bg-orange-900 dark:text-orange-50']
        : ['zakończona', 'bg-muted text-muted-foreground']
  return <span className={cn('rounded px-1.5 py-px text-xs font-medium', style)}>{label}</span>
}

const dateTimeFormat = new Intl.DateTimeFormat('pl-PL', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
})

/** "2026-11-02T06:39:56" (czas lokalny sklepu) -> "02.11.2026, 06:39". */
function formatDateTime(value: string) {
  const [date, time = '00:00'] = value.split('T')
  const [hours, minutes] = time.split(':').map(Number)
  const day = parseIsoDate(date)
  day.setHours(hours, minutes)
  return dateTimeFormat.format(day)
}
