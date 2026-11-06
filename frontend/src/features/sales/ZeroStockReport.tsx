import { useMemo, useState } from 'react'
import { ChevronRightIcon, DownloadIcon, PrinterIcon, ShoppingCartIcon, TriangleAlertIcon } from 'lucide-react'
import { Link, useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import { ReportFreshness } from '@/components/ReportFreshness'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Switch } from '@/components/ui/switch'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatQuantity } from '@/features/documents/format'
import { useSalesNomenclatures, useZeroStock, useZeroStockPdf } from '@/features/sales/api'
import { salesPaths } from '@/features/sales/paths'
import { nomenclatureName } from '@/features/sales/report-parts'
import { ZeroStockOrderDialog } from '@/features/sales/ZeroStockOrderDialog'
import { markOf, type NomenclatureTree, nomenclatureTree, withMark } from '@/features/sales/zero-stock-selection'
import { storeSettingsPaths } from '@/features/store-settings/paths'
import { ApiError } from '@/lib/api'
import type { NomenclatureOption, ZeroStockReport as Report } from '@/lib/types'
import { cn } from '@/lib/utils'

const SUMMARY_LIMIT = 6

/**
 * Raport zerówek: artykuły aktywne w cadencier ze stanem 0. Najpierw wybór nomenklatury (przełącznik przy rejonie,
 * ptaszki przy rodzinach i podrodzinach), po potwierdzeniu lista, jej wydruk z kodami kreskowymi i plik zamówienia.
 * Potwierdzony wybór jest w adresie ({@code ?nom=}), szkic wyboru tylko na ekranie.
 */
export function ZeroStockReport({ storeId }: { storeId: string }) {
  const [params, setParams] = useSearchParams()
  const confirmed = params.getAll('nom')
  const [draft, setDraft] = useState(confirmed)
  const [picking, setPicking] = useState(confirmed.length === 0)
  const report = useZeroStock(storeId, confirmed)

  const confirm = () => {
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        next.delete('nom')
        draft.forEach((code) => next.append('nom', code))
        return next
      },
      { replace: true },
    )
    setPicking(false)
  }

  return (
    <>
      <BackLink to={salesPaths.overview}>Wszystkie raporty</BackLink>
      <h2 className="mb-1 text-xl font-semibold tracking-tight">Raport zerówek</h2>
      <p className="mb-4 max-w-3xl text-sm text-muted-foreground">
        Artykuły aktywne w cadencier, których stan w kartotece to 0, w wybranych rejonach, rodzinach i podrodzinach.
      </p>

      {picking ? (
        <NomenclaturePicker storeId={storeId} value={draft} onChange={setDraft} onConfirm={confirm} />
      ) : (
        <SelectionSummary report={report.data} count={confirmed.length} onChange={() => setPicking(true)} />
      )}

      {confirmed.length > 0 &&
        (report.isError ? (
          <p className="text-sm text-destructive">Nie udało się wczytać raportu.</p>
        ) : !report.data ? (
          <div aria-hidden className="h-72 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
        ) : (
          <div className={cn('transition-opacity duration-150', report.isPlaceholderData && 'opacity-60')}>
            <Results storeId={storeId} codes={confirmed} report={report.data} />
          </div>
        ))}
    </>
  )
}

function NomenclaturePicker({
  storeId,
  value,
  onChange,
  onConfirm,
}: {
  storeId: string
  value: string[]
  onChange: (value: string[]) => void
  onConfirm: () => void
}) {
  const nomenclatures = useSalesNomenclatures(storeId)
  const tree = useMemo(() => nomenclatureTree(nomenclatures.data ?? []), [nomenclatures.data])
  const regions = tree.get('') ?? []
  const toggle = (code: string, on: boolean) => onChange(withMark(tree, value, code, on))

  return (
    <Card className="mb-4 gap-4 p-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-muted-foreground">
          Włącz cały rejon albo rozwiń go i zaznacz rodziny lub podrodziny.
        </p>
        <div className="flex gap-1">
          <Button variant="ghost" size="sm" onClick={() => onChange(regions.map((region) => region.code))}>
            Zaznacz wszystkie
          </Button>
          <Button variant="ghost" size="sm" disabled={value.length === 0} onClick={() => onChange([])}>
            Wyczyść
          </Button>
        </div>
      </div>

      {nomenclatures.isError ? (
        <p className="text-sm text-destructive">Nie udało się wczytać nomenklatury.</p>
      ) : !nomenclatures.data ? (
        <div aria-hidden className="h-48 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
      ) : regions.length === 0 ? (
        <p className="rounded-lg border border-dashed px-4 py-5 text-center text-sm text-muted-foreground">
          Wgraj nomenklaturę w Imporcie danych.
        </p>
      ) : (
        <div className="grid items-start gap-2 sm:grid-cols-2 xl:grid-cols-3">
          {regions.map((region) => (
            <RegionRow key={region.code} region={region} tree={tree} selection={value} onToggle={toggle} />
          ))}
        </div>
      )}

      <div className="flex justify-end">
        <Button size="lg" disabled={value.length === 0} onClick={onConfirm}>
          Pokaż zerówki
        </Button>
      </div>
    </Card>
  )
}

