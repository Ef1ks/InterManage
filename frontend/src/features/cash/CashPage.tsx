import { useMemo, useState, type ReactNode } from 'react'
import {
  BanIcon,
  EllipsisIcon,
  FileTextIcon,
  LockIcon,
  MinusIcon,
  PlusIcon,
  PrinterIcon,
  SettingsIcon,
  StampIcon,
  Undo2Icon,
} from 'lucide-react'
import { Link, NavLink, Navigate, Route, Routes, useParams, useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import { DateField } from '@/components/form'
import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { TableSkeletonRows } from '@/components/TableSkeleton'
import { Button } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { Input } from '@/components/ui/input'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCan } from '@/features/auth/api'
import {
  cashPaths,
  closingPdfPath,
  documentPdfPath,
  reportPdfPath,
  useCashClosing,
  useCashClosings,
  useCashHistory,
  useCashReport,
  useCashSettings,
  useRestoreCashDocument,
} from '@/features/cash/api'
import { CancelDocumentDialog } from '@/features/cash/CancelDocumentDialog'
import { CashDocumentDialog } from '@/features/cash/CashDocumentDialog'
import { CashSettingsDialog } from '@/features/cash/CashSettingsDialog'
import { formatDateTime, formatMoney, KIND_LABELS, KIND_STYLES } from '@/features/cash/format'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { ApiError, downloadFile, printFile } from '@/lib/api'
import { addDays, formatShortDate, todayIso } from '@/lib/dates'
import type { CashDocument, CashDocumentKind, CashSettings, StoreSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

/**
 * Raport kasowy sklepu: każdy wpływ to KP, każda wypłata KW, każdy dokument z wydrukiem z pieczątką sklepu.
 * Zakładki: raport dnia (na bieżąco), zakończone raporty (o 23:00 zapisują się same, RK 5/11/2026) i wszystkie
 * dokumenty.
 */
export function CashPage() {
  const store = useCurrentStore()
  if (!store) {
    return (
      <>
        <PageHeader title="Raport kasowy" />
        <FullPageMessage title="Brak sklepu" description="Raport kasowy prowadzi każdy sklep osobno." inline />
      </>
    )
  }
  return <StoreCash key={store.id} store={store} />
}

/** Najwcześniejsza data nowego dokumentu: dzień po zakończonym raporcie i nie przed stanem początkowym. */
function firstOpenDay(settings: CashSettings | undefined): string | undefined {
  const afterClosing = settings?.lastClosedDate ? addDays(settings.lastClosedDate, 1) : undefined
  const opening = settings?.openingBalance?.date
  if (!afterClosing) return opening
  if (!opening) return afterClosing
  return afterClosing > opening ? afterClosing : opening
}

function StoreCash({ store }: { store: StoreSummary }) {
  const canEdit = useCan('CASH_REPORT', 'EDIT')
  const { data: settings } = useCashSettings(store.id)
  const [newKind, setNewKind] = useState<CashDocumentKind | null>(null)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [cancelling, setCancelling] = useState<CashDocument | null>(null)
  const missingStamp = settings !== undefined && settings.stamp === null
  const minDate = firstOpenDay(settings)
  const todayClosed = minDate !== undefined && minDate > todayIso()

  const actions = (
    <div className="flex flex-wrap gap-2">
      <Button size="lg" onClick={() => setNewKind('KP')} disabled={missingStamp || todayClosed}>
        <PlusIcon />
        Nowy KP
      </Button>
      <Button size="lg" variant="outline" onClick={() => setNewKind('KW')} disabled={missingStamp || todayClosed}>
        <MinusIcon />
        Nowy KW
      </Button>
      {canEdit && (
        <Button size="lg" variant="ghost" aria-label="Ustawienia kasy" onClick={() => setSettingsOpen(true)}>
          <SettingsIcon />
        </Button>
      )}
    </div>
  )

  return (
    <>
      <PageHeader title="Raport kasowy" description={storeLabel(store)} actions={actions} />

      {missingStamp && (
        <div
          role="status"
          className="mb-5 flex flex-wrap items-center justify-between gap-3 rounded-xl bg-amber-200 px-4 py-3 text-sm text-amber-950 dark:bg-amber-900/60 dark:text-amber-100"
        >
          <span className="flex items-center gap-2">
            <StampIcon className="size-4 shrink-0" aria-hidden />
            Wgraj skan pieczątki sklepu: bez niej nie da się wystawić KP ani KW.
            {!canEdit && ' Poproś osobę, która zarządza kasą.'}
          </span>
          {canEdit && (
            <Button size="sm" variant="outline" onClick={() => setSettingsOpen(true)}>
              Wgraj pieczątkę
            </Button>
          )}
        </div>
      )}
      {todayClosed && (
        <div role="status" className="mb-5 flex items-center gap-2 rounded-xl bg-muted px-4 py-3 text-sm">
          <LockIcon className="size-4 shrink-0" aria-hidden />
          Dzisiejszy raport jest już zakończony. Kolejne dokumenty wystawisz jutro.
        </div>
      )}

      <nav aria-label="Raport kasowy" className="mb-6 flex gap-1 overflow-x-auto rounded-xl bg-muted p-1 sm:w-fit">
        <SubNavLink to={cashPaths.report} end>
          Raport dnia
        </SubNavLink>
        <SubNavLink to={cashPaths.closings}>Zakończone raporty</SubNavLink>
        <SubNavLink to={cashPaths.history}>Dokumenty</SubNavLink>
      </nav>

      <Routes>
        <Route index element={<ReportView store={store} onCancel={setCancelling} />} />
        <Route path="zakonczone" element={<ClosingsView store={store} />} />
        <Route path="zakonczone/:closingId" element={<ClosingView store={store} />} />
        <Route path="dokumenty" element={<HistoryView store={store} onCancel={setCancelling} />} />
        <Route path="*" element={<Navigate to={cashPaths.report} replace />} />
      </Routes>

      <CashDocumentDialog storeId={store.id} kind={newKind} minDate={minDate} onClose={() => setNewKind(null)} />
      <CancelDocumentDialog storeId={store.id} document={cancelling} onClose={() => setCancelling(null)} />
      <CashSettingsDialog
        storeId={store.id}
        settings={settings}
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
      />
    </>
  )
}

// ---------------------------------------------------------------- raport dnia (na bieżąco)

function ReportView({ store, onCancel }: { store: StoreSummary; onCancel: (document: CashDocument) => void }) {
  const [params, setParams] = useSearchParams()
  const today = todayIso()
  const from = params.get('od') ?? today
  const to = params.get('do') ?? today
  const setPeriod = (nextFrom: string, nextTo: string) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        if (nextFrom === today && nextTo === today) {
          next.delete('od')
          next.delete('do')
        } else {
          next.set('od', nextFrom)
          next.set('do', nextTo)
        }
        return next
      },
      { replace: true },
    )
  const { data: report, isPending, isError, refetch } = useCashReport(store.id, from, to)
  const { data: closings } = useCashClosings(store.id)
  const closing = from === to ? closings?.find((item) => item.reportDate === from) : undefined
  const singleDay = from === to

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="grid w-full gap-2 sm:flex sm:w-auto sm:items-end">
          <DateField
            label="Od"
            value={from}
            onChange={(value) => setPeriod(value, to < value ? value : to)}
            max={today}
            shortcuts={[]}
            className="sm:w-56"
          />
          <DateField
            label="Do"
            value={to}
            onChange={(value) => setPeriod(from > value ? value : from, value)}
            max={today}
            shortcuts={[]}
            className="sm:w-56"
          />
          <div className="flex gap-1">
            <Button variant="ghost" onClick={() => setPeriod(today, today)}>
              Dzisiaj
            </Button>
            <Button variant="ghost" onClick={() => setPeriod(addDays(today, -1), addDays(today, -1))}>
              Wczoraj
            </Button>
          </div>
        </div>
        <PrintMenu
          label={closing ? `Drukuj ${closing.number}` : 'Drukuj raport'}
          path={closing ? closingPdfPath(store.id, closing.id) : reportPdfPath(store.id, from, to)}
          fileName={`raport-kasowy-${from}.pdf`}
          disabled={!report}
        />
      </div>

      {singleDay && (
        <p className="flex items-center gap-2 text-sm text-muted-foreground">
          {closing ? (
            <>
              <LockIcon className="size-4" aria-hidden />
              Raport zakończony:{' '}
              <Link
                to={cashPaths.closing(closing.id)}
                className="font-medium text-foreground underline-offset-4 hover:underline"
              >
                {closing.number}
              </Link>
              {' · '}
              {formatDateTime(closing.closedAt)}
            </>
          ) : (
            'Raport w toku: zakończy się sam o 23:00 i trafi do zakończonych raportów.'
          )}
        </p>
      )}

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Tile label="Stan poprzedni" value={report?.openingBalance} />
        <Tile label="Przychód (KP)" value={report?.receipts} tone="text-emerald-700 dark:text-emerald-400" />
        <Tile label="Rozchód (KW)" value={report?.payments} tone="text-amber-700 dark:text-amber-400" />
        <Tile label="Stan obecny" value={report?.closingBalance} strong />
      </div>

      <div className="overflow-x-auto rounded-xl border bg-card">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="w-12 text-right">Poz.</TableHead>
              <TableHead className="w-28">Data</TableHead>
              <TableHead className="w-40">Dowód</TableHead>
              <TableHead>Treść</TableHead>
              <TableHead className="text-right">Przychód</TableHead>
              <TableHead className="text-right">Rozchód</TableHead>
              <TableHead className="text-right">Stan kasy</TableHead>
              <TableHead className="w-24">
                <span className="sr-only">Działania</span>
              </TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isPending && <TableSkeletonRows columns={8} />}
            {isError && (
              <TableRow>
                <TableCell colSpan={8} className="py-8 text-center text-sm text-muted-foreground">
                  Nie udało się wczytać raportu.{' '}
                  <Button variant="link" className="h-auto p-0" onClick={() => refetch()}>
                    Spróbuj ponownie
                  </Button>
                </TableCell>
              </TableRow>
            )}
            {report?.rows.length === 0 && (
              <TableRow>
                <TableCell colSpan={8} className="py-10 text-center text-sm text-muted-foreground">
                  {singleDay ? 'Tego dnia nie wystawiono dokumentów.' : 'W tym okresie nie wystawiono dokumentów.'}
                </TableCell>
              </TableRow>
            )}
            {report?.rows.map(({ document, balance }, index) => {
              const cancelled = document.cancelledAt !== null
              return (
                <TableRow key={document.id} className={cn(cancelled && 'text-muted-foreground')}>
                  <TableCell className="text-right tabular-nums">{index + 1}</TableCell>
                  <TableCell className="tabular-nums">{formatShortDate(document.issueDate)}</TableCell>
                  <TableCell>
                    <DocumentNumber kind={document.kind} number={document.number} />
                  </TableCell>
                  <TableCell className="max-w-md">
                    <Content content={document.content} counterparty={document.counterparty} cancelled={cancelled} />
                    {cancelled && <CancelledNote document={document} />}
                  </TableCell>
                  <TableCell className={cn('text-right tabular-nums', cancelled && 'line-through')}>
                    {document.kind === 'KP' ? formatMoney(document.amount) : ''}
                  </TableCell>
                  <TableCell className={cn('text-right tabular-nums', cancelled && 'line-through')}>
                    {document.kind === 'KW' ? formatMoney(document.amount) : ''}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">
                    {balance === null ? '–' : formatMoney(balance)}
                  </TableCell>
                  <TableCell>
                    <DocumentActions storeId={store.id} document={document} onCancel={onCancel} />
                  </TableCell>
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------- zakończone raporty

function ClosingsView({ store }: { store: StoreSummary }) {
  const { data: closings, isPending, isError, refetch } = useCashClosings(store.id)

  return (
    <div className="overflow-x-auto rounded-xl border bg-card">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="w-36">Raport</TableHead>
            <TableHead className="w-28">Dzień</TableHead>
            <TableHead className="text-right">Stan poprzedni</TableHead>
            <TableHead className="text-right">Przychód</TableHead>
            <TableHead className="text-right">Rozchód</TableHead>
            <TableHead className="text-right">Stan obecny</TableHead>
            <TableHead className="text-center">KP / KW</TableHead>
            <TableHead>Zakończony</TableHead>
            <TableHead className="w-12">
              <span className="sr-only">Drukuj</span>
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {isPending && <TableSkeletonRows columns={9} />}
          {isError && (
            <TableRow>
              <TableCell colSpan={9} className="py-8 text-center text-sm text-muted-foreground">
                Nie udało się wczytać raportów.{' '}
                <Button variant="link" className="h-auto p-0" onClick={() => refetch()}>
                  Spróbuj ponownie
                </Button>
              </TableCell>
            </TableRow>
          )}
          {closings?.length === 0 && (
            <TableRow>
              <TableCell colSpan={9} className="py-10 text-center text-sm text-muted-foreground">
                Nie ma jeszcze zakończonych raportów. Raport dnia z dokumentami kończy się sam o 23:00.
              </TableCell>
            </TableRow>
          )}
          {closings?.map((closing) => (
            <TableRow key={closing.id} className="relative hover:bg-muted/50">
              <TableCell className="font-medium whitespace-nowrap">
                <Link
                  to={cashPaths.closing(closing.id)}
                  className="outline-none after:absolute after:inset-0 focus-visible:underline"
                >
                  {closing.number}
                </Link>
              </TableCell>
              <TableCell className="tabular-nums">{formatShortDate(closing.reportDate)}</TableCell>
              <TableCell className="text-right tabular-nums">{formatMoney(closing.openingBalance)}</TableCell>
              <TableCell className="text-right tabular-nums">{formatMoney(closing.receipts)}</TableCell>
              <TableCell className="text-right tabular-nums">{formatMoney(closing.payments)}</TableCell>
              <TableCell className="text-right font-semibold tabular-nums">
                {formatMoney(closing.closingBalance)}
              </TableCell>
              <TableCell className="text-center tabular-nums">
                {closing.receiptCount} / {closing.paymentCount}
              </TableCell>
              <TableCell className="text-sm text-muted-foreground">
                {closing.closedBy ?? 'automatycznie'}, {formatDateTime(closing.closedAt)}
              </TableCell>
              <TableCell className="relative z-10">
                <PrintButton path={closingPdfPath(store.id, closing.id)} label={`Drukuj ${closing.number}`} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

function ClosingView({ store }: { store: StoreSummary }) {
  const { closingId } = useParams()
  const { data: closing, isPending, isError } = useCashClosing(store.id, closingId)

  if (isError) {
    return <FullPageMessage title="Nie znaleziono raportu" description="Raport mógł należeć do innego sklepu." inline />
  }
  const summary = closing?.summary
  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <BackLink to={cashPaths.closings}>Zakończone raporty</BackLink>
          <h2 className="mt-1 text-xl font-semibold">
            {summary ? `${summary.number} · ${formatShortDate(summary.reportDate)}` : 'Raport kasowy'}
          </h2>
          {summary && (
            <p className="text-sm text-muted-foreground">
              Zakończony {summary.closedBy ?? 'automatycznie'}, {formatDateTime(summary.closedAt)}. Zrzut z tej chwili:
              nie zmieni się.
            </p>
          )}
        </div>
        {summary && (
          <PrintMenu
            label="Drukuj raport"
            path={closingPdfPath(store.id, summary.id)}
            fileName={`${summary.number.replace(/[ /]/g, '-')}.pdf`}
          />
        )}
      </div>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Tile label="Stan poprzedni" value={summary?.openingBalance} />
        <Tile label="Przychód (KP)" value={summary?.receipts} tone="text-emerald-700 dark:text-emerald-400" />
        <Tile label="Rozchód (KW)" value={summary?.payments} tone="text-amber-700 dark:text-amber-400" />
        <Tile label="Stan obecny" value={summary?.closingBalance} strong />
      </div>

      <div className="overflow-x-auto rounded-xl border bg-card">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="w-12 text-right">Poz.</TableHead>
              <TableHead className="w-40">Dowód</TableHead>
              <TableHead>Treść</TableHead>
              <TableHead className="text-right">Przychód</TableHead>
              <TableHead className="text-right">Rozchód</TableHead>
              <TableHead className="text-right">Stan kasy</TableHead>
              <TableHead className="w-12">
                <span className="sr-only">Drukuj</span>
              </TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isPending && <TableSkeletonRows columns={7} />}
            {closing?.rows.map((row) => (
              <TableRow key={row.documentId} className={cn(row.cancelled && 'text-muted-foreground')}>
                <TableCell className="text-right tabular-nums">{row.position}</TableCell>
                <TableCell>
                  <DocumentNumber kind={row.kind} number={row.number} />
                </TableCell>
                <TableCell className="max-w-md">
                  <Content content={row.content} counterparty={row.counterparty} cancelled={row.cancelled} />
                  {row.cancelled && (
                    <div className="mt-0.5 text-xs font-medium text-red-700 dark:text-red-400">Anulowany</div>
                  )}
                </TableCell>
                <TableCell className={cn('text-right tabular-nums', row.cancelled && 'line-through')}>
                  {row.kind === 'KP' ? formatMoney(row.amount) : ''}
                </TableCell>
                <TableCell className={cn('text-right tabular-nums', row.cancelled && 'line-through')}>
                  {row.kind === 'KW' ? formatMoney(row.amount) : ''}
                </TableCell>
                <TableCell className="text-right tabular-nums">
                  {row.balance === null ? '–' : formatMoney(row.balance)}
                </TableCell>
                <TableCell>
                  <PrintButton path={documentPdfPath(store.id, row.documentId)} label={`Drukuj ${row.number}`} />
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------- wszystkie dokumenty

type HistoryFilter = 'all' | CashDocumentKind | 'cancelled'

function HistoryView({ store, onCancel }: { store: StoreSummary; onCancel: (document: CashDocument) => void }) {
  const { data: documents, isPending, isError, refetch } = useCashHistory(store.id)
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState<HistoryFilter>('all')

  const visible = useMemo(() => {
    const words = query.toLowerCase().split(/\s+/).filter(Boolean)
    return (documents ?? []).filter((document) => {
      if (filter === 'cancelled' ? document.cancelledAt === null : filter !== 'all' && document.kind !== filter) {
        return false
      }
      const text =
        `${document.number} ${document.content} ${document.counterparty ?? ''} ${document.createdBy ?? ''}`.toLowerCase()
      return words.every((word) => text.includes(word))
    })
  }, [documents, query, filter])

  const filters: { value: HistoryFilter; label: string }[] = [
    { value: 'all', label: 'Wszystkie' },
    { value: 'KP', label: 'KP' },
    { value: 'KW', label: 'KW' },
    { value: 'cancelled', label: 'Anulowane' },
  ]

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <Input
          type="search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Szukaj: numer, treść, kontrahent, kto wystawił"
          className="sm:max-w-sm"
          aria-label="Szukaj dokumentów"
        />
        <div className="inline-flex rounded-lg bg-muted p-0.5" role="group" aria-label="Rodzaj dokumentów">
          {filters.map((option) => (
            <button
              key={option.value}
              type="button"
              aria-pressed={filter === option.value}
              onClick={() => setFilter(option.value)}
              className={cn(
                'rounded-md px-3 py-1.5 text-sm text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/40',
                filter === option.value &&
                  'bg-background font-medium text-foreground shadow-[0_1px_2px_rgb(0_0_0/0.08)]',
              )}
            >
              {option.label}
            </button>
          ))}
        </div>
        {documents && (
          <span className="text-sm text-muted-foreground tabular-nums">
            {visible.length} z {documents.length}
          </span>
        )}
      </div>

      <div className="overflow-x-auto rounded-xl border bg-card">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="w-40">Dowód</TableHead>
              <TableHead className="w-28">Data</TableHead>
              <TableHead>Treść</TableHead>
              <TableHead className="text-right">Kwota</TableHead>
              <TableHead>Wystawił</TableHead>
              <TableHead className="w-24">
                <span className="sr-only">Działania</span>
              </TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isPending && <TableSkeletonRows columns={6} />}
            {isError && (
              <TableRow>
                <TableCell colSpan={6} className="py-8 text-center text-sm text-muted-foreground">
                  Nie udało się wczytać dokumentów.{' '}
                  <Button variant="link" className="h-auto p-0" onClick={() => refetch()}>
                    Spróbuj ponownie
                  </Button>
                </TableCell>
              </TableRow>
            )}
            {documents && visible.length === 0 && (
              <TableRow>
                <TableCell colSpan={6} className="py-10 text-center text-sm text-muted-foreground">
                  {documents.length === 0
                    ? 'Nie wystawiono jeszcze żadnego dokumentu.'
                    : 'Brak dokumentów dla tego filtra.'}
                </TableCell>
              </TableRow>
            )}
            {visible.map((document) => {
              const cancelled = document.cancelledAt !== null
              return (
                <TableRow key={document.id} className={cn(cancelled && 'text-muted-foreground')}>
                  <TableCell>
                    <DocumentNumber kind={document.kind} number={document.number} />
                  </TableCell>
                  <TableCell className="tabular-nums">{formatShortDate(document.issueDate)}</TableCell>
                  <TableCell className="max-w-md">
                    <Content content={document.content} counterparty={document.counterparty} cancelled={cancelled} />
                    {cancelled && <CancelledNote document={document} />}
                  </TableCell>
                  <TableCell className={cn('text-right tabular-nums', cancelled && 'line-through')}>
                    {document.kind === 'KW' ? '−' : ''}
                    {formatMoney(document.amount)}
                  </TableCell>
                  <TableCell className="text-sm">
                    <div>{document.createdBy ?? '–'}</div>
                    <div className="text-xs text-muted-foreground tabular-nums">
                      {formatDateTime(document.createdAt)}
                    </div>
                  </TableCell>
                  <TableCell>
                    <DocumentActions storeId={store.id} document={document} onCancel={onCancel} />
                  </TableCell>
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------- wspólne

function Tile({
  label,
  value,
  tone,
  strong,
}: {
  label: string
  value: number | undefined
  tone?: string
  strong?: boolean
}) {
  return (
    <div className={cn('rounded-xl border bg-card px-4 py-3', strong && 'border-foreground/20')}>
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className={cn('mt-1 text-2xl font-semibold tabular-nums', tone)}>
        {value === undefined ? <span className="text-muted-foreground/50">…</span> : formatMoney(value)}
      </div>
    </div>
  )
}

function DocumentNumber({ kind, number }: { kind: CashDocumentKind; number: string }) {
  return (
    <span className="flex items-center gap-2 whitespace-nowrap">
      <span title={KIND_LABELS[kind]} className={cn('rounded px-1.5 py-0.5 text-xs font-semibold', KIND_STYLES[kind])}>
        {kind}
      </span>
      <span className="font-medium tabular-nums">{number.slice(3)}</span>
    </span>
  )
}

function Content({
  content,
  counterparty,
  cancelled,
}: {
  content: string
  counterparty: string | null
  cancelled: boolean
}) {
  return (
    <>
      <div className={cn('truncate', cancelled && 'line-through')}>{content}</div>
      {counterparty && <div className="truncate text-xs text-muted-foreground">{counterparty}</div>}
    </>
  )
}

function CancelledNote({ document }: { document: CashDocument }) {
  return (
    <div className="mt-0.5 text-xs font-medium text-red-700 dark:text-red-400">
      Anulowany{document.cancelReason ? `: ${document.cancelReason}` : ''}
      {document.cancelledBy ? ` · ${document.cancelledBy}` : ''}
      {document.cancelledAt ? `, ${formatDateTime(document.cancelledAt)}` : ''}
    </div>
  )
}

function usePrintAction() {
  const [pending, setPending] = useState(false)
  const run = async (action: () => Promise<void>) => {
    setPending(true)
    try {
      await action()
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się przygotować wydruku')
    } finally {
      setPending(false)
    }
  }
  return { pending, run }
}

function PrintButton({ path, label }: { path: string; label: string }) {
  const { pending, run } = usePrintAction()
  return (
    <Button
      size="icon-sm"
      variant="ghost"
      aria-label={label}
      title="Drukuj"
      loading={pending}
      onClick={() => run(() => printFile(path))}
    >
      <PrinterIcon />
    </Button>
  )
}

/** Drukuj od razu, a w menu PDF, anulowanie i przywrócenie (z uprawnieniem do zmian). */
function DocumentActions({
  storeId,
  document,
  onCancel,
}: {
  storeId: string
  document: CashDocument
  onCancel: (document: CashDocument) => void
}) {
  const canEdit = useCan('CASH_REPORT', 'EDIT')
  const restore = useRestoreCashDocument(storeId)
  const { pending, run } = usePrintAction()
  const path = documentPdfPath(storeId, document.id)
  const fileName = `${document.number.replace(/[ /]/g, '-')}.pdf`

  return (
    <div className="flex justify-end gap-1">
      <Button
        size="icon-sm"
        variant="ghost"
        aria-label={`Drukuj ${document.number}`}
        title="Drukuj"
        loading={pending}
        onClick={() => run(() => printFile(path))}
      >
        <PrinterIcon />
      </Button>
      <DropdownMenu>
        <DropdownMenuTrigger
          render={<Button size="icon-sm" variant="ghost" aria-label={`Więcej: ${document.number}`} />}
        >
          <EllipsisIcon />
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem onClick={() => run(() => downloadFile(path, fileName))}>
            <FileTextIcon />
            Pobierz PDF
          </DropdownMenuItem>
          {canEdit && document.cancelledAt === null && (
            <DropdownMenuItem variant="destructive" onClick={() => onCancel(document)}>
              <BanIcon />
              Anuluj dokument
            </DropdownMenuItem>
          )}
          {canEdit && document.cancelledAt !== null && (
            <DropdownMenuItem
              onClick={() =>
                restore.mutate(document.id, {
                  onSuccess: () => toast.success(`Przywrócono ${document.number}`),
                  onError: (error) =>
                    toast.error(error instanceof ApiError ? error.message : 'Nie udało się przywrócić dokumentu'),
                })
              }
            >
              <Undo2Icon />
              Cofnij anulowanie
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  )
}

function PrintMenu({
  label,
  path,
  fileName,
  disabled,
}: {
  label: string
  path: string
  fileName: string
  disabled?: boolean
}) {
  const { pending, run } = usePrintAction()
  return (
    <DropdownMenu>
      <DropdownMenuTrigger render={<Button variant="outline" loading={pending} disabled={disabled} />}>
        <PrinterIcon />
        {label}
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-48">
        <DropdownMenuItem onClick={() => run(() => printFile(path))}>
          <PrinterIcon />
          Drukuj
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => run(() => downloadFile(path, fileName))}>
          <FileTextIcon />
          Pobierz PDF
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

function SubNavLink({ to, end, children }: { to: string; end?: boolean; children: ReactNode }) {
  return (
    <NavLink
      to={to}
      end={end}
      className={({ isActive }) =>
        cn(
          'flex shrink-0 items-center gap-2 rounded-lg px-3.5 py-1.5 text-sm font-medium whitespace-nowrap text-muted-foreground transition-[background-color,color,box-shadow] duration-150 outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50',
          isActive && 'bg-background text-foreground shadow-[0_1px_2px_rgb(0_0_0/0.08)]',
        )
      }
    >
      {children}
    </NavLink>
  )
}
