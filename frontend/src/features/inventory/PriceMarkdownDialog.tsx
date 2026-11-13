import { ClipboardCopyIcon, TagIcon, TriangleAlertIcon } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'

import { DateField, Form, FormActions, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { buildMarkdownMacro } from '@/features/data-entry/mercalys-macro'
import { parseNumber } from '@/features/data-entry/grid-values'
import { formatDocumentDate, formatMoney, formatPrice } from '@/features/documents/format'
import { useCreatePriceMarkdown, useMarkdownPrices } from '@/features/inventory/api'
import { ApiError } from '@/lib/api'
import { todayIso } from '@/lib/dates'
import type { SalesArticle } from '@/lib/types'
import { marginOf } from '@/lib/margin'
import { cn } from '@/lib/utils'

const percentFormat = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 1 })

/** Marża od sprzedaży brutto przy cenie brutto (jak w ustawianiu promocji); bez ceny zakupu albo VAT-u nieznana. */
function marginAt(price: number | null, purchase: number | null, vatPercent: number | null) {
  if (price == null || price <= 0 || purchase == null || vatPercent == null) return null
  return marginOf(price, vatPercent, purchase)
}

/** Przycisk przy cenie sprzedaży na karcie artykułu. */
export function PriceMarkdownButton({ storeId, article }: { storeId: string; article: SalesArticle }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button variant="outline" size="xs" className="mt-1" onClick={() => setOpen(true)}>
        <TagIcon />
        Przeceń
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="gap-5 p-6 sm:max-w-md">
          <MarkdownForm storeId={storeId} article={article} onDone={() => setOpen(false)} />
        </DialogContent>
      </Dialog>
    </>
  )
}

/**
 * Nowa cena i okres: makro idzie do schowka (Alt+E odtwarzacza wpisuje je w taryfach sprzedaży w Mercalys), a artykuł trafia
 * na listę cenówek.
 */