function Expander({ open, label, onClick }: { open: boolean; label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      aria-expanded={open}
      aria-label={`${open ? 'Zwiń' : 'Rozwiń'} ${label}`}
      onClick={onClick}
      className="flex size-6 shrink-0 items-center justify-center rounded-md text-muted-foreground transition-colors duration-150 hover:bg-muted hover:text-foreground"
    >
      <ChevronRightIcon aria-hidden className={cn('size-4 transition-transform duration-150', open && 'rotate-90')} />
    </button>
  )
}

type RowProps = {
  tree: NomenclatureTree
  selection: string[]
  onToggle: (code: string, on: boolean) => void
}

function RegionRow({ region, tree, selection, onToggle }: RowProps & { region: NomenclatureOption }) {
  const [open, setOpen] = useState(false)
  const mark = markOf(selection, region.code)
  const families = tree.get(region.code) ?? []
  const name = nomenclatureName(region.name)

  return (
    <div className="rounded-lg border">
      <div className="flex items-center gap-2 px-2 py-1.5 text-sm">
        <Expander open={open} label={name} onClick={() => setOpen(!open)} />
        <span className="text-muted-foreground tabular-nums">{region.code}</span>
        <span className="min-w-0 flex-1 truncate" title={name}>
          {name}
        </span>
        {mark === 'some' && <span className="text-xs text-muted-foreground">częściowo</span>}
        <Switch
          checked={mark === 'on'}
          onCheckedChange={(on) => onToggle(region.code, on)}
          aria-label={`${region.code} ${name}`}
        />
      </div>
      {open && (
        <ul className="border-t px-2 py-1">
          {families.map((family) => (
            <TickRow key={family.code} option={family} tree={tree} selection={selection} onToggle={onToggle} />
          ))}
        </ul>
      )}
    </div>
  )
}

/** Rodzina albo podrodzina z ptaszkiem; kreska w polu = zaznaczona tylko część pozycji pod spodem. */
function TickRow({ option, tree, selection, onToggle }: RowProps & { option: NomenclatureOption }) {
  const [open, setOpen] = useState(false)
  const mark = markOf(selection, option.code)
  const children = tree.get(option.code) ?? []

  return (
    <li>
      <div className="flex items-center gap-1 text-sm">
        {children.length > 0 ? (
          <Expander open={open} label={option.name} onClick={() => setOpen(!open)} />
        ) : (
          <span aria-hidden className="size-6 shrink-0" />
        )}
        <label className="flex min-w-0 flex-1 cursor-pointer items-center gap-2 rounded-md px-1 py-1 hover:bg-muted">
          <input
            type="checkbox"
            className="size-4 shrink-0 accent-primary"
            checked={mark === 'on'}
            ref={(input) => {
              if (input) input.indeterminate = mark === 'some'
            }}
            onChange={(event) => onToggle(option.code, event.target.checked)}
          />
          <span className="text-muted-foreground tabular-nums">{option.code.slice(-2)}</span>
          <span className="min-w-0 truncate" title={option.name}>
            {option.name}
          </span>
        </label>
      </div>
      {open && (
        <ul className="pl-5">
          {children.map((child) => (
            <TickRow key={child.code} option={child} tree={tree} selection={selection} onToggle={onToggle} />
          ))}
        </ul>
      )}
    </li>
  )
}

