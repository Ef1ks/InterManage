import { useId, useState } from 'react'
import {
  CircleAlertIcon,
  CircleCheckIcon,
  EllipsisIcon,
  EyeOffIcon,
  FileCodeIcon,
  FileTextIcon,
  LoaderCircleIcon,
  RefreshCwIcon,
  UndoIcon,
} from 'lucide-react'
import { Link } from 'react-router'
import { toast } from 'sonner'

import { DateField, SegmentedField, SelectField } from '@/components/form'
import { SearchInput } from '@/components/SearchInput'
import { SortableHead } from '@/components/SortableHead'
import { TableSkeletonRows } from '@/components/TableSkeleton'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Switch } from '@/components/ui/switch'
import { Table, TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCan } from '@/features/auth/api'
import { countFormat, formatMoney } from '@/features/documents/format'
import {
  type EnteredFilter,
  type KindFilter,
  type KsefColumn,
  type KsefFilters,
  useDownloadInvoice,
  useKsefInvoices,
  useKsefOverview,
  useKsefSettingsMutations,
  useKsefSync,
  useMarkEntered,
  useSyncFollow,
} from '@/features/ksef/api'
import { formatDate, formatDateTime, formatNip, invoicesWord, invoiceTypeLabel, ksefPaths } from '@/features/ksef/format'
import { ApiError } from '@/lib/api'
import { addDays, todayIso } from '@/lib/dates'
import { nextSort, type SortState } from '@/lib/table-sort'
import type { KsefInvoice, KsefOverview } from '@/lib/types'
import { useDebouncedValue } from '@/lib/use-debounced-value'
import { useLoadMore } from '@/lib/use-load-more'
import { cn } from '@/lib/utils'

const ENTERED_OPTIONS: { value: EnteredFilter; label: string }[] = [
  { value: 'ALL', label: 'Wszystkie' },
  { value: 'NOT_ENTERED', label: 'Do wprowadzenia' },
  { value: 'ENTERED', label: 'Wprowadzone' },
]

const KIND_OPTIONS: { value: KindFilter; label: string }[] = [
  { value: 'ALL', label: 'Wszystkie' },
  { value: 'INVOICES', label: 'Faktury' },
  { value: 'CORRECTIONS', label: 'Korekty' },
]

const EMPTY_FILTERS: Omit<KsefFilters, 'query'> = {
  from: '',
  to: '',
  entered: 'ALL',
  kind: 'ALL',
  internalId: 'ALL',
  showSkipped: false,
}

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

/**
 * Faktury zakupowe z KSeF: sklep bez IDWew widzi wszystkie faktury firmy, sklep z IDWew - ze swoimi IDWew i bez IDWew
 * (albo, po wybraniu, tylko ze swoimi). Filtry (daty wystawienia,
 * status, rodzaj, IDWew, pominięci dostawcy), sortowanie i suma liczą się w bazie; kolejne strony po 100 dochodzą
 * przy przewijaniu. "Wprowadzona" = numer faktury jest w przyjęciu PZ albo ktoś ją oznaczył ręcznie.
 */
export function InvoicesList({ storeId }: { storeId: string }) {
  const { data: overview, isPending: overviewPending } = useKsefOverview(storeId)
  const canEdit = useCan('KSEF_INVOICES', 'EDIT')

  if (overviewPending) {
    return <div aria-hidden className="h-64 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
  }
  if (!overview?.configured) {
    return (
      <Card className="items-center px-6 py-10 text-center">
        <p className="font-medium">Sklep nie jest jeszcze połączony z KSeF</p>
        <p className="max-w-md text-sm text-muted-foreground">
          {canEdit
            ? 'Wgraj certyfikat KSeF i podaj NIP firmy w ustawieniach - faktury zakupowe pobiorą się same.'
            : 'Połączenie ustawia osoba z uprawnieniem do edycji faktur KSeF w tym sklepie.'}
        </p>
        {canEdit && <Button render={<Link to={ksefPaths.settings} />}>Przejdź do ustawień</Button>}
      </Card>
    )
  }
  return <Invoices storeId={storeId} overview={overview} canEdit={canEdit} />
}

