import { useEffect, useRef, useState, type ReactNode } from 'react'
import type { UseMutationResult } from '@tanstack/react-query'
import { ArrowRightIcon, CircleAlertIcon, CircleCheckIcon, RefreshCwIcon, SettingsIcon, XIcon } from 'lucide-react'
import { Link } from 'react-router'
import { toast } from 'sonner'

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
import { useCan, useMe } from '@/features/auth/api'
import {
  useDocumentImports,
  useEanChanges,
  useImportDocuments,
  useImportGuides,
  useImportNomenclatures,
  useImportProductCodes,
  useImportProducts,
  useImportPromotions,
  useImportSales,
  useImportSuppliers,
  useNomenclatureImports,
  useProductCodeImports,
  useProductImports,
  usePromotionImports,
  useSalesImports,
  useSupplierImports,
} from '@/features/imports/api'
import { ImportSourcesDialog } from '@/features/imports/ImportSourcesDialog'
import {
  DAILY_REPORTS,
  newestReportFile,
  PDF_EXTENSIONS,
  readMasks,
  REPORT_TITLES,
  sourceFolderKey,
  XLSX_EXTENSIONS,
  type DailyReport,
} from '@/features/imports/import-sources'
import { ReportHelp } from '@/features/imports/ReportHelp'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { TOOL_ROUTES } from '@/features/tools/categories'
import { ApiError } from '@/lib/api'
import { parseIsoDate } from '@/lib/dates'
import { formatReportDate } from '@/lib/report-freshness'
import { ensureFolderAccess, folderAccess, folderSavingSupported } from '@/lib/saved-folders'
import type {
  DocumentImport,
  EanChange,
  ImportGuide,
  ImportReportKind,
  NomenclatureImport,
  ProductCodeImport,
  ProductImport,
  PromotionImport,
  SalesImport,
  StoreSummary,
  SupplierImport,
} from '@/lib/types'

const numberFormat = new Intl.NumberFormat('pl-PL')
const dateTimeFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'short' })
const timeFormat = new Intl.DateTimeFormat('pl-PL', { timeStyle: 'short' })
const dayFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short' })

/** Co jest w raporcie: pierwsza część okienka pod znakiem zapytania (resztę pisze właściciel). */
const REPORT_DESCRIPTIONS: Record<ImportReportKind, string> = {
  PRODUCTS: 'Kartoteka sklepu: ceny, stany, rotacja i nowe kody (np. BAZA DO STRONY.xlsx). Wgrywaj ją codziennie.',
  PRODUCT_CODES:
    'Wszystkie EAN-y przypisane teraz do każdego ITM8, z kodem głównym i współczynnikiem ceny (np. Multikody.xlsx). Kody, których zabraknie w nowym pliku, zostają w bazie jako historia przypisań.',
  PROMOTIONS:
    'Bieżące i przyszłe promocje z cenami zakupu, sprzedaży i promocyjnymi (PDF, np. Taryfy.pdf). Promocje, których zabraknie w nowym pliku, zostają w bazie.',
  SALES:
    'Sprzedaż po rabacie wg artykułu i dnia, z wartością FIFO, VAT i marżą (np. sprzedaz.xlsx). Zapisana sprzedaż się nie zmienia: z pliku dopisujemy tylko zakończone dni, których jeszcze nie ma.',
  DOCUMENTS:
    'Statystyka Wejścia/Wyjścia: przyjęcia towaru, korekty, likwidacje, zwroty, przeniesienia stanów i inne dokumenty magazynowe. Te same dokumenty można wgrywać wielokrotnie - zostaną uzupełnione.',
  SUPPLIERS:
    'Dostawcy z systemu sklepu. Wgraj listę ponownie, gdy dojdzie nowy dostawca albo zmienią się jego dane. Dostawcy, których zabraknie w nowym pliku, zostają w bazie.',
  NOMENCLATURES:
    'Rejony, rodziny i podrodziny z nazwami do raportów (PDF, np. nomenklatury.pdf). Wgraj ją ponownie, gdy w systemie sklepu zmieni się nomenklatura.',
}

