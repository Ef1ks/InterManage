import { useMemo, useRef, useState } from 'react'
import { ShoppingBasketIcon } from 'lucide-react'
import { toast } from 'sonner'

import { SegmentedField } from '@/components/form'
import { LoadingPanel } from '@/components/LoadingPanel'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { MAX_QUANTITY, ORDER_EAN } from '@/features/data-entry/order-file'
import { formatDocumentDate, formatMoney, formatQuantity } from '@/features/documents/format'
import { useBasketPreview } from '@/features/sales/api'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { supplierKey, type SupplierOption, useBasket, withBaseSupplier } from '@/features/sales/SalesBasket'
import { ApiError } from '@/lib/api'
import type { BasketArticleDetails } from '@/lib/types'
import { useLoadMore } from '@/lib/use-load-more'
import { cn } from '@/lib/utils'
import { useAppDispatch } from '@/store'
import { addBasketLines, type BasketLine, MAX_BASKET_SUPPLIERS, setBasket } from '@/store/salesBasketSlice'

/** Artykuł z raportu; {@code weekly}: ile schodzi tygodniowo (podpowiedź ilości), puste = nie wiadomo. */
export type BulkBasketItem = { itm8: string; weekly: number | null }

/** Skąd ilość na start: sprzedaż tygodnia, ostatnia dostawa od dostawcy albo po jednej. */
export type BulkQuantity = 'week' | 'last' | 'one'
type SupplierPick = 'auto' | 'last' | 'cheapest'

const PAGE = 60

type Row = { article: BasketArticleDetails; weekly: number | null; options: SupplierOption[] }

const supplierOptionKey = (supplier: BasketArticleDetails['suppliers'][number]) =>
  supplier.code || `nazwa:${supplier.name}`

function optionsOf(article: BasketArticleDetails): SupplierOption[] {
  return withBaseSupplier(
    article.suppliers.map((supplier) => ({
      key: supplierOptionKey(supplier),
      code: supplier.code,
      name: supplier.name ?? `Dostawca ${supplier.code}`,
      price: supplier.purchasePrice,
      lastDate: supplier.lastDate,
      lastQuantity: supplier.lastQuantity,
      deliveries: supplier.deliveries,
    })),
    {
      cadencier: article.cadencier,
      region: article.subfamilyCode?.slice(0, 2) ?? '',
      catalogPrice: article.purchasePrice,
      current: article.cadencierCurrent,
    },
  )
}

function pickOption({ article, options }: Pick<Row, 'article' | 'options'>, pick: SupplierPick): SupplierOption | null {
  if (options.length === 0) return null
  if (pick === 'cheapest') {
    const priced = options.filter((option) => option.price != null)
    if (priced.length > 0) return priced.reduce((best, option) => (option.price! < best.price! ? option : best))
  }
  if (pick === 'last') {
    const delivered = options.filter((option) => option.lastDate)
    if (delivered.length > 0) {
      return delivered.reduce((best, option) => (option.lastDate! > best.lastDate! ? option : best))
    }
  }
  // Domyślnie artykuł z cadencier z bazy, pozostałe od ostatniego dostawcy. Poza cadencier ostatnia dostawa z bazy to
  // zwykle jednorazowa gazetka, więc gdy przedostatnia była od kogoś innego, zamawiamy u niego.
  if (pick === 'auto' && !article.cadencier && options[0].base) {
    const regular = article.suppliers.find((supplier) => supplier.rank === 2)
    const option = regular && options.find((candidate) => candidate.key === supplierOptionKey(regular))
    if (option && !option.base) return option
  }
  return options[0]
}

function startQuantity(row: Row, option: SupplierOption, mode: BulkQuantity): number {
  if (mode === 'one') return 1
  const week = row.weekly != null && row.weekly > 0 ? Math.ceil(row.weekly) : null
  const last = option.lastQuantity != null && option.lastQuantity > 0 ? option.lastQuantity : null
  const pieces = (mode === 'week' ? (week ?? last) : (last ?? week)) ?? 1
  if (!option.base) return Math.round(pieces * 1000) / 1000
  // Do bazy zamawia się kartony: bez znanego kartonu jeden.
  const caseSize = row.article.caseSize
  if (caseSize == null || caseSize <= 0) return 1
  return Math.min(Math.max(Math.ceil(pieces / caseSize), 1), MAX_QUANTITY)
}

