import { useMemo, useState } from 'react'
import { ClipboardPasteIcon } from 'lucide-react'
import { toast } from 'sonner'

import { FormActions } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import type { PastedProduct, PasteProductsResult } from '@/features/deli-order/api'
import { ApiError } from '@/lib/api'
import type { OrderUnit } from '@/lib/types'

type ParsedPaste = {
  rows: PastedProduct[]
  errors: { line: number; message: string }[]
  duplicates: number
}

const UNIT_ALIASES: Record<string, OrderUnit> = { kg: 'KG', szt: 'SZT', sztuka: 'SZT', sztuki: 'SZT', sztuk: 'SZT' }

/**
 * Tekst skopiowany z Excela: wiersze, komórki rozdzielone tabulatorem. Pierwsza kolumna to EAN, druga jednostka:
 * pusta = kg, inaczej „kg” albo „szt”. Wiersz nagłówka (np. „EAN”) na początku jest pomijany, powtórzony EAN liczy
 * się raz.
 */
function parsePastedProducts(text: string): ParsedPaste {
  const rows: PastedProduct[] = []
  const errors: ParsedPaste['errors'] = []
  const seen = new Set<string>()
  let duplicates = 0

  text.split(/\r?\n/).forEach((rawLine, index) => {
    if (rawLine.trim() === '') return
    const [rawEan = '', rawUnit = ''] = rawLine.split('\t')
    const ean = rawEan.replace(/\s+/g, '')
    const line = index + 1

    if (!/^\d+$/.test(ean)) {
      if (rows.length === 0 && errors.length === 0 && !/\d/.test(ean)) return // nagłówek kolumny
      errors.push({
        line,
        message: /e\+/i.test(ean)
          ? `„${rawEan.trim()}”: Excel skrócił kod. Ustaw w kolumnie format Tekst albo Liczba bez miejsc po przecinku.`
          : `„${rawEan.trim()}” to nie jest EAN (same cyfry)`,
      })
      return
    }
    if (ean.length < 8 || ean.length > 14) {
      errors.push({ line, message: `${ean}: EAN ma od 8 do 14 cyfr` })
      return
    }

    const unitKey = rawUnit.trim().toLowerCase().replace(/\.$/, '')
    const orderUnit = unitKey === '' ? 'KG' : UNIT_ALIASES[unitKey]
    if (!orderUnit) {
      errors.push({ line, message: `${ean}: jednostka „${rawUnit.trim()}” - wpisz kg albo szt (pusta = kg)` })
      return
    }

    if (seen.has(ean)) {
      duplicates++
      return
    }
    seen.add(ean)
    rows.push({ ean, orderUnit })
  })

  return { rows, errors, duplicates }
}

export type PasteDialogState = {
  categoryName: string
  /** Tekst wklejony od razu w wyszukiwarkę kategorii. */
  initialText: string
  onSubmit: (rows: PastedProduct[]) => Promise<PasteProductsResult>
}

