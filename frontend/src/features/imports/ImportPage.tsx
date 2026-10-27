import { useEffect, useRef, useState, type ReactNode } from 'react'
import { ArrowRightIcon, CircleAlertIcon, CircleCheckIcon, XIcon } from 'lucide-react'
import { Link } from 'react-router'

import { FileDropZone } from '@/components/FileDropZone'
import { ImportError } from '@/components/ImportError'
import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { ReportFreshness } from '@/components/ReportFreshness'
import { TableSkeletonRows } from '@/components/TableSkeleton'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCan } from '@/features/auth/api'
import {
  useDocumentImports,
  useEanChanges,
  useImportDocuments,
  useImportNomenclatures,
  useImportProducts,
  useImportSales,
  useImportSuppliers,
  useNomenclatureImports,
  useProductImports,
  useSalesImports,
  useSupplierImports,
} from '@/features/imports/api'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { TOOL_ROUTES } from '@/features/tools/categories'
import { parseIsoDate } from '@/lib/dates'
import { formatReportDate } from '@/lib/report-freshness'
import type {
  DocumentImport,
  EanChange,
  NomenclatureImport,
  ProductImport,
  SalesImport,
  StoreSummary,
  SupplierImport,
} from '@/lib/types'

const ACCEPTED_EXTENSIONS = ['.xlsx', '.xlsm']
/** Lista nomenklatur jest drukowana z systemu sklepu tylko do PDF. */
const PDF_EXTENSIONS = ['.pdf']

const numberFormat = new Intl.NumberFormat('pl-PL')
const dateTimeFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'short' })
const timeFormat = new Intl.DateTimeFormat('pl-PL', { timeStyle: 'short' })
const dayFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short' })

/** Okres raportu albo zakres dni w bazie: "31.12.2025 – 30.06.2026". */
function formatPeriod(from: string, to: string) {
  return `${dayFormat.format(parseIsoDate(from))} – ${dayFormat.format(parseIsoDate(to))}`
}

export function ImportPage() {
  const store = useCurrentStore()

  if (!store) {
    return (
      <>
        <PageHeader title="Import danych" />
        <FullPageMessage
          title="Brak sklepu"
          description="Import trafia do danych konkretnego sklepu. Nie masz dostępu do żadnego sklepu - poproś właściciela o przypisanie."
          inline
        />
      </>
    )
  }

  // Zmiana sklepu czyści wyniki ostatnich wgrań i otwartą listę zmian EAN.
  return <StoreImports key={store.id} store={store} />
}

/** Data najświeższego raportu z historii (nie ostatniego wgrania - ktoś mógł wgrać starszy plik). */
function newestReport(imports: { reportGeneratedAt: string | null }[] | undefined) {
  return imports?.reduce<string | null>(
    (newest, item) => (item.reportGeneratedAt && (!newest || item.reportGeneratedAt > newest) ? item.reportGeneratedAt : newest),
    null,
  )
}

