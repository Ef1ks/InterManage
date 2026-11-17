import {
  ArrowRightLeftIcon,
  CheckCheckIcon,
  CombineIcon,
  DiffIcon,
  FileOutputIcon,
  PackageSearchIcon,
  Redo2Icon,
  Trash2Icon,
  Undo2Icon,
} from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useStore } from 'react-redux'
import { toast } from 'sonner'

import { FullPageMessage } from '@/components/FullPageMessage'
import { HowTo } from '@/components/HowTo'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { ACTION_TILE, ACTION_TONES, TILE_WIDTH, TileContent } from '@/features/data-entry/ActionMenu'
import { lookupProducts, useDataEntryNomenclature } from '@/features/data-entry/api'
import { type CatalogAddition, CatalogListDialog } from '@/features/data-entry/CatalogListDialog'
import { CommandBar, Stat } from '@/features/data-entry/DataEntryPage'
import { DataEntryGrid, type GridHandle } from '@/features/data-entry/DataEntryGrid'
import { duplicateGroups, withMergedDuplicates, withSyncedDuplicates } from '@/features/data-entry/duplicates'
import { nomenclatureNames, PROTOCOL_COLUMNS, pricingOf } from '@/features/data-entry/grid-values'
import { ProtocolDialog, type ProtocolLine } from '@/features/data-entry/ProtocolDialog'
import {
  type DataEntryRow,
  eanError,
  emptyRow,
  mergeVisible,
  serviceKind,
  withAddedRow,
  withScannedProduct,
  withTrailingEmptyRow,
} from '@/features/data-entry/rows'
import { buildTransferMacro, planTransferMacro } from '@/features/data-entry/transfer-macro'
import { useUndoHistory, useUndoShortcuts } from '@/features/data-entry/undo-history'
import { type SalesChartTarget, useSalesReportsOverview } from '@/features/sales/api'
import { useCanSeeArticleStats } from '@/features/sales/ArticleStatsButton'
import { SalesChartDialog } from '@/features/sales/SalesChartDialog'
import { useCurrentStore } from '@/features/stores/current-store'
import { addDays, todayIso } from '@/lib/dates'
import type { StoreSummary } from '@/lib/types'
import { cn } from '@/lib/utils'
import { type RootState, useAppDispatch, useAppSelector } from '@/store'
import { EMPTY_PROTOCOL_ENTRY, type ProtocolEntryDraft, setProtocolEntryDraft } from '@/store/protocolEntryDraftSlice'

/**
 * Protokoły: okrojona tabela wprowadzania danych dla pracowników działów (likwidacje, potrzeby sklepu, MM…). Tylko
 * EAN i ilość do wpisania; nazwa, rejon, stan i rotacja z kartoteki. Bez faktur, cen, nomenklatury i plików
 * artykułów, żeby nikt niczego w kartotece nie zmienił. Tylko na komputerze.
 */
