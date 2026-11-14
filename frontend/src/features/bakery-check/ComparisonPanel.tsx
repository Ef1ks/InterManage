import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Link2Icon } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useBakeryComparison } from '@/features/bakery-check/api'
import { LinkArticleDialog, type LinkTarget } from '@/features/data-entry/LinkArticleDialog'
import { emptyRow } from '@/features/data-entry/rows'
import { formatMoney, formatPrice, formatQuantity } from '@/features/documents/format'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import type { BakeryComparison, BakeryComparisonRow, BakeryRowStatus } from '@/lib/types'
import { cn } from '@/lib/utils'

const STATUS: Record<BakeryRowStatus, { label: string; className: string }> = {
  OK: { label: 'Zgodne', className: 'bg-emerald-200 text-emerald-950 dark:bg-emerald-900 dark:text-emerald-50' },
  WZ: { label: 'Inna ilość na WZ', className: 'bg-red-200 text-red-950 dark:bg-red-900 dark:text-red-50' },
  BON: { label: 'Bon inny niż faktura', className: 'bg-amber-200 text-amber-950 dark:bg-amber-900 dark:text-amber-50' },
  NO_ARTICLE: { label: 'Bez artykułu', className: 'bg-amber-200 text-amber-950 dark:bg-amber-900 dark:text-amber-50' },
}

const quantity = (value: number | null) => (value === null ? '–' : formatQuantity(value))
const signed = (value: number | null) =>
  value === null ? '–' : value > 0 ? `+${formatQuantity(value)}` : formatQuantity(value)