function validQuantity(quantity: number, base: boolean) {
  return base
    ? Number.isInteger(quantity) && quantity >= 1 && quantity <= MAX_QUANTITY
    : quantity > 0 && quantity <= 99_999 && Math.round(quantity * 1000) / 1000 === quantity
}

/** Okno „Dodaj do koszyka” dla wielu zaznaczonych artykułów naraz: dostawca i ilość przy każdym. */
export function BulkBasketDialog({
  storeId,
  items,
  startWith = 'last',
  open,
  onClose,
  onAdded,
}: {
  storeId: string
  items: BulkBasketItem[]
  startWith?: BulkQuantity
  open: boolean
  onClose: () => void
  onAdded: () => void
}) {
  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent className="gap-4 p-6 sm:max-w-5xl">
        <DialogHeader>
          <DialogTitle>Dodaj do koszyka</DialogTitle>
          <DialogDescription>
            Zaznaczone artykuły: {formatQuantity(items.length)}. Koszyk jest wspólny dla porównania sprzedaży, braków
            towaru i brakującego asortymentu.
          </DialogDescription>
        </DialogHeader>
        {open && (
          <BulkContent
            storeId={storeId}
            items={items}
            startWith={startWith}
            onAdded={() => {
              onAdded()
              onClose()
            }}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

function BulkContent({
  storeId,
  items,
  startWith,
  onAdded,
}: {
  storeId: string
  items: BulkBasketItem[]
  startWith: BulkQuantity
  onAdded: () => void
}) {
  const dispatch = useAppDispatch()
  const basket = useBasket(storeId)
  const itm8s = useMemo(() => items.map((item) => item.itm8), [items])
  const preview = useBasketPreview(storeId, itm8s)
  const [pick, setPick] = useState<SupplierPick>('auto')
  const [mode, setMode] = useState<BulkQuantity>(startWith)
  const [suppliers, setSuppliers] = useState<Record<string, string>>({})
  const [quantities, setQuantities] = useState<Record<string, string>>({})
  const [toggled, setToggled] = useState<Record<string, boolean>>({})
  const [shown, setShown] = useState(PAGE)
  const list = useRef<HTMLUListElement>(null)

  const rows = useMemo<Row[]>(() => {
    const weekly = new Map(items.map((item) => [item.itm8, item.weekly]))
    return (preview.data ?? []).map((article) => ({
      article,
      weekly: weekly.get(article.itm8) ?? null,
      options: optionsOf(article),
    }))
  }, [preview.data, items])
  const loadMore = useLoadMore<HTMLLIElement>(
    () => setShown((count) => count + PAGE),
    shown < rows.length,
    shown + (rows.length > 0 ? 0 : -1),
    list,
  )

  const inBasket = useMemo(() => {
    const found = new Map<string, number>()
    for (const line of basket) found.set(line.itm8, (found.get(line.itm8) ?? 0) + line.quantity)
    return found
  }, [basket])

  if (preview.isError) {
    return (
      <div className="flex flex-col items-center gap-3 rounded-lg border px-4 py-8 text-center">
        <p className="text-sm">
          {preview.error instanceof ApiError ? preview.error.message : 'Nie udało się wczytać dostawców'}
        </p>
        <Button variant="outline" onClick={() => preview.refetch()}>
          Spróbuj ponownie
        </Button>
      </div>
    )
  }
  if (!preview.data) return <LoadingPanel className="h-96 rounded-lg" label="Wczytuję dostawców…" />

  const lines = rows.map((row) => {
    const option =
      row.options.find((candidate) => candidate.key === suppliers[row.article.itm8]) ?? pickOption(row, pick)
    const problem = !option
      ? 'bez dostaw w ostatnim roku'
      : option.base && !(row.article.ean && ORDER_EAN.test(row.article.ean))
        ? 'bez EAN-u nie trafi do pliku z bazy'
        : null
    const text =
      quantities[row.article.itm8] ?? (option ? String(startQuantity(row, option, mode)).replace('.', ',') : '')
    return { row, option, problem, text, quantity: Number(text.replace(',', '.')) }
  })

  const groups = new Map<string, { option: SupplierOption; count: number }>()
  for (const { option, problem } of lines) {
    if (!option || problem) continue
    const known = groups.get(option.key)
    if (known) known.count += 1
    else groups.set(option.key, { option, count: 1 })
  }
  const bySize = [...groups.values()].sort((a, b) => Number(b.option.base) - Number(a.option.base) || b.count - a.count)
  const basketSuppliers = new Set(basket.map(supplierKey))
  const free = MAX_BASKET_SUPPLIERS - basketSuppliers.size
  // Na start włączeni dostawcy, którzy już są w koszyku, i tylu największych nowych, ilu się zmieści.
  const fitting = new Set(
    bySize
      .filter(({ option }) => !basketSuppliers.has(option.key))
      .slice(0, Math.max(free, 0))
      .map(({ option }) => option.key),
  )
  const isOn = (key: string) => toggled[key] ?? (basketSuppliers.has(key) || fitting.has(key))
  const newSuppliers = bySize.filter(({ option }) => isOn(option.key) && !basketSuppliers.has(option.key)).length

  const ready = lines.filter(({ option, problem }) => option && !problem && isOn(option.key))
  const invalid = ready.filter(({ option, quantity }) => !validQuantity(quantity, option!.base))
  const switchedOff = bySize.filter(({ option }) => !isOn(option.key)).length
  const skipped = lines.filter(({ problem }) => problem).length
  const total = ready.reduce(
    (sum, { option, quantity }) =>
      sum + (option!.price != null && !option!.base && Number.isFinite(quantity) ? option!.price * quantity : 0),
    0,
  )
  const error =
    newSuppliers > free
      ? `W koszyku mieści się ${MAX_BASKET_SUPPLIERS} dostawców, wolne miejsca: ${Math.max(free, 0)}. Wyłącz kilku nad listą.`
      : invalid.length > 0
        ? `Popraw ilości (${invalid.length}): z bazy całe kartony od 1 do ${MAX_QUANTITY}, u innych ilość większa od zera.`
        : null

  const add = () => {
    const before = basket
    const added: BasketLine[] = ready.map(({ row, option, quantity }) => ({
      itm8: row.article.itm8,
      ean: row.article.ean,
      name: row.article.name,
      brand: row.article.brand,
      supplierCode: option!.code,
      supplierName: option!.name,
      base: option!.base,
      purchasePrice: option!.price,
      quantity,
    }))
    dispatch(addBasketLines({ storeId, lines: added }))
    toast.success(`Dodano do koszyka: ${formatQuantity(added.length)} art.`, {
      duration: 10_000,
      action: { label: 'Cofnij', onClick: () => dispatch(setBasket({ storeId, lines: before })) },
    })
    onAdded()
  }

  return (
    <>
      <div className="flex flex-wrap gap-x-6 gap-y-3">
        <div className="grid gap-1.5">
          <span className="text-xs font-medium text-muted-foreground">Dostawca</span>
          <SegmentedField
            label="Dostawca"
            options={[
              { value: 'auto', label: 'Cadencier z bazy' },
              { value: 'last', label: 'Ostatni' },
              { value: 'cheapest', label: 'Najtańszy' },
            ]}
            value={pick}
            onChange={(next) => {
              setPick(next)
              setSuppliers({})
            }}
          />
        </div>
        <div className="grid gap-1.5">
          <span className="text-xs font-medium text-muted-foreground">Ilość</span>
          <SegmentedField
            label="Ilość"
            options={[
              { value: 'week', label: 'Na tydzień' },
              { value: 'last', label: 'Jak ostatnio' },
              { value: 'one', label: 'Po 1' },
            ]}
            value={mode}
            onChange={(next) => {
              setMode(next)
              setQuantities({})
            }}
          />
        </div>
      </div>

      {(bySize.length > 1 || switchedOff > 0) && (
        <div className="flex max-h-28 flex-wrap gap-1.5 overflow-y-auto" role="group" aria-label="Dostawcy do koszyka">
          {bySize.map(({ option, count }) => {
            const on = isOn(option.key)
            return (
              <button
                key={option.key}
                type="button"
                aria-pressed={on}
                title={on ? 'Wyłącz dostawcę' : 'Włącz dostawcę'}
                onClick={() => setToggled((current) => ({ ...current, [option.key]: !on }))}
                className={cn(
                  'max-w-64 truncate rounded-lg border px-2.5 py-1 text-sm transition-colors duration-150 outline-none focus-visible:ring-3 focus-visible:ring-ring/50',
                  on
                    ? 'border-foreground bg-foreground text-background'
                    : 'bg-card text-muted-foreground hover:border-foreground/20',
                )}
              >
                {option.code && <span className="tabular-nums opacity-70">{option.code} </span>}
                {option.name}
                <span className="ml-1.5 tabular-nums opacity-70">{count}</span>
              </button>
            )
          })}
        </div>
      )}

      <ul ref={list} className="max-h-[48vh] divide-y overflow-y-auto rounded-lg border">
        {lines.slice(0, shown).map(({ row: { article, options }, option, problem, text, quantity }) => {
          const on = option != null && !problem && isOn(option.key)
          const already = inBasket.get(article.itm8)
          return (
            <li key={article.itm8} className="flex flex-wrap items-center gap-x-3 gap-y-1.5 px-3 py-2 text-sm">
              <span className={cn('min-w-0 flex-1 basis-full sm:basis-64', !on && 'opacity-50')}>
                <span className="flex items-center gap-1.5">
                  <span className="min-w-0 truncate" title={article.name ?? undefined}>
                    {article.name ?? article.itm8}
                    {article.brand && <span className="text-muted-foreground"> · {article.brand}</span>}
                  </span>
                  {article.cadencier && (
                    <span className="shrink-0 rounded-md bg-blue-200 px-1.5 text-xs font-medium text-blue-950 dark:bg-blue-500/30 dark:text-blue-100">
                      cadencier
                    </span>
                  )}
                  <ArticleStatsButton article={article} skipTab />
                </span>
                <span className="block text-xs text-muted-foreground tabular-nums">
                  {[
                    `stan ${article.stock == null ? '–' : formatQuantity(article.stock)}`,
                    article.caseSize && article.caseSize !== 1 ? `karton ${formatQuantity(article.caseSize)}` : null,
                    option?.lastDate ? `ostatnio ${formatDocumentDate(option.lastDate)}` : null,
                    already ? `w koszyku ${formatQuantity(already)}` : null,
                  ]
                    .filter(Boolean)
                    .join(' · ')}
                </span>
                {problem && <span className="block text-xs text-destructive">{problem}</span>}
              </span>
              {options.length > 0 && (
                <select
                  aria-label={`Dostawca: ${article.name ?? article.itm8}`}
                  value={option?.key}
                  onChange={(event) => setSuppliers((current) => ({ ...current, [article.itm8]: event.target.value }))}
                  className="h-8 w-full min-w-0 flex-1 rounded-lg border border-input bg-background px-2 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/30 sm:w-64 sm:flex-none dark:bg-input/30"
                >
                  {options.map((candidate) => (
                    <option key={candidate.key} value={candidate.key}>
                      {candidate.code} {candidate.name}
                      {candidate.price != null ? ` · ${formatMoney(candidate.price)} zł` : ''}
                    </option>
                  ))}
                </select>
              )}
              <span className="w-20 shrink-0 text-right text-muted-foreground tabular-nums">
                {option?.price == null ? 'brak ceny' : `${formatMoney(option.price)} zł`}
              </span>
              <span className="flex w-28 shrink-0 items-center justify-end gap-1.5">
                <Input
                  aria-label={`${option?.base ? 'Kartony' : 'Ilość'}: ${article.name ?? article.itm8}`}
                  inputMode="decimal"
                  className="h-8 w-18 text-right tabular-nums"
                  value={text}
                  disabled={!on}
                  aria-invalid={on && !validQuantity(quantity, option!.base)}
                  onFocus={(event) => event.target.select()}
                  onChange={(event) => {
                    const value = event.target.value
                    setQuantities((current) => ({ ...current, [article.itm8]: value }))
                  }}
                />
                <span className="w-8 text-xs text-muted-foreground">{option?.base ? 'kart.' : ''}</span>
              </span>
            </li>
          )
        })}
        <li ref={loadMore} aria-hidden className="h-0 border-0!" />
      </ul>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className={cn('min-w-0 flex-1 text-sm', error ? 'text-destructive' : 'text-muted-foreground')}>
          {error ??
            [
              total > 0 ? `Dostawcy spoza bazy: ${formatMoney(total)} zł netto` : null,
              switchedOff > 0
                ? `wyłączeni dostawcy: ${switchedOff} (w koszyku mieści się ${MAX_BASKET_SUPPLIERS}, zajęte: ${basketSuppliers.size + newSuppliers})`
                : null,
              skipped > 0 ? `pominięte: ${skipped}` : null,
            ]
              .filter(Boolean)
              .join(' · ')}
        </p>
        <Button size="lg" disabled={ready.length === 0 || error != null} onClick={add}>
          <ShoppingBasketIcon />
          Dodaj do koszyka ({formatQuantity(ready.length)})
        </Button>
      </div>
    </>
  )
}