function StoreImports({ store }: { store: StoreSummary }) {
  const canImport = useCan('IMPORTS', 'EDIT')
  const canViewDocuments = useCan('DOCUMENTS')
  const products = useProductImports(store.id)
  const documents = useDocumentImports(store.id)
  const suppliers = useSupplierImports(store.id)
  const sales = useSalesImports(store.id)
  const nomenclatures = useNomenclatureImports(store.id)
  const [eanChangesOf, setEanChangesOf] = useState<ProductImport | null>(null)
  const lastProductImport = products.data?.imports[0]

  return (
    <>
      <PageHeader
        title="Import danych"
        description={`Raporty z systemu sklepu ${storeLabel(store)}. Wgrywaj je codziennie - dane z raportu sprzed dzisiaj są oznaczane jako nieaktualne.`}
      />

      <div className="mb-10 grid gap-4 lg:grid-cols-2 xl:grid-cols-3">
        <ReportCard
          id="product-report"
          title="Raport produktów"
          description="Kartoteka: ceny, stany, rotacja i nowe kody (np. BAZA DO STRONY.xlsx)."
          generatedAt={products.isPending ? undefined : (newestReport(products.data?.imports) ?? null)}
          meta={
            products.data && (
              <>
                W bazie {numberFormat.format(products.data.productCount)} produktów
                {lastProductImport && <> · ostatni import {relativeDateTime(lastProductImport.importedAt)}</>}
              </>
            )
          }
        >
          {canImport ? <ProductReportUpload store={store} onShowEanChanges={setEanChangesOf} /> : <ReadOnlyNote />}
        </ReportCard>

        <ReportCard
          id="document-report"
          title="Raport Wejścia/Wyjścia"
          description="Dokumenty magazynowe: przyjęcia towaru, korekty, likwidacje, zwroty, przeniesienia stanów i inne."
          generatedAt={documents.isPending ? undefined : (newestReport(documents.data) ?? null)}
          meta={
            canViewDocuments && (
              <Link
                to={TOOL_ROUTES.DOCUMENTS.path}
                className="inline-flex items-center gap-1 font-medium text-foreground underline-offset-4 hover:underline"
              >
                Zobacz dokumenty
                <ArrowRightIcon aria-hidden className="size-3.5" />
              </Link>
            )
          }
        >
          {canImport ? <DocumentReportUpload store={store} /> : <ReadOnlyNote />}
        </ReportCard>

        <ReportCard
          id="supplier-report"
          title="Lista dostawców"
          description="Dostawcy z systemu sklepu, do wyboru przy rejestrowaniu gratisów w Dokumentach."
          generatedAt={suppliers.isPending ? undefined : (newestReport(suppliers.data?.imports) ?? null)}
          meta={
            suppliers.data && (
              <>
                W bazie {numberFormat.format(suppliers.data.supplierCount)} dostawców
                {suppliers.data.manualCount > 0 && <>, w tym {numberFormat.format(suppliers.data.manualCount)} dodanych ręcznie</>}
              </>
            )
          }
        >
          {canImport ? <SupplierReportUpload store={store} /> : <ReadOnlyNote />}
        </ReportCard>

        <ReportCard
          id="sales-report"
          title="Raport sprzedaży"
          description="Sprzedaż po rabacie wg artykułu i dnia, z wartością FIFO, VAT i marżą."
          generatedAt={sales.isPending ? undefined : (newestReport(sales.data?.imports) ?? null)}
          meta={
            sales.data && (
              <>
                W bazie {numberFormat.format(sales.data.rowCount)} wierszy
                {sales.data.firstSaleDate && sales.data.lastSaleDate && (
                  <> · {formatPeriod(sales.data.firstSaleDate, sales.data.lastSaleDate)}</>
                )}
              </>
            )
          }
        >
          {canImport ? <SalesReportUpload store={store} /> : <ReadOnlyNote />}
        </ReportCard>

        <ReportCard
          id="nomenclature-report"
          title="Lista nomenklatur"
          description="Rejony, rodziny i podrodziny z nazwami do raportów sprzedaży (PDF, np. nomenklatury.pdf)."
          generatedAt={nomenclatures.isPending ? undefined : (newestReport(nomenclatures.data?.imports) ?? null)}
          meta={
            nomenclatures.data && (
              <>
                W bazie {numberFormat.format(nomenclatures.data.regionCount)} rejonów,{' '}
                {numberFormat.format(nomenclatures.data.familyCount)} rodzin,{' '}
                {numberFormat.format(nomenclatures.data.subfamilyCount)} podrodzin
              </>
            )
          }
        >
          {canImport ? <NomenclatureReportUpload store={store} /> : <ReadOnlyNote />}
        </ReportCard>
      </div>

      <section aria-labelledby="import-history">
        <h2 id="import-history" className="mb-3 text-base font-semibold">
          Historia importów
        </h2>
        {products.isError && documents.isError && suppliers.isError && sales.isError && nomenclatures.isError ? (
          <FullPageMessage
            title="Nie udało się wczytać historii importów"
            action={{
              label: 'Spróbuj ponownie',
              onClick: () => {
                products.refetch()
                documents.refetch()
                suppliers.refetch()
                sales.refetch()
                nomenclatures.refetch()
              },
            }}
            inline
          />
        ) : (
          <ImportHistory
            products={products.data?.imports}
            documents={documents.data}
            suppliers={suppliers.data?.imports}
            sales={sales.data?.imports}
            nomenclatures={nomenclatures.data?.imports}
            loading={
              products.isPending || documents.isPending || suppliers.isPending || sales.isPending || nomenclatures.isPending
            }
            onShowEanChanges={setEanChangesOf}
          />
        )}
      </section>

      {eanChangesOf && (
        <EanChangesPanel
          key={eanChangesOf.id}
          storeId={store.id}
          productImport={eanChangesOf}
          onClose={() => setEanChangesOf(null)}
        />
      )}
    </>
  )
}

