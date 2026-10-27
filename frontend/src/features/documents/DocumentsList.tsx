import { useState } from 'react'
import { ArrowLeftIcon, ChevronRightIcon, LoaderCircleIcon } from 'lucide-react'
import { Link, useNavigate, useParams } from 'react-router'

import { FullPageMessage } from '@/components/FullPageMessage'
import { ReportFreshness } from '@/components/ReportFreshness'
import { SearchInput } from '@/components/SearchInput'
import { SegmentedField } from '@/components/form'
import { SortableHead } from '@/components/SortableHead'
import { TableSkeletonRows } from '@/components/TableSkeleton'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { type DocumentColumn, type Presence, useDocuments } from '@/features/documents/api'
import { CorrectionBadge } from '@/features/documents/DeliveryCorrections'
import { DeliveryPeriodReport } from '@/features/documents/DeliveryPeriodReport'
import { GratisBadge } from '@/features/documents/DocumentGratis'
import { DocumentRowKsef } from '@/features/documents/DocumentKsefInvoices'
import { countFormat, documentPaths, documentsWord, formatDocumentDate, formatMoney } from '@/features/documents/format'
import { InvoiceAmountCheck } from '@/features/documents/InvoiceAmountCheck'
import { StaleDataNotice } from '@/features/documents/StaleDataNotice'
import { isReportFresh } from '@/lib/report-freshness'
import { nextSort, type SortState } from '@/lib/table-sort'
import type { DocumentSummary } from '@/lib/types'
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

export function BackToTypes() {
  return (
    <Link
      to={documentPaths.overview}
      className="mb-4 inline-flex items-center gap-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground"
    >
      <ArrowLeftIcon className="size-4" />
      Wszystkie dokumenty
    </Link>
  )
}

/** Przyjęcia i korekty od dostawcy - do nich przypina się faktury z KSeF (jak `DocumentType.canLinkKsefInvoice`). */
const KSEF_LINKABLE_TYPES = new Set(['PZ', 'WZ'])

const INVOICE_OPTIONS: { value: Presence; label: string }[] = [
  { value: 'all', label: 'Wszystkie' },
  { value: 'with', label: 'Z fakturą' },
  { value: 'without', label: 'Bez faktury' },
]

const CORRECTION_OPTIONS: { value: Presence; label: string }[] = [
  { value: 'all', label: 'Wszystkie' },
  { value: 'with', label: 'Z korektami' },
  { value: 'without', label: 'Bez korekt' },
]

/**
 * Dokumenty jednego typu (z adresu strony). Dokumentów są tysiące, więc serwer oddaje je po 100, a kolejne strony
 * dochodzą przy przewijaniu; wyszukiwanie (też po artykułach w liniach), filtry przypiętych faktur i korekt,
 * sortowanie po nagłówkach i suma "Razem" liczą się w bazie.
 */
export function DocumentsList({ storeId }: { storeId: string }) {
  const typeCode = useParams().type ?? ''
  const upperType = typeCode.toUpperCase()
  const [query, setQuery] = useState('')
  const [product, setProduct] = useState('')
  const [invoice, setInvoice] = useState<Presence>('all')
  const [corrections, setCorrections] = useState<Presence>('all')
  const debouncedQuery = useDebouncedValue(query.trim(), 300)
  const debouncedProduct = useDebouncedValue(product.trim(), 300)
  // Domyślnie po numerze dokumentu, od góry do dołu.
  const [sort, setSort] = useState<SortState<DocumentColumn>>({ key: 'number', direction: 'asc' })
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
    (correctionFilter && corrections !== 'all')
  const clearFilters = () => {
    setQuery('')
    setProduct('')
    setInvoice('all')
    setCorrections('all')
  }
  const open = (document: DocumentSummary) => navigate(documentPaths.document(typeCode, document.id))

  return (
    <>
      <BackToTypes />
      {first && totalCount > 0 && <StaleDataNotice generatedAt={first.lastReportGeneratedAt} />}

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
          {upperType === 'PZ' && totalCount > 0 && <DeliveryPeriodReport storeId={storeId} />}
        </div>

        {totalCount > 0 && (
          <Card className="mb-4 gap-3 p-4">
            <div className="grid gap-3 md:grid-cols-2">
              <SearchInput value={query} onChange={setQuery} placeholder="Szukaj: numer, dostawca, nr dok. dostawcy" />
              <SearchInput value={product} onChange={setProduct} placeholder="Szukaj produktu: nazwa, marka, EAN, ITM8" />
            </div>
            {(invoiceFilter || correctionFilter || searching) && (
              <div className="flex flex-wrap items-center gap-x-5 gap-y-3">
                {invoiceFilter && (
                  <div className="flex items-center gap-2">
                    <span className="text-sm text-muted-foreground">Faktura</span>
                    <SegmentedField label="Faktura KSeF" options={INVOICE_OPTIONS} value={invoice} onChange={setInvoice} />
                  </div>
                )}
                {correctionFilter && (
                  <div className="flex items-center gap-2">
                    <span className="text-sm text-muted-foreground">Korekty</span>
                    <SegmentedField
                      label="Przypięte korekty"
                      options={CORRECTION_OPTIONS}
                      value={corrections}
                      onChange={setCorrections}
                    />
                  </div>
                )}
                {searching && (
                  <Button variant="link" className="h-auto p-0 text-sm" onClick={clearFilters}>
                    Wyczyść filtry
                  </Button>
                )}
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
                      className="cursor-pointer tabular-nums outline-none focus-visible:bg-muted"
                    >
                      <TableCell className="py-3 pl-4 font-medium">
                        <div className="flex items-center gap-2">
                          {document.number}
                          <GratisBadge document={document} />
                          <CorrectionBadge document={document} />
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
                        {document.supplierDocumentNumber}
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
