import { useState } from 'react'

import { Form, FormActions, SegmentedField, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { formatMoney } from '@/features/documents/format'

import { parseNumber } from './grid-values'
import { type DataEntryRow, type QuantitySplit, splitQuantity } from './rows'

type SplitUnit = 'kg' | 'szt.'

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
  row: DataEntryRow | null
  onClose: () => void
  onSplit: (row: DataEntryRow, split: QuantitySplit, unit: SplitUnit) => void
}

/**
 * Podział ilości pozycji: opakowanie z faktury (np. 10 worków po 10 kg) na sztuki sprzedaży (100 × 1 kg). Cena dzieli
 * się tak, żeby wartość netto pozycji została ta sama; pozycja zostaje jednym wierszem.
 */
export function SplitQuantityDialog({ row, onClose, onSplit }: Props) {
  return (
    <Dialog open={row !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="gap-5 p-6 sm:max-w-md">
        {row && <SplitForm key={row.id} row={row} onCancel={onClose} onSplit={onSplit} />}
      </DialogContent>
    </Dialog>
  )
}

function SplitForm({ row, onCancel, onSplit }: { row: DataEntryRow; onCancel: () => void; onSplit: Props['onSplit'] }) {
  const [factorText, setFactorText] = useState('')
  const [unit, setUnit] = useState<SplitUnit>(row.unit === 'kg' ? 'kg' : 'szt.')
  const [submitted, setSubmitted] = useState(false)
  const factor = parseNumber(factorText)
  const split = typeof factor === 'number' && factor > 0 ? splitQuantity(row, factor) : null
  const error =
    factor === null
      ? 'Podaj, ile jest w jednej sztuce z faktury'
      : factor === undefined
        ? 'To ma być liczba, np. 10 albo 2,5'
        : !split
          ? 'Liczba musi być większa od 0'
          : null
  const from = row.invoiceUnit ? ` ${row.invoiceUnit}` : ''

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    setSubmitted(true)
    if (split) onSplit(row, split, unit)
  }

  return (
    <Form onSubmit={submit}>
      <DialogHeader>
        <DialogTitle className="text-lg">Podziel ilość</DialogTitle>
        <DialogDescription>
          {row.name || row.ean} · teraz {quantityFormat.format(row.quantity ?? 0)}
          {from} × {priceFormat.format(row.unitNet ?? 0)} zł
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
          hint="Np. 10, gdy worek ma 10 kg"
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

      {split && (
        <div className="rounded-lg bg-muted px-4 py-3 text-sm">
          <p className="tabular-nums">
            {quantityFormat.format(split.quantity)} {unit} × {priceFormat.format(split.unitNet)} zł
          </p>
          <p className="mt-1.5 text-muted-foreground">Wartość netto {formatMoney(split.value)}, tak jak na fakturze.</p>
          {split.xmlValue !== split.value && (
            <p className="mt-1.5 text-muted-foreground">
              Plik XML ma cenę z 2 miejscami ({priceFormat.format(Math.round(split.unitNet * 100) / 100)} zł), więc
              wartość w pliku to {formatMoney(split.xmlValue)}.
            </p>
          )}
        </div>
      )}

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onCancel}>
          Anuluj
        </Button>
        <Button type="submit" size="lg">
          Podziel
        </Button>
      </FormActions>
    </Form>
  )
}