/** Kafel jednego raportu: co to za plik, czy dane są aktualne i pole do wgrania. */
function ReportCard({
  id,
  title,
  description,
  generatedAt,
  meta,
  children,
}: {
  id: string
  title: string
  description: string
  /** {@code undefined} w trakcie wczytywania historii. */
  generatedAt: string | null | undefined
  meta?: ReactNode
  children: ReactNode
}) {
  return (
    <Card aria-labelledby={id} className="gap-4 p-5">
      <div className="grid gap-1.5">
        <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2">
          <h2 id={id} className="text-base font-semibold">
            {title}
          </h2>
          {generatedAt !== undefined && <ReportFreshness generatedAt={generatedAt} />}
        </div>
        <p className="text-sm text-muted-foreground">{description}</p>
        {meta && <p className="text-sm text-muted-foreground tabular-nums">{meta}</p>}
      </div>
      {children}
    </Card>
  )
}

function ReadOnlyNote() {
  return <p className="text-sm text-muted-foreground">Masz dostęp tylko do podglądu historii importów.</p>
}

type ShowEanChanges = (productImport: ProductImport) => void

function RejectedFile({ fileName, pdf }: { fileName: string; pdf?: boolean }) {
  return (
    <Alert variant="destructive" className="animate-in fade-in-0 duration-200 motion-reduce:animate-none">
      <CircleAlertIcon />
      <AlertTitle>Tego pliku nie zaimportujemy</AlertTitle>
      <AlertDescription>
        {pdf
          ? `${fileName} nie jest plikiem PDF. Wydrukuj listę nomenklatur z systemu sklepu do PDF.`
          : `${fileName} nie jest plikiem .xlsx ani .xlsm. Plik .xls otwórz w Excelu i zapisz jako .xlsx.`}
      </AlertDescription>
    </Alert>
  )
}

function ProductReportUpload({ store, onShowEanChanges }: { store: StoreSummary; onShowEanChanges: ShowEanChanges }) {
  const upload = useImportProducts(store.id)
  const [rejectedFile, setRejectedFile] = useState<string | null>(null)

  return (
    <div className="grid gap-3">
      <FileDropZone
        className="min-h-32 py-6"
        accept={ACCEPTED_EXTENSIONS}
        pendingFile={upload.isPending ? upload.variables : undefined}
        onFile={(file) => {
          setRejectedFile(null)
          upload.mutate(file)
        }}
        onReject={(fileName) => {
          upload.reset()
          setRejectedFile(fileName)
        }}
        title="Przeciągnij raport produktów albo kliknij"
        hint={`Plik .xlsx lub .xlsm z systemu sklepu ${store.number}`}
      />

      <div aria-live="polite">
        {rejectedFile && <RejectedFile fileName={rejectedFile} />}
        {upload.isError && <ImportError error={upload.error} />}
        {upload.isSuccess && <ProductImportResult result={upload.data} onShowEanChanges={onShowEanChanges} />}
      </div>
    </div>
  )
}

