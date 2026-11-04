import {
  ArchiveIcon,
  ArrowLeftIcon,
  CheckIcon,
  ChevronDownIcon,
  DownloadIcon,
  EllipsisIcon,
  EyeIcon,
  EyeOffIcon,
  PrinterIcon,
  ReceiptTextIcon,
  Undo2Icon,
  UserXIcon,
  XIcon,
} from 'lucide-react'
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { toast } from 'sonner'

import { SearchInput } from '@/components/SearchInput'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Switch } from '@/components/ui/switch'
import { bonNumber, formatMoney } from '@/features/documents/format'
import { useKsefOverview } from '@/features/ksef/api'
import { formatDate } from '@/features/ksef/format'
import { ApiError } from '@/lib/api'
import type {
  DataEntryInvoice,
  DataEntryInvoiceItem,
  DataEntrySkippedSupplier,
  InvoiceMarkKind,
  KsefInvoice,
  SupplierRuleMode,
} from '@/lib/types'
import { useDebouncedValue } from '@/lib/use-debounced-value'
import { useLoadMore } from '@/lib/use-load-more'
import { cn } from '@/lib/utils'

import {
  type InvoiceBulkAction,
  type InvoicePickerFilters,
  useBulkInvoices,
  useDataEntryInvoices,
  useInvoiceLines,
  useMarkInvoice,
  useSkippedSuppliers,
  useSkipSupplier,
} from './api'
import { InvoiceFileActions } from './InvoiceFileActions'
import { PRINT_VARIANT_LABELS, useInvoiceFiles } from './invoice-files'

/**
 * Wczytanie faktury w dwóch krokach: wybór faktury albo korekty z KSeF (najnowsze na górze, kolejne przy przewijaniu),
 * potem skąd wziąć EAN pozycji (GTIN, indeks dostawcy, dodatkowy opis, nazwa albo automatycznie) z podglądem.
 * Domyślnie lista pokazuje tylko faktury do wczytania: wczytane, pobrane i oznaczone jako nie moje są w „Wczytane”,
 * a pomijani dostawcy (własna lista, niezależna od Faktur KSeF) znikają. Docelowo „Do wczytania” jest puste.
 */
export function InvoicePickerDialog({
  storeId,
  open,
  onOpenChange,
  onConfirm,
}: {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  onConfirm: (invoice: DataEntryInvoice) => void
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-4 p-6 sm:max-w-4xl">
        {/* Okno odmontowuje treść dopiero po animacji zamknięcia, więc każde otwarcie zaczyna od listy faktur, a przy
            zamykaniu treść nie znika przed oknem (zostawało puste okno). */}
        <Steps storeId={storeId} onConfirm={onConfirm} />
      </DialogContent>
    </Dialog>
  )
}

function Steps({ storeId, onConfirm }: { storeId: string; onConfirm: (invoice: DataEntryInvoice) => void }) {
  const [picked, setPicked] = useState<KsefInvoice | null>(null)
  // Lista zostaje zamontowana pod drugim krokiem: „Wstecz” wraca do tego samego wyszukiwania, zaznaczenia i miejsca.
  return (
    <>
      <div hidden={picked !== null} className={cn(picked === null && 'contents')}>
        <ListStep storeId={storeId} visible={picked === null} onPick={setPicked} />
      </div>
      {picked && <SourceStep storeId={storeId} invoice={picked} onBack={() => setPicked(null)} onConfirm={onConfirm} />}
    </>
  )
}

const FILTERS_KEY = 'intermanage.dataEntryInvoicePicker'
const DEFAULT_FILTERS: InvoicePickerFilters = { kind: 'INVOICES', status: 'NEW', internalId: 'ALL', showSkipped: false }

function storedFilters(): InvoicePickerFilters {
  try {
    const value = JSON.parse(localStorage.getItem(FILTERS_KEY) ?? 'null') as Partial<InvoicePickerFilters> | null
    return {
      kind: value?.kind === 'CORRECTIONS' ? 'CORRECTIONS' : 'INVOICES',
      status: value?.status === 'DONE' || value?.status === 'ALL' ? value.status : 'NEW',
      internalId: value?.internalId === 'OWN' || value?.internalId === 'NONE' ? value.internalId : 'ALL',
      showSkipped: value?.showSkipped === true,
    }
  } catch {
    return DEFAULT_FILTERS
  }
}

type Selection = { scope: string; all: boolean; ids: Set<string>; excluded: Set<string> }

const emptySelection = (scope: string): Selection => ({ scope, all: false, ids: new Set(), excluded: new Set() })

const BULK_LABELS: Record<InvoiceBulkAction, string> = {
  NOT_MINE: 'Odłożono jako nie moje',
  LOADED: 'Oznaczono jako wczytane',
  RESTORE: 'Przywrócono do wczytania',
}

