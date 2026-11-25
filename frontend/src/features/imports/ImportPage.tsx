import { useEffect, useRef, useState, type ReactNode } from 'react'
import type { UseMutationResult } from '@tanstack/react-query'
import {
  ArrowRightIcon,
  CircleAlertIcon,
  CircleCheckIcon,
  ClipboardCopyIcon,
  RefreshCwIcon,
  SettingsIcon,
  XIcon,
} from 'lucide-react'
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
  copyReportMacro,
  useDocumentImports,
  useEanChanges,
  useImportDocuments,
  useImportGuides,
  useImportHistory,
  useImportNomenclatures,
  useImportProductCodes,
  useImportProducts,
  useImportPromotions,
  useImportSales,
  useImportSuppliers,
  useAgentReportFiles,
  useNomenclatureImports,
  useProductCodeImports,
  useProductImports,
  usePromotionImports,
  useSalesImports,
  useSupplierImports,
} from '@/features/imports/api'
import { ImportSourcesDialog } from '@/features/imports/ImportSourcesDialog'
import { AgentReportFiles, agentNote } from '@/features/imports/AgentReportFiles'
import { CadTopsCard } from '@/features/imports/CadTopsCard'
import {
  DAILY_REPORTS,
  MACRO_REPORTS,
  newReportFiles,
  PDF_EXTENSIONS,
  REPORT_TITLES,
  sourceFolderKey,
  XLSX_EXTENSIONS,
  type DailyReport,
  type MacroReport,
} from '@/features/imports/import-sources'
import { ReportHelp } from '@/features/imports/ReportHelp'
import { useImportSettings } from '@/features/imports/use-import-settings'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { useImportProgress, type ImportProgress } from '@/features/imports/report-upload'
import { useCadencier, useImportCadencier } from '@/features/sales/api'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { TOOL_ROUTES } from '@/features/tools/categories'
import { ApiError } from '@/lib/api'
import { parseIsoDate } from '@/lib/dates'
import { formatReportDate } from '@/lib/report-freshness'
import { ensureFolderAccess, folderAccess, folderSavingSupported } from '@/lib/saved-folders'
import { useAgentState } from '@/lib/store-agent'
import { useLoadMore } from '@/lib/use-load-more'
import type {
  CadencierImport,
  DocumentImport,
  EanChange,
  ImportGuide,
  ImportHistoryEntry,
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
    'Dostawcy z systemu sklepu z numerami, NIP-em i adresem (np. DOSTAWCY.xlsx). Wgrywaj ją codziennie, żeby nowy dostawca był od razu. Dostawcy, których zabraknie w nowym pliku, zostają w bazie.',
  CADENCIER:
    'Artykuły z formularza zamówień do bazy, kolumny ITM i EAN (np. obecne w cad.xlsx). Nowa lista zastępuje poprzednią w całości: raporty zakupu bazowego poza bazą, marż i zerówek, wprowadzanie danych (niebieska cena) i lista towarów biorą zawsze najnowszą.',
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
  const cadencier = useCadencier(store.id)
  const importSettings = useImportSettings(store.id)
  const uploads = {
    PRODUCTS: useImportProducts(store.id),
    PRODUCT_CODES: useImportProductCodes(store.id),
    PROMOTIONS: useImportPromotions(store.id),
    SALES: useImportSales(store.id),
    DOCUMENTS: useImportDocuments(store.id),
    CADENCIER: useImportCadencier(store.id),
    SUPPLIERS: useImportSuppliers(store.id),
    NOMENCLATURES: useImportNomenclatures(store.id),
  }
  const [eanChangesOf, setEanChangesOf] = useState<ProductImport | null>(null)
  const [sourcesOpen, setSourcesOpen] = useState(false)
  const [refreshing, setRefreshing] = useState<DailyReport | 'access' | null>(null)
  const [refreshNotes, setRefreshNotes] = useState<Partial<Record<DailyReport, RefreshNote>>>({})
  const lastProductImport = products.data?.imports[0]
  // Raporty, których folder pilnuje agent sklepu: wgrywa je sam, a „Odśwież dane” je pomija.
  const agent = useAgentState()
  const agentKinds = DAILY_REPORTS.filter(
    (kind) => agent?.storeId === store.id && agent.status.active && agent.status.watches.includes(`IMPORT_${kind}`),
  )
  const agentFiles = useAgentReportFiles(store.id, agentKinds.length > 0)
  const lastAgentFile = (kind: DailyReport) => agentFiles.data?.find((file) => file.kind === kind)

  const guideOf = (kind: ImportReportKind) => guides.data?.find((guide) => guide.kind === kind)
  const lastImported: Record<DailyReport, number> = {
    PRODUCTS: lastImportedAt(products.data?.imports),
    PRODUCT_CODES: lastImportedAt(productCodes.data?.imports),
    PROMOTIONS: lastImportedAt(promotions.data?.imports),
    SALES: lastImportedAt(sales.data?.imports),
    DOCUMENTS: lastImportedAt(documents.data),
    CADENCIER: lastImportedAt(cadencier.data?.imports),
    SUPPLIERS: lastImportedAt(suppliers.data?.imports),
  }

  /**
   * Dla każdego raportu z wybranym folderem: najnowszy pasujący plik, o ile jest nowszy niż ostatnie wgranie, idzie
   * po kolei przez to samo wgrywanie co upuszczony plik (wynik i błąd pokazuje karta raportu).
   */
  const refresh = async () => {
    if (!importSettings.loaded) {
      toast.info('Wczytuję ustawienia importu, spróbuj za chwilę')
      return
    }
    setRefreshing('access')
    setRefreshNotes({})
    try {
      await refreshReports()
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się odświeżyć danych')
    } finally {
      setRefreshing(null)
    }
  }

  const refreshReports = async () => {
    const { masks } = importSettings.settings
    const notes: Partial<Record<DailyReport, RefreshNote>> = {}
    const ready: DailyReport[] = []
    // Zgody najpierw, wszystkie zaraz po kliknięciu: okno zgody działa tylko bezpośrednio po geście użytkownika,
    // więc stan folderów sprawdzamy naraz. Błąd jednego folderu (zawieszony dysk, IndexedDB) nie zatrzymuje reszty.
    const kinds = DAILY_REPORTS.filter((kind) => !agentKinds.includes(kind))
    if (kinds.length === 0) {
      toast.info('Wszystkie raporty wgrywa agent sklepu', {
        description: 'Agent sam wgrywa nowe pliki z folderów raportów. Wynik jest w tabeli pod raportami.',
      })
      return
    }
    const states = await Promise.all(kinds.map((kind) => folderAccess(sourceFolderKey(store.id, kind), 'read')))
    for (const [index, kind] of kinds.entries()) {
      if (states[index] === 'missing') continue
      try {
        if (states[index] === 'granted' || (await ensureFolderAccess(sourceFolderKey(store.id, kind), 'read'))) {
          ready.push(kind)
        } else {
          notes[kind] = { tone: 'warning', text: 'Brak zgody na odczyt folderu. Kliknij „Odśwież dane” i zezwól.' }
        }
      } catch (error) {
        notes[kind] = {
          tone: 'warning',
          text: error instanceof ApiError ? error.message : 'Nie udało się sprawdzić zgody na folder.',
        }
      }
    }
    setRefreshNotes({ ...notes })
    if (ready.length === 0 && Object.keys(notes).length === 0) {
      setSourcesOpen(true)
      toast.info('Wskaż foldery, w których zapisujesz raporty')
      return
    }

    let imported = 0
    let failed = 0
    for (const kind of ready) {
      setRefreshing(kind)
      try {
        const history = kind === 'SALES' ? sales.data?.imports : kind === 'DOCUMENTS' ? documents.data : undefined
        const { newest, files } = await newReportFiles(
          store.id,
          kind,
          masks[kind] ?? '',
          lastImported[kind],
          history ?? [],
        )
        if (!newest) {
          notes[kind] = { tone: 'warning', text: 'W folderze nie ma pliku raportu pasującego do ustawień.' }
        } else if (files.length === 0) {
          notes[kind] = {
            tone: 'muted',
            text: `Bez nowego pliku: ${newest.name} z ${dateTimeFormat.format(new Date(newest.lastModified))} jest już wgrany.`,
          }
        } else {
          // Zaległość zapisana przez makro w częściach: od najstarszego pliku, błąd zatrzymuje resztę.
          for (const file of files) {
            try {
              await uploads[kind].mutateAsync(file)
              imported++
            } catch {
              failed++
              break
            }
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

  // Makro na wszystkie raporty z zapisaną ścieżką (ścieżki sklepu z ustawień importu).
  // Jeden raport (przycisk na karcie) albo wszystkie z ustawieniami (nagłówek strony)
  const copyMacros = async (kinds: readonly MacroReport[] = MACRO_REPORTS) => {
    // Makro składa serwer (to samo dostaje agent sklepu): ścieżki z ustawień importu i zaległe okresy.
    let result
    try {
      result = await copyReportMacro(store.id, kinds)
    } catch {
      toast.error('Nie udało się skopiować makra')
      return
    }
    if (result.reports.length === 0) {
      toast.info(
        kinds.length === 1
          ? `${REPORT_TITLES[kinds[0]]}: uzupełnij ustawienia makra`
          : 'Ustaw ścieżki zapisu z Mercalys',
        {
          description:
            kinds.length === 1
              ? 'W ustawieniach importu wpisz przy tym raporcie ścieżkę zapisu (i numery z Mercalys, gdy są potrzebne).'
              : 'W ustawieniach importu wpisz przy raportach, gdzie makro ma je zapisać.',
        },
      )
      setSourcesOpen(true)
      return
    }
    const skipped = kinds.length - result.reports.length
    toast.success(`Skopiowano makro: ${result.reports.map((kind) => REPORT_TITLES[kind]).join(', ')}`, {
      description: `Naciśnij Alt+E (odtwarzacz od wersji ${result.playerVersion}).${
        skipped > 0 ? ` Bez ustawień makra: ${skipped}.` : ''
      }`,
    })
  }

  const cardProps = (kind: ImportReportKind) => ({
    kind,
    guide: guideOf(kind),
    canEditGuide: isOwner,
    refreshNote: !(kind in lastImported)
      ? undefined
      : agentKinds.includes(kind as DailyReport)
        ? agentNote(lastAgentFile(kind as DailyReport))
        : refreshNotes[kind as DailyReport],
    refreshing: refreshing === kind,
    onMercalys:
      canImport && (MACRO_REPORTS as readonly string[]).includes(kind)
        ? () => void copyMacros([kind as MacroReport])
        : undefined,
  })

  return (
    <>
      <PageHeader
        title="Import danych"
        description={`Raporty z systemu sklepu ${storeLabel(store)}. Bazę danych, multikody, promocje, sprzedaż, dokumenty, listę cadencier i listę dostawców wgrywaj codziennie, listę nomenklatur wtedy, gdy się zmieni.`}
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
                size="lg"
                onClick={() => void copyMacros()}
                title="Jedno makro do schowka (Alt+E): po kolei każdy raport z Mercalys zapisany w swoim folderze, potem „Odśwież dane”"
              >
                <ClipboardCopyIcon data-icon="inline-start" />
                Zaktualizuj wszystkie pliki
              </Button>
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
              progress={<ImportProgressBar storeId={store.id} kind="PRODUCTS" />}
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
              progress={<ImportProgressBar storeId={store.id} kind="PRODUCT_CODES" />}
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
              progress={<ImportProgressBar storeId={store.id} kind="PROMOTIONS" />}
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
              progress={<ImportProgressBar storeId={store.id} kind="SALES" />}
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
              progress={<ImportProgressBar storeId={store.id} kind="DOCUMENTS" />}
              accept={XLSX_EXTENSIONS}
              result={(result) => <DocumentImportResult result={result} />}
            />
          ) : (
            <ReadOnlyNote />
          )}
        </ReportCard>

        <ReportCard
          {...cardProps('CADENCIER')}
          generatedAt={cadencier.isPending ? undefined : (newestReport(cadencier.data?.imports) ?? null)}
          meta={
            cadencier.data &&
            (cadencier.data.imports.length ? (
              <>W bazie {numberFormat.format(cadencier.data.itemCount)} artykułów z cadencier</>
            ) : (
              <>Lista nie jest jeszcze wgrana</>
            ))
          }
        >
          {canImport ? (
            <ReportUpload
              upload={uploads.CADENCIER}
              progress={<ImportProgressBar storeId={store.id} kind="CADENCIER" />}
              accept={XLSX_EXTENSIONS}
              result={(result) => <CadencierImportResult result={result} />}
            />
          ) : (
            <ReadOnlyNote />
          )}
        </ReportCard>

        <ReportCard
          {...cardProps('SUPPLIERS')}
          generatedAt={suppliers.isPending ? undefined : (newestReport(suppliers.data?.imports) ?? null)}
          meta={
            suppliers.data && (
              <>
                W bazie {numberFormat.format(suppliers.data.supplierCount)} dostawców
                {suppliers.data.manualCount > 0 && (
                  <>, w tym {numberFormat.format(suppliers.data.manualCount)} dodanych ręcznie</>
                )}
              </>
            )
          }
        >
          {canImport ? (
            <ReportUpload
              upload={uploads.SUPPLIERS}
              progress={<ImportProgressBar storeId={store.id} kind="SUPPLIERS" />}
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
              progress={<ImportProgressBar storeId={store.id} kind="NOMENCLATURES" />}
              accept={PDF_EXTENSIONS}
              pdf
              result={(result) => <NomenclatureImportResult result={result} />}
            />
          ) : (
            <ReadOnlyNote />
          )}
        </ReportCard>
      </div>

      <CadTopsCard storeId={store.id} canImport={canImport} />

      {agentKinds.length > 0 && agentFiles.data && <AgentReportFiles files={agentFiles.data} />}

      <section aria-labelledby="import-history">
        <h2 id="import-history" className="mb-3 text-base font-semibold">
          Historia importów
        </h2>
        <ImportHistory storeId={store.id} onShowEanChanges={setEanChangesOf} />
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
 * wgrania. Listę nomenklatur wgrywa się tylko na życzenie, więc nie ma znacznika aktualności.
 */
function ReportCard({
  kind,
  guide,
  canEditGuide,
  generatedAt,
  meta,
  refreshNote,
  refreshing,
  onMercalys,
  children,
}: {
  kind: ImportReportKind
  guide: ImportGuide | undefined
  canEditGuide: boolean
  /** Makro do schowka: ten raport z Mercalys zapisany w jego folderze (tylko raporty z makrem). */
  onMercalys?: () => void
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
          {onMercalys && (
            <Button
              variant="outline"
              size="sm"
              className="ml-auto"
              onClick={onMercalys}
              title="Makro do schowka (Alt+E): ten raport z Mercalys zapisany w swoim folderze"
            >
              <ClipboardCopyIcon data-icon="inline-start" />
              Zaktualizuj plik
            </Button>
          )}
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
  progress,
  result,
}: {
  upload: UseMutationResult<T, Error, File>
  accept: string[]
  pdf?: boolean
  /** Pokazywany w trakcie wgrania (import w tle z postępem). */
  progress?: ReactNode
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
        {upload.isPending && progress}
        {rejectedFile && <RejectedFile fileName={rejectedFile} pdf={pdf} />}
        {upload.isError && <ImportError error={upload.error} />}
        {upload.isSuccess && result(upload.data)}
      </div>
    </div>
  )
}

const IMPORT_PHASES: Record<ImportProgress['phase'], string> = {
  UPLOADING: 'Wysyłanie pliku',
  PROCESSING: 'Odczyt i zapis na serwerze',
  WAITING: 'Czeka na koniec innych importów',
  READING: 'Odczyt pliku',
  SAVING: 'Zapis do kartoteki',
  FINISHING: 'Kończenie importu',
}

/** Kolejne etapy: baza danych idzie w tle i podaje własne etapy, pozostałe raporty to wysłanie i przetworzenie. */
const PRODUCT_STEPS: ImportProgress['phase'][] = ['UPLOADING', 'READING', 'SAVING', 'FINISHING']
const REPORT_STEPS: ImportProgress['phase'][] = ['UPLOADING', 'PROCESSING']

/** Czas od początku etapu, odświeżany co sekundę (etap bez procentów). */
function useElapsedSeconds(since: number | undefined) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (since === undefined) return
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [since])
  return since === undefined ? 0 : Math.max(0, Math.floor((now - since) / 1000))
}

function elapsedLabel(seconds: number) {
  const minutes = Math.floor(seconds / 60)
  return minutes > 0 ? `${minutes} min ${String(seconds % 60).padStart(2, '0')} s` : `${seconds} s`
}

/** Postęp wgrania raportu: krok, nazwa etapu, procent (albo czas przy przetwarzaniu bez procentów) i pasek. */
function ImportProgressBar({ storeId, kind }: { storeId: string; kind: ImportReportKind }) {
  const job = useImportProgress(storeId, kind)
  const processing = job?.phase === 'PROCESSING'
  const elapsed = useElapsedSeconds(processing ? job.since : undefined)
  if (!job) return null
  const steps = kind === 'PRODUCTS' ? PRODUCT_STEPS : REPORT_STEPS
  const step = steps.indexOf(job.phase === 'WAITING' ? 'READING' : job.phase) + 1
  return (
    <div className="grid gap-1.5 text-sm">
      <div className="flex items-baseline justify-between gap-3">
        <span className="text-muted-foreground">
          <span className="font-medium text-foreground">
            Krok {step} z {steps.length}:
          </span>{' '}
          {IMPORT_PHASES[job.phase]}
          {job.phase === 'SAVING' && (
            <>
              {' '}
              · {numberFormat.format(job.processedRows)} z {numberFormat.format(job.totalRows)} wierszy
            </>
          )}
        </span>
        <span className="font-medium tabular-nums">{processing ? elapsedLabel(elapsed) : `${job.percent}%`}</span>
      </div>
      <div
        role="progressbar"
        aria-label={`Postęp importu: ${IMPORT_PHASES[job.phase]}`}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={processing ? undefined : job.percent}
        className="h-2 overflow-hidden rounded-full bg-muted"
      >
        {processing ? (
          // Serwer nie podaje postępu: pełny pasek z przesuwającym się refleksem mówi, że praca trwa.
          <div className="loading-shimmer h-full rounded-full bg-primary/40!" />
        ) : (
          <div
            className="h-full origin-left rounded-full bg-primary transition-transform duration-500 ease-out motion-reduce:transition-none"
            style={{ transform: `scaleX(${job.percent / 100})` }}
          />
        )}
      </div>
      {processing && (
        <p className="text-xs text-muted-foreground">Plik dotarł na serwer. Duży raport zapisuje się do kilku minut.</p>
      )}
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
            <ResultCount label="Nowe artykuły z wprowadzania danych" value={result.draftsMatchedCount} />
          )}
          {result.draftsRemovedCount > 0 && (
            <ResultCount label="Nowe artykuły, których nie ma w raporcie" value={result.draftsRemovedCount} />
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

function CadencierImportResult({ result }: { result: CadencierImport }) {
  return (
    <Alert className="animate-in fade-in-0 slide-in-from-top-1 duration-200 motion-reduce:animate-none">
      <CircleCheckIcon className="text-emerald-600 dark:text-emerald-400" />
      <AlertTitle>
        Wgrano {numberFormat.format(result.totalRows)} artykułów
        {result.reportGeneratedAt && <> z listy {formatReportDate(result.reportGeneratedAt)}</>}
      </AlertTitle>
      <AlertDescription>
        <dl className="mt-1 flex flex-wrap gap-x-5 gap-y-1 tabular-nums">
          <ResultCount label="Nowe" value={result.addedCount} />
          <ResultCount label="Ubyło" value={result.removedCount} />
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

type HistoryRow = ImportHistoryEntry

const REPORT_NAMES: Record<HistoryRow['kind'], string> = {
  products: 'Baza danych',
  documents: 'Dokumenty',
  suppliers: 'Lista dostawców',
  sales: 'Sprzedaż',
  nomenclatures: 'Lista nomenklatur',
  promotions: 'Lista promocji',
  productCodes: 'Multikody',
  cadencier: 'Obecne w cadencier',
}

/**
 * Wszystkie wgrania raportów razem, od najnowszego. Serwer daje je porcjami po 30, a kolejną dociągamy, gdy koniec
 * listy zbliża się do dołu ekranu.
 */
function ImportHistory({ storeId, onShowEanChanges }: { storeId: string; onShowEanChanges: ShowEanChanges }) {
  const history = useImportHistory(storeId)
  const rows: HistoryRow[] = history.data?.pages.flatMap((page) => page.items) ?? []
  const loading = history.isPending
  const loadMoreRef = useLoadMore<HTMLTableRowElement>(
    () => {
      if (!history.isFetchingNextPage) void history.fetchNextPage()
    },
    history.hasNextPage && !history.isFetchNextPageError,
    rows.length,
  )

  if (history.isError && !history.data) {
    return (
      <FullPageMessage
        title="Nie udało się wczytać historii importów"
        action={{ label: 'Spróbuj ponownie', onClick: () => history.refetch() }}
        inline
      />
    )
  }

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
                    {row.item.importedBy ?? 'Agent sklepu'} · {row.item.fileName}
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
          {history.isFetchingNextPage && <TableSkeletonRows columns={5} rows={3} />}
          {history.isFetchNextPageError && (
            <TableRow className="hover:bg-transparent">
              <TableCell colSpan={5} className="py-4 text-center text-sm text-muted-foreground">
                Nie udało się wczytać starszych importów.{' '}
                <Button variant="link" size="sm" className="h-auto p-0" onClick={() => void history.fetchNextPage()}>
                  Spróbuj ponownie
                </Button>
              </TableCell>
            </TableRow>
          )}
          {history.hasNextPage && (
            <TableRow ref={loadMoreRef} aria-hidden className="hover:bg-transparent">
              <TableCell colSpan={5} className="h-px p-0" />
            </TableRow>
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
    case 'cadencier':
      return [
        `${numberFormat.format(row.item.totalRows)} artykułów`,
        `${numberFormat.format(row.item.addedCount)} nowych`,
        `${numberFormat.format(row.item.removedCount)} ubyło`,
      ].join(' · ')
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
