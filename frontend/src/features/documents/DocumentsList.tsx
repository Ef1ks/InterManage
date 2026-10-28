import { useEffect, useState } from 'react'
import { ChevronRightIcon, LoaderCircleIcon, LockIcon } from 'lucide-react'
import { useNavigate, useParams, useSearchParams } from 'react-router'

import { BackLink } from '@/components/BackLink'
import { CopyButton } from '@/components/CopyButton'
import { FullPageMessage } from '@/components/FullPageMessage'
import { ReportFreshness } from '@/components/ReportFreshness'
import { SearchInput } from '@/components/SearchInput'
import { SortableHead } from '@/components/SortableHead'
import { TableSkeletonRows } from '@/components/TableSkeleton'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { type DocumentColumn, type Presence, useDocuments } from '@/features/documents/api'
import { AutoInvoiceMatching } from '@/features/documents/AutoInvoiceMatching'
import { CorrectionBadge } from '@/features/documents/DeliveryCorrections'
import { DeliveryPeriodReport, PendingReportChanges } from '@/features/documents/DeliveryPeriodReport'
import { GratisBadge } from '@/features/documents/DocumentGratis'
import { TransferTargetBadge } from '@/features/documents/DocumentTransferTarget'
import { DocumentFilters, useHiddenSuppliers } from '@/features/documents/DocumentFilters'
import { DocumentRowKsef } from '@/features/documents/DocumentKsefInvoices'
import { countFormat, documentPaths, documentsWord, formatDocumentDate, formatMoney } from '@/features/documents/format'
import { InvoiceAmountCheck } from '@/features/documents/InvoiceAmountCheck'
import { StaleDataNotice } from '@/features/documents/StaleDataNotice'
import { isReportFresh } from '@/lib/report-freshness'
import { nextSort, type SortDirection, type SortState } from '@/lib/table-sort'
import type { DocumentReportClosing, DocumentSummary } from '@/lib/types'
import { useDebouncedValue } from '@/lib/use-debounced-value'
import { useLoadMore } from '@/lib/use-load-more'
import { cn } from '@/lib/utils'

/** Wartość z raportu, a przy przyjęciu z korektami ta po korektach, z raportu przekreślona pod spodem. */
function ListValue({ value, corrected }: { value: number; corrected: number | undefined }) {
  if (corrected == null || corrected === value) return <>{formatMoney(value)}</>
  return (
    <span title="Po korektach">
      {formatMoney(corrected)}
      <span className="block text-xs text-muted-foreground line-through decoration-muted-foreground/50">
        {formatMoney(value)}
      </span>
    </span>
  )
}

/** Przyjęcie poszło w zamkniętym raporcie okresowym. */
function ClosedLock({ closing }: { closing: DocumentReportClosing }) {
  const label = `W zamkniętym raporcie za ${formatDocumentDate(closing.periodFrom)} – ${formatDocumentDate(closing.periodTo)}`
  return (
    <span title={label} className="inline-flex text-muted-foreground">
      <LockIcon aria-hidden className="size-3.5" />
      <span className="sr-only">{label}</span>
    </span>
  )
}

export function BackToTypes() {
  return (
    <BackLink to={documentPaths.overview}>
      Wszystkie dokumenty
    </BackLink>
  )
}

/** Filtry „ma / nie ma” w adresie strony: `?faktura=z|bez` itd.; `all` = brak parametru. */
const PRESENCE_PARAMS: Record<Presence, string | null> = { all: null, with: 'z', without: 'bez' }
const presenceOf = (value: string | null): Presence => (value === 'z' ? 'with' : value === 'bez' ? 'without' : 'all')

const SORT_COLUMNS: DocumentColumn[] = ['number', 'date', 'supplier', 'supplierDocument', 'lines', 'purchase', 'sale']
/** Domyślnie po numerze dokumentu, od najnowszego (najwyższego numeru bonu); domyślne nie trafia do adresu. */
const DEFAULT_SORT: SortState<DocumentColumn> = { key: 'number', direction: 'desc' }

