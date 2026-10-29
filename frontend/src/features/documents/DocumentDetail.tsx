import { useDeferredValue, useMemo, useState } from 'react'
import { ClipboardListIcon, PrinterIcon } from 'lucide-react'
import { Link, useParams } from 'react-router'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import { CopyButton } from '@/components/CopyButton'
import { FullPageMessage } from '@/components/FullPageMessage'
import { SearchInput } from '@/components/SearchInput'
import { SortableHead } from '@/components/SortableHead'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Switch } from '@/components/ui/switch'
import { Table, TableBody, TableCell, TableHeader, TableRow } from '@/components/ui/table'
import { deliPaths } from '@/features/deli-order/paths'
import { useDocument, useDownloadDeliveryNote } from '@/features/documents/api'
import { DeliveryCorrections } from '@/features/documents/DeliveryCorrections'
import { DocumentGratisSection } from '@/features/documents/DocumentGratis'
import { DocumentKsefInvoices } from '@/features/documents/DocumentKsefInvoices'
import { DocumentTransferTargetSection } from '@/features/documents/DocumentTransferTarget'
import { BackToTypes } from '@/features/documents/DocumentsList'
import { SupplierDocumentNumber } from '@/features/documents/SupplierDocumentNumber'
import {
  bonNumber,
  countFormat,
  documentPaths,
  formatDocumentDate,
  formatMoney,
  formatPercent,
  formatPrice,
  formatQuantity,
  showsSupplier,
} from '@/features/documents/format'
import { matchesSearch } from '@/lib/search'
import { type SortAccessors, useTableSort } from '@/lib/table-sort'
import { useLoadMore } from '@/lib/use-load-more'
import { ApiError } from '@/lib/api'
import type { CorrectedLine, Document, DocumentLine } from '@/lib/types'
import { cn } from '@/lib/utils'

/** Linie dokumentu rysujemy po 100; kolejne dochodzą przy przewijaniu (przyjęcie potrafi mieć 1500 linii). */
const PAGE_SIZE = 100

type Column = 'line' | 'name' | 'quantity' | 'purchasePrice' | 'purchaseValue' | 'salePrice' | 'vat' | 'margin'

/**
 * Linia w tabeli: z dokumentu albo (przyjęcie po korektach) z wartościami po korektach i tymi z przyjęcia obok.
 */
type Row = DocumentLine & {
  originalQuantity?: number | null
  originalPurchaseValue?: number | null
  corrected?: boolean
}

const fromCorrected = (line: CorrectedLine): Row => ({
  ...line,
  id: line.key,
  supplierCode: null,
  supplierName: null,
  nomenclatureName: null,
  salePriceCurrent: null,
})

const SORT_BY: SortAccessors<Row, Column> = {
  line: (line) => line.lineNumber,
  name: (line) => line.name,
  quantity: (line) => line.quantity,
  purchasePrice: (line) => line.purchasePrice,
  purchaseValue: (line) => line.purchaseValue,
  salePrice: (line) => line.salePriceDocument,
  vat: (line) => line.vatRate,
  margin: (line) => line.marginPercent,
}

export function DocumentDetail({ storeId }: { storeId: string }) {
  const params = useParams()
  const typeCode = params.type ?? ''
  const { data: document, isPending, isError } = useDocument(storeId, typeCode, params.documentId ?? '')

  if (isError) {
    return (
      <>
        <BackToTypes />
        <FullPageMessage title="Nie znaleziono dokumentu" description="Mógł zostać wgrany w innym sklepie." inline />
      </>
    )
  }
  if (isPending) {
    return <div aria-hidden className="h-40 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
  }
  return <DocumentView storeId={storeId} document={document} />
}