function ProductImportResult({ result, onShowEanChanges }: { result: ProductImport; onShowEanChanges: ShowEanChanges }) {
  const eanChanges = result.eanChangedCount + result.eanMovedCount

  return (
    <Alert className="animate-in fade-in-0 slide-in-from-top-1 duration-200 motion-reduce:animate-none">
      <CircleCheckIcon className="text-emerald-600 dark:text-emerald-400" />
      <AlertTitle>
        Zaimportowano {numberFormat.format(result.totalRows)} pozycji
        {result.reportGeneratedAt && <> z raportu {formatReportDate(result.reportGeneratedAt)}</>}
      </AlertTitle>
      <AlertDescription>
        <dl className="mt-1 flex flex-wrap gap-x-5 gap-y-1 tabular-nums">
          <ResultCount label="Nowe" value={result.createdCount} />
          <ResultCount label="Zmienione" value={result.updatedCount} />
          <ResultCount label="Bez zmian" value={result.unchangedCount} />
          <ResultCount label="Brak w pliku" value={result.missingCount} />
          <ResultCount label="Zmiana EAN" value={result.eanChangedCount} />
          <ResultCount label="EAN z innego ITM8" value={result.eanMovedCount} />
        </dl>
        {eanChanges > 0 && (
          <Button variant="link" className="mt-1 h-auto justify-self-start p-0" onClick={() => onShowEanChanges(result)}>
            Pokaż zmiany EAN
          </Button>
        )}
      </AlertDescription>
    </Alert>
  )
}

function DocumentReportUpload({ store }: { store: StoreSummary }) {
  const upload = useImportDocuments(store.id)
  const [rejectedFile, setRejectedFile] = useState<string | null>(null)

  return (
    <div className="grid gap-3">
      <FileDropZone
        className="min-h-32 py-6"
        accept={ACCEPTED_EXTENSIONS}
        pendingFile={upload.isPending ? upload.variables : undefined}
        onFile={(file) => {
          setRejectedFile(null)
          upload.mutate(file)
        }}
        onReject={(fileName) => {
          upload.reset()
          setRejectedFile(fileName)
        }}
        title="Przeciągnij raport Wejścia/Wyjścia albo kliknij"
        hint="Te same dokumenty można wgrywać wielokrotnie - zostaną uzupełnione."
      />

      <div aria-live="polite">
        {rejectedFile && <RejectedFile fileName={rejectedFile} />}
        {upload.isError && <ImportError error={upload.error} />}
        {upload.isSuccess && <DocumentImportResult result={upload.data} />}
      </div>
    </div>
  )
}

function DocumentImportResult({ result }: { result: DocumentImport }) {
  return (
    <Alert className="animate-in fade-in-0 slide-in-from-top-1 duration-200 motion-reduce:animate-none">
      <CircleCheckIcon className="text-emerald-600 dark:text-emerald-400" />
      <AlertTitle>
        Zaimportowano {numberFormat.format(result.totalLines)} linii
        {result.reportGeneratedAt && <> z raportu {formatReportDate(result.reportGeneratedAt)}</>}
      </AlertTitle>
      <AlertDescription>
        <dl className="mt-1 flex flex-wrap gap-x-5 gap-y-1 tabular-nums">
          <ResultCount label="Nowe dokumenty" value={result.createdCount} />
          <ResultCount label="Uzupełnione" value={result.updatedCount} />
          <ResultCount label="Zapisane linie" value={result.savedLines} />
        </dl>
        {result.skippedLines > 0 && (
          <p className="mt-1">
            Pominięto {numberFormat.format(result.skippedLines)} linii typu, którego jeszcze nie obsługujemy (kod w
            raporcie: {result.skippedTypes}).
          </p>
        )}
      </AlertDescription>
    </Alert>
  )
}

function SupplierReportUpload({ store }: { store: StoreSummary }) {
  const upload = useImportSuppliers(store.id)
  const [rejectedFile, setRejectedFile] = useState<string | null>(null)

  return (
    <div className="grid gap-3">
      <FileDropZone
        className="min-h-32 py-6"
        accept={ACCEPTED_EXTENSIONS}
        pendingFile={upload.isPending ? upload.variables : undefined}
        onFile={(file) => {
          setRejectedFile(null)
          upload.mutate(file)
        }}
        onReject={(fileName) => {
          upload.reset()
          setRejectedFile(fileName)
        }}
        title="Przeciągnij listę dostawców albo kliknij"
        hint="Dostawcy, których zabraknie w nowym pliku, zostają w bazie."
      />

      <div aria-live="polite">
        {rejectedFile && <RejectedFile fileName={rejectedFile} />}
        {upload.isError && <ImportError error={upload.error} />}
        {upload.isSuccess && <SupplierImportResult result={upload.data} />}
      </div>
    </div>
  )
}