function MarkdownForm({ storeId, article, onDone }: { storeId: string; article: SalesArticle; onDone: () => void }) {
  const create = useCreatePriceMarkdown(storeId)
  const prices = useMarkdownPrices(storeId, article.itm8)
  const [priceText, setPriceText] = useState('')
  const [dateFrom, setDateFrom] = useState(todayIso())
  const [dateTo, setDateTo] = useState('')

  const parsed = parseNumber(priceText)
  const price = parsed != null && parsed > 0 && Number.isInteger(Math.round(parsed * 1e6) / 1e4) ? parsed : null
  const priceError = priceText.trim() !== '' && price == null ? 'Wpisz cenę, np. 2,49' : undefined
  const periodError = dateTo && dateTo < dateFrom ? 'Koniec przeceny nie może być przed początkiem' : undefined
  const ready = price != null && dateTo !== '' && !periodError
  // Do czasu odpowiedzi (albo przy błędzie) ceny z karty: kartoteka i cena zakupu z kartoteki.
  const regular = prices.data ? prices.data.regularPrice : article.salePrice
  const delivered = prices.data?.lastPurchasePrice != null
  const purchase = delivered ? prices.data!.lastPurchasePrice : article.purchasePrice
  const notLower = price != null && regular != null && price >= regular
  const discount = price != null && regular != null && regular > 0 ? (1 - price / regular) * 100 : null
  const marginNow = marginAt(regular, purchase, article.vatPercent)
  const margin = marginAt(price, purchase, article.vatPercent)

  const submit = async () => {
    if (price == null || !ready) return
    try {
      const macro = buildMarkdownMacro({ ean: article.ean, itm8: article.itm8, price, from: dateFrom, to: dateTo })
      await navigator.clipboard.writeText(macro)
    } catch {
      toast.error('Nie udało się skopiować makra do schowka')
      return
    }
    create.mutate(
      { itm8: article.itm8, newPrice: price, validFrom: dateFrom, validTo: dateTo },
      {
        onSuccess: () => {
          toast.success('Makro przeceny w schowku', {
            description: 'Naciśnij Alt+E, a odtwarzacz sam otworzy Mercalys. Cenówka czeka na liście.',
          })
          onDone()
        },
        onError: (error) =>
          toast.error(error instanceof ApiError ? error.message : 'Nie udało się dodać artykułu do listy cenówek'),
      },
    )
  }

  return (
    <Form
      onSubmit={(event) => {
        event.preventDefault()
        void submit()
      }}
    >
      <DialogHeader>
        <DialogTitle>Przeceń artykuł</DialogTitle>
        <DialogDescription>
          {article.name ?? `ITM8 ${article.itm8}`} · EAN {article.ean}
        </DialogDescription>
      </DialogHeader>

      <dl className="grid grid-cols-3 gap-2 text-sm">
        <Figure
          label="Ostatnia dostawa"
          value={purchase != null ? `${formatPrice(purchase)} zł` : '–'}
          note={
            prices.isPending
              ? 'sprawdzam…'
              : delivered
                ? `netto, ${formatDocumentDate(prices.data!.lastPurchaseDate!)}`
                : 'bez przyjęcia, z kartoteki'
          }
        />
        <Figure label="Cena bez promocji" value={regular != null ? `${formatMoney(regular)} zł` : '–'} note="brutto" />
        <Figure
          label="Marża teraz"
          value={marginNow != null ? `${percentFormat.format(marginNow)}%` : '–'}
          note="od brutto"
          negative={marginNow != null && marginNow < 0}
        />
      </dl>

      <TextField
        label="Nowa cena sprzedaży brutto"
        inputMode="decimal"
        autoFocus
        value={priceText}
        onChange={(event) => setPriceText(event.target.value)}
        error={priceError}
      />

      <div className="grid gap-3 sm:grid-cols-2">
        <DateField label="Od" value={dateFrom} onChange={setDateFrom} />
        <DateField label="Do" value={dateTo} min={dateFrom} onChange={setDateTo} error={periodError} />
      </div>

      <dl className="grid grid-cols-2 gap-2 text-sm">
        <Figure
          label="Przecena"
          value={discount != null && discount > 0 ? `${percentFormat.format(discount)}%` : '–'}
          note="od ceny bez promocji"
        />
        <Figure
          label="Marża po przecenie"
          value={margin != null ? `${percentFormat.format(margin)}%` : '–'}
          note={purchase == null ? 'brak ceny zakupu' : 'od brutto, z ostatniej dostawy'}
          negative={margin != null && margin < 0}
          strong
        />
      </dl>

      {notLower && <Warning>Nowa cena nie jest niższa od obecnej.</Warning>}

      <FormActions>
        <Button type="submit" size="lg" loading={create.isPending} disabled={!ready}>
          <ClipboardCopyIcon />
          Skopiuj makro przeceny
        </Button>
      </FormActions>
    </Form>
  )
}

function Figure({
  label,
  value,
  note,
  negative = false,
  strong = false,
}: {
  label: string
  value: string
  note: string
  negative?: boolean
  strong?: boolean
}) {
  return (
    <div
      className={cn(
        'rounded-lg bg-muted px-3 py-2',
        negative && 'bg-red-200 text-red-950 dark:bg-red-900/70 dark:text-red-100',
      )}
    >
      <dt className={cn('text-xs text-muted-foreground', negative && 'text-red-950/80 dark:text-red-100/80')}>
        {label}
      </dt>
      <dd className={cn('tabular-nums', strong ? 'text-lg font-semibold' : 'font-medium')}>{value}</dd>
      <dd className={cn('text-xs text-muted-foreground', negative && 'text-red-950/80 dark:text-red-100/80')}>
        {note}
      </dd>
    </div>
  )
}

function Warning({ children }: { children: string }) {
  return (
    <p className="flex items-start gap-2 rounded-lg bg-amber-200 px-3 py-2 text-sm text-amber-950 dark:bg-amber-950/60 dark:text-amber-100">
      <TriangleAlertIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
      {children}
    </p>
  )
}
