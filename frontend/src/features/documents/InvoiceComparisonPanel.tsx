import { useState } from 'react'
import { ListChecksIcon, LoaderCircleIcon } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Switch } from '@/components/ui/switch'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCan } from '@/features/auth/api'
import { useInvoiceComparison } from '@/features/documents/api'
import { formatMoney, formatPrice, formatQuantity } from '@/features/documents/format'
import { ApiError } from '@/lib/api'
import type { InvoiceAmountCheck, InvoiceComparisonRow, InvoiceComparisonStatus } from '@/lib/types'
import { cn } from '@/lib/utils'

const STATUS: Record<InvoiceComparisonStatus, string> = {
  MATCH: 'Zgodne',
  PRICE: 'Inna cena',
  QUANTITY: 'Inna ilość',
  MISSING_ON_INVOICE: 'Brak na fakturze',
  MISSING_IN_DELIVERY: 'Brak w przyjęciu',
  NO_CODE: 'Pozycja bez EAN',
}

const signed = (value: number) => (value > 0 ? `+${formatMoney(value)}` : value < 0 ? `−${formatMoney(-value)}` : '0,00')

/**
 * W którym artykule przyjęcie różni się od faktury: pozycje z XML faktur KSeF połączone z artykułami przyjęcia po EAN.
 * Przy niezgodnej kwocie porównanie rusza samo po wejściu do dokumentu; przy zgodnej - na żądanie.
 */
export function InvoiceComparisonPanel({
  storeId,
  documentId,
  check,
}: {
  storeId: string
  documentId: string
  check: InvoiceAmountCheck | null
}) {
  const canViewKsef = useCan('KSEF_INVOICES', 'VIEW')
  const [requested, setRequested] = useState(false)
  const automatic = check != null && !check.matches && check.confirmedAt == null
  const enabled = canViewKsef && check != null && (automatic || requested)
  const { data, isPending, isError, error } = useInvoiceComparison(storeId, documentId, enabled)
  const [showMatching, setShowMatching] = useState(false)

  if (!canViewKsef || !check) return null
  if (!enabled) {
    return (
      <Button variant="ghost" size="sm" className="mt-2 text-muted-foreground" onClick={() => setRequested(true)}>
        <ListChecksIcon />
        Porównaj pozycje z fakturą
      </Button>
    )
  }
  if (isPending) {
    return (
      <p className="mt-3 inline-flex items-center gap-2 text-sm text-muted-foreground">
        <LoaderCircleIcon aria-hidden className="size-4 animate-spin motion-reduce:animate-none" />
        Pobieram fakturę z KSeF i porównuję pozycje…
      </p>
    )
  }
  if (isError) {
    return (
      <p className="mt-3 text-sm text-destructive">
        {error instanceof ApiError ? error.message : 'Nie udało się porównać pozycji z fakturą.'}
      </p>
    )
  }

  const differences = data.rows.filter((row) => row.status !== 'MATCH')
  const matching = data.rows.length - differences.length
  const visible = showMatching ? data.rows : differences

  return (
    <div className="mt-4">
      <div className="mb-2 flex flex-wrap items-end justify-between gap-x-4 gap-y-2">
        <div>
          <h4 className="text-sm font-semibold">Porównanie pozycji z fakturą</h4>
          <p className="text-xs text-muted-foreground tabular-nums">
            {data.invoices.length > 0 && `${data.invoices.join(', ')} · `}
            pozycje faktur netto {formatMoney(data.invoiceNet)} zł, przyjęcie {formatMoney(data.deliveryValue)} zł ·
            łączone po EAN
          </p>
        </div>
        {matching > 0 && (
          <label className="flex items-center gap-2 text-sm text-muted-foreground">
            <Switch checked={showMatching} onCheckedChange={setShowMatching} />
            Pokaż zgodne ({matching})
          </label>
        )}
      </div>

      {data.unavailable.map((item) => (
        <p key={item.invoiceNumber} className="mb-2 text-sm text-amber-800 dark:text-amber-200">
          Faktury {item.invoiceNumber} nie udało się teraz pobrać: {item.message}
        </p>
      ))}

      {visible.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          Wszystkie pozycje zgadzają się z fakturą - różnica wynika z zaokrągleń albo pozycji spoza listy.
        </p>
      ) : (
        <Card className="py-0">
          <Table className="min-w-4xl">
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="pl-4">Towar</TableHead>
                <TableHead className="text-right">Przyjęcie</TableHead>
                <TableHead className="text-right">Faktura</TableHead>
                <TableHead className="pr-4 text-right">Różnica zł</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {visible.map((row, index) => (
                <ComparisonRow key={`${row.itm8 ?? row.ean ?? row.invoiceName}-${index}`} row={row} />
              ))}
            </TableBody>
          </Table>
        </Card>
      )}
    </div>
  )
}

function ComparisonRow({ row }: { row: InvoiceComparisonRow }) {
  const match = row.status === 'MATCH'
  return (
    <TableRow className={cn('tabular-nums', match && 'text-muted-foreground')}>
      <TableCell className="max-w-96 py-2.5 pl-4 whitespace-normal">
        <div className="flex flex-wrap items-center gap-2 font-medium">
          {row.name ?? row.invoiceName ?? 'Bez nazwy'}
          <span
            className={cn(
              'rounded-md px-1.5 py-0.5 text-[0.7rem] font-medium',
              match
                ? 'bg-muted text-muted-foreground'
                : 'bg-amber-50 text-amber-900 dark:bg-amber-950/40 dark:text-amber-200',
            )}
          >
            {STATUS[row.status]}
          </span>
        </div>
        <div className="text-xs text-muted-foreground">
          {[
            row.ean && `EAN ${row.ean}`,
            row.itm8 && `ITM8 ${row.itm8}`,
            row.invoiceName && row.invoiceName !== row.name && row.name && `na fakturze: ${row.invoiceName}`,
          ]
            .filter(Boolean)
            .join(' · ')}
        </div>
      </TableCell>
      <TableCell className="text-right">
        <Amounts quantity={row.deliveryQuantity} price={row.deliveryPrice} value={row.deliveryValue} />
      </TableCell>
      <TableCell className="text-right">
        <Amounts quantity={row.invoiceQuantity} price={row.invoicePrice} value={row.invoiceValue} />
      </TableCell>
      <TableCell className={cn('pr-4 text-right font-medium', match && 'font-normal')}>
        {row.difference != null ? signed(row.difference) : ''}
      </TableCell>
    </TableRow>
  )
}

/** "100 × 2,00" nad wartością netto; puste, gdy artykułu nie ma po tej stronie. */
function Amounts({ quantity, price, value }: { quantity: number | null; price: number | null; value: number | null }) {
  if (value == null) return <span className="text-muted-foreground">-</span>
  return (
    <>
      <div>{formatMoney(value)}</div>
      {quantity != null && (
        <div className="text-xs text-muted-foreground">
          {formatQuantity(quantity)}
          {price != null && ` × ${formatPrice(price)}`}
        </div>
      )}
    </>
  )
}
