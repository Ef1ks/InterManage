import { type ReactNode, useMemo, useRef, useState } from 'react'

import { SelectField } from '@/components/form'
import { LoadingPanel } from '@/components/LoadingPanel'
import { formatDocumentDate, formatMoney, formatPercent } from '@/features/documents/format'
import { useProductPrices } from '@/features/inventory/api'
import {
  ArticleListSection,
  EmptyListRow,
  LoadMoreRow,
  SortTh,
  useListControls,
  useLocalPages,
} from '@/features/sales/ArticleListSection'
import { parseIsoDate, todayIso } from '@/lib/dates'
import { type SortAccessors, sortRows } from '@/lib/table-sort'
import type { ProductPriceChange, ProductPromotion } from '@/lib/types'
import { cn } from '@/lib/utils'

/**
 * Historia cen artykułu pod wykresem: zmiany ceny sprzedaży (pliki z kasy po EAN-ie, importy kartoteki, pliki zmiany
 * cen z wprowadzania danych) i przeceny z listy promocji, od najnowszych. Artykuł z kilkoma EAN-ami (także z etykiet
 * obniżek) pokazuje jeden naraz, domyślnie główny. Historia przychodzi w całości, więc sortowanie i wyszukiwanie
 * liczy przeglądarka, a wiersze rysują się po 15 przy przewijaniu.
 */
export function ArticlePriceHistory({ storeId, itm8 }: { storeId: string; itm8: string }) {
  const prices = useProductPrices(storeId, itm8)
  const data = prices.data
  const [chosenEan, setChosenEan] = useState<string | null>(null)
  const eans = [...new Set(data?.changes.flatMap((change) => change.ean ?? []))]
  const ean = chosenEan ?? (eans.find((code) => code === data?.mainEan) || eans[0]) ?? null
  const changes = useMemo(
    () => data?.changes.filter((change) => change.ean === null || change.ean === ean) ?? [],
    [data, ean],
  )

  if (!data) {
    return (
      <>
        {['Zmiany cen', 'Przeceny'].map((title) => (
          <section key={title} className="grid min-w-0 gap-2">
            <h3 className="text-sm font-semibold">{title}</h3>
            {prices.isError ? (
              <p className="rounded-lg bg-muted px-4 py-3 text-sm text-muted-foreground">
                Nie udało się wczytać historii cen.
              </p>
            ) : (
              <LoadingPanel className="h-24 rounded-lg" />
            )}
          </section>
        ))}
      </>
    )
  }

  return (
    <>
      {data.changes.length === 0 ? (
        <EmptySection title="Zmiany cen">
          Brak zapisanych zmian ceny. Ceny z kasy mamy od stycznia 2026, zmiany z importów kartoteki od 2 listopada
          2026, a z wprowadzania danych od pierwszego pliku zmiany cen.
        </EmptySection>
      ) : (
        <PriceChanges
          changes={changes}
          toolbar={
            eans.length > 1 && ean ? (
              <div className="w-56">
                <SelectField
                  label="EAN"
                  options={eans.map((code) => ({
                    value: code,
                    label: code === data.mainEan ? `${code} (główny)` : code,
                  }))}
                  value={ean}
                  onChange={(next) => setChosenEan(next)}
                />
              </div>
            ) : null
          }
        />
      )}
      {data.promotions.length === 0 ? (
        <EmptySection title="Przeceny">Artykułu nie ma we wgranych listach promocji.</EmptySection>
      ) : (
        <Promotions promotions={data.promotions} />
      )}
    </>
  )
}

function EmptySection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="grid min-w-0 gap-2">
      <h3 className="text-sm font-semibold">{title}</h3>
      <p className="rounded-lg bg-muted px-4 py-3 text-sm text-muted-foreground">{children}</p>
    </section>
  )
}

/** Każde słowo wyszukiwania musi być w tekście wiersza (bez wielkości liter). */
function matches(text: string, query: string) {
  const haystack = text.toLowerCase()
  return query
    .toLowerCase()
    .split(/\s+/)
    .filter(Boolean)
    .every((word) => haystack.includes(word))
}

type ChangeColumn = 'date' | 'price' | 'promo'

