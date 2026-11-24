import { useState } from 'react'
import { ChevronDownIcon, CircleAlertIcon, RefreshCwIcon } from 'lucide-react'
import { toast } from 'sonner'

import { FileDropZone } from '@/components/FileDropZone'
import { ImportError } from '@/components/ImportError'
import { LoadingPanel } from '@/components/LoadingPanel'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCadTops, useSyncCadTops, useUploadCadPlan } from '@/features/imports/api'
import { ApiError } from '@/lib/api'
import { parseIsoDate } from '@/lib/dates'
import type { CadPlan, CadTopRow } from '@/lib/types'
import { cn } from '@/lib/utils'

const numberFormat = new Intl.NumberFormat('pl-PL', { useGrouping: 'always' })
const dayFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short' })
const dateTimeFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'short' })

const STATUS: Record<CadTopRow['status'], { label: string; className: string }> = {
  CURRENT: {
    label: 'Obowiązuje',
    className: 'bg-emerald-200 text-emerald-950 dark:bg-emerald-900/70 dark:text-emerald-100',
  },
  UPCOMING: { label: 'Wejdzie', className: 'bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-100' },
  PAST: { label: 'Zakończony', className: 'bg-muted text-muted-foreground' },
  NOT_PLANNED: {
    label: 'Spoza planu',
    className: 'bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100',
  },
}

const day = (value: string) => dayFormat.format(parseIsoDate(value))

function period(row: CadTopRow) {
  if (!row.startsOn) return '—'
  return row.endsOn ? `${day(row.startsOn)} – ${day(row.endsOn)}` : `od ${day(row.startsOn)}`
}

function planNote(plan: CadPlan) {
  const version = plan.modifiedAt ? `wersja z ${dayFormat.format(new Date(plan.modifiedAt))}, ` : ''
  const source = plan.fromPortal ? 'pobrany z portalu' : `wgrany${plan.uploadedBy ? ` (${plan.uploadedBy})` : ''}`
  return `Plan ${plan.year}: ${version}${source} ${dayFormat.format(new Date(plan.uploadedAt))}`
}

/**
 * Plan cadencier i pliki TOP rejonu sklepu: oba pobierają się same z portalu, a o tym, który plik obowiązuje, decyduje
 * plan (portal wystawia plik kilka dni przed wejściem). Plan można wgrać ręcznie, gdy portal zmieni jego nazwę.
 */
