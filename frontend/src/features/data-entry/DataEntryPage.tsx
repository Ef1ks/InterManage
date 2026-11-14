import {
  ArrowRightLeftIcon,
  BadgePercentIcon,
  SplitIcon,
  CheckCheckIcon,
  ClipboardCopyIcon,
  ChevronDownIcon,
  CircleCheckIcon,
  CombineIcon,
  DiffIcon,
  EyeIcon,
  EyeOffIcon,
  FileCodeIcon,
  FileInputIcon,
  FileClockIcon,
  FileOutputIcon,
  FileTextIcon,
  FileUpIcon,
  FilterXIcon,
  HistoryIcon,
  ListOrderedIcon,
  PackageCheckIcon,
  PackagePlusIcon,
  PackageSearchIcon,
  PencilIcon,
  RecycleIcon,
  ScaleIcon,
  ShoppingCartIcon,
  PlusIcon,
  ReceiptTextIcon,
  SearchIcon,
  SparklesIcon,
  Redo2Icon,
  SettingsIcon,
  TagIcon,
  TagsIcon,
  Trash2Icon,
  TrendingDownIcon,
  Undo2Icon,
  WandSparklesIcon,
} from 'lucide-react'
import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useStore } from 'react-redux'
import { useNavigate } from 'react-router'
import { toast } from 'sonner'

import { CopyButton } from '@/components/CopyButton'
import { FullPageMessage } from '@/components/FullPageMessage'
import { HowTo, HowToButton } from '@/components/HowTo'
import { PriceLabelsButton } from '@/features/inventory/PriceLabelsDialog'
import { PageHeader } from '@/components/PageHeader'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useCan } from '@/features/auth/api'
import { useSuppliers } from '@/features/documents/api'
import { documentPaths, formatMoney } from '@/features/documents/format'
import { type SalesChartTarget, useSalesReportsOverview } from '@/features/sales/api'
import { documentPrice } from '@/features/base-delivery/base-rows'
import { useCanSeeArticleStats } from '@/features/sales/ArticleStatsButton'
import { SalesChartDialog } from '@/features/sales/SalesChartDialog'
import { usePriceRoundingRules, useSupplierSalePrices } from '@/features/store-settings/api'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { ApiError, fetchPostedFile, saveBlob } from '@/lib/api'
import { useHiddenHint } from '@/lib/use-hidden-hint'
import { cn } from '@/lib/utils'
import { useProtoFolder } from '@/lib/proto-files'
import { ensureFolderAccess, folderSavingSupported, type SavedFolderSpec, saveToFolder } from '@/lib/saved-folders'
import { addDays, todayIso } from '@/lib/dates'
import type {
  DataEntryCatalogProduct,
  DataEntryDocument,
  DataEntryXmlDocument,
  StoreSummary,
  Supplier,
} from '@/lib/types'
import { type RootState, useAppDispatch, useAppSelector } from '@/store'
import {
  type DataEntryDraft,
  type DraftCorrection,
  type DraftDelivery,
  EMPTY_DATA_ENTRY,
  setDataEntryDraft,
  setDataEntrySaved,
} from '@/store/dataEntryDraftSlice'

import {
  type ExportPayload,
  type PriceControlPayload,
  type ProductFilePayload,
  lookupProducts,
  useDataEntryNomenclature,
  useLinkProduct,
  useCorrectionBons,
  useLoadedKsefInvoice,
  useMarkInvoice,
  useSaveTemporaryXml,
  useSuggest,
  useUnlinkProduct,
} from './api'
import { DataEntryGrid, type GridHandle } from './DataEntryGrid'
import {
  duplicateGroups,
  priceConflicts,
  withAveragedPrices,
  withMergedDuplicates,
  withSyncedDuplicates,
} from './duplicates'
import { nomenclatureNames, pricingOf } from './grid-values'
import { addTotals, differs, invoiceTotals, signedMoney } from './invoice-totals'
import { type CatalogAddition, CatalogListDialog } from './CatalogListDialog'
import { CorrectionBanner } from './CorrectionBanner'
import { DeliOrderBanner } from './DeliOrderBanner'
import { FreeScaleCodesDialog } from './FreeScaleCodesDialog'
import { documentSupplier } from './document-supplier'
import { InvoiceFileActions } from './InvoiceFileActions'
import { CompletedInvoiceBadge } from './CompletedInvoiceBadge'
import { InvoicePickerDialog } from './InvoicePickerDialog'
import { ArticleFileDialog } from './ArticleFileDialog'
import { ACTION_TILE, ACTION_TONES, ActionItem, ActionMenu, TILE_WIDTH, TileContent } from './ActionMenu'
import { LabelDialog, type LabelLine } from './LabelDialog'
import { LinkArticleDialog, type LinkTarget } from './LinkArticleDialog'
import { MercalysSupplierDialog } from './MercalysSupplierDialog'
import { LST_FOLDER, lstFile, lstFileName, parseLst } from './lst-file'
import { priceFilesNeedAttention, usePriceFileOverview, usePriceFileWatch } from './price-files'
import { savePriceControlFile } from './price-control'
import type { NewArticles } from './product-drafts'
import { orderLines } from './order-file'
import { ProFileDialog } from './ProFileDialog'
import { ProtocolDialog, type ProtocolLine } from './ProtocolDialog'
import { SaveSettingsDialog } from './SaveSettingsDialog'
import { type RowSplit, SplitQuantityDialog } from './SplitQuantityDialog'
import { SupplierChangeDialog } from './SupplierChangeDialog'
import { NOMENCLATURE_TINTS, ROW_TINTS } from './row-tints'
import { buildTransferMacro, planTransferMacro } from './transfer-macro'
import {
  cadencierPrice,
  catalogChanges,
  type DataEntryRow,
  type RowLink,
  emptyRow,
  mergeVisible,
  belowRequiredMargin,
  missingDeposit,
  needsAi,
  needsProductFile,
  nomenclatureState,
  paddedScaleCode,
  productFileRow,
  rowFromLine,
  rowProblems,
  serviceKind,
  suggestedPrice,
  withAutoSalePrice,
  withAddedRow,
  withSplitQuantity,
  canSplitQuantity,
  withCatalogProduct,
  withCurrentPromo,
  withRefreshedCatalog,
  withLinkedProduct,
  withScannedProduct,
  withServicesLast,
  withSupplierPrice,
  withSuggestion,
  withTrailingEmptyRow,
} from './rows'
import { useUndoHistory, useUndoShortcuts } from './undo-history'
import { CloseInvoicesDialog } from './CloseInvoicesDialog'
import { PluMacroDialog } from './PluMacroDialog'
import { pluItems } from './plu-macro'
import { XmlOpenDialog, type XmlSource } from './XmlOpenDialog'
import { XmlSaveDialog } from './XmlSaveDialog'
import { useXmlSave } from './xml-save'

/**
 * Wprowadzanie danych (wersja testowa): faktura z KSeF w tabeli jak w Excelu, propozycje AI dla nowych artykułów
 * i zapis tabeli do pliku XML faktury dla programu sklepu. Tylko na komputerze - tabela ma 14 kolumn i obsługę z klawiatury.
 */
export function DataEntryPage() {
  const store = useCurrentStore()
  return (
    <>
      <span data-wide-page hidden />
      {!store ? (
        <>
          <PageHeader title="Wprowadzanie danych" />
          <FullPageMessage title="Brak sklepu" description="Artykuły wprowadza się osobno w każdym sklepie." inline />
        </>
      ) : (
        <>
          <div className="lg:hidden">
            <PageHeader title="Wprowadzanie danych" />
            <FullPageMessage
              title="Otwórz na komputerze"
              description="Tabela wprowadzania danych działa jak arkusz Excela i potrzebuje dużego ekranu oraz klawiatury."
              inline
            />
          </div>
          <div className="hidden min-h-0 flex-1 flex-col lg:flex">
            <StoreDataEntry key={store.id} store={store} />
          </div>
        </>
      )}
    </>
  )
}

const pieces = (count: number) =>
  count === 1
    ? 'pozycja'
    : count % 10 >= 2 && count % 10 <= 4 && (count % 100 < 12 || count % 100 > 14)
      ? 'pozycje'
      : 'pozycji'