function SupplierImportResult({ result }: { result: SupplierImport }) {
  return (
    <Alert className="animate-in fade-in-0 slide-in-from-top-1 duration-200 motion-reduce:animate-none">
      <CircleCheckIcon className="text-emerald-600 dark:text-emerald-400" />
      <AlertTitle>
        Zaimportowano {numberFormat.format(result.totalRows)} dostawców
        {result.reportGeneratedAt && <> z raportu {formatReportDate(result.reportGeneratedAt)}</>}
      </AlertTitle>
      <AlertDescription>
        <dl className="mt-1 flex flex-wrap gap-x-5 gap-y-1 tabular-nums">
          <ResultCount label="Nowi" value={result.createdCount} />
          <ResultCount label="Zmienieni" value={result.updatedCount} />
          <ResultCount label="Brak w pliku" value={result.missingCount} />
        </dl>
      </AlertDescription>
    </Alert>
  )
}

function SalesReportUpload({ store }: { store: StoreSummary }) {
  const upload = useImportSales(store.id)
  const [rejectedFile, setRejectedFile] = useState<string | null>(null)

  return (
    <div className="grid gap-3">
      <FileDropZone
        className="min-h-32 py-6"
        accept={ACCEPTED_EXTENSIONS}
        pendingFile={upload.isPending ? upload.variables : undefined}
        onFile={(file) => {
          setRejectedFile(null)
          upload.mutate(file)
        }}
        onReject={(fileName) => {
          upload.reset()
          setRejectedFile(fileName)
        }}
        title="Przeciągnij raport sprzedaży albo kliknij"
        hint="Zapisana sprzedaż się nie zmienia: z pliku dopisujemy tylko zakończone dni, których jeszcze nie ma."
      />

      <div aria-live="polite">
        {rejectedFile && <RejectedFile fileName={rejectedFile} />}
        {upload.isError && <ImportError error={upload.error} />}
        {upload.isSuccess && <SalesImportResult result={upload.data} />}
      </div>
    </div>
  )
}

function SalesImportResult({ result }: { result: SalesImport }) {
  return (
    <Alert className="animate-in fade-in-0 slide-in-from-top-1 duration-200 motion-reduce:animate-none">
      <CircleCheckIcon className="text-emerald-600 dark:text-emerald-400" />
      <AlertTitle>
        Zaimportowano {numberFormat.format(result.totalRows)} wierszy sprzedaży z dni{' '}
        {formatPeriod(result.periodFrom, result.periodTo)}
      </AlertTitle>
      <AlertDescription>
        <dl className="mt-1 flex flex-wrap gap-x-5 gap-y-1 tabular-nums">
          <ResultCount label="Dopisane" value={result.createdCount} />
          <ResultCount label="Już były w bazie" value={result.existingCount} />
          {result.incompleteCount > 0 && <ResultCount label="Dzień niezakończony" value={result.incompleteCount} />}
        </dl>
        {result.incompleteCount > 0 && result.reportGeneratedAt && (
          <p className="mt-1">
            Sprzedaż z {dayFormat.format(parseIsoDate(result.reportGeneratedAt.slice(0, 10)))} nie została zapisana, bo
            raport wygenerowano tego samego dnia i sprzedaż jeszcze trwała. Dopisze ją raport wgrany jutro.
          </p>
        )}
        {result.conflictCount > 0 && (
          <p className="mt-1">
            {numberFormat.format(result.conflictCount)} z nich ma w pliku inne wartości niż zapisane. Zostały wartości z
            pierwszego importu.
          </p>
        )}
      </AlertDescription>
    </Alert>
  )
}