const RULE_BADGES: Record<SupplierRuleMode, { label: string; className: string; title: string }> = {
  HIDDEN: {
    label: 'pomijany dostawca',
    className: 'bg-amber-200 text-amber-950 dark:bg-amber-900 dark:text-amber-50',
    title: 'Faktury tego dostawcy nie pokazują się w oknie',
  },
  DONE: {
    label: 'dostawca odkładany',
    className: 'bg-slate-300 text-slate-950 dark:bg-slate-700 dark:text-slate-50',
    title: 'Faktury tego dostawcy są od razu w „Wczytane”',
  },
}

const STATUS_BADGES: Record<'LOADED' | 'NOT_MINE', { label: string; className: string }> = {
  LOADED: { label: 'wczytana', className: 'bg-violet-200 text-violet-950 dark:bg-violet-900 dark:text-violet-50' },
  NOT_MINE: { label: 'nie moja', className: 'bg-stone-300 text-stone-950 dark:bg-stone-700 dark:text-stone-50' },
}

const COMPLETED_BADGE = 'bg-emerald-300 text-emerald-950 dark:bg-emerald-800 dark:text-emerald-50'

function Badge({ className, children, title }: { className: string; children: React.ReactNode; title?: string }) {
  return (
    <span title={title} className={cn('shrink-0 rounded-md px-1.5 py-0.5 text-[0.7rem] font-medium', className)}>
      {children}
    </span>
  )
}