function StoreDataEntry({ store }: { store: StoreSummary }) {
  const dispatch = useAppDispatch()
  const reduxStore = useStore<RootState>()
  const { rows, invoice, deliOrderId } =
    useAppSelector((state) => state.dataEntryDraft.drafts[store.id]) ?? EMPTY_DATA_ENTRY
  // Najnowszy szkic prosto ze stanu: kilka zmian w jednym kliknięciu i odpowiedzi z API nie nadpisują się nawzajem.
  const currentDraft = useCallback(
    () => reduxStore.getState().dataEntryDraft.drafts[store.id] ?? EMPTY_DATA_ENTRY,
    [reduxStore, store.id],
  )

  const applyDraft = useCallback(
    (draft: DataEntryDraft) =>
      dispatch(
        setDataEntryDraft({
          storeId: store.id,
          // Pusta tabela to jeden pusty wiersz: od razu można skanować i wybrać dostawcę.
          draft: { ...draft, rows: draft.rows.length === 0 ? [emptyRow()] : withTrailingEmptyRow(draft.rows) },
        }),
      ),
    [dispatch, store.id],
  )
  // Ctrl+Z / Ctrl+Y: każda zmiana użytkownika odkłada poprzedni szkic. Dane z kartoteki po wpisaniu EAN-u nie są
  // osobnym krokiem (record: false), więc jedno cofnięcie zdejmuje wpisany kod razem z nimi.
  const history = useUndoHistory(currentDraft, applyDraft)
  const { record } = history
  const setDraft = useCallback(
    (draft: DataEntryDraft, { record: recorded = true }: { record?: boolean } = {}) => {
      const before = currentDraft()
      if (recorded && draft !== before) record(before)
      applyDraft(draft)
    },
    [applyDraft, currentDraft, record],
  )
  const setRows = useCallback(
    (update: (rows: DataEntryRow[]) => DataEntryRow[], options?: { record?: boolean }) => {
      const draft = currentDraft()
      const rows = update(draft.rows)
      if (rows !== draft.rows) setDraft({ ...draft, rows }, options)
    },
    [currentDraft, setDraft],
  )
  useUndoShortcuts(history.undo, history.redo)
  // Filtr „Brakująca kaucja” działa na wiersze z chwili włączenia: poprawiony wiersz nie znika, nowe są widoczne.
  const [rowFilter, setRowFilter] = useState<RowFilter | null>(null)
  const visibleOf = useCallback(
    (all: DataEntryRow[]) =>
      rowFilter ? all.filter((row) => !rowFilter.known.has(row.id) || rowFilter.pass.has(row.id)) : all,
    [rowFilter],
  )
  // Tabela pokazuje kaucje i dostawę pod artykułami; zmiany z tabeli dotyczą wierszy w tej samej kolejności.
  // Zmiana artykułu w jednym wierszu przechodzi na wiersze z tym samym EAN-em.
  const viewRows = useMemo(() => visibleOf(withServicesLast(rows)), [visibleOf, rows])
  const setViewRows = useCallback(
    (update: (rows: DataEntryRow[]) => DataEntryRow[]) =>
      setRows((current) => {
        const before = withServicesLast(current)
        const visible = visibleOf(before)
        return withSyncedDuplicates(before, mergeVisible(before, visible, update(visible)))
      }),
    [setRows, visibleOf],
  )

  const canUseAi = useCan('DATA_ENTRY', 'EDIT')
  const { data: nomenclature = [] } = useDataEntryNomenclature(store.id)
  const names = useMemo(() => nomenclatureNames(nomenclature), [nomenclature])
  const { data: rounding } = usePriceRoundingRules(store.id)
  const gridRef = useRef<GridHandle>(null)
  // Pusta tabela: fokus od razu w EAN-ie pierwszego wiersza, więc skan trafia do tabeli bez klikania.
  const focusFirstEan = useCallback(
    () =>
      requestAnimationFrame(() => {
        const first = currentDraft().rows[0]
        if (first) gridRef.current?.focusCell(first.id, 'ean')
      }),
    [currentDraft],
  )
  useEffect(() => {
    const draft = currentDraft()
    if (draft.rows.length === 0) applyDraft(draft)
    if (draft.rows.every((row) => row.ean === '' && row.name === '')) focusFirstEan()
  }, [store.id, currentDraft, applyDraft, focusFirstEan])

  // Okno sprzedaży i dostaw artykułu jak w raportach; wymaga dostępu do raportów sprzedaży.
  const canSeeSales = useCanSeeArticleStats()
  const salesOverview = useSalesReportsOverview(store.id, canSeeSales)
  const [chartTarget, setChartTarget] = useState<SalesChartTarget | null>(null)
  const showArticle = useCallback(
    (row: DataEntryRow) =>
      row.itm8 &&
      setChartTarget({
        kind: 'article',
        itm8: row.itm8,
        name: row.name,
        ean: row.ean,
        brand: row.brand || null,
      }),
    [],
  )

  const [pickerOpen, setPickerOpen] = useState(false)
  const [catalogOpen, setCatalogOpen] = useState(false)
  const [freeCodesOpen, setFreeCodesOpen] = useState(false)
  // Kody 24 z wagą zamiast ceny (24…05000 = 5 kg). Celowo bez zapamiętania: po odświeżeniu wraca zwykłe działanie.
  const [weightIn24, setWeightIn24] = useState(false)
  const [linkTarget, setLinkTarget] = useState<LinkTarget | null>(null)
  const [correctionMacroOpen, setCorrectionMacroOpen] = useState(false)
  const [closeInvoicesOpen, setCloseInvoicesOpen] = useState(false)
  const [pluMacroOpen, setPluMacroOpen] = useState(false)
  const [splitTargets, setSplitTargets] = useState<DataEntryRow[]>([])
  const [pendingInvoice, setPendingInvoice] = useState<PendingDocument | null>(null)
  const [xmlOpenOpen, setXmlOpenOpen] = useState(false)
  const [xmlSource, setXmlSource] = useState<XmlSource>('file')
  const openXml = (source: XmlSource) => {
    setXmlSource(source)
    setXmlOpenOpen(true)
  }
  const [selectedRows, setSelectedRows] = useState<string[]>([])
  const [protocolOpen, setProtocolOpen] = useState(false)
  const [labelsOpen, setLabelsOpen] = useState(false)
  const [proFileOpen, setProFileOpen] = useState(false)
  const [articleFileOpen, setArticleFileOpen] = useState(false)
  const [checkOpen, setCheckOpen] = useState(false)
  const [resaveOpen, setResaveOpen] = useState(false)
  const [saveSettings, setSaveSettings] = useState<{ open: boolean; missing: SavedFolderSpec[] }>({
    open: false,
    missing: [],
  })
  const lastSaved = useAppSelector((state) => state.dataEntryDraft.saved[store.id]) ?? null
  const forgetSaved = useCallback(
    () => dispatch(setDataEntrySaved({ storeId: store.id, saved: null })),
    [dispatch, store.id],
  )
  const [supplierOpen, setSupplierOpen] = useState(false)
  const [mercalysSupplierOpen, setMercalysSupplierOpen] = useState(false)
  const { data: suppliers } = useSuppliers(store.id)
  const supplier = useMemo(() => documentSupplier(suppliers, invoice), [suppliers, invoice])
  // NIP sprzedawcy z faktury, którego nie ma na liście dostawców (i nie wybrano innego): można go założyć w Mercalys.
  const sellerToCreate =
    suppliers && !supplier && !invoice?.supplier && invoice?.sellerNip?.replace(/\D/g, '').length === 10
      ? invoice.sellerNip.replace(/\D/g, '')
      : null
  // Linie wszystkich bonów korekty (główny i kolejne) do przypinania pozycji.
  const draftCorrection = invoice?.correction
  const correctionDeliveries = useMemo(
    () => (draftCorrection?.delivery ? [draftCorrection.delivery, ...(draftCorrection.extraDeliveries ?? [])] : []),
    [draftCorrection],
  )
  const correctionBons = useCorrectionBons(store.id, invoice?.id ?? null, correctionDeliveries)
  const correctionLines = useMemo(
    () =>
      correctionBons.bons.some((bon) => bon.lines !== null)
        ? correctionBons.bons.flatMap((bon) => bon.lines ?? [])
        : undefined,
    [correctionBons.bons],
  )
  const { data: supplierPrices } = useSupplierSalePrices(store.id)
  // Stała cena sprzedaży dostawcy (Ustawienia sklepu): cena sugerowana wszystkich artykułów dokumentu.
  const supplierPriceFor = useCallback(
    (supplierId: string | null | undefined) =>
      supplierPrices?.find((price) => price.supplierId === supplierId)?.salePrice ?? null,
    [supplierPrices],
  )
  // Wiersze z dostawy z bazy (Weryfikuj warzywa / mięso pakowane): cena sugerowana to cena dla klienta z dokumentu.
  const pricing = useMemo(
    () => ({ ...pricingOf(nomenclature, rounding, supplierPriceFor(supplier?.id)), rowPrice: documentPrice }),
    [nomenclature, rounding, supplierPriceFor, supplier],
  )
  // Wysokość przyklejonego paska nad tabelą: pod nim przykleja się nagłówek kolumn.
  const [toolbarHeight, setToolbarHeight] = useState(0)
  const suggest = useSuggest(store.id)
  const markInvoice = useMarkInvoice(store.id)

  // ---------- faktura

  const load = ({ data, fromXml }: PendingDocument, mode: 'replace' | 'append') => {
    setPendingInvoice(null)
    setPickerOpen(false)
    setXmlOpenOpen(false)
    setRowFilter(null)
    const loaded = data.lines.map(rowFromLine)
    const base = mode === 'append' ? currentDraft().rows.filter((row) => row.ean !== '' || row.name !== '') : []
    // Dopisana faktura: tabela porównuje się z sumą obu dokumentów.
    const previous = mode === 'append' ? currentDraft().invoice : null
    forgetSaved()
    const nextInvoice = {
      id: data.invoiceId,
      number: data.invoiceNumber,
      sellerName: data.sellerName,
      sellerNip: data.sellerNip,
      issueDate: data.issueDate,
      fromXml,
      netTotal: previous ? addTotals(previous.netTotal, data.netTotal) : data.netTotal,
      vatTotal: previous ? addTotals(previous.vatTotal, data.vatTotal) : data.vatTotal,
      // Korekta dopisana do innych wierszy nie ma jednego bonu.
      correction: !previous && data.correction ? { ...data.correction } : null,
    }
    // Dostawca ze stałą ceną (Ustawienia sklepu): wczytane artykuły dostają ją od razu jako cenę sprzedaży.
    const fixedPrice = supplierPriceFor(documentSupplier(suppliers, nextInvoice)?.id)
    setDraft({
      invoice: nextInvoice,
      rows: [...base, ...withSupplierPrice(loaded, fixedPrice)],
      deliOrderId: mode === 'append' ? currentDraft().deliOrderId : null,
    })
    // Status "wczytana": faktura znika z listy do wczytania (także informacja dla innych sklepów firmy). Plik XML
    // z historii był już wczytany, gdy go zapisywano.
    if (data.invoiceId && !fromXml)
      markInvoice.mutate(
        { invoiceId: data.invoiceId, kind: 'LOADED' },
        { onError: () => toast.error('Nie udało się zapisać statusu „wczytana” faktury') },
      )
    const services = loaded.filter((row) => serviceKind(row) !== null).length
    const articles = loaded.length - services
    const fresh = loaded.filter((row) => !row.known && serviceKind(row) === null).length
    toast.success(fromXml ? `Wczytano plik ${fromXml}` : `Wczytano fakturę ${data.invoiceNumber}`, {
      description:
        `${articles} ${pieces(articles)}, ${fresh === 0 ? 'wszystkie są w kartotece' : `nowych: ${fresh}`}` +
        (services > 0 ? `; kaucje i dostawa (${services}) na dole, bez zapisu do pliku XML.` : '.') +
        (fixedPrice !== null ? ` Stała cena dostawcy: ${formatMoney(fixedPrice)}.` : ''),
    })
    if (previous && data.correction)
      toast.warning('Korekta dopisana do innej faktury nie ma makra', {
        description: 'Bon i makro są tylko przy samej korekcie: wczytaj ją jeszcze raz i wybierz „Zastąp”.',
        duration: 10_000,
      })
  }

  const confirmDocument = (pending: PendingDocument) => {
    if (rows.some((row) => row.ean !== '' || row.name !== '')) {
      setPendingInvoice(pending)
    } else {
      load(pending, 'replace')
    }
  }

  // Zapis tymczasowy bywa sprzed kilku dni: artykuły założone od tamtej pory są już w kartotece. Poza krokiem cofania,
  // bo dane z kartoteki należą do wczytania.
  const refreshFromCatalog = (saved: DataEntryRow[]) => {
    const wanted = saved.filter((row) => row.ean !== '' && !row.eanFallback && serviceKind(row) === null)
    const eans = [...new Set(wanted.map((row) => row.ean))]
    if (eans.length === 0) return
    const requested = new Map(wanted.map((row) => [row.id, row.ean]))
    lookupByKey(eans)
      .then((byKey) => {
        const found = wanted.filter((row) => !row.known && byKey.has(row.ean.replace(/^0+/, ''))).length
        setRows(
          (current) => {
            const next = current.map((row) =>
              requested.get(row.id) === row.ean
                ? withRefreshedCatalog(row, byKey.get(row.ean.replace(/^0+/, '')) ?? null, weightIn24)
                : row,
            )
            return next.some((row, index) => row !== current[index]) ? next : current
          },
          { record: false },
        )
        if (found > 0) toast.info(`Już w kartotece: ${found} ${pieces(found)} z zapisu tymczasowego`)
      })
      .catch(() => toast.error('Nie udało się odświeżyć artykułów z kartoteki'))
  }

  const confirmXml = (xml: DataEntryXmlDocument) => {
    // Zapis tymczasowy oddaje całą tabelę taką, jaka była (nazwy, nomenklatura, propozycje AI); plik XML tylko gdy
    // tabeli z zapisu nie da się odczytać.
    const draft = parseDraft(xml.draft)
    if (draft) {
      const before = currentDraft()
      setXmlOpenOpen(false)
      setRowFilter(null)
      forgetSaved()
      setDraft(draft)
      refreshFromCatalog(draft.rows)
      const replaced = before.rows.some((row) => row.ean !== '' || row.name !== '')
      toast.success(`Wczytano zapis tymczasowy ${xml.document.invoiceNumber ?? ''}`.trim(), {
        description: replaced ? 'Zastąpił tabelę.' : undefined,
        action: replaced ? { label: 'Przywróć', onClick: () => setDraft(before) } : undefined,
      })
      return
    }
    if (xml.otherStore)
      toast.warning(`Plik jest dla sklepu ${xml.recipientCode}`, {
        description: `Odbiorca w pliku to nie ${store.number}. Sprawdź, czy to właściwy dokument.`,
      })
    confirmDocument({ data: xml.document, fromXml: xml.fileName || xml.document.invoiceNumber || 'XML' })
  }

  // ---------- EAN wpisany albo wklejony: szukamy artykułu w kartotece

  const onEansChanged = useCallback(
    (rowIds: string[]) => {
      const wanted = currentDraft().rows.filter((row) => rowIds.includes(row.id) && row.ean !== '')
      if (wanted.length === 0) return
      const requested = new Map(wanted.map((row) => [row.id, row.ean]))
      lookupProducts(
        store.id,
        wanted.map((row) => row.ean),
      )
        .then((products) => {
          const byKey = new Map(products.map((product) => [product.ean, product]))
          setRows(
            (current) =>
              current.map((row) =>
                requested.get(row.id) === row.ean
                  ? withScannedProduct(row, byKey.get(row.ean.replace(/^0+/, '')) ?? null, weightIn24)
                  : row,
              ),
            { record: false },
          )
        })
        .catch(() => toast.error('Nie udało się sprawdzić EAN-ów w kartotece'))
    },
    [currentDraft, setRows, store.id, weightIn24],
  )

  // ---------- promocje z kasy: wiersz pamięta promocję z chwili dodania, a plik TRACEART przychodzi w ciągu dnia,
  // więc po wejściu na stronę i po każdym nowym pliku cen promocje znanych artykułów są pobierane od nowa.

  const priceFiles = usePriceFileOverview(store.id)
    .data?.latest.map((file) => file.id)
    .join()
  const refreshedPromos = useRef<string | null>(null)
  const [legendHidden, setLegendHidden] = useHiddenHint('data-entry-legend')
  useEffect(() => {
    if (priceFiles === undefined) return
    const key = `${store.id}:${priceFiles}`
    if (refreshedPromos.current === key) return
    refreshedPromos.current = key
    const eans = [
      ...new Set(
        currentDraft()
          .rows.filter((row) => row.known && row.ean !== '')
          .map((row) => row.ean),
      ),
    ]
    if (eans.length === 0) return
    lookupProducts(store.id, eans)
      .then((products) => {
        const byKey = new Map(products.map((product) => [product.ean, product]))
        setRows(
          (current) => {
            const next = current.map((row) =>
              row.known && eans.includes(row.ean)
                ? withCurrentPromo(row, byKey.get(row.ean.replace(/^0+/, '')) ?? null)
                : row,
            )
            return next.some((row, index) => row !== current[index]) ? next : current
          },
          { record: false },
        )
      })
      .catch(() => {
        // Promocja to tylko podpowiedź; przy błędzie zostaje ta z wiersza.
      })
  }, [currentDraft, priceFiles, setRows, store.id])

  // ---------- lista towarów: artykuł z kartoteki z ilością i ceną zakupu z okna

  const tableEans = useMemo(() => new Set(rows.map((row) => row.ean.replace(/^0+/, '')).filter(Boolean)), [rows])

  // EAN-y idą paczkami, bo lista może mieć ich setki, a wysyłamy je w adresie zapytania.
  const lookupByKey = async (eans: string[]) => {
    const chunks = Array.from({ length: Math.ceil(eans.length / 100) }, (_, index) =>
      eans.slice(index * 100, index * 100 + 100),
    )
    const products = (await Promise.all(chunks.map((chunk) => lookupProducts(store.id, chunk)))).flat()
    return new Map(products.map((product) => [product.ean, product]))
  }

  const addFromCatalog = async (additions: CatalogAddition[]) => {
    // Dane wiersza jak po wpisaniu EAN-u (VAT, cena sprzedaży, nomenklatura); ilość i cena z okna mają pierwszeństwo.
    const byKey = await lookupByKey(additions.map(({ item }) => item.ean))
    const added = additions.map(({ item, quantity, unitNet }) => {
      const scanned = withScannedProduct(
        { ...emptyRow(), ean: item.ean, quantity, unitNet },
        byKey.get(item.ean.replace(/^0+/, '')) ?? null,
      )
      return { ...scanned, quantity, unitNet: unitNet ?? scanned.unitNet }
    })
    const before = currentDraft()
    setRows((current) => added.reduce(withAddedRow, current))
    if (additions.length === 1) {
      toast.success(`Dodano do tabeli: ${additions[0].item.name ?? additions[0].item.ean}`)
    } else {
      toast.success(`Dodano do tabeli artykuły: ${additions.length}`, {
        action: { label: 'Przywróć', onClick: () => setDraft(before) },
      })
    }
  }

  // ---------- plik .lst: jeden kod w linii, trafia do kolumny EAN i dalej jak po wpisaniu kodu

  const lstInput = useRef<HTMLInputElement>(null)
  const [loadingLst, setLoadingLst] = useState(false)
  const loadLst = async (file: File) => {
    setLoadingLst(true)
    try {
      const codes = parseLst(await file.text()).map(paddedScaleCode)
      if (codes.length === 0) {
        toast.warning(`Plik ${file.name} nie ma żadnych kodów`)
        return
      }
      const byKey = await lookupByKey(codes)
      const added = codes.map((ean) =>
        withScannedProduct({ ...emptyRow(), ean }, byKey.get(ean.replace(/^0+/, '')) ?? null, weightIn24),
      )
      const before = currentDraft()
      setRowFilter(null)
      setRows((current) => added.reduce(withAddedRow, current))
      const fresh = added.filter((row) => !row.known).length
      toast.success(`Wczytano z pliku ${file.name}: ${added.length} ${pieces(added.length)}`, {
        description: fresh === 0 ? 'Wszystkie są w kartotece.' : `Nowych: ${fresh}.`,
        action: { label: 'Przywróć', onClick: () => setDraft(before) },
      })
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się wczytać pliku .lst')
    } finally {
      setLoadingLst(false)
    }
  }

  // ---------- wolne kody 24: nowe artykuły ważone z kodem do założenia w programie sklepu

  const insertScaleCodes = (eans: string[]) => {
    if (eans.length === 0) return
    const before = currentDraft()
    setRows((current) =>
      eans.map((ean) => withScannedProduct({ ...emptyRow(), ean }, null)).reduce(withAddedRow, current),
    )
    toast.success(`Wstawiono wolne kody: ${eans.length}`, {
      action: { label: 'Przywróć', onClick: () => setDraft(before) },
    })
  }

  // ---------- podział ilości (opakowanie z faktury na sztuki sprzedaży)

  const splitRows = (splits: RowSplit[], unit: string) => {
    const before = currentDraft()
    setRows((current) => splits.reduce((rows, { row, split }) => withSplitQuantity(rows, row.id, split, unit), current))
    setSplitTargets([])
    toast.success(
      splits.length === 1
        ? 'Podzielono ilość, wartość netto bez zmian'
        : `Podzielono ilość: ${splits.length} ${pieces(splits.length)}, wartość netto bez zmian`,
      {
        action: { label: 'Przywróć', onClick: () => setDraft(before) },
      },
    )
    gridRef.current?.focus()
  }

  // ---------- przypięcie pozycji bez EAN-u do artykułu z kartoteki

  const linkProduct = useLinkProduct(store.id)
  const unlinkProduct = useUnlinkProduct(store.id)
  const openLink = useCallback(
    (row: DataEntryRow) => {
      const current = currentDraft().invoice
      setLinkTarget({
        row,
        sellerNip: current?.sellerNip ?? null,
        sellerName: current?.sellerName ?? null,
      })
    },
    [currentDraft],
  )

  // Ten sam kod (np. ta sama nazwa bez EAN-u) w kilku wierszach to ta sama pozycja: przypinamy wszystkie.
  const sameCode = (row: DataEntryRow, target: DataEntryRow) =>
    row.id === target.id || (!row.known && row.ean !== '' && row.ean === target.ean && serviceKind(row) === null)

  const onLinked = (target: DataEntryRow, product: DataEntryCatalogProduct, link: RowLink | null) => {
    const before = currentDraft()
    setRows((current) => current.map((row) => (sameCode(row, target) ? withLinkedProduct(row, product, link) : row)))
    setLinkTarget(null)
    toast.success(`Przypisano „${target.link?.code ?? target.ean}” do ${product.name ?? product.ean}`, {
      description: link ? 'Kolejne faktury tego dostawcy wczytają ten artykuł od razu.' : undefined,
      action: {
        label: 'Cofnij',
        onClick: () => {
          setDraft(before)
          if (link && !target.link) unlinkProduct.mutate(link.id)
        },
      },
    })
  }

  const onUnlinked = (target: DataEntryRow) => {
    const link = target.link
    if (!link) return
    const before = currentDraft()
    setRows((current) =>
      current.map((row) =>
        row.link?.id === link.id
          ? {
              ...withCatalogProduct(
                {
                  ...row,
                  ean: link.code,
                  eanFallback: 'Przypięty kod',
                  eanChecked: link.code,
                  link: null,
                },
                null,
              ),
              name: row.invoiceName ?? row.name,
              nameFromInvoice: Boolean(row.invoiceName),
            }
          : row,
      ),
    )
    setLinkTarget(null)
    const sellerNip = currentDraft().invoice?.sellerNip
    toast(`Odpięto „${link.code}”`, {
      action: {
        label: 'Przywróć',
        onClick: () => {
          setDraft(before)
          if (sellerNip && target.itm8)
            linkProduct.mutate({
              sellerNip,
              code: link.code,
              itm8: target.itm8,
            })
        },
      },
    })
  }

  // ---------- AI

  const forAi = rows.filter(needsAi)
  const fillWithAi = () => {
    const examples = rows
      .filter((row) => row.known && row.invoiceName)
      .slice(0, 10)
      .map((row) => ({
        invoiceName: row.invoiceName ?? '',
        catalogName: row.name,
        brand: row.brand || null,
      }))
    // mutateAsync, nie mutate z onSuccess: callbacki mutate() przepadają, gdy komponent się przemontuje (powrót do
    // zakładki, przeładowanie modułu), a odpowiedź trafia do szkicu w Reduxie, który przetrwa przemontowanie.
    suggest
      .mutateAsync({
        invoiceId: invoice?.id ?? null,
        sellerNip: invoice?.sellerNip ?? null,
        sellerName: invoice?.sellerName ?? null,
        examples,
        items: forAi.map((row) => ({
          key: row.id,
          // Kod zastępczy (indeks, nazwa) nie jest EAN-em: AI szukałoby po nim producenta.
          ean: row.eanFallback ? null : row.ean || null,
          invoiceName: row.invoiceName ?? row.name,
          invoiceUnit: row.invoiceUnit,
        })),
      })
      .then((response) => {
        const byKey = new Map(response.items.map((item) => [item.key, item]))
        setRows((current) =>
          current.map((row) => {
            const suggestion = byKey.get(row.id)
            return suggestion ? withSuggestion(row, suggestion) : row
          }),
        )
        const toCheck = response.items.filter((item) => item.status === 'TO_CHECK').length
        const deposits = response.items.filter((item) => item.deposit).length
        const summary = `Uzupełniono ${response.items.length} ${pieces(response.items.length)}${toCheck ? `, do sprawdzenia: ${toCheck}` : ''}${deposits ? `, z kaucją: ${deposits}` : ''}.`
        if (response.modelUsed) {
          toast.success(summary)
        } else {
          toast.warning(response.message ?? 'Model AI nie odpowiedział', {
            description: summary,
          })
        }
      })
      .catch((error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się uzupełnić pozycji'))
  }

  const agreed = rows.filter((row) => {
    const state = nomenclatureState(row)
    return state === 'AGREED' || state === 'CERTAIN'
  })
  const confirmAgreed = () =>
    setRows((current) =>
      current.map((row) =>
        agreed.some((candidate) => candidate.id === row.id) && row.suggestion
          ? withAutoSalePrice(row, { ...row, suggestion: { ...row.suggestion, confirmed: true } }, pricing)
          : row,
      ),
    )

  // ---------- wiersze

  // Kaucje i dostawa zostają w tabeli, ale nie są artykułami: bez liczników, sprawdzania i eksportu.
  const filled = rows.filter((row) => (row.ean !== '' || row.name !== '') && serviceKind(row) === null)
  const transferMacro = planTransferMacro(filled)
  const newCount = filled.filter((row) => !row.known).length
  const changedCount = filled.filter((row) => catalogChanges(row).length > 0).length
  const toCheck = rows.filter((row) => nomenclatureState(row) === 'TO_CHECK').length
  const depositCount = filled.filter(missingDeposit).length
  const lowMargin = (row: DataEntryRow) => serviceKind(row) === null && belowRequiredMargin(row, pricing)
  const lowMarginCount = filled.filter(lowMargin).length
  const totals = useMemo(() => invoiceTotals(rows, invoice), [rows, invoice])

  const removeRows = (ids: string[]) => {
    const before = currentDraft()
    setRows((current) => current.filter((row) => !ids.includes(row.id)))
    toast(`Usunięto ${ids.length} ${pieces(ids.length)}`, {
      action: { label: 'Przywróć', onClick: () => setDraft(before) },
    })
    gridRef.current?.focus()
  }

  // ---------- pasek nad tabelą: działania na zaznaczonych wierszach

  const selectedIds = new Set(selectedRows)
  const selectedArticles = viewRows.filter((row) => selectedIds.has(row.id) && serviceKind(row) === null)
  const priceChanges = new Map<string, number>()
  for (const row of selectedArticles) {
    const price = suggestedPrice(row, pricing)
    if (price !== null && price !== row.salePrice) priceChanges.set(row.id, price)
  }
  const splittable = selectedArticles.filter(canSplitQuantity)
  const withoutSuggestion = selectedArticles.filter((row) => suggestedPrice(row, pricing) === null).length

  const applySuggestedPrices = () => {
    const before = currentDraft()
    const changes = new Map(priceChanges)
    setRows((current) =>
      current.map((row) => (changes.has(row.id) ? { ...row, salePrice: changes.get(row.id) ?? null } : row)),
    )
    toast.success(`Ustawiono cenę sugerowaną: ${changes.size} ${pieces(changes.size)}`, {
      description:
        withoutSuggestion > 0
          ? `Bez ceny sugerowanej: ${withoutSuggestion} (brak marży wymaganej, ceny zakupu albo VAT).`
          : undefined,
      action: { label: 'Przywróć', onClick: () => setDraft(before) },
    })
    gridRef.current?.focus()
  }

  const isArticle = (row: DataEntryRow) => (row.ean !== '' || row.name !== '') && serviceKind(row) === null
  // Sam kursor też zaznacza wiersz, także pusty pod artykułami, a taki nie wybiera artykułów do pliku.
  const chosenArticles = selectedArticles.filter(isArticle)
  // Nowe kody 24 z tabeli i każdy zaznaczony kod 24, także z kartoteki.
  const pluCodes = pluItems(filled, chosenArticles)

  const toggleRowFilter = (kind: RowFilter['kind']) => {
    if (rowFilter?.kind === kind) {
      setRowFilter(null)
      return
    }
    const all = currentDraft().rows
    setRowFilter({
      kind,
      known: new Set(all.map((row) => row.id)),
      pass: new Set(all.filter(kind === 'deposit' ? missingDeposit : lowMargin).map((row) => row.id)),
    })
  }

  // Plik .lst: zaznaczone artykuły, a bez zaznaczenia wszystkie widoczne (także po filtrze); tylko prawdziwe EAN-y.
  const lstSource = chosenArticles.length > 0 ? chosenArticles : viewRows.filter(isArticle)
  const lstEans = [
    ...new Set(
      lstSource.filter((row) => !row.eanFallback && /^(\d{1,6}|\d{8,14})$/.test(row.ean)).map((row) => row.ean),
    ),
  ]
  const [savingLst, setSavingLst] = useState(false)
  const saveLst = async () => {
    setSavingLst(true)
    try {
      const folderReady = await ensureFolderAccess(LST_FOLDER.key)
      if (!folderReady && folderSavingSupported) {
        setSaveSettings({ open: true, missing: [LST_FOLDER] })
        return
      }
      const fileName = lstFileName(rowFilter?.kind === 'deposit' ? 'KAUCJE' : 'EAN', invoice?.number)
      if (folderReady) await saveToFolder(LST_FOLDER.key, fileName, lstFile(lstEans))
      else saveBlob(lstFile(lstEans), fileName)
      const skipped = lstSource.length - lstEans.length
      toast.success(`Zapisano plik ${fileName}`, {
        description: [
          `Kodów: ${lstEans.length}`,
          skipped > 0 && `pominięto bez EAN-u i powtórzone: ${skipped}`,
          !folderReady && 'pobrany: ta przeglądarka nie zapisuje do folderów',
        ]
          .filter(Boolean)
          .join(', '),
      })
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Nie udało się zapisać pliku .lst')
    } finally {
      setSavingLst(false)
    }
  }

  /** Zmiana całej tabeli z paskiem „Przywróć”. */
  const changeAll = (update: (rows: DataEntryRow[]) => DataEntryRow[], message: string) => {
    const before = currentDraft()
    setRows(update)
    toast.success(message, {
      action: { label: 'Przywróć', onClick: () => setDraft(before) },
    })
    gridRef.current?.focus()
  }

  const setAllQuantities = (quantity: number) =>
    changeAll(
      (current) => current.map((row) => (isArticle(row) ? { ...row, quantity } : row)),
      `Ilość ${quantity} we wszystkich artykułach`,
    )

  // Zmiana znaku ilości (+ na -, - na +): zaznaczone artykuły, a bez zaznaczenia wszystkie (bez kaucji i dostawy).
  const signTargets = (selectedArticles.length > 0 ? selectedArticles : rows.filter(isArticle)).filter(
    (row) => row.quantity !== null && row.quantity !== 0,
  )
  const flipQuantities = () => {
    const ids = new Set(signTargets.map((row) => row.id))
    changeAll(
      (current) => current.map((row) => (ids.has(row.id) && row.quantity ? { ...row, quantity: -row.quantity } : row)),
      `Zmieniono znak ilości w ${ids.size} ${pieces(ids.size)}`,
    )
  }

  // Błędny albo niepełny EAN blokuje każdy plik dla programu sklepu: protokół, etykiety, zmianę cen i plik XML.
  const badEanCount = filled.filter((row) => rowProblems(row).includes('invalid-ean')).length
  const badEanTitle = badEanCount > 0 ? `Najpierw popraw błędne albo niepełne EAN-y w tabeli: ${badEanCount}` : null

  // Do protokołu idą artykuły z EAN-em (nie zastępczym kodem z faktury) i ilością różną od 0, ze znakiem.
  const protocolLines: ProtocolLine[] = []
  for (const row of rows.filter(isArticle)) {
    if (!row.eanFallback && /^\d{1,14}$/.test(row.ean) && row.quantity) {
      protocolLines.push({ ean: row.ean, quantity: row.quantity })
    }
  }
  const protocolSkipped = rows.filter(isArticle).length - protocolLines.length

  // Etykiety cenowe: zaznaczone artykuły, a bez zaznaczenia wszystkie; tylko z prawdziwym EAN-em.
  const labelSource = chosenArticles.length > 0 ? chosenArticles : rows.filter(isArticle)
  const labelLines: LabelLine[] = labelSource
    .filter((row) => !row.eanFallback && /^(\d{1,6}|\d{8,14})$/.test(row.ean))
    .map((row) => ({ ean: row.ean, quantity: row.quantity }))

  const conflicts = priceConflicts(rows)
  const duplicates = duplicateGroups(rows)
  const duplicateRows = duplicates.reduce((sum, group) => sum + group.length, 0)

  const averagePrices = () =>
    changeAll(
      withAveragedPrices,
      `Uśredniono ceny ${conflicts.length} ${conflicts.length === 1 ? 'artykułu' : 'artykułów'}`,
    )

  const mergeDuplicates = () =>
    changeAll(
      withMergedDuplicates,
      `Połączono ${duplicateRows} ${pieces(duplicateRows)} w ${duplicates.length} ${duplicates.length === 1 ? 'wiersz' : 'wiersze'}`,
    )

  const clearAll = () => {
    const before = currentDraft()
    forgetSaved()
    setRowFilter(null)
    setDraft(EMPTY_DATA_ENTRY)
    focusFirstEan()
    toast('Wyczyszczono tabelę', {
      action: { label: 'Przywróć', onClick: () => setDraft(before) },
    })
  }

  // ---------- dostawca dokumentu

  // Dokument dla innego dostawcy niż sprzedawca z faktury: numer faktury zastępuje numer wpisany w oknie, a pusty
  // numer nadaje serwer z czasu zapisu. Sama zmiana numeru zostawia ceny w tabeli.
  const changeSupplier = (supplier: Supplier, number: string | null) => {
    setSupplierOpen(false)
    const before = currentDraft()
    const supplierChanged = supplier.id !== documentSupplier(suppliers, before.invoice)?.id
    const fixedPrice = supplierChanged ? supplierPriceFor(supplier.id) : null
    setDraft({
      ...before,
      invoice: {
        ...(before.invoice ?? { id: null, sellerName: null, sellerNip: null }),
        number,
        supplier: { id: supplier.id, name: supplier.name, nip: supplier.nip },
      },
      rows: supplierChanged ? withSupplierPrice(before.rows, fixedPrice) : before.rows,
    })
    const notes = [
      !before.invoice?.supplier &&
        before.invoice?.number &&
        before.invoice.number !== number &&
        `Usunięto numer faktury ${before.invoice.number}`,
      fixedPrice !== null && `Stała cena dostawcy ${formatMoney(fixedPrice)} wpisana w artykułach`,
    ].filter(Boolean)
    toast(supplierChanged ? `Dostawca dokumentu: ${supplier.name}` : `Numer dokumentu: ${number ?? 'z czasu zapisu'}`, {
      description: notes.length ? `${notes.join('. ')}.` : undefined,
      action: { label: 'Cofnij', onClick: () => setDraft(before) },
    })
  }

  // ---------- bon faktury korygującej

  const chooseCorrectionDelivery = useCallback(
    (delivery: NonNullable<DraftCorrection['delivery']> | null, auto = false, extra: DraftDelivery[] = []) => {
      const before = currentDraft()
      if (!before.invoice?.correction) return
      setDraft(
        {
          ...before,
          invoice: {
            ...before.invoice,
            correction: { ...before.invoice.correction, delivery, extraDeliveries: extra },
          },
        },
        { record: !auto },
      )
    },
    [currentDraft, setDraft],
  )

  // ---------- zamówienie garmażu wstawione do tabeli

  const forgetDeliOrder = () => setDraft({ ...currentDraft(), deliOrderId: null }, { record: false })

  const keepDeliOrderOpen = () => {
    const { deliOrderId: orderId } = currentDraft()
    setDraft({ ...currentDraft(), deliOrderId: null })
    toast('Zamówienie zostaje otwarte', {
      description: 'Wiersze zostają w tabeli.',
      action: { label: 'Przywróć', onClick: () => setDraft({ ...currentDraft(), deliOrderId: orderId }) },
    })
  }

  // ---------- plik XML

  const buildPayload = (): ExportPayload => ({
    invoiceId: invoice?.id ?? null,
    invoiceNumber: invoice?.number ?? null,
    sellerName: invoice?.sellerName ?? null,
    sellerNip: invoice?.sellerNip ?? null,
    issueDate: invoice?.issueDate ?? null,
    supplierId: invoice?.supplier?.id ?? null,
    rows: filled.map((row) => ({
      suggestionId: row.suggestion?.id ?? null,
      // Kod zastępczy z faktury (inny kod albo nazwa) nie jest EAN-em: w pliku idą zera.
      ean: (!row.eanFallback && row.ean) || null,
      quantity: row.quantity,
      unitNet: row.unitNet,
      invoiceUnit: row.invoiceUnit,
      name: row.name,
      brand: row.brand || null,
      vatPercent: row.vatPercent,
      salePrice: row.salePrice,
      cadencierPrice: cadencierPrice(row),
      subfamilyCode: row.subfamilyCode,
      unit: row.unit,
      netContent: row.netContent,
      status: row.edited.subfamilyCode ? null : (row.suggestion?.status ?? null),
      known: row.known,
    })),
  })
  const payload = buildPayload()
  // Po tym widać, czy tabela zmieniła się od ostatniego zapisu pliku XML.
  const signature = JSON.stringify(payload)
  // Inny dostawca albo numer to inny dokument: zapis poprzedniego go nie dotyczy, a Ctrl+Z przywraca znacznik.
  const documentKey = JSON.stringify([invoice?.supplier?.id ?? null, invoice?.number ?? null])
  const saved = lastSaved && (lastSaved.document ?? documentKey) === documentKey ? lastSaved : null
  const savedState = saved ? (saved.signature === signature ? 'saved' : 'changed') : null

  // Nowe i zmienione artykuły (bez samej zmiany ceny) idą razem z plikiem XML do pliku CADBEZP dla programu sklepu.
  const productRows = filled.filter(needsProductFile)
  const productFileOf = (source: DataEntryRow[]): ProductFilePayload => ({
    supplierId: invoice?.supplier?.id ?? null,
    sellerNip: invoice?.sellerNip ?? null,
    sellerName: invoice?.supplier?.name ?? invoice?.sellerName ?? null,
    invoiceNumber: invoice?.number ?? null,
    rows: source.map(productFileRow),
  })
  const productFilePayload = productRows.length > 0 ? productFileOf(productRows) : null

  // O wprowadzenie pytamy tylko przy nowych artykułach; zmiany artykułów z kartoteki idą do pliku bez pytania.
  const newArticlesOf = (source: DataEntryRow[]): NewArticles | null => {
    const fresh = source.filter((row) => !row.known)
    return fresh.length > 0 ? { payload: productFileOf(fresh), supplierCode: supplier?.code ?? null } : null
  }
  const newArticles = newArticlesOf(productRows)

  // Sam plik CADBEZP nie ma okna sprawdzenia, więc artykuły bez podrodziny i z za długą nazwą trzeba najpierw poprawić.
  const unfinishedProducts = productRows.filter((row) =>
    rowProblems(row).some((problem) => problem === 'no-subfamily' || problem === 'name-too-long'),
  ).length
  const productFileBlocker =
    badEanTitle ??
    (unfinishedProducts > 0 ? `Najpierw uzupełnij podrodzinę i popraw za długie nazwy: ${unfinishedProducts}` : null)

  // Artykuły z kartoteki z ceną sprzedaży inną niż przy wczytaniu (kartoteka albo promocja z kasy) idą do CTRLPRIX.
  const priceControlRows = filled.flatMap((row) =>
    row.known &&
    !row.eanFallback &&
    row.ean &&
    row.catalog &&
    row.salePrice !== null &&
    row.salePrice > 0 &&
    row.salePrice !== row.catalog.salePrice
      ? [{ ean: row.ean, salePrice: row.salePrice }]
      : [],
  )
  const priceControlPayload: PriceControlPayload | null =
    priceControlRows.length > 0
      ? {
          supplierId: invoice?.supplier?.id ?? null,
          sellerNip: invoice?.sellerNip ?? null,
          invoiceNumber: invoice?.number ?? null,
          rows: priceControlRows,
        }
      : null

  // „Zmień ceny”: sam plik CTRLPRIX, bez pliku XML (np. gdy dokument jest już wprowadzony).
  const protoFolder = useProtoFolder(store.id)
  const queryClient = useQueryClient()
  const [savingPrices, setSavingPrices] = useState(false)
  const savePriceChanges = async () => {
    if (!priceControlPayload) return
    setSavingPrices(true)
    try {
      // Zgoda na folder zaraz po kliknięciu, zanim cokolwiek pójdzie do serwera.
      const folderReady = await ensureFolderAccess(protoFolder.key)
      const file = await savePriceControlFile(store.id, priceControlPayload, protoFolder.key, folderReady)
      void queryClient.invalidateQueries({ queryKey: ['stores', store.id, 'data-entry', 'price-control-files'] })
      toast.success(`Zapisano plik zmian cen${file.supplierCode ? ` dla dostawcy ${file.supplierCode}` : ''}`, {
        description: `${file.fileName}${file.downloaded ? ' (pobrany: folder protokołów nie jest wybrany w tej przeglądarce)' : ''}`,
        duration: 10_000,
      })
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać pliku zmiany cen')
    } finally {
      setSavingPrices(false)
    }
  }

  // „Stwórz zamówienie”: plik COMMANDE w folderze protokołów, ze wszystkich artykułów, a z zaznaczonych dopiero od dwóch:
  // kursor stojący na jednym wierszu nie może zamienić zamówienia w jedną pozycję.
  const orderFromSelection = chosenArticles.length > 1
  const order = orderLines(orderFromSelection ? chosenArticles : rows.filter(isArticle))
  const orderBlockedTitle =
    badEanTitle ??
    (order.invalid.length > 0
      ? `Popraw ilości w tabeli (${order.invalid.length} poz.): w zamówieniu tylko liczby całkowite od 1 do 99`
      : null)
  const [savingOrder, setSavingOrder] = useState(false)
  const copyTransferMacro = async () => {
    try {
      await navigator.clipboard.writeText(buildTransferMacro(transferMacro.items))
    } catch {
      toast.error('Nie udało się skopiować makra do schowka')
      return
    }
    toast.success(`Makro w schowku: ${transferMacro.items.length} poz.`, {
      description: `Naciśnij Alt+E, a odtwarzacz sam otworzy Mercalys i przeniesie stany.${
        transferMacro.skipped > 0 ? ` Pominięte bez EAN-u albo ilości: ${transferMacro.skipped}.` : ''
      }`,
    })
  }

  const saveOrder = async () => {
    setSavingOrder(true)
    try {
      // Zgoda na folder zaraz po kliknięciu, zanim cokolwiek pójdzie do serwera.
      const folderReady = await ensureFolderAccess(protoFolder.key)
      const { fileName, blob } = await fetchPostedFile(
        `/stores/${store.id}/data-entry/export/order`,
        { lines: order.lines },
        'COMMANDE.traite0',
      )
      if (folderReady) await saveToFolder(protoFolder.key, fileName, blob)
      else saveBlob(blob, fileName)
      toast.success('Zapisano zamówienie', {
        description: [
          fileName,
          `Pozycji: ${order.lines.length}`,
          order.skipped > 0 && `pominięto bez EAN-u, z kodem wagi albo bez ilości: ${order.skipped}`,
          !folderReady && 'pobrany: folder protokołów nie jest wybrany w tej przeglądarce',
        ]
          .filter(Boolean)
          .join(', '),
        duration: 10_000,
      })
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać zamówienia')
    } finally {
      setSavingOrder(false)
    }
  }

  // Zapis tymczasowy: plik XML i cała tabela na serwerze, bez zapisu na dysku (najwyżej 10 w sklepie).
  const saveTemporaryXml = useSaveTemporaryXml(store.id)
  const saveTemporary = () =>
    saveTemporaryXml.mutate(
      { export: buildPayload(), draft: JSON.stringify(currentDraft()) },
      {
        onSuccess: (item) =>
          toast.success('Zapisano tymczasowo', {
            description: `${item.documentNumber ?? 'Tabela bez numeru'}: wczytasz ją w „Wczytaj z XML”. Zostaje 10 ostatnich zapisów.`,
          }),
        onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać tymczasowo'),
      },
    )
  const xmlProblems = {
    invalidEan: filled.filter((row) => rowProblems(row).includes('invalid-ean')),
    tooLong: filled.filter((row) => rowProblems(row).includes('name-too-long')),
    noSubfamily: filled.filter((row) => rowProblems(row).includes('no-subfamily')),
  }
  const hasProblems =
    totals.mismatch ||
    conflicts.length > 0 ||
    xmlProblems.invalidEan.length > 0 ||
    xmlProblems.tooLong.length > 0 ||
    xmlProblems.noSubfamily.length > 0

  // „Zapisz”: bez braków zapisuje od razu do folderów z ustawień zapisu; z brakami najpierw okno sprawdzenia, a gdy
  // dokument jest już zapisany, pyta o ponowny zapis.
  // Po „Tak” artykuły z pliku przestają być nowe (po „Cofnij” znów są), więc wiersze szukają ich od nowa.
  const xmlSave = useXmlSave(store.id, (eans) => {
    const changed = new Set(eans.map((ean) => ean.replace(/^0+/, '')))
    onEansChanged(
      currentDraft()
        .rows.filter((row) => changed.has(row.ean.replace(/^0+/, '')))
        .map((row) => row.id),
    )
  })
  const runSave = async () => {
    const missing = await xmlSave.missingFolders(productFilePayload, priceControlPayload)
    if (missing.length > 0) {
      setCheckOpen(false)
      setSaveSettings({ open: true, missing })
      return
    }
    const fileName = await xmlSave.save(payload, productFilePayload, priceControlPayload, newArticles)
    if (!fileName) return
    setCheckOpen(false)
    dispatch(
      setDataEntrySaved({ storeId: store.id, saved: { at: nowIso(), fileName, signature, document: documentKey } }),
    )
  }
  // „Zmień artykuły”: plik CADBEZP z artykułów wybranych w oknie, także niezmienionych.
  const saveChosenArticles = async (chosen: DataEntryRow[]) => {
    if (await xmlSave.saveProducts(productFileOf(chosen), newArticlesOf(chosen))) setArticleFileOpen(false)
  }
  const checkAndSave = () => {
    if (hasProblems) setCheckOpen(true)
    else void runSave()
  }
  const onSaveClick = () => {
    if (saved) setResaveOpen(true)
    else checkAndSave()
  }

  const goTo = (row: DataEntryRow, key: 'ean' | 'name' | 'subfamily') => {
    setCheckOpen(false)
    setRowFilter(null)
    // Fokus wraca do tabeli dopiero po zamknięciu okna.
    setTimeout(() => gridRef.current?.focusCell(row.id, key), 0)
  }

  return (
    <>
      {deliOrderId && (
        <DeliOrderBanner
          storeId={store.id}
          orderId={deliOrderId}
          rows={rows}
          onFulfilled={forgetDeliOrder}
          onDismiss={keepDeliOrderOpen}
        />
      )}
      <PageHeader
        title="Wprowadzanie danych"
        description={
          invoice ? (
            <DocumentHeading storeId={store.id} invoice={invoice} onEditNumber={() => setSupplierOpen(true)} />
          ) : (
            storeLabel(store)
          )
        }
        actions={
          <>
            <HowToButton id="data-entry" />
            {/* Zmiany cen z plików CTRLPRIX czekają tu na jeden plik cenówek dnia. */}
            <PriceLabelsButton storeId={store.id} />
            <SettingsButton storeId={store.id} onClick={() => setSaveSettings({ open: true, missing: [] })} />
          </>
        }
        actionsAtBottom
      />

      <HowTo
        id="data-entry"
        reopenInHeader
        className="mb-4"
        title="Jak wprowadzić artykuły z faktury"
        steps={[
          'Wczytaj fakturę z KSeF. Artykuły zapisane w kartotece są szare i mają dane z kartoteki, nowe są fioletowe, a zmienione w tabeli pomarańczowe. Kaucje i dostawa idą na dół i nie trafiają do pliku XML.',
          'Kliknij „Uzupełnij”: nazwa, marka, nomenklatura, rodzaj i waga dla nowych artykułów. Artykuły z kaucją dostają plakietkę, a „Brakująca kaucja” pokazuje tylko je.',
          'Skanujesz ręcznie? Wpisz albo zeskanuj EAN i Enter: od razu powstaje kolejny wiersz na następny kod.',
          'Popraw jak w Excelu: strzałki, Enter, pisanie albo F2, Ctrl+C / Ctrl+V, Ctrl+Z cofa, Ctrl+Y ponawia. Ctrl + plus wstawia wiersz nad bieżącym, a plus przy numerze wiersza pod nim. Żółte komórki wymagają wyboru.',
          'Kliknij „Zapisz”: pliki trafiają od razu do folderów z ustawień zapisu (koło zębate, wybierasz je raz). Niedokończoną tabelę zapisz tymczasowo i wczytaj później: „Wczytaj” → „Wczytaj tymczasowe”.',
        ]}
      />

      {invoice?.correction && invoice.id && (
        <CorrectionBanner
          key={invoice.id}
          storeId={store.id}
          invoice={invoice}
          correction={invoice.correction}
          rows={rows}
          macroOpen={correctionMacroOpen}
          onMacroOpenChange={setCorrectionMacroOpen}
          onDelivery={chooseCorrectionDelivery}
          onLinkRow={openLink}
        />
      )}

      <CommandBar onHeightChange={setToolbarHeight}>
        {(invoice || rows.length > 0) && (
          <SupplierNumber
            code={supplier?.code ?? null}
            name={supplier?.name ?? invoice?.supplier?.name ?? invoice?.sellerName ?? null}
            problem={
              supplier?.code || !suppliers
                ? null
                : supplier
                  ? 'dostawca bez numeru (dodany ręcznie)'
                  : invoice?.supplier || invoice?.sellerNip
                    ? 'brak dostawcy na liście dostawców'
                    : 'nie wybrano dostawcy'
            }
            changeLabel={
              rows.length === 0
                ? null
                : invoice?.supplier || invoice?.sellerName || invoice?.sellerNip
                  ? 'Zmień dostawcę'
                  : 'Wybierz dostawcę'
            }
            onChange={() => setSupplierOpen(true)}
            onCreate={sellerToCreate ? () => setMercalysSupplierOpen(true) : undefined}
          />
        )}
        <div className="flex shrink-0 gap-1.5" role="group" aria-label="Pliki">
          <ActionMenu
            label="Wczytaj"
            icon={<FileInputIcon />}
            tone="load"
            tile
            loading={loadingLst}
            title="Wczytaj do tabeli fakturę z KSeF, plik XML albo listę .lst"
          >
            <ActionItem icon={<ReceiptTextIcon />} label="Fakturę z KSeF" onClick={() => setPickerOpen(true)} />
            <ActionItem icon={<FileCodeIcon />} label="Plik XML" onClick={() => openXml('file')} />
            <ActionItem
              icon={<FileUpIcon />}
              label="Plik .lst"
              hint="Dopisuje do tabeli kody z pliku .lst: jeden kod w wierszu, trafia do kolumny EAN"
              onClick={() => lstInput.current?.click()}
            />
            <DropdownMenuSeparator />
            <ActionItem
              icon={<FileClockIcon />}
              label="Wczytaj tymczasowe"
              hint="Tabela zapisana tymczasowo, taka jak przy zapisie (10 ostatnich)"
              onClick={() => openXml('temporary')}
            />
            <ActionItem
              icon={<HistoryIcon />}
              label="Wczytaj kopię"
              hint="Kopia pliku XML zapisanego wcześniej na dysku (100 ostatnich)"
              onClick={() => openXml('history')}
            />
          </ActionMenu>
          <input
            ref={lstInput}
            type="file"
            accept=".lst,.txt"
            hidden
            onChange={(event) => {
              const file = event.target.files?.[0]
              event.target.value = ''
              if (file) void loadLst(file)
            }}
          />
          <ActionMenu
            label="Wytwórz"
            icon={<FileOutputIcon />}
            tone="make"
            tile
            loading={savingOrder || savingLst || savingPrices || xmlSave.savingProducts}
            disabled={filled.length === 0}
            title={
              filled.length === 0
                ? 'Najpierw dodaj artykuły do tabeli'
                : chosenArticles.length > 1
                  ? `Pliki z zaznaczonych artykułów (${chosenArticles.length}): zamówienie, etykiety, plik .lst`
                  : 'Protokół, zamówienie, etykiety, zmiana cen i pliki artykułów'
            }
          >
            {badEanTitle && (
              <p className="mb-1 rounded-md bg-amber-200 px-2 py-1.5 text-xs font-medium text-amber-950 dark:bg-amber-900 dark:text-amber-50">
                {badEanTitle}
              </p>
            )}
            {invoice?.correction && invoice.id && (
              <>
                <DropdownMenuGroup>
                  <DropdownMenuLabel>Korekta</DropdownMenuLabel>
                  <ActionItem
                    icon={<ClipboardCopyIcon />}
                    label="Makro korekty"
                    disabled={!invoice.correction.delivery}
                    hint={invoice.correction.delivery ? 'Podgląd i makro do programu sklepu' : 'Najpierw wybierz bon'}
                    onClick={() => setCorrectionMacroOpen(true)}
                  />
                </DropdownMenuGroup>
                <DropdownMenuSeparator />
              </>
            )}
            <DropdownMenuGroup>
              <DropdownMenuLabel>Makro</DropdownMenuLabel>
              <ActionItem
                icon={<ArrowRightLeftIcon />}
                label="Przeniesienie"
                count={transferMacro.items.length}
                disabled={transferMacro.items.length === 0}
                hint={
                  transferMacro.items.length === 0
                    ? 'Żaden artykuł nie ma EAN-u i ilości'
                    : 'Do schowka, Alt+E: Przesunięcie stanu w Mercalys, wszystkie artykuły, przeniesienie i wydruk'
                }
                onClick={() => void copyTransferMacro()}
              />
              <ActionItem
                icon={<ScaleIcon />}
                label="Kody PLU dla wagi"
                count={pluCodes.length}
                disabled={pluCodes.length === 0}
                hint={
                  pluCodes.length === 0
                    ? 'W tabeli nie ma nowych artykułów z kodem 24 (artykuł z kartoteki: zaznacz go w tabeli)'
                    : 'Do schowka, Alt+E: kod PLU, „KOD:” w tekście dowolnym i format etykiety dla każdego nowego kodu 24'
                }
                onClick={() => setPluMacroOpen(true)}
              />
              <ActionItem
                icon={<ClipboardCopyIcon />}
                label="Zamknij faktury"
                hint="Faktury z zapisanych plików XML, skrypt do schowka"
                onClick={() => setCloseInvoicesOpen(true)}
              />
            </DropdownMenuGroup>
            <DropdownMenuSeparator />
            <DropdownMenuGroup>
              <DropdownMenuLabel>Do folderu protokołów</DropdownMenuLabel>
              <ActionItem
                icon={<FileOutputIcon />}
                label="Wygeneruj protokół"
                disabled={protocolLines.length === 0 || badEanTitle !== null}
                hint={!badEanTitle && protocolLines.length === 0 ? 'Żaden artykuł nie ma EAN-u i ilości' : undefined}
                onClick={() => setProtocolOpen(true)}
              />
              <ActionItem
                icon={<ShoppingCartIcon />}
                label="Stwórz zamówienie"
                count={order.lines.length}
                disabled={order.lines.length === 0 || orderBlockedTitle !== null}
                hint={
                  badEanTitle
                    ? undefined
                    : (orderBlockedTitle ??
                      (order.lines.length === 0
                        ? 'Żaden artykuł nie ma EAN-u i ilości'
                        : orderFromSelection
                          ? 'Z zaznaczonych artykułów'
                          : 'Ze wszystkich artykułów tabeli (zaznacz wiersze, żeby wybrać tylko część)'))
                }
                onClick={() => void saveOrder()}
              />
              <ActionItem
                icon={<TagsIcon />}
                label="Etykiety"
                count={labelLines.length}
                disabled={labelLines.length === 0 || badEanTitle !== null}
                hint={
                  badEanTitle
                    ? undefined
                    : labelLines.length === 0
                      ? 'Żaden artykuł nie ma EAN-u'
                      : chosenArticles.length > 0
                        ? 'Etykiety cenowe zaznaczonych artykułów'
                        : 'Etykiety cenowe wszystkich artykułów tabeli (zaznacz wiersze, żeby wybrać tylko część)'
                }
                onClick={() => setLabelsOpen(true)}
              />
              <ActionItem
                icon={<TagIcon />}
                label="Zmień ceny"
                count={priceControlRows.length}
                disabled={priceControlRows.length === 0 || badEanTitle !== null}
                hint={
                  badEanTitle
                    ? undefined
                    : priceControlRows.length > 0
                      ? 'Sama zmiana cen, bez pliku XML'
                      : 'Żaden artykuł z kartoteki nie ma w tabeli zmienionej ceny sprzedaży'
                }
                onClick={() => void savePriceChanges()}
              />
            </DropdownMenuGroup>
            <DropdownMenuSeparator />
            <DropdownMenuGroup>
              <DropdownMenuLabel>Promocja</DropdownMenuLabel>
              <ActionItem
                icon={<BadgePercentIcon />}
                label="Plik cen promocji .PRO"
                disabled={badEanTitle !== null}
                hint={badEanTitle ? undefined : 'EAN i cena z kolumny „Cena sprz.”, do folderu plików .PRO'}
                onClick={() => setProFileOpen(true)}
              />
            </DropdownMenuGroup>
            <DropdownMenuSeparator />
            <DropdownMenuGroup>
              <DropdownMenuLabel>Plik artykułów</DropdownMenuLabel>
              <ActionItem
                icon={<PackagePlusIcon />}
                label="Dodaj nowe artykuły"
                count={productRows.length}
                disabled={productRows.length === 0 || productFileBlocker !== null}
                hint={
                  (productFileBlocker === badEanTitle ? undefined : productFileBlocker) ??
                  (productRows.length > 0
                    ? 'Sam plik nowych i zmienionych artykułów, bez pliku XML'
                    : 'W tabeli nie ma nowych ani zmienionych artykułów')
                }
                onClick={() => productFilePayload && void xmlSave.saveProducts(productFilePayload, newArticles)}
              />
              <ActionItem
                icon={<PackageCheckIcon />}
                label="Zmień artykuły"
                hint="Wybierz artykuły do pliku, także bez zmian: wczytany plik przepisuje je na dostawcę dokumentu"
                onClick={() => setArticleFileOpen(true)}
              />
            </DropdownMenuGroup>
            <DropdownMenuSeparator />
            <DropdownMenuGroup>
              <DropdownMenuLabel>Lista EAN</DropdownMenuLabel>
              <ActionItem
                icon={<FileTextIcon />}
                label="Plik .lst"
                count={lstEans.length}
                disabled={lstEans.length === 0}
                hint={
                  lstEans.length === 0
                    ? 'Żaden artykuł nie ma EAN-u'
                    : chosenArticles.length > 0
                      ? 'EAN-y zaznaczonych artykułów'
                      : 'EAN-y artykułów widocznych w tabeli (zaznacz wiersze, żeby wybrać tylko część)'
                }
                onClick={() => void saveLst()}
              />
            </DropdownMenuGroup>
          </ActionMenu>
        </div>
        <div className={cn('grid w-28 shrink-0 gap-1', rows.length > 0 && 'grid-rows-[2fr_1fr]')}>
          <Button
            variant="outline"
            className="h-auto rounded-md py-1 font-semibold [&_svg:not([class*='size-'])]:size-5"
            onClick={() => setCatalogOpen(true)}
            aria-label="Lista towarów"
            title="Lista towarów: kartoteka sklepu"
          >
            <span className="flex flex-col items-center">
              <PackageSearchIcon />
              Lista
            </span>
          </Button>
          {rows.length > 0 && (
            <div className="flex gap-1" role="group" aria-label="Zaznaczone wiersze">
              <Button
                variant="outline"
                className={ROW_ACTION}
                onClick={() => gridRef.current?.selectAll()}
                aria-label="Zaznacz wszystkie"
                title="Zaznacz wszystkie pozycje (Ctrl+A)"
              >
                <CheckCheckIcon />
              </Button>
              <Button
                variant="outline"
                className={ROW_ACTION}
                onClick={flipQuantities}
                disabled={signTargets.length === 0}
                aria-label="Zmień znak ilości"
                title={
                  selectedArticles.length > 0
                    ? `Zmień znak ilości w zaznaczonych artykułach (${signTargets.length}): + na −, − na +`
                    : 'Zmień znak ilości we wszystkich artykułach (bez kaucji i dostawy): + na −, − na +'
                }
              >
                <DiffIcon />
              </Button>
              <Button
                variant="outline"
                className={ROW_ACTION}
                onClick={() => removeRows(selectedRows)}
                disabled={selectedRows.length === 0}
                aria-label="Usuń zaznaczone wiersze"
                title={
                  selectedRows.length > 0
                    ? `Usuń zaznaczone wiersze (${selectedRows.length}) (Ctrl+−)`
                    : 'Usuń wiersze: zaznacz je w tabeli (klik w numer wiersza, Shift + klik)'
                }
              >
                <Trash2Icon />
              </Button>
            </div>
          )}
        </div>
        <div className="flex min-w-0 flex-1 flex-col justify-center gap-2">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1.5" role="group" aria-label="Dokument">
            <ActionMenu
              label={weightIn24 ? 'Kody 24 = waga' : 'Kody 24'}
              icon={<ScaleIcon />}
              tone={weightIn24 ? 'warn' : undefined}
              title="Wolne kody 240001–242999 i odczyt kodów 24 z etykiety"
            >
              <ActionItem
                icon={<ListOrderedIcon />}
                label="Wolne kody 24"
                hint="Wolne kody 240001–242999 do zakładania nowych artykułów ważonych"
                onClick={() => setFreeCodesOpen(true)}
              />
              <DropdownMenuCheckboxItem
                checked={weightIn24}
                onCheckedChange={setWeightIn24}
                className="gap-2 pr-1.5 **:data-[slot=dropdown-menu-checkbox-item-indicator]:hidden"
              >
                <input
                  type="checkbox"
                  checked={weightIn24}
                  readOnly
                  tabIndex={-1}
                  aria-hidden
                  className="pointer-events-none size-4 shrink-0 accent-primary"
                />
                Koniec kodu to waga (24…05000 = 5 kg)
              </DropdownMenuCheckboxItem>
            </ActionMenu>
            {rows.length > 0 && (agreed.length > 0 || canUseAi) && <ToolbarDivider />}
            {rows.length > 0 && canUseAi && (
              <Button
                variant="outline"
                className={ACTION_TONES.ai}
                onClick={fillWithAi}
                loading={suggest.isPending}
                disabled={forAi.length === 0}
              >
                <SparklesIcon />
                Uzupełnij
                {forAi.length > 0 && ` (${forAi.length} ${forAi.length === 1 ? 'nowy' : 'nowych'})`}
              </Button>
            )}
            {agreed.length > 0 && (
              <Button variant="outline" onClick={confirmAgreed}>
                Zatwierdź zgodne ({agreed.length})
              </Button>
            )}
            {invoice?.id && (
              <>
                <ToolbarDivider />
                <InvoiceFileActions storeId={store.id} invoiceId={invoice.id} size="default" />
              </>
            )}
          </div>

          {rows.length > 0 && (
            <div className="flex flex-wrap items-center gap-x-2 gap-y-1.5" role="group" aria-label="Tabela">
              <Button
                variant="outline"
                onClick={applySuggestedPrices}
                disabled={priceChanges.size === 0}
                title={
                  priceChanges.size > 0
                    ? 'Wpisuje cenę sugerowaną jako cenę sprzedaży w zaznaczonych wierszach'
                    : selectedArticles.length === 0
                      ? 'Zaznacz wiersze w tabeli (Shift + klik, Ctrl+A)'
                      : 'Zaznaczone wiersze mają już cenę sugerowaną albo nie da się jej policzyć'
                }
              >
                <BadgePercentIcon />
                Ustaw ceny sugerowane
                {priceChanges.size > 0 && ` (${priceChanges.size})`}
              </Button>
              <Button
                variant="outline"
                onClick={() => setSplitTargets(splittable)}
                disabled={splittable.length === 0}
                title={
                  splittable.length > 0
                    ? 'Dzieli ilość zaznaczonych pozycji tą samą liczbą (np. opakowania po 10 szt. na sztuki)'
                    : 'Zaznacz wiersze z ilością i ceną zakupu (Shift + klik, Ctrl+A)'
                }
              >
                <SplitIcon />
                Podziel ilość
                {splittable.length > 1 && ` (${splittable.length})`}
              </Button>
              <ToolbarDivider />
              <div className="flex items-center gap-1" role="group" aria-label="Ilość we wszystkich artykułach">
                <span className="mr-1 text-sm text-muted-foreground">Ilość:</span>
                {[0, 1].map((quantity) => (
                  <Button
                    key={quantity}
                    variant="outline"
                    className="min-w-8 tabular-nums"
                    onClick={() => setAllQuantities(quantity)}
                    disabled={filled.length === 0}
                    title={`Ustawia ilość ${quantity} we wszystkich artykułach (bez kaucji i dostawy)`}
                  >
                    {quantity}
                  </Button>
                ))}
              </div>
              {duplicates.length > 0 && (
                <ActionMenu
                  label={`Ten sam EAN (${duplicates.length})`}
                  icon={<CombineIcon />}
                  tone="warn"
                  title="Ten sam EAN jest w kilku wierszach: program sklepu łączy takie pozycje"
                >
                  <ActionItem
                    icon={<ScaleIcon />}
                    label="Uśrednij ceny"
                    count={conflicts.length}
                    disabled={conflicts.length === 0}
                    hint={
                      conflicts.length > 0
                        ? 'Wpisuje w wierszach cenę średnią ważoną ilością'
                        : 'Wiersze z tym samym EAN-em mają już jedną cenę'
                    }
                    onClick={averagePrices}
                  />
                  <ActionItem
                    icon={<CombineIcon />}
                    label="Połącz te same"
                    count={duplicates.length}
                    hint="Łączy wiersze w jeden: ilości zsumowane, cena średnia"
                    onClick={mergeDuplicates}
                  />
                </ActionMenu>
              )}
              {(lowMarginCount > 0 || rowFilter?.kind === 'margin') && (
                <Button
                  variant="outline"
                  className={cn(rowFilter?.kind === 'margin' && ACTION_TONES.warn)}
                  aria-pressed={rowFilter?.kind === 'margin'}
                  onClick={() => toggleRowFilter('margin')}
                  title="Artykuły z marżą poniżej wymaganej na ich nomenklaturze (Ustawienia sklepu → Marża wymagana)"
                >
                  <TrendingDownIcon />
                  Za mała marża ({lowMarginCount})
                </Button>
              )}
              {(depositCount > 0 || rowFilter?.kind === 'deposit') && (
                <Button
                  variant="outline"
                  className={cn(rowFilter?.kind === 'deposit' && ACTION_TONES.warn)}
                  aria-pressed={rowFilter?.kind === 'deposit'}
                  onClick={() => toggleRowFilter('deposit')}
                  title="Nowe artykuły z kaucją wskazane przez „Uzupełnij”: kaucję trzeba ustawić przy zakładaniu w Mercalys"
                >
                  <RecycleIcon />
                  Brakująca kaucja ({depositCount})
                </Button>
              )}
              {rowFilter && (
                <Button variant="ghost" onClick={() => setRowFilter(null)}>
                  <FilterXIcon />
                  Pokaż wszystkie ({viewRows.length}/{rows.length})
                </Button>
              )}
            </div>
          )}
        </div>
        {rows.length > 0 && (
          <div
            className="flex shrink-0 flex-col items-center justify-center gap-2"
            role="group"
            aria-label="Historia zmian"
          >
            <Button variant="ghost" onClick={clearAll}>
              Wyczyść
            </Button>
            <div className="flex items-center gap-1">
              <Button
                variant="ghost"
                size="icon"
                onClick={history.undo}
                disabled={!history.canUndo}
                aria-label="Cofnij"
                title="Cofnij ostatnią zmianę (Ctrl+Z)"
              >
                <Undo2Icon />
              </Button>
              <Button
                variant="ghost"
                size="icon"
                onClick={history.redo}
                disabled={!history.canRedo}
                aria-label="Ponów"
                title="Ponów cofniętą zmianę (Ctrl+Y)"
              >
                <Redo2Icon />
              </Button>
            </div>
          </div>
        )}
        {rows.length > 0 && (
          <div className={cn('flex', TILE_WIDTH)} role="group" aria-label="Zapis">
            <Button
              className={cn(ACTION_TONES.save, ACTION_TILE, 'min-w-0 flex-1 rounded-r-none')}
              onClick={onSaveClick}
              loading={xmlSave.saving}
              disabled={filled.length === 0}
              title="Zapisuje plik XML (z nowymi artykułami i zmianą cen) w folderach z ustawień zapisu"
            >
              <TileContent
                icon={
                  saved && savedState ? (
                    <SavedStatus state={savedState} at={saved.at} fileName={saved.fileName} />
                  ) : (
                    <FileCodeIcon />
                  )
                }
              >
                Zapisz
              </TileContent>
            </Button>
            <DropdownMenu>
              <DropdownMenuTrigger
                render={
                  <Button
                    className={cn(ACTION_TONES.save, '-ml-px h-auto w-7 rounded-l-none rounded-r-md px-0')}
                    aria-label="Inne sposoby zapisu"
                    loading={saveTemporaryXml.isPending}
                    disabled={filled.length === 0}
                  />
                }
              >
                <ChevronDownIcon />
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-80">
                <ActionItem
                  icon={<FileClockIcon />}
                  label="Zapisz tymczasowo"
                  hint="Tabela na serwerze, bez pliku na dysku; wczytasz ją w „Wczytaj” → „Wczytaj tymczasowe” (10 ostatnich)"
                  onClick={saveTemporary}
                />
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        )}
      </CommandBar>

      <>
        <div className="mb-2 flex items-start gap-3">
          <dl className="flex min-w-0 flex-1 flex-wrap gap-x-6 gap-y-1 text-sm tabular-nums">
            <Stat label="Pozycje" value={filled.length} />
            <Stat
              label="Suma netto"
              value={<ComparedSum value={totals.net} document={totals.invoiceNet} diff={totals.netDiff} />}
            />
            <Stat
              label="VAT"
              value={<ComparedSum value={totals.vat} document={totals.invoiceVat} diff={totals.vatDiff} />}
            />
            <Stat label="Nowe" value={newCount} />
            {changedCount > 0 && <Stat label="Zmienione z kartoteki" value={changedCount} />}
            <Stat label="Do sprawdzenia" value={toCheck} highlight={toCheck > 0} />
            {depositCount > 0 && <Stat label="Brakująca kaucja" value={depositCount} highlight />}
          </dl>
          <Button
            data-keep-grid-selection
            variant="ghost"
            size="xs"
            className="text-muted-foreground"
            aria-expanded={!legendHidden}
            onClick={() => setLegendHidden(!legendHidden)}
          >
            {legendHidden ? <EyeIcon /> : <EyeOffIcon />}
            {legendHidden ? 'Pokaż legendę' : 'Schowaj legendę'}
          </Button>
        </div>

        {!legendHidden && <Legend />}

        {viewRows.length === 0 ? (
          <div className="rounded-xl border border-dashed px-6 py-10 text-center text-sm text-muted-foreground">
            Żaden wiersz nie pasuje do filtra.{' '}
            <Button variant="link" className="h-auto p-0" onClick={() => setRowFilter(null)}>
              Pokaż wszystkie
            </Button>
          </div>
        ) : (
          <DataEntryGrid
            ref={gridRef}
            rows={viewRows}
            setRows={setViewRows}
            onEansChanged={onEansChanged}
            onSelectedRowsChange={setSelectedRows}
            onDeleteRows={removeRows}
            onInvalidPaste={(count) =>
              toast.warning(`Pominięto ${count} ${count === 1 ? 'komórkę' : 'komórek'} z nieprawidłową wartością`)
            }
            nomenclature={nomenclature}
            names={names}
            pricing={pricing}
            onShowArticle={canSeeSales ? showArticle : undefined}
            onLinkArticle={openLink}
            onSplitQuantity={(row) => setSplitTargets([row])}
            stickyTop={`calc(3.5rem + ${toolbarHeight}px)`}
          />
        )}

        <div data-keep-grid-selection className="mt-3 flex shrink-0 gap-2">
          <Button variant="outline" onClick={() => setRows((current) => [...current, emptyRow()])}>
            <PlusIcon />
            Dodaj wiersz
          </Button>
          <Button variant="ghost" onClick={() => removeRows(selectedRows)} disabled={selectedRows.length === 0}>
            <Trash2Icon />
            Usuń zaznaczone wiersze
            {selectedRows.length > 0 && ` (${selectedRows.length})`}
          </Button>
        </div>
      </>

      {canSeeSales && (
        <SalesChartDialog
          storeId={store.id}
          kind="sales"
          target={chartTarget}
          filter={NO_FILTER}
          basis="gross"
          lastSaleDate={salesOverview.data?.lastSaleDate ?? addDays(todayIso(), -1)}
          onClose={() => setChartTarget(null)}
        />
      )}

      <ProtocolDialog
        storeId={store.id}
        lines={protocolLines}
        skipped={protocolSkipped}
        open={protocolOpen}
        onOpenChange={setProtocolOpen}
      />

      <ArticleFileDialog
        rows={filled}
        selectedIds={selectedRows}
        supplierNote={
          supplier?.code
            ? `Plik CADBEZP dla dostawcy ${supplier.code} ${supplier.name}.`
            : 'Dokument nie ma dostawcy z numerem: wybierz go w pasku, jeśli artykuły mają przejść na innego dostawcę.'
        }
        saving={xmlSave.savingProducts}
        open={articleFileOpen}
        onOpenChange={setArticleFileOpen}
        onSave={(chosen) => void saveChosenArticles(chosen)}
      />

      <LabelDialog
        storeId={store.id}
        lines={labelLines}
        selection={chosenArticles.length > 0}
        skipped={labelSource.length - labelLines.length}
        open={labelsOpen}
        onOpenChange={setLabelsOpen}
      />

      <ProFileDialog
        rows={orderFromSelection ? chosenArticles : rows.filter(isArticle)}
        selection={orderFromSelection}
        open={proFileOpen}
        onOpenChange={setProFileOpen}
      />

      <CatalogListDialog
        storeId={store.id}
        open={catalogOpen}
        onOpenChange={setCatalogOpen}
        inTable={tableEans}
        onAdd={addFromCatalog}
        lastSaleDate={canSeeSales ? (salesOverview.data?.lastSaleDate ?? addDays(todayIso(), -1)) : null}
      />

      <FreeScaleCodesDialog
        storeId={store.id}
        open={freeCodesOpen}
        onOpenChange={setFreeCodesOpen}
        inTable={tableEans}
        onInsert={insertScaleCodes}
      />

      <LinkArticleDialog
        storeId={store.id}
        target={linkTarget}
        onClose={() => setLinkTarget(null)}
        onLinked={onLinked}
        onUnlinked={onUnlinked}
        inTable={tableEans}
        lastSaleDate={canSeeSales ? (salesOverview.data?.lastSaleDate ?? addDays(todayIso(), -1)) : null}
        bonLines={correctionLines}
      />

      <SplitQuantityDialog rows={splitTargets} onClose={() => setSplitTargets([])} onSplit={splitRows} />

      <InvoicePickerDialog
        storeId={store.id}
        open={pickerOpen}
        onOpenChange={setPickerOpen}
        onConfirm={(data) => confirmDocument({ data })}
      />

      <CloseInvoicesDialog storeId={store.id} open={closeInvoicesOpen} onOpenChange={setCloseInvoicesOpen} />
      <PluMacroDialog open={pluMacroOpen} onOpenChange={setPluMacroOpen} items={pluCodes} />

      <XmlOpenDialog
        storeId={store.id}
        source={xmlSource}
        open={xmlOpenOpen}
        onOpenChange={setXmlOpenOpen}
        onLoaded={confirmXml}
      />

      <AlertDialog open={pendingInvoice !== null} onOpenChange={(open) => !open && setPendingInvoice(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>W tabeli są już wiersze</AlertDialogTitle>
            <AlertDialogDescription>
              Zastąpić je pozycjami{' '}
              {pendingInvoice?.fromXml
                ? `pliku ${pendingInvoice.fromXml}`
                : `faktury ${pendingInvoice?.data.invoiceNumber}`}{' '}
              czy dopisać pozycje na końcu?
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Anuluj</AlertDialogCancel>
            <AlertDialogAction variant="outline" onClick={() => pendingInvoice && load(pendingInvoice, 'append')}>
              Dopisz
            </AlertDialogAction>
            <AlertDialogAction onClick={() => pendingInvoice && load(pendingInvoice, 'replace')}>
              Zastąp
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <XmlSaveDialog
        count={filled.length}
        problems={xmlProblems}
        totals={totals}
        priceConflicts={conflicts}
        onMergeDuplicates={mergeDuplicates}
        open={checkOpen}
        onOpenChange={setCheckOpen}
        onGoTo={goTo}
        onSave={() => void runSave()}
        saving={xmlSave.saving}
      />

      <SaveSettingsDialog
        storeId={store.id}
        open={saveSettings.open}
        onOpenChange={(open) => setSaveSettings((current) => ({ ...current, open }))}
        missing={saveSettings.missing}
        extraFolders={[{ folder: LST_FOLDER, note: 'Lista EAN-ów artykułów z tabeli („Plik .lst”).' }]}
        withPriceFiles
      />

      {xmlSave.draftDialog}

      <AlertDialog open={resaveOpen} onOpenChange={setResaveOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Dokument jest już zapisany</AlertDialogTitle>
            <AlertDialogDescription>
              {saved && (
                <>
                  Plik <span className="break-all">{saved.fileName}</span> zapisano {formatSavedAt(saved.at)}
                  {savedState === 'changed' ? ', a tabela zmieniła się od tego czasu' : ''}.{' '}
                </>
              )}
              Czy na pewno chcesz zapisać go ponownie? Plik o tej samej nazwie zostanie nadpisany.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Anuluj</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                setResaveOpen(false)
                checkAndSave()
              }}
            >
              Zapisz ponownie
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <SupplierChangeDialog
        storeId={store.id}
        invoice={invoice}
        open={supplierOpen}
        onOpenChange={setSupplierOpen}
        onChange={changeSupplier}
      />

      {sellerToCreate && (
        <MercalysSupplierDialog
          storeId={store.id}
          nip={sellerToCreate}
          invoiceName={invoice?.sellerName ?? null}
          open={mercalysSupplierOpen}
          onOpenChange={setMercalysSupplierOpen}
        />
      )}
    </>
  )
}

const NO_FILTER: string[] = []

/** Przycisk pod „Lista”: trzy dzielą szerokość po równo, wysokość bierze z bloku. */
const ROW_ACTION = 'h-full min-w-0 flex-1 rounded-md px-0'

/** Filtr wierszy z chwili włączenia: wszystkie wtedy znane wiersze i te, które przeszły filtr. */
type RowFilter = { kind: 'deposit' | 'margin'; known: Set<string>; pass: Set<string> }

/** Chwila zapisu pliku (poza komponentem: woła ją tylko obsługa kliknięcia). */
const nowIso = () => new Date().toISOString()

/** Tabela z zapisu tymczasowego; zepsuty albo obcy JSON = null (wtedy wczytuje się sam plik XML). */
function parseDraft(json: string | null): DataEntryDraft | null {
  if (!json) return null
  try {
    const value = JSON.parse(json) as Partial<DataEntryDraft> | null
    if (!value || !Array.isArray(value.rows) || value.rows.length === 0) return null
    return { invoice: value.invoice ?? null, rows: value.rows, deliOrderId: value.deliOrderId ?? null }
  } catch {
    return null
  }
}

/** Dokument czekający na wczytanie: faktura z KSeF albo plik XML (`fromXml` = nazwa pliku). */
type PendingDocument = { data: DataEntryDocument; fromXml?: string }

/**
 * Wczytany dokument pod tytułem: duży numer faktury (kopiowanie i „Wyszukaj”, który otwiera przyjęcia PZ albo przy
 * korekcie korekty od dostawcy z tym numerem w wyszukiwarce), dostawca i plik. Faktura, która ma już dokument
 * w systemie sklepu, jest „zakończona”, żeby nie wprowadzić jej drugi raz. Przy dostawcy wybranym ręcznie to już inny
 * dokument niż faktura, więc bez sprawdzania KSeF, a numer da się wpisać. Same elementy liniowe, bo opis nagłówka jest
 * akapitem.
 */
function DocumentHeading({
  storeId,
  invoice,
  onEditNumber,
}: {
  storeId: string
  invoice: NonNullable<DataEntryDraft['invoice']>
  onEditNumber: () => void
}) {
  const navigate = useNavigate()
  const canSeeDocuments = useCan('DOCUMENTS', 'VIEW')
  const ownDocument = Boolean(invoice.supplier)
  const ksef = useLoadedKsefInvoice(storeId, !ownDocument ? invoice.id : null, invoice.number)
  const documents = ksef.data?.deliveries ?? []
  const correction = ksef.data?.correction ?? false
  const seller = invoice.supplier ? `dla ${invoice.supplier.name}` : (invoice.sellerName ?? invoice.sellerNip)
  const details = [seller, invoice.fromXml && !invoice.id ? `z pliku ${invoice.fromXml}` : null].filter(Boolean)
  const number = invoice.number

  return (
    <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
      {number ? (
        <>
          <span className="text-muted-foreground">
            {invoice.id && !ownDocument ? (correction ? 'Korekta' : 'Faktura') : 'Dokument'}
          </span>
          <span className="text-xl font-semibold tracking-tight text-foreground tabular-nums">{number}</span>
          <CopyButton value={number} label="Kopiuj numer faktury" />
          {ownDocument && (
            <Button
              variant="ghost"
              size="icon-xs"
              aria-label="Zmień numer dokumentu"
              title="Zmień numer dokumentu"
              onClick={onEditNumber}
            >
              <PencilIcon />
            </Button>
          )}
          {canSeeDocuments && (
            <Button
              variant="outline"
              size="xs"
              onClick={() =>
                navigate(`${documentPaths.list(correction ? 'WZ' : 'PZ')}?szukaj=${encodeURIComponent(number)}`)
              }
              title={`Szukaj numeru ${number} w ${correction ? 'korektach od dostawcy' : 'przyjęciach PZ'}`}
            >
              <SearchIcon />
              Wyszukaj
            </Button>
          )}
        </>
      ) : (
        <>
          <span className="text-foreground">Dokument bez numeru</span>
          {ownDocument && (
            <Button variant="outline" size="xs" onClick={onEditNumber}>
              <PencilIcon />
              Wpisz numer
            </Button>
          )}
        </>
      )}
      {invoice.id && <CompletedInvoiceBadge storeId={storeId} invoiceId={invoice.id} documents={documents} size="lg" />}
      {details.length > 0 && <span>· {details.join(' · ')}</span>}
    </span>
  )
}

/**
 * Przypięty pasek ze wszystkimi działaniami: numer dostawcy, dokument (wczytanie, zapis) i tabela (zaznaczone wiersze,
 * cała tabela). Przykleja się pod nagłówkiem aplikacji, więc przy przewijaniu długiej tabeli nie trzeba wracać na
 * górę; pod nim klei się nagłówek kolumn.
 */
export function CommandBar({
  onHeightChange,
  children,
}: {
  onHeightChange: (height: number) => void
  children: React.ReactNode
}) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const element = ref.current
    if (!element) return
    const observer = new ResizeObserver(() => onHeightChange(element.offsetHeight))
    observer.observe(element)
    return () => observer.disconnect()
  }, [onHeightChange])
  return (
    <div
      ref={ref}
      data-keep-grid-selection
      className="sticky top-14 z-[15] mb-3 flex shrink-0 items-stretch gap-3 rounded-lg border bg-muted p-1.5 shadow-xs"
    >
      {children}
    </div>
  )
}

