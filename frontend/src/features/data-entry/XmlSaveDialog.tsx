import { FileCodeIcon } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { formatMoney } from '@/features/documents/format'

import { differs, type InvoiceTotals, signedMoney } from './invoice-totals'
import type { DataEntryRow } from './rows'

export type XmlProblems = {
  invalidEan: DataEntryRow[]
  tooLong: DataEntryRow[]
  noSubfamily: DataEntryRow[]
}

type Props = {
  /** Pozycje do pliku (bez kaucji i dostawy). */
  count: number
  problems: XmlProblems
  /** Sumy tabeli i faktury: różnica wymaga potwierdzenia zapisu, ale go nie blokuje. */
  totals: InvoiceTotals
  /**
   * Ten sam EAN w kilku wierszach z różnymi cenami. Program sklepu łączy pozycje pliku z jednym EAN-em: sumuje ilości
   * i bierze cenę pierwszej (21 × 13,29 i 9 × 0,03 przyjął jako 30 × 13,29), więc przed zapisem trzeba je połączyć.
   */
  priceConflicts: DataEntryRow[][]
  onMergeDuplicates: () => void
  open: boolean
  onOpenChange: (open: boolean) => void
  onGoTo: (row: DataEntryRow, key: 'ean' | 'name' | 'subfamily') => void
  onSave: () => void
  saving: boolean
}

/**
 * Sprawdzenie przed zapisem, gdy tabela ma braki: „Zapisz” bez braków zapisuje od razu, a z nimi otwiera to okno.
 * Błędny EAN nowego artykułu blokuje zapis; pozostałe braki tabeli i różnica sum z fakturą nie blokują: przycisk
 * pyta wtedy „Zapisz mimo …”.
 */