function Invoices({ storeId, overview, canEdit }: { storeId: string; overview: KsefOverview; canEdit: boolean }) {
  const [query, setQuery] = useState('')
  const debouncedQuery = useDebouncedValue(query.trim(), 300)
  const [filters, setFilters] = useState(EMPTY_FILTERS)
  const [sort, setSort] = useState<SortState<KsefColumn>>({ key: 'issueDate', direction: 'desc' })
  const { data, isPending, isError, refetch, fetchNextPage, hasNextPage, isFetchingNextPage, isPlaceholderData } =
    useKsefInvoices(storeId, { ...filters, query: debouncedQuery }, sort)
  const loadMoreRef = useLoadMore<HTMLDivElement>(
    () => fetchNextPage(),
    Boolean(hasNextPage) && !isFetchingNextPage,
    data?.pages.length ?? 0,
  )
  const switchId = useId()

  const set = <K extends keyof typeof filters>(key: K, value: (typeof filters)[K]) =>
    setFilters((current) => ({ ...current, [key]: value }))
  const filtered = debouncedQuery !== '' || JSON.stringify(filters) !== JSON.stringify(EMPTY_FILTERS)
  const first = data?.pages[0]
  const invoices = data?.pages.flatMap((page) => page.invoices) ?? []
  const toggleSort = (key: KsefColumn) => setSort(nextSort(sort, key))
  const today = todayIso()

  const internalIdOptions = [
    { value: 'ALL', label: 'Z IDWew sklepu i bez IDWew' },
    { value: 'OWN', label: 'Tylko z IDWew sklepu' },
    // Przy kilku IDWew sklepu można zawęzić do jednego.
    ...(overview.internalIds.length > 1
      ? overview.internalIds.map((item) => ({
          value: item.internalId,
          label: `Tylko ${item.name ? `${item.name} (${item.internalId})` : item.internalId}`,
        }))
      : []),
    { value: 'NONE', label: 'Tylko bez IDWew' },
  ]

  return (
    <section aria-labelledby="ksef-invoices-heading">
      <div className="mb-4 flex flex-wrap items-end justify-between gap-x-4 gap-y-3">
        <div>
          <h2 id="ksef-invoices-heading" className="text-xl font-semibold tracking-tight">
            Faktury zakupowe
          </h2>
          <SyncStatus storeId={storeId} overview={overview} />
        </div>
        {canEdit && <SyncButton storeId={storeId} running={overview.syncRunning} />}
      </div>

      <Card className="mb-4 gap-3 p-4">
        <div className="grid gap-3 md:grid-cols-[minmax(0,1fr)_12rem_12rem]">
          <SearchInput value={query} onChange={setQuery} placeholder="Szukaj: nr faktury, dostawca, NIP, nr KSeF" />
          <DateField
            label="Wystawiona od"
            value={filters.from}
            onChange={(value) => set('from', value)}
            shortcuts={[
              { label: 'Dziś', value: today },
              { label: '7 dni', value: addDays(today, -7) },
              { label: 'Ten miesiąc', value: `${today.slice(0, 8)}01` },
            ]}
          />
          <DateField
            label="Wystawiona do"
            value={filters.to}
            onChange={(value) => set('to', value)}
            min={filters.from || undefined}
            shortcuts={[{ label: 'Dziś', value: today }]}
          />
        </div>
        <div className="flex flex-wrap items-center gap-x-4 gap-y-3">
          <SegmentedField
            label="Status"
            options={ENTERED_OPTIONS}
            value={filters.entered}
            onChange={(value) => set('entered', value)}
          />
          <SegmentedField label="Rodzaj" options={KIND_OPTIONS} value={filters.kind} onChange={(value) => set('kind', value)} />
          {overview.internalIds.length > 0 && (
            <SelectField
              label="IDWew"
              className="w-72"
              options={internalIdOptions}
              value={filters.internalId}
              onChange={(value) => set('internalId', value)}
            />
          )}
          <label htmlFor={switchId} className="flex cursor-pointer items-center gap-2 text-sm text-muted-foreground">
            <Switch id={switchId} checked={filters.showSkipped} onCheckedChange={(value) => set('showSkipped', value)} />
            Pokaż pominiętych dostawców
          </label>
          {filtered && (
            <Button
              variant="link"
              className="h-auto p-0 text-sm"
              onClick={() => {
                setQuery('')
                setFilters(EMPTY_FILTERS)
              }}
            >
              Wyczyść filtry
            </Button>
          )}
        </div>
      </Card>

      {isError && !data ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">Nie udało się wczytać faktur</p>
          <Button variant="outline" onClick={() => refetch()}>
            Spróbuj ponownie
          </Button>
        </Card>
      ) : (
        <Card className="py-0">
          {/*
            Stałe szerokości dla dat, kwot, statusu i akcji; numer i dostawca dzielą resztę i zawijają się albo
            przycinają, więc tabela mieści się w karcie. Przewija się dopiero na wąskim ekranie (poniżej 48rem).
          */}
          <Table
            className={cn(
              'min-w-[48rem] table-fixed transition-opacity duration-150',
              isPlaceholderData && 'opacity-60',
            )}
          >
            <colgroup>
              <col />
              <col className="w-24" />
              <col />
              <col className="w-28" />
              <col className="w-28" />
              <col className="w-36" />
              <col className="w-12" />
            </colgroup>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <SortableHead column="number" sort={sort} onSort={toggleSort} className="pl-4">
                  Nr faktury
                </SortableHead>
                <SortableHead column="issueDate" sort={sort} onSort={toggleSort}>
                  Wystawiona
                </SortableHead>
                <SortableHead column="seller" sort={sort} onSort={toggleSort}>
                  Dostawca
                </SortableHead>
                <SortableHead column="net" sort={sort} onSort={toggleSort} align="right">
                  Netto
                </SortableHead>
                <SortableHead column="gross" sort={sort} onSort={toggleSort} align="right">
                  Brutto
                </SortableHead>
                <TableHead>Status</TableHead>
                <TableHead className="w-10 pr-4">
                  <span className="sr-only">Akcje</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {isPending ? (
                <TableSkeletonRows columns={7} />
              ) : invoices.length === 0 ? (
                <TableRow className="hover:bg-transparent">
                  <TableCell colSpan={7} className="py-10 text-center text-sm text-muted-foreground">
                    {overview.invoiceCount === 0
                      ? 'Nie ma jeszcze pobranych faktur. Pobieranie rusza samo co pół godziny.'
                      : 'Żadna faktura nie pasuje do filtrów.'}
                  </TableCell>
                </TableRow>
              ) : (
                invoices.map((invoice) => (
                  <InvoiceRow key={invoice.id} storeId={storeId} invoice={invoice} canEdit={canEdit} />
                ))
              )}
            </TableBody>
            {first && first.matchingCount > 0 && (
              <TableFooter>
                <TableRow className="hover:bg-transparent">
                  <TableCell colSpan={3} className="pl-4 font-medium">
                    Razem{' '}
                    <span className="font-normal text-muted-foreground tabular-nums">
                      ({countFormat.format(first.matchingCount)} {invoicesWord(first.matchingCount)})
                    </span>
                  </TableCell>
                  <TableCell className="text-right font-medium tabular-nums">{formatMoney(first.netTotal)}</TableCell>
                  <TableCell className="text-right font-medium tabular-nums">{formatMoney(first.grossTotal)}</TableCell>
                  <TableCell colSpan={2} />
                </TableRow>
              </TableFooter>
            )}
          </Table>
        </Card>
      )}

      <div ref={loadMoreRef} aria-live="polite" className="flex min-h-12 items-center justify-center text-sm text-muted-foreground">
        {isFetchingNextPage && (
          <span className="inline-flex items-center gap-2">
            <LoaderCircleIcon aria-hidden className="size-4 animate-spin motion-reduce:animate-none" />
            Wczytuję kolejne faktury…
          </span>
        )}
      </div>
    </section>
  )
}

