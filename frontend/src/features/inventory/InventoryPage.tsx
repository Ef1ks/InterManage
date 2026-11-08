import { ArrowLeftIcon, PackageSearchIcon, SlidersHorizontalIcon } from 'lucide-react'
import { type ClipboardEvent, type KeyboardEvent, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { SearchInput } from '@/components/SearchInput'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { formatMoney, formatQuantity } from '@/features/documents/format'
import { findProducts, useProductList } from '@/features/inventory/api'
import {
  activeFilterCount,
  filterParams,
  type ProductFilters,
  readFilters,
  writeFilters,
} from '@/features/inventory/filters'
import { ProductFiltersPanel } from '@/features/inventory/ProductFiltersPanel'
import { ArticlePriceHistory } from '@/features/inventory/ArticlePriceHistory'
import { PriceLabelsButton } from '@/features/inventory/PriceLabelsDialog'
import { useSalesReportsOverview } from '@/features/sales/api'
import { ArticleSalesPanel } from '@/features/sales/SalesChartDialog'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { addDays, todayIso } from '@/lib/dates'
import { useDebouncedValue } from '@/lib/use-debounced-value'
import { useLoadMore } from '@/lib/use-load-more'
import { useSearchParamText } from '@/lib/use-search-param-text'
import type { ProductListItem, StoreSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

/**
 * Magazyn i produkty: po lewej cała kartoteka sklepu z wyszukiwarką, po prawej karta wybranego artykułu (dane
 * z kartoteki, wykres sprzedaży i dostawy jak w raportach sprzedaży, historia cen i przecen). Wyszukiwanie i wybrany
 * artykuł są w adresie ({@code ?szukaj=&artykul=}). Poniżej {@code lg} lista i karta są osobno, więc nad kartą jest
 * pole na kod następnego artykułu ({@link ArticleJump}), bez wracania do listy.
 */
export function InventoryPage() {
  const store = useCurrentStore()
  if (!store) {
    return (
      <>
        <PageHeader title="Magazyn i produkty" />
        <FullPageMessage
          title="Brak sklepu"
          description="Kartoteka jest prowadzona osobno dla każdego sklepu."
          inline
        />
      </>
    )
  }
  return <StoreInventory key={store.id} store={store} />
}

function StoreInventory({ store }: { store: StoreSummary }) {
  const [params, setParams] = useSearchParams()
  const { text: query, setText: setQuery, debounced: debouncedQuery } = useSearchParamText('szukaj')
  const selected = params.get('artykul')
  const filters = readFilters(params)
  const overview = useSalesReportsOverview(store.id)
  const jumped = useRef(false)

  // Po przejściu z pola kodu nowa karta zaczyna się od góry. W efekcie, bo ScrollRestoration przywraca pozycję
  // po każdej zmianie adresu na tej samej ścieżce.
  useEffect(() => {
    if (!jumped.current) return
    jumped.current = false
    window.scrollTo({ top: 0 })
  }, [selected])

  const setParam = (key: string, value: string | null) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        if (value) next.set(key, value)
        else next.delete(key)
        return next
      },
      { replace: true },
    )

  // Wykres kończy się na ostatnim dniu ze sprzedażą, najpóźniej wczoraj (dzisiejsza nie jest jeszcze wgrana).
  const yesterday = addDays(todayIso(), -1)
  const savedUntil = overview.data?.lastSaleDate ?? null
  const lastSaleDate = savedUntil && savedUntil < yesterday ? savedUntil : yesterday

  return (
    <>
      <span data-wide-page hidden />
      <PageHeader
        title="Magazyn i produkty"
        description={`Kartoteka sklepu ${storeLabel(store)}: stan, sprzedaż, dostawy i historia cen każdego artykułu.`}
        actions={<PriceLabelsButton storeId={store.id} />}
      />
      <div className="grid gap-4 lg:grid-cols-[22rem_minmax(0,1fr)] xl:grid-cols-[26rem_minmax(0,1fr)]">
        <ProductList
          storeId={store.id}
          query={query}
          debouncedQuery={debouncedQuery}
          onQueryChange={setQuery}
          filters={filters}
          onFiltersChange={(next) => setParams((current) => writeFilters(current, next), { replace: true })}
          selected={selected}
          onSelect={(itm8) => setParam('artykul', itm8)}
          className={cn(selected && 'max-lg:hidden')}
        />
        <section className={cn('min-w-0', !selected && 'max-lg:hidden')} aria-label="Karta artykułu">
          {selected ? (
            <>
              <div className="sticky top-14 z-10 mb-3 grid justify-items-start gap-1 bg-background py-2 lg:hidden">
                <Button variant="ghost" size="sm" className="-ml-2" onClick={() => setParam('artykul', null)}>
                  <ArrowLeftIcon />
                  Lista artykułów
                </Button>
                <ArticleJump
                  storeId={store.id}
                  onFound={(itm8) => {
                    jumped.current = true
                    setParam('artykul', itm8)
                  }}
                  onSeveral={(code) =>
                    setParams(
                      (current) => {
                        const next = new URLSearchParams(current)
                        next.delete('artykul')
                        next.set('szukaj', code)
                        return next
                      },
                      { replace: true },
                    )
                  }
                />
              </div>
              <Card className="gap-5 px-5 py-5 sm:px-6">
                <ArticleSalesPanel
                  storeId={store.id}
                  target={{ kind: 'article', itm8: selected, name: null, ean: null, brand: null }}
                  lastSaleDate={lastSaleDate}
                />
                <ArticlePriceHistory key={selected} storeId={store.id} itm8={selected} />
              </Card>
            </>
          ) : (
            <Card className="h-full min-h-80 items-center justify-center gap-2 px-6 py-12 text-center">
              <PackageSearchIcon aria-hidden className="size-8 text-muted-foreground" />
              <p className="font-medium">Wybierz artykuł z listy</p>
              <p className="max-w-sm text-sm text-muted-foreground">
                Zobaczysz jego stan, sprzedaż, dostawy, zmiany cen i promocje.
              </p>
            </Card>
          )}
        </section>
      </div>
    </>
  )
}