/**
 * Numer dostawcy dokumentu w dużym kwadracie (trafia do pliku XML i do programu sklepu), pod nim nazwa, a w rogu
 * zmiana dostawcy, żeby nie podnosiła paska. Bez numeru bursztynowy z powodem.
 */
function SupplierNumber({
  code,
  name,
  problem,
  changeLabel,
  onChange,
  onCreate,
}: {
  code: string | null
  name: string | null
  problem: string | null
  changeLabel: string | null
  onChange: () => void
  /** Sprzedawcy z faktury nie ma na liście dostawców: założenie go w Mercalys makrem. */
  onCreate?: () => void
}) {
  return (
    <div
      className={cn(
        'relative flex flex-col items-center justify-center rounded-md border-2 px-2 py-0.5 text-center',
        TILE_WIDTH,
        code
          ? 'border-foreground/20 bg-card'
          : 'border-amber-400 bg-amber-200 text-amber-950 dark:border-amber-600 dark:bg-amber-900 dark:text-amber-50',
      )}
      title={name ?? undefined}
    >
      <span className={cn('text-xs leading-tight', code ? 'text-muted-foreground' : 'font-medium')}>Nr dostawcy</span>
      {code ? (
        <span className="text-3xl leading-none font-bold tracking-tight tabular-nums">{code}</span>
      ) : (
        <span className="text-sm leading-tight font-semibold">{problem ?? 'Sprawdzanie…'}</span>
      )}
      {name && <span className="w-full truncate text-xs leading-tight">{name}</span>}
      {onCreate && (
        <button
          type="button"
          onClick={onCreate}
          title="Założy dostawcę w Mercalys makrem (dane z GUS, pierwszy wolny kod)"
          className="mt-0.5 inline-flex items-center gap-1 rounded-md bg-amber-950 px-1.5 py-0.5 text-xs leading-tight font-semibold text-amber-50 transition-colors duration-150 outline-none hover:bg-amber-900 focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-amber-100 dark:text-amber-950 dark:hover:bg-amber-200"
        >
          <WandSparklesIcon aria-hidden className="size-3" />
          Załóż w Mercalys
        </button>
      )}
      {changeLabel && (
        <Button
          variant="ghost"
          size="icon-xs"
          className="absolute top-0 right-0 size-5"
          onClick={onChange}
          aria-label={changeLabel}
          title={changeLabel}
        >
          <PencilIcon />
        </Button>
      )}
    </div>
  )
}

