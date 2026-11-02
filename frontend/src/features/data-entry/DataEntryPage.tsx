import {
  BadgePercentIcon,
  CheckCheckIcon,
  CircleCheckIcon,
  CombineIcon,
  DiffIcon,
  FileCodeIcon,
  FileInputIcon,
  FileClockIcon,
  FileOutputIcon,
  PackageSearchIcon,
  ScaleIcon,
  PlusIcon,
  ReceiptTextIcon,
  SparklesIcon,
  Redo2Icon,
  SettingsIcon,
  TagIcon,
  Trash2Icon,
  Undo2Icon,
} from 'lucide-react'
import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
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
import { useSuppliers } from '@/features/documents/api'
import { formatMoney } from '@/features/documents/format'
import { type SalesChartTarget, useSalesReportsOverview } from '@/features/sales/api'
import { SalesChartDialog } from '@/features/sales/SalesChartDialog'
import { usePriceRoundingRules, useSupplierSalePrices } from '@/features/store-settings/api'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/utils'
import { usePriceChangeFolder } from '@/lib/proto-files'
import { ensureFolderAccess, type SavedFolderSpec } from '@/lib/saved-folders'
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
import { documentSupplier } from './document-supplier'
import { InvoiceFileActions } from './InvoiceFileActions'
import { InvoicePickerDialog } from './InvoicePickerDialog'
import { LinkArticleDialog, type LinkTarget } from './LinkArticleDialog'
import { usePriceFileOverview } from './price-files'
import { savePriceControlFile } from './price-control'
import { PriceFileStatus } from './PriceFileStatus'
import { ProtocolDialog, type ProtocolLine } from './ProtocolDialog'
import { SaveSettingsDialog } from './SaveSettingsDialog'
import { SplitQuantityDialog } from './SplitQuantityDialog'
import { SupplierChangeDialog } from './SupplierChangeDialog'
import { NOMENCLATURE_TINTS, ROW_TINTS } from './row-tints'
import {
  cadencierPrice,
  catalogChanges,
  type DataEntryRow,
  type RowLink,
  emptyRow,
  needsAi,
  needsProductFile,
  nomenclatureState,
  purchasePerPiece,
  rowFromLine,
  rowProblems,
  serviceKind,
  suggestedPrice,
  withAutoSalePrice,
  withAddedRow,
  withSplitQuantity,
  type QuantitySplit,
  withCatalogProduct,
  withCurrentPromo,
  withLinkedProduct,
  withScannedProduct,
  withServicesLast,
  withSupplierPrice,
  withSuggestion,
  withTrailingEmptyRow,
} from './rows'
import { useUndoHistory, useUndoShortcuts } from './undo-history'
import { XmlOpenDialog } from './XmlOpenDialog'
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
  const [pendingInvoice, setPendingInvoice] = useState<PendingDocument | null>(null)
  const [xmlOpenOpen, setXmlOpenOpen] = useState(false)
  const [selectedRows, setSelectedRows] = useState<string[]>([])
  const [protocolOpen, setProtocolOpen] = useState(false)
  const [checkOpen, setCheckOpen] = useState(false)
  const [resaveOpen, setResaveOpen] = useState(false)
  const [saveSettings, setSaveSettings] = useState<{ open: boolean; missing: SavedFolderSpec[] }>({
    open: false,
    missing: [],
  })
  const saved = useAppSelector((state) => state.dataEntryDraft.saved[store.id]) ?? null
  const forgetSaved = useCallback(
    () => dispatch(setDataEntrySaved({ storeId: store.id, saved: null })),
    [dispatch, store.id],
  )
  const [supplierOpen, setSupplierOpen] = useState(false)
  const { data: suppliers } = useSuppliers(store.id)
  const supplier = useMemo(() => documentSupplier(suppliers, invoice), [suppliers, invoice])
  const { data: supplierPrices } = useSupplierSalePrices(store.id)
  // Stała cena sprzedaży dostawcy (Ustawienia sklepu): cena sugerowana wszystkich artykułów dokumentu.
  const supplierPriceFor = useCallback(
    (supplierId: string | null | undefined) =>
      supplierPrices?.find((price) => price.supplierId === supplierId)?.salePrice ?? null,
    [supplierPrices],
  )
  const pricing = useMemo(
    () => pricingOf(nomenclature, rounding, supplierPriceFor(supplier?.id)),
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
    }
    // Dostawca ze stałą ceną (Ustawienia sklepu): wczytane artykuły dostają ją od razu jako cenę sprzedaży.
    const fixedPrice = supplierPriceFor(documentSupplier(suppliers, nextInvoice)?.id)
    setDraft({ invoice: nextInvoice, rows: [...base, ...withSupplierPrice(loaded, fixedPrice)] })
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
  }

  const confirmDocument = (pending: PendingDocument) => {
    if (rows.some((row) => row.ean !== '' || row.name !== '')) {
      setPendingInvoice(pending)
    } else {
      load(pending, 'replace')
    }
  }

  const confirmXml = (xml: DataEntryXmlDocument) => {
    // Zapis tymczasowy oddaje całą tabelę taką, jaka była (nazwy, nomenklatura, propozycje AI); plik XML tylko gdy
    // tabeli z zapisu nie da się odczytać.
    const draft = parseDraft(xml.draft)
    if (draft) {
      const before = currentDraft()
      setXmlOpenOpen(false)
      forgetSaved()
      setDraft(draft)
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

  // ---------- promocje z kasy: wiersz pamięta promocję z chwili dodania, a plik TRACEART przychodzi w ciągu dnia,
  // więc po wejściu na stronę i po każdym nowym pliku cen promocje znanych artykułów są pobierane od nowa.

  const priceFiles = usePriceFileOverview(store.id).data?.latest.map((file) => file.id).join()
  const refreshedPromos = useRef<string | null>(null)
  useEffect(() => {
    if (priceFiles === undefined) return
    const key = `${store.id}:${priceFiles}`
    if (refreshedPromos.current === key) return
    refreshedPromos.current = key
    const eans = [...new Set(currentDraft().rows.filter((row) => row.known && row.ean !== '').map((row) => row.ean))]
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

  const addFromCatalog = async (additions: CatalogAddition[]) => {
    // Dane wiersza jak po wpisaniu EAN-u (VAT, cena sprzedaży, nomenklatura); ilość i cena z okna mają pierwszeństwo.
    // EAN-y idą paczkami, bo „Dodaj wszystkie wyfiltrowane” wysyła ich setki w adresie zapytania.
    const eans = additions.map(({ item }) => item.ean)
    const chunks = Array.from({ length: Math.ceil(eans.length / 100) }, (_, index) =>
      eans.slice(index * 100, index * 100 + 100),
    )
    const products = (await Promise.all(chunks.map((chunk) => lookupProducts(store.id, chunk)))).flat()
    const byKey = new Map(products.map((product) => [product.ean, product]))
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
        const summary = `Uzupełniono ${response.items.length} ${pieces(response.items.length)}${toCheck ? `, do sprawdzenia: ${toCheck}` : ''}.`
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
  const newCount = filled.filter((row) => !row.known).length
  const changedCount = filled.filter((row) => catalogChanges(row).length > 0).length
  const toCheck = rows.filter((row) => nomenclatureState(row) === 'TO_CHECK').length
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
    forgetSaved()
    setDraft(EMPTY_DATA_ENTRY)
    toast('Wyczyszczono tabelę', {
      action: { label: 'Przywróć', onClick: () => setDraft(before) },
    })
  }

  // ---------- dostawca dokumentu

  // Dokument dla innego dostawcy niż sprzedawca z faktury: numer faktury znika z nagłówka pliku.
  const changeSupplier = (supplier: Supplier) => {
    setSupplierOpen(false)
    const before = currentDraft()
    const fixedPrice = supplierPriceFor(supplier.id)
    setDraft({
      ...before,
      invoice: {
        ...(before.invoice ?? { id: null, sellerName: null, sellerNip: null }),
        number: null,
        supplier: { id: supplier.id, name: supplier.name, nip: supplier.nip },
      },
      rows: withSupplierPrice(before.rows, fixedPrice),
    })
    const notes = [
      before.invoice?.number && `Usunięto numer faktury ${before.invoice.number}`,
      fixedPrice !== null && `Stała cena dostawcy ${formatMoney(fixedPrice)} wpisana w artykułach`,
    ].filter(Boolean)
    toast(`Dostawca dokumentu: ${supplier.name}`, {
      description: notes.length ? `${notes.join('. ')}.` : undefined,
      action: { label: 'Cofnij', onClick: () => setDraft(before) },
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
  const savedState = saved ? (saved.signature === signature ? 'saved' : 'changed') : null

  // Nowe i zmienione artykuły (bez samej zmiany ceny) idą razem z plikiem XML do pliku CADBEZP dla programu sklepu.
  const productRows = filled.filter(needsProductFile)
  const productFilePayload: ProductFilePayload | null =
    productRows.length > 0
      ? {
          supplierId: invoice?.supplier?.id ?? null,
          sellerNip: invoice?.sellerNip ?? null,
          sellerName: invoice?.supplier?.name ?? invoice?.sellerName ?? null,
          invoiceNumber: invoice?.number ?? null,
          rows: productRows.map((row) => ({
            ean: (!row.eanFallback && row.ean) || null,
            name: row.name,
            brand: row.brand || null,
            unit: row.unit,
            netContent: row.netContent,
            purchasePrice: purchasePerPiece(row),
            vatPercent: row.vatPercent,
            salePrice: row.salePrice,
            subfamilyCode: row.subfamilyCode,
            supplierCode: row.supplierCode ?? null,
            cn: row.cn ?? null,
          })),
        }
      : null

  // Artykuły z kartoteki z ceną sprzedaży inną niż przy wczytaniu (kartoteka albo promocja z kasy) idą do CTRLPRIX.
  const priceControlRows = filled.flatMap((row) =>
    row.known && !row.eanFallback && row.ean && row.catalog && row.salePrice !== null && row.salePrice > 0
      && row.salePrice !== row.catalog.salePrice
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
  const priceChangeFolder = usePriceChangeFolder(store.id)
  const queryClient = useQueryClient()
  const [savingPrices, setSavingPrices] = useState(false)
  const savePriceChanges = async () => {
    if (!priceControlPayload) return
    setSavingPrices(true)
    try {
      // Zgoda na folder zaraz po kliknięciu, zanim cokolwiek pójdzie do serwera.
      const folderReady = await ensureFolderAccess(priceChangeFolder.key)
      const file = await savePriceControlFile(store.id, priceControlPayload, priceChangeFolder.key, folderReady)
      void queryClient.invalidateQueries({ queryKey: ['stores', store.id, 'data-entry', 'price-control-files'] })
      toast.success(`Zapisano plik zmian cen${file.supplierCode ? ` dla dostawcy ${file.supplierCode}` : ''}`, {
        description: `${file.fileName}${file.downloaded ? ' (pobrany: folder zmiany cen nie jest wybrany w tej przeglądarce)' : ''}`,
        duration: 10_000,
      })
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać pliku zmiany cen')
    } finally {
      setSavingPrices(false)
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
  const xmlSave = useXmlSave(store.id)
  const runSave = async () => {
    const missing = await xmlSave.missingFolders(productFilePayload, priceControlPayload)
    if (missing.length > 0) {
      setCheckOpen(false)
      setSaveSettings({ open: true, missing })
      return
    }
    const fileName = await xmlSave.save(payload, productFilePayload, priceControlPayload)
    if (!fileName) return
    setCheckOpen(false)
    dispatch(setDataEntrySaved({ storeId: store.id, saved: { at: nowIso(), fileName, signature } }))
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
    // Fokus wraca do tabeli dopiero po zamknięciu okna.
    setTimeout(() => gridRef.current?.focusCell(row.id, key), 0)
  }

  return (
    <>
      <PageHeader
        title="Wprowadzanie danych"
        description={invoice ? documentLabel(invoice) : storeLabel(store)}
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
          'Kliknij „Zapisz”: pliki trafiają od razu do folderów z ustawień zapisu (koło zębate, wybierasz je raz). Niedokończoną tabelę zapisz tymczasowo i wczytaj później w „Wczytaj z XML”.',
        ]}
      />

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
          />
        )}
        <div className="flex min-w-0 flex-1 flex-col justify-center gap-1.5">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1.5" role="group" aria-label="Dokument">
            <Button
              variant={rows.length > 0 ? 'outline' : 'default'}
              size="sm"
              onClick={() => setPickerOpen(true)}
              disabled={!canLoadInvoices}
              title={canLoadInvoices ? undefined : 'Wczytanie faktury wymaga dostępu do faktur KSeF'}
            >
              <ReceiptTextIcon />
              Wczytaj fakturę z KSeF
            </Button>
            <Button variant="outline" size="sm" onClick={() => setXmlOpenOpen(true)}>
              <FileInputIcon />
              Wczytaj z XML
            </Button>
            <Button variant="outline" size="sm" onClick={() => setCatalogOpen(true)}>
              <PackageSearchIcon />
              Lista towarów
            </Button>
            <PriceFileStatus storeId={store.id} size="sm" />
            {rows.length > 0 && (
              <>
                <ToolbarDivider />
                <Button
                  variant="outline"
                  size="sm"
                  onClick={saveTemporary}
                  loading={saveTemporaryXml.isPending}
                  disabled={filled.length === 0}
                  title="Zapisuje tabelę na serwerze bez pliku na dysku; wczytasz ją w „Wczytaj z XML” (10 ostatnich)"
                >
                  <FileClockIcon />
                  Zapisz tymczasowo
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={savePriceChanges}
                  loading={savingPrices}
                  disabled={priceControlRows.length === 0}
                  title={
                    priceControlRows.length > 0
                      ? `Zapisuje samą zmianę cen (${priceControlRows.length} ${pieces(priceControlRows.length)}) w folderze zmiany cen, bez pliku XML`
                      : 'Żaden artykuł z kartoteki nie ma w tabeli zmienionej ceny sprzedaży'
                  }
                >
                  <TagIcon />
                  Zmień ceny
                  {priceControlRows.length > 0 && ` (${priceControlRows.length})`}
                </Button>
              </>
            )}
            <div className="ml-auto flex items-center gap-2">
              {saved && savedState && <SavedStatus state={savedState} at={saved.at} fileName={saved.fileName} />}
              <Button
                variant="ghost"
                size="icon-sm"
                onClick={() => setSaveSettings({ open: true, missing: [] })}
                aria-label="Ustawienia zapisu"
                title="Ustawienia zapisu: foldery na plik XML, nowe artykuły, protokoły i zmianę cen"
              >
                <SettingsIcon />
              </Button>
              {rows.length > 0 && (
                <Button
                  size="sm"
                  onClick={onSaveClick}
                  loading={xmlSave.saving}
                  disabled={filled.length === 0}
                  title="Zapisuje plik XML (z nowymi artykułami i zmianą cen) w folderach z ustawień zapisu"
                >
                  <FileCodeIcon />
                  Zapisz
                </Button>
              )}
            </div>
          </div>

          {rows.length > 0 && (
            <div className="flex flex-wrap items-center gap-x-2 gap-y-1.5" role="group" aria-label="Tabela">
              <Button
                variant="ghost"
                size="icon-sm"
                onClick={() => gridRef.current?.selectAll()}
                aria-label="Zaznacz wszystkie"
                title="Zaznacz wszystkie pozycje (Ctrl+A)"
              >
                <CheckCheckIcon />
              </Button>
              <Button
                variant="ghost"
                size="icon-sm"
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
                variant="ghost"
                size="icon-sm"
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
              <ToolbarDivider />
              {invoice?.id && (
                <>
                  <InvoiceFileActions storeId={store.id} invoiceId={invoice.id} />
                  <ToolbarDivider />
                </>
              )}
              <Button
                variant="outline"
                size="sm"
                onClick={() => setProtocolOpen(true)}
                disabled={protocolLines.length === 0}
              >
                <FileOutputIcon />
                Wygeneruj protokół
              </Button>
              <Button
                variant="outline"
                size="sm"
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
              <ToolbarDivider />
              <div className="flex items-center gap-1" role="group" aria-label="Ilość we wszystkich artykułach">
                <span className="mr-1 text-sm text-muted-foreground">Ilość:</span>
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
              {(agreed.length > 0 || canUseAi) && <ToolbarDivider />}
              {agreed.length > 0 && (
                <Button variant="outline" size="sm" onClick={confirmAgreed}>
                  Zatwierdź zgodne ({agreed.length})
                </Button>
              )}
              {canUseAi && (
                <Button
                  variant="outline"
                  size="sm"
                  onClick={fillWithAi}
                  loading={suggest.isPending}
                  disabled={forAi.length === 0}
                >
                  <SparklesIcon />
                  Uzupełnij z AI
                  {forAi.length > 0 && ` (${forAi.length} ${forAi.length === 1 ? 'nowy' : 'nowych'})`}
                </Button>
              )}
              <div className="ml-auto flex items-center gap-1" role="group" aria-label="Historia zmian">
                <Button variant="ghost" size="sm" onClick={clearAll}>
                  Wyczyść
                </Button>
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
            </div>
          )}
        </div>
      </CommandBar>

      {rows.length === 0 ? (
        <div className="flex flex-col items-center gap-3 rounded-xl border border-dashed px-6 py-16 text-center">
          <p className="text-base font-medium">Tabela jest pusta</p>
          <p className="max-w-md text-sm text-muted-foreground">
            Wczytaj fakturę zakupową z KSeF, plik XML faktury (z dysku albo zapisany tu wcześniej) albo dodaj wiersz i
            wklej pozycje z Excela (Ctrl+V).
          </p>
          <div className="mt-2 flex gap-2">
            <Button onClick={() => setPickerOpen(true)} disabled={!canLoadInvoices}>
              <ReceiptTextIcon />
              Wczytaj fakturę z KSeF
            </Button>
            <Button variant="outline" onClick={() => setXmlOpenOpen(true)}>
              <FileInputIcon />
              Wczytaj z XML
            </Button>
            <Button variant="outline" onClick={() => setDraft({ invoice: null, rows: [emptyRow()] })}>
              <PlusIcon />
              Pusty wiersz
            </Button>
          </div>
        </div>
      ) : (
        <>
          <dl className="mb-2 flex flex-wrap gap-x-6 gap-y-1 text-sm tabular-nums">
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
          </dl>

          <Legend />

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
            onSplitQuantity={setSplitTarget}
            stickyTop={`calc(3.5rem + ${toolbarHeight}px)`}
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
        onConfirm={(data) => confirmDocument({ data })}
      />

      <XmlOpenDialog storeId={store.id} open={xmlOpenOpen} onOpenChange={setXmlOpenOpen} onLoaded={confirmXml} />

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
      />

      <AlertDialog open={resaveOpen} onOpenChange={setResaveOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Dokument jest już zapisany</AlertDialogTitle>
            <AlertDialogDescription>
              {saved
                ? `Plik ${saved.fileName} zapisano ${formatSavedAt(saved.at)}${savedState === 'changed' ? ', a tabela zmieniła się od tego czasu' : ''}. `
                : ''}
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
    </>
  )
}

const NO_FILTER: string[] = []

/** Chwila zapisu pliku (poza komponentem: woła ją tylko obsługa kliknięcia). */
const nowIso = () => new Date().toISOString()

/** Tabela z zapisu tymczasowego; zepsuty albo obcy JSON = null (wtedy wczytuje się sam plik XML). */
function parseDraft(json: string | null): DataEntryDraft | null {
  if (!json) return null
  try {
    const value = JSON.parse(json) as Partial<DataEntryDraft> | null
    if (!value || !Array.isArray(value.rows) || value.rows.length === 0) return null
    return { invoice: value.invoice ?? null, rows: value.rows }
  } catch {
    return null
  }
}

/** Dokument czekający na wczytanie: faktura z KSeF albo plik XML (`fromXml` = nazwa pliku). */
type PendingDocument = { data: DataEntryDocument; fromXml?: string }

function documentLabel(invoice: NonNullable<DataEntryDraft['invoice']>) {
  const seller = invoice.supplier ? `dla ${invoice.supplier.name}` : (invoice.sellerName ?? invoice.sellerNip)
  const what = invoice.number ? `${invoice.id ? 'Faktura' : 'Dokument'} ${invoice.number}` : 'Dokument bez numeru'
  return [what, seller, invoice.fromXml && !invoice.id ? `z pliku ${invoice.fromXml}` : null]
    .filter(Boolean)
    .join(' · ')
}

/**
 * Przypięty pasek ze wszystkimi działaniami: numer dostawcy, dokument (wczytanie, zapis) i tabela (zaznaczone wiersze,
 * cała tabela). Przykleja się pod nagłówkiem aplikacji, więc przy przewijaniu długiej tabeli nie trzeba wracać na
 * górę; pod nim klei się nagłówek kolumn.
 */
function CommandBar({
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
 * Numer dostawcy dokumentu w dużym kwadracie (trafia do pliku XML i do programu sklepu), pod nim nazwa i zmiana
 * dostawcy. Bez numeru bursztynowy z powodem.
 */
function SupplierNumber({
  code,
  name,
  problem,
  changeLabel,
  onChange,
}: {
  code: string | null
  name: string | null
  problem: string | null
  changeLabel: string | null
  onChange: () => void
}) {
  return (
    <div
      className={cn(
        'flex w-36 shrink-0 flex-col items-center justify-center rounded-md border-2 px-2 py-1.5 text-center',
        code
          ? 'border-foreground/20 bg-card'
          : 'border-amber-400 bg-amber-200 text-amber-950 dark:border-amber-600 dark:bg-amber-900 dark:text-amber-50',
      )}
      title={name ?? undefined}
    >
      <span className={cn('text-xs', code ? 'text-muted-foreground' : 'font-medium')}>Nr dostawcy</span>
      {code ? (
        <span className="text-4xl leading-tight font-bold tracking-tight tabular-nums">{code}</span>
      ) : (
        <span className="my-1 text-sm leading-tight font-semibold">{problem ?? 'Sprawdzanie…'}</span>
      )}
      {name && <span className="w-full truncate text-xs">{name}</span>}
      {changeLabel && (
        <Button variant="link" className="h-auto p-0 text-xs" onClick={onChange}>
          {changeLabel}
        </Button>
      )}
    </div>
  )
}

/** Informacja o zapisie pliku XML: zielona, gdy tabela jest taka jak przy zapisie, bursztynowa po zmianach. */
function SavedStatus({ state, at, fileName }: { state: 'saved' | 'changed'; at: string; fileName: string }) {
  return (
    <span
      className={cn(
        'flex items-center gap-1.5 rounded-md px-2 py-1 text-sm font-medium',
        state === 'saved'
          ? 'bg-emerald-200 text-emerald-950 dark:bg-emerald-900 dark:text-emerald-50'
          : 'bg-amber-200 text-amber-950 dark:bg-amber-900 dark:text-amber-50',
      )}
      title={fileName}
    >
      <CircleCheckIcon aria-hidden className="size-4" />
      {state === 'saved' ? `Dokument zapisany ${formatSavedAt(at)}` : `Zapisany ${formatSavedAt(at)} · zmiany po zapisie`}
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

function ToolbarDivider() {
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