function Segmented<T extends string>({
  label,
  value,
  options,
  onChange,
}: {
  label: string
  value: T
  options: { value: T; label: string; title?: string }[]
  onChange: (value: T) => void
}) {
  return (
    <div role="group" aria-label={label} className="inline-flex rounded-lg border bg-muted/50 p-0.5">
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          aria-pressed={option.value === value}
          title={option.title}
          onClick={() => onChange(option.value)}
          className={cn(
            'rounded-md px-2.5 py-1 text-sm font-medium text-muted-foreground transition-colors duration-150 outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/30',
            option.value === value && 'bg-background text-foreground shadow-xs',
          )}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}

function ListStep({
  storeId,
  visible,
  onPick: pick,
}: {
  storeId: string
  visible: boolean
  onPick: (invoice: KsefInvoice) => void
}) {
  const [query, setQuery] = useState('')
  const debounced = useDebouncedValue(query.trim(), 300)
  const [filters, setFilters] = useState<InvoicePickerFilters>(storedFilters)
  useEffect(() => {
    try {
      localStorage.setItem(FILTERS_KEY, JSON.stringify(filters))
    } catch {
      // Tryb prywatny: filtry obowiązują do odświeżenia strony.
    }
  }, [filters])
  const set = <K extends keyof InvoicePickerFilters>(key: K, value: InvoicePickerFilters[K]) =>
    setFilters((current) => ({ ...current, [key]: value }))

  const overview = useKsefOverview(storeId)
  const hasInternalIds = (overview.data?.internalIds.length ?? 0) > 0
  // Bez IDWew sklepu filtr nie ma znaczenia: sklep widzi wszystkie faktury firmy.
  const effective = hasInternalIds ? filters : { ...filters, internalId: 'ALL' as const }
  const invoices = useDataEntryInvoices(storeId, effective, debounced)
  const list = invoices.data?.pages.flatMap((page) => page.invoices) ?? []
  const matching = invoices.data?.pages[0]?.matchingCount ?? 0
  // Lista przewija się we własnym obszarze okna: doczytywanie liczy zapas do jego dołu.
  const listRef = useRef<HTMLDivElement>(null)
  // Schowana lista (drugi krok) może zgubić przewinięcie, więc po powrocie wraca do zapamiętanego miejsca.
  const savedScroll = useRef(0)
  const onPick = (invoice: KsefInvoice) => {
    savedScroll.current = listRef.current?.scrollTop ?? 0
    pick(invoice)
  }
  useLayoutEffect(() => {
    if (visible && listRef.current) {
      listRef.current.scrollTop = savedScroll.current
    }
  }, [visible])
  const loadMoreRef = useLoadMore<HTMLLIElement>(
    () => invoices.fetchNextPage(),
    Boolean(invoices.hasNextPage) && !invoices.isFetchingNextPage,
    list.length,
    listRef,
  )
  const rules = useSkippedSuppliers(storeId)
  const rulesByNip = new Map((rules.data ?? []).map((supplier) => [supplier.sellerNip, supplier]))
  const hiddenCount = (rules.data ?? []).filter((rule) => rule.mode === 'HIDDEN').length
  const corrections = filters.kind === 'CORRECTIONS'

  // Zaznaczenie dotyczy bieżących filtrów i wyszukiwania; ich zmiana je czyści.
  const scope = JSON.stringify([effective, debounced])
  const [selection, setSelection] = useState<Selection>(() => emptySelection(scope))
  const current = selection.scope === scope ? selection : emptySelection(scope)
  const isSelected = (id: string) => (current.all ? !current.excluded.has(id) : current.ids.has(id))
  const selectedCount = current.all ? Math.max(matching - current.excluded.size, 0) : current.ids.size
  // Przy „wszystkich wyfiltrowanych” zaznaczony = nie wykluczony, inaczej = na liście zaznaczonych.
  const setSelected = (id: string, value: boolean) =>
    setSelection((previous) => {
      const base = previous.scope === scope ? previous : emptySelection(scope)
      const key = base.all ? 'excluded' : 'ids'
      const inSet = !base.all === value
      if (base[key].has(id) === inSet) return base
      const next = new Set(base[key])
      if (inSet) next.add(id)
      else next.delete(id)
      return { ...base, [key]: next }
    })

  // Przeciągnięcie myszą po polach zaznaczenia: pierwszy wiersz decyduje, czy zaznaczamy, czy odznaczamy, a każdy
  // wiersz pod kursorem dostaje ten sam stan. Przy górnej i dolnej krawędzi listy (także poza nią) lista przewija się
  // sama, tym szybciej, im dalej kursor; doładowane strony też się zaznaczają.
  const stopDrag = useRef<(() => void) | null>(null)
  useEffect(() => () => stopDrag.current?.(), [])
  const startDrag = (id: string, x: number, y: number) => {
    const value = !isSelected(id)
    setSelected(id, value)
    let pointer = { x, y }
    let frame = 0
    let last = id
    const tick = () => {
      const list = listRef.current
      if (list) {
        const rect = list.getBoundingClientRect()
        const edge = 40
        const step =
          pointer.y < rect.top + edge
            ? -Math.min(Math.ceil((rect.top + edge - pointer.y) / 3), 30)
            : pointer.y > rect.bottom - edge
              ? Math.min(Math.ceil((pointer.y - rect.bottom + edge) / 3), 30)
              : 0
        if (step !== 0) list.scrollTop += step
        // Wiersz pod kursorem, a gdy kursor jest poza listą - wiersz przy jej krawędzi.
        const row = document
          .elementFromPoint(
            Math.min(Math.max(pointer.x, rect.left + 1), rect.right - 1),
            Math.min(Math.max(pointer.y, rect.top + 1), rect.bottom - 1),
          )
          ?.closest<HTMLElement>('[data-invoice-id]')
        // Szybki ruch może przeskoczyć wiersze między klatkami: zaznaczamy cały zakres od poprzedniego wiersza.
        const target = row?.dataset.invoiceId
        if (target && target !== last) {
          const ids = [...list.querySelectorAll<HTMLElement>('[data-invoice-id]')].map((item) => item.dataset.invoiceId)
          const from = ids.indexOf(last)
          const to = ids.indexOf(target)
          const range = from < 0 ? [target] : ids.slice(Math.min(from, to), Math.max(from, to) + 1)
          range.forEach((item) => item && setSelected(item, value))
          last = target
        }
      }
      frame = requestAnimationFrame(tick)
    }
    const onMove = (event: PointerEvent) => (pointer = { x: event.clientX, y: event.clientY })
    const stop = () => {
      cancelAnimationFrame(frame)
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', stop)
      window.removeEventListener('pointercancel', stop)
      stopDrag.current = null
    }
    stopDrag.current?.()
    stopDrag.current = stop
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', stop)
    window.addEventListener('pointercancel', stop)
    frame = requestAnimationFrame(tick)
  }
  const toggleAll = () =>
    setSelection(
      current.all && current.excluded.size === 0 ? emptySelection(scope) : { ...emptySelection(scope), all: true },
    )
  const clearSelection = () => setSelection(emptySelection(scope))

  const bulk = useBulkInvoices(storeId)
  const runBulk = (action: InvoiceBulkAction) =>
    bulk.run.mutate(
      {
        action,
        selection: current.all
          ? { filter: { ...effective, query: debounced }, excludeIds: [...current.excluded] }
          : { invoiceIds: [...current.ids] },
      },
      {
        onSuccess: (result) => {
          clearSelection()
          toast(`${BULK_LABELS[action]}: ${result.count}`, {
            action: result.count > 0 ? { label: 'Cofnij', onClick: () => bulk.undo.mutate(result) } : undefined,
          })
        },
        onError: (error) =>
          toast.error(error instanceof ApiError ? error.message : 'Nie udało się zmienić zaznaczonych faktur'),
      },
    )
  const allBox = useRef<HTMLInputElement>(null)
  useEffect(() => {
    if (allBox.current) allBox.current.indeterminate = selectedCount > 0 && selectedCount < matching
  }, [selectedCount, matching])

  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-lg">Wczytaj fakturę z KSeF</DialogTitle>
        <DialogDescription>Wybierz fakturę, a w następnym kroku pole, z którego wziąć EAN pozycji.</DialogDescription>
      </DialogHeader>

      <div className="flex flex-col gap-2.5">
        <SearchInput value={query} onChange={setQuery} placeholder="Szukaj: numer faktury, dostawca, NIP" />
        <div className="flex flex-wrap items-center gap-2">
          <Segmented
            label="Rodzaj"
            value={filters.kind}
            onChange={(value) => set('kind', value)}
            options={[
              { value: 'INVOICES', label: 'Faktury' },
              { value: 'CORRECTIONS', label: 'Korekty' },
            ]}
          />
          <Segmented
            label="Status"
            value={filters.status}
            onChange={(value) => set('status', value)}
            options={[
              { value: 'NEW', label: 'Do wczytania', title: 'Bez statusu w tym sklepie' },
              { value: 'DONE', label: 'Wczytane', title: 'Wczytane i oznaczone jako nie moje (pobrane czekają dalej)' },
              { value: 'ALL', label: 'Wszystkie' },
            ]}
          />
          {hasInternalIds && (
            <Segmented
              label="IDWew"
              value={filters.internalId}
              onChange={(value) => set('internalId', value)}
              options={[
                { value: 'ALL', label: 'Sklep i bez IDWew', title: 'Faktury z IDWew tego sklepu i bez IDWew' },
                { value: 'OWN', label: 'Tylko IDWew sklepu' },
                { value: 'NONE', label: 'Bez IDWew' },
              ]}
            />
          )}
          <div className="ml-auto flex items-center gap-3">
            <SupplierRules storeId={storeId} rules={rules.data ?? []} />
            <label
              className="flex items-center gap-2 text-sm text-muted-foreground"
              title="Także faktury dostawców pomijanych w tym oknie i w Fakturach KSeF"
            >
              <Switch checked={filters.showSkipped} onCheckedChange={(value) => set('showSkipped', value)} />
              Pokaż pomijanych{hiddenCount > 0 && ` (${hiddenCount})`}
            </label>
          </div>
        </div>
      </div>

      {/* Przy pustej liście pasek jest tylko niewidoczny, żeby okno nie skakało przy filtrowaniu. */}
      <div
        aria-hidden={list.length === 0 || undefined}
        className={cn(
          '-mb-2 flex min-h-8 flex-wrap items-center gap-x-3 gap-y-1.5 px-1',
          list.length === 0 && 'invisible',
        )}
      >
        <label className="flex items-center gap-2 text-sm">
          <input
            ref={allBox}
            type="checkbox"
            className="size-4 accent-primary"
            checked={current.all && current.excluded.size === 0}
            onChange={toggleAll}
          />
          Zaznacz wszystkie wyfiltrowane ({matching})
        </label>
        {selectedCount > 0 && (
          <div className="ml-auto flex flex-wrap items-center gap-1.5">
            <span className="mr-1 text-sm text-muted-foreground tabular-nums">Zaznaczone: {selectedCount}</span>
            <Button size="sm" variant="outline" onClick={() => runBulk('NOT_MINE')} loading={bulk.run.isPending}>
              <EyeOffIcon />
              Nie moje
            </Button>
            <Button size="sm" variant="outline" onClick={() => runBulk('LOADED')} loading={bulk.run.isPending}>
              <ArchiveIcon />
              Oznacz jako wczytane
            </Button>
            {filters.status !== 'NEW' && (
              <Button size="sm" variant="outline" onClick={() => runBulk('RESTORE')} loading={bulk.run.isPending}>
                <Undo2Icon />
                Przywróć do wczytania
              </Button>
            )}
            <Button size="icon-sm" variant="ghost" onClick={clearSelection} aria-label="Odznacz wszystkie">
              <XIcon />
            </Button>
          </div>
        )}
      </div>

      {/* W trakcie wczytywania wyników nowych filtrów stara lista jest tylko podglądem: klik trafiłby w inną fakturę. */}
      <div
        ref={listRef}
        inert={invoices.isPlaceholderData}
        aria-busy={invoices.isPlaceholderData}
        className={cn(
          'h-[55svh] min-h-32 overflow-y-auto rounded-xl border transition-opacity duration-150',
          invoices.isPlaceholderData && 'opacity-60',
        )}
      >
        {overview.data && !overview.data.configured ? (
          <p className="px-4 py-6 text-center text-sm text-muted-foreground">
            Sklep nie ma połączenia z KSeF. Połącz go w zakładce Faktury KSeF → Ustawienia.
          </p>
        ) : invoices.isError ? (
          <p className="px-4 py-6 text-center text-sm text-destructive">Nie udało się wczytać faktur.</p>
        ) : invoices.isPending ? (
          <div aria-hidden className="m-3 h-24 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
        ) : list.length === 0 ? (
          <p className="px-4 py-6 text-center text-sm text-muted-foreground">
            {filters.status === 'NEW' && !debounced
              ? `Nie ma ${corrections ? 'korekt' : 'faktur'} do wczytania: wszystkie mają status.`
              : `Nie znaleziono ${corrections ? 'korekty' : 'faktury'}.`}
          </p>
        ) : (
          <ul className="divide-y">
            {list.map((item) => (
              <InvoiceRow
                key={item.invoice.id}
                storeId={storeId}
                item={item}
                rule={rulesByNip.get(item.invoice.sellerNip) ?? null}
                selected={isSelected(item.invoice.id)}
                onToggle={() => setSelected(item.invoice.id, !isSelected(item.invoice.id))}
                onDragStart={(x, y) => startDrag(item.invoice.id, x, y)}
                onPick={onPick}
              />
            ))}
            <li ref={loadMoreRef} aria-hidden className="h-px" />
          </ul>
        )}
      </div>
      {list.length > 0 && (
        <p className="-mt-2 text-xs text-muted-foreground tabular-nums">
          {corrections ? 'Korekty' : 'Faktury'}: {matching}
          {invoices.hasNextPage && ' · kolejne wczytują się przy przewijaniu'}
        </p>
      )}
    </>
  )
}