export function ProtocolEntryPage() {
  const store = useCurrentStore()
  return (
    <>
      <span data-wide-page hidden />
      {!store ? (
        <>
          <PageHeader title="Protokoły" />
          <FullPageMessage title="Brak sklepu" description="Protokoły wprowadza się osobno w każdym sklepie." inline />
        </>
      ) : (
        <>
          <div className="lg:hidden">
            <PageHeader title="Protokoły" />
            <FullPageMessage
              title="Otwórz na komputerze"
              description="Tabela protokołu działa jak arkusz Excela i potrzebuje dużego ekranu oraz klawiatury."
              inline
            />
          </div>
          <div className="hidden min-h-0 flex-1 flex-col lg:flex">
            <StoreProtocolEntry key={store.id} store={store} />
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

const isArticle = (row: DataEntryRow) => (row.ean !== '' || row.name !== '') && serviceKind(row) === null

const ROW_ACTION = 'h-full min-w-0 flex-1 rounded-md px-0'

const NO_FILTER: string[] = []

function StoreProtocolEntry({ store }: { store: StoreSummary }) {
  const dispatch = useAppDispatch()
  const reduxStore = useStore<RootState>()
  const { rows } = useAppSelector((state) => state.protocolEntryDraft.drafts[store.id]) ?? EMPTY_PROTOCOL_ENTRY
  const currentDraft = useCallback(
    () => reduxStore.getState().protocolEntryDraft.drafts[store.id] ?? EMPTY_PROTOCOL_ENTRY,
    [reduxStore, store.id],
  )
  const applyDraft = useCallback(
    (draft: ProtocolEntryDraft) =>
      dispatch(
        setProtocolEntryDraft({
          storeId: store.id,
          // Pusta tabela to jeden pusty wiersz: od razu można skanować.
          draft: { rows: draft.rows.length === 0 ? [emptyRow()] : withTrailingEmptyRow(draft.rows) },
        }),
      ),
    [dispatch, store.id],
  )
  const history = useUndoHistory(currentDraft, applyDraft)
  const { record } = history
  const setDraft = useCallback(
    (draft: ProtocolEntryDraft, { record: recorded = true }: { record?: boolean } = {}) => {
      const before = currentDraft()
      if (recorded && draft !== before) record(before)
      applyDraft(draft)
    },
    [applyDraft, currentDraft, record],
  )
  const setRows = useCallback(
    (update: (rows: DataEntryRow[]) => DataEntryRow[], options?: { record?: boolean }) => {
      const draft = currentDraft()
      const next = update(draft.rows)
      if (next !== draft.rows) setDraft({ rows: next }, options)
    },
    [currentDraft, setDraft],
  )
  useUndoShortcuts(history.undo, history.redo)
  // Ten sam EAN w kilku wierszach to jeden artykuł (dane z kartoteki przechodzą na pozostałe), ilość osobno.
  const setGridRows = useCallback(
    (update: (rows: DataEntryRow[]) => DataEntryRow[]) =>
      setRows((current) => withSyncedDuplicates(current, mergeVisible(current, current, update(current)))),
    [setRows],
  )

  const { data: nomenclature = [] } = useDataEntryNomenclature(store.id)
  const names = useMemo(() => nomenclatureNames(nomenclature), [nomenclature])
  const pricing = useMemo(() => pricingOf(nomenclature, undefined), [nomenclature])
  const gridRef = useRef<GridHandle>(null)
  const [toolbarHeight, setToolbarHeight] = useState(0)

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

  const canSeeSales = useCanSeeArticleStats()
  const salesOverview = useSalesReportsOverview(store.id, canSeeSales)
  const [chartTarget, setChartTarget] = useState<SalesChartTarget | null>(null)
  const showArticle = useCallback(
    (row: DataEntryRow) =>
      row.itm8 &&
      setChartTarget({ kind: 'article', itm8: row.itm8, name: row.name, ean: row.ean, brand: row.brand || null }),
    [],
  )

  const [catalogOpen, setCatalogOpen] = useState(false)
  const [protocolOpen, setProtocolOpen] = useState(false)
  const [selectedRows, setSelectedRows] = useState<string[]>([])

  // ---------- EAN wpisany albo zeskanowany: artykuł z kartoteki jak we wprowadzaniu danych

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

  // ---------- lista towarów

  const tableEans = useMemo(() => new Set(rows.map((row) => row.ean.replace(/^0+/, '')).filter(Boolean)), [rows])

  const addFromCatalog = async (additions: CatalogAddition[]) => {
    const eans = additions.map(({ item }) => item.ean)
    const chunks = Array.from({ length: Math.ceil(eans.length / 100) }, (_, index) =>
      eans.slice(index * 100, index * 100 + 100),
    )
    const products = (await Promise.all(chunks.map((chunk) => lookupProducts(store.id, chunk)))).flat()
    const byKey = new Map(products.map((product) => [product.ean, product]))
    const added = additions.map(({ item, quantity }) => ({
      ...withScannedProduct({ ...emptyRow(), ean: item.ean, quantity }, byKey.get(item.ean.replace(/^0+/, '')) ?? null),
      quantity,
    }))
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

  // ---------- wiersze

  const removeRows = (ids: string[]) => {
    const before = currentDraft()
    setRows((current) => current.filter((row) => !ids.includes(row.id)))
    toast(`Usunięto ${ids.length} ${pieces(ids.length)}`, {
      action: { label: 'Przywróć', onClick: () => setDraft(before) },
    })
    gridRef.current?.focus()
  }

  const articles = useMemo(() => rows.filter(isArticle), [rows])
  const selectedIds = new Set(selectedRows)
  const selectedArticles = articles.filter((row) => selectedIds.has(row.id))

  // Zmiana znaku ilości (+ na −, − na +): zaznaczone artykuły, a bez zaznaczenia wszystkie.
  const signTargets = (selectedArticles.length > 0 ? selectedArticles : articles).filter(
    (row) => row.quantity !== null && row.quantity !== 0,
  )
  const flipQuantities = () => {
    const ids = new Set(signTargets.map((row) => row.id))
    const before = currentDraft()
    setRows((current) =>
      current.map((row) => (ids.has(row.id) && row.quantity ? { ...row, quantity: -row.quantity } : row)),
    )
    toast.success(`Zmieniono znak ilości w ${ids.size} ${pieces(ids.size)}`, {
      action: { label: 'Przywróć', onClick: () => setDraft(before) },
    })
    gridRef.current?.focus()
  }

  // Ten sam EAN w kilku wierszach: jeden wiersz z ilością zsumowaną (ze znakiem).
  const duplicates = useMemo(() => duplicateGroups(rows), [rows])
  const duplicateRows = duplicates.reduce((sum, group) => sum + group.length, 0)
  const mergeDuplicates = () => {
    const before = currentDraft()
    setRows(withMergedDuplicates)
    toast.success(
      `Połączono ${duplicateRows} ${pieces(duplicateRows)} w ${duplicates.length} ${duplicates.length === 1 ? 'wiersz' : 'wiersze'}`,
      { action: { label: 'Przywróć', onClick: () => setDraft(before) } },
    )
    gridRef.current?.focus()
  }

  const clearAll = () => {
    const before = currentDraft()
    setDraft(EMPTY_PROTOCOL_ENTRY)
    focusFirstEan()
    toast('Wyczyszczono tabelę', { action: { label: 'Przywróć', onClick: () => setDraft(before) } })
  }

  // ---------- protokół: artykuły z EAN-em i ilością różną od 0, ze znakiem; błędny EAN blokuje plik

  const badEanCount = articles.filter((row) => eanError(row, false) !== null).length
  const badEanTitle = badEanCount > 0 ? `Najpierw popraw błędne albo niepełne EAN-y w tabeli: ${badEanCount}` : null
  const protocolLines = useMemo<ProtocolLine[]>(
    () =>
      articles.flatMap((row) =>
        !row.eanFallback && /^\d{1,14}$/.test(row.ean) && row.quantity
          ? [{ ean: row.ean, quantity: row.quantity }]
          : [],
      ),
    [articles],
  )
  const unknownCount = articles.filter((row) => row.ean !== '' && row.eanChecked === row.ean && !row.known).length
  const protocolTitle =
    badEanTitle ?? (protocolLines.length === 0 ? 'Najpierw dodaj artykuły z ilością' : 'Wybierz protokół i zapisz plik')

  // ---------- przeniesienie stanów w Mercalys: makro do schowka, jak „Przeniesienie” we wprowadzaniu danych
  const transferMacro = useMemo(() => planTransferMacro(articles), [articles])
  const transferTitle =
    badEanTitle ??
    (transferMacro.items.length === 0
      ? 'Najpierw dodaj artykuły z EAN-em i ilością'
      : 'Do schowka, Alt+E: Przesunięcie stanu w Mercalys, wszystkie artykuły, przeniesienie i wydruk')
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

  return (
    <>
      <PageHeader title="Protokoły" />

      <HowTo
        id="protocol-entry"
        className="mb-4"
        title="Jak wprowadzić protokół"
        steps={[
          'Zeskanuj albo wpisz EAN w pierwszej kolumnie: artykuł z kartoteki pojawi się z ilością 1, a kursor przejdzie do kolejnego wiersza. Artykuł znajdziesz też w „Lista”.',
          'Popraw ilości. Zmień znak (+/−) zmienia znak ilości zaznaczonych artykułów albo wszystkich.',
          'Kliknij „Wytwórz protokół”, wybierz rodzaj protokołu i zapisz plik dla programu sklepu. Przeniesienie stanów w Mercalys: „Przeniesienie” kopiuje makro, potem Alt+E.',
        ]}
      />

      <CommandBar onHeightChange={setToolbarHeight}>
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
                    : 'Zmień znak ilości we wszystkich artykułach: + na −, − na +'
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

        <dl className="flex min-w-0 flex-1 flex-wrap content-center gap-x-6 gap-y-1 text-sm tabular-nums">
          <Stat label="Pozycje" value={articles.length} />
          <Stat label="Do protokołu" value={protocolLines.length} />
          {unknownCount > 0 && <Stat label="Spoza kartoteki" value={unknownCount} highlight />}
          {badEanCount > 0 && <Stat label="Błędne EAN-y" value={badEanCount} highlight />}
        </dl>

        {duplicates.length > 0 && (
          <Button
            variant="outline"
            className={cn(ACTION_TONES.warn, 'shrink-0 self-center')}
            onClick={mergeDuplicates}
            title="Ten sam EAN jest w kilku wierszach: połącz je w jeden, ilości się zsumują"
          >
            <CombineIcon />
            Połącz te same ({duplicates.length})
          </Button>
        )}

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

        <Button
          className={cn(ACTION_TONES.load, ACTION_TILE, TILE_WIDTH, 'whitespace-normal')}
          variant="outline"
          onClick={() => void copyTransferMacro()}
          disabled={transferMacro.items.length === 0 || badEanTitle !== null}
          title={transferTitle}
        >
          <TileContent icon={<ArrowRightLeftIcon />}>Przeniesienie</TileContent>
        </Button>

        <Button
          className={cn(ACTION_TONES.make, ACTION_TILE, TILE_WIDTH, 'whitespace-normal')}
          variant="outline"
          onClick={() => setProtocolOpen(true)}
          disabled={protocolLines.length === 0 || badEanTitle !== null}
          title={protocolTitle}
        >
          <TileContent icon={<FileOutputIcon />}>Wytwórz protokół</TileContent>
        </Button>
      </CommandBar>

      <DataEntryGrid
        ref={gridRef}
        rows={rows}
        setRows={setGridRows}
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
        columns={PROTOCOL_COLUMNS}
      />

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
        skipped={articles.length - protocolLines.length}
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
    </>
  )
}
