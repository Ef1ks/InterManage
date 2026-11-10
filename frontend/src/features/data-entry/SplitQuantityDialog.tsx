import { useState } from 'react'

import { Form, FormActions, SegmentedField, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { formatMoney } from '@/features/documents/format'

import { parseNumber } from './grid-values'
import { type DataEntryRow, type QuantitySplit, splitQuantity } from './rows'

type SplitUnit = 'kg' | 'szt.'

export type RowSplit = { row: DataEntryRow; split: QuantitySplit }

const UNIT_OPTIONS = [
  { value: 'kg', label: 'kg' },
  { value: 'szt.', label: 'szt.' },
] satisfies { value: SplitUnit; label: string }[]

const quantityFormat = new Intl.NumberFormat('pl-PL', {
  maximumFractionDigits: 3,
})
const priceFormat = new Intl.NumberFormat('pl-PL', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 4,
})

type Props = {
  /** Pozycje do podziału (jedna z ikony w komórce albo zaznaczone w tabeli); pusta lista = okno zamknięte. */
  rows: DataEntryRow[]
  onClose: () => void
  onSplit: (splits: RowSplit[], unit: SplitUnit) => void
}

/**
 * Podział ilości pozycji: opakowanie z faktury (np. 10 worków po 10 kg) na sztuki sprzedaży (100 × 1 kg). Cena dzieli
 * się tak, żeby wartość netto pozycji została ta sama; pozycja zostaje jednym wierszem. Przy kilku zaznaczonych
 * pozycjach ta sama liczba dzieli każdą z nich (np. 30 artykułów w opakowaniach po 10 szt.).
 */
export function SplitQuantityDialog({ rows, onClose, onSplit }: Props) {
  return (
    <Dialog open={rows.length > 0} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="gap-5 p-6 sm:max-w-md">
        {rows.length > 0 && (
          <SplitForm key={rows.map((row) => row.id).join()} rows={rows} onCancel={onClose} onSplit={onSplit} />
        )}
      </DialogContent>
    </Dialog>
  )
}

function SplitForm({
  rows,
  onCancel,
  onSplit,
}: {
  rows: DataEntryRow[]
  onCancel: () => void
  onSplit: Props['onSplit']
}) {
  const [factorText, setFactorText] = useState('')
  const [unit, setUnit] = useState<SplitUnit>(rows.every((row) => row.unit === 'kg') ? 'kg' : 'szt.')
  const [submitted, setSubmitted] = useState(false)
  const factor = parseNumber(factorText)
  const splits: RowSplit[] =
    typeof factor === 'number' && factor > 0
      ? rows.flatMap((row) => {
          const split = splitQuantity(row, factor)
          return split ? [{ row, split }] : []
        })
      : []
  const error =
    factor === null
      ? 'Podaj, ile jest w jednej sztuce z faktury'
      : factor === undefined
        ? 'To ma być liczba, np. 10 albo 2,5'
        : splits.length === 0
          ? 'Liczba musi być większa od 0'
          : null
  const single = rows.length === 1 ? rows[0] : null
  const from = single?.invoiceUnit ? ` ${single.invoiceUnit}` : ''
  const value = splits.reduce((sum, { split }) => sum + split.value, 0)
  const xmlValue = splits.reduce((sum, { split }) => sum + split.xmlValue, 0)
  const xmlDiffers = splits.some(({ split }) => split.xmlValue !== split.value)

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    setSubmitted(true)
    if (splits.length > 0) onSplit(splits, unit)
  }

  return (
    <Form onSubmit={submit}>
      <DialogHeader>
        <DialogTitle className="text-lg">
          {single ? 'Podziel ilość' : `Podziel ilość: ${rows.length} pozycji`}
        </DialogTitle>
        <DialogDescription>
          {single ? (
            <>
              {single.name || single.ean} · teraz {quantityFormat.format(single.quantity ?? 0)}
              {from} × {priceFormat.format(single.unitNet ?? 0)} zł
            </>
          ) : (
            'Każda zaznaczona pozycja zostanie podzielona tą samą liczbą.'
          )}
        </DialogDescription>
      </DialogHeader>
      <div className="flex items-start gap-3">
        <TextField
          label="Ile w 1 sztuce z faktury"
          inputMode="decimal"
          autoComplete="off"
          autoFocus
          className="flex-1"
          value={factorText}
          onChange={(event) => setFactorText(event.currentTarget.value)}
          hint="Np. 10, gdy opakowanie ma 10 szt."
          error={submitted ? (error ?? undefined) : undefined}
        />
        <SegmentedField
          label="Jednostka po podziale"
          options={UNIT_OPTIONS}
          value={unit}
          onChange={setUnit}
          className="mt-2"
        />
      </div>

      {splits.length > 0 && (
        <div className="rounded-lg bg-muted px-4 py-3 text-sm">
          {single ? (
            <p className="tabular-nums">
              {quantityFormat.format(splits[0].split.quantity)} {unit} × {priceFormat.format(splits[0].split.unitNet)}{' '}
              zł
            </p>
          ) : (
            <ul className="max-h-48 space-y-1 overflow-y-auto tabular-nums">
              {splits.map(({ row, split }) => (
                <li key={row.id} className="flex justify-between gap-3">
                  <span className="truncate">{row.name || row.ean}</span>
                  <span className="shrink-0">
                    {quantityFormat.format(row.quantity ?? 0)} → {quantityFormat.format(split.quantity)} {unit} ×{' '}
                    {priceFormat.format(split.unitNet)} zł
                  </span>
                </li>
              ))}
            </ul>
          )}
          <p className="mt-1.5 text-muted-foreground">Wartość netto {formatMoney(value)}, tak jak na fakturze.</p>
          {xmlDiffers && (
            <p className="mt-1.5 text-muted-foreground">
              Plik XML ma ceny z 2 miejscami, więc wartość w pliku to {formatMoney(xmlValue)}.
            </p>
          )}
        </div>
      )}

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onCancel}>
          Anuluj
        </Button>
        <Button type="submit" size="lg">
          {single ? 'Podziel' : `Podziel ${splits.length || rows.length}`}
        </Button>
      </FormActions>
    </Form>
  )
}
