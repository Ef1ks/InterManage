import {
  ArrowLeftIcon,
  BadgePercentIcon,
  CalendarClockIcon,
  CarrotIcon,
  CheckCheckIcon,
  ClipboardListIcon,
  CloudDownloadIcon,
  FileInputIcon,
  FileSearchIcon,
  FileOutputIcon,
  FileDownIcon,
  FilePlusIcon,
  FileUpIcon,
  FilterXIcon,
  LayersIcon,
  ListChecksIcon,
  PackageCheckIcon,
  Redo2Icon,
  SettingsIcon,
  TagIcon,
  Trash2Icon,
  TrendingDownIcon,
  Undo2Icon,
  BeefIcon,
  HashIcon,
  HistoryIcon,
  KeyboardIcon,
  ScanBarcodeIcon,
} from 'lucide-react'
import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useStore } from 'react-redux'
import { Link, useNavigate } from 'react-router'
import { toast } from 'sonner'

import { CopyButton } from '@/components/CopyButton'
import { FullPageMessage } from '@/components/FullPageMessage'
import { HowTo } from '@/components/HowTo'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { DropdownMenuGroup, DropdownMenuLabel, DropdownMenuSeparator } from '@/components/ui/dropdown-menu'
import { ACTION_TONES, ActionItem, ActionMenu } from '@/features/data-entry/ActionMenu'
import {
  type PriceControlPayload,
  type ProductFilePayload,
  lookupProducts,
  useDataEntryNomenclature,
  usePriceControlPendingCount,
} from '@/features/data-entry/api'
import { CommandBar, Stat, ToolbarDivider } from '@/features/data-entry/DataEntryPage'
import { DataEntryGrid, type GridHandle } from '@/features/data-entry/DataEntryGrid'
import { withSyncedDuplicates } from '@/features/data-entry/duplicates'
import { lstFile } from '@/features/data-entry/lst-file'
import {
  BASE_COLUMNS,
  type Column,
  type ColumnKey,
  cellText,
  nomenclatureNames,
  pricingOf,
} from '@/features/data-entry/grid-values'
import { savePriceControlFile } from '@/features/data-entry/price-control'
import { PriceControlPendingBanner } from '@/features/data-entry/PriceControlFilesDialog'
import { type SavedProductFile, useProductDraftConfirm } from '@/features/data-entry/product-drafts'
import { ArticleFileDialog } from '@/features/data-entry/ArticleFileDialog'
import { ProtocolDialog, type ProtocolLine } from '@/features/data-entry/ProtocolDialog'
import {
  type DataEntryRow,
  depositError,
  incompleteEan,
  mergeVisible,
  netValue,
  productFileRow,
  suggestedPrice,
  vatMismatch,
  withScannedProduct,
  withTrailingEmptyRow,
} from '@/features/data-entry/rows'
import { SaveSettingsDialog } from '@/features/data-entry/SaveSettingsDialog'
import { useSuppliers } from '@/features/documents/api'
import { formatDocumentDate, formatMoney } from '@/features/documents/format'
import { type SalesChartTarget, useSalesReportsOverview } from '@/features/sales/api'
import { useCanSeeArticleStats } from '@/features/sales/ArticleStatsButton'
import { SalesChartDialog } from '@/features/sales/SalesChartDialog'
import { usePriceRoundingRules } from '@/features/store-settings/api'
import { TOOL_ROUTES } from '@/features/tools/categories'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { ApiError, fetchNamedFile, fetchPostedFile, saveBlob } from '@/lib/api'
import { setFileQueueDocument } from '@/lib/file-queue'
import { addDays, todayIso } from '@/lib/dates'
import { useProductFileFolder, useProtoFolder } from '@/lib/proto-files'
import { ensureFolderAccess, saveToFolder } from '@/lib/saved-folders'
import type { BaseDelivery, StoreSummary, Supplier } from '@/lib/types'
import { cn } from '@/lib/utils'
import { type RootState, useAppDispatch, useAppSelector } from '@/store'
import { setDataEntryDraft, setDataEntrySaved } from '@/store/dataEntryDraftSlice'
import {
  type BaseDeliveryDraft,
  type BaseDeliveryView,
  EMPTY_BASE_DELIVERY,
  setBaseDeliveryDraft,
} from '@/store/baseDeliveryDraftSlice'
import { useUndoHistory, useUndoShortcuts } from '@/features/data-entry/undo-history'

import { useBaseDelivery, useMeaOverview, useUploadBaseDelivery } from './api'
import {
  type ColumnFilters,
  deliverySuppliers,
  documentPrice,
  EMPTY_VALUE,
  isNewArticle,
  negativeMargin,
  newArticlesBySupplier,
  notDelivered,
  NOTE_FILE_FOLDER,
  withDeliveryProduct,
  PLU_FOLDER,
  PLU_REGIONS,
  pluEans,
  supplierKey,
  VIEW_LABELS,
  VIEW_REGIONS,
  verificationRows,
  viewRows,
} from './base-rows'
import { CarrierDialog } from './CarrierDialog'
import { ColumnFilter, type FilterValue } from './ColumnFilter'
import { DeliveryPickerDialog } from './DeliveryPickerDialog'
import { PortalDeliveryDialog } from './PortalDeliveryDialog'
import { DifferencesDialog } from './DifferencesDialog'
import { meaPath } from './paths'
import { Itm8MacroDialog } from './Itm8MacroDialog'
import { ShortDatesDialog } from './ShortDatesDialog'

/**
 * Dostawa z bazy: dokument dostawy z bazy (plik .xls) w tabeli jak we wprowadzaniu danych, bez faktur. Nośniki do
 * tabeli (np. protokół MM), weryfikacja cen warzyw i mięsa pakowanego z cenami z dokumentu, raport krótkich
 * terminów, nowe artykuły do pliku CADBEZP, kontrola kaucji i ujemnych marż. Tylko na komputerze.
 */