function NomenclatureReportUpload({ store }: { store: StoreSummary }) {
  const upload = useImportNomenclatures(store.id)
  const [rejectedFile, setRejectedFile] = useState<string | null>(null)

  return (
    <div className="grid gap-3">
      <FileDropZone
        className="min-h-32 py-6"
        accept={PDF_EXTENSIONS}
        pendingFile={upload.isPending ? upload.variables : undefined}
        onFile={(file) => {
          setRejectedFile(null)
          upload.mutate(file)
        }}
        onReject={(fileName) => {
          upload.reset()
          setRejectedFile(fileName)
        }}
        title="Przeciągnij listę nomenklatur (PDF) albo kliknij"
        hint="Pozycje, których zabraknie w nowym pliku, zostają w bazie."
      />

      <div aria-live="polite">
        {rejectedFile && <RejectedFile fileName={rejectedFile} pdf />}
        {upload.isError && <ImportError error={upload.error} />}
        {upload.isSuccess && <NomenclatureImportResult result={upload.data} />}
      </div>
    </div>
  )
}

function NomenclatureImportResult({ result }: { result: NomenclatureImport }) {
  return (
    <Alert className="animate-in fade-in-0 slide-in-from-top-1 duration-200 motion-reduce:animate-none">
      <CircleCheckIcon className="text-emerald-600 dark:text-emerald-400" />
      <AlertTitle>
        Zaimportowano {numberFormat.format(result.totalRows)} pozycji nomenklatury
        {result.reportGeneratedAt && <> z raportu {formatReportDate(result.reportGeneratedAt)}</>}
      </AlertTitle>
      <AlertDescription>
        <dl className="mt-1 flex flex-wrap gap-x-5 gap-y-1 tabular-nums">
          <ResultCount label="Nowe" value={result.createdCount} />
          <ResultCount label="Zmienione" value={result.updatedCount} />
          <ResultCount label="Brak w pliku" value={result.missingCount} />
        </dl>
      </AlertDescription>
    </Alert>
  )
}

function ResultCount({ label, value }: { label: string; value: number }) {
  return (
    <div className="flex gap-1.5">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="font-medium text-foreground">{numberFormat.format(value)}</dd>
    </div>
  )
}

type HistoryRow =
  | { kind: 'products'; importedAt: string; item: ProductImport }
  | { kind: 'documents'; importedAt: string; item: DocumentImport }
  | { kind: 'suppliers'; importedAt: string; item: SupplierImport }
  | { kind: 'sales'; importedAt: string; item: SalesImport }
  | { kind: 'nomenclatures'; importedAt: string; item: NomenclatureImport }

const REPORT_NAMES: Record<HistoryRow['kind'], string> = {
  products: 'Produkty',
  documents: 'Wejścia/Wyjścia',
  suppliers: 'Lista dostawców',
  sales: 'Sprzedaż',
  nomenclatures: 'Lista nomenklatur',
}