/** Wiersz faktury: wybór (klik), status, informacja o innych sklepach firmy i menu działań. */
function InvoiceRow({
  storeId,
  item,
  rule,
  selected,
  onToggle,
  onDragStart,
  onPick,
}: {
  storeId: string
  item: DataEntryInvoiceItem
  rule: DataEntrySkippedSupplier | null
  selected: boolean
  onToggle: () => void
  /** Wciśnięcie myszy na polu zaznaczenia: początek przeciągania (pozycja kursora). */
  onDragStart: (x: number, y: number) => void
  onPick: (invoice: KsefInvoice) => void
}) {
  const { invoice } = item
  const files = useInvoiceFiles(storeId)
  const mark = useMarkInvoice(storeId)
  const skip = useSkipSupplier(storeId)
  const supplier = invoice.sellerName ?? invoice.sellerNip
  // Dokument z raportu w sklepie firmy (przyjęcie z jej numerem albo przypięty): faktura jest wczytana z automatu.
  const documents = invoice.deliveries
  const documentTitle = documents
    .map((document) => `${document.typeSingular} ${bonNumber(document.number)} (sklep ${document.store})`)
    .join(', ')
  const failed = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać zmiany')

  const setMark = (kind: InvoiceMarkKind | null, message: string, undo: InvoiceMarkKind | null) =>
    mark.mutate(
      { invoiceId: invoice.id, kind },
      {
        onSuccess: () =>
          toast(message, {
            action: { label: 'Cofnij', onClick: () => mark.mutate({ invoiceId: invoice.id, kind: undo }) },
          }),
        onError: failed,
      },
    )

  // Nowa reguła zastępuje poprzednią tego dostawcy; „Cofnij” zdejmuje nową i przywraca poprzednią.
  const setRule = (mode: SupplierRuleMode) =>
    skip.mutate(
      { type: 'skip', sellerNip: invoice.sellerNip, sellerName: invoice.sellerName, mode },
      {
        onSuccess: (added) =>
          toast(mode === 'HIDDEN' ? `Pomijasz dostawcę ${supplier}` : `Odkładasz faktury dostawcy ${supplier}`, {
            description:
              mode === 'HIDDEN'
                ? 'Jego faktury nie pokażą się w tym oknie (Faktury KSeF bez zmian).'
                : 'Jego faktury będą od razu w „Wczytane”; potrzebną wczytasz stamtąd.',
            action: added
              ? {
                  label: 'Cofnij',
                  onClick: () =>
                    skip.mutate(
                      { type: 'unskip', id: added.id },
                      { onSuccess: () => rule && skip.mutate({ type: 'restore', id: rule.id }) },
                    ),
                }
              : undefined,
          }),
        onError: failed,
      },
    )

  const removeRule = (id: string) =>
    skip.mutate(
      { type: 'unskip', id },
      {
        onSuccess: () =>
          toast(`Dostawca ${supplier} wraca do zwykłej listy`, {
            action: { label: 'Przywróć', onClick: () => skip.mutate({ type: 'restore', id }) },
          }),
        onError: failed,
      },
    )

  return (
    <li
      data-invoice-id={invoice.id}
      className={cn('flex items-center gap-1 pr-2', selected && 'bg-blue-100 dark:bg-blue-950/60')}
    >
      {/* Mysz zaznacza na wciśnięciu (i dalej przeciąganiem), klawiatura spacją przez onChange. */}
      <label
        className="flex cursor-pointer touch-none items-center self-stretch pr-1 pl-3 select-none"
        onPointerDown={(event) => {
          if (event.button !== 0) return
          event.preventDefault()
          onDragStart(event.clientX, event.clientY)
        }}
        // Klik myszą już zaznaczył na wciśnięciu: etykieta nie przekazuje go do pola (spacja na polu działa dalej).
        onClick={(event) => event.target === event.currentTarget && event.preventDefault()}
      >
        <input
          type="checkbox"
          className="pointer-events-none size-4 accent-primary"
          checked={selected}
          onChange={onToggle}
          aria-label={`Zaznacz ${invoice.invoiceNumber}`}
        />
      </label>
      <button
        type="button"
        onClick={() => onPick(invoice)}
        className="flex min-w-0 flex-1 items-center gap-4 px-3 py-3 text-left tabular-nums transition-colors duration-150 outline-none hover:bg-muted focus-visible:bg-muted"
      >
        <span className="min-w-0 flex-1">
          <span className="flex flex-wrap items-center gap-1.5 text-sm font-medium">
            <span className="truncate">{invoice.invoiceNumber}</span>
            {/* Dokument w systemie sklepu zamyka sprawę: „zakończona” zastępuje „wczytana”, żeby nie wprowadzić jej drugi raz. */}
            {item.status && (documents.length === 0 || item.status === 'NOT_MINE') && (
              <Badge className={STATUS_BADGES[item.status].className}>{STATUS_BADGES[item.status].label}</Badge>
            )}
            {documents.length > 0 && (
              <Badge className={COMPLETED_BADGE} title={`Faktura jest już w systemie sklepu: ${documentTitle}`}>
                zakończona · {documents[0].type === 'PZ' ? 'w PZ' : documents[0].typeSingular.toLowerCase()}{' '}
                {bonNumber(documents[0].number)}
                {documents.length > 1 && ` +${documents.length - 1}`}
              </Badge>
            )}
            {item.downloadedAt && (
              <Badge
                className="bg-emerald-200 text-emerald-950 dark:bg-emerald-900 dark:text-emerald-50"
                title={`Pobrana albo wydrukowana ${formatDate(item.downloadedAt.slice(0, 10))}; dalej czeka na wczytanie`}
              >
                pobrana
              </Badge>
            )}
            {item.otherStores.map((other) => (
              <Badge
                key={`${other.storeId}:${other.kind}`}
                className="bg-sky-200 text-sky-950 dark:bg-sky-900 dark:text-sky-50"
                title={`${other.kind === 'DOWNLOADED' ? 'Pobrana' : 'Wczytana'} ${formatDate(other.at.slice(0, 10))} w sklepie ${other.store}`}
              >
                {other.kind === 'DOWNLOADED' ? 'pobrana' : 'wczytana'} na innym stanowisku: {other.store}
              </Badge>
            ))}
            {invoice.supplierSkipped && (
              <Badge className={RULE_BADGES.HIDDEN.className} title="Dostawca pominięty w Fakturach KSeF">
                pomijany w KSeF
              </Badge>
            )}
            {item.supplierRule && (
              <Badge className={RULE_BADGES[item.supplierRule].className} title={RULE_BADGES[item.supplierRule].title}>
                {RULE_BADGES[item.supplierRule].label}
              </Badge>
            )}
          </span>
          <span className="block truncate text-xs text-muted-foreground">
            {formatDate(invoice.issueDate)} · {supplier}
            {invoice.internalIds.length > 0 ? (
              <>
                {' · IDWew '}
                <span className="font-medium text-foreground">{invoice.internalIds.join(', ')}</span>
              </>
            ) : (
              ' · bez IDWew'
            )}
          </span>
        </span>
        <span className="text-right text-sm font-medium">{formatMoney(invoice.netAmount)} zł</span>
      </button>
      {/* Druk najczęściej potrzebny przy wprowadzaniu: osobny przycisk, wariant jak w oknie (domyślnie podstawowy). */}
      <Button
        variant="ghost"
        size="icon-sm"
        onClick={() => files.print(invoice.id)}
        loading={files.busy === 'print'}
        aria-label={`Drukuj fakturę ${invoice.invoiceNumber}`}
        title={`Drukuj fakturę (${PRINT_VARIANT_LABELS[files.variant]})`}
      >
        <PrinterIcon />
      </Button>
      <DropdownMenu>
        <DropdownMenuTrigger
          render={
            <Button
              variant="ghost"
              size="icon-sm"
              aria-label={`Działania: ${invoice.invoiceNumber}`}
              loading={files.busy === 'download' || mark.isPending || skip.isPending}
            />
          }
        >
          <EllipsisIcon />
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-64">
          <DropdownMenuItem onClick={() => files.download(invoice.id)}>
            <DownloadIcon />
            Pobierz PDF ({PRINT_VARIANT_LABELS[files.variant]})
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          {item.status ? (
            <DropdownMenuItem
              onClick={() => setMark(null, `Faktura ${invoice.invoiceNumber} wróciła do wczytania`, item.status)}
            >
              <Undo2Icon />
              Przywróć do wczytania
            </DropdownMenuItem>
          ) : (
            <DropdownMenuItem
              onClick={() => setMark('NOT_MINE', `Odłożono ${invoice.invoiceNumber} jako nie moją fakturę`, null)}
            >
              <EyeOffIcon />
              Nie moja faktura
            </DropdownMenuItem>
          )}
          <DropdownMenuSeparator />
          {rule?.mode !== 'DONE' && (
            <DropdownMenuItem onClick={() => setRule('DONE')}>
              <ArchiveIcon />
              Odkładaj faktury dostawcy do wczytanych
            </DropdownMenuItem>
          )}
          {rule?.mode !== 'HIDDEN' && (
            <DropdownMenuItem onClick={() => setRule('HIDDEN')}>
              <UserXIcon />
              Pomijaj dostawcę
            </DropdownMenuItem>
          )}
          {rule && (
            <DropdownMenuItem onClick={() => removeRule(rule.id)}>
              <EyeIcon />
              {rule.mode === 'HIDDEN' ? 'Przestań pomijać dostawcę' : 'Przestań odkładać faktury dostawcy'}
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
    </li>
  )
}

/** Lista dostawców z regułami: odkładani do wczytanych i pomijani; klik zdejmuje regułę („Przywróć” w toaście). */
function SupplierRules({ storeId, rules }: { storeId: string; rules: DataEntrySkippedSupplier[] }) {
  const skip = useSkipSupplier(storeId)
  const remove = (rule: DataEntrySkippedSupplier) =>
    skip.mutate(
      { type: 'unskip', id: rule.id },
      {
        onSuccess: () =>
          toast(`${rule.sellerName ?? rule.sellerNip} wraca do zwykłej listy`, {
            action: { label: 'Przywróć', onClick: () => skip.mutate({ type: 'restore', id: rule.id }) },
          }),
        onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać zmiany'),
      },
    )
  const groups: { mode: SupplierRuleMode; title: string }[] = [
    { mode: 'DONE', title: 'Odkładani do wczytanych' },
    { mode: 'HIDDEN', title: 'Pomijani' },
  ]
  return (
    <DropdownMenu>
      <DropdownMenuTrigger render={<Button variant="outline" size="sm" loading={skip.isPending} />}>
        <ReceiptTextIcon />
        Dostawcy{rules.length > 0 && ` (${rules.length})`}
        <ChevronDownIcon />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="max-h-80 w-80">
        {rules.length === 0 ? (
          <p className="px-2 py-3 text-sm text-muted-foreground">
            Brak reguł. Dostawcę odłożysz albo pominiesz z menu ⋯ przy fakturze.
          </p>
        ) : (
          groups
            .filter((group) => rules.some((rule) => rule.mode === group.mode))
            .map((group, index) => (
              <DropdownMenuGroup key={group.mode}>
                {index > 0 && <DropdownMenuSeparator />}
                <DropdownMenuLabel>{group.title}</DropdownMenuLabel>
                {rules
                  .filter((rule) => rule.mode === group.mode)
                  .map((rule) => (
                    <DropdownMenuItem key={rule.id} onClick={() => remove(rule)} title="Kliknij, żeby zdjąć regułę">
                      <span className="min-w-0 flex-1 truncate">{rule.sellerName ?? rule.sellerNip}</span>
                      <XIcon className="text-muted-foreground" />
                    </DropdownMenuItem>
                  ))}
              </DropdownMenuGroup>
            ))
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

const positions = (count: number) =>
  count === 1
    ? 'pozycja'
    : count % 10 >= 2 && count % 10 <= 4 && (count % 100 < 12 || count % 100 > 14)
      ? 'pozycje'
      : 'pozycji'

function SourceStep({
  storeId,
  invoice,
  onBack,
  onConfirm,
}: {
  storeId: string
  invoice: KsefInvoice
  onBack: () => void
  onConfirm: (invoice: DataEntryInvoice) => void
}) {
  const [source, setSource] = useState('AUTO')
  const lines = useInvoiceLines(storeId, invoice.id, source)
  const data = lines.data
  const total = data?.lines.length ?? 0
  const sourceLabels = new Map(data?.sources.map((item) => [item.key, item.label]))

  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-lg">Faktura {invoice.invoiceNumber}</DialogTitle>
        <DialogDescription>
          {invoice.sellerName ?? invoice.sellerNip} · {formatDate(invoice.issueDate)}. Skąd wziąć EAN, po którym szukamy
          artykułu w kartotece?
        </DialogDescription>
      </DialogHeader>

      {lines.isError ? (
        <p className="rounded-xl border px-4 py-6 text-center text-sm text-destructive">
          {lines.error instanceof ApiError ? lines.error.message : 'Nie udało się wczytać faktury.'}
        </p>
      ) : !data ? (
        <div aria-hidden className="h-48 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
      ) : (
        <>
          <div role="radiogroup" aria-label="Źródło EAN" className="grid gap-2 sm:grid-cols-2">
            {data.sources.map((item) => {
              const checked = item.key === source
              return (
                <button
                  key={item.key}
                  type="button"
                  role="radio"
                  aria-checked={checked}
                  onClick={() => setSource(item.key)}
                  className={cn(
                    'flex items-start gap-3 rounded-xl border px-3.5 py-2.5 text-left transition-colors duration-150 outline-none hover:bg-muted/60 focus-visible:ring-3 focus-visible:ring-ring/30',
                    checked && 'border-foreground bg-muted/60',
                  )}
                >
                  <span
                    aria-hidden
                    className={cn(
                      'mt-0.5 flex size-4 shrink-0 items-center justify-center rounded-full border',
                      checked && 'border-foreground bg-foreground text-background',
                    )}
                  >
                    {checked && <CheckIcon className="size-3" />}
                  </span>
                  <span className="min-w-0">
                    <span className="block text-sm font-medium">
                      {item.label}
                      {item.key === 'AUTO' && <span className="font-normal text-muted-foreground"> (zalecane)</span>}
                    </span>
                    <span className="block text-xs text-muted-foreground tabular-nums">
                      {item.key === 'AUTO'
                        ? `Pierwszy kod znany w kartotece z dowolnego pola · ${item.matched} z ${total} w kartotece`
                        : `Kod ma ${item.filled} z ${total} ${positions(total)} · ${item.matched} w kartotece`}
                    </span>
                  </span>
                </button>
              )
            })}
          </div>

          <div
            className={cn(
              'max-h-[38svh] overflow-y-auto rounded-xl border transition-opacity duration-150',
              lines.isPlaceholderData && 'opacity-60',
            )}
          >
            <table className="w-full text-sm tabular-nums">
              <thead className="sticky top-0 bg-muted text-xs text-muted-foreground">
                <tr>
                  <th className="px-3 py-2 text-left font-medium">Pozycja z faktury</th>
                  <th className="px-3 py-2 text-left font-medium">EAN</th>
                  <th className="px-3 py-2 text-left font-medium">Kartoteka</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {data.lines.map((line, index) => (
                  <tr key={index}>
                    <td className="max-w-80 truncate px-3 py-1.5" title={line.invoiceName ?? undefined}>
                      {line.invoiceName}
                    </td>
                    <td className="px-3 py-1.5 whitespace-nowrap">
                      {line.ean ??
                        (line.fallbackCode ? (
                          <span
                            className="text-muted-foreground italic"
                            title={`Nie EAN, kod z faktury: ${line.fallbackSource ?? ''}`}
                          >
                            {line.fallbackCode}
                          </span>
                        ) : (
                          <span className="text-muted-foreground">brak</span>
                        ))}
                      {line.ean && data.source === 'AUTO' && line.eanSource && line.eanSource !== 'GTIN' && (
                        <span className="ml-1.5 text-xs text-muted-foreground">
                          z: {sourceLabels.get(line.eanSource) ?? line.eanSource}
                        </span>
                      )}
                    </td>
                    <td className="max-w-64 truncate px-3 py-1.5 text-muted-foreground">
                      {line.product ? (
                        <span className="text-foreground">{line.product.name}</span>
                      ) : line.ean || line.fallbackCode ? (
                        'nowy artykuł'
                      ) : (
                        '—'
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      <DialogFooter className="sm:justify-between">
        <InvoiceFileActions storeId={storeId} invoiceId={invoice.id} />
        <div className="flex flex-col-reverse gap-2 sm:flex-row">
          <Button variant="ghost" onClick={onBack}>
            <ArrowLeftIcon />
            Inna faktura
          </Button>
          <Button onClick={() => data && onConfirm(data)} disabled={!data || lines.isPlaceholderData}>
            Wczytaj do tabeli
          </Button>
        </div>
      </DialogFooter>
    </>
  )
}