/** Kolumny, bez których raport jest odrzucany (ta sama lista w ProductReportParser). */
const REQUIRED_COLUMNS: Partial<Record<ImportReportKind, readonly string[]>> = {
  PRODUCTS: [
    'EAN',
    'ITM8',
    'NAZWA',
    'MARKA',
    'DOST',
    'NOM',
    'CENA_SPRZEDAZY',
    'CENA_ZAKUPU',
    'STAN',
    'ROTACJA',
    'COLI',
    'AKTYWNY',
    'PRIO',
    'VAT',
    'JED',
    'WAGA',
    'DLV',
    'RP',
    'KAUCJA',
    'REFERENCJA',
    'KASA',
    'TYP',
    'RODZ',
    'JMSP',
    'CN',
    'CENA_CAD',
  ],
}

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
    (newest, item) =>
      item.reportGeneratedAt && (!newest || item.reportGeneratedAt > newest) ? item.reportGeneratedAt : newest,
    null,
  )
}

/** Chwila ostatniego wgrania raportu; plik zmieniony wcześniej „Odśwież dane” pomija. */
function lastImportedAt(imports: { importedAt: string }[] | undefined) {
  return imports?.reduce((latest, item) => Math.max(latest, new Date(item.importedAt).getTime()), 0) ?? 0
}

/** Wynik „Odśwież dane” dla jednego raportu, pokazywany pod polem wgrywania. */
type RefreshNote = { tone: 'muted' | 'warning'; text: string }