/** Wszystkie wgrania raportów razem, od najnowszego. */
function ImportHistory({
  products,
  documents,
  suppliers,
  sales,
  nomenclatures,
  loading,
  onShowEanChanges,
}: {
  products?: ProductImport[]
  documents?: DocumentImport[]
  suppliers?: SupplierImport[]
  sales?: SalesImport[]
  nomenclatures?: NomenclatureImport[]
  loading: boolean
  onShowEanChanges: ShowEanChanges
}) {
  const rows: HistoryRow[] = [
    ...(products ?? []).map((item) => ({ kind: 'products' as const, importedAt: item.importedAt, item })),
    ...(documents ?? []).map((item) => ({ kind: 'documents' as const, importedAt: item.importedAt, item })),
    ...(suppliers ?? []).map((item) => ({ kind: 'suppliers' as const, importedAt: item.importedAt, item })),
    ...(sales ?? []).map((item) => ({ kind: 'sales' as const, importedAt: item.importedAt, item })),
    ...(nomenclatures ?? []).map((item) => ({ kind: 'nomenclatures' as const, importedAt: item.importedAt, item })),
  ].sort((a, b) => b.importedAt.localeCompare(a.importedAt))

  if (!loading && rows.length === 0) {
    return (
      <Card className="items-center px-6 py-10 text-center">
        <p className="font-medium">Nie było jeszcze żadnego importu</p>
        <p className="text-sm text-muted-foreground">Po wgraniu pierwszego raportu zobaczysz tu, co się zmieniło.</p>
      </Card>
    )
  }

  return (
    <Card className="py-0">
      <Table>
        <TableHeader>
          <TableRow className="hover:bg-transparent">
            <TableHead className="pl-4">Wgrano</TableHead>
            <TableHead>Raport</TableHead>
            <TableHead>Dane z</TableHead>
            <TableHead>Wynik</TableHead>
            <TableHead className="pr-4 text-right">Zmiany EAN</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {loading ? (
            <TableSkeletonRows columns={5} rows={3} />
          ) : (
            rows.map((row) => (
              <TableRow key={`${row.kind}-${row.item.id}`} className="tabular-nums">
                <TableCell className="py-3 pl-4">
                  <div className="font-medium">{dateTimeFormat.format(new Date(row.importedAt))}</div>
                  <div className="max-w-72 truncate text-xs text-muted-foreground" title={row.item.fileName}>
                    {row.item.importedBy ?? 'Usunięty użytkownik'} · {row.item.fileName}
                  </div>
                </TableCell>
                <TableCell>{REPORT_NAMES[row.kind]}</TableCell>
                <TableCell className="text-muted-foreground">
                  {row.item.reportGeneratedAt ? formatReportDate(row.item.reportGeneratedAt) : '—'}
                </TableCell>
                <TableCell className="whitespace-normal text-muted-foreground">
                  {row.kind === 'products'
                    ? productSummary(row.item)
                    : row.kind === 'documents'
                      ? documentSummary(row.item)
                      : row.kind === 'suppliers'
                        ? supplierSummary(row.item)
                        : row.kind === 'nomenclatures'
                          ? nomenclatureSummary(row.item)
                          : salesSummary(row.item)}
                </TableCell>
                <TableCell className="pr-4 text-right">
                  {row.kind === 'products' ? (
                    <EanChangesCell productImport={row.item} onShow={onShowEanChanges} />
                  ) : (
                    <span className="text-muted-foreground">—</span>
                  )}
                </TableCell>
              </TableRow>
            ))
          )}
        </TableBody>
      </Table>
    </Card>
  )
}

function productSummary(item: ProductImport) {
  return [
    `${numberFormat.format(item.totalRows)} pozycji`,
    `${numberFormat.format(item.createdCount)} nowych`,
    `${numberFormat.format(item.updatedCount)} zmienionych`,
    `${numberFormat.format(item.missingCount)} brak w pliku`,
  ].join(' · ')
}

function documentSummary(item: DocumentImport) {
  const parts = [
    `${numberFormat.format(item.totalLines)} linii`,
    `dokumenty: ${numberFormat.format(item.createdCount)} nowych, ${numberFormat.format(item.updatedCount)} uzupełnionych`,
  ]
  if (item.skippedLines > 0) parts.push(`pominięto ${numberFormat.format(item.skippedLines)}`)
  return parts.join(' · ')
}

function supplierSummary(item: SupplierImport) {
  return [
    `${numberFormat.format(item.totalRows)} dostawców`,
    `${numberFormat.format(item.createdCount)} nowych`,
    `${numberFormat.format(item.updatedCount)} zmienionych`,
    `${numberFormat.format(item.missingCount)} brak w pliku`,
  ].join(' · ')
}

function nomenclatureSummary(item: NomenclatureImport) {
  return [
    `${numberFormat.format(item.totalRows)} pozycji`,
    `${numberFormat.format(item.createdCount)} nowych`,
    `${numberFormat.format(item.updatedCount)} zmienionych`,
    `${numberFormat.format(item.missingCount)} brak w pliku`,
  ].join(' · ')
}

function salesSummary(item: SalesImport) {
  return [
    `${numberFormat.format(item.totalRows)} wierszy z dni ${formatPeriod(item.periodFrom, item.periodTo)}`,
    `${numberFormat.format(item.createdCount)} dopisanych`,
    `${numberFormat.format(item.existingCount)} już było`,
    ...(item.conflictCount > 0 ? [`${numberFormat.format(item.conflictCount)} niezgodnych z zapisanymi`] : []),
    ...(item.incompleteCount > 0 ? [`${numberFormat.format(item.incompleteCount)} z dnia niezakończonego`] : []),
  ].join(' · ')
}