function DocumentView({ storeId, document }: { storeId: string; document: Document }) {
  const [query, setQuery] = useState('')
  const deferredQuery = useDeferredValue(query)
  const [hideEmpty, setHideEmpty] = useState(false)
  const [limit, setLimit] = useState(PAGE_SIZE)
  // Przyjęcie z korektami pokazujemy domyślnie po korektach; przełącznik wraca do linii z raportu.
  const [showCorrected, setShowCorrected] = useState(true)
  const corrected = document.corrected
  const lines: Row[] = useMemo(
    () => (corrected && showCorrected ? corrected.lines.map(fromCorrected) : document.lines),
    [corrected, showCorrected, document.lines],
  )
  const download = useDownloadDeliveryNote(storeId)
  const emptyCount = useMemo(() => lines.filter((line) => line.quantity === 0).length, [lines])
  // W likwidacjach czy przeniesieniach każda linia ma swojego dostawcę - pokazujemy go przy linii.
  const perLineSupplier = document.supplierName == null

  const filtered = useMemo(
    () =>
      lines.filter(
        (line) =>
          (!hideEmpty || line.quantity !== 0) &&
          matchesSearch(
            [line.name, line.brand, line.ean, line.itm8, line.nomenclatureName, line.supplierName, line.lineNumber],
            deferredQuery,
          ),
      ),
    [lines, hideEmpty, deferredQuery],
  )
  const { sorted, sort, toggle } = useTableSort(filtered, SORT_BY, { key: 'line', direction: 'asc' })
  const visible = sorted.slice(0, limit)
  const loadMoreRef = useLoadMore<HTMLDivElement>(
    () => setLimit((current) => current + PAGE_SIZE),
    sorted.length > limit,
    visible.length,
  )

  return (
    <>
      <BackLink to={documentPaths.list(document.type)}>
        {document.typeLabel}
      </BackLink>

      <div className="mb-6">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <h2 className="text-xl font-semibold tracking-tight tabular-nums">
            {document.typeSingular} {document.number}
            <CopyButton value={document.number} label={`Kopiuj numer ${document.number}`} className="ml-1 align-middle" />
            {document.correctable && (
              <span className="ml-2 text-base font-normal text-muted-foreground">
                bon {bonNumber(document.number)}
                <CopyButton
                  value={bonNumber(document.number)}
                  label={`Kopiuj numer bonu ${bonNumber(document.number)}`}
                  className="ml-1 align-middle"
                />
              </span>
            )}
          </h2>
          {document.correctable && (
            <Button
              variant="outline"
              size="sm"
              loading={download.isPending}
              onClick={() =>
                download.mutate(
                  { id: document.id, number: bonNumber(document.number) },
                  { onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się pobrać wydruku') },
                )
              }
            >
              <PrinterIcon />
              Wydruk bonu
            </Button>
          )}
        </div>
        <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm sm:grid-cols-[auto_1fr_auto_1fr]">
          <dt className="text-muted-foreground">Data</dt>
          <dd className="tabular-nums">{formatDocumentDate(document.documentDate)}</dd>
          {showsSupplier(document.type) && (
            <>
              <dt className="text-muted-foreground">Dostawca</dt>
              <dd>
                {document.supplierName ?? <span className="text-muted-foreground">różni dostawcy - przy liniach</span>}
                {document.supplierCode && (
                  <span className="ml-1.5 text-muted-foreground tabular-nums">{document.supplierCode}</span>
                )}
              </dd>
              <dt className="text-muted-foreground">Nr dok. dostawcy</dt>
              <dd>
                <SupplierDocumentNumber storeId={storeId} document={document} />
              </dd>
            </>
          )}
          <dt className="text-muted-foreground">Linie</dt>
          <dd className="tabular-nums">
            {countFormat.format(document.lineCount)}
            {emptyCount > 0 && <span className="text-muted-foreground"> (z ilością 0: {countFormat.format(emptyCount)})</span>}
          </dd>
          <dt className="text-muted-foreground">Zakup</dt>
          <dd className="font-medium tabular-nums">
            <CorrectedValue value={document.purchaseValue} corrected={document.correctionTotals?.purchaseValue} />
          </dd>
          <dt className="text-muted-foreground">Sprzedaż brutto</dt>
          <dd className="font-medium tabular-nums">
            <CorrectedValue value={document.saleValueGross} corrected={document.correctionTotals?.saleValueGross} />
          </dd>
        </dl>
      </div>

      <DeliveryCorrections storeId={storeId} document={document} />
      <DocumentGratisSection storeId={storeId} document={document} />
      <DocumentTransferTargetSection storeId={storeId} document={document} />
      <DocumentKsefInvoices storeId={storeId} document={document} />

      {document.deliOrders.length > 0 && (
        <section aria-labelledby="deli-orders" className="mb-6">
          <h3 id="deli-orders" className="mb-2 text-sm font-semibold">
            Zamówienia garmażu jadące na tym dokumencie
          </h3>
          <ul className="flex flex-wrap gap-2">
            {document.deliOrders.map((order) => (
              <li key={order.orderId}>
                <Link
                  to={deliPaths.orderDetail(order.orderId)}
                  className="inline-flex items-center gap-2 rounded-lg border bg-card px-3 py-1.5 text-sm tabular-nums transition-colors hover:bg-muted/60"
                >
                  <ClipboardListIcon aria-hidden className="size-4 text-muted-foreground" />
                  Do sklepu {order.storeNumber}
                  {order.storeName && ` · ${order.storeName}`}
                  <span className="text-muted-foreground">· dostawa {formatDocumentDate(order.deliveryDate)}</span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}

      <div className="mb-3 flex flex-wrap items-center justify-between gap-x-4 gap-y-3">
        <SearchInput
          className="w-full sm:w-96"
          value={query}
          onChange={(value) => {
            setQuery(value)
            setLimit(PAGE_SIZE)
          }}
          placeholder={perLineSupplier ? 'Szukaj: nazwa, marka, EAN, ITM8, dostawca' : 'Szukaj: nazwa, marka, EAN, ITM8'}
        />
        <div className="flex flex-wrap items-center gap-x-5 gap-y-2">
          {corrected && (
            <label className="flex items-center gap-2 text-sm text-muted-foreground">
              <Switch checked={showCorrected} onCheckedChange={setShowCorrected} />
              Po korektach
            </label>
          )}
          {emptyCount > 0 && (
            <label className="flex items-center gap-2 text-sm text-muted-foreground">
              <Switch checked={hideEmpty} onCheckedChange={setHideEmpty} />
              Ukryj linie z ilością 0
            </label>
          )}
        </div>
      </div>

      <Card className="py-0">
        <Table className="min-w-4xl">
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <SortableHead column="line" sort={sort} onSort={toggle} align="right" className="w-16 pl-4">
                Lp.
              </SortableHead>
              <SortableHead column="name" sort={sort} onSort={toggle}>
                Towar
              </SortableHead>
              <SortableHead column="quantity" sort={sort} onSort={toggle} align="right">
                Ilość
              </SortableHead>
              <SortableHead column="purchasePrice" sort={sort} onSort={toggle} align="right">
                Cena zakupu
              </SortableHead>
              <SortableHead column="purchaseValue" sort={sort} onSort={toggle} align="right">
                Zakup zł
              </SortableHead>
              <SortableHead column="salePrice" sort={sort} onSort={toggle} align="right">
                Cena sprzedaży
              </SortableHead>
              <SortableHead column="vat" sort={sort} onSort={toggle} align="right">
                VAT
              </SortableHead>
              <SortableHead column="margin" sort={sort} onSort={toggle} align="right" className="pr-4">
                Marża
              </SortableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {visible.length === 0 ? (
              <TableRow className="hover:bg-transparent">
                <TableCell colSpan={8} className="py-8 text-center text-sm text-muted-foreground">
                  {query ? `Żadna linia nie pasuje do „${query}”.` : 'Brak linii do pokazania.'}
                </TableCell>
              </TableRow>
            ) : (
              visible.map((line) => (
                <TableRow key={line.id} className={cn('tabular-nums', line.quantity === 0 && 'text-muted-foreground')}>
                  <TableCell className="pl-4 text-right text-muted-foreground">{line.lineNumber}</TableCell>
                  <TableCell className="max-w-96 py-2.5 whitespace-normal">
                    <div className="flex flex-wrap items-center gap-2 font-medium">
                      {line.name ?? 'Bez nazwy'}
                      {line.corrected && (
                        <span className="rounded-md bg-amber-50 px-1.5 py-0.5 text-[0.7rem] font-medium text-amber-800 dark:bg-amber-950/40 dark:text-amber-200">
                          {line.originalQuantity == null ? 'Dopisane korektą' : 'Po korekcie'}
                        </span>
                      )}
                    </div>
                    <div className="text-xs text-muted-foreground">
                      {[line.brand, line.ean && `EAN ${line.ean}`, `ITM8 ${line.itm8}`, perLineSupplier && line.supplierName]
                        .filter(Boolean)
                        .join(' · ')}
                    </div>
                  </TableCell>
                  <TableCell className="text-right">
                    {formatQuantity(line.quantity)}
                    <WasValue line={line} value={line.originalQuantity} current={line.quantity} format={formatQuantity} />
                  </TableCell>
                  <TableCell className="text-right">{formatPrice(line.purchasePrice)}</TableCell>
                  <TableCell className="text-right">
                    {formatMoney(line.purchaseValue)}
                    <WasValue line={line} value={line.originalPurchaseValue} current={line.purchaseValue} format={formatMoney} />
                  </TableCell>
                  <TableCell className="text-right">{formatPrice(line.salePriceDocument)}</TableCell>
                  <TableCell className="text-right">{line.vatRate != null ? `${line.vatRate}%` : ''}</TableCell>
                  <TableCell className="pr-4 text-right">{formatPercent(line.marginPercent)}</TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </Card>

      {/* Dojechanie tu dokłada kolejne 100 linii. */}
      <div ref={loadMoreRef} className="min-h-8" />
    </>
  )
}

/** Wartość dokumentu; przy przyjęciu z korektami ta po korektach, a z raportu mniejszym drukiem. */
function CorrectedValue({ value, corrected }: { value: number; corrected: number | undefined }) {
  if (corrected == null || corrected === value) return <>{formatMoney(value)} zł</>
  return (
    <>
      {formatMoney(corrected)} zł
      <span className="ml-1.5 text-xs font-normal text-muted-foreground">
        po korektach, z raportu {formatMoney(value)} zł
      </span>
    </>
  )
}

/** Wartość z przyjęcia pod wartością po korekcie, gdy korekta ją zmieniła. */
function WasValue({
  line,
  value,
  current,
  format,
}: {
  line: Row
  value: number | null | undefined
  current: number | null
  format: (value: number) => string
}) {
  if (!line.corrected || value == null || value === current) return null
  return <div className="text-xs text-muted-foreground line-through decoration-muted-foreground/50">{format(value)}</div>
}