/** Na dole weryfikacji: suma WZ kontra faktura z korektami i bon po korektach. */
export function ComparisonPanel({ storeId, checkId }: { storeId: string; checkId: string }) {
  const { data, isPending, isError, refetch } = useBakeryComparison(storeId, checkId)
  const queryClient = useQueryClient()
  const [onlyDifferences, setOnlyDifferences] = useState(false)
  const [target, setTarget] = useState<LinkTarget | null>(null)

  if (isPending)
    return <div aria-hidden className="h-32 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
  if (isError) {
    return (
      <p className="text-sm text-destructive">
        Nie udało się policzyć podsumowania.{' '}
        <Button variant="link" className="h-auto p-0" onClick={() => refetch()}>
          Spróbuj ponownie
        </Button>
      </p>
    )
  }

  const rows = onlyDifferences ? data.rows.filter((row) => row.status !== 'OK') : data.rows
  const entered = data.rows.some((row) => row.wzQuantity !== 0)

  const link = (row: BakeryComparisonRow) =>
    setTarget({
      row: {
        ...emptyRow(),
        ean: row.linkCode ?? row.name ?? '',
        invoiceName: row.name,
        name: row.name ?? '',
        unitNet: row.price,
        invoiceUnit: row.invoiceUnit,
      },
      sellerNip: data.sellerNip,
      sellerName: data.sellerName,
    })

  const refresh = () => {
    setTarget(null)
    void queryClient.invalidateQueries({ queryKey: ['stores', storeId, 'bakery-checks', 'check', checkId] })
  }

  return (
    <div className="grid gap-4">
      <Notices data={data} entered={entered} />

      <dl className="grid grid-cols-2 gap-x-6 gap-y-3 rounded-xl border bg-card px-4 py-3 lg:grid-cols-4">
        <Total label="Suma WZ w cenach z faktury" value={data.totals.wzValue} />
        <Total label="Faktura z korektami" value={data.totals.finalValue} note={correctionNote(data)} />
        <Total label="Bon po korektach" value={data.hasBon ? data.totals.bonValue : null} />
        <Total
          label="Różnica WZ i faktury"
          value={data.totals.differenceValue}
          note={`pozycji do sprawdzenia: ${data.totals.differences}`}
          alert={data.totals.differences > 0}
        />
      </dl>

      {data.rows.length > 0 && (
        <>
          <div className="flex justify-end">
            <Button
              variant={onlyDifferences ? 'secondary' : 'outline'}
              size="sm"
              onClick={() => setOnlyDifferences(!onlyDifferences)}
            >
              Tylko różnice
            </Button>
          </div>
          <div className="overflow-x-auto rounded-xl border bg-card">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Artykuł</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead className="text-right">WZ</TableHead>
                  <TableHead className="text-right">Faktura</TableHead>
                  <TableHead className="text-right">Korekty</TableHead>
                  <TableHead className="text-right">Po korektach</TableHead>
                  <TableHead className="text-right">Bon</TableHead>
                  <TableHead className="text-right">Różnica</TableHead>
                  <TableHead className="text-right">Cena netto</TableHead>
                  <TableHead className="text-right">Różnica zł</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((row) => (
                  <TableRow key={row.key}>
                    <TableCell className="max-w-72">
                      <span className="flex items-center gap-1">
                        <span className="min-w-0 truncate font-medium" title={row.name ?? undefined}>
                          {row.name}
                        </span>
                        {row.itm8 && <ArticleStatsButton article={{ itm8: row.itm8, name: row.name, ean: row.ean }} />}
                      </span>
                      <span className="block truncate text-xs text-muted-foreground tabular-nums">
                        {row.ean ?? row.linkCode}
                      </span>
                    </TableCell>
                    <TableCell>
                      {row.status === 'NO_ARTICLE' ? (
                        <Button variant="outline" size="sm" onClick={() => link(row)}>
                          <Link2Icon />
                          Przypisz do artykułu
                        </Button>
                      ) : (
                        <span
                          className={cn(
                            'inline-block rounded-md px-2 py-0.5 text-xs font-medium whitespace-nowrap',
                            STATUS[row.status].className,
                          )}
                        >
                          {STATUS[row.status].label}
                        </span>
                      )}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{quantity(row.wzQuantity)}</TableCell>
                    <TableCell className="text-right tabular-nums">{quantity(row.invoiceQuantity)}</TableCell>
                    <TableCell className="text-right tabular-nums">
                      {row.correctionQuantity ? signed(row.correctionQuantity) : ''}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{quantity(row.finalQuantity)}</TableCell>
                    <TableCell className="text-right tabular-nums">{quantity(row.bonQuantity)}</TableCell>
                    <TableCell className="text-right font-medium tabular-nums">
                      {row.difference ? signed(row.difference) : ''}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{formatPrice(row.price)}</TableCell>
                    <TableCell className="text-right font-medium tabular-nums">
                      {row.differenceValue ? formatMoney(row.differenceValue) : ''}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
              <TableFooter>
                <TableRow>
                  <TableCell colSpan={9} className="font-medium">
                    Razem różnica
                  </TableCell>
                  <TableCell className="text-right font-semibold tabular-nums">
                    {formatMoney(data.totals.differenceValue)}
                  </TableCell>
                </TableRow>
              </TableFooter>
            </Table>
          </div>
        </>
      )}

      <LinkArticleDialog
        storeId={storeId}
        target={target}
        onClose={() => setTarget(null)}
        onLinked={refresh}
        onUnlinked={refresh}
        inTable={new Set()}
        lastSaleDate={null}
      />
    </div>
  )
}

const correctionNote = (data: BakeryComparison) =>
  data.totals.correctionValue !== 0
    ? `faktura ${formatMoney(data.totals.invoiceValue)}, korekty ${formatMoney(data.totals.correctionValue)}`
    : undefined

function Total({ label, value, note, alert }: { label: string; value: number | null; note?: string; alert?: boolean }) {
  return (
    <div className="min-w-0">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className={cn('text-lg font-semibold tabular-nums', alert && 'text-destructive')}>
        {value === null ? '–' : `${formatMoney(value)} zł`}
      </dd>
      {note && <dd className="truncate text-xs text-muted-foreground tabular-nums">{note}</dd>}
    </div>
  )
}

function Notices({ data, entered }: { data: BakeryComparison; entered: boolean }) {
  const notices = [
    !entered && 'Wpisz ilości z dokumentów WZ, a podsumowanie pokaże różnice.',
    data.pendingCorrections > 0 &&
      `Korekty tego dostawcy bez odczytanego XML: ${data.pendingCorrections}. Mogą dotyczyć tej faktury, odczytają się same w ciągu kilkunastu minut.`,
    ...data.unavailable.map((item) => `Faktura ${item.invoiceNumber} nie weszła do podsumowania: ${item.reason}`),
  ].filter((notice) => notice !== false)
  if (notices.length === 0) return null
  return (
    <ul className="grid gap-1 rounded-xl bg-amber-200 px-4 py-3 text-sm text-amber-950 dark:bg-amber-900 dark:text-amber-50">
      {notices.map((notice) => (
        <li key={notice}>{notice}</li>
      ))}
    </ul>
  )
}