const withoutLeadingZeros = (code: string | null) => code?.replace(/^0+/, '') ?? ''

/**
 * Pole na EAN nad kartą artykułu: wklejony albo zeskanowany kod (Enter) od razu otwiera artykuł. Główny EAN ma
 * pierwszeństwo, bo krótki kod bywa też początkiem dłuższych; gdy kilka artykułów pasuje, wybiera się je na liście.
 */
function ArticleJump({
  storeId,
  onFound,
  onSeveral,
}: {
  storeId: string
  onFound: (itm8: string) => void
  onSeveral: (code: string) => void
}) {
  const [code, setCode] = useState('')
  const [message, setMessage] = useState<string | null>(null)
  const input = useRef<HTMLInputElement>(null)
  const lastSearch = useRef(0)

  const search = async (text: string) => {
    const wanted = text.trim()
    if (!wanted) return
    const current = ++lastSearch.current
    setMessage(null)
    try {
      const page = await findProducts(storeId, wanted)
      if (current !== lastSearch.current) return
      const match =
        page.items.find((item) => withoutLeadingZeros(item.ean) === withoutLeadingZeros(wanted)) ??
        (page.total === 1 ? page.items[0] : undefined)
      if (match) {
        setCode('')
        // Na telefonie klawiatura zasłoniłaby kartę; przy czytniku kodów pole zostaje gotowe na kolejny kod.
        if (window.matchMedia('(pointer: coarse)').matches) input.current?.blur()
        onFound(match.itm8)
      } else if (page.total > 1) {
        onSeveral(wanted)
      } else {
        setMessage(`Nie znaleziono artykułu z kodem ${wanted}.`)
      }
    } catch {
      if (current === lastSearch.current) setMessage('Nie udało się sprawdzić kodu. Spróbuj ponownie.')
    }
  }

  const paste = (event: ClipboardEvent<HTMLDivElement>) => {
    const pasted = event.clipboardData.getData('text')
    if (!pasted.trim()) return
    event.preventDefault()
    setCode(pasted.trim())
    void search(pasted)
  }

  return (
    <div className="grid w-full gap-1" onPaste={paste}>
      <SearchInput
        inputRef={input}
        value={code}
        onChange={(value) => {
          setCode(value)
          setMessage(null)
        }}
        onEnter={() => void search(code)}
        placeholder="Wklej lub zeskanuj EAN następnego artykułu"
      />
      {message && (
        <p role="alert" className="px-1 text-xs text-destructive">
          {message}
        </p>
      )}
    </div>
  )
}

