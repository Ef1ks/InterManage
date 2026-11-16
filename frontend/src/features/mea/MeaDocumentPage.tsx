import { EraserIcon, LockIcon, MinusIcon, PlusIcon, SendIcon } from 'lucide-react'
import { useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { useParams } from 'react-router'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import { FullPageMessage } from '@/components/FullPageMessage'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { ArticleStatsButton, useCanSeeArticleStats } from '@/features/sales/ArticleStatsButton'
import { useSalesReportsOverview } from '@/features/sales/api'
import { ArticleSalesPanel } from '@/features/sales/SalesChartDialog'
import { useCurrentStore } from '@/features/stores/current-store'
import { addDays, todayIso } from '@/lib/dates'
import { marginOf } from '@/lib/margin'
import type { MeaDocument, MeaDocumentLine, StoreSummary } from '@/lib/types'
import { cn } from '@/lib/utils'
import { useAppDispatch, useAppSelector } from '@/store'
import {
  clearMeaDraft,
  EMPTY_LINE,
  meaDraftKey,
  restoreMeaDraft,
  setMeaMultiplier,
  setMeaQuantity,
  type MeaLineDraft,
  type MeaOrderDraft,
} from '@/store/meaOrderDraftSlice'

import { useMeaDocument } from './api'
import { reservationClosed } from './reservation'
import { SendToAthosDialog } from './SendToAthosDialog'
import { meaOnlinePath } from './paths'

const EMPTY_DRAFT: MeaOrderDraft = {}

const money = (value: number | null | undefined) =>
  value == null ? '' : value.toLocaleString('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

const plain = (value: number | null | undefined, digits = 2) =>
  value == null ? '' : value.toLocaleString('pl-PL', { maximumFractionDigits: digits })

const shortDate = (iso: string | null) =>
  iso
    ? new Date(`${iso}T00:00:00`).toLocaleDateString('pl-PL', { day: '2-digit', month: '2-digit', year: 'numeric' })
    : '—'

const weekday = (iso: string) => new Date(`${iso}T00:00:00`).toLocaleDateString('pl-PL', { weekday: 'long' })

/** Sztuki w zamówionym kartonie (COLI) po mnożniku. */
const piecesPerColi = (line: MeaDocumentLine, draft: MeaLineDraft) => (line.unitsPerPack ?? 0) * draft.multiplier

/** Kartony ze wszystkich rozrzutów. */
const coliOf = (draft: MeaLineDraft) => draft.quantities.reduce((sum, value) => sum + Math.max(0, value), 0)

/** Wartość zakupu jak w eMEA: kartony × sztuk w COLI × mnożnik × cena zakupu (MEA albo CAD). */
const valueOf = (line: MeaDocumentLine, draft: MeaLineDraft, price: number | null) =>
  coliOf(draft) * piecesPerColi(line, draft) * (price ?? 0)

export function MeaDocumentPage() {
  const store = useCurrentStore()
  const { number = '' } = useParams()
  if (!store) {
    return <FullPageMessage title="Brak sklepu" description="MEA wczytuje się osobno dla każdego sklepu." inline />
  }
  return <StoreMeaDocument key={`${store.id}:${number}`} store={store} number={number} />
}

function StoreMeaDocument({ store, number }: { store: StoreSummary; number: string }) {
  const document = useMeaDocument(store.id, number)

  return (
    <div data-wide-page>
      <BackLink to={meaOnlinePath} className="mb-3">
        Moduł MEA
      </BackLink>
      {document.isPending ? (
        <p className="px-1 py-6 text-sm text-muted-foreground">Wczytywanie dokumentu MEA {number} z portalu…</p>
      ) : document.isError ? (
        <FullPageMessage
          title={`Nie udało się wczytać MEA ${number}`}
          description={document.error instanceof Error ? document.error.message : 'Spróbuj ponownie za chwilę.'}
          inline
          action={{ label: 'Spróbuj ponownie', onClick: () => void document.refetch() }}
        />
      ) : (
        <MeaDocumentView store={store} document={document.data} />
      )}
    </div>
  )
}

function MeaDocumentView({ store, document }: { store: StoreSummary; document: MeaDocument }) {
  const dispatch = useAppDispatch()
  const key = meaDraftKey(store.id, document.number)
  const draft = useAppSelector((state) => state.meaOrderDraft.drafts[key] ?? EMPTY_DRAFT)
  const [selected, setSelected] = useState<string | null>(document.lines[0]?.meaItm8 ?? null)
  const inputs = useRef(new Map<string, HTMLInputElement>())
  // Po terminie „rezerwacja do” portal nie przyjmie już ilości, więc dokument jest tylko do podglądu.
  const readOnly = reservationClosed(document.reservationTo)
  // Kolumny tylko dla rozrzutów z datą (co najmniej jedna), żeby tabela zmieściła się na 2/3 ekranu.
  const dispatchColumns = Array.from({ length: Math.max(1, document.dispatches.length) }, (_, index) => index)

  const lineDraft = (itm8: string) => draft[itm8] ?? EMPTY_LINE

  const totals = useMemo(() => {
    let coli = 0
    let value = 0
    let cadValue = 0
    let ordered = 0
    for (const line of document.lines) {
      const current = draft[line.meaItm8] ?? EMPTY_LINE
      const lineColi = coliOf(current)
      if (lineColi > 0) ordered += 1
      coli += lineColi
      value += valueOf(line, current, line.purchasePrice)
      cadValue += valueOf(line, current, line.parallelPurchasePrice || line.purchasePrice)
    }
    const difference = cadValue ? (value / cadValue - 1) * 100 : 0
    return { coli, value, cadValue, difference, ordered }
  }, [document.lines, draft])

  const focusCell = (row: number, column: number) => {
    const line = document.lines[row]
    if (!line) return
    const input = inputs.current.get(`${line.meaItm8}:${column}`)
    input?.focus()
    input?.select()
  }

  const onCellKey = (event: KeyboardEvent<HTMLInputElement>, row: number, column: number) => {
    if (event.key === 'Enter' || event.key === 'ArrowDown') {
      event.preventDefault()
      focusCell(event.shiftKey ? row - 1 : row + 1, column)
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      focusCell(row - 1, column)
    }
  }

  const clear = () => {
    const previous = draft
    dispatch(clearMeaDraft(key))
    toast('Wyczyszczono ilości', {
      action: { label: 'Przywróć', onClick: () => dispatch(restoreMeaDraft({ key, draft: previous })) },
    })
  }

  const selectedLine = document.lines.find((line) => line.meaItm8 === selected) ?? null
  const [sendOpen, setSendOpen] = useState(false)

  return (
    <>
      <DocumentHeader
        document={document}
        totals={totals}
        onClear={totals.coli > 0 && !readOnly ? clear : undefined}
        onSend={readOnly ? undefined : () => setSendOpen(true)}
      />
      <SendToAthosDialog
        storeId={store.id}
        document={document}
        draft={draft}
        totals={totals}
        open={sendOpen}
        onOpenChange={setSendOpen}
      />
      {readOnly && (
        <p
          role="status"
          className="mb-4 flex items-center gap-2 rounded-lg bg-amber-200 px-4 py-2.5 text-sm text-amber-950 dark:bg-amber-900/70 dark:text-amber-50"
        >
          <LockIcon className="size-4 shrink-0" />
          Rezerwacja zakończyła się {shortDate(document.reservationTo)}. Dokument jest tylko do podglądu, ilości nie
          można już zmieniać.
        </p>
      )}

      {/* Od xl tabela na 2/3 szerokości, a statystyki zaznaczonego artykułu obok na 1/3, każde z własnym przewijaniem,
          żeby pracownik wpisując ilości widział od razu stan i sprzedaż. */}
      <div className="grid gap-4 xl:grid-cols-[minmax(0,2fr)_minmax(0,1fr)] xl:items-start">
        <Card className="overflow-clip p-0">
          <div className="max-h-[calc(100dvh-18rem)] min-h-64 overflow-auto">
            <table className="w-full border-separate border-spacing-0 text-sm">
              <thead className="sticky top-0 z-10 bg-card text-xs text-muted-foreground">
                <tr className="[&>th]:border-b [&>th]:px-2 [&>th]:py-2 [&>th]:text-left [&>th]:font-medium">
                  <th className="w-10">Lp.</th>
                  <th>
                    Kod MEA
                    <br />
                    EAN
                  </th>
                  <th className="min-w-48">
                    Nazwa
                    <br />
                    Marka
                  </th>
                  <th className="text-right!">
                    Szt. w COLI
                    <br />
                    COLI / paleta
                  </th>
                  <th className="text-right!">
                    VAT
                    <br />
                    DLV
                  </th>
                  <th className="text-right!">
                    Zakup netto
                    <br />
                    MEA / CAD
                  </th>
                  <th className="text-right!">
                    Półka brutto
                    <br />
                    MEA / CAD
                  </th>
                  <th className="text-right!">
                    Marża %
                    <br />
                    MEA / CAD
                  </th>
                  {dispatchColumns.map((index) => (
                    <th key={index} className="text-center!">
                      R{index + 1}
                      <br />
                      <span className="font-normal tabular-nums">
                        {document.dispatches[index] ? shortDate(document.dispatches[index]).slice(0, 5) : ''}
                      </span>
                    </th>
                  ))}
                  <th className="text-right!">
                    Stan
                    <br />
                    Rotacja
                  </th>
                  <th className="text-right!">
                    Wartość
                    <br />
                    R1–R4
                  </th>
                  <th className="text-center!">
                    Mnożnik
                    <br />
                    ile w COLI
                  </th>
                </tr>
              </thead>
              <tbody>
                {document.lines.map((line, row) => (
                  <MeaLineRow
                    key={line.meaItm8}
                    line={line}
                    draft={lineDraft(line.meaItm8)}
                    dispatchColumns={dispatchColumns}
                    readOnly={readOnly}
                    selected={line.meaItm8 === selected}
                    onSelect={() => setSelected(line.meaItm8)}
                    registerInput={(column, element) => {
                      const id = `${line.meaItm8}:${column}`
                      if (element) inputs.current.set(id, element)
                      else inputs.current.delete(id)
                    }}
                    onCellKey={(event, column) => onCellKey(event, row, column)}
                    onQuantity={(column, value) =>
                      dispatch(setMeaQuantity({ key, itm8: line.meaItm8, dispatch: column, value }))
                    }
                    onMultiplier={(value) => dispatch(setMeaMultiplier({ key, itm8: line.meaItm8, value }))}
                  />
                ))}
              </tbody>
            </table>
          </div>
        </Card>

        <aside
          aria-label="Statystyki artykułu"
          className="min-w-0 xl:sticky xl:top-18 xl:max-h-[calc(100dvh-5.5rem)] xl:overflow-y-auto"
        >
          {selectedLine ? (
            <ArticleDetails store={store} line={selectedLine} draft={lineDraft(selectedLine.meaItm8)} />
          ) : (
            <Card className="px-5 py-6 text-sm text-muted-foreground">
              Kliknij pozycję, a zobaczysz stan, sprzedaż i dostawy artykułu.
            </Card>
          )}
        </aside>
      </div>
    </>
  )
}

function DocumentHeader({
  document,
  totals,
  onClear,
  onSend,
}: {
  document: MeaDocument
  totals: { coli: number; value: number; cadValue: number; difference: number; ordered: number }
  onClear?: () => void
  /** „Wyślij do ATHOS”; bez niego (rezerwacja zamknięta) przycisku nie ma. */
  onSend?: () => void
}) {
  return (
    <Card className="mb-4 grid gap-4 px-5 py-4 lg:grid-cols-[auto_1fr_auto]">
      <dl className="grid grid-cols-2 gap-x-6 gap-y-1 text-sm sm:grid-cols-4 lg:grid-cols-2 xl:grid-cols-4">
        {[0, 1, 2, 3].map((index) => {
          const date = document.dispatches[index]
          return (
            <div key={index} className="flex gap-2">
              <dt className="font-semibold">R{index + 1}:</dt>
              <dd className={cn('tabular-nums', !date && 'text-muted-foreground')}>
                {date ? (
                  <>
                    {shortDate(date)}
                    <span className="block text-xs text-muted-foreground">{weekday(date)}</span>
                  </>
                ) : (
                  '—'
                )}
              </dd>
            </div>
          )
        })}
      </dl>

      <div className="min-w-0">
        <h1 className="truncate text-xl font-semibold tracking-tight text-blue-700 dark:text-blue-300">
          {document.title ?? `MEA ${document.number}`}
        </h1>
        <p className="mt-1 flex flex-wrap gap-x-5 gap-y-1 text-sm">
          <span>
            Numer: <strong className="tabular-nums">{document.number}</strong>
          </span>
          <span>
            Pozycji: <strong className="tabular-nums">{document.lines.length}</strong>
            {totals.ordered > 0 && <span className="text-muted-foreground"> (zamówione {totals.ordered})</span>}
          </span>
          <span>
            Promocja:{' '}
            <strong className="tabular-nums">
              {shortDate(document.promoFrom)} – {shortDate(document.promoTo)}
            </strong>
          </span>
          <span>
            Rezerwacja do: <strong className="tabular-nums">{shortDate(document.reservationTo)}</strong>
          </span>
        </p>
        <AthosSendNote document={document} totals={totals} />
      </div>

      <div className="flex items-start gap-4">
        <dl className="grid grid-cols-[auto_auto] gap-x-3 gap-y-0.5 text-sm">
          <dt className="text-muted-foreground">Suma COLI</dt>
          <dd className="text-right font-semibold tabular-nums">{totals.coli}</dd>
          <dt className="text-muted-foreground">Wartość</dt>
          <dd className="text-right font-semibold tabular-nums">{money(totals.value)} zł</dd>
          <dt className="text-muted-foreground">CAD</dt>
          <dd className="text-right tabular-nums">
            {money(totals.cadValue)} zł
            <span
              className={cn(
                'ml-2 text-xs',
                totals.difference < 0 ? 'text-emerald-700 dark:text-emerald-300' : 'text-muted-foreground',
              )}
            >
              {plain(totals.difference)}%
            </span>
          </dd>
        </dl>
        <div className="grid gap-2">
          {onSend && (
            <Button
              onClick={onSend}
              className="bg-orange-500 text-white hover:bg-orange-600 dark:bg-orange-600 dark:hover:bg-orange-500"
            >
              <SendIcon />
              Wyślij do ATHOS
            </Button>
          )}
          {onClear && (
            <Button variant="outline" size="sm" onClick={onClear}>
              <EraserIcon />
              Wyczyść ilości
            </Button>
          )}
        </div>
      </div>
    </Card>
  )
}

/**
 * Wysyłka z ATHOS (najnowsza z dziennika Histo.Log): kiedy, ile pozycji i COLI. Gdy w aplikacji są wpisane ilości,
 * różne liczby są na czerwono, jak w eMEA (zamówienie wysłane z ATHOS nie zgadza się z formularzem).
 */
function AthosSendNote({ document, totals }: { document: MeaDocument; totals: { coli: number; ordered: number } }) {
  const [latest, ...older] = document.athosSends
  const [order] = document.orders
  // Zapisane z aplikacji, a dziennik ATHOS jeszcze nie pokazał wysyłki (albo nie jest czytany).
  const orderNote = order && (!latest || new Date(latest.sentAt) < new Date(order.savedAt)) && (
    <span className="mr-2 rounded bg-sky-200 px-1.5 py-0.5 font-medium text-sky-950 dark:bg-sky-900 dark:text-sky-50">
      Zapisano do ATHOS{' '}
      {new Date(order.savedAt).toLocaleString('pl-PL', {
        day: '2-digit',
        month: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
      })}{' '}
      · {order.colis} COLI
      {order.createdBy && ` · ${order.createdBy}`}
    </span>
  )
  if (!latest) {
    return <p className="mt-1 text-sm text-muted-foreground">{orderNote || 'Nie wysłano jeszcze do ATHOS.'}</p>
  }
  const compare = totals.coli > 0
  const mismatch = (sent: number, entered: number) =>
    compare && sent !== entered ? 'font-semibold text-red-700 dark:text-red-300' : 'font-semibold'
  const sentAt = new Date(latest.sentAt).toLocaleString('pl-PL', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
  return (
    <p className="mt-1 text-sm">
      {orderNote}
      <span className="rounded bg-emerald-200 px-1.5 py-0.5 font-medium text-emerald-950 dark:bg-emerald-900 dark:text-emerald-50">
        Wysłano z ATHOS {sentAt}
      </span>{' '}
      <span className={mismatch(latest.positions, totals.ordered)}>{latest.positions} poz.</span> ·{' '}
      <span className={mismatch(latest.colis, totals.coli)}>{latest.colis} COLI</span>
      {older.length > 0 && <span className="text-muted-foreground"> · wcześniej wysyłane {older.length}×</span>}
    </p>
  )
}

function MeaLineRow({
  line,
  draft,
  dispatchColumns,
  readOnly,
  selected,
  onSelect,
  registerInput,
  onCellKey,
  onQuantity,
  onMultiplier,
}: {
  line: MeaDocumentLine
  draft: MeaLineDraft
  dispatchColumns: number[]
  /** Rezerwacja zamknięta: ilości i mnożnik tylko jako tekst. */
  readOnly: boolean
  selected: boolean
  onSelect: () => void
  registerInput: (column: number, element: HTMLInputElement | null) => void
  onCellKey: (event: KeyboardEvent<HTMLInputElement>, column: number) => void
  onQuantity: (column: number, value: number) => void
  onMultiplier: (value: number) => void
}) {
  const coli = coliOf(draft)
  const value = valueOf(line, draft, line.purchasePrice)
  const product = line.product

  return (
    <tr
      onClick={onSelect}
      className={cn(
        'cursor-default align-top [&>td]:border-b [&>td]:px-2 [&>td]:py-1.5',
        coli > 0 ? 'bg-emerald-200/70 dark:bg-emerald-900/50' : 'odd:bg-muted/40 hover:bg-accent/60',
        selected && 'outline-2 -outline-offset-2 outline-blue-500',
      )}
    >
      <td className="tabular-nums text-muted-foreground">{line.lp}</td>
      <td className="tabular-nums">
        {line.meaItm8}
        <span className="block text-xs text-muted-foreground">{line.ean}</span>
        {line.parallelItm8 && <span className="block text-xs text-muted-foreground">CAD {line.parallelItm8}</span>}
      </td>
      <td>
        <span className="flex items-start gap-1">
          <span className="min-w-0">
            <span className="block font-medium">{line.name}</span>
            <span className="block text-xs text-muted-foreground">{line.brand}</span>
            {line.note && <span className="mt-0.5 block text-xs text-amber-800 dark:text-amber-200">{line.note}</span>}
          </span>
          {product?.itm8 && (
            <ArticleStatsButton
              skipTab
              article={{ itm8: product.itm8, name: product.name, ean: product.catalogEan, brand: product.brand }}
            />
          )}
        </span>
      </td>
      <td className="text-right tabular-nums">
        {line.unitsPerPack}
        <span className="block text-muted-foreground">{line.packsPerPallet}</span>
      </td>
      <td className="text-right tabular-nums">
        {line.vatPercent != null ? `${line.vatPercent}%` : ''}
        <span className="block text-muted-foreground">{line.guaranteedDays}</span>
      </td>
      <td className="text-right tabular-nums">
        {money(line.purchasePrice)}
        <span className="block text-muted-foreground">
          {line.parallelPurchasePrice ? money(line.parallelPurchasePrice) : ''}
        </span>
      </td>
      <td className="text-right tabular-nums">
        {money(line.shelfPrice)}
        <span className="block text-muted-foreground">
          {line.parallelShelfPrice ? money(line.parallelShelfPrice) : ''}
        </span>
      </td>
      <td className="text-right tabular-nums">
        {money(line.margin)}
        <span className="block text-muted-foreground">{line.parallelMargin ? money(line.parallelMargin) : ''}</span>
      </td>
      {dispatchColumns.map((column) => (
        <td key={column} className="text-center">
          {readOnly ? (
            <span className="font-semibold tabular-nums">{draft.quantities[column] || '—'}</span>
          ) : (
            <QuantityStepper
              label={`R${column + 1} ${line.name ?? line.meaItm8}`}
              value={draft.quantities[column] ?? 0}
              inputRef={(element) => registerInput(column, element)}
              onKeyDown={(event) => onCellKey(event, column)}
              onChange={(next) => onQuantity(column, next)}
              onFocus={onSelect}
            />
          )}
        </td>
      ))}
      <td className="text-right tabular-nums">
        {product ? (
          <>
            <span className={cn(product.stock != null && product.stock < 0 && 'text-red-700 dark:text-red-300')}>
              {plain(product.stock, 3)}
            </span>
            <span className="block text-muted-foreground">{plain(product.rotation)}</span>
          </>
        ) : (
          <span className="text-xs text-muted-foreground">brak w kartotece</span>
        )}
      </td>
      <td className="text-right font-medium tabular-nums">{money(value)}</td>
      <td className="text-center">
        {readOnly ? (
          <span className="tabular-nums">{String(draft.multiplier).replace('.', ',')}</span>
        ) : (
          <input
            aria-label={`Mnożnik ${line.name ?? line.meaItm8}`}
            inputMode="decimal"
            defaultValue={draft.multiplier === 1 ? '1' : String(draft.multiplier).replace('.', ',')}
            key={draft.multiplier}
            onFocus={(event) => {
              onSelect()
              event.target.select()
            }}
            onBlur={(event) => {
              const parsed = Number(event.target.value.replace(',', '.'))
              onMultiplier(Number.isFinite(parsed) && parsed > 0 ? parsed : 1)
            }}
            className="h-8 w-14 rounded-md border border-input bg-background px-2 text-right tabular-nums dark:bg-input/30"
          />
        )}
      </td>
    </tr>
  )
}

/** Pole ilości kartonów z przyciskami − / + jak w eMEA. */
function QuantityStepper({
  label,
  value,
  inputRef,
  onKeyDown,
  onChange,
  onFocus,
}: {
  label: string
  value: number
  inputRef: (element: HTMLInputElement | null) => void
  onKeyDown: (event: KeyboardEvent<HTMLInputElement>) => void
  onChange: (value: number) => void
  onFocus: () => void
}) {
  return (
    <span className="inline-flex items-center gap-0.5">
      <button
        type="button"
        tabIndex={-1}
        aria-label={`Mniej: ${label}`}
        disabled={value <= 0}
        onClick={(event) => {
          event.stopPropagation()
          onChange(value - 1)
        }}
        className="grid size-7 place-items-center rounded-md border bg-background hover:bg-accent disabled:opacity-40 dark:bg-input/30"
      >
        <MinusIcon className="size-3.5" />
      </button>
      <input
        ref={inputRef}
        aria-label={label}
        inputMode="numeric"
        value={value === 0 ? '' : String(value)}
        placeholder="0"
        onFocus={(event) => {
          onFocus()
          event.target.select()
        }}
        onKeyDown={onKeyDown}
        onChange={(event) => {
          const digits = event.target.value.replace(/\D/g, '')
          onChange(digits === '' ? 0 : Number(digits))
        }}
        className="h-8 w-12 rounded-md border border-input bg-background text-center font-semibold tabular-nums placeholder:font-normal placeholder:text-muted-foreground dark:bg-input/30"
      />
      <button
        type="button"
        tabIndex={-1}
        aria-label={`Więcej: ${label}`}
        onClick={(event) => {
          event.stopPropagation()
          onChange(value + 1)
        }}
        className="grid size-7 place-items-center rounded-md border bg-background hover:bg-accent dark:bg-input/30"
      >
        <PlusIcon className="size-3.5" />
      </button>
    </span>
  )
}

/** Dolny panel jak w eMEA: artykuł z kartoteki sklepu dla zaznaczonej pozycji i jego sprzedaż. */
function ArticleDetails({ store, line, draft }: { store: StoreSummary; line: MeaDocumentLine; draft: MeaLineDraft }) {
  const canSeeStats = useCanSeeArticleStats()
  const overview = useSalesReportsOverview(store.id, canSeeStats)
  const yesterday = addDays(todayIso(), -1)
  const savedUntil = overview.data?.lastSaleDate ?? null
  const lastSaleDate = savedUntil && savedUntil < yesterday ? savedUntil : yesterday
  const product = line.product
  const vat = product?.catalogVat ?? product?.vatPercent ?? line.vatPercent
  const margin =
    product?.salePrice && product.purchasePrice != null && vat != null
      ? marginOf(product.salePrice, vat, product.purchasePrice)
      : null
  const pieces = coliOf(draft) * piecesPerColi(line, draft)

  return (
    <div className="grid gap-4">
      <Card className="gap-3 px-5 py-4 text-sm">
        {product ? (
          <>
            <div>
              <p className="tabular-nums text-muted-foreground">{product.catalogEan ?? product.ean}</p>
              <p className="font-semibold text-red-700 dark:text-red-300">{product.name}</p>
              <p className="text-muted-foreground">{product.brand}</p>
            </div>
            <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 tabular-nums">
              <dt className="text-muted-foreground">ITM8</dt>
              <dd>{product.itm8?.replace(/^0+/, '')}</dd>
              <dt className="text-muted-foreground">Cena sprzedaży</dt>
              <dd>
                {money(product.salePrice)} zł
                {product.promoPrice != null && (
                  <span className="ml-2 text-orange-700 dark:text-orange-300">
                    promocja {money(product.promoPrice)} zł
                  </span>
                )}
              </dd>
              <dt className="text-muted-foreground">Cena zakupu</dt>
              <dd>{money(product.purchasePrice)} zł</dd>
              <dt className="text-muted-foreground">Marża / VAT</dt>
              <dd>
                {margin != null ? `${plain(margin)}%` : '—'} / {vat != null ? `${vat}%` : '—'}
              </dd>
              <dt className="text-muted-foreground">Stan</dt>
              <dd>{plain(product.stock, 3)}</dd>
              <dt className="text-muted-foreground">Rotacja</dt>
              <dd>{plain(product.rotation)}</dd>
              <dt className="text-muted-foreground">Sztuk w COLI</dt>
              <dd>{line.unitsPerPack}</dd>
              <dt className="text-muted-foreground">Zamawiasz</dt>
              <dd>
                {coliOf(draft)} COLI = {pieces} szt.
              </dd>
            </dl>
          </>
        ) : (
          <p className="text-muted-foreground">
            Artykułu {line.name} (ITM8 {line.meaItm8}, EAN {line.ean}) nie ma w kartotece sklepu.
          </p>
        )}
      </Card>
      {canSeeStats && product?.itm8 && (
        <Card className="gap-5 px-5 py-5 sm:px-6">
          <ArticleSalesPanel
            storeId={store.id}
            target={{
              kind: 'article',
              itm8: product.itm8,
              name: product.name,
              ean: product.catalogEan,
              brand: product.brand,
            }}
            lastSaleDate={lastSaleDate}
          />
        </Card>
      )}
    </div>
  )
}