function StoreImports({ store }: { store: StoreSummary }) {
  const canImport = useCan('IMPORTS', 'EDIT')
  const canViewDocuments = useCan('DOCUMENTS')
  const isOwner = useMe().data?.owner === true
  const guides = useImportGuides()
  const products = useProductImports(store.id)
  const documents = useDocumentImports(store.id)
  const suppliers = useSupplierImports(store.id)
  const sales = useSalesImports(store.id)
  const nomenclatures = useNomenclatureImports(store.id)
  const promotions = usePromotionImports(store.id)
  const productCodes = useProductCodeImports(store.id)
  const uploads = {
    PRODUCTS: useImportProducts(store.id),
    PRODUCT_CODES: useImportProductCodes(store.id),
    PROMOTIONS: useImportPromotions(store.id),
    SALES: useImportSales(store.id),
    DOCUMENTS: useImportDocuments(store.id),
    SUPPLIERS: useImportSuppliers(store.id),
    NOMENCLATURES: useImportNomenclatures(store.id),
  }
  const [eanChangesOf, setEanChangesOf] = useState<ProductImport | null>(null)
  const [sourcesOpen, setSourcesOpen] = useState(false)
  const [refreshing, setRefreshing] = useState<DailyReport | 'access' | null>(null)
  const [refreshNotes, setRefreshNotes] = useState<Partial<Record<DailyReport, RefreshNote>>>({})
  const lastProductImport = products.data?.imports[0]

  const guideOf = (kind: ImportReportKind) => guides.data?.find((guide) => guide.kind === kind)
  const lastImported: Record<DailyReport, number> = {
    PRODUCTS: lastImportedAt(products.data?.imports),
    PRODUCT_CODES: lastImportedAt(productCodes.data?.imports),
    PROMOTIONS: lastImportedAt(promotions.data?.imports),
    SALES: lastImportedAt(sales.data?.imports),
    DOCUMENTS: lastImportedAt(documents.data),
  }

  /**
   * Dla każdego raportu z wybranym folderem: najnowszy pasujący plik, o ile jest nowszy niż ostatnie wgranie, idzie
   * po kolei przez to samo wgrywanie co upuszczony plik (wynik i błąd pokazuje karta raportu).
   */
  const refresh = async () => {
    setRefreshing('access')
    setRefreshNotes({})
    const masks = readMasks(store.id)
    const notes: Partial<Record<DailyReport, RefreshNote>> = {}
    const ready: DailyReport[] = []
    // Zgody najpierw, wszystkie zaraz po kliknięciu: okno zgody działa tylko bezpośrednio po geście użytkownika.
    for (const kind of DAILY_REPORTS) {
      const key = sourceFolderKey(store.id, kind)
      if ((await folderAccess(key, 'read')) === 'missing') continue
      if (await ensureFolderAccess(key, 'read')) ready.push(kind)
      else notes[kind] = { tone: 'warning', text: 'Brak zgody na odczyt folderu. Kliknij „Odśwież dane” i zezwól.' }
    }
    if (ready.length === 0 && Object.keys(notes).length === 0) {
      setRefreshing(null)
      setSourcesOpen(true)
      toast.info('Wskaż foldery, w których zapisujesz raporty')
      return
    }

    let imported = 0
    let failed = 0
    for (const kind of ready) {
      setRefreshing(kind)
      try {
        const file = await newestReportFile(store.id, kind, masks[kind] ?? '')
        if (!file) {
          notes[kind] = { tone: 'warning', text: 'W folderze nie ma pliku raportu pasującego do ustawień.' }
        } else if (file.lastModified <= lastImported[kind]) {
          notes[kind] = {
            tone: 'muted',
            text: `Bez nowego pliku: ${file.name} z ${dateTimeFormat.format(new Date(file.lastModified))} jest już wgrany.`,
          }
        } else {
          try {
            await uploads[kind].mutateAsync(file)
            imported++
          } catch {
            failed++
          }
        }
      } catch (error) {
        notes[kind] = {
          tone: 'warning',
          text: error instanceof ApiError ? error.message : 'Nie udało się odczytać folderu.',
        }
      }
      setRefreshNotes({ ...notes })
    }
    setRefreshNotes(notes)
    setRefreshing(null)

    const skipped = Object.values(notes).filter((note) => note.tone === 'warning').length
    if (failed > 0 || skipped > 0) {
      toast.warning(
        `${imported > 0 ? `Wgrano raporty: ${imported}. ` : ''}Część raportów wymaga uwagi - sprawdź karty.`,
      )
    } else if (imported > 0) {
      toast.success(imported === 1 ? 'Wgrano nowy raport' : `Wgrano nowe raporty: ${imported}`)
    } else {
      toast.success('Wszystkie raporty są już wgrane')
    }
  }

  const cardProps = (kind: ImportReportKind) => ({
    kind,
    guide: guideOf(kind),
    canEditGuide: isOwner,
    refreshNote: kind in lastImported ? refreshNotes[kind as DailyReport] : undefined,
    refreshing: refreshing === kind,
  })

  return (
    <>
      <PageHeader
        title="Import danych"
        description={`Raporty z systemu sklepu ${storeLabel(store)}. Bazę danych, multikody, promocje, sprzedaż i dokumenty wgrywaj codziennie, listy dostawców i nomenklatur wtedy, gdy się zmienią.`}
        actions={
          canImport && (
            <>
              {folderSavingSupported && (
                <Button
                  size="lg"
                  loading={refreshing !== null}
                  onClick={refresh}
                  title="Wgrywa najnowsze pliki z folderów wskazanych w ustawieniach"
                >
                  {refreshing === null && <RefreshCwIcon data-icon="inline-start" />}
                  {refreshing && refreshing !== 'access' ? `Wgrywanie: ${REPORT_TITLES[refreshing]}` : 'Odśwież dane'}
                </Button>
              )}
              <Button
                variant="outline"
                size="icon-lg"
                aria-label="Ustawienia importu: foldery raportów"
                title="Ustawienia importu"
                onClick={() => setSourcesOpen(true)}
              >
                <SettingsIcon />
              </Button>
            </>
          )
        }
      />

      <div className="mb-10 grid auto-rows-fr gap-4 lg:grid-cols-2 xl:grid-cols-3">
        <ReportCard
          {...cardProps('PRODUCTS')}
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
          {canImport ? (
            <ReportUpload
              upload={uploads.PRODUCTS}
              accept={XLSX_EXTENSIONS}
              result={(result) => <ProductImportResult result={result} onShowEanChanges={setEanChangesOf} />}
            />
          ) : (
            <ReadOnlyNote />
          )}
        </ReportCard>

        <ReportCard
          {...cardProps('PRODUCT_CODES')}
          generatedAt={productCodes.isPending ? undefined : (newestReport(productCodes.data?.imports) ?? null)}
          meta={
            productCodes.data && (
              <>
                W bazie {numberFormat.format(productCodes.data.codeCount)} kodów dla{' '}
                {numberFormat.format(productCodes.data.articleCount)} artykułów
              </>
            )
          }
        >
          {canImport ? (
            <ReportUpload
              upload={uploads.PRODUCT_CODES}
              accept={XLSX_EXTENSIONS}
              result={(result) => <ProductCodeImportResult result={result} />}
            />
          ) : (
            <ReadOnlyNote />
          )}
        </ReportCard>

        <ReportCard
          {...cardProps('PROMOTIONS')}
          generatedAt={promotions.isPending ? undefined : (newestReport(promotions.data?.imports) ?? null)}
          meta={
            promotions.data && (
              <>W bazie {numberFormat.format(promotions.data.activeCount)} promocji trwających i zaplanowanych</>
            )
          }
        >
          {canImport ? (
            <ReportUpload
              upload={uploads.PROMOTIONS}
              accept={PDF_EXTENSIONS}
              pdf
              result={(result) => <PromotionImportResult result={result} />}
            />
          ) : (
            <ReadOnlyNote />
          )}
        </ReportCard>

        <ReportCard
          {...cardProps('SALES')}
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
          {canImport ? (
            <ReportUpload
              upload={uploads.SALES}
              accept={XLSX_EXTENSIONS}
              result={(result) => <SalesImportResult result={result} />}
            />
          ) : (
            <ReadOnlyNote />
          )}
        </ReportCard>

        <ReportCard
          {...cardProps('DOCUMENTS')}
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
          {canImport ? (
            <ReportUpload
              upload={uploads.DOCUMENTS}
              accept={XLSX_EXTENSIONS}
              result={(result) => <DocumentImportResult result={result} />}
            />
          ) : (
            <ReadOnlyNote />
          )}
        </ReportCard>

        <ReportCard
          {...cardProps('SUPPLIERS')}
          meta={
            suppliers.data && (
              <>
                W bazie {numberFormat.format(suppliers.data.supplierCount)} dostawców
                {suppliers.data.manualCount > 0 && (
                  <>, w tym {numberFormat.format(suppliers.data.manualCount)} dodanych ręcznie</>
                )}
                {newestReport(suppliers.data.imports) && (
                  <> · lista z {formatReportDate(newestReport(suppliers.data.imports)!)}</>
                )}
              </>
            )
          }
        >
          {canImport ? (
            <ReportUpload
              upload={uploads.SUPPLIERS}
              accept={XLSX_EXTENSIONS}
              result={(result) => <SupplierImportResult result={result} />}
            />
          ) : (
            <ReadOnlyNote />
          )}
        </ReportCard>

        <ReportCard
          {...cardProps('NOMENCLATURES')}
          meta={
            nomenclatures.data && (
              <>
                W bazie {numberFormat.format(nomenclatures.data.regionCount)} rejonów,{' '}
                {numberFormat.format(nomenclatures.data.familyCount)} rodzin,{' '}
                {numberFormat.format(nomenclatures.data.subfamilyCount)} podrodzin
                {newestReport(nomenclatures.data.imports) && (
                  <> · lista z {formatReportDate(newestReport(nomenclatures.data.imports)!)}</>
                )}
              </>
            )
          }
        >
          {canImport ? (
            <ReportUpload
              upload={uploads.NOMENCLATURES}
              accept={PDF_EXTENSIONS}
              pdf
              result={(result) => <NomenclatureImportResult result={result} />}
            />
          ) : (
            <ReadOnlyNote />
          )}
        </ReportCard>
      </div>

      <section aria-labelledby="import-history">
        <h2 id="import-history" className="mb-3 text-base font-semibold">
          Historia importów
        </h2>
        {products.isError &&
        documents.isError &&
        suppliers.isError &&
        sales.isError &&
        nomenclatures.isError &&
        promotions.isError &&
        productCodes.isError ? (
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
                promotions.refetch()
                productCodes.refetch()
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
            promotions={promotions.data?.imports}
            productCodes={productCodes.data?.imports}
            loading={
              products.isPending ||
              documents.isPending ||
              suppliers.isPending ||
              sales.isPending ||
              nomenclatures.isPending ||
              promotions.isPending ||
              productCodes.isPending
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

      {canImport && (
        <ImportSourcesDialog
          open={sourcesOpen}
          onOpenChange={setSourcesOpen}
          storeId={store.id}
          guides={guides.data ?? []}
        />
      )}
    </>
  )
}

/**
 * Kafel jednego raportu: tytuł z okienkiem opisu (znak zapytania), aktualność danych, stan w bazie i pole do
 * wgrania. Listy dostawców i nomenklatur wgrywa się tylko na życzenie, więc nie mają znacznika aktualności.
 */
function ReportCard({
  kind,
  guide,
  canEditGuide,
  generatedAt,
  meta,
  refreshNote,
  refreshing,
  children,
}: {
  kind: ImportReportKind
  guide: ImportGuide | undefined
  canEditGuide: boolean
  /** {@code undefined} w trakcie wczytywania historii i przy raportach bez znacznika aktualności. */
  generatedAt?: string | null
  meta?: ReactNode
  refreshNote?: RefreshNote
  refreshing: boolean
  children: ReactNode
}) {
  const id = `report-${kind.toLowerCase()}`
  return (
    <Card aria-labelledby={id} aria-busy={refreshing || undefined} className="gap-4 p-5">
      <div className="grid flex-1 content-start gap-2">
        <div className="flex items-center gap-1">
          <h2 id={id} className="text-base font-semibold">
            {REPORT_TITLES[kind]}
          </h2>
          <ReportHelp
            kind={kind}
            title={REPORT_TITLES[kind]}
            description={REPORT_DESCRIPTIONS[kind]}
            requiredColumns={REQUIRED_COLUMNS[kind]}
            guide={guide}
            canEdit={canEditGuide}
          />
        </div>
        {generatedAt !== undefined && <ReportFreshness generatedAt={generatedAt} className="justify-self-start" />}
        {meta && <p className="text-sm text-muted-foreground tabular-nums">{meta}</p>}
      </div>
      {children}
      {refreshNote && (
        <p
          className={
            refreshNote.tone === 'warning'
              ? 'flex gap-1.5 text-sm text-amber-800 dark:text-amber-300'
              : 'text-sm text-muted-foreground'
          }
        >
          {refreshNote.tone === 'warning' && <CircleAlertIcon aria-hidden className="mt-0.5 size-4 shrink-0" />}
          {refreshNote.text}
        </p>
      )}
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
          ? `${fileName} nie jest plikiem PDF. Wydrukuj raport z systemu sklepu do PDF.`
          : `${fileName} nie jest plikiem .xlsx ani .xlsm. Plik .xls otwórz w Excelu i zapisz jako .xlsx.`}
      </AlertDescription>
    </Alert>
  )
}

/**
 * Pole wgrywania raportu z wynikiem albo błędem. Wgranie należy do strony, więc „Odśwież dane” wgrywa przez nie
 * pliki z folderów i wynik pokazuje się tu tak samo jak po upuszczeniu pliku.
 */
function ReportUpload<T>({
  upload,
  accept,
  pdf,
  result,
}: {
  upload: UseMutationResult<T, Error, File>
  accept: string[]
  pdf?: boolean
  result: (data: T) => ReactNode
}) {
  const [rejectedFile, setRejectedFile] = useState<string | null>(null)

  return (
    <div className="grid gap-3">
      <FileDropZone
        className="h-32 min-h-0 py-5"
        accept={accept}
        pendingFile={upload.isPending ? upload.variables : undefined}
        onFile={(file) => {
          setRejectedFile(null)
          upload.mutate(file)
        }}
        onReject={(fileName) => {
          upload.reset()
          setRejectedFile(fileName)
        }}
        title="Przeciągnij plik lub wybierz"
      />

      <div aria-live="polite">
        {rejectedFile && <RejectedFile fileName={rejectedFile} pdf={pdf} />}
        {upload.isError && <ImportError error={upload.error} />}
        {upload.isSuccess && result(upload.data)}
      </div>
    </div>
  )
}

function ProductImportResult({
  result,
  onShowEanChanges,
}: {
  result: ProductImport
  onShowEanChanges: ShowEanChanges
}) {
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
          {result.draftsMatchedCount > 0 && (
            <ResultCount label="Nowe artykuły z CADBEZP" value={result.draftsMatchedCount} />
          )}
          {result.draftsRemovedCount > 0 && (
            <ResultCount label="Usunięte z CADBEZP (brak w raporcie)" value={result.draftsRemovedCount} />
          )}
        </dl>
        {eanChanges > 0 && (
          <Button
            variant="link"
            className="mt-1 h-auto justify-self-start p-0"
            onClick={() => onShowEanChanges(result)}
          >
            Pokaż zmiany EAN
          </Button>
        )}
      </AlertDescription>
    </Alert>
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

function PromotionImportResult({ result }: { result: PromotionImport }) {
  return (
    <Alert className="animate-in fade-in-0 slide-in-from-top-1 duration-200 motion-reduce:animate-none">
      <CircleCheckIcon className="text-emerald-600 dark:text-emerald-400" />
      <AlertTitle>
        Zaimportowano {numberFormat.format(result.totalRows)} promocji
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

function ProductCodeImportResult({ result }: { result: ProductCodeImport }) {
  return (
    <Alert className="animate-in fade-in-0 slide-in-from-top-1 duration-200 motion-reduce:animate-none">
      <CircleCheckIcon className="text-emerald-600 dark:text-emerald-400" />
      <AlertTitle>
        Zaimportowano {numberFormat.format(result.totalRows)} kodów
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
  | { kind: 'promotions'; importedAt: string; item: PromotionImport }
  | { kind: 'productCodes'; importedAt: string; item: ProductCodeImport }

const REPORT_NAMES: Record<HistoryRow['kind'], string> = {
  products: 'Baza danych',
  documents: 'Dokumenty',
  suppliers: 'Lista dostawców',
  sales: 'Sprzedaż',
  nomenclatures: 'Lista nomenklatur',
  promotions: 'Lista promocji',
  productCodes: 'Multikody',
}

/** Wszystkie wgrania raportów razem, od najnowszego. */
function ImportHistory({
  products,
  documents,
  suppliers,
  sales,
  nomenclatures,
  promotions,
  productCodes,
  loading,
  onShowEanChanges,
}: {
  products?: ProductImport[]
  documents?: DocumentImport[]
  suppliers?: SupplierImport[]
  sales?: SalesImport[]
  nomenclatures?: NomenclatureImport[]
  promotions?: PromotionImport[]
  productCodes?: ProductCodeImport[]
  loading: boolean
  onShowEanChanges: ShowEanChanges
}) {
  const rows: HistoryRow[] = [
    ...(products ?? []).map((item) => ({ kind: 'products' as const, importedAt: item.importedAt, item })),
    ...(documents ?? []).map((item) => ({ kind: 'documents' as const, importedAt: item.importedAt, item })),
    ...(suppliers ?? []).map((item) => ({ kind: 'suppliers' as const, importedAt: item.importedAt, item })),
    ...(sales ?? []).map((item) => ({ kind: 'sales' as const, importedAt: item.importedAt, item })),
    ...(nomenclatures ?? []).map((item) => ({ kind: 'nomenclatures' as const, importedAt: item.importedAt, item })),
    ...(promotions ?? []).map((item) => ({ kind: 'promotions' as const, importedAt: item.importedAt, item })),
    ...(productCodes ?? []).map((item) => ({ kind: 'productCodes' as const, importedAt: item.importedAt, item })),
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
                <TableCell className="whitespace-normal text-muted-foreground">{historySummary(row)}</TableCell>
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

function historySummary(row: HistoryRow) {
  switch (row.kind) {
    case 'products':
      return productSummary(row.item)
    case 'documents':
      return documentSummary(row.item)
    case 'suppliers':
      return supplierSummary(row.item)
    case 'nomenclatures':
      return nomenclatureSummary(row.item)
    case 'promotions':
      return mergeSummary(row.item, 'promocji')
    case 'productCodes':
      return mergeSummary(row.item, 'kodów')
    case 'sales':
      return salesSummary(row.item)
  }
}

/** Import scalany z bazą: ile pozycji w pliku, nowych, zmienionych i brakujących. */
function mergeSummary(item: PromotionImport | ProductCodeImport, unit: string) {
  return [
    `${numberFormat.format(item.totalRows)} ${unit}`,
    `${numberFormat.format(item.createdCount)} nowych`,
    `${numberFormat.format(item.updatedCount)} zmienionych`,
    `${numberFormat.format(item.missingCount)} brak w pliku`,
  ].join(' · ')
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
                      <div className="font-medium">
                        {change.name ?? 'Bez nazwy'}
                        <ArticleStatsButton article={change} className="ml-1" />
                      </div>
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