/**
 * Lista artykułów we własnym obszarze przewijania, przyklejona pod nagłówkiem aplikacji, z doczytywaniem stron.
 * Strzałki góra / dół przechodzą do sąsiedniego artykułu.
 */
function ProductList({
  storeId,
  query,
  debouncedQuery,
  onQueryChange,
  filters,
  onFiltersChange,
  selected,
  onSelect,
  className,
}: {
  storeId: string
  query: string
  debouncedQuery: string
  onQueryChange: (value: string) => void
  filters: ProductFilters
  onFiltersChange: (filters: ProductFilters) => void
  selected: string | null
  onSelect: (itm8: string) => void
  className?: string
}) {
  // Filtry jako tekst zapytania: przedziały wpisuje się cyframi, więc też z opóźnieniem.
  const debouncedFilters = useDebouncedValue(filterParams(filters).toString(), 250)
  const list = useProductList(storeId, debouncedQuery, debouncedFilters)
  const [filtersOpen, setFiltersOpen] = useState(false)
  const filterCount = activeFilterCount(filters)
  const items = list.data?.pages.flatMap((page) => page.items) ?? []
  const total = list.data?.pages[0]?.total
  const scroller = useRef<HTMLDivElement>(null)
  const loadMore = useLoadMore<HTMLDivElement>(
    () => list.hasNextPage && !list.isFetchingNextPage && list.fetchNextPage(),
    list.hasNextPage,
    items.length,
    scroller,
  )

  // Nowe wyszukiwanie albo filtry zaczynają od góry listy.
  useEffect(() => {
    scroller.current?.scrollTo({ top: 0 })
  }, [debouncedQuery, debouncedFilters])

  const move = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
    event.preventDefault()
    const index = items.findIndex((item) => item.itm8 === selected)
    const next = items[Math.min(Math.max(index + (event.key === 'ArrowDown' ? 1 : -1), 0), items.length - 1)]
    if (!next) return
    onSelect(next.itm8)
    scroller.current?.querySelector<HTMLButtonElement>(`[data-itm8="${next.itm8}"]`)?.focus({ preventScroll: false })
  }

  return (
    <Card
      className={cn('gap-0 py-0 lg:sticky lg:top-[4.5rem] lg:h-[calc(100dvh-5.5rem)] max-lg:max-h-[75dvh]', className)}
    >
      <div className="grid gap-2 border-b p-3">
        <div className="flex gap-2">
          <SearchInput
            className="min-w-0 flex-1"
            value={query}
            onChange={onQueryChange}
            placeholder="Szukaj: nazwa, marka, EAN, ITM8"
          />
          <Button
            variant={filtersOpen ? 'secondary' : 'outline'}
            className="h-10 shrink-0"
            aria-expanded={filtersOpen}
            aria-controls="product-filters"
            onClick={() => setFiltersOpen((open) => !open)}
          >
            <SlidersHorizontalIcon />
            Filtry
            {filterCount > 0 && (
              <span className="rounded-full bg-foreground px-1.5 text-xs leading-5 font-semibold text-background tabular-nums">
                {filterCount}
              </span>
            )}
          </Button>
        </div>
        <p className="px-1 text-xs text-muted-foreground tabular-nums">
          {total == null
            ? 'Wczytuję…'
            : `${countLabel(total)}${debouncedQuery || filterCount > 0 ? ' pasujących' : ' w kartotece'}`}
        </p>
      </div>
      {filtersOpen && (
        <div id="product-filters" className="max-h-[60%] shrink-0 overflow-y-auto overscroll-contain">
          <ProductFiltersPanel storeId={storeId} filters={filters} onChange={onFiltersChange} />
        </div>
      )}
      <div
        ref={scroller}
        onKeyDown={move}
        className={cn(
          'min-h-0 flex-1 overflow-y-auto overscroll-contain transition-opacity duration-150',
          list.isPlaceholderData && 'opacity-60',
        )}
      >
        {list.isError && items.length === 0 ? (
          <div className="grid justify-items-center gap-3 px-4 py-10 text-center text-sm text-muted-foreground">
            Nie udało się wczytać kartoteki.
            <Button variant="outline" size="sm" onClick={() => list.refetch()}>
              Spróbuj ponownie
            </Button>
          </div>
        ) : list.isPending ? (
          <div aria-hidden className="grid gap-px p-2">
            {Array.from({ length: 10 }, (_, index) => (
              <div key={index} className="h-14 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
            ))}
          </div>
        ) : items.length === 0 ? (
          <p className="px-4 py-10 text-center text-sm text-muted-foreground">
            {debouncedQuery || filterCount > 0
              ? 'Żaden artykuł nie pasuje do wyszukiwania i filtrów.'
              : 'Kartoteka jest pusta. Wgraj raport produktów w Imporcie danych.'}
          </p>
        ) : (
          <ul className="grid gap-px p-1.5">
            {items.map((item) => (
              <li key={item.itm8}>
                <ProductRow item={item} active={item.itm8 === selected} onSelect={() => onSelect(item.itm8)} />
              </li>
            ))}
          </ul>
        )}
        <div ref={loadMore} aria-hidden />
        {list.isFetchingNextPage && (
          <p className="pb-3 text-center text-xs text-muted-foreground">Wczytuję kolejne artykuły…</p>
        )}
      </div>
    </Card>
  )
}