/**
 * Koło zębate ustawień. Kropka mówi, że ceny z kasy czekają na zgodę albo mają błąd, bo ich stan jest teraz w oknie.
 * Osobny komponent, żeby postęp wczytywania plików cen nie przerysowywał całej tabeli.
 */
function SettingsButton({ storeId, onClick }: { storeId: string; onClick: () => void }) {
  const attention = priceFilesNeedAttention(usePriceFileWatch(), storeId)
  return (
    <Button
      variant="ghost"
      size="icon-sm"
      className="text-muted-foreground"
      onClick={onClick}
      aria-label={
        attention ? 'Ustawienia zapisu i cen z kasy: ceny z kasy wymagają uwagi' : 'Ustawienia zapisu i cen z kasy'
      }
      title={
        attention
          ? 'Ceny z kasy wymagają uwagi: brak zgody na odczyt folderu albo błąd sprawdzania'
          : 'Ustawienia: foldery na plik XML, nowe artykuły, protokoły, pliki .lst i ceny z kasy'
      }
    >
      <SettingsIcon />
      {attention && <span aria-hidden className="absolute top-0.5 right-0.5 size-2 rounded-full bg-amber-500" />}
    </Button>
  )
}

/**
 * Stan zapisu w kafelku Zapisz, w miejscu ikony, więc nie zmienia układu paska: godzina zapisu, a po zmianach w tabeli
 * bursztynowe „zmiany”. Pełny opis w podpowiedzi. Kafelek jest zielony w obu motywach, stąd kolory bez wariantu dark.
 */