export function XmlSaveDialog({
  count,
  problems,
  totals,
  priceConflicts,
  onMergeDuplicates,
  open,
  onOpenChange,
  onGoTo,
  onSave,
  saving,
}: Props) {
  // Błędny EAN blokuje zapis: program sklepu założyłby artykuł z kodem, którego nie ma na towarze.
  const invalidEan = problems.invalidEan.length > 0
  const issues = [
    { rows: problems.tooLong, text: 'Nazwa dłuższa niż 30 znaków', key: 'name' as const, show: 'Pokaż pierwszą' },
    { rows: problems.noSubfamily, text: 'Nowy artykuł bez podrodziny', key: 'subfamily' as const, show: 'Pokaż pierwszy' },
  ].filter((issue) => issue.rows.length > 0)

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Sprawdź przed zapisem</DialogTitle>
          <DialogDescription>
            Pozycje z tabeli: {count} (bez kaucji i dostawy). Artykuły bez EAN-u trafią do pliku z kodem 0000000000000.
          </DialogDescription>
        </DialogHeader>

        {totals.mismatch && (
          <div
            role="alert"
            className="rounded-xl border border-red-400 bg-red-200 px-4 py-3 text-sm text-red-950 dark:border-red-500/50 dark:bg-red-950/70 dark:text-red-50"
          >
            <p className="font-medium">Suma tabeli nie zgadza się z fakturą</p>
            <p className="mt-1">
              Sprawdź ilości i ceny: program sklepu nie przyjmie dokumentu z błędną ilością. Możesz zapisać mimo to.
            </p>
            <dl className="mt-2 grid grid-cols-[auto_1fr_1fr_1fr] gap-x-4 gap-y-0.5 tabular-nums">
              <span />
              <dt className="text-right text-xs">Tabela</dt>
              <dt className="text-right text-xs">Faktura</dt>
              <dt className="text-right text-xs">Różnica</dt>
              <SumRow label="Netto" value={totals.net} document={totals.invoiceNet} diff={totals.netDiff} />
              <SumRow label="VAT" value={totals.vat} document={totals.invoiceVat} diff={totals.vatDiff} />
            </dl>
          </div>
        )}

        {invalidEan && (
          <div
            role="alert"
            className="rounded-xl border border-red-400 bg-red-200 px-4 py-3 text-sm text-red-950 dark:border-red-500/50 dark:bg-red-950/70 dark:text-red-50"
          >
            <p className="font-medium">Błędny EAN nowego artykułu: {problems.invalidEan.length}</p>
            <p className="mt-1">
              Zła długość albo cyfra kontrolna. Popraw EAN z towaru (albo wyczyść komórkę, gdy towar nie ma kodu), dopiero
              wtedy plik da się zapisać.{' '}
              <button
                type="button"
                className="font-medium underline underline-offset-3"
                onClick={() => onGoTo(problems.invalidEan[0], 'ean')}
              >
                Pokaż pierwszy
              </button>
            </p>
          </div>
        )}

        {priceConflicts.length > 0 && (
          <div
            role="alert"
            className="rounded-xl border border-red-400 bg-red-200 px-4 py-3 text-sm text-red-950 dark:border-red-500/50 dark:bg-red-950/70 dark:text-red-50"
          >
            <p className="font-medium">Ten sam artykuł w kilku cenach</p>
            <p className="mt-1">
              Program sklepu łączy pozycje z tym samym EAN-em: sumuje ilości i bierze cenę z pierwszej. Połącz je w
              jeden wiersz ze średnią ceną, inaczej przyjęcie będzie miało złą wartość.
            </p>
            <ul className="mt-2 space-y-1">
              {priceConflicts.map((group) => (
                <li key={group[0].id} className="tabular-nums">
                  <span className="font-medium">{group[0].name || group[0].ean}</span>:{' '}
                  {group.map((row) => `${formatQuantity(row.quantity)} × ${formatPrice(row.unitNet)}`).join(', ')}
                </li>
              ))}
            </ul>
            <Button variant="outline" size="sm" className="mt-3" onClick={onMergeDuplicates}>
              Połącz w jeden wiersz
            </Button>
          </div>
        )}

        {issues.length > 0 && (
          <div className="rounded-xl border border-amber-300 bg-amber-100 px-4 py-3 text-sm text-amber-950 dark:border-amber-500/40 dark:bg-amber-950/60 dark:text-amber-100">
            <p className="font-medium">Tabela ma braki</p>
            <ul className="mt-1.5 space-y-1.5">
              {issues.map((issue) => (
                <li key={issue.key}>
                  {issue.text}: {issue.rows.length}.{' '}
                  <button
                    type="button"
                    className="font-medium underline underline-offset-3"
                    onClick={() => onGoTo(issue.rows[0], issue.key)}
                  >
                    {issue.show}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        )}

        <Button size="xl" loading={saving} disabled={count === 0 || invalidEan} onClick={onSave}>
          <FileCodeIcon />
          {invalidEan
            ? 'Popraw błędne EAN-y'
            : totals.mismatch
              ? 'Zapisz mimo różnicy'
              : issues.length > 0 || priceConflicts.length > 0
                ? 'Zapisz mimo to'
                : 'Zapisz plik XML'}
        </Button>

      </DialogContent>
    </Dialog>
  )
}

function formatQuantity(quantity: number | null) {
  return quantity === null ? '–' : quantity.toLocaleString('pl-PL', { maximumFractionDigits: 3 })
}

function formatPrice(price: number | null) {
  return price === null ? '–' : `${formatMoney(price)} zł`
}

function SumRow({
  label,
  value,
  document,
  diff,
}: {
  label: string
  value: number
  document: number | null
  diff: number | null
}) {
  return (
    <>
      <dt>{label}</dt>
      <dd className="text-right">{formatMoney(value)} zł</dd>
      <dd className="text-right">{document === null ? '–' : `${formatMoney(document)} zł`}</dd>
      <dd className={differs(diff) ? 'text-right font-semibold' : 'text-right'}>
        {diff === null ? '–' : `${signedMoney(diff)} zł`}
      </dd>
    </>
  )
}