function sortOf(params: URLSearchParams): SortState<DocumentColumn> {
  const key = SORT_COLUMNS.find((column) => column === params.get('sortuj'))
  if (!key) return DEFAULT_SORT
  const direction: SortDirection = params.get('kierunek') === 'rosnaco' ? 'asc' : 'desc'
  return { key, direction }
}

/** Przyjęcia i korekty od dostawcy - do nich przypina się faktury z KSeF (jak `DocumentType.canLinkKsefInvoice`). */
const KSEF_LINKABLE_TYPES = new Set(['PZ', 'WZ'])

/**
 * Dokumenty jednego typu (z adresu strony). Dokumentów są tysiące, więc serwer oddaje je po 100, a kolejne strony
 * dochodzą przy przewijaniu; wyszukiwanie (też po artykułach w liniach), filtry (przypięte faktury i korekty,
 * zamknięcie w raporcie okresowym, ukryci dostawcy), sortowanie po nagłówkach i suma "Razem" liczą się w bazie.
 * Przyjęcia z zamkniętego raportu mają kłódkę i inne tło.
 */
export function DocumentsList({ storeId }: { storeId: string }) {
  const typeCode = useParams().type ?? ''
  const upperType = typeCode.toUpperCase()
  // Wyszukiwanie, filtry i sortowanie są w adresie strony: po wejściu w dokument i powrocie lista jest ta sama
  // (z pamięci zapytań), więc ScrollRestoration wraca w to samo miejsce.
  const [params, setParams] = useSearchParams()
  const updateParams = (changes: Record<string, string | null>) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        Object.entries(changes).forEach(([key, value]) => (value ? next.set(key, value) : next.delete(key)))
        return next
      },
      { replace: true },
    )
  const [query, setQuery] = useState(() => params.get('szukaj') ?? '')
  const [product, setProduct] = useState(() => params.get('produkt') ?? '')
  const invoice = presenceOf(params.get('faktura'))
  const corrections = presenceOf(params.get('korekty'))
  const closed = presenceOf(params.get('raport'))
  const setInvoice = (value: Presence) => updateParams({ faktura: PRESENCE_PARAMS[value] })
  const setCorrections = (value: Presence) => updateParams({ korekty: PRESENCE_PARAMS[value] })
  const setClosed = (value: Presence) => updateParams({ raport: PRESENCE_PARAMS[value] })
  const [hiddenSuppliers, setHiddenSuppliers] = useHiddenSuppliers(storeId)
  const debouncedQuery = useDebouncedValue(query.trim(), 300)
  const debouncedProduct = useDebouncedValue(product.trim(), 300)
  // Pola wyszukiwania trafiają do adresu dopiero po chwili bez pisania, jak do zapytania.
  useEffect(() => {
    const changes: Record<string, string | null> = {}
    if ((params.get('szukaj') ?? '') !== debouncedQuery) changes.szukaj = debouncedQuery || null
    if ((params.get('produkt') ?? '') !== debouncedProduct) changes.produkt = debouncedProduct || null
    if (Object.keys(changes).length === 0) return
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        Object.entries(changes).forEach(([key, value]) => (value ? next.set(key, value) : next.delete(key)))
        return next
      },
      { replace: true },
    )
  }, [debouncedQuery, debouncedProduct, params, setParams])
  const sort = sortOf(params)
  const setSort = (next: SortState<DocumentColumn>) =>
    updateParams(
      next.key === DEFAULT_SORT.key && next.direction === DEFAULT_SORT.direction
        ? { sortuj: null, kierunek: null }
        : { sortuj: next.key, kierunek: next.direction === 'asc' ? 'rosnaco' : 'malejaco' },
    )
  const invoiceFilter = KSEF_LINKABLE_TYPES.has(upperType)
  const correctionFilter = upperType === 'PZ'
  const { data, isPending, isError, refetch, fetchNextPage, hasNextPage, isFetchingNextPage, isPlaceholderData } =
    useDocuments(
      storeId,
      typeCode,
      {
        query: debouncedQuery,
        product: debouncedProduct,
        invoice: invoiceFilter ? invoice : 'all',
        corrections: correctionFilter ? corrections : 'all',
        closed: correctionFilter ? closed : 'all',
        hiddenSuppliers,
      },
      sort,
    )
  const navigate = useNavigate()
  const loadMoreRef = useLoadMore<HTMLDivElement>(
    () => fetchNextPage(),
    Boolean(hasNextPage) && !isFetchingNextPage,
    data?.pages.length ?? 0,
  )

  if (isError && !data) {
    return (
      <>
        <BackToTypes />
        <FullPageMessage
          title="Nie udało się wczytać dokumentów"
          description="Ten typ dokumentów może nie istnieć."
          action={{ label: 'Spróbuj ponownie', onClick: () => refetch() }}
          inline
        />
      </>
    )
  }

  const first = data?.pages[0]
  const documents = data?.pages.flatMap((page) => page.documents) ?? []
  const totalCount = first?.type.documentCount ?? 0
  const searching =
    debouncedQuery !== '' ||
    debouncedProduct !== '' ||
    (invoiceFilter && invoice !== 'all') ||
    (correctionFilter && (corrections !== 'all' || closed !== 'all')) ||
    hiddenSuppliers.length > 0
  // Pola wyszukiwania znikną z adresu po chwili, razem z zapytaniem.
  const clearFilters = () => {
    setQuery('')
    setProduct('')
    updateParams({ faktura: null, korekty: null, raport: null })
    setHiddenSuppliers([])
  }
  const open = (document: DocumentSummary) => navigate(documentPaths.document(typeCode, document.id))

  return (
    <>
      <BackToTypes />
      {first && totalCount > 0 && <StaleDataNotice generatedAt={first.lastReportGeneratedAt} />}
      {upperType === 'PZ' && <PendingReportChanges storeId={storeId} />}

      <section aria-labelledby="documents-heading">
        <div className="mb-3 flex flex-wrap items-end justify-between gap-x-4 gap-y-3">
          <div>
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
              <h2 id="documents-heading" className="text-xl font-semibold tracking-tight">
                {first?.type.label ?? 'Dokumenty'}
              </h2>
              {/* Nieaktualne dane pokazuje baner nad listą. */}
              {first && totalCount > 0 && isReportFresh(first.lastReportGeneratedAt) && (
                <ReportFreshness generatedAt={first.lastReportGeneratedAt} />
              )}
            </div>
            <p className="mt-0.5 text-sm text-muted-foreground tabular-nums" aria-live="polite">
              {!first
                ? ' '
                : totalCount === 0
                  ? first.type.description
                  : searching
                    ? `Znaleziono ${countFormat.format(first.matchingCount)} z ${countFormat.format(totalCount)}`
                    : `${countFormat.format(totalCount)} ${documentsWord(totalCount)} · kliknij nagłówek kolumny, żeby posortować`}
            </p>
          </div>
          {totalCount > 0 && (
            <div className="flex flex-wrap items-center gap-2">
              <DocumentFilters
                storeId={storeId}
                invoice={invoiceFilter ? { value: invoice, onChange: setInvoice } : null}
                corrections={correctionFilter ? { value: corrections, onChange: setCorrections } : null}
                closed={correctionFilter ? { value: closed, onChange: setClosed } : null}
                hiddenSuppliers={hiddenSuppliers}
                onHiddenSuppliersChange={setHiddenSuppliers}
                onClear={clearFilters}
              />
              {upperType === 'PZ' && <AutoInvoiceMatching storeId={storeId} />}
              {upperType === 'PZ' && <DeliveryPeriodReport storeId={storeId} />}
            </div>
          )}
        </div>

        {totalCount > 0 && (
          <Card className="mb-4 gap-3 p-4">
            <div className="grid gap-3 md:grid-cols-2">
              <SearchInput value={query} onChange={setQuery} placeholder="Szukaj: numer, dostawca, nr dok. dostawcy" />
              <SearchInput value={product} onChange={setProduct} placeholder="Szukaj produktu: nazwa, marka, EAN, ITM8" />
            </div>
            {searching && (
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted-foreground">
                {hiddenSuppliers.length > 0 && (
                  <span>
                    Ukryci dostawcy: {hiddenSuppliers.length} ·{' '}
                    <Button variant="link" className="h-auto p-0 text-sm" onClick={() => setHiddenSuppliers([])}>
                      pokaż wszystkich
                    </Button>
                  </span>
                )}
                <Button variant="link" className="h-auto p-0 text-sm" onClick={clearFilters}>
                  Wyczyść filtry
                </Button>
              </div>
            )}
          </Card>
        )}

        {first && totalCount === 0 ? (
          <Card className="items-center px-6 py-10 text-center">
            <p className="font-medium">Brak dokumentów tego typu</p>
            <p className="max-w-sm text-sm text-muted-foreground">
              Pojawią się, gdy raport Wejścia/Wyjścia wgrany w Imporcie danych będzie je zawierał.
            </p>
          </Card>
        ) : (
          <Card className="py-0">
            <Table className={cn('transition-opacity duration-150', isPlaceholderData && 'opacity-60')}>
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  <SortableHead column="number" sort={sort} onSort={(key) => setSort(nextSort(sort, key))} className="pl-4">
                    Nr dokumentu
                  </SortableHead>
                  <SortableHead column="date" sort={sort} onSort={(key) => setSort(nextSort(sort, key))}>
                    Data
                  </SortableHead>
                  <SortableHead column="supplier" sort={sort} onSort={(key) => setSort(nextSort(sort, key))}>
                    Dostawca
                  </SortableHead>
                  <SortableHead column="supplierDocument" sort={sort} onSort={(key) => setSort(nextSort(sort, key))}>
                    Nr dok. dostawcy
                  </SortableHead>
                  <SortableHead column="lines" sort={sort} onSort={(key) => setSort(nextSort(sort, key))} align="right">
                    Linie
                  </SortableHead>
                  <SortableHead column="purchase" sort={sort} onSort={(key) => setSort(nextSort(sort, key))} align="right">
                    Zakup zł
                  </SortableHead>
                  <SortableHead column="sale" sort={sort} onSort={(key) => setSort(nextSort(sort, key))} align="right">
                    Sprzedaż brutto zł
                  </SortableHead>
                  <TableHead className="w-10 pr-4">
                    <span className="sr-only">Otwórz</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {isPending ? (
                  <TableSkeletonRows columns={8} />
                ) : documents.length === 0 ? (
                  <TableRow className="hover:bg-transparent">
                    <TableCell colSpan={8} className="py-8 text-center text-sm text-muted-foreground">
                      {debouncedQuery && !debouncedProduct
                        ? `Żaden dokument nie pasuje do „${debouncedQuery}”.`
                        : 'Żaden dokument nie pasuje do wyszukiwania i filtrów.'}
                    </TableCell>
                  </TableRow>
                ) : (
                  documents.map((document) => (
                    <TableRow
                      key={document.id}
                      tabIndex={0}
                      role="link"
                      aria-label={`${first?.type.singular ?? 'Dokument'} ${document.number}`}
                      onClick={() => open(document)}
                      onKeyDown={(event) => event.key === 'Enter' && open(document)}
                      className={cn(
                        'cursor-pointer tabular-nums outline-none focus-visible:bg-muted',
                        document.reportClosing && 'bg-muted/60 text-muted-foreground hover:bg-muted',
                      )}
                    >
                      <TableCell className="py-3 pl-4 font-medium">
                        <div className="flex items-center gap-2">
                          {document.reportClosing && <ClosedLock closing={document.reportClosing} />}
                          {document.number}
                          <GratisBadge document={document} />
                          <CorrectionBadge document={document} />
                          <TransferTargetBadge document={document} />
                        </div>
                      </TableCell>
                      <TableCell>{formatDocumentDate(document.documentDate)}</TableCell>
                      <TableCell className="max-w-64 truncate" title={document.supplierName ?? undefined}>
                        {document.supplierName ? (
                          <>
                            {document.supplierName}
                            {document.supplierCode && (
                              <span className="ml-1.5 text-xs text-muted-foreground">{document.supplierCode}</span>
                            )}
                          </>
                        ) : (
                          <span className="text-muted-foreground">różni dostawcy</span>
                        )}
                      </TableCell>
                      <TableCell className="text-muted-foreground">
                        {document.supplierDocumentNumber && (
                          <span className="inline-flex items-center gap-0.5">
                            {document.supplierDocumentNumber}
                            <CopyButton
                              value={document.supplierDocumentNumber}
                              label={`Kopiuj numer ${document.supplierDocumentNumber}`}
                            />
                          </span>
                        )}
                        {KSEF_LINKABLE_TYPES.has(typeCode.toUpperCase()) && (
                          <DocumentRowKsef
                            storeId={storeId}
                            target={{
                              ...document,
                              type: typeCode.toUpperCase(),
                              typeSingular: first?.type.singular ?? 'Dokument',
                            }}
                          />
                        )}
                        <InvoiceAmountCheck
                          storeId={storeId}
                          documentId={document.id}
                          check={document.invoiceAmountCheck}
                          compact
                        />
                      </TableCell>
                      <TableCell className="text-right">{countFormat.format(document.lineCount)}</TableCell>
                      <TableCell className="text-right">
                        <ListValue value={document.purchaseValue} corrected={document.correctionTotals?.purchaseValue} />
                      </TableCell>
                      <TableCell className="text-right">
                        <ListValue value={document.saleValueGross} corrected={document.correctionTotals?.saleValueGross} />
                      </TableCell>
                      <TableCell className="pr-4 text-right">
                        <ChevronRightIcon aria-hidden className="ml-auto size-4 text-muted-foreground" />
                      </TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
              {first && first.matchingCount > 0 && (
                <TableFooter>
                  <TableRow className="hover:bg-transparent">
                    <TableCell colSpan={5} className="pl-4 font-medium">
                      Razem{' '}
                      <span className="font-normal text-muted-foreground tabular-nums">
                        ({countFormat.format(first.matchingCount)} {documentsWord(first.matchingCount)}
                        {searching && ' znalezionych'})
                      </span>
                    </TableCell>
                    <TableCell className="text-right font-medium tabular-nums">{formatMoney(first.purchaseTotal)}</TableCell>
                    <TableCell className="text-right font-medium tabular-nums">{formatMoney(first.saleTotal)}</TableCell>
                    <TableCell />
                  </TableRow>
                </TableFooter>
              )}
            </Table>
          </Card>
        )}

        {/* Dojechanie tu dociąga kolejne 100 dokumentów. */}
        <div ref={loadMoreRef} aria-live="polite" className="flex min-h-12 items-center justify-center text-sm text-muted-foreground">
          {isFetchingNextPage ? (
            <span className="inline-flex items-center gap-2">
              <LoaderCircleIcon aria-hidden className="size-4 animate-spin motion-reduce:animate-none" />
              Wczytuję kolejne dokumenty…
            </span>
          ) : (
            documents.length > 0 &&
            !hasNextPage &&
            first &&
            first.matchingCount > 100 && `To wszystkie ${countFormat.format(first.matchingCount)} ${documentsWord(first.matchingCount)}.`
          )}
        </div>
      </section>
    </>
  )
}