const CHANGE_SORT: SortAccessors<ProductPriceChange, ChangeColumn> = {
  date: (change) => change.changedAt,
  price: (change) => change.salePrice,
  promo: (change) => change.promoPrice,
}

const SOURCE_LABELS: Record<ProductPriceChange['source'], string> = {
  TILL: 'kasa',
  REPORT: 'kartoteka',
  DATA_ENTRY: 'wprowadzanie danych',
}

/** Tekst zmiany do wyszukiwania: data, ceny, źródło, opis i osoba. */
function changeText(change: ProductPriceChange) {
  return [
    change.changedAt && formatDateTime(change.changedAt),
    change.salePrice != null && formatMoney(change.salePrice),
    change.previousSalePrice != null && formatMoney(change.previousSalePrice),
    change.promoPrice != null && `${formatMoney(change.promoPrice)} promocja`,
    SOURCE_LABELS[change.source],
    change.description,
    change.user,
  ]
    .filter(Boolean)
    .join(' ')
}

function PriceChanges({ changes, toolbar }: { changes: ProductPriceChange[]; toolbar: ReactNode }) {
  const controls = useListControls<ChangeColumn>({ key: 'date', direction: 'desc' })
  const { sort, toggleSort } = controls
  const scrollRef = useRef<HTMLDivElement>(null)
  const rows = useMemo(
    () =>
      sortRows(
        changes.filter((change) => matches(changeText(change), controls.query)),
        CHANGE_SORT,
        sort,
      ),
    [changes, controls.query, sort],
  )
  const pages = useLocalPages(rows)
  const searching = controls.query.trim() !== ''

  return (
    <ArticleListSection
      title="Zmiany cen"
      note={
        <span title="Cena sprzedaży brutto">{searching ? `pasuje: ${rows.length}` : `zmiany: ${rows.length}`}</span>
      }
      searchLabel="Szukaj: data, cena, źródło"
      search={controls.text}
      onSearch={controls.setText}
      toolbar={toolbar}
      scrollRef={scrollRef}
      className="max-h-80"
    >
      <table className="w-full min-w-[40rem] text-sm tabular-nums">
        <thead className="sticky top-0 z-10 bg-muted text-xs text-muted-foreground">
          <tr>
            <SortTh column="date" sort={sort} onSort={toggleSort}>
              Kiedy
            </SortTh>
            <SortTh column="price" sort={sort} onSort={toggleSort} align="right">
              Cena
            </SortTh>
            <SortTh column="promo" sort={sort} onSort={toggleSort}>
              Promocja
            </SortTh>
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 && <EmptyListRow colSpan={3}>Żadna zmiana nie pasuje do wyszukiwania.</EmptyListRow>}
          {pages.visible.map((change, index) => (
            <tr key={index} className="border-t align-top">
              <td className="px-3 py-1.5 whitespace-nowrap">
                {change.changedAt ? formatDateTime(change.changedAt) : '—'}
              </td>
              <td className="px-3 py-1.5 text-right whitespace-nowrap">
                <PriceStep before={change.previousSalePrice} after={change.salePrice} />
              </td>
              <td className="px-3 py-1.5 whitespace-nowrap">
                <PromoCell change={change} />
              </td>
            </tr>
          ))}
          <LoadMoreRow
            colSpan={3}
            hasMore={pages.hasMore}
            loading={false}
            loaded={pages.visible.length}
            onLoadMore={pages.loadMore}
            root={scrollRef}
          />
        </tbody>
      </table>
    </ArticleListSection>
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

type PromotionColumn = 'period' | 'regular' | 'promo' | 'discount' | 'margin' | 'status'

const discountOf = (promotion: ProductPromotion) =>
  promotion.regularSalePrice && promotion.promoPrice != null
    ? ((promotion.regularSalePrice - promotion.promoPrice) / promotion.regularSalePrice) * 100
    : null

/** Kolejność statusów przy sortowaniu: najpierw trwające, potem zaplanowane, zakończone i odwołane. */
const STATUS_ORDER = { trwa: 0, zaplanowana: 1, zakończona: 2, odwołana: 3 } as const

function promotionStatus(promotion: ProductPromotion, today: string): keyof typeof STATUS_ORDER {
  if (promotion.cancelled) return 'odwołana'
  if (promotion.startDate > today) return 'zaplanowana'
  return promotion.endDate >= today ? 'trwa' : 'zakończona'
}

function Promotions({ promotions }: { promotions: ProductPromotion[] }) {
  // Domyślnie od najpóźniej kończącej się, jak lista promocji.
  const controls = useListControls<PromotionColumn>({ key: 'period', direction: 'desc' })
  const { sort, toggleSort } = controls
  const scrollRef = useRef<HTMLDivElement>(null)
  const today = todayIso()
  const accessors = useMemo<SortAccessors<ProductPromotion, PromotionColumn>>(
    () => ({
      period: (promotion) => promotion.endDate,
      regular: (promotion) => promotion.regularSalePrice,
      promo: (promotion) => promotion.promoPrice,
      discount: discountOf,
      margin: (promotion) => promotion.marginPercent,
      status: (promotion) => STATUS_ORDER[promotionStatus(promotion, today)],
    }),
    [today],
  )
  const rows = useMemo(() => {
    const found = promotions.filter((promotion) =>
      matches(
        [
          formatDocumentDate(promotion.startDate),
          formatDocumentDate(promotion.endDate),
          promotion.regularSalePrice != null && formatMoney(promotion.regularSalePrice),
          promotion.promoPrice != null && formatMoney(promotion.promoPrice),
          promotionStatus(promotion, today),
        ]
          .filter(Boolean)
          .join(' '),
        controls.query,
      ),
    )
    return sortRows(found, accessors, sort)
  }, [promotions, controls.query, accessors, sort, today])
  const pages = useLocalPages(rows)
  const searching = controls.query.trim() !== ''

  return (
    <ArticleListSection
      title="Przeceny"
      note={
        <span title="Promocje z listy promocji">
          {searching ? `pasuje: ${rows.length}` : `promocje: ${rows.length}`}
        </span>
      }
      searchLabel="Szukaj: data, cena, status"
      search={controls.text}
      onSearch={controls.setText}
      scrollRef={scrollRef}
      className="max-h-80"
    >
      <table className="w-full min-w-[40rem] text-sm tabular-nums">
        <thead className="sticky top-0 z-10 bg-muted text-xs text-muted-foreground">
          <tr>
            <SortTh column="period" sort={sort} onSort={toggleSort}>
              Okres
            </SortTh>
            <SortTh column="regular" sort={sort} onSort={toggleSort} align="right">
              Cena regularna
            </SortTh>
            <SortTh column="promo" sort={sort} onSort={toggleSort} align="right">
              Cena promocyjna
            </SortTh>
            <SortTh column="discount" sort={sort} onSort={toggleSort} align="right">
              Rabat
            </SortTh>
            <SortTh column="margin" sort={sort} onSort={toggleSort} align="right">
              Marża
            </SortTh>
            <SortTh column="status" sort={sort} onSort={toggleSort}>
              Status
            </SortTh>
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 && <EmptyListRow colSpan={6}>Żadna promocja nie pasuje do wyszukiwania.</EmptyListRow>}
          {pages.visible.map((promotion) => {
            const discount = discountOf(promotion)
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
                  <PromotionStatus status={promotionStatus(promotion, today)} />
                </td>
              </tr>
            )
          })}
          <LoadMoreRow
            colSpan={6}
            hasMore={pages.hasMore}
            loading={false}
            loaded={pages.visible.length}
            onLoadMore={pages.loadMore}
            root={scrollRef}
          />
        </tbody>
      </table>
    </ArticleListSection>
  )
}

const STATUS_STYLES: Record<keyof typeof STATUS_ORDER, string> = {
  odwołana: 'bg-muted text-muted-foreground line-through',
  zaplanowana: 'bg-sky-200 text-sky-950 dark:bg-sky-900 dark:text-sky-50',
  trwa: 'bg-orange-200 text-orange-950 dark:bg-orange-900 dark:text-orange-50',
  zakończona: 'bg-muted text-muted-foreground',
}

function PromotionStatus({ status }: { status: keyof typeof STATUS_ORDER }) {
  return <span className={cn('rounded px-1.5 py-px text-xs font-medium', STATUS_STYLES[status])}>{status}</span>
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
