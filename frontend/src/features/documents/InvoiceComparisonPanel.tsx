import { useState } from 'react'
import { Checkbox } from '@base-ui/react/checkbox'
import { CheckIcon, CombineIcon, ListChecksIcon, LoaderCircleIcon, UnlinkIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Switch } from '@/components/ui/switch'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCan } from '@/features/auth/api'
import { useInvoiceComparison, useInvoiceComparisonMatches } from '@/features/documents/api'
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

/** Wiersz po artykułach i pozycjach faktury - ten sam po odświeżeniu porównania. */
const rowKey = (row: InvoiceComparisonRow) => [...row.itm8s, ...row.invoiceKeys].join('|')

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

const signed = (value: number) => (value > 0 ? `+${formatMoney(value)}` : value < 0 ? `−${formatMoney(-value)}` : '0,00')

/**
 * W którym artykule przyjęcie różni się od faktury: pozycje z XML faktur KSeF połączone z artykułami przyjęcia po EAN.
 * Przy niezgodnej kwocie porównanie rusza samo po wejściu do dokumentu; przy zgodnej - na żądanie. Czego EAN nie
 * połączył (inny kod u dostawcy, pozycja przyjęcia rozbita na fakturze na kilka), osoba z edycją dokumentów zaznacza
 * i łączy ręcznie; połączenie da się rozłączyć.
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
  const canEdit = useCan('DOCUMENTS', 'EDIT')
  const { match, unmatch, restore } = useInvoiceComparisonMatches(storeId, documentId)
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set())

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
  const selectedRows = data.rows.filter((row) => selected.has(rowKey(row)))
  const canMerge =
    selectedRows.some((row) => row.itm8s.length > 0) && selectedRows.some((row) => row.invoiceKeys.length > 0)

  const toggle = (row: InvoiceComparisonRow, checked: boolean) =>
    setSelected((current) => {
      const next = new Set(current)
      if (checked) next.add(rowKey(row))
      else next.delete(rowKey(row))
      return next
    })
  const merge = () =>
    match.mutate(
      {
        invoiceKeys: [...new Set(selectedRows.flatMap((row) => row.invoiceKeys))],
        itm8s: [...new Set(selectedRows.flatMap((row) => row.itm8s))],
      },
      {
        onSuccess: (created) => {
          setSelected(new Set())
          toast.success('Połączono pozycje', {
            description: 'Od teraz porównują się razem w jednym wierszu.',
            action: { label: 'Cofnij', onClick: () => unmatch.mutate([created.id]) },
          })
        },
        onError: (failure) => toast.error(errorMessage(failure, 'Nie udało się połączyć pozycji')),
      },
    )
  const split = (row: InvoiceComparisonRow) =>
    unmatch.mutate(row.matchIds, {
      onSuccess: () =>
        toast.success('Rozłączono pozycje', {
          action: { label: 'Przywróć', onClick: () => restore.mutate(row.matchIds) },
        }),
      onError: (failure) => toast.error(errorMessage(failure, 'Nie udało się rozłączyć pozycji')),
    })

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
          {canEdit && differences.length > 0 && (
            <p className="mt-1 max-w-prose text-xs text-muted-foreground">
              Inny kod na fakturze albo jedna pozycja rozbita na kilka? Zaznacz artykuł z przyjęcia i pozycje z faktury,
              które są tym samym towarem, i połącz je.
            </p>
          )}
        </div>
        {matching > 0 && (
          <label className="flex items-center gap-2 text-sm text-muted-foreground">
            <Switch checked={showMatching} onCheckedChange={setShowMatching} />
            Pokaż zgodne ({matching})
          </label>
        )}
      </div>

      {canEdit && selectedRows.length > 0 && (
        <div className="mb-2 flex flex-wrap items-center gap-x-3 gap-y-2 rounded-xl border bg-muted/40 px-3 py-2 text-sm">
          <span className="min-w-0 flex-1 text-muted-foreground tabular-nums">
            Zaznaczono: {selectedRows.length}
            {!canMerge && ' · zaznacz artykuł z przyjęcia i pozycję z faktury'}
          </span>
          <Button variant="ghost" size="sm" onClick={() => setSelected(new Set())}>
            Wyczyść
          </Button>
          <Button size="sm" loading={match.isPending} disabled={!canMerge} onClick={merge}>
            <CombineIcon />
            Połącz zaznaczone
          </Button>
        </div>
      )}

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
                {canEdit && (
                  <TableHead className="w-10 pl-4">
                    <span className="sr-only">Zaznacz do połączenia</span>
                  </TableHead>
                )}
                <TableHead className={cn(!canEdit && 'pl-4')}>Towar</TableHead>
                <TableHead className="text-right">Przyjęcie</TableHead>
                <TableHead className="text-right">Faktura</TableHead>
                <TableHead className="pr-4 text-right">Różnica zł</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {visible.map((row) => (
                <ComparisonRow
                  key={rowKey(row)}
                  row={row}
                  selectable={canEdit}
                  selected={selected.has(rowKey(row))}
                  onSelect={(checked) => toggle(row, checked)}
                  onSplit={canEdit && row.matchIds.length > 0 ? () => split(row) : undefined}
                  splitting={unmatch.isPending && unmatch.variables === row.matchIds}
                />
              ))}
            </TableBody>
          </Table>
        </Card>
      )}
    </div>
  )
}

function ComparisonRow({
  row,
  selectable,
  selected,
  onSelect,
  onSplit,
  splitting,
}: {
  row: InvoiceComparisonRow
  selectable: boolean
  selected: boolean
  onSelect: (checked: boolean) => void
  onSplit?: () => void
  splitting: boolean
}) {
  const match = row.status === 'MATCH'
  const label = row.name ?? row.invoiceName ?? 'Bez nazwy'
  return (
    <TableRow className={cn('tabular-nums', match && 'text-muted-foreground', selected && 'bg-muted/50')}>
      {selectable && (
        <TableCell className="py-2.5 pl-4 align-top">
          <Checkbox.Root
            checked={selected}
            onCheckedChange={onSelect}
            aria-label={`Zaznacz do połączenia: ${label}`}
            className="mt-0.5 flex size-4 items-center justify-center rounded border border-input outline-none focus-visible:ring-3 focus-visible:ring-ring/50 data-checked:border-primary data-checked:bg-primary"
          >
            <Checkbox.Indicator className="text-primary-foreground">
              <CheckIcon className="size-3" />
            </Checkbox.Indicator>
          </Checkbox.Root>
        </TableCell>
      )}
      <TableCell className={cn('max-w-96 py-2.5 whitespace-normal', !selectable && 'pl-4')}>
        <div className="flex flex-wrap items-center gap-2 font-medium">
          {label}
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
          {row.matchIds.length > 0 && (
            <span className="rounded-md bg-sky-50 px-1.5 py-0.5 text-[0.7rem] font-medium text-sky-800 dark:bg-sky-950/40 dark:text-sky-200">
              Połączone ręcznie
            </span>
          )}
          {onSplit && (
            <Button
              variant="ghost"
              size="xs"
              className="text-muted-foreground"
              loading={splitting}
              onClick={onSplit}
            >
              <UnlinkIcon />
              Rozłącz
            </Button>
          )}
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