function SelectionSummary({
  report,
  count,
  onChange,
}: {
  report: Report | undefined
  count: number
  onChange: () => void
}) {
  const selection = report?.selection ?? []
  const hidden = count - Math.min(selection.length, SUMMARY_LIMIT)
  return (
    <Card className="mb-4 flex-row flex-wrap items-center justify-between gap-3 p-4">
      <ul className="flex min-w-0 flex-wrap gap-1.5" aria-label="Wybrana nomenklatura">
        {selection.slice(0, SUMMARY_LIMIT).map((option) => (
          <li key={option.code} className="rounded-md bg-muted px-2 py-0.5 text-sm">
            <span className="text-muted-foreground tabular-nums">{option.code}</span> {nomenclatureName(option.name)}
          </li>
        ))}
        {hidden > 0 && <li className="px-1 py-0.5 text-sm text-muted-foreground">i jeszcze {hidden}</li>}
      </ul>
      <Button variant="outline" size="lg" onClick={onChange}>
        Zmień wybór
      </Button>
    </Card>
  )
}

function Results({ storeId, codes, report }: { storeId: string; codes: string[]; report: Report }) {
  const pdf = useZeroStockPdf(storeId)
  const [ordering, setOrdering] = useState(false)
  const items = useMemo(() => report.groups.flatMap((group) => group.items), [report.groups])
  const onPdfError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się przygotować wydruku')
  const empty = report.itemCount === 0

  return (
    <>
      {!report.cadencierGeneratedAt && (
        <p className="mb-4 flex items-start gap-2 text-sm text-amber-700 dark:text-amber-400">
          <TriangleAlertIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
          <span>
            Sklep nie ma wgranej listy cadencier, więc raport nie ma z czego się policzyć. Wgrasz ją w{' '}
            <Link to={storeSettingsPaths.priceCheck} className="underline underline-offset-4">
              Ustawieniach sklepu
            </Link>
            .
          </span>
        </p>
      )}

      <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-3">
          <h3 className="font-semibold tabular-nums">Artykułów: {report.itemCount}</h3>
          <ReportFreshness generatedAt={report.stockReportGeneratedAt} />
        </div>
        <div className="flex flex-wrap gap-2">
          <Button
            variant="outline"
            size="lg"
            loading={pdf.download.isPending}
            disabled={empty}
            onClick={() => pdf.download.mutate(codes, { onError: onPdfError })}
          >
            <DownloadIcon />
            Pobierz PDF
          </Button>
          <Button
            variant="outline"
            size="lg"
            loading={pdf.print.isPending}
            disabled={empty}
            onClick={() => pdf.print.mutate(codes, { onError: onPdfError })}
          >
            <PrinterIcon />
            Drukuj
          </Button>
          <Button size="lg" disabled={empty} onClick={() => setOrdering(true)}>
            <ShoppingCartIcon />
            Stwórz zamówienie
          </Button>
        </div>
      </div>

      {empty ? (
        <p className="rounded-lg border border-dashed px-4 py-8 text-center text-sm text-muted-foreground">
          W wybranych nomenklaturach nie ma artykułów z cadencier ze stanem 0.
        </p>
      ) : (
        <Card className="overflow-clip p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>ITM8</TableHead>
                <TableHead>EAN</TableHead>
                <TableHead>Nazwa</TableHead>
                <TableHead>Marka</TableHead>
                <TableHead className="text-right">Stan</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {report.groups.flatMap((group) => [
                <TableRow key={group.code} className="bg-muted hover:bg-muted">
                  <TableCell colSpan={5} className="font-medium">
                    <span className="text-muted-foreground tabular-nums">{group.code}</span> {group.name}
                    <span className="ml-2 text-muted-foreground tabular-nums">{group.items.length}</span>
                  </TableCell>
                </TableRow>,
                ...group.items.map((item) => (
                  <TableRow key={item.itm8}>
                    <TableCell className="tabular-nums">{item.itm8.replace(/^0+/, '')}</TableCell>
                    <TableCell className="tabular-nums">{item.ean}</TableCell>
                    <TableCell>{item.name}</TableCell>
                    <TableCell className="text-muted-foreground">{item.brand}</TableCell>
                    <TableCell className="text-right tabular-nums">{formatQuantity(item.stock ?? 0)}</TableCell>
                  </TableRow>
                )),
              ])}
            </TableBody>
          </Table>
        </Card>
      )}

      <ZeroStockOrderDialog storeId={storeId} items={items} open={ordering} onOpenChange={setOrdering} />
    </>
  )
}