function SavedStatus({ state, at, fileName }: { state: 'saved' | 'changed'; at: string; fileName: string }) {
  const time = new Date(at).toLocaleTimeString('pl-PL', { hour: '2-digit', minute: '2-digit' })
  return (
    <span
      className={cn(
        'flex h-6 items-center gap-1 rounded-md px-1.5 text-xs font-semibold tabular-nums',
        state === 'saved' ? 'bg-white/20 text-white' : 'bg-amber-200 text-amber-950',
      )}
      title={
        state === 'saved'
          ? `Dokument zapisany ${formatSavedAt(at)}: ${fileName}`
          : `Zapisany ${formatSavedAt(at)}, zmiany po zapisie: ${fileName}`
      }
    >
      {state === 'saved' ? (
        <>
          <CircleCheckIcon aria-hidden className="size-3.5" />
          {time}
        </>
      ) : (
        'zmiany'
      )}
    </span>
  )
}

/** Godzina zapisu, a z innego dnia data i godzina. */
function formatSavedAt(iso: string) {
  const date = new Date(iso)
  const time = date.toLocaleTimeString('pl-PL', { hour: '2-digit', minute: '2-digit' })
  return date.toDateString() === new Date().toDateString()
    ? `o ${time}`
    : `${date.toLocaleDateString('pl-PL')} o ${time}`
}

