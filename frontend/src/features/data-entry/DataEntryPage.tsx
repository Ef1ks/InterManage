import {
  BadgePercentIcon,
  CheckCheckIcon,
  CombineIcon,
  DiffIcon,
  FileCodeIcon,
  FileOutputIcon,
  PackageSearchIcon,
  ScaleIcon,
  PlusIcon,
  ReceiptTextIcon,
  SparklesIcon,
  Redo2Icon,
  Trash2Icon,
  Undo2Icon,
} from 'lucide-react'
import { useCallback, useMemo, useRef, useState } from 'react'
import { useStore } from 'react-redux'
import { toast } from 'sonner'

import { FullPageMessage } from '@/components/FullPageMessage'
import { HowTo } from '@/components/HowTo'
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
import { useCan } from '@/features/auth/api'
import { formatMoney } from '@/features/documents/format'
import { type SalesChartTarget, useSalesReportsOverview } from '@/features/sales/api'
import { SalesChartDialog } from '@/features/sales/SalesChartDialog'
import { usePriceRoundingRules } from '@/features/store-settings/api'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import { addDays, todayIso } from '@/lib/dates'
import type { DataEntryCatalogProduct, DataEntryInvoice, StoreSummary } from '@/lib/types'
import { type RootState, useAppDispatch, useAppSelector } from '@/store'
import { type DataEntryDraft, EMPTY_DATA_ENTRY, setDataEntryDraft } from '@/store/dataEntryDraftSlice'