export function BaseDeliveryPage() {
  const store = useCurrentStore()
  return (
    <>
      <span data-wide-page hidden />
      {!store ? (
        <>
          <PageHeader title="Dostawa z bazy" />
          <FullPageMessage
            title="Brak sklepu"
            description="Dostawy z bazy wprowadza się osobno w każdym sklepie."
            inline
          />
        </>
      ) : (
        <>
          <div className="lg:hidden">
            <PageHeader title="Dostawa z bazy" />
            <FullPageMessage
              title="Otwórz na komputerze"
              description="Tabela dostawy działa jak arkusz Excela i potrzebuje dużego ekranu oraz klawiatury."
              inline
            />
          </div>
          <div className="hidden min-h-0 flex-1 flex-col lg:flex">
            <StoreBaseDelivery key={store.id} store={store} />
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

/**
 * Szybki filtr tabeli: ujemne marże, nowe artykuły, towar, który nie przyjechał, krótkie terminy, błędy kaucji albo
 * błędny VAT
 * (liczniki nad tabelą są przyciskami tych filtrów).
 */
type QuickFilter = 'negative' | 'new' | 'missing' | 'short' | 'deposit' | 'vat'

const QUICK_FILTERS: Record<QuickFilter, (row: DataEntryRow) => boolean> = {
  negative: negativeMargin,
  new: isNewArticle,
  missing: notDelivered,
  short: (row) => row.base?.shortDated === true,
  deposit: (row) => depositError(row) !== null,
  vat: vatMismatch,
}

/** Sortowanie tabeli po kliknięciu nagłówka kolumny. */
type SortState = { key: ColumnKey; direction: 'asc' | 'desc' }

/**
 * Filtr zastosowany na wiersze z chwili zmiany filtra (jak w Excelu): wiersz poprawiony tak, że już nie pasuje, nie
 * znika spod kursora, a wiersze dodane później są widoczne.
 */
type AppliedFilter = {
  filters: ColumnFilters
  quick: QuickFilter | null
  known: Set<string>
  pass: Set<string>
}

function StoreBaseDelivery({ store }: { store: StoreSummary }) {
  const dispatch = useAppDispatch()
  const reduxStore = useStore<RootState>()
  const draft = useAppSelector((state) => state.baseDeliveryDraft.drafts[store.id]) ?? EMPTY_BASE_DELIVERY
  const { rows, view } = draft
  // Pliki tego bonu (nowe artykuły, zmiana cen, plik dostawy…) są w kolejce plików sklepu jednym wierszem.
  const queueBon = draft.delivery?.number ?? null
  useEffect(() => {
    setFileQueueDocument(store.id, queueBon ? { key: `bon:${queueBon}`, label: `Bon z bazy ${queueBon}` } : null)
    return () => setFileQueueDocument(store.id, null)
  }, [store.id, queueBon])
  const currentDraft = useCallback(
    () => reduxStore.getState().baseDeliveryDraft.drafts[store.id] ?? EMPTY_BASE_DELIVERY,
    [reduxStore, store.id],
  )
  const applyDraft = useCallback(
    (next: BaseDeliveryDraft) => dispatch(setBaseDeliveryDraft({ storeId: store.id, draft: next })),
    [dispatch, store.id],
  )
  const history = useUndoHistory(currentDraft, applyDraft)
  const { record } = history
  const setDraft = useCallback(
    (next: BaseDeliveryDraft, { record: recorded = true }: { record?: boolean } = {}) => {
      const before = currentDraft()
      if (recorded && next !== before) record(before)
      applyDraft(next)
    },
    [applyDraft, currentDraft, record],
  )
  const setRows = useCallback(
    (update: (rows: DataEntryRow[]) => DataEntryRow[], options?: { record?: boolean }) => {
      const current = currentDraft()
      const next = update(current.rows)
      if (next !== current.rows) setDraft({ ...current, rows: next }, options)
    },
    [currentDraft, setDraft],
  )
  useUndoShortcuts(history.undo, history.redo)

  const queryClient = useQueryClient()
  const deliveryQuery = useBaseDelivery(store.id, draft.delivery?.id ?? null)
  const delivery = deliveryQuery.data ?? null
  // Tabela ma wiersze z chwili wczytania (też z localStorage), a artykuły po „Dodaj nowe artykuły” i raporcie kartoteki
  // przestają być nowe dopiero w świeżym dokumencie.
  useEffect(() => {
    if (!delivery) return
    const lines = new Map(delivery.lines.map((line) => [line.id, line]))
    setRows(
      (current) => {
        const next = current.map((row) => (row.base ? withDeliveryProduct(row, lines.get(row.base.lineId)) : row))
        return next.some((row, index) => row !== current[index]) ? next : current
      },
      { record: false },
    )
  }, [delivery, setRows])
  const { data: nomenclature = [] } = useDataEntryNomenclature(store.id)
  const names = useMemo(() => nomenclatureNames(nomenclature), [nomenclature])
  const { data: rounding } = usePriceRoundingRules(store.id)
  // Warzywa i mięso pakowane: cena sugerowana to cena dla klienta z dokumentu (cena z opakowania).
  const pricing = useMemo(
    () => ({ ...pricingOf(nomenclature, rounding), rowPrice: view === 'carriers' ? undefined : documentPrice }),
    [nomenclature, rounding, view],
  )
  const { data: suppliers } = useSuppliers(store.id)
  const supplierFor = useCallback(
    (code: string): Supplier | null => suppliers?.find((supplier) => supplierKey(supplier.code) === code) ?? null,
    [suppliers],
  )
  const mea = useMeaOverview(store.id)
  const gridRef = useRef<GridHandle>(null)
  const [toolbarHeight, setToolbarHeight] = useState(0)
  const { data: priceControlPending = 0 } = usePriceControlPendingCount(store.id, true)

  const canSeeSales = useCanSeeArticleStats()
  const salesOverview = useSalesReportsOverview(store.id, canSeeSales)
  const [chartTarget, setChartTarget] = useState<SalesChartTarget | null>(null)
  const showArticle = useCallback(
    (row: DataEntryRow) =>
      row.itm8 &&
      setChartTarget({ kind: 'article', itm8: row.itm8, name: row.name, ean: row.ean, brand: row.brand || null }),
    [],
  )

  const [pickerOpen, setPickerOpen] = useState(false)
  // Dokument w oknie nośników zostaje po zamknięciu, żeby okno nie znikało puste w trakcie animacji.
  const [carrierFor, setCarrierFor] = useState<BaseDelivery | null>(null)
  const [carrierOpen, setCarrierOpen] = useState(false)
  const showCarriers = (loaded: BaseDelivery) => {
    setCarrierFor(loaded)
    setCarrierOpen(true)
  }
  const [shortDatesOpen, setShortDatesOpen] = useState(false)
  const [itm8MacroOpen, setItm8MacroOpen] = useState(false)
  const [differencesOpen, setDifferencesOpen] = useState(false)
  const [protocolOpen, setProtocolOpen] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [selectedRows, setSelectedRows] = useState<string[]>([])

  // ---------- filtry

  const [applied, setApplied] = useState<AppliedFilter | null>(null)
  const passes = useCallback(
    (row: DataEntryRow, filters: ColumnFilters, quick: QuickFilter | null) => {
      if (quick && !QUICK_FILTERS[quick](row)) return false
      return Object.entries(filters).every(([key, allowed]) =>
        allowed.includes(cellText(row, key as ColumnKey, pricing) || EMPTY_VALUE),
      )
    },
    [pricing],
  )
  const applyFilter = (filters: ColumnFilters, quick: QuickFilter | null) => {
    if (!quick && Object.keys(filters).length === 0) {
      setApplied(null)
      return
    }
    const all = currentDraft().rows
    setApplied({
      filters,
      quick,
      known: new Set(all.map((row) => row.id)),
      pass: new Set(all.filter((row) => passes(row, filters, quick)).map((row) => row.id)),
    })
  }
  const setColumnFilter = (key: ColumnKey, selected: string[] | null) => {
    const filters = { ...(applied?.filters ?? {}) }
    if (selected === null) delete filters[key]
    else filters[key] = selected
    applyFilter(filters, applied?.quick ?? null)
  }
  const toggleQuick = (quick: QuickFilter) =>
    applyFilter(applied?.filters ?? {}, applied?.quick === quick ? null : quick)

  // ---------- sortowanie: klik w nagłówek układa wiersze tabeli (jak w Excelu; Ctrl+Z cofa), puste zawsze na końcu.

  const [sort, setSort] = useState<SortState | null>(null)
  const sortBy = (key: ColumnKey) => {
    const direction = sort?.key === key && sort.direction === 'asc' ? 'desc' : 'asc'
    const numeric = BASE_COLUMNS.find((column) => column.key === key)?.align === 'right'
    const sign = direction === 'asc' ? 1 : -1
    setSort({ key, direction })
    setRows((current) =>
      [...current].sort((a, b) => {
        const left = cellText(a, key, pricing)
        const right = cellText(b, key, pricing)
        if (left === '' || right === '') return left === right ? 0 : left === '' ? 1 : -1
        const order = numeric
          ? Number(left.replace(',', '.')) - Number(right.replace(',', '.'))
          : left.localeCompare(right, 'pl', { numeric: true })
        return order * sign
      }),
    )
  }

  /** Nowa zawartość tabeli: bez filtrów i strzałki sortowania. */
  const resetView = () => {
    setApplied(null)
    setSort(null)
  }

  const visibleOf = useCallback(
    (all: DataEntryRow[]) =>
      applied ? all.filter((row) => !applied.known.has(row.id) || applied.pass.has(row.id)) : all,
    [applied],
  )
  const visibleRows = useMemo(() => visibleOf(rows), [visibleOf, rows])
  const setVisibleRows = useCallback(
    (update: (rows: DataEntryRow[]) => DataEntryRow[]) =>
      setRows((current) => {
        const visible = visibleOf(current)
        const merged = mergeVisible(current, visible, update(visible))
        return withSyncedDuplicates(current, merged)
      }),
    [setRows, visibleOf],
  )

  // Wartości kolumny do filtra liczone dopiero po otwarciu jego listy (z tabeli z tej chwili), nie przy każdej zmianie.
  const columnValues = (column: Column): FilterValue[] => {
    const counts = new Map<string, number>()
    for (const row of currentDraft().rows) {
      const value = cellText(row, column.key, pricing) || EMPTY_VALUE
      counts.set(value, (counts.get(value) ?? 0) + 1)
    }
    const numeric = column.align === 'right'
    return [...counts.entries()]
      .map(([value, count]) => ({ value, count }))
      .sort((a, b) =>
        a.value === EMPTY_VALUE
          ? 1
          : b.value === EMPTY_VALUE
            ? -1
            : numeric
              ? Number(a.value.replace(',', '.')) - Number(b.value.replace(',', '.'))
              : a.value.localeCompare(b.value, 'pl'),
      )
  }

  const headerExtra = (column: Column) => (
    <ColumnFilter
      label={column.title ?? column.header}
      getValues={() => columnValues(column)}
      selected={applied?.filters[column.key] ?? null}
      onChange={(selected) => setColumnFilter(column.key, selected)}
    />
  )

  // ---------- dokument i nośniki

  const [portalOpen, setPortalOpen] = useState(false)

  const openDelivery = (loaded: BaseDelivery) => {
    queryClient.setQueryData(['stores', store.id, 'base-deliveries', loaded.id], loaded)
    setPickerOpen(false)
    setPortalOpen(false)
    loadCarriers(
      loaded,
      loaded.carriers.map((carrier) => carrier.number),
    )
  }

  // „Wczytaj dokument z bazy” od razu otwiera wybór pliku; wgrany dokument trafia do tabeli ze wszystkimi nośnikami
  // (zawęzić je można później przyciskiem „Nośniki”).
  const fileInput = useRef<HTMLInputElement>(null)
  const upload = useUploadBaseDelivery(store.id)
  const uploadFile = (file: File) =>
    upload.mutate(file, {
      onSuccess: (loaded) => {
        toast.success(`Wgrano bon ${loaded.number}`, {
          description: `${loaded.lines.length} linii na ${loaded.carriers.length} nośnikach`,
        })
        openDelivery(loaded)
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

  const chooseCarriers = (carriers: string[]) => {
    setCarrierOpen(false)
    if (carrierFor) loadCarriers(carrierFor, carriers)
  }

  function loadCarriers(loaded: BaseDelivery, carriers: string[]) {
    const before = currentDraft()
    const next = viewRows(loaded, 'carriers', carriers)
    resetView()
    setDraft({
      delivery: { id: loaded.id, number: loaded.number, deliveryDate: loaded.deliveryDate },
      view: 'carriers',
      carriers,
      rows: next,
      previous: null,
    })
    const fresh = next.filter(isNewArticle).length
    toast.success(`Nośniki w tabeli: ${carriers.length}`, {
      description: `${next.length} ${pieces(next.length)}, ${fresh === 0 ? 'wszystkie są w kartotece' : `nowych: ${fresh}`}`,
      action: before.rows.length > 0 ? { label: 'Przywróć', onClick: () => setDraft(before) } : undefined,
    })
  }

  // Weryfikacja warzyw i mięsa pakowanego idzie we wprowadzaniu danych: tam są ceny sugerowane z dokumentu (cena dla
  // klienta z bazy), zmiana cen dla dostawcy z bazy i etykiety. Artykuły w promocji są odrzucane. Tabela wprowadzania
  // danych jest zastępowana („Przywróć” w toaście oddaje poprzednią).
  const navigate = useNavigate()
  const verify = (target: Exclude<BaseDeliveryView, 'carriers'>) => {
    if (!delivery) return
    const { rows: next, skipped } = verificationRows(delivery, target)
    const counts = new Map<string, number>()
    for (const row of next) {
      const key = supplierKey(row.base?.supplierCode) || '538'
      counts.set(key, (counts.get(key) ?? 0) + 1)
    }
    const code = [...counts.entries()].sort((a, b) => b[1] - a[1])[0]?.[0] ?? '538'
    const supplier = supplierFor(code)
    const state = reduxStore.getState()
    const before = state.dataEntryDraft.drafts[store.id] ?? null
    const beforeSaved = state.dataEntryDraft.saved[store.id] ?? null
    dispatch(setDataEntrySaved({ storeId: store.id, saved: null }))
    dispatch(
      setDataEntryDraft({
        storeId: store.id,
        draft: {
          invoice: {
            id: null,
            number: delivery.number,
            sellerName: supplier?.name ?? null,
            sellerNip: supplier?.nip ?? null,
            issueDate: delivery.deliveryDate,
            supplier: supplier ? { id: supplier.id, name: supplier.name, nip: supplier.nip } : null,
          },
          rows: withTrailingEmptyRow(next),
        },
      }),
    )
    navigate(TOOL_ROUTES.DATA_ENTRY.path)
    toast.success(VIEW_LABELS[target], {
      description: `${next.length} ${pieces(next.length)} z bonu ${delivery.number} we wprowadzaniu danych.${skipped > 0 ? ` Odrzucone, bo w promocji: ${skipped}.` : ''} Cena sugerowana to cena z dokumentu bazy: zaznacz wiersze i „Ustaw ceny sugerowane”.`,
      action:
        before && before.rows.some((row) => row.ean !== '' || row.name !== '')
          ? {
              label: 'Przywróć',
              onClick: () => {
                dispatch(setDataEntryDraft({ storeId: store.id, draft: before }))
                dispatch(setDataEntrySaved({ storeId: store.id, saved: beforeSaved }))
              },
            }
          : undefined,
    })
  }

  const backToCarriers = () => {
    const current = currentDraft()
    resetView()
    setDraft({
      ...current,
      view: 'carriers',
      carriers: current.previous?.carriers ?? current.carriers,
      rows: current.previous?.rows ?? [],
      previous: null,
    })
  }

  const clearAll = () => {
    const before = currentDraft()
    resetView()
    setDraft(EMPTY_BASE_DELIVERY)
    toast('Wyczyszczono tabelę', { action: { label: 'Przywróć', onClick: () => setDraft(before) } })
  }

  // ---------- EAN wpisany ręcznie: artykuł z kartoteki jak we wprowadzaniu danych

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
                  ? withScannedProduct(row, byKey.get(row.ean.replace(/^0+/, '')) ?? null)
                  : row,
              ),
            { record: false },
          )
        })
        .catch(() => toast.error('Nie udało się sprawdzić EAN-ów w kartotece'))
    },
    [currentDraft, setRows, store.id],
  )

  // ---------- wiersze

  const removeRows = (ids: string[]) => {
    const before = currentDraft()
    setRows((current) => current.filter((row) => !ids.includes(row.id)))
    toast(`Usunięto ${ids.length} ${pieces(ids.length)}`, {
      action: { label: 'Przywróć', onClick: () => setDraft(before) },
    })
    gridRef.current?.focus()
  }

  const priceChanges = useMemo(() => {
    const selectedIds = new Set(selectedRows)
    const changes = new Map<string, number>()
    for (const row of visibleRows) {
      if (!selectedIds.has(row.id)) continue
      const price = suggestedPrice(row, pricing)
      if (price !== null && price !== row.salePrice) changes.set(row.id, price)
    }
    return changes
  }, [selectedRows, visibleRows, pricing])
  const applySuggestedPrices = () => {
    const before = currentDraft()
    const changes = new Map(priceChanges)
    setRows((current) =>
      current.map((row) => (changes.has(row.id) ? { ...row, salePrice: changes.get(row.id) ?? null } : row)),
    )
    toast.success(`Ustawiono cenę sugerowaną: ${changes.size} ${pieces(changes.size)}`, {
      action: { label: 'Przywróć', onClick: () => setDraft(before) },
    })
    gridRef.current?.focus()
  }

  // Liczniki z całej tabeli i dokumentu liczą się tylko przy zmianie danych: zaznaczanie i strzałki odświeżają stronę
  // przy każdym ruchu, a przy ponad tysiącu wierszy przeliczanie wszystkiego od nowa zacinało tabelę.
  const tableStats = useMemo(
    () => ({
      quick: Object.fromEntries(
        (Object.keys(QUICK_FILTERS) as QuickFilter[]).map((quick) => [quick, rows.filter(QUICK_FILTERS[quick]).length]),
      ) as Record<QuickFilter, number>,
      net: rows.reduce((sum, row) => sum + (netValue(row) ?? 0), 0),
      incompleteEans: rows.filter(incompleteEan).length,
    }),
    [rows],
  )
  const quickCounts = tableStats.quick
  const negativeCount = quickCounts.negative
  const depositErrorCount = quickCounts.deposit
  const tableNet = tableStats.net
  const { shortDated, regionCounts, plu } = useMemo(
    () => ({
      shortDated: delivery?.lines.filter((line) => line.shortDated).length ?? 0,
      regionCounts: {
        vegetables: delivery ? verificationRows(delivery, 'vegetables').rows.length : 0,
        meat: delivery ? verificationRows(delivery, 'meat').rows.length : 0,
      },
      // „Sprawdź PLU”: EAN-y artykułów z rejonów 32 i 33 całego dokumentu do pliku .lst w folderze z ustawień zapisu.
      plu: delivery ? pluEans(delivery) : [],
    }),
    [delivery],
  )
  const [savingPlu, setSavingPlu] = useState(false)
  const savePlu = async () => {
    if (!draft.delivery || plu.length === 0) return
    setSavingPlu(true)
    try {
      const folderReady = await ensureFolderAccess(PLU_FOLDER.key)
      const fileName = `PLU_${draft.delivery.number}.LST`
      if (folderReady) await saveToFolder(PLU_FOLDER.key, fileName, lstFile(plu))
      else saveBlob(lstFile(plu), fileName)
      toast.success('Zapisano listę do sprawdzenia PLU', {
        description: `${plu.length} ${plu.length === 1 ? 'EAN' : 'EAN-ów'} z nomenklatury ${PLU_REGIONS.join(' i ')}${folderReady ? '' : ' (pobrany: folder nie jest wybrany w ustawieniach zapisu)'}`,
      })
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Nie udało się zapisać listy EAN-ów')
    } finally {
      setSavingPlu(false)
    }
  }

  // Plik dostawy dla programu sklepu (układ pliku bazy) z całego dokumentu, osobno dla dostawcy (538, 88110),
  // zapisywany od razu w folderze z ustawień zapisu; bez folderu pobranie.
  const [savingNote, setSavingNote] = useState(false)
  const saveNoteFiles = async () => {
    if (!delivery) return
    setSavingNote(true)
    try {
      const folderReady = await ensureFolderAccess(NOTE_FILE_FOLDER.key)
      const saved: string[] = []
      for (const supplier of deliverySuppliers(delivery)) {
        const { fileName, blob } = await fetchNamedFile(
          `/stores/${store.id}/base-deliveries/${delivery.id}/note-file?supplier=${encodeURIComponent(supplier)}`,
          `dostawa-${delivery.number}-${supplier}.TXT`,
        )
        if (folderReady) await saveToFolder(NOTE_FILE_FOLDER.key, fileName, blob)
        else saveBlob(blob, fileName)
        saved.push(`dostawca ${supplier}`)
      }
      toast.success(`Zapisano plik dostawy ${delivery.number}`, {
        description: `${saved.join(', ')}${folderReady ? '' : ' (pobrany: folder nie jest wybrany w ustawieniach zapisu)'}`,
      })
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Nie udało się zapisać pliku dostawy')
    } finally {
      setSavingNote(false)
    }
  }

  const incompleteEanCount = tableStats.incompleteEans
  const blocked = depositErrorCount > 0 || incompleteEanCount > 0
  const blockedTitle =
    incompleteEanCount > 0
      ? `W tabeli są niepełne EAN-y (${incompleteEanCount}): popraw je, zanim zapiszesz plik`
      : `W tabeli są błędy kaucji (${depositErrorCount}): popraw kaucję w kartotece Mercalys albo usuń te wiersze`

  // Protokół (np. MM nośnika dla innego sklepu): artykuły z EAN-em i ilością różną od 0.
  const protocolLines = useMemo<ProtocolLine[]>(
    () =>
      rows.flatMap((row) =>
        /^\d{1,14}$/.test(row.ean) && row.quantity ? [{ ean: row.ean, quantity: row.quantity }] : [],
      ),
    [rows],
  )

  // ---------- pliki: zmiana cen (CTRLPRIX) i nowe artykuły (CADBEZP), osobno dla dostawcy (538, 88110)

  const protoFolder = useProtoFolder(store.id)
  const productFolder = useProductFileFolder(store.id)
  const priceRows = useMemo(
    () =>
      rows.filter(
        (row) =>
          row.known &&
          row.ean &&
          row.catalog &&
          row.salePrice !== null &&
          row.salePrice > 0 &&
          row.salePrice !== row.catalog.salePrice,
      ),
    [rows],
  )
  const [saving, setSaving] = useState<'prices' | 'articles' | null>(null)

  const savePriceChanges = async () => {
    setSaving('prices')
    try {
      const folderReady = await ensureFolderAccess(protoFolder.key)
      const groups = new Map<string, DataEntryRow[]>()
      for (const row of priceRows) {
        const key = supplierKey(row.base?.supplierCode) || '538'
        groups.set(key, [...(groups.get(key) ?? []), row])
      }
      const saved: string[] = []
      for (const [code, group] of groups) {
        const supplier = supplierFor(code)
        if (!supplier) throw new Error(`Dostawcy ${code} nie ma na liście dostawców sklepu`)
        const payload: PriceControlPayload = {
          supplierId: supplier.id,
          sellerNip: null,
          invoiceNumber: draft.delivery?.number ?? null,
          rows: group.map((row) => ({ ean: row.ean, salePrice: row.salePrice ?? 0 })),
        }
        const file = await savePriceControlFile(store.id, payload, protoFolder.key, folderReady)
        saved.push(`${file.fileName}${file.downloaded ? ' (pobrany)' : ''}`)
      }
      void queryClient.invalidateQueries({ queryKey: ['stores', store.id, 'data-entry', 'price-control-files'] })
      toast.success('Zapisano zmianę cen', { description: saved.join(', '), duration: 10_000 })
    } catch (error) {
      toast.error(
        error instanceof ApiError || error instanceof Error ? error.message : 'Nie udało się zapisać zmiany cen',
      )
    } finally {
      setSaving(null)
    }
  }

  const productDrafts = useProductDraftConfirm(store.id)
  const newGroups = useMemo(() => (delivery ? newArticlesBySupplier(delivery, rows) : []), [delivery, rows])
  const newArticles = newGroups.reduce((sum, group) => sum + group.rows.length, 0)
  /** Osobny plik CADBEZP dla każdego dostawcy; o wprowadzenie pyta tylko przy nowych artykułach. Zwraca, czy się udało. */
  const saveArticleFiles = async (groups: { supplierCode: string; rows: DataEntryRow[] }[], title: string) => {
    setSaving('articles')
    try {
      const folderReady = await ensureFolderAccess(productFolder.key)
      const saved: string[] = []
      const savedFiles: SavedProductFile[] = []
      for (const group of groups) {
        const supplier = supplierFor(group.supplierCode)
        const payload: ProductFilePayload = {
          supplierId: supplier?.id ?? null,
          sellerNip: null,
          sellerName: supplier?.name ?? `BAZA ${group.supplierCode}`,
          invoiceNumber: draft.delivery?.number ?? null,
          rows: group.rows.map(productFileRow),
        }
        const { fileName, blob } = await fetchPostedFile(
          `/stores/${store.id}/data-entry/export/product-file`,
          payload,
          'CADBEZP.xls',
        )
        if (folderReady) await saveToFolder(productFolder.key, fileName, blob)
        else saveBlob(blob, fileName)
        saved.push(
          `dostawca ${supplier?.code ?? group.supplierCode}: ${group.rows.length}${folderReady ? '' : ' (pobrany)'}`,
        )
        const fresh = group.rows.filter((row) => !row.known)
        if (fresh.length > 0) {
          savedFiles.push({
            fileName,
            payload: { ...payload, rows: fresh.map(productFileRow) },
            supplierCode: supplier?.code ?? group.supplierCode,
          })
        }
      }
      toast.success(title, { description: saved.join(', '), duration: 10_000 })
      productDrafts.ask(savedFiles)
      return true
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać pliku nowych artykułów')
      return false
    } finally {
      setSaving(null)
    }
  }
  const saveNewArticles = () => saveArticleFiles(newGroups, `Zapisano nowe artykuły: ${newArticles}`)

  // „Zmień artykuły”: plik CADBEZP z artykułów wybranych w oknie, także niezmienionych.
  const [articleFileOpen, setArticleFileOpen] = useState(false)
  const saveChosenArticles = async (chosen: DataEntryRow[]) => {
    const groups = new Map<string, DataEntryRow[]>()
    for (const row of chosen) {
      const key = supplierKey(row.base?.supplierCode) || '538'
      groups.set(key, [...(groups.get(key) ?? []), row])
    }
    const files = [...groups.entries()].map(([supplierCode, rows]) => ({ supplierCode, rows }))
    if (await saveArticleFiles(files, `Zapisano plik artykułów: ${chosen.length}`)) setArticleFileOpen(false)
  }

  const meaPending = mea.data?.pending.length ?? 0
  const filtersActive = applied !== null

  return (
    <>
      <PriceControlPendingBanner storeId={store.id} count={priceControlPending} />
      <PageHeader
        title="Dostawa z bazy"
        // Bon i data są w pasku poleceń, a liczba wybranych nośników przy przycisku „Nośniki”.
        description={!draft.delivery ? storeLabel(store) : view !== 'carriers' ? VIEW_LABELS[view] : undefined}
      />

      <HowTo
        id="base-delivery"
        className="mb-4"
        title="Jak wprowadzić dostawę z bazy"
        steps={[
          '„Wczytaj”: dokument z portalu, z dysku albo wgrany wcześniej. Artykuły wszystkich nośników trafią do tabeli z ilościami w sztukach; „Nośniki” je zawęża. Linie, które nie przyjechały, mają ilość 0.',
          'Sprawdź kaucje (czerwona komórka = inna kaucja w dokumencie niż w kartotece; blokuje zmianę cen i protokół) i ujemne marże na cenach cadencier.',
          '„Wytwórz”: nowe i zmienione artykuły, zmiana cen, protokół, plik dostawy, raport krótkich terminów i makro ITM8.',
          '„Weryfikuj”: warzywa albo mięso pakowane z całego dokumentu (bez tych w promocji) idą do wprowadzania danych z ceną sugerowaną z bazy. Tam zaznacz (Ctrl+A), „Ustaw ceny sugerowane”, „Zmień ceny” i „Etykiety”.',
          'Filtr w nagłówku każdej kolumny działa jak w Excelu; Ctrl+Z cofa.',
        ]}
      />

      <CommandBar onHeightChange={setToolbarHeight}>
        {draft.delivery && (
          <div className="flex shrink-0 flex-col items-center justify-center rounded-md border-2 border-foreground/20 bg-card px-2 py-1 text-center">
            <span className="text-xs leading-tight text-muted-foreground">Bon z bazy</span>
            <span className="flex items-center gap-0.5">
              <span className="text-xl leading-tight font-bold tracking-tight tabular-nums">
                {draft.delivery.number}
              </span>
              <CopyButton value={draft.delivery.number} label="Kopiuj numer bonu" />
            </span>
            <span className="flex items-center gap-0.5 text-xs leading-tight">
              {formatDocumentDate(draft.delivery.deliveryDate)}
              <CopyButton value={formatDocumentDate(draft.delivery.deliveryDate)} label="Kopiuj datę dostawy" />
            </span>
          </div>
        )}
        <div className="flex shrink-0 gap-1.5" role="group" aria-label="Pliki">
          <ActionMenu
            label="Wczytaj"
            icon={<FileInputIcon />}
            tone="load"
            tile
            loading={upload.isPending}
            title="Wczytaj dokument z bazy: z portalu, z dysku albo wgrany wcześniej"
          >
            <ActionItem
              icon={<CloudDownloadIcon />}
              label="Z portalu Grupy Muszkieterów"
              hint="Bon prosto z portalu, bez zapisywania pliku"
              onClick={() => setPortalOpen(true)}
            />
            <ActionItem
              icon={<FileUpIcon />}
              label="Plik z dysku"
              hint="Dokument z bazy zapisany jako .xls, .xlsx albo .htm"
              onClick={() => fileInput.current?.click()}
            />
            <DropdownMenuSeparator />
            <ActionItem
              icon={<HistoryIcon />}
              label="Wgrane wcześniej"
              hint="Dokumenty z bazy wgrane w tym sklepie"
              onClick={() => setPickerOpen(true)}
            />
          </ActionMenu>
          {draft.delivery && (
            <ActionMenu
              label="Wytwórz"
              icon={<FileOutputIcon />}
              tone="make"
              tile
              loading={saving !== null || savingNote || savingPlu}
              title="Pliki dla programu sklepu, wydruki i makra z dokumentu"
            >
              {blocked && (
                <p className="mb-1 rounded-md bg-amber-200 px-2 py-1.5 text-xs font-medium text-amber-950 dark:bg-amber-900 dark:text-amber-50">
                  {blockedTitle}
                </p>
              )}
              <DropdownMenuGroup>
                <DropdownMenuLabel>Kartoteka</DropdownMenuLabel>
                <ActionItem
                  icon={<FilePlusIcon />}
                  label="Dodaj nowe artykuły"
                  count={newArticles}
                  disabled={newArticles === 0}
                  hint={
                    newArticles > 0
                      ? 'Nowe artykuły całego dokumentu z danymi z bazy, osobno dla każdego dostawcy'
                      : 'Wszystkie artykuły dokumentu są w kartotece'
                  }
                  onClick={() => void saveNewArticles()}
                />
                <ActionItem
                  icon={<PackageCheckIcon />}
                  label="Zmień artykuły"
                  disabled={rows.length === 0}
                  hint="Wybierz artykuły tabeli do zmiany w kartotece; zmiana przepisuje je na dostawcę dokumentu"
                  onClick={() => setArticleFileOpen(true)}
                />
                <ActionItem
                  icon={<TagIcon />}
                  label="Zmień ceny"
                  count={priceRows.length}
                  disabled={priceRows.length === 0 || blocked}
                  hint={
                    blocked
                      ? 'Najpierw popraw błędy opisane wyżej'
                      : priceRows.length > 0
                        ? 'Artykuły z kartoteki ze zmienioną w tabeli ceną sprzedaży'
                        : 'Żaden artykuł z kartoteki nie ma w tabeli zmienionej ceny sprzedaży'
                  }
                  onClick={() => void savePriceChanges()}
                />
              </DropdownMenuGroup>
              <DropdownMenuSeparator />
              <DropdownMenuGroup>
                <DropdownMenuLabel>Pliki i wydruki</DropdownMenuLabel>
                <ActionItem
                  icon={<ClipboardListIcon />}
                  label="Protokół"
                  disabled={protocolLines.length === 0 || blocked}
                  hint={
                    blocked
                      ? 'Najpierw popraw błędy opisane wyżej'
                      : protocolLines.length > 0
                        ? 'Np. MM wybranych nośników dla innego sklepu'
                        : 'Żaden artykuł tabeli nie ma EAN-u i ilości'
                  }
                  onClick={() => setProtocolOpen(true)}
                />
                <ActionItem
                  icon={<FileDownIcon />}
                  label="Plik dostawy"
                  disabled={!delivery}
                  hint="Plik dostawy dla programu sklepu z całego dokumentu, osobno dla każdego dostawcy"
                  onClick={() => void saveNoteFiles()}
                />
                <ActionItem
                  icon={<CalendarClockIcon />}
                  label="Raport krótkich terminów"
                  count={shortDated}
                  disabled={!delivery}
                  hint="Krótkie terminy całego dokumentu do wydruku, z miejscem na podpisy"
                  onClick={() => setShortDatesOpen(true)}
                />
                {plu.length > 0 && (
                  <ActionItem
                    icon={<ScanBarcodeIcon />}
                    label="Sprawdź PLU"
                    count={plu.length}
                    hint={`Lista EAN-ów artykułów z nomenklatury ${PLU_REGIONS.join(' i ')} do sprawdzenia na wadze`}
                    onClick={() => void savePlu()}
                  />
                )}
              </DropdownMenuGroup>
              <DropdownMenuSeparator />
              <DropdownMenuGroup>
                <DropdownMenuLabel>Makro</DropdownMenuLabel>
                <ActionItem
                  icon={<KeyboardIcon />}
                  label="Uzupełnij ITM8"
                  disabled={!delivery}
                  hint="Do schowka, Alt+E: kody ITM8 artykułom z dokumentu w Mercalys"
                  onClick={() => setItm8MacroOpen(true)}
                />
              </DropdownMenuGroup>
            </ActionMenu>
          )}
        </div>

        <div className="flex min-w-0 flex-1 flex-col justify-center gap-2">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1.5" role="group" aria-label="Dokument">
            {draft.delivery && (
              <>
                {view !== 'carriers' ? (
                  <Button variant="outline" onClick={backToCarriers}>
                    <ArrowLeftIcon />
                    Wróć do nośników
                  </Button>
                ) : (
                  <Button
                    variant="outline"
                    disabled={!delivery}
                    onClick={() => delivery && showCarriers(delivery)}
                    title="Wybierz nośniki do tabeli (zastąpią wiersze)"
                  >
                    <LayersIcon />
                    Nośniki
                    {delivery && (
                      <span className="text-muted-foreground tabular-nums">
                        {draft.carriers.length}/{delivery.carriers.length}
                      </span>
                    )}
                  </Button>
                )}
                {/* Weryfikacja tylko, gdy dokument ma artykuły z rejonu. */}
                {(regionCounts.vegetables > 0 || regionCounts.meat > 0) && (
                  <ActionMenu
                    label="Weryfikuj"
                    icon={<ListChecksIcon />}
                    title="Artykuły rejonu z całego dokumentu (bez promocji) do wprowadzania danych, cena sugerowana z bazy"
                  >
                    {regionCounts.vegetables > 0 && (
                      <ActionItem
                        icon={<CarrotIcon />}
                        label="Warzywa"
                        count={regionCounts.vegetables}
                        hint={`Rejon ${VIEW_REGIONS.vegetables}, cena sugerowana z bazy`}
                        onClick={() => verify('vegetables')}
                      />
                    )}
                    {regionCounts.meat > 0 && (
                      <ActionItem
                        icon={<BeefIcon />}
                        label="Mięso pakowane"
                        count={regionCounts.meat}
                        hint={`Rejon ${VIEW_REGIONS.meat}, cena sugerowana z bazy (cena z opakowania)`}
                        onClick={() => verify('meat')}
                      />
                    )}
                  </ActionMenu>
                )}
                <Button
                  variant="outline"
                  disabled={rows.length === 0}
                  onClick={() => setDifferencesOpen(true)}
                  title="Porównaj tabelę z tymczasowymi bonami dostawy z Mercalys (538, 88110): czego brakuje w programie albo co się różni"
                >
                  <FileSearchIcon />
                  Znajdź różnice
                </Button>
              </>
            )}
            <div className="ml-auto flex items-center gap-1">
              <Button
                variant="outline"
                nativeButton={false}
                render={<Link to={meaPath} />}
                className={cn(meaPending > 0 && ACTION_TONES.warn)}
                title="Kody MEA z dokumentów bazy, których jeszcze nie wpisano w Mercalys"
              >
                <HashIcon />
                Kody MEA
                {meaPending > 0 && <span className="tabular-nums">{meaPending}</span>}
              </Button>
              <Button
                variant="ghost"
                size="icon"
                className="text-muted-foreground"
                onClick={() => setSettingsOpen(true)}
                aria-label="Ustawienia zapisu"
                title="Ustawienia zapisu: foldery na nowe artykuły, protokoły, zmianę cen, plik dostawy i PLU"
              >
                <SettingsIcon />
              </Button>
            </div>
          </div>

          {rows.length > 0 && (
            <div className="flex flex-wrap items-center gap-x-2 gap-y-1.5" role="group" aria-label="Tabela">
              <div className="flex items-center gap-1" role="group" aria-label="Zaznaczone wiersze">
                <Button
                  variant="outline"
                  size="icon"
                  onClick={() => gridRef.current?.selectAll()}
                  aria-label="Zaznacz wszystkie"
                  title="Zaznacz wszystkie widoczne pozycje (Ctrl+A)"
                >
                  <CheckCheckIcon />
                </Button>
                <Button
                  variant="outline"
                  size="icon"
                  onClick={() => removeRows(selectedRows)}
                  disabled={selectedRows.length === 0}
                  aria-label="Usuń zaznaczone wiersze"
                  title={
                    selectedRows.length > 0
                      ? `Usuń zaznaczone wiersze (${selectedRows.length}) (Ctrl+−)`
                      : 'Zaznacz wiersze w tabeli'
                  }
                >
                  <Trash2Icon />
                </Button>
              </div>
              <ToolbarDivider />
              <Button
                variant="outline"
                onClick={applySuggestedPrices}
                disabled={priceChanges.size === 0}
                title={
                  priceChanges.size > 0
                    ? 'Wpisuje cenę sugerowaną jako cenę sprzedaży w zaznaczonych wierszach'
                    : 'Zaznacz wiersze w tabeli (Shift + klik, Ctrl+A)'
                }
              >
                <BadgePercentIcon />
                Ustaw ceny sugerowane
                {priceChanges.size > 0 && <span className="tabular-nums">({priceChanges.size})</span>}
              </Button>
              {(negativeCount > 0 || applied?.quick === 'negative') && (
                <QuickToggle
                  active={applied?.quick === 'negative'}
                  alert
                  onClick={() => toggleQuick('negative')}
                  title="Artykuły z ceną cadencier (niebieską), bez promocji, z marżą poniżej zera przy aktualnej cenie sprzedaży"
                >
                  <TrendingDownIcon />
                  Ujemne marże ({negativeCount})
                </QuickToggle>
              )}
              {filtersActive && (
                <Button variant="ghost" onClick={() => applyFilter({}, null)}>
                  <FilterXIcon />
                  Pokaż wszystkie ({visibleRows.length}/{rows.length})
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
      </CommandBar>

      {draft.delivery && deliveryQuery.isError && (
        <p className="mb-3 rounded-lg bg-amber-200 px-3 py-2 text-sm font-medium text-amber-950 dark:bg-amber-900 dark:text-amber-50">
          Nie udało się wczytać dokumentu {draft.delivery.number} (mógł zostać wgrany ponownie). Tabela działa, ale
          krótkie terminy, nowe artykuły i weryfikacja wymagają ponownego wczytania dokumentu.
        </p>
      )}

      {rows.length === 0 ? (
        <div className="flex flex-col items-center gap-3 rounded-xl border border-dashed px-6 py-16 text-center">
          <p className="text-base font-medium">Tabela jest pusta</p>
          <p className="max-w-md text-sm text-muted-foreground">
            Wczytaj plik dostawy z bazy (.xls) i wybierz nośniki, których artykuły mają trafić do tabeli.
          </p>
          <div className="mt-2 flex gap-2">
            <Button loading={upload.isPending} onClick={() => fileInput.current?.click()}>
              <FileInputIcon />
              Wczytaj dokument z bazy
            </Button>
            <Button onClick={() => setPortalOpen(true)}>
              <CloudDownloadIcon />
              Pobierz dokument
            </Button>
            <Button variant="outline" onClick={() => setPickerOpen(true)}>
              <HistoryIcon />
              Wgrane wcześniej
            </Button>
          </div>
        </div>
      ) : (
        <>
          <div className="mb-2 flex flex-wrap items-center gap-x-6 gap-y-1.5 text-sm tabular-nums">
            <dl className="flex flex-wrap gap-x-6 gap-y-1">
              <Stat label="Pozycje" value={rows.length} />
              <Stat label="Suma netto" value={`${formatMoney(tableNet)} zł`} />
            </dl>
            {/* Liczniki to filtry tabeli: klik pokazuje tylko te wiersze, drugi klik wszystkie. */}
            <div className="flex flex-wrap items-center gap-1.5" role="group" aria-label="Filtry tabeli">
              <FilterStat
                label="Nowe"
                count={quickCounts.new}
                active={applied?.quick === 'new'}
                onClick={() => toggleQuick('new')}
                tone="violet"
              />
              <FilterStat
                label="Nie przyjechało"
                count={quickCounts.missing}
                active={applied?.quick === 'missing'}
                onClick={() => toggleQuick('missing')}
                tone="amber"
              />
              <FilterStat
                label="Krótkie terminy"
                count={quickCounts.short}
                active={applied?.quick === 'short'}
                onClick={() => toggleQuick('short')}
                tone="red"
              />
              <FilterStat
                label="Błędy kaucji"
                count={depositErrorCount}
                active={applied?.quick === 'deposit'}
                onClick={() => toggleQuick('deposit')}
                tone="red"
              />
              <FilterStat
                label="Błędny VAT"
                count={quickCounts.vat}
                active={applied?.quick === 'vat'}
                onClick={() => toggleQuick('vat')}
                tone="red"
              />
            </div>
          </div>

          {visibleRows.length === 0 ? (
            <div className="rounded-xl border border-dashed px-6 py-10 text-center text-sm text-muted-foreground">
              Żaden wiersz nie pasuje do filtrów.{' '}
              <Button variant="link" className="h-auto p-0" onClick={() => applyFilter({}, null)}>
                Pokaż wszystkie
              </Button>
            </div>
          ) : (
            <DataEntryGrid
              ref={gridRef}
              rows={visibleRows}
              setRows={setVisibleRows}
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
              stickyTop={`calc(3.5rem + ${toolbarHeight}px)`}
              columns={BASE_COLUMNS}
              headerExtra={headerExtra}
              onSort={sortBy}
              sort={sort}
            />
          )}
        </>
      )}

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

      <input
        ref={fileInput}
        type="file"
        accept=".xls,.xlsx,.htm,.html"
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
      <DeliveryPickerDialog storeId={store.id} open={pickerOpen} onOpenChange={setPickerOpen} onOpen={openDelivery} />
      <PortalDeliveryDialog storeId={store.id} open={portalOpen} onOpenChange={setPortalOpen} onOpen={openDelivery} />
      <CarrierDialog
        delivery={carrierFor}
        initial={
          carrierFor && carrierFor.id === draft.delivery?.id && view === 'carriers'
            ? draft.carriers
            : (carrierFor?.carriers.map((carrier) => carrier.number) ?? NO_FILTER)
        }
        open={carrierOpen}
        onOpenChange={setCarrierOpen}
        onConfirm={chooseCarriers}
      />
      <ShortDatesDialog storeId={store.id} delivery={delivery} open={shortDatesOpen} onOpenChange={setShortDatesOpen} />
      <Itm8MacroDialog delivery={delivery} open={itm8MacroOpen} onOpenChange={setItm8MacroOpen} />
      <DifferencesDialog storeId={store.id} rows={rows} open={differencesOpen} onOpenChange={setDifferencesOpen} />
      <ProtocolDialog
        storeId={store.id}
        lines={protocolLines}
        skipped={rows.length - protocolLines.length}
        open={protocolOpen}
        onOpenChange={setProtocolOpen}
      />
      <ArticleFileDialog
        rows={rows}
        selectedIds={selectedRows}
        allowLongNames
        supplierNote="Osobno dla każdego dostawcy z dokumentu (538 baza, 88110 alkohol)."
        saving={saving === 'articles'}
        open={articleFileOpen}
        onOpenChange={setArticleFileOpen}
        onSave={(chosen) => void saveChosenArticles(chosen)}
      />
      {productDrafts.dialog}

      <SaveSettingsDialog
        storeId={store.id}
        open={settingsOpen}
        onOpenChange={setSettingsOpen}
        withXml={false}
        extraFolders={[
          { folder: NOTE_FILE_FOLDER, note: 'Plik dostawy z bazy dla programu sklepu („Plik dostawy”).' },
          { folder: PLU_FOLDER, note: 'Lista EAN-ów artykułów z nomenklatury 32 i 33 („Sprawdź PLU”).' },
        ]}
      />
    </>
  )
}

const NO_FILTER: string[] = []

const FILTER_TONES = {
  violet:
    'border-violet-300 bg-violet-200 text-violet-950 dark:border-violet-700 dark:bg-violet-900 dark:text-violet-50',
  amber: 'border-amber-300 bg-amber-200 text-amber-950 dark:border-amber-700 dark:bg-amber-900 dark:text-amber-50',
  red: 'border-red-300 bg-red-200 text-red-950 dark:border-red-700 dark:bg-red-900 dark:text-red-50',
}

/**
 * Licznik nad tabelą, który jest filtrem: kolor, gdy są takie wiersze; włączony wypełniony ciemno. Pusty (0) jest
 * wyłączony, chyba że właśnie filtruje.
 */
function FilterStat({
  label,
  count,
  active,
  onClick,
  tone,
}: {
  label: string
  count: number
  active: boolean
  onClick: () => void
  tone: keyof typeof FILTER_TONES
}) {
  return (
    <button
      type="button"
      aria-pressed={active}
      disabled={count === 0 && !active}
      onClick={onClick}
      title={active ? 'Pokaż wszystkie wiersze' : `Pokaż tylko: ${label.toLowerCase()}`}
      className={cn(
        'flex items-baseline gap-1.5 rounded-md border px-2 py-0.5 transition-colors duration-100 disabled:cursor-default disabled:opacity-60',
        active
          ? 'border-foreground bg-foreground text-background'
          : count > 0
            ? cn(FILTER_TONES[tone], 'hover:brightness-95')
            : 'border-border text-muted-foreground',
      )}
    >
      <span className={cn(!active && count > 0 ? '' : 'opacity-80')}>{label}</span>
      <span className="font-semibold">{count}</span>
    </button>
  )
}

/** Przełącznik szybkiego filtra nad tabelą (włączony: wypełniony). */
function QuickToggle({
  active,
  alert,
  className,
  ...props
}: React.ComponentProps<typeof Button> & { active: boolean; alert?: boolean }) {
  return (
    <Button
      variant={active ? 'default' : 'outline'}
      size="sm"
      aria-pressed={active}
      className={cn(
        !active &&
          alert &&
          'border-red-400 bg-red-200 text-red-950 hover:bg-red-300 dark:border-red-700 dark:bg-red-900 dark:text-red-50',
        className,
      )}
      {...props}
    />
  )
}