export function ToolbarDivider() {
  return <span aria-hidden className="mx-1 h-5 w-px bg-border" />
}

/**
 * Suma tabeli i porównanie z sumą z faktury: zgodna (do 2 gr zaokrągleń) na zielono, różna na czerwono z różnicą
 * tabela − faktura. Bez faktury (albo bez sum w dokumencie) sama suma tabeli.
 */
function ComparedSum({ value, document, diff }: { value: number; document: number | null; diff: number | null }) {
  const money = `${formatMoney(value)} zł`
  if (document === null || diff === null) return money
  if (!differs(diff)) {
    return (
      <span className="flex items-baseline gap-1.5">
        {money}
        <span
          className="rounded bg-emerald-200 px-1.5 text-xs font-medium text-emerald-950 dark:bg-emerald-900 dark:text-emerald-50"
          title={`Na fakturze ${formatMoney(document)} zł`}
        >
          zgodna z fakturą
        </span>
      </span>
    )
  }
  return (
    <span className="flex items-baseline gap-1.5">
      {money}
      <span
        className="rounded bg-red-300 px-1.5 text-xs font-medium text-red-950 dark:bg-red-800 dark:text-red-50"
        title="Sprawdź ilości i ceny: program sklepu nie przyjmie dokumentu z błędną ilością"
      >
        faktura {formatMoney(document)} zł · różnica {signedMoney(diff)} zł
      </span>
    </span>
  )
}

