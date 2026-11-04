import {
  ChevronDownIcon,
  FileInputIcon,
  FileOutputIcon,
  HashIcon,
  HistoryIcon,
  LinkIcon,
  RefreshCwIcon,
  SparklesIcon,
  Unlink2Icon,
} from 'lucide-react'
import { Fragment, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { CopyButton } from '@/components/CopyButton'
import { FullPageMessage } from '@/components/FullPageMessage'
import { HowTo } from '@/components/HowTo'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { meaPath } from '@/features/base-delivery/paths'
import { ColumnFilter } from '@/features/base-delivery/ColumnFilter'
import { CatalogListDialog } from '@/features/data-entry/CatalogListDialog'
import { CommandBar } from '@/features/data-entry/DataEntryPage'
import { formatDocumentDate, formatMoney, formatQuantity } from '@/features/documents/format'
import { useSalesReportsOverview } from '@/features/sales/api'
import { useCan } from '@/features/auth/api'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import { addDays, todayIso } from '@/lib/dates'
import type { DataEntryCatalogListItem, LeafletLine, StoreSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

import { useLeaflet, useLeafletLink, useLeaflets, useUploadLeaflet } from './api'
import { ExtraMarksDialog } from './ExtraMarksDialog'
import { LeafletLabels } from './LeafletLabels'
import { extraMarks, hasStrikePrice, isDiscount, labelFormat, proFiles, promoPrice, toProcess } from './leaflet-rows'
import { ProFilesDialog } from './ProFilesDialog'

/**
 * Opracowanie gazetki: plik z centrali w tabeli, gdzie każdy EAN z gazetki dostaje artykuł z kartoteki (Mercalys).
 * Z pozycji bez kodu MEA (puste albo DDD) powstają pliki cen promocji .PRO (jeden okres = jeden plik) i pliki etykiet
 * w formatach cenówek; kody MEA z gazetki trafiają na listę do wpisania jak kody z dostaw z bazy. Tylko na komputerze.
 */
export function LeafletPage() {
  const store = useCurrentStore()
  return (
    <>
      <span data-wide-page hidden />
      {!store ? (
        <>
          <PageHeader title="Opracowanie gazetki" />
          <FullPageMessage title="Brak sklepu" description="Gazetkę opracowuje się osobno w każdym sklepie." inline />
        </>
      ) : (
        <>
          <div className="lg:hidden">
            <PageHeader title="Opracowanie gazetki" />
            <FullPageMessage
              title="Otwórz na komputerze"
              description="Tabela gazetki ma ponad tysiąc pozycji i potrzebuje dużego ekranu."
              inline
            />
          </div>
          <div className="hidden min-h-0 flex-1 flex-col lg:flex">
            <StoreLeaflet key={store.id} store={store} />
          </div>
        </>
      )}
    </>
  )
}

type FilterKey = 'dateFrom' | 'dateTo' | 'brand' | 'meaCode' | 'label'
type Filters = Partial<Record<FilterKey, string[]>>

const EMPTY = '(puste)'
const NO_ITEMS = new Set<string>()

/** Wartość pozycji w filtrze kolumny (jak w tabeli). */
function filterValue(line: LeafletLine, key: FilterKey) {
  switch (key) {
    case 'dateFrom':
      return formatDocumentDate(line.dateFrom)
    case 'dateTo':
      return formatDocumentDate(line.dateTo)
    case 'brand':
      return line.product?.brand ?? EMPTY
    case 'meaCode':
      return line.meaCode ?? EMPTY
    case 'label':
      return labelFormat(line) ?? 'bez etykiety z Mercalys'
  }
}

/** Wartości kolumny z liczbą pozycji, posortowane (daty po kolejności dni). */
function filterValues(lines: LeafletLine[], key: FilterKey) {
  const counts = new Map<string, { value: string; count: number; order: string }>()
  for (const line of lines) {
    const value = filterValue(line, key)
    const order = key === 'dateFrom' ? line.dateFrom : key === 'dateTo' ? line.dateTo : value
    const known = counts.get(value)
    counts.set(value, { value, count: (known?.count ?? 0) + 1, order })
  }
  return [...counts.values()]
    .sort((a, b) => a.order.localeCompare(b.order, 'pl'))
    .map(({ value, count }) => ({ value, count }))
}

/** Początki dwóch pierwszych słów nazwy z gazetki do wyszukiwania w liście towarów („G.X.RM.PAPRIKA 130G” → „pap”). */
function searchFilter(name: string) {
  return name
    .toLowerCase()
    .split(/[^\p{L}]+/u)
    .filter((word) => word.length >= 3)
    .slice(0, 2)
    .map((word) => word.slice(0, 3))
    .join(' ')
}

function StoreLeaflet({ store }: { store: StoreSummary }) {
  const [params, setParams] = useSearchParams()
  const list = useLeaflets(store.id)
  const leafletId = params.get('gazetka') ?? list.data?.[0]?.id ?? null
  const leaflet = useLeaflet(store.id, leafletId)
  const upload = useUploadLeaflet(store.id)
  const link = useLeafletLink(store.id, leafletId)
  const canSeeSales = useCan('SALES', 'VIEW')
  const salesOverview = useSalesReportsOverview(store.id, canSeeSales)

  const showAll = params.get('widok') === 'wszystkie'
  const [filters, setFilters] = useState<Filters>({})
  const [withoutProduct, setWithoutProduct] = useState(false)
  const [toolbarHeight, setToolbarHeight] = useState(0)
  const [proOpen, setProOpen] = useState(false)
  const [extraOpen, setExtraOpen] = useState(false)
  const [picking, setPicking] = useState<LeafletLine | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)

  const setParam = (key: string, value: string | null) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        if (value === null) next.delete(key)
        else next.set(key, value)
        return next
      },
      { replace: true },
    )

  const lines = useMemo(() => leaflet.data?.lines ?? [], [leaflet.data])
  const processLines = useMemo(() => lines.filter(toProcess), [lines])
  const scope = showAll ? lines : processLines
  const visible = useMemo(
    () =>
      scope.filter(
        (line) =>
          (!withoutProduct || !line.product) &&
          (Object.entries(filters) as [FilterKey, string[]][]).every(([key, values]) =>
            values.includes(filterValue(line, key)),
          ),
      ),
    [scope, filters, withoutProduct],
  )
  const files = useMemo(() => (leaflet.data ? proFiles(lines, leaflet.data.week) : []), [lines, leaflet.data])
  const extras = useMemo(() => extraMarks(lines), [lines])
  const missing = scope.filter((line) => !line.product).length
  const filtersActive = Object.keys(filters).length > 0 || withoutProduct

  const uploadFile = (file: File) =>
    upload.mutate(file, {
      onSuccess: (loaded) => {
        toast.success(`Wgrano gazetkę ${loaded.week}/${loaded.year}`, {
          description: `${loaded.lines.length} pozycji, do opracowania: ${loaded.lines.filter(toProcess).length}`,
        })
        setFilters({})
        setWithoutProduct(false)
        setParam('gazetka', loaded.id)
      },
      onError: (error) => {
        const rowErrors =
          error instanceof ApiError && Array.isArray(error.problem.rowErrors)
            ? (error.problem.rowErrors as string[])
            : []
        toast.error(error instanceof ApiError ? error.message : 'Nie udało się wgrać pliku', {
          description: rowErrors.length > 0 ? rowErrors.slice(0, 5).join('\n') : undefined,
          duration: 10_000,
        })
      },
    })

  const runLink = (action: Parameters<typeof link.mutate>[0], onDone?: (linkId: string | null) => void) =>
    link.mutate(action, {
      onSuccess: (match) => onDone?.(match.linkId),
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się zmienić artykułu'),
    })

  const pick = (line: LeafletLine, item: DataEntryCatalogListItem) => {
    setPicking(null)
    runLink({ type: 'link', ean: line.ean, itm8: item.itm8 }, (linkId) =>
      toast.success(`EAN ${line.ean} z gazetki: ${item.name ?? item.itm8}`, {
        description: 'Zapamiętane w sklepie na kolejne gazetki.',
        action: linkId ? { label: 'Cofnij', onClick: () => runLink({ type: 'unlink', linkId }) } : undefined,
      }),
    )
  }

  const unlink = (line: LeafletLine) => {
    const linkId = line.linkId
    if (!linkId) return
    runLink({ type: 'unlink', linkId }, () =>
      toast(`Usunięto przypięcie EAN-u ${line.ean}`, {
        action: { label: 'Przywróć', onClick: () => runLink({ type: 'restore', linkId }) },
      }),
    )
  }

  const data = leaflet.data
  const stickyTop = `calc(3.5rem + ${toolbarHeight}px)`
  const columnFilter = (key: FilterKey, label: string) => (
    <ColumnFilter
      label={label}
      getValues={() => filterValues(scope, key)}
      selected={filters[key] ?? null}
      onChange={(selected) =>
        setFilters((current) => {
          const next = { ...current }
          if (selected === null) delete next[key]
          else next[key] = selected
          return next
        })
      }
    />
  )

  return (
    <>
      <PageHeader
        title="Opracowanie gazetki"
        description={data ? `Gazetka ${data.week}/${data.year} · ${data.fileName}` : storeLabel(store)}
      />

      <HowTo
        id="leaflet"
        className="mb-4"
        title="Jak opracować gazetkę"
        steps={[
          'Wczytaj plik gazetki z centrali (UTRZYMYWANIE CEN Gazetka … .xlsx). Tabela pokazuje pozycje do opracowania: bez kodu MEA (puste albo DDD); „Wszystkie” pokazuje całą gazetkę.',
          'Każdy EAN z gazetki musi mieć artykuł z Mercalys. Brakujący albo zły zmienisz strzałkami przy nazwie: wybrany artykuł zostaje zapamiętany na kolejne gazetki.',
          'Kody MEA z gazetki wpisz w Mercalys i potwierdź w „Kody MEA”, tak jak przy dostawie z bazy.',
          '„Stwórz pliki PRO”: plik z cenami promocji na każdy okres od-do do N:\\ALOA\\PRO\\IMPORT, przy wczytaniu wpisz jego kod taryfy (TYDZGAZ…).',
          'Etykiety: wybierz format albo „Generuj wszystkie etykiety” (z całej gazetki, także pozycji z kodem MEA, według oznaczenia przy półce; każdy artykuł raz). „Extra Znaczek” to lista do wydruku.',
        ]}
      />

      <CommandBar onHeightChange={setToolbarHeight}>
        {data && (
          <div className="flex shrink-0 flex-col items-center justify-center rounded-md border-2 border-foreground/20 bg-card px-3 py-1.5 text-center">
            <span className="text-xs text-muted-foreground">Gazetka</span>
            <span className="text-lg leading-tight font-bold tracking-tight tabular-nums">
              {data.week}/{data.year}
            </span>
            <span className="text-xs text-muted-foreground tabular-nums">{data.lines.length} pozycji</span>
          </div>
        )}
        <div className="flex min-w-0 flex-1 flex-col justify-center gap-1.5">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1.5" role="group" aria-label="Gazetka">
            <Button
              variant={data ? 'outline' : 'default'}
              size="sm"
              loading={upload.isPending}
              onClick={() => fileInput.current?.click()}
            >
              <FileInputIcon />
              Wczytaj gazetkę
            </Button>
            <DropdownMenu>
              <DropdownMenuTrigger
                render={<Button variant="ghost" size="sm" disabled={(list.data ?? []).length === 0} />}
              >
                <HistoryIcon />
                Wgrane wcześniej
                <ChevronDownIcon />
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" className="w-80">
                {(list.data ?? []).map((item) => (
                  <DropdownMenuItem
                    key={item.id}
                    onClick={() => {
                      setFilters({})
                      setWithoutProduct(false)
                      setParam('gazetka', item.id)
                    }}
                  >
                    <span className="flex min-w-0 flex-col">
                      <span className={cn('font-medium', item.id === leafletId && 'text-blue-700 dark:text-blue-300')}>
                        Gazetka {item.week}/{item.year}
                      </span>
                      <span className="truncate text-xs text-muted-foreground">
                        {item.fileName} · {formatDocumentDate(item.uploadedAt)}
                        {item.uploadedBy && ` · ${item.uploadedBy}`}
                      </span>
                    </span>
                  </DropdownMenuItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>
            {data && (
              <>
                <Button size="sm" onClick={() => setProOpen(true)} title="Pliki cen promocji do N:\ALOA\PRO\IMPORT">
                  <FileOutputIcon />
                  Stwórz pliki PRO ({files.length})
                </Button>
                <LeafletLabels storeId={store.id} lines={lines} />
                <Button variant="outline" size="sm" onClick={() => setExtraOpen(true)}>
                  <SparklesIcon />
                  Extra Znaczek ({extras.length})
                </Button>
              </>
            )}
            <Button
              variant="ghost"
              size="sm"
              className="ml-auto"
              nativeButton={false}
              render={<Link to={meaPath} />}
              title="Kody MEA z dokumentów bazy i gazetek, których jeszcze nie wpisano w Mercalys"
            >
              <HashIcon />
              Kody MEA{data && data.pendingMeaCodes.length > 0 && ` (${data.pendingMeaCodes.length})`}
            </Button>
          </div>
          {data && (
            <div className="flex flex-wrap items-center gap-x-2 gap-y-1.5" role="group" aria-label="Widok tabeli">
              <div className="flex rounded-md border bg-background p-0.5">
                <Button
                  variant={showAll ? 'ghost' : 'secondary'}
                  size="xs"
                  aria-pressed={!showAll}
                  onClick={() => setParam('widok', null)}
                >
                  Do opracowania ({processLines.length})
                </Button>
                <Button
                  variant={showAll ? 'secondary' : 'ghost'}
                  size="xs"
                  aria-pressed={showAll}
                  onClick={() => setParam('widok', 'wszystkie')}
                >
                  Wszystkie ({lines.length})
                </Button>
              </div>
              <Button
                variant={withoutProduct ? 'secondary' : 'ghost'}
                size="xs"
                aria-pressed={withoutProduct}
                disabled={missing === 0 && !withoutProduct}
                onClick={() => setWithoutProduct((current) => !current)}
                className={cn(missing > 0 && 'text-amber-900 dark:text-amber-200')}
              >
                Bez artykułu ({missing})
              </Button>
              {filtersActive && (
                <Button
                  variant="ghost"
                  size="xs"
                  onClick={() => {
                    setFilters({})
                    setWithoutProduct(false)
                  }}
                >
                  Wyczyść filtry
                </Button>
              )}
              <span className="ml-auto text-xs text-muted-foreground tabular-nums">
                W tabeli: {visible.length} z {scope.length}
              </span>
            </div>
          )}
        </div>
      </CommandBar>

      {data && data.pendingMeaCodes.length > 0 && (
        <div className="mb-3 flex flex-wrap items-center gap-2 rounded-lg bg-amber-200 px-3 py-2 text-sm text-amber-950 dark:bg-amber-900 dark:text-amber-50">
          <span className="font-medium">Kody MEA z gazetki do wpisania w Mercalys: {data.pendingMeaCodes.length}</span>
          <span className="min-w-0 truncate tabular-nums">{data.pendingMeaCodes.join(', ')}</span>
          <CopyButton value={data.pendingMeaCodes.join('\n')} label="Kopiuj kody MEA" />
          <Button size="xs" variant="outline" className="ml-auto" nativeButton={false} render={<Link to={meaPath} />}>
            Potwierdź w „Kody MEA”
          </Button>
        </div>
      )}

      {leafletId === null && !list.isPending ? (
        <div className="flex flex-col items-center gap-3 rounded-xl border border-dashed px-6 py-16 text-center">
          <p className="text-base font-medium">Brak wgranej gazetki</p>
          <p className="max-w-md text-sm text-muted-foreground">
            Wczytaj plik gazetki z centrali (UTRZYMYWANIE CEN Gazetka nr … + ceny.xlsx).
          </p>
          <Button className="mt-2" loading={upload.isPending} onClick={() => fileInput.current?.click()}>
            <FileInputIcon />
            Wczytaj gazetkę
          </Button>
        </div>
      ) : leaflet.isError ? (
        <p className="rounded-lg bg-amber-200 px-3 py-2 text-sm font-medium text-amber-950 dark:bg-amber-900 dark:text-amber-50">
          Nie udało się wczytać gazetki (mogła zostać wgrana ponownie). Wybierz ją z „Wgrane wcześniej”.
        </p>
      ) : !data ? (
        <p className="px-1 py-6 text-sm text-muted-foreground">Wczytywanie gazetki…</p>
      ) : (
        <div className="rounded-lg border bg-card">
          <table className="w-full border-separate border-spacing-0 text-sm">
            <thead className="sticky z-10 bg-muted text-left text-xs text-muted-foreground" style={{ top: stickyTop }}>
              <tr className="[&>th]:border-b [&>th]:px-2 [&>th]:py-1.5 [&>th]:font-medium">
                <Th filter={columnFilter('dateFrom', 'Od')}>Od</Th>
                <Th filter={columnFilter('dateTo', 'Do')}>Do</Th>
                <th>EAN gazetki</th>
                <th>Nazwa z gazetki</th>
                <th>Produkt z Mercalys</th>
                <Th filter={columnFilter('brand', 'Marka')}>Marka</Th>
                <th className="text-right" title="Cena z gazetki (cena promocji brutto)">
                  Cena gazetkowa
                </th>
                <th className="text-right" title="Cena sprzedaży z kartoteki bez promocji z kasy">
                  Cena sprz.
                </th>
                <th className="text-right">Stan</th>
                <th className="text-right">Rotacja</th>
                <Th filter={columnFilter('meaCode', 'NR MEA')}>NR MEA</Th>
                <Th filter={columnFilter('label', 'Etykieta')}>Etykieta</Th>
              </tr>
            </thead>
            <tbody>
              {visible.length === 0 && (
                <tr>
                  <td colSpan={12} className="px-3 py-8 text-center text-muted-foreground">
                    Brak pozycji dla wybranych filtrów.
                  </td>
                </tr>
              )}
              {visible.map((line, index) => (
                <Fragment key={line.id}>
                  {line.section && line.section !== visible[index - 1]?.section && (
                    <tr>
                      <td
                        colSpan={12}
                        className="border-b bg-muted/60 px-2 py-1 text-xs font-semibold tracking-wide text-muted-foreground"
                      >
                        {line.section}
                      </td>
                    </tr>
                  )}
                  <Row
                    line={line}
                    pending={link.isPending}
                    onPick={() => setPicking(line)}
                    onUnlink={() => unlink(line)}
                  />
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <input
        ref={fileInput}
        type="file"
        accept=".xlsx,.xlsm"
        className="sr-only"
        tabIndex={-1}
        aria-hidden
        onChange={(event) => {
          const file = event.target.files?.[0]
          // Ten sam plik wybrany ponownie też ma wywołać zmianę.
          event.target.value = ''
          if (file) uploadFile(file)
        }}
      />
      <ProFilesDialog files={files} open={proOpen} onOpenChange={setProOpen} />
      <ExtraMarksDialog
        title={data ? `Gazetka ${data.week}/${data.year}: karteczki Extra Znaczek` : 'Karteczki Extra Znaczek'}
        lines={extras}
        open={extraOpen}
        onOpenChange={setExtraOpen}
      />
      {picking && (
        <CatalogListDialog
          storeId={store.id}
          open
          onOpenChange={(open) => !open && setPicking(null)}
          inTable={NO_ITEMS}
          title={`Artykuł dla EAN-u ${picking.ean}: ${picking.name}`}
          initialQuery={searchFilter(picking.name)}
          onPick={(item) => pick(picking, item)}
          lastSaleDate={canSeeSales ? (salesOverview.data?.lastSaleDate ?? addDays(todayIso(), -1)) : null}
        />
      )}
    </>
  )
}

function Th({ children, filter }: { children: React.ReactNode; filter: React.ReactNode }) {
  return (
    <th className="group/th">
      <span className="flex items-center gap-1">
        {children}
        {filter}
      </span>
    </th>
  )
}

function Row({
  line,
  pending,
  onPick,
  onUnlink,
}: {
  line: LeafletLine
  pending: boolean
  onPick: () => void
  onUnlink: () => void
}) {
  const product = line.product
  const processed = toProcess(line)
  const price = promoPrice(line)
  const format = labelFormat(line)
  return (
    <tr className={cn('align-top [&>td]:border-b [&>td]:px-2 [&>td]:py-1', !processed && 'text-muted-foreground')}>
      <td className="whitespace-nowrap tabular-nums">{formatDocumentDate(line.dateFrom)}</td>
      <td className="whitespace-nowrap tabular-nums">{formatDocumentDate(line.dateTo)}</td>
      <td className="tabular-nums">{line.ean}</td>
      <td className="max-w-72" title={line.promoForm ?? undefined}>
        <span className="line-clamp-2">{line.name}</span>
      </td>
      <td
        className={cn(
          'max-w-80',
          !product && processed && 'bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-50',
        )}
      >
        <span className="flex items-start gap-1">
          <span className="min-w-0 flex-1">
            {product ? (
              <>
                <span className="line-clamp-2 font-medium text-foreground">{product.name}</span>
                <span className="text-xs text-muted-foreground tabular-nums">
                  {product.catalogEan}
                  {line.linkId && (
                    <span className="ml-1 inline-flex items-center gap-0.5 text-blue-700 dark:text-blue-300">
                      <LinkIcon className="size-3" />
                      wybrany ręcznie
                    </span>
                  )}
                </span>
              </>
            ) : (
              <span className="font-medium">brak w kartotece</span>
            )}
          </span>
          <Button
            variant="ghost"
            size="icon-xs"
            disabled={pending}
            onClick={onPick}
            aria-label={`Zmień artykuł dla EAN-u ${line.ean}`}
            title="Zmień artykuł (zapamiętany w sklepie na kolejne gazetki)"
          >
            <RefreshCwIcon />
          </Button>
          {line.linkId && (
            <Button
              variant="ghost"
              size="icon-xs"
              disabled={pending}
              onClick={onUnlink}
              aria-label={`Usuń ręczny wybór artykułu dla EAN-u ${line.ean}`}
              title="Usuń ręczny wybór (artykuł po EAN-ie z gazetki)"
            >
              <Unlink2Icon />
            </Button>
          )}
        </span>
      </td>
      <td>{product?.brand}</td>
      <td className="text-right whitespace-nowrap tabular-nums">
        {isDiscount(line) && (
          <span className="mr-1 rounded bg-violet-200 px-1 text-xs font-semibold text-violet-950 dark:bg-violet-900 dark:text-violet-50">
            −{Math.round(-(line.price ?? 0) * 100)}%
          </span>
        )}
        <span className={cn('font-semibold', processed && 'text-foreground')}>{formatMoney(price)}</span>
      </td>
      <td
        className={cn('text-right whitespace-nowrap tabular-nums', hasStrikePrice(line) && 'line-through')}
        title={hasStrikePrice(line) ? 'Cena z gazetki jest inna: na cenówce cena skreślona' : undefined}
      >
        {formatMoney(line.regularPrice)}
      </td>
      <td className="text-right tabular-nums">{product?.stock == null ? '' : formatQuantity(product.stock)}</td>
      <td className="text-right tabular-nums">{product?.rotation == null ? '' : formatQuantity(product.rotation)}</td>
      <td className="tabular-nums">{line.meaCode}</td>
      <td className="whitespace-nowrap text-xs" title={line.shelfLabel ?? undefined}>
        {format ?? <span className="text-muted-foreground">–</span>}
      </td>
    </tr>
  )
}