/**
 * "Pobrano z KSeF 25.10.2026, 14:05 · Brak nowych faktur", błąd ostatniego pobierania albo postęp trwającego
 * (pobieranie czeka na limity KSeF, więc przy wielu fakturach trwa minuty).
 */
function SyncStatus({ storeId, overview }: { storeId: string; overview: KsefOverview }) {
  useSyncFollow(storeId, overview)
  if (overview.syncRunning) {
    return (
      <p aria-live="polite" className="mt-0.5 flex items-center gap-1.5 text-sm text-muted-foreground tabular-nums">
        <LoaderCircleIcon aria-hidden className="size-4 shrink-0 animate-spin motion-reduce:animate-none" />
        Pobieram faktury z KSeF
        {overview.syncNewInvoices > 0 && ` · nowych: ${countFormat.format(overview.syncNewInvoices)}`}
      </p>
    )
  }
  if (!overview.syncedAt) {
    return <p className="mt-0.5 text-sm text-muted-foreground">Faktury jeszcze nie były pobierane.</p>
  }
  return (
    <p
      className={cn(
        'mt-0.5 flex items-center gap-1.5 text-sm tabular-nums',
        overview.syncOk ? 'text-muted-foreground' : 'text-destructive',
      )}
    >
      {!overview.syncOk && <CircleAlertIcon aria-hidden className="size-4 shrink-0" />}
      Pobrano z KSeF {formatDateTime(overview.syncedAt)}
      {overview.syncMessage && ` · ${overview.syncMessage}`}
    </p>
  )
}