/** Wklejanie listy produktów z Excela do kategorii formularza. `null` = okno zamknięte. */
export function PasteProductsDialog({ state, onClose }: { state: PasteDialogState | null; onClose: () => void }) {
  return (
    <Dialog open={state !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="gap-5 p-6 sm:max-w-lg">
        {state && <PasteForm key={state.categoryName + state.initialText} state={state} onDone={onClose} />}
      </DialogContent>
    </Dialog>
  )
}

function PasteForm({ state, onDone }: { state: PasteDialogState; onDone: () => void }) {
  const [text, setText] = useState(state.initialText)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<PasteProductsResult | null>(null)
  const parsed = useMemo(() => parsePastedProducts(text), [text])
  const kg = parsed.rows.filter((row) => row.orderUnit === 'KG').length

  const onSubmit = async () => {
    setError(null)
    setSubmitting(true)
    try {
      const outcome = await state.onSubmit(parsed.rows)
      const summary = resultSummary(outcome)
      if (outcome.notFound.length === 0) {
        toast.success(summary)
        onDone()
      } else {
        setResult(outcome)
      }
    } catch (error) {
      setError(error instanceof ApiError ? error.message : 'Nie udało się dodać produktów')
    } finally {
      setSubmitting(false)
    }
  }

  if (result) {
    return (
      <>
        <DialogHeader>
          <DialogTitle className="text-lg">{resultSummary(result)}</DialogTitle>
          <DialogDescription>
            Tych EAN-ów nie ma w kartotece sklepu ({result.notFound.length}). Sprawdź je albo dodaj produkty
            wyszukiwarką po nazwie.
          </DialogDescription>
        </DialogHeader>
        <ul className="max-h-60 overflow-y-auto rounded-xl border bg-muted/40 px-4 py-2 font-mono text-sm tabular-nums">
          {result.notFound.map((ean) => (
            <li key={ean} className="py-0.5">
              {ean}
            </li>
          ))}
        </ul>
        <FormActions>
          <Button
            type="button"
            variant="outline"
            size="lg"
            onClick={() => navigator.clipboard?.writeText(result.notFound.join('\n'))}
          >
            Kopiuj listę
          </Button>
          <Button type="button" size="lg" onClick={onDone}>
            Zamknij
          </Button>
        </FormActions>
      </>
    )
  }

  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-lg">Wklej produkty do kategorii {state.categoryName}</DialogTitle>
        <DialogDescription>
          Skopiuj z Excela dwie kolumny: EAN i jednostkę. Pusta jednostka to kg, inaczej wpisz kg albo szt.
        </DialogDescription>
      </DialogHeader>

      <textarea
        aria-label="Lista produktów z Excela"
        autoFocus
        rows={8}
        spellCheck={false}
        value={text}
        onChange={(event) => setText(event.target.value)}
        placeholder={'5901234567890\tkg\n5909876543210\tszt\n5900000000017'}
        className="w-full resize-y rounded-xl border border-input bg-background px-3 py-2.5 font-mono text-sm tabular-nums transition-[border-color,box-shadow] duration-150 outline-none placeholder:text-muted-foreground focus:border-ring focus:ring-3 focus:ring-ring/30"
      />

      <div className="grid gap-2 text-sm" aria-live="polite">
        {parsed.rows.length > 0 && (
          <p>
            <span className="font-medium">Rozpoznane produkty: {parsed.rows.length}</span>
            <span className="text-muted-foreground">
              {' '}
              · {kg} w kg, {parsed.rows.length - kg} w szt.
              {parsed.duplicates > 0 && ` · powtórzone pominięte: ${parsed.duplicates}`}
            </span>
          </p>
        )}
        {parsed.errors.length > 0 && (
          <div className="grid gap-1">
            <p className="font-medium text-destructive">Wiersze do poprawy ({parsed.errors.length}), nie zostaną dodane:</p>
            <ul className="max-h-32 overflow-y-auto text-muted-foreground">
              {parsed.errors.map((rowError) => (
                <li key={rowError.line}>
                  <span className="tabular-nums">Wiersz {rowError.line}:</span> {rowError.message}
                </li>
              ))}
            </ul>
          </div>
        )}
        {error && <p className="text-destructive">{error}</p>}
      </div>

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onDone}>
          Anuluj
        </Button>
        <Button
          type="button"
          size="lg"
          loading={submitting}
          disabled={parsed.rows.length === 0}
          onClick={onSubmit}
        >
          <ClipboardPasteIcon />
          Dodaj {parsed.rows.length > 0 ? parsed.rows.length : ''} {productsWord(parsed.rows.length)}
        </Button>
      </FormActions>
    </>
  )
}

function resultSummary({ added, updated }: PasteProductsResult) {
  const parts = [`Dodano ${added} ${productsWord(added)}`]
  if (updated > 0) parts.push(`już były w kategorii: ${updated}`)
  return parts.join(', ')
}

function productsWord(count: number) {
  if (count === 1) return 'produkt'
  const lastTwo = count % 100
  const last = count % 10
  return last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? 'produkty' : 'produktów'
}