function ProductRow({ item, active, onSelect }: { item: ProductListItem; active: boolean; onSelect: () => void }) {
  const inactive = item.active === false
  return (
    <button
      type="button"
      data-itm8={item.itm8}
      aria-current={active || undefined}
      onClick={onSelect}
      className={cn(
        'grid w-full grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3 rounded-lg px-3 py-2 text-left transition-colors duration-100 outline-none focus-visible:ring-3 focus-visible:ring-ring/40',
        active ? 'bg-accent text-accent-foreground' : 'hover:bg-muted',
      )}
    >
      <span className="min-w-0">
        <span className={cn('block truncate font-medium', inactive && 'text-muted-foreground')}>
          {item.name ?? `ITM8 ${item.itm8}`}
        </span>
        <span className="block truncate text-xs text-muted-foreground tabular-nums">
          {[item.brand, item.ean, inactive && 'nieaktywny'].filter(Boolean).join(' · ')}
        </span>
      </span>
      <span className="text-right tabular-nums">
        <span
          className={cn(
            'block font-medium whitespace-nowrap',
            item.promoPrice != null && 'font-semibold text-orange-600 dark:text-orange-400',
          )}
          title={item.promoPrice != null ? `Promocja, cena regularna ${formatMoney(item.salePrice)} zł` : undefined}
        >
          {item.promoPrice != null
            ? `${formatMoney(item.promoPrice)} zł`
            : item.salePrice != null
              ? `${formatMoney(item.salePrice)} zł`
              : '—'}
        </span>
        <span
          className={cn(
            'block text-xs whitespace-nowrap text-muted-foreground',
            item.stock != null && item.stock < 0 && 'text-destructive',
          )}
        >
          stan {item.stock == null ? '—' : formatQuantity(item.stock)}
          {item.rotation != null && ` · rot. ${formatQuantity(item.rotation)}`}
        </span>
      </span>
    </button>
  )
}

const countFormat = new Intl.NumberFormat('pl-PL')

/** "1 artykuł", "3 artykuły", "5 artykułów". */
function countLabel(count: number) {
  const tens = count % 100
  const units = count % 10
  const word = count === 1 ? 'artykuł' : units >= 2 && units <= 4 && (tens < 12 || tens > 14) ? 'artykuły' : 'artykułów'
  return `${countFormat.format(count)} ${word}`
}