export function CadTopsCard({ storeId, canImport }: { storeId: string; canImport: boolean }) {
  const overview = useCadTops(storeId)
  const sync = useSyncCadTops(storeId)
  const upload = useUploadCadPlan(storeId)
  const [file, setFile] = useState<File | undefined>()
  const [manual, setManual] = useState(false)
  const data = overview.data

  const checkPortal = () =>
    sync.mutate(undefined, {
      onSuccess: ({ downloadedTops, downloadedPlans }) => {
        const parts = [
          downloadedTops > 0 && `pliki TOP: ${downloadedTops}`,
          downloadedPlans > 0 && `plany: ${downloadedPlans}`,
        ].filter(Boolean)
        toast.success(parts.length > 0 ? `Pobrano ${parts.join(', ')}` : 'Na portalu nie ma nic nowego')
      },
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się sprawdzić portalu'),
    })

  return (
    <Card aria-labelledby="cad-tops" className="mb-10 gap-4 p-5">
      <div className="flex flex-wrap items-start gap-x-4 gap-y-3">
        <div className="grid min-w-0 flex-1 basis-80 gap-1">
          <h2 id="cad-tops" className="text-base font-semibold">
            Cadencier: plan i TOP-y
            {data?.region && (
              <span className="font-normal text-muted-foreground">
                {' '}
                · rejon <span className="capitalize">{data.region}</span>
              </span>
            )}
          </h2>
          <p className="max-w-prose text-sm text-muted-foreground">
            Pliki TOP i plan pobierają się same z portalu. Nowy plik zaczyna obowiązywać w dniu z planu, a nie w dniu
            pobrania.
          </p>
        </div>
        {canImport && data?.configured && (
          <Button variant="outline" onClick={checkPortal} loading={sync.isPending}>
            {!sync.isPending && <RefreshCwIcon data-icon="inline-start" />}
            Sprawdź portal
          </Button>
        )}
      </div>

      {overview.isPending ? (
        <LoadingPanel className="h-40 rounded-lg" />
      ) : !data ? (
        <p className="text-sm text-destructive">
          {overview.error instanceof ApiError ? overview.error.message : 'Nie udało się wczytać planu cadencier.'}
        </p>
      ) : (
        <>
          {data.warnings.length > 0 && (
            <ul className="grid gap-2 rounded-lg bg-amber-200 px-3 py-2.5 text-sm text-amber-950 dark:bg-amber-900/60 dark:text-amber-100">
              {data.warnings.map((warning) => (
                <li key={warning} className="flex gap-2">
                  <CircleAlertIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
                  <span>{warning}</span>
                </li>
              ))}
            </ul>
          )}

          {data.rows.length > 0 && (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Cadencier</TableHead>
                  <TableHead>Stan</TableHead>
                  <TableHead>Okres z planu</TableHead>
                  <TableHead className="text-right">Artykuły</TableHead>
                  <TableHead className="text-right" title="Najbardziej porównywalne cenowo w konkurencji">
                    BTop
                  </TableHead>
                  <TableHead className="text-right" title="Bardzo porównywalne cenowo w konkurencji">
                    B
                  </TableHead>
                  <TableHead className="text-right" title="PNM w indeksie sklepów">
                    A
                  </TableHead>
                  <TableHead className="text-right">Nowości</TableHead>
                  <TableHead className="pl-6">Na portalu od</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.rows.map((row) => (
                  <CadRow key={row.nrCad} row={row} />
                ))}
              </TableBody>
            </Table>
          )}

          {data.plans.length > 0 && (
            <ul className="grid gap-0.5 text-sm text-muted-foreground tabular-nums">
              {data.plans.map((plan) => (
                <li key={plan.year} title={plan.fileName}>
                  {planNote(plan)}
                </li>
              ))}
            </ul>
          )}
        </>
      )}

      {canImport && (
        <div className="grid gap-3">
          <button
            type="button"
            onClick={() => setManual((value) => !value)}
            aria-expanded={manual}
            className="flex items-center gap-1 justify-self-start rounded-md text-sm text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
          >
            <ChevronDownIcon aria-hidden className={cn('size-4', manual && 'rotate-180')} />
            Wgraj plan ręcznie
          </button>
          {manual && (
            <>
              <FileDropZone
                accept={['.xlsx']}
                pendingFile={upload.isPending ? file : undefined}
                title="Przeciągnij plan cadencier albo kliknij"
                hint="Gdy na intranecie wyszła aktualizacja pod inną nazwą. Plan jest wspólny dla wszystkich sklepów."
                className="min-h-28 py-5"
                onReject={(name) => toast.error(`${name}: wgraj plan cadencier .xlsx`)}
                onFile={(chosen) => {
                  setFile(chosen)
                  upload.mutate(chosen, { onSuccess: () => toast.success('Wgrano plan cadencier') })
                }}
              />
              {upload.error && <ImportError error={upload.error} />}
            </>
          )}
        </div>
      )}
    </Card>
  )
}

function CadRow({ row }: { row: CadTopRow }) {
  const status = STATUS[row.status]
  const { file } = row
  return (
    <TableRow className={cn(row.status === 'PAST' && 'text-muted-foreground')}>
      <TableCell className={cn('tabular-nums', row.status === 'CURRENT' ? 'font-semibold' : 'font-medium')}>
        {row.nrCad}
      </TableCell>
      <TableCell>
        <span className={cn('rounded-md px-2 py-0.5 text-xs font-medium whitespace-nowrap', status.className)}>
          {status.label}
        </span>
      </TableCell>
      <TableCell className="whitespace-nowrap tabular-nums">{period(row)}</TableCell>
      {file ? (
        <>
          <TableCell className="text-right tabular-nums">{numberFormat.format(file.articleCount)}</TableCell>
          <TableCell className="text-right tabular-nums">{numberFormat.format(file.btopCount)}</TableCell>
          <TableCell className="text-right tabular-nums">{numberFormat.format(file.bCount)}</TableCell>
          <TableCell className="text-right tabular-nums">{numberFormat.format(file.aCount)}</TableCell>
          <TableCell className="text-right tabular-nums">{numberFormat.format(file.newCount)}</TableCell>
          <TableCell
            className="pl-6 whitespace-nowrap tabular-nums"
            title={`${file.fileName}, pobrany ${dateTimeFormat.format(new Date(file.downloadedAt))}`}
          >
            {publishedAt(file.publishedAt)}
          </TableCell>
        </>
      ) : (
        <TableCell colSpan={6} className="text-muted-foreground">
          {row.status === 'PAST' ? 'bez pliku' : 'portal nie ma jeszcze pliku'}
        </TableCell>
      )}
    </TableRow>
  )
}

/** Portal podaje czas sklepu bez strefy: „2026-11-24 09:29:26”. */
function publishedAt(value: string) {
  const date = new Date(value.replace(' ', 'T'))
  return Number.isNaN(date.getTime()) ? value : dateTimeFormat.format(date)
}