export function Stat({ label, value, highlight }: { label: string; value: React.ReactNode; highlight?: boolean }) {
  return (
    <div className="flex items-baseline gap-1.5">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className={highlightClass(highlight)}>{value}</dd>
    </div>
  )
}

const highlightClass = (highlight?: boolean) =>
  highlight
    ? 'rounded bg-amber-100 px-1.5 font-semibold text-amber-950 dark:bg-amber-950/50 dark:text-amber-100'
    : 'font-semibold'

/** Kolory komórek nomenklatury i oznaczenia wierszy. */
function Legend() {
  const items = [
    [`${NOMENCLATURE_TINTS.CERTAIN} ring-emerald-600 dark:ring-emerald-500`, 'Pewne (kartoteka)'],
    [`${NOMENCLATURE_TINTS.AGREED} ring-sky-600 dark:ring-sky-500`, 'Zgodne (AI i kartoteka)'],
    [`${NOMENCLATURE_TINTS.TO_CHECK} ring-amber-600 dark:ring-amber-500`, 'Do sprawdzenia: wybierz z propozycji'],
    [`${ROW_TINTS.new} ring-violet-500 dark:ring-violet-400`, 'Nowy artykuł'],
    [`${ROW_TINTS.known} ring-slate-500 dark:ring-slate-400`, 'Zapisany w kartotece'],
    [`${ROW_TINTS.changedCell} ring-orange-600 dark:ring-orange-500`, 'Z kartoteki, zmieniony w tabeli'],
    ['bg-stone-200 ring-stone-400 dark:bg-stone-800 dark:ring-stone-500', 'Kaucja, dostawa: bez zapisu do pliku XML'],
    ['bg-red-500 ring-red-700 dark:bg-red-700 dark:ring-red-500', 'Nazwa dłuższa niż 30 znaków albo błędny EAN'],
  ]
  return (
    <ul className="mb-2 flex flex-wrap gap-x-5 gap-y-1 text-xs text-muted-foreground" aria-label="Legenda">
      {items.map(([swatch, label]) => (
        <li key={label} className="flex items-center gap-1.5">
          <span aria-hidden className={`size-3 rounded-sm ring-1 ${swatch}`} />
          {label}
        </li>
      ))}
    </ul>
  )
}
