import { FileDownIcon, PlusIcon, ReceiptTextIcon, SparklesIcon, Trash2Icon } from 'lucide-react'
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
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import type { KsefInvoice, StoreSummary } from '@/lib/types'
import { type RootState, useAppDispatch, useAppSelector } from '@/store'
import { type DataEntryDraft, EMPTY_DATA_ENTRY, setDataEntryDraft } from '@/store/dataEntryDraftSlice'

import { lookupProducts, useDataEntryNomenclature, useExport, useLoadInvoice, useSuggest } from './api'
import { DataEntryGrid, type GridHandle } from './DataEntryGrid'
import { nomenclatureNames } from './grid-values'
import { InvoicePickerDialog } from './InvoicePickerDialog'
import {
  type DataEntryRow,
  emptyRow,
  needsAi,
  netValue,
  nomenclatureState,
  rowFromLine,
  rowProblems,
  withCatalogProduct,
  withSuggestion,
} from './rows'

/**
 * Wprowadzanie danych (wersja testowa): faktura z KSeF w tabeli jak w Excelu, propozycje AI dla nowych artykułów
 * i zapis tabeli do pliku Excel. Tylko na komputerze - tabela ma 14 kolumn i obsługę z klawiatury.
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
          <div className="hidden lg:block">
            <StoreDataEntry key={store.id} store={store} />
          </div>
        </>
      )}
    </>
  )
}

const pieces = (count: number) => (count === 1 ? 'pozycja' : count % 10 >= 2 && count % 10 <= 4 && (count % 100 < 12 || count % 100 > 14) ? 'pozycje' : 'pozycji')

function StoreDataEntry({ store }: { store: StoreSummary }) {
  const dispatch = useAppDispatch()
  const reduxStore = useStore<RootState>()
  const { rows, invoice } = useAppSelector((state) => state.dataEntryDraft.drafts[store.id]) ?? EMPTY_DATA_ENTRY
  // Najnowszy szkic prosto ze stanu: kilka zmian w jednym kliknięciu i odpowiedzi z API nie nadpisują się nawzajem.
  const currentDraft = useCallback(
    () => reduxStore.getState().dataEntryDraft.drafts[store.id] ?? EMPTY_DATA_ENTRY,
    [reduxStore, store.id],
  )

  const setDraft = useCallback(
    (draft: DataEntryDraft) => dispatch(setDataEntryDraft({ storeId: store.id, draft })),
    [dispatch, store.id],
  )
  const setRows = useCallback(
    (update: (rows: DataEntryRow[]) => DataEntryRow[]) => {
      const draft = currentDraft()
      setDraft({ ...draft, rows: update(draft.rows) })
    },
    [currentDraft, setDraft],
  )

  const canLoadInvoices = useCan('KSEF_INVOICES', 'VIEW')
  const canUseAi = useCan('DATA_ENTRY', 'EDIT')
  const { data: nomenclature = [] } = useDataEntryNomenclature(store.id)
  const names = useMemo(() => nomenclatureNames(nomenclature), [nomenclature])
  const gridRef = useRef<GridHandle>(null)

  const [pickerOpen, setPickerOpen] = useState(false)
  const [pendingInvoice, setPendingInvoice] = useState<KsefInvoice | null>(null)
  const [selectedRows, setSelectedRows] = useState<string[]>([])
  const [exportCheck, setExportCheck] = useState<{ tooLong: DataEntryRow[]; noSubfamily: DataEntryRow[] } | null>(null)
  const loadInvoice = useLoadInvoice(store.id)
  const suggest = useSuggest(store.id)
  const exportFile = useExport(store.id)

  // ---------- faktura

  const load = (picked: KsefInvoice, mode: 'replace' | 'append') => {
    setPendingInvoice(null)
    loadInvoice.mutate(picked.id, {
      onSuccess: (data) => {
        setPickerOpen(false)
        const loaded = data.lines.map(rowFromLine)
        const base = mode === 'append' ? currentDraft().rows.filter((row) => row.ean !== '' || row.name !== '') : []
        setDraft({
          invoice: { id: data.invoiceId, number: data.invoiceNumber, sellerName: data.sellerName, sellerNip: data.sellerNip },
          rows: [...base, ...loaded],
        })
        const fresh = loaded.filter((row) => !row.known).length
        toast.success(`Wczytano fakturę ${data.invoiceNumber}`, {
          description: `${loaded.length} ${pieces(loaded.length)}, ${fresh === 0 ? 'wszystkie są w kartotece' : `nowych: ${fresh}`}.`,
        })
      },
      onError: (error) =>
        toast.error(error instanceof ApiError ? error.message : 'Nie udało się wczytać faktury'),
    })
  }

  const pick = (picked: KsefInvoice) => {
    if (rows.some((row) => row.ean !== '' || row.name !== '')) {
      setPendingInvoice(picked)
    } else {
      load(picked, 'replace')
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
          setRows((current) =>
            current.map((row) =>
              requested.get(row.id) === row.ean ? withCatalogProduct(row, byKey.get(row.ean.replace(/^0+/, '')) ?? null) : row,
            ),
          )
        })
        .catch(() => toast.error('Nie udało się sprawdzić EAN-ów w kartotece'))
    },
    [currentDraft, setRows, store.id],
  )

  // ---------- AI

  const forAi = rows.filter(needsAi)
  const fillWithAi = () => {
    const examples = rows
      .filter((row) => row.known && row.invoiceName)
      .slice(0, 10)
      .map((row) => ({ invoiceName: row.invoiceName ?? '', catalogName: row.name, brand: row.brand || null }))
    suggest.mutate(
      {
        invoiceId: invoice?.id ?? null,
        sellerNip: invoice?.sellerNip ?? null,
        sellerName: invoice?.sellerName ?? null,
        examples,
        items: forAi.map((row) => ({
          key: row.id,
          ean: row.ean || null,
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
            toast.warning(response.message ?? 'Model AI nie odpowiedział', { description: summary })
          }
        },
        onError: (error) =>
          toast.error(error instanceof ApiError ? error.message : 'Nie udało się uzupełnić pozycji'),
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

  const filled = rows.filter((row) => row.ean !== '' || row.name !== '')
  const newCount = filled.filter((row) => !row.known).length
  const toCheck = rows.filter((row) => nomenclatureState(row) === 'TO_CHECK').length
  const total = rows.reduce((sum, row) => sum + (netValue(row) ?? 0), 0)

  const removeRows = (ids: string[]) => {
    const before = currentDraft()
    setRows((current) => current.filter((row) => !ids.includes(row.id)))
    toast(`Usunięto ${ids.length} ${pieces(ids.length)}`, { action: { label: 'Przywróć', onClick: () => setDraft(before) } })
  }

  const clearAll = () => {
    const before = currentDraft()
    setDraft(EMPTY_DATA_ENTRY)
    toast('Wyczyszczono tabelę', { action: { label: 'Przywróć', onClick: () => setDraft(before) } })
  }

  // ---------- eksport

  const doExport = () => {
    setExportCheck(null)
    exportFile.mutate(
      {
        invoiceNumber: invoice?.number ?? null,
        sellerName: invoice?.sellerName ?? null,
        rows: filled.map((row) => ({
          suggestionId: row.suggestion?.id ?? null,
          ean: row.ean || null,
          quantity: row.quantity,
          unitNet: row.unitNet,
          invoiceUnit: row.invoiceUnit,
          name: row.name,
          brand: row.brand || null,
          vatPercent: row.vatPercent,
          salePrice: row.salePrice,
          subfamilyCode: row.subfamilyCode,
          unit: row.unit,
          netContent: row.netContent,
          status: row.edited.subfamilyCode ? null : (row.suggestion?.status ?? null),
          known: row.known,
        })),
      },
      { onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać pliku') },
    )
  }

  const startExport = () => {
    const tooLong = filled.filter((row) => rowProblems(row).includes('name-too-long'))
    const noSubfamily = filled.filter((row) => rowProblems(row).includes('no-subfamily'))
    if (tooLong.length + noSubfamily.length > 0) {
      setExportCheck({ tooLong, noSubfamily })
    } else {
      doExport()
    }
  }

  const goTo = (row: DataEntryRow, key: 'name' | 'subfamily') => {
    setExportCheck(null)
    // Fokus wraca do tabeli dopiero po zamknięciu okna.
    setTimeout(() => gridRef.current?.focusCell(row.id, key), 0)
  }

  return (
    <>
      <PageHeader
        title="Wprowadzanie danych"
        description={invoice ? `Faktura ${invoice.number} · ${invoice.sellerName ?? invoice.sellerNip}` : storeLabel(store)}
        actions={
          <Button
            onClick={() => setPickerOpen(true)}
            disabled={!canLoadInvoices}
            title={canLoadInvoices ? undefined : 'Wczytanie faktury wymaga dostępu do faktur KSeF'}
          >
            <ReceiptTextIcon />
            Wczytaj fakturę z KSeF
          </Button>
        }
      />

      <HowTo
        id="data-entry"
        className="mb-4"
        title="Jak wprowadzić artykuły z faktury"
        steps={[
          'Wczytaj fakturę z KSeF. Znane artykuły dostaną dane z kartoteki, nowe są oznaczone „nowy”.',
          'Kliknij „Uzupełnij z AI”: nazwa, marka, nomenklatura, rodzaj i waga dla nowych artykułów.',
          'Popraw jak w Excelu: strzałki, Enter, pisanie albo F2, Ctrl+C / Ctrl+V. Żółte komórki wymagają wyboru.',
          'Zapisz do Excela.',
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
                  Uzupełnij z AI{forAi.length > 0 && ` (${forAi.length} ${forAi.length === 1 ? 'nowy' : 'nowych'})`}
                </Button>
              )}
            </div>
          </div>

          <Legend />

          <DataEntryGrid
            ref={gridRef}
            rows={rows}
            setRows={setRows}
            onEansChanged={onEansChanged}
            onSelectedRowsChange={setSelectedRows}
            onInvalidPaste={(count) => toast.warning(`Pominięto ${count} ${count === 1 ? 'komórkę' : 'komórek'} z nieprawidłową wartością`)}
            nomenclature={nomenclature}
            names={names}
          />

          <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
            <div className="flex gap-2">
              <Button variant="outline" onClick={() => setRows((current) => [...current, emptyRow()])}>
                <PlusIcon />
                Dodaj wiersz
              </Button>
              <Button variant="ghost" onClick={() => removeRows(selectedRows)} disabled={selectedRows.length === 0}>
                <Trash2Icon />
                Usuń zaznaczone wiersze{selectedRows.length > 0 && ` (${selectedRows.length})`}
              </Button>
            </div>
            <Button size="xl" onClick={startExport} loading={exportFile.isPending} disabled={filled.length === 0}>
              <FileDownIcon />
              Zapisz do Excela
            </Button>
          </div>
        </>
      )}

      <InvoicePickerDialog storeId={store.id} open={pickerOpen} onOpenChange={setPickerOpen} onPick={pick} />

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
            <AlertDialogAction onClick={() => pendingInvoice && load(pendingInvoice, 'replace')}>Zastąp</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog open={exportCheck !== null} onOpenChange={(open) => !open && setExportCheck(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Tabela ma braki</AlertDialogTitle>
            <AlertDialogDescription render={<div />}>
              <ul className="mt-1 space-y-2">
                {exportCheck && exportCheck.tooLong.length > 0 && (
                  <li>
                    Nazwa dłuższa niż 30 znaków: {exportCheck.tooLong.length}.{' '}
                    <button type="button" className="font-medium text-foreground underline underline-offset-3" onClick={() => goTo(exportCheck.tooLong[0], 'name')}>
                      Pokaż pierwszą
                    </button>
                  </li>
                )}
                {exportCheck && exportCheck.noSubfamily.length > 0 && (
                  <li>
                    Nowy artykuł bez podrodziny: {exportCheck.noSubfamily.length}.{' '}
                    <button type="button" className="font-medium text-foreground underline underline-offset-3" onClick={() => goTo(exportCheck.noSubfamily[0], 'subfamily')}>
                      Pokaż pierwszy
                    </button>
                  </li>
                )}
              </ul>
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Wróć do tabeli</AlertDialogCancel>
            <AlertDialogAction onClick={doExport}>Zapisz mimo to</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
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
  highlight ? 'rounded bg-amber-100 px-1.5 font-semibold text-amber-950 dark:bg-amber-950/50 dark:text-amber-100' : 'font-semibold'

/** Kolory komórek nomenklatury i oznaczenia wierszy. */
function Legend() {
  const items = [
    ['bg-emerald-50 ring-emerald-200 dark:bg-emerald-950/40 dark:ring-emerald-800', 'Pewne (kartoteka)'],
    ['bg-sky-50 ring-sky-200 dark:bg-sky-950/40 dark:ring-sky-800', 'Zgodne (AI i kartoteka)'],
    ['bg-amber-100 ring-amber-300 dark:bg-amber-950/50 dark:ring-amber-700', 'Do sprawdzenia: wybierz z propozycji'],
    ['bg-violet-100 ring-violet-200 dark:bg-violet-950/50 dark:ring-violet-800', 'Nowy artykuł'],
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