function EanChangesCell({ productImport, onShow }: { productImport: ProductImport; onShow: ShowEanChanges }) {
  const count = productImport.eanChangedCount + productImport.eanMovedCount
  if (count === 0) return <span className="text-muted-foreground">0</span>
  return (
    <Button
      variant="ghost"
      size="sm"
      className="-mr-2.5 tabular-nums underline underline-offset-4"
      aria-label={`Pokaż ${count} zmian EAN z importu ${dateTimeFormat.format(new Date(productImport.importedAt))}`}
      onClick={() => onShow(productImport)}
    >
      {numberFormat.format(count)}
    </Button>
  )
}

/**
 * Zmiany przypisań EAN z jednego importu: gdzie sprawdzić etykiety i które kody system sklepu poprawił.
 * Sprzedaż pod starymi parami ITM8 + EAN zostaje w bazie przy tych parach.
 */
function EanChangesPanel({
  storeId,
  productImport,
  onClose,
}: {
  storeId: string
  productImport: ProductImport
  onClose: () => void
}) {
  const { data: changes, isPending, isError, refetch } = useEanChanges(storeId, productImport.id)
  const sectionRef = useRef<HTMLElement>(null)

  useEffect(() => {
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    sectionRef.current?.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' })
  }, [])

  return (
    <section
      ref={sectionRef}
      aria-labelledby="ean-changes"
      className="mt-8 scroll-mt-6 animate-in fade-in-0 duration-200 motion-reduce:animate-none"
    >
      <div className="mb-3 flex items-start justify-between gap-4">
        <div>
          <h2 id="ean-changes" className="text-base font-semibold">
            Zmiany EAN z importu {dateTimeFormat.format(new Date(productImport.importedAt))}
          </h2>
          <p className="mt-0.5 text-sm text-muted-foreground">
            Sprawdź etykiety na półce. Dotychczasowa sprzedaż zostaje przy poprzednich parach ITM8 i EAN.
          </p>
        </div>
        <Button variant="ghost" size="icon-sm" aria-label="Zamknij listę zmian EAN" onClick={onClose}>
          <XIcon />
        </Button>
      </div>

      {isError ? (
        <FullPageMessage
          title="Nie udało się wczytać zmian EAN"
          action={{ label: 'Spróbuj ponownie', onClick: () => refetch() }}
          inline
        />
      ) : (
        <Card className="py-0">
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="pl-4">Produkt</TableHead>
                <TableHead>EAN teraz</TableHead>
                <TableHead className="pr-4">Wcześniej</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {isPending ? (
                <TableSkeletonRows columns={3} rows={3} />
              ) : (
                changes.map((change) => (
                  <TableRow key={`${change.itm8}-${change.ean}`} className="tabular-nums">
                    <TableCell className="py-3 pl-4">
                      <div className="font-medium">{change.name ?? 'Bez nazwy'}</div>
                      <div className="text-xs text-muted-foreground">ITM8 {change.itm8}</div>
                    </TableCell>
                    <TableCell>{change.ean}</TableCell>
                    <TableCell className="pr-4 whitespace-normal">
                      <PreviousAssignment change={change} />
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </Card>
      )}
    </section>
  )
}

function PreviousAssignment({ change }: { change: EanChange }) {
  return (
    <div className="grid gap-0.5 text-sm">
      {change.previousEan && (
        <div>
          <span className="text-muted-foreground">ten ITM8 miał EAN</span> {change.previousEan}
        </div>
      )}
      {change.previousItm8 && (
        <div>
          <span className="text-muted-foreground">ten EAN był pod ITM8</span> {change.previousItm8}
          {change.previousName && <span className="text-muted-foreground"> · {change.previousName}</span>}
        </div>
      )}
    </div>
  )
}

/** "dziś, 16:04", "wczoraj, 07:30" albo pełna data. */
function relativeDateTime(iso: string) {
  const date = new Date(iso)
  const today = new Date()
  const yesterday = new Date(today)
  yesterday.setDate(today.getDate() - 1)
  if (date.toDateString() === today.toDateString()) return `dziś, ${timeFormat.format(date)}`
  if (date.toDateString() === yesterday.toDateString()) return `wczoraj, ${timeFormat.format(date)}`
  return dateTimeFormat.format(date)
}
