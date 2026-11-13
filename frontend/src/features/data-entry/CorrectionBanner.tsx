import { ClipboardCopyIcon, FileDiffIcon, PencilIcon, SearchIcon } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { toast } from 'sonner'

import { CopyButton } from '@/components/CopyButton'
import { TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { formatDocumentDate, formatMoney, formatPrice, formatQuantity } from '@/features/documents/format'
import { todayIso } from '@/lib/dates'
import type { CorrectionDelivery } from '@/lib/types'
import { useDebouncedValue } from '@/lib/use-debounced-value'
import { cn } from '@/lib/utils'
import type { DraftCorrection, DraftDelivery, DraftInvoice } from '@/store/dataEntryDraftSlice'

import { useCorrectionBons, useCorrectionDeliveries } from './api'
import { buildCorrectionMacro, isBaseSeller, planCorrectionMacro } from './correction-macro'
import { type DataEntryRow, serviceKind } from './rows'

type Delivery = DraftDelivery

const toDelivery = (item: CorrectionDelivery): Delivery => ({
  id: item.id,
  number: item.number,
  date: item.documentDate.slice(0, 10),
  supplierName: item.supplierName,
})

const SHOWN_INVOICES = 3

/**
 * Korekta w tabeli: faktury, które koryguje (numer widać bez drukowania korekty), i bon, do którego należy. Bon faktury
 * korygowanej podpina się sam, gdy jest dokładnie jeden; inny wybiera się z wyszukiwarki albo wpisuje ręcznie (stara
 * faktura, bon spoza raportów). Z bonu powstaje makro do programu sklepu.
 */
export function CorrectionBanner({
  storeId,
  invoice,
  correction,
  rows,
  macroOpen,
  onMacroOpenChange,
  onDelivery,
  onLinkRow,
}: {
  storeId: string
  invoice: DraftInvoice
  correction: DraftCorrection
  rows: DataEntryRow[]
  macroOpen: boolean
  onMacroOpenChange: (open: boolean) => void
  /** `auto`: bon podpięty sam, bez kroku cofania; `extra`: kolejne bony tej samej korekty. */
  onDelivery: (delivery: Delivery | null, auto?: boolean, extra?: Delivery[]) => void
  onLinkRow: (row: DataEntryRow) => void
}) {
  const [pickerOpen, setPickerOpen] = useState(false)
  const { delivery, corrected, reason } = correction
  const extra = useMemo(() => correction.extraDeliveries ?? [], [correction.extraDeliveries])
  const undecided = delivery === undefined
  const candidates = useCorrectionDeliveries(storeId, invoice.id, '', delivery === null || undecided)
  const matched = useMemo(() => (candidates.data ?? []).filter((item) => item.matched), [candidates.data])

  // Jeden pasujący bon podpina się sam; przy kilku (faktura bazy z kilkoma WZ) wszystkie z artykułami korekty, od
  // największej liczby (tak podaje je serwer): makro wpisze przy każdym artykule jego bon.
  const auto = useMemo(
    () => (matched.length === 1 ? matched : matched.filter((item) => item.commonArticles > 0)),
    [matched],
  )
  useEffect(() => {
    if (undecided && auto.length > 0) onDelivery(toDelivery(auto[0]), true, auto.slice(1).map(toDelivery))
  }, [undecided, auto, onDelivery])

  return (
    <div className="mb-4 flex flex-wrap items-center gap-x-5 gap-y-3 rounded-xl bg-sky-200 px-4 py-3 text-sky-950 dark:bg-sky-950/60 dark:text-sky-100">
      <FileDiffIcon aria-hidden className="size-5 shrink-0" />
      <div className="min-w-0 flex-1">
        <p className="text-xs">{corrected.length > 1 ? 'Koryguje faktury' : 'Koryguje fakturę'}</p>
        {corrected.length === 0 ? (
          <p className="text-sm font-semibold">Korekta nie podaje numeru faktury (korekta za okres)</p>
        ) : (
          <ul className="flex flex-wrap items-center gap-x-4">
            {corrected.slice(0, SHOWN_INVOICES).map((item) => (
              <li key={item.ksefNumber ?? item.number} className="flex items-center gap-1">
                <span className="text-base font-semibold tabular-nums">{item.number}</span>
                {item.issueDate && <span className="text-xs">z {formatDocumentDate(item.issueDate)}</span>}
                {item.number && (
                  <CopyButton value={item.number} label="Kopiuj numer faktury" className="text-inherit" />
                )}
              </li>
            ))}
            {corrected.length > SHOWN_INVOICES && (
              <li className="text-sm" title={corrected.map((item) => item.number).join(', ')}>
                +{corrected.length - SHOWN_INVOICES}
              </li>
            )}
          </ul>
        )}
        {reason && <p className="text-xs text-pretty">Przyczyna: {reason}</p>}
      </div>

      <div className="min-w-0">
        <p className="text-xs">Bon</p>
        {delivery ? (
          <p className="flex items-center gap-1">
            <span className="text-base font-semibold tabular-nums">{delivery.number}</span>
            <CopyButton value={delivery.number} label="Kopiuj numer bonu" className="text-inherit" />
            <Button
              variant="ghost"
              size="icon-xs"
              className="text-inherit"
              aria-label="Zmień bon"
              title="Zmień bon"
              onClick={() => setPickerOpen(true)}
            >
              <PencilIcon />
            </Button>
          </p>
        ) : (
          <Button variant="outline" size="sm" onClick={() => setPickerOpen(true)}>
            <SearchIcon />
            {matched.length > 1 ? `Wybierz bon (${matched.length})` : 'Wybierz bon'}
          </Button>
        )}
        {delivery && (delivery.date || delivery.supplierName) && (
          <p className="text-xs">
            {[delivery.date && formatDocumentDate(delivery.date), delivery.supplierName].filter(Boolean).join(' · ')}
          </p>
        )}
        {delivery && !delivery.id && <p className="text-xs">wpisany ręcznie, spoza raportów</p>}
        {extra.length > 0 && (
          <p className="text-xs" title="Artykuły korekty są na kilku bonach: makro wpisze przy każdym jego bon">
            oraz bon {extra.map((item) => item.number).join(', ')}
          </p>
        )}
      </div>

      <Button
        disabled={!delivery}
        onClick={() => onMacroOpenChange(true)}
        title={delivery ? undefined : 'Najpierw wybierz bon'}
      >
        <ClipboardCopyIcon />
        Makro korekty
      </Button>

      <DeliveryPicker
        storeId={storeId}
        invoiceId={invoice.id}
        open={pickerOpen}
        onOpenChange={setPickerOpen}
        current={delivery ?? null}
        onPick={(picked) => {
          setPickerOpen(false)
          onDelivery(picked)
        }}
      />

      {delivery && (
        <MacroDialog
          storeId={storeId}
          invoiceId={invoice.id}
          deliveries={[delivery, ...extra]}
          hasBeforeRows={correction.hasBeforeRows}
          fromBase={isBaseSeller(invoice.sellerNip)}
          rows={rows}
          open={macroOpen}
          onOpenChange={onMacroOpenChange}
          onLinkRow={onLinkRow}
        />
      )}
    </div>
  )
}

/** Wybór bonu: najpierw bony faktury korygowanej, wyszukiwarka po numerze bonu, faktury i dostawcy albo numer wpisany. */
function DeliveryPicker({
  storeId,
  invoiceId,
  open,
  onOpenChange,
  current,
  onPick,
}: {
  storeId: string
  invoiceId: string | null
  open: boolean
  onOpenChange: (open: boolean) => void
  current: Delivery | null
  onPick: (delivery: Delivery) => void
}) {
  const [query, setQuery] = useState('')
  const debounced = useDebouncedValue(query, 300)
  const deliveries = useCorrectionDeliveries(storeId, invoiceId, debounced, open)
  const typed = query.trim()
  const list = deliveries.data ?? []
  const exact = list.some((item) => item.number === typed)

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-4 p-6 sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Bon, do którego należy korekta</DialogTitle>
          <DialogDescription>
            Na górze bony faktury korygowanej. Stara faktura? Znajdź bon po numerze albo wpisz jego numer ręcznie.
          </DialogDescription>
        </DialogHeader>

        <TextField
          label="Numer bonu, numer faktury albo dostawca"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          autoFocus
        />

        <div className="max-h-[50svh] min-h-32 overflow-y-auto rounded-xl border">
          {deliveries.isError ? (
            <p className="px-4 py-6 text-center text-sm text-destructive">Nie udało się wczytać bonów.</p>
          ) : deliveries.isPending ? (
            <div aria-hidden className="m-3 h-24 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
          ) : list.length === 0 ? (
            <p className="px-4 py-6 text-center text-sm text-muted-foreground">
              Brak bonów. Wpisz numer, żeby go wyszukać.
            </p>
          ) : (
            <ul className="divide-y">
              {list.map((item) => (
                <li key={item.id}>
                  <button
                    type="button"
                    onClick={() => onPick(toDelivery(item))}
                    className={cn(
                      'flex w-full items-center gap-4 px-4 py-2.5 text-left hover:bg-muted',
                      current?.id === item.id && 'bg-muted/60',
                    )}
                  >
                    <span className="min-w-0 flex-1">
                      <span className="flex items-center gap-2 text-sm font-medium tabular-nums">
                        {item.number}
                        {item.matched && (
                          <span className="rounded-md bg-emerald-300 px-1.5 py-0.5 text-xs font-semibold text-emerald-950 dark:bg-emerald-800 dark:text-emerald-50">
                            faktura korygowana
                          </span>
                        )}
                        {item.commonArticles > 0 && (
                          <span
                            className="rounded-md bg-sky-200 px-1.5 py-0.5 text-xs font-medium text-sky-950 dark:bg-sky-900 dark:text-sky-50"
                            title="Tyle artykułów z korekty jest w tym przyjęciu"
                          >
                            artykuły korekty: {item.commonArticles}
                          </span>
                        )}
                      </span>
                      <span className="block truncate text-xs text-muted-foreground">
                        {[
                          formatDocumentDate(item.documentDate),
                          item.supplierName,
                          item.supplierDocumentNumber && `faktura ${item.supplierDocumentNumber}`,
                        ]
                          .filter(Boolean)
                          .join(' · ')}
                      </span>
                    </span>
                    <span className="text-sm whitespace-nowrap tabular-nums">{formatMoney(item.purchaseValue)} zł</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Anuluj
          </Button>
          {typed !== '' && !exact && (
            <Button onClick={() => onPick({ id: null, number: typed, date: null, supplierName: null })}>
              Użyj numeru {typed}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/**
 * Podgląd makra: co wpisze dla każdego artykułu. Pozycje, których nie da się wpisać, są wypisane z powodem, a przy
 * braku na bonie można je od razu przypisać do artykułu. Makro trafia do schowka jak „Uzupełnij ITM8”.
 */
function MacroDialog({
  storeId,
  invoiceId,
  deliveries,
  hasBeforeRows,
  fromBase,
  rows,
  open,
  onOpenChange,
  onLinkRow,
}: {
  storeId: string
  invoiceId: string | null
  deliveries: Delivery[]
  hasBeforeRows: boolean
  /** Korekta bazy SCA PR: protokół typu 10 zamiast 8. */
  fromBase: boolean
  rows: DataEntryRow[]
  open: boolean
  onOpenChange: (open: boolean) => void
  onLinkRow: (row: DataEntryRow) => void
}) {
  const lines = useCorrectionBons(storeId, invoiceId, deliveries, open)
  const numbers = deliveries.map((item) => item.number).join(', ')
  const articles = useMemo(
    () => rows.filter((row) => (row.ean !== '' || row.name !== '') && serviceKind(row) === null),
    [rows],
  )
  const waiting = lines.pending || lines.error
  const plan = useMemo(
    () => (waiting ? null : planCorrectionMacro(articles, hasBeforeRows, lines.bons)),
    [waiting, articles, hasBeforeRows, lines.bons],
  )

  const copy = async () => {
    if (!plan) return
    try {
      await navigator.clipboard.writeText(buildCorrectionMacro(plan.items, todayIso(), fromBase))
    } catch {
      toast.error('Nie udało się skopiować makra do schowka')
      return
    }
    toast.success(`Makro w schowku: ${plan.items.length} poz.`, {
      description: `Naciśnij Alt+E, a odtwarzacz sam otworzy Mercalys i zapyta przed zakończeniem.${
        plan.problems.length > 0 ? ` Pominięte: ${plan.problems.length}.` : ''
      }`,
    })
    onOpenChange(false)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-4 p-6 sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>
            {deliveries.length > 1 ? `Makro korekty bonów ${numbers}` : `Makro korekty bonu ${numbers}`}
          </DialogTitle>
          <DialogDescription>
            {hasBeforeRows
              ? 'Korekta podaje stan po zmianie: ilości i ceny są z faktury.'
              : 'Korekta podaje różnice: ilość to stan bonu po doliczeniu różnicy, cena jest z bonu.'}{' '}
            Makro samo założy protokół w Mercalys, wpisze pozycje i zapyta przed zakończeniem. Każde uruchomienie to
            nowy protokół.
          </DialogDescription>
        </DialogHeader>

        {lines.error ? (
          <p className="text-sm text-destructive">Nie udało się wczytać bonu.</p>
        ) : !plan ? (
          <div aria-hidden className="h-24 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
        ) : (
          <div className="grid gap-3 text-sm">
            {plan.items.length > 0 && (
              <div className="max-h-[45svh] overflow-y-auto rounded-xl border">
                <table className="w-full text-left">
                  <thead className="sticky top-0 bg-popover text-xs text-muted-foreground">
                    <tr>
                      <th className="w-full px-3 py-2 font-medium">Artykuł</th>
                      <th className="px-3 py-2 text-right font-medium">Na bonie</th>
                      <th className="px-3 py-2 text-right font-medium">Ilość poprawna</th>
                      <th className="px-3 py-2 text-right font-medium">Cena zakupu</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y">
                    {plan.items.map((item) => (
                      <tr key={item.key}>
                        <td className="max-w-0 px-3 py-1.5">
                          <span className="block truncate font-medium">{item.name}</span>
                          <span className="block text-xs text-muted-foreground tabular-nums">
                            {item.ean}
                            {deliveries.length > 1 && ` · bon ${item.bonNumber}`}
                          </span>
                          {item.added && (
                            <span className="mt-0.5 mr-1 inline-block rounded-md bg-sky-300 px-1.5 py-0.5 text-xs font-semibold text-sky-950 dark:bg-sky-800 dark:text-sky-50">
                              dochodzi do bonu
                            </span>
                          )}
                        </td>
                        <td
                          className={cn(
                            'px-3 py-1.5 text-right whitespace-nowrap tabular-nums',
                            item.bonDiffers && 'bg-amber-200 text-amber-950 dark:bg-amber-900 dark:text-amber-50',
                          )}
                          title={item.bonDiffers ? 'Na fakturze przed korektą była inna ilość niż na bonie' : undefined}
                        >
                          {item.bonQuantity === null ? '–' : formatQuantity(item.bonQuantity)}
                          {item.bonPrice !== null && (
                            <span className="block text-xs text-muted-foreground">{formatPrice(item.bonPrice)} zł</span>
                          )}
                        </td>
                        <td className="px-3 py-1.5 text-right font-semibold whitespace-nowrap tabular-nums">
                          {formatQuantity(item.quantity)}
                        </td>
                        <td className="px-3 py-1.5 text-right font-semibold whitespace-nowrap tabular-nums">
                          {formatPrice(item.price)} zł
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {plan.items.some((item) => item.bonDiffers) && (
              <p className="rounded-lg bg-amber-200 px-3 py-2 text-amber-950 dark:bg-amber-900 dark:text-amber-50">
                Zaznaczone ilości z bonu różnią się od faktury przed korektą. Sprawdź jednostkę (karton, sztuka) i czy
                bon nie był już poprawiany.
              </p>
            )}

            {plan.problems.length > 0 && (
              <div className="rounded-lg bg-amber-200 px-3 py-2 text-amber-950 dark:bg-amber-900 dark:text-amber-50">
                <p className="font-medium">Makro pominie ({plan.problems.length}), wpisz je ręcznie albo popraw:</p>
                <ul className="mt-1 grid gap-1">
                  {plan.problems.map(({ row, reason, canLink }) => (
                    <li key={row.id} className="flex items-center gap-3 text-xs">
                      <span className="min-w-0 flex-1">
                        <span className="font-medium">{row.name || row.ean}</span> · {reason}
                      </span>
                      {canLink && (
                        <Button
                          variant="outline"
                          size="xs"
                          onClick={() => {
                            onOpenChange(false)
                            onLinkRow(row)
                          }}
                        >
                          Przypisz
                        </Button>
                      )}
                    </li>
                  ))}
                </ul>
              </div>
            )}

            {plan.unchanged > 0 && (
              <p className="text-xs text-muted-foreground">Bez zmian, makro je pomija: {plan.unchanged}.</p>
            )}
            {plan.items.length === 0 && plan.problems.length === 0 && (
              <p className="text-muted-foreground">
                {articles.length === 0
                  ? 'Korekta nie ma pozycji, więc makro nie ma czego wpisać.'
                  : 'Korekta niczego nie zmienia na tym bonie.'}
              </p>
            )}
          </div>
        )}

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Anuluj
          </Button>
          <Button disabled={!plan || plan.items.length === 0} onClick={() => void copy()}>
            <ClipboardCopyIcon />
            Generuj i kopiuj makro
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