import {
  type ExportPayload,
  lookupProducts,
  useDataEntryNomenclature,
  useLinkProduct,
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
import { type CatalogAddition, CatalogListDialog } from './CatalogListDialog'
import { InvoicePickerDialog } from './InvoicePickerDialog'
import { LinkArticleDialog, type LinkTarget } from './LinkArticleDialog'
import { ProtocolDialog, type ProtocolLine } from './ProtocolDialog'
import { SplitQuantityDialog } from './SplitQuantityDialog'
import { NOMENCLATURE_TINTS, ROW_TINTS } from './row-tints'
import {
  cadencierPrice,
  catalogChanges,
  type DataEntryRow,
  type RowLink,
  emptyRow,
  needsAi,
  netValue,
  nomenclatureState,
  rowFromLine,
  rowProblems,
  serviceKind,
  suggestedPrice,
  withAddedRow,
  withSplitQuantity,
  type QuantitySplit,
  withCatalogProduct,
  withLinkedProduct,
  withScannedProduct,
  withServicesLast,
  withSuggestion,
  withTrailingEmptyRow,
} from './rows'
import { useUndoHistory, useUndoShortcuts } from './undo-history'
import { XmlSaveDialog } from './XmlSaveDialog'

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
  const { rows, invoice } = useAppSelector((state) => state.dataEntryDraft.drafts[store.id]) ?? EMPTY_DATA_ENTRY
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
          draft: { ...draft, rows: withTrailingEmptyRow(draft.rows) },
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
  // Tabela pokazuje kaucje i dostawę pod artykułami; zmiany z tabeli dotyczą wierszy w tej samej kolejności.
  // Zmiana artykułu w jednym wierszu przechodzi na wiersze z tym samym EAN-em.
  const viewRows = useMemo(() => withServicesLast(rows), [rows])
  const setViewRows = useCallback(
    (update: (rows: DataEntryRow[]) => DataEntryRow[]) =>
      setRows((current) => {
        const before = withServicesLast(current)
        return withSyncedDuplicates(before, update(before))
      }),
    [setRows],
  )

  const canLoadInvoices = useCan('KSEF_INVOICES', 'VIEW')
  const canUseAi = useCan('DATA_ENTRY', 'EDIT')
  const { data: nomenclature = [] } = useDataEntryNomenclature(store.id)
  const names = useMemo(() => nomenclatureNames(nomenclature), [nomenclature])
  const { data: rounding } = usePriceRoundingRules(store.id)
  const pricing = useMemo(() => pricingOf(nomenclature, rounding), [nomenclature, rounding])
  const gridRef = useRef<GridHandle>(null)

  // Okno sprzedaży i dostaw artykułu jak w raportach; wymaga dostępu do raportów sprzedaży.
  const canSeeSales = useCan('SALES', 'VIEW')
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
  const [linkTarget, setLinkTarget] = useState<LinkTarget | null>(null)
  const [splitTarget, setSplitTarget] = useState<DataEntryRow | null>(null)
  const [pendingInvoice, setPendingInvoice] = useState<DataEntryInvoice | null>(null)
  const [selectedRows, setSelectedRows] = useState<string[]>([])
  const [protocolOpen, setProtocolOpen] = useState(false)
  const [xmlOpen, setXmlOpen] = useState(false)
  const suggest = useSuggest(store.id)

  // ---------- faktura

  const load = (data: DataEntryInvoice, mode: 'replace' | 'append') => {
    setPendingInvoice(null)
    setPickerOpen(false)
    const loaded = data.lines.map(rowFromLine)
    const base = mode === 'append' ? currentDraft().rows.filter((row) => row.ean !== '' || row.name !== '') : []
    setDraft({
      invoice: {
        id: data.invoiceId,
        number: data.invoiceNumber,
        sellerName: data.sellerName,
        sellerNip: data.sellerNip,
      },
      rows: [...base, ...loaded],
    })
    const services = loaded.filter((row) => serviceKind(row) !== null).length
    const articles = loaded.length - services
    const fresh = loaded.filter((row) => !row.known && serviceKind(row) === null).length
    toast.success(`Wczytano fakturę ${data.invoiceNumber}`, {
      description:
        `${articles} ${pieces(articles)}, ${fresh === 0 ? 'wszystkie są w kartotece' : `nowych: ${fresh}`}` +
        (services > 0 ? `; kaucje i dostawa (${services}) na dole, bez zapisu do pliku XML.` : '.'),
    })
  }

  const confirmInvoice = (data: DataEntryInvoice) => {
    if (rows.some((row) => row.ean !== '' || row.name !== '')) {
      setPendingInvoice(data)
    } else {
      load(data, 'replace')
    }
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

  // ---------- lista towarów: artykuł z kartoteki z ilością i ceną zakupu z okna

  const tableEans = useMemo(() => new Set(rows.map((row) => row.ean.replace(/^0+/, '')).filter(Boolean)), [rows])

  const addFromCatalog = async ({ item, quantity, unitNet }: CatalogAddition) => {
    // Dane wiersza jak po wpisaniu EAN-u (VAT, cena sprzedaży, nomenklatura); ilość i cena z okna mają pierwszeństwo.
    const key = item.ean.replace(/^0+/, '')
    const products = await lookupProducts(store.id, [item.ean])
    const scanned = withScannedProduct(
      { ...emptyRow(), ean: item.ean, quantity, unitNet },
      products.find((product) => product.ean === key) ?? null,
    )
    setRows((current) =>
      withAddedRow(current, {
        ...scanned,
        quantity,
        unitNet: unitNet ?? scanned.unitNet,
      }),
    )
    toast.success(`Dodano do tabeli: ${item.name ?? item.ean}`)
  }

  // ---------- podział ilości (opakowanie z faktury na sztuki sprzedaży)

  const splitRow = (row: DataEntryRow, split: QuantitySplit, unit: string) => {
    const before = currentDraft()
    setRows((current) => withSplitQuantity(current, row.id, split, unit))
    setSplitTarget(null)
    toast.success('Podzielono ilość, wartość netto bez zmian', {
      action: { label: 'Przywróć', onClick: () => setDraft(before) },
    })
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
    suggest.mutate(
      {
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
      },
      {
        onSuccess: (response) => {
          const byKey = new Map(response.items.map((item) => [item.key, item]))
          setRows((current) =>
            current.map((row) => {
              const suggestion = byKey.get(row.id)
              return suggestion ? withSuggestion(row, suggestion) : row
            }),
          )
          const toCheck = response.items.filter((item) => item.status === 'TO_CHECK').length
          const summary = `Uzupełniono ${response.items.length} ${pieces(response.items.length)}${toCheck ? `, do sprawdzenia: ${toCheck}` : ''}.`
          if (response.modelUsed) {
            toast.success(summary)
          } else {
            toast.warning(response.message ?? 'Model AI nie odpowiedział', {
              description: summary,
            })
          }
        },
        onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się uzupełnić pozycji'),
      },
    )
  }

  const agreed = rows.filter((row) => {
    const state = nomenclatureState(row)
    return state === 'AGREED' || state === 'CERTAIN'
  })
  const confirmAgreed = () =>
    setRows((current) =>
      current.map((row) =>
        agreed.some((candidate) => candidate.id === row.id) && row.suggestion
          ? { ...row, suggestion: { ...row.suggestion, confirmed: true } }
          : row,
      ),
    )

  // ---------- wiersze

  // Kaucje i dostawa zostają w tabeli, ale nie są artykułami: bez liczników, sprawdzania i eksportu.
  const filled = rows.filter((row) => (row.ean !== '' || row.name !== '') && serviceKind(row) === null)
  const newCount = filled.filter((row) => !row.known).length
  const changedCount = filled.filter((row) => catalogChanges(row).length > 0).length
  const toCheck = rows.filter((row) => nomenclatureState(row) === 'TO_CHECK').length
  const total = rows.reduce((sum, row) => sum + (netValue(row) ?? 0), 0)

  const removeRows = (ids: string[]) => {
    const before = currentDraft()
    setRows((current) => current.filter((row) => !ids.includes(row.id)))
    toast(`Usunięto ${ids.length} ${pieces(ids.length)}`, {
      action: { label: 'Przywróć', onClick: () => setDraft(before) },
    })
  }

  // ---------- pasek nad tabelą: działania na zaznaczonych wierszach

  const selectedIds = new Set(selectedRows)
  const selectedArticles = viewRows.filter((row) => selectedIds.has(row.id) && serviceKind(row) === null)
  const priceChanges = new Map<string, number>()
  for (const row of selectedArticles) {
    const price = suggestedPrice(row, pricing)
    if (price !== null && price !== row.salePrice) priceChanges.set(row.id, price)
  }
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

  // Do protokołu idą artykuły z EAN-em (nie zastępczym kodem z faktury) i ilością różną od 0, ze znakiem.
  const protocolLines: ProtocolLine[] = []
  for (const row of rows.filter(isArticle)) {
    if (!row.eanFallback && /^\d{1,14}$/.test(row.ean) && row.quantity) {
      protocolLines.push({ ean: row.ean, quantity: row.quantity })
    }
  }
  const protocolSkipped = rows.filter(isArticle).length - protocolLines.length

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
    setDraft(EMPTY_DATA_ENTRY)
    toast('Wyczyszczono tabelę', {
      action: { label: 'Przywróć', onClick: () => setDraft(before) },
    })
  }

  // ---------- plik XML

  const xmlPayload: ExportPayload | null = xmlOpen
    ? {
        invoiceId: invoice?.id ?? null,
        invoiceNumber: invoice?.number ?? null,
        sellerName: invoice?.sellerName ?? null,
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
      }
    : null
  const xmlProblems = {
    invalidEan: xmlOpen ? filled.filter((row) => rowProblems(row).includes('invalid-ean')) : [],
    tooLong: xmlOpen ? filled.filter((row) => rowProblems(row).includes('name-too-long')) : [],
    noSubfamily: xmlOpen ? filled.filter((row) => rowProblems(row).includes('no-subfamily')) : [],
  }

  const goTo = (row: DataEntryRow, key: 'ean' | 'name' | 'subfamily') => {
    setXmlOpen(false)
    // Fokus wraca do tabeli dopiero po zamknięciu okna.
    setTimeout(() => gridRef.current?.focusCell(row.id, key), 0)
  }

  return (
    <>
      <PageHeader
        title="Wprowadzanie danych"
        description={
          invoice ? `Faktura ${invoice.number} · ${invoice.sellerName ?? invoice.sellerNip}` : storeLabel(store)
        }
        actions={
          <>
            <Button variant="outline" onClick={() => setCatalogOpen(true)}>
              <PackageSearchIcon />
              Lista towarów
            </Button>
            <Button
              variant={rows.length > 0 ? 'outline' : 'default'}
              onClick={() => setPickerOpen(true)}
              disabled={!canLoadInvoices}
              title={canLoadInvoices ? undefined : 'Wczytanie faktury wymaga dostępu do faktur KSeF'}
            >
              <ReceiptTextIcon />
              Wczytaj fakturę z KSeF
            </Button>
            {rows.length > 0 && (
              <>
                {/* Działa na zaznaczonych wierszach, więc klik nie może chować zaznaczenia tabeli. */}
                <Button
                  data-keep-grid-selection
                  variant="outline"
                  onClick={flipQuantities}
                  disabled={signTargets.length === 0}
                  title={
                    selectedArticles.length > 0
                      ? 'Zmienia znak ilości w zaznaczonych artykułach: + na −, − na +'
                      : 'Zmienia znak ilości we wszystkich artykułach (bez kaucji i dostawy): + na −, − na +'
                  }
                >
                  <DiffIcon />
                  Zmień znak
                  {selectedArticles.length > 0 && ` (${signTargets.length})`}
                </Button>
                <Button variant="outline" onClick={() => setProtocolOpen(true)} disabled={protocolLines.length === 0}>
                  <FileOutputIcon />
                  Wygeneruj protokół
                </Button>
              </>
            )}
            {rows.length > 0 && (
              <Button onClick={() => setXmlOpen(true)} disabled={filled.length === 0}>
                <FileCodeIcon />
                Zapisz XML
              </Button>
            )}
          </>
        }
      />

      <HowTo
        id="data-entry"
        className="mb-4"
        title="Jak wprowadzić artykuły z faktury"
        steps={[
          'Wczytaj fakturę z KSeF. Artykuły zapisane w kartotece są szare i mają dane z kartoteki, nowe są fioletowe, a zmienione w tabeli pomarańczowe. Kaucje i dostawa idą na dół i nie trafiają do pliku XML.',
          'Kliknij „Uzupełnij z AI”: nazwa, marka, nomenklatura, rodzaj i waga dla nowych artykułów.',
          'Skanujesz ręcznie? Wpisz albo zeskanuj EAN i Enter: od razu powstaje kolejny wiersz na następny kod.',
          'Popraw jak w Excelu: strzałki, Enter, pisanie albo F2, Ctrl+C / Ctrl+V, Ctrl+Z cofa, Ctrl+Y ponawia. Żółte komórki wymagają wyboru.',
          'Zapisz XML (przycisk u góry): plik trafia do folderu wybranego raz w przeglądarce.',
        ]}
      />

      {rows.length === 0 ? (
        <div className="flex flex-col items-center gap-3 rounded-xl border border-dashed px-6 py-16 text-center">
          <p className="text-base font-medium">Tabela jest pusta</p>
          <p className="max-w-md text-sm text-muted-foreground">
            Wczytaj fakturę zakupową z KSeF albo dodaj wiersz i wklej pozycje z Excela (Ctrl+V).
          </p>
          <div className="mt-2 flex gap-2">
            <Button onClick={() => setPickerOpen(true)} disabled={!canLoadInvoices}>
              <ReceiptTextIcon />
              Wczytaj fakturę z KSeF
            </Button>
            <Button variant="outline" onClick={() => setDraft({ invoice: null, rows: [emptyRow()] })}>
              <PlusIcon />
              Pusty wiersz
            </Button>
          </div>
        </div>
      ) : (
        <>
          <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
            <dl className="flex flex-wrap gap-x-6 gap-y-1 text-sm tabular-nums">
              <Stat label="Pozycje" value={filled.length} />
              <Stat label="Suma netto" value={`${formatMoney(total)} zł`} />
              <Stat label="Nowe" value={newCount} />
              {changedCount > 0 && <Stat label="Zmienione z kartoteki" value={changedCount} />}
              <Stat label="Do sprawdzenia" value={toCheck} highlight={toCheck > 0} />
            </dl>
            <div className="flex flex-wrap gap-2">
              <Button variant="ghost" onClick={clearAll}>
                Wyczyść
              </Button>
              {agreed.length > 0 && (
                <Button variant="outline" onClick={confirmAgreed}>
                  Zatwierdź zgodne ({agreed.length})
                </Button>
              )}
              {canUseAi && (
                <Button onClick={fillWithAi} loading={suggest.isPending} disabled={forAi.length === 0}>
                  <SparklesIcon />
                  Uzupełnij z AI
                  {forAi.length > 0 && ` (${forAi.length} ${forAi.length === 1 ? 'nowy' : 'nowych'})`}
                </Button>
              )}
            </div>
          </div>

          <Legend />

          <GridToolbar
            selected={selectedArticles.length}
            onSelectAll={() => gridRef.current?.selectAll()}
            actions={
              <Button
                variant="outline"
                size="sm"
                onClick={applySuggestedPrices}
                disabled={priceChanges.size === 0}
                title={
                  priceChanges.size > 0
                    ? 'Wpisuje cenę sugerowaną jako cenę sprzedaży w zaznaczonych wierszach'
                    : selectedArticles.length === 0
                      ? 'Zaznacz wiersze w tabeli'
                      : 'Zaznaczone wiersze mają już cenę sugerowaną albo nie da się jej policzyć'
                }
              >
                <BadgePercentIcon />
                Ustaw ceny sugerowane
                {priceChanges.size > 0 && ` (${priceChanges.size})`}
              </Button>
            }
            tableActions={
              <>
                <div className="flex items-center gap-1" role="group" aria-label="Historia zmian">
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    onClick={history.undo}
                    disabled={!history.canUndo}
                    aria-label="Cofnij"
                    title="Cofnij ostatnią zmianę (Ctrl+Z)"
                  >
                    <Undo2Icon />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    onClick={history.redo}
                    disabled={!history.canRedo}
                    aria-label="Ponów"
                    title="Ponów cofniętą zmianę (Ctrl+Y)"
                  >
                    <Redo2Icon />
                  </Button>
                </div>
                <div className="flex items-center gap-1" role="group" aria-label="Ilość we wszystkich artykułach">
                  <span className="mr-1 text-sm text-muted-foreground">Ilość wszystkich:</span>
                  {[0, 1].map((quantity) => (
                    <Button
                      key={quantity}
                      variant="outline"
                      size="sm"
                      className="min-w-8 tabular-nums"
                      onClick={() => setAllQuantities(quantity)}
                      disabled={filled.length === 0}
                      title={`Ustawia ilość ${quantity} we wszystkich artykułach (bez kaucji i dostawy)`}
                    >
                      {quantity}
                    </Button>
                  ))}
                </div>
                {conflicts.length > 0 && (
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={averagePrices}
                    title="Ten sam EAN jest na fakturze w różnych cenach: wpisuje w nich cenę średnią ważoną ilością"
                  >
                    <ScaleIcon />
                    Uśrednij ceny ({conflicts.length})
                  </Button>
                )}
                {duplicates.length > 0 && (
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={mergeDuplicates}
                    title="Łączy wiersze z tym samym EAN-em w jeden: ilości zsumowane, cena średnia"
                  >
                    <CombineIcon />
                    Połącz te same ({duplicates.length})
                  </Button>
                )}
              </>
            }
          />

          <DataEntryGrid
            ref={gridRef}
            rows={viewRows}
            setRows={setViewRows}
            onEansChanged={onEansChanged}
            onSelectedRowsChange={setSelectedRows}
            onInvalidPaste={(count) =>
              toast.warning(`Pominięto ${count} ${count === 1 ? 'komórkę' : 'komórek'} z nieprawidłową wartością`)
            }
            nomenclature={nomenclature}
            names={names}
            pricing={pricing}
            onShowArticle={canSeeSales ? showArticle : undefined}
            onLinkArticle={openLink}
            onSplitQuantity={setSplitTarget}
          />

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

      <ProtocolDialog
        storeId={store.id}
        lines={protocolLines}
        skipped={protocolSkipped}
        open={protocolOpen}
        onOpenChange={setProtocolOpen}
      />

      <CatalogListDialog
        storeId={store.id}
        open={catalogOpen}
        onOpenChange={setCatalogOpen}
        inTable={tableEans}
        onAdd={addFromCatalog}
        lastSaleDate={canSeeSales ? (salesOverview.data?.lastSaleDate ?? addDays(todayIso(), -1)) : null}
      />

      <LinkArticleDialog
        storeId={store.id}
        target={linkTarget}
        onClose={() => setLinkTarget(null)}
        onLinked={onLinked}
        onUnlinked={onUnlinked}
        inTable={tableEans}
        lastSaleDate={canSeeSales ? (salesOverview.data?.lastSaleDate ?? addDays(todayIso(), -1)) : null}
      />

      <SplitQuantityDialog row={splitTarget} onClose={() => setSplitTarget(null)} onSplit={splitRow} />

      <InvoicePickerDialog
        storeId={store.id}
        open={pickerOpen}
        onOpenChange={setPickerOpen}
        onConfirm={confirmInvoice}
      />

      <AlertDialog open={pendingInvoice !== null} onOpenChange={(open) => !open && setPendingInvoice(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>W tabeli są już wiersze</AlertDialogTitle>
            <AlertDialogDescription>
              Zastąpić je pozycjami faktury {pendingInvoice?.invoiceNumber} czy dopisać pozycje na końcu?
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
        storeId={store.id}
        payload={xmlPayload}
        problems={xmlProblems}
        open={xmlOpen}
        onOpenChange={setXmlOpen}
        onGoTo={goTo}
      />
    </>
  )
}

const NO_FILTER: string[] = []

/** Pasek nad tabelą: działania na zaznaczonych wierszach i na całej tabeli (kolejne dochodzą tutaj). */
function GridToolbar({
  selected,
  onSelectAll,
  actions,
  tableActions,
}: {
  selected: number
  onSelectAll: () => void
  /** Działania na zaznaczonych wierszach. */
  actions: React.ReactNode
  /** Działania na całej tabeli, po prawej. */
  tableActions: React.ReactNode
}) {
  return (
    <div
      data-keep-grid-selection
      className="mb-2 flex shrink-0 flex-wrap items-center gap-x-4 gap-y-2 rounded-lg border bg-muted/50 px-2 py-1"
    >
      <Button variant="ghost" size="sm" onClick={onSelectAll} title="Zaznacz wszystkie pozycje (Ctrl+A)">
        <CheckCheckIcon />
        Zaznacz wszystkie
      </Button>
      <p className="text-sm text-muted-foreground">
        {selected > 0 ? (
          <>
            Zaznaczone: <span className="font-semibold text-foreground tabular-nums">{selected}</span>{' '}
            {pieces(selected)}
          </>
        ) : (
          'Zaznacz wiersze: Shift + klik albo strzałki, Ctrl+A zaznacza wszystkie'
        )}
      </p>
      <div className="flex flex-wrap gap-2">{actions}</div>
      <div className="ml-auto flex flex-wrap items-center gap-2">{tableActions}</div>
    </div>
  )
}

function Stat({ label, value, highlight }: { label: string; value: React.ReactNode; highlight?: boolean }) {
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