function SyncButton({ storeId, running }: { storeId: string; running: boolean }) {
  const sync = useKsefSync(storeId)
  return (
    <Button
      variant="outline"
      loading={sync.isPending || running}
      onClick={() =>
        sync.mutate(undefined, {
          onError: (error) => toast.error(errorMessage(error, 'Nie udało się zacząć pobierania z KSeF')),
        })
      }
    >
      <RefreshCwIcon />
      {running ? 'Pobieram…' : 'Pobierz z KSeF'}
    </Button>
  )
}

function InvoiceRow({ storeId, invoice, canEdit }: { storeId: string; invoice: KsefInvoice; canEdit: boolean }) {
  const markEntered = useMarkEntered(storeId)
  const download = useDownloadInvoice(storeId)
  const downloadAs = (format: 'pdf' | 'xml') =>
    download.mutate(
      { invoice, format },
      { onError: (error) => toast.error(errorMessage(error, 'Nie udało się pobrać faktury z KSeF')) },
    )
  const settings = useKsefSettingsMutations(storeId)
  const foreign = invoice.currency !== 'PLN'
  const manual = invoice.enteredAt !== null

  const setEntered = (entered: boolean) =>
    markEntered.mutate(
      { id: invoice.id, entered },
      {
        onSuccess: () =>
          toast.success(entered ? `Oznaczono ${invoice.invoiceNumber} jako wprowadzoną` : 'Cofnięto oznaczenie', {
            action: { label: 'Cofnij', onClick: () => markEntered.mutate({ id: invoice.id, entered: !entered }) },
          }),
        onError: (error) => toast.error(errorMessage(error, 'Nie udało się zapisać')),
      },
    )

  const skipSupplier = () =>
    settings.addSkippedSupplier.mutate(
      { nip: invoice.sellerNip, name: invoice.sellerName ?? '' },
      {
        onSuccess: (result) => {
          const added = result.skippedSuppliers.find((supplier) => supplier.nip === invoice.sellerNip)
          toast.success(`Faktury ${invoice.sellerName ?? formatNip(invoice.sellerNip)} będą pomijane`, {
            description: 'Zobaczysz je po włączeniu „Pokaż pominiętych dostawców”.',
            action: added
              ? { label: 'Cofnij', onClick: () => settings.removeSkippedSupplier.mutate(added.id) }
              : undefined,
          })
        },
        onError: (error) => toast.error(errorMessage(error, 'Nie udało się pominąć dostawcy')),
      },
    )

  return (
    <TableRow className={cn('tabular-nums', invoice.supplierSkipped && 'text-muted-foreground')}>
      <TableCell className="py-3 pl-4">
        <div className="flex items-center gap-2 font-medium">
          {invoice.invoiceNumber}
          {invoice.invoiceType !== 'Vat' && (
            <span className="rounded-md bg-muted px-1.5 py-0.5 text-[0.7rem] font-medium text-muted-foreground">
              {invoiceTypeLabel(invoice.invoiceType)}
            </span>
          )}
        </div>
        {/* Numer KSeF i IDWew to dane techniczne: mniejszy krój o stałej szerokości i plakietka, żeby nie zlewały się
            z dostawcą i kwotami w sąsiednich kolumnach. */}
        <div className="mt-1 flex flex-wrap items-center gap-1.5">
          <span className="font-mono text-[0.68rem] tracking-tight text-muted-foreground/75" title="Numer KSeF">
            {invoice.ksefNumber}
          </span>
          {invoice.internalIds.map((internalId) => (
            <span
              key={internalId}
              title={`IDWew ${internalId}`}
              className="rounded-md bg-sky-50 px-1.5 py-px text-[0.68rem] font-medium text-sky-800 ring-1 ring-sky-200 dark:bg-sky-950/40 dark:text-sky-200 dark:ring-sky-800"
            >
              IDWew {internalId.slice(internalId.indexOf('-') + 1)}
            </span>
          ))}
        </div>
      </TableCell>
      <TableCell>{formatDate(invoice.issueDate)}</TableCell>
      <TableCell className="max-w-72">
        <div className="truncate" title={invoice.sellerName ?? undefined}>
          {invoice.sellerName ?? 'Bez nazwy'}
        </div>
        <div className="text-xs text-muted-foreground">
          NIP {formatNip(invoice.sellerNip)}
          {invoice.supplierSkipped && ' · pominięty'}
        </div>
      </TableCell>
      <TableCell className="text-right">
        {formatMoney(invoice.netAmount)}
        {foreign && <span className="ml-1 text-xs text-muted-foreground">{invoice.currency}</span>}
      </TableCell>
      <TableCell className="text-right font-medium">
        {formatMoney(invoice.grossAmount)}
        {foreign && <span className="ml-1 text-xs font-normal text-muted-foreground">{invoice.currency}</span>}
      </TableCell>
      <TableCell>
        <EnteredStatus invoice={invoice} />
      </TableCell>
      <TableCell className="pr-4 text-right">
        <DropdownMenu>
          <DropdownMenuTrigger
            render={
              <Button
                variant="ghost"
                size="icon-sm"
                aria-label={`Akcje faktury ${invoice.invoiceNumber}`}
                loading={markEntered.isPending || download.isPending || settings.addSkippedSupplier.isPending}
              />
            }
          >
            <EllipsisIcon />
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-64">
            {canEdit && !invoice.entered && (
              <DropdownMenuItem onClick={() => setEntered(true)}>
                <CircleCheckIcon />
                Oznacz jako wprowadzoną
              </DropdownMenuItem>
            )}
            {canEdit && manual && (
              <DropdownMenuItem onClick={() => setEntered(false)}>
                <UndoIcon />
                Cofnij ręczne oznaczenie
              </DropdownMenuItem>
            )}
            <DropdownMenuItem onClick={() => downloadAs('pdf')}>
              <FileTextIcon />
              Pobierz fakturę (PDF)
            </DropdownMenuItem>
            <DropdownMenuItem onClick={() => downloadAs('xml')}>
              <FileCodeIcon />
              Pobierz XML z KSeF
            </DropdownMenuItem>
            {canEdit && !invoice.supplierSkipped && (
              <>
                <DropdownMenuSeparator />
                <DropdownMenuItem onClick={skipSupplier}>
                  <EyeOffIcon />
                  Pomijaj faktury tego dostawcy
                </DropdownMenuItem>
              </>
            )}
          </DropdownMenuContent>
        </DropdownMenu>
      </TableCell>
    </TableRow>
  )
}

/** Wprowadzona (z numerem PZ albo kto oznaczył ręcznie) albo do wprowadzenia. */
function EnteredStatus({ invoice }: { invoice: KsefInvoice }) {
  if (!invoice.entered) {
    return (
      <span className="inline-flex items-center gap-1.5 text-sm text-amber-700 dark:text-amber-400">
        <span aria-hidden className="size-1.5 rounded-full bg-current" />
        Do wprowadzenia
      </span>
    )
  }
  const detail =
    invoice.deliveries.length > 0
      ? `Przyjęcie ${invoice.deliveries.map((delivery) => delivery.number).join(', ')}`
      : `Ręcznie${invoice.enteredBy ? ` · ${invoice.enteredBy}` : ''}`
  return (
    <div className="grid">
      <span className="inline-flex items-center gap-1.5 text-sm text-emerald-700 dark:text-emerald-400">
        <CircleCheckIcon aria-hidden className="size-3.5" />
        Wprowadzona
      </span>
      <span className="max-w-48 truncate text-xs text-muted-foreground" title={detail}>
        {detail}
      </span>
    </div>
  )
}
