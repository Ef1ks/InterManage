import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import {
  FileSpreadsheetIcon,
  FileTextIcon,
  LinkIcon,
  PackageXIcon,
  PrinterIcon,
  SparklesIcon,
  Trash2Icon,
  UnlinkIcon,
} from 'lucide-react'
import { toast } from 'sonner'

import { TextareaField } from '@/components/form'
import { FullPageMessage } from '@/components/FullPageMessage'
import { HowTo, HowToButton } from '@/components/HowTo'
import { LoadingPanel } from '@/components/LoadingPanel'
import { PageHeader } from '@/components/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Table, TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { SupplierPicker } from '@/features/documents/SupplierPicker'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { ApiError, downloadFile, printFile } from '@/lib/api'
import type {
  SupplierReturnCandidates,
  SupplierReturnDetails,
  SupplierReturnDocument,
  SupplierReturnStatus,
  SupplierReturnSummary,
} from '@/lib/types'
import { cn } from '@/lib/utils'

import {
  isOpen,
  STATUS_LABELS,
  STATUS_ORDER,
  STATUS_STYLES,
  supplierReturnFile,
  useSupplierReturn,
  useSupplierReturnActions,
  useSupplierReturnCandidates,
  useSupplierReturns,
  type AttachPart,
} from './api'

const HINT = 'supplier-returns'
const dateTime = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'short' })
const date = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short' })
const money = new Intl.NumberFormat('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const quantity = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 3 })

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

type Filter = 'otwarte' | 'wszystkie' | SupplierReturnStatus

/**
 * Zwroty do dostawcy: listy z kolektora (protokół „Zwrot do dostawcy”) albo z Protokołów, prowadzone stanami do
 * rozliczenia, z bonem dostawy, bonem korekty i korektą z KSeF. Korekty aplikacja podpina sama po artykułach.
 */
export function SupplierReturnsPage() {
  const store = useCurrentStore()
  return (
    <div data-wide-page>
      <PageHeader
        title="Zwroty do dostawcy"
        description={store ? storeLabel(store) : undefined}
        actions={<HowToButton id={HINT} />}
      />
      {store ? (
        <Returns key={store.id} storeId={store.id} />
      ) : (
        <FullPageMessage title="Brak sklepu" description="Zwroty są w konkretnym sklepie." inline />
      )}
    </div>
  )
}

function Returns({ storeId }: { storeId: string }) {
  const returns = useSupplierReturns(storeId)
  const [params, setParams] = useSearchParams()
  const filter = (params.get('stan') as Filter | null) ?? 'otwarte'

  if (returns.isPending) return <LoadingPanel className="h-72 rounded-xl" />
  if (returns.isError) {
    return (
      <FullPageMessage
        title="Nie udało się wczytać zwrotów"
        action={{ label: 'Spróbuj ponownie', onClick: () => returns.refetch() }}
        inline
      />
    )
  }
  const visible = returns.data.filter((item) =>
    filter === 'wszystkie' ? true : filter === 'otwarte' ? isOpen(item.status) : item.status === filter,
  )
  const picked = params.get('zwrot')
  const selected = picked && returns.data.some((item) => item.id === picked) ? picked : (visible[0]?.id ?? null)

  const set = (key: string, value: string | null) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        if (value) next.set(key, value)
        else next.delete(key)
        return next
      },
      { replace: true },
    )

  const counts = new Map<Filter, number>()
  counts.set('otwarte', returns.data.filter((item) => isOpen(item.status)).length)
  counts.set('wszystkie', returns.data.length)
  STATUS_ORDER.forEach((status) => counts.set(status, returns.data.filter((item) => item.status === status).length))

  return (
    <div className="grid gap-4">
      <HowTo
        id={HINT}
        title="Zwrot do dostawcy"
        reopenInHeader
        steps={[
          'Zeskanuj towar na kolektorze w „Protokołach” i w Danych z kolektorów wybierz „Zwrot do dostawcy”. Albo wpisz go w Protokołach i wybierz „Zwrot do dostawcy” w „Wytwórz”.',
          'Sprawdź dostawcę (podpowiada go ostatnia dostawa artykułów) i podepnij bon dostawy, z którą towar przyjechał.',
          'Zmieniaj stan, gdy zgłosisz zwrot i gdy dostawca odpowie. Korektę z raportu dokumentów albo z KSeF aplikacja podepnie sama, gdy pojawi się z tymi artykułami.',
        ]}
      />
      <div className="flex flex-wrap gap-1.5">
        {(['otwarte', ...STATUS_ORDER, 'wszystkie'] as Filter[]).map((key) => (
          <Button
            key={key}
            size="sm"
            variant={filter === key ? 'default' : 'outline'}
            onClick={() => {
              set('stan', key === 'otwarte' ? null : key)
              set('zwrot', null)
            }}
          >
            {key === 'otwarte' ? 'Otwarte' : key === 'wszystkie' ? 'Wszystkie' : STATUS_LABELS[key]}
            <span className="tabular-nums opacity-70">{counts.get(key) ?? 0}</span>
          </Button>
        ))}
      </div>
      {returns.data.length === 0 ? (
        <FullPageMessage
          title="Nie ma jeszcze zwrotów"
          description="Zwrot powstaje z listy z kolektora albo z Protokołów."
          inline
        />
      ) : (
        <div className="grid items-start gap-4 lg:grid-cols-[18rem_minmax(0,1fr)]">
          <ReturnList items={visible} selected={selected} onSelect={(id) => set('zwrot', id)} />
          {selected ? (
            <ReturnDetails key={selected} storeId={storeId} id={selected} onRemoved={() => set('zwrot', null)} />
          ) : (
            <FullPageMessage title="Nie ma zwrotów w tym stanie" inline />
          )}
        </div>
      )}
    </div>
  )
}

function ReturnList({
  items,
  selected,
  onSelect,
}: {
  items: SupplierReturnSummary[]
  selected: string | null
  onSelect: (id: string) => void
}) {
  return (
    <Card className="gap-0 overflow-hidden p-0 lg:sticky lg:top-16 lg:max-h-[calc(100dvh-5rem)] lg:overflow-y-auto">
      <ul className="divide-y">
        {items.map((item) => (
          <li key={item.id}>
            <button
              type="button"
              onClick={() => onSelect(item.id)}
              aria-current={item.id === selected || undefined}
              className={cn(
                'grid w-full gap-1 px-3 py-2.5 text-left text-sm transition-colors duration-100',
                item.id === selected ? 'bg-muted' : 'hover:bg-muted/60',
              )}
            >
              <span className="flex items-center gap-1.5 font-medium">
                <PackageXIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
                <span className="min-w-0 truncate">{item.supplier?.name ?? 'Dostawca nieustalony'}</span>
              </span>
              <span className="text-xs text-muted-foreground tabular-nums">
                {dateTime.format(new Date(item.createdAt))}, artykułów: {item.lineCount}
              </span>
              <span className="flex flex-wrap gap-1">
                <Badge className={STATUS_STYLES[item.status]}>{STATUS_LABELS[item.status]}</Badge>
                {item.correction && <Badge variant="outline">korekta {item.correction.number}</Badge>}
                {item.invoice && <Badge variant="outline">KSeF</Badge>}
              </span>
            </button>
          </li>
        ))}
      </ul>
    </Card>
  )
}

function ReturnDetails({ storeId, id, onRemoved }: { storeId: string; id: string; onRemoved: () => void }) {
  const details = useSupplierReturn(storeId, id)
  const actions = useSupplierReturnActions(storeId)
  const [supplierOpen, setSupplierOpen] = useState(false)

  if (details.isPending) return <LoadingPanel className="h-96 rounded-xl" />
  if (details.isError) {
    return (
      <FullPageMessage
        title="Nie udało się wczytać zwrotu"
        description={errorMessage(details.error, '')}
        action={{ label: 'Spróbuj ponownie', onClick: () => details.refetch() }}
        inline
      />
    )
  }
  const { summary, events } = details.data
  const fail = (fallback: string) => (error: unknown) => toast.error(errorMessage(error, fallback))
  const file = (request: Promise<void>, fallback: string) => request.catch(fail(fallback))

  const remove = () =>
    actions.remove.mutate(id, {
      onSuccess: () => {
        onRemoved()
        toast.success('Usunięto zwrot', {
          action: {
            label: 'Przywróć',
            onClick: () => actions.restore.mutate(id, { onError: fail('Nie udało się przywrócić zwrotu') }),
          },
        })
      },
      onError: fail('Nie udało się usunąć zwrotu'),
    })

  return (
    <div className="grid min-w-0 gap-4">
      <Card className="grid gap-4 p-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="grid gap-1">
            <h2 className="text-lg font-semibold">
              {summary.supplier ? summary.supplier.name : 'Dostawca nieustalony'}
              {summary.supplier?.code && (
                <span className="ml-2 text-sm font-normal text-muted-foreground">kod {summary.supplier.code}</span>
              )}
            </h2>
            <p className="text-sm text-muted-foreground">
              {summary.source === 'COLLECTOR' ? 'Z kolektora' : 'Z Protokołów'},{' '}
              {dateTime.format(new Date(summary.createdAt))}
              {summary.createdBy && `, ${summary.createdBy}`}
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" size="sm" onClick={() => setSupplierOpen(true)}>
              Zmień dostawcę
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() =>
                void file(downloadFile(supplierReturnFile(storeId, id, 'xlsx')), 'Nie udało się pobrać Excela')
              }
            >
              <FileSpreadsheetIcon aria-hidden />
              Excel
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() =>
                void file(downloadFile(supplierReturnFile(storeId, id, 'pdf')), 'Nie udało się pobrać PDF-a')
              }
            >
              <FileTextIcon aria-hidden />
              PDF
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => void file(printFile(supplierReturnFile(storeId, id, 'pdf')), 'Nie udało się wydrukować')}
            >
              <PrinterIcon aria-hidden />
              Drukuj
            </Button>
            <Button variant="ghost" size="icon-sm" aria-label="Usuń zwrot" title="Usuń zwrot" onClick={remove}>
              <Trash2Icon aria-hidden />
            </Button>
          </div>
        </div>

        <div className="grid gap-2">
          <span className="text-sm font-medium">Stan</span>
          <div className="flex flex-wrap gap-1.5">
            {STATUS_ORDER.map((status) => (
              <button
                key={status}
                type="button"
                aria-pressed={summary.status === status}
                disabled={actions.status.isPending}
                onClick={() =>
                  summary.status !== status &&
                  actions.status.mutate({ id, status }, { onError: fail('Nie udało się zmienić stanu') })
                }
                className={cn(
                  'rounded-full px-3 py-1 text-sm font-medium transition-[background-color,box-shadow] duration-100',
                  summary.status === status
                    ? cn(STATUS_STYLES[status], 'ring-2 ring-foreground/40')
                    : 'bg-muted text-muted-foreground hover:text-foreground',
                )}
              >
                {STATUS_LABELS[status]}
              </button>
            ))}
          </div>
        </div>

        <Attachments storeId={storeId} summary={summary} />

        <TextareaField
          key={summary.note ?? ''}
          label="Notatka (np. numer zgłoszenia u dostawcy)"
          rows={2}
          maxLength={500}
          defaultValue={summary.note ?? ''}
          onBlur={(event) => {
            const note = event.target.value.trim()
            if (note !== (summary.note ?? '')) {
              actions.note.mutate({ id, note }, { onError: fail('Nie udało się zapisać notatki') })
            }
          }}
        />
      </Card>

      <ReturnRows details={details.data} />

      {events.length > 0 && (
        <Card className="grid gap-2 p-4">
          <h3 className="font-medium">Historia</h3>
          <ol className="grid gap-1.5 text-sm">
            {events.map((event, index) => (
              <li key={index} className="flex flex-wrap gap-x-2">
                <span className="text-muted-foreground tabular-nums">{dateTime.format(new Date(event.createdAt))}</span>
                <span className="min-w-0 flex-1">{event.detail}</span>
                <span className="text-muted-foreground">
                  {event.automatic ? (
                    <span className="inline-flex items-center gap-1">
                      <SparklesIcon className="size-3.5" aria-hidden />
                      automatycznie
                    </span>
                  ) : (
                    event.createdBy
                  )}
                </span>
              </li>
            ))}
          </ol>
        </Card>
      )}

      <Dialog open={supplierOpen} onOpenChange={setSupplierOpen}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>Dostawca zwrotu</DialogTitle>
            <DialogDescription>Od niego aplikacja szuka bonów i korekt z tymi artykułami.</DialogDescription>
          </DialogHeader>
          <SupplierPicker
            storeId={storeId}
            selectedId={summary.supplier?.id ?? null}
            suggested={null}
            onSelect={(supplierId) =>
              actions.supplier.mutate(
                { id, supplierId },
                { onSuccess: () => setSupplierOpen(false), onError: fail('Nie udało się zmienić dostawcy') },
              )
            }
          />
        </DialogContent>
      </Dialog>
    </div>
  )
}

const PARTS: { part: AttachPart; label: string; empty: string }[] = [
  { part: 'delivery', label: 'Bon dostawy', empty: 'Z którą dostawą towar przyjechał' },
  { part: 'correction', label: 'Bon korekty', empty: 'Korekta od dostawcy albo błędu sklepu z raportu dokumentów' },
  { part: 'invoice', label: 'Korekta z KSeF', empty: 'Faktura korygująca od dostawcy' },
]

function Attachments({ storeId, summary }: { storeId: string; summary: SupplierReturnSummary }) {
  const actions = useSupplierReturnActions(storeId)
  const [picking, setPicking] = useState<AttachPart | null>(null)
  const candidates = useSupplierReturnCandidates(storeId, summary.id, picking !== null)

  const attach = (part: AttachPart, documentId: string | null) =>
    actions.attach.mutate(
      { id: summary.id, part, documentId },
      {
        onSuccess: () => {
          setPicking(null)
          if (documentId === null) {
            const previous =
              part === 'delivery' ? summary.delivery : part === 'correction' ? summary.correction : summary.invoice
            toast.success('Odpięto', {
              action: previous
                ? {
                    label: 'Przywróć',
                    onClick: () => actions.attach.mutate({ id: summary.id, part, documentId: previous.id }),
                  }
                : undefined,
            })
          }
        },
        onError: (error) => toast.error(errorMessage(error, 'Nie udało się zapisać')),
      },
    )

  return (
    <div className="grid gap-2 sm:grid-cols-3">
      {PARTS.map(({ part, label, empty }) => {
        const document = part === 'delivery' ? summary.delivery : part === 'correction' ? summary.correction : null
        const invoice = part === 'invoice' ? summary.invoice : null
        const attached = document ?? invoice
        return (
          <div key={part} className="grid content-start gap-1.5 rounded-lg border p-3">
            <span className="text-xs font-medium text-muted-foreground uppercase">{label}</span>
            {document && <DocumentLink document={document} />}
            {invoice && (
              <span className="grid text-sm">
                <span className="font-medium">{invoice.invoiceNumber}</span>
                <span className="text-xs text-muted-foreground tabular-nums">
                  {invoice.issueDate && date.format(new Date(invoice.issueDate))}
                  {invoice.netAmount != null && ` · ${money.format(invoice.netAmount)} zł netto`}
                </span>
              </span>
            )}
            {!attached && <span className="text-xs text-muted-foreground">{empty}</span>}
            <div className="flex flex-wrap gap-1.5">
              <Button size="sm" variant={attached ? 'ghost' : 'outline'} onClick={() => setPicking(part)}>
                <LinkIcon aria-hidden />
                {attached ? 'Zmień' : 'Podepnij'}
              </Button>
              {invoice && (
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() =>
                    void printFile(`/stores/${storeId}/ksef/invoices/${invoice.id}/pdf`).catch(() =>
                      toast.error('Nie udało się wydrukować korekty'),
                    )
                  }
                >
                  <PrinterIcon aria-hidden />
                  Drukuj
                </Button>
              )}
              {attached && (
                <Button size="sm" variant="ghost" onClick={() => attach(part, null)}>
                  <UnlinkIcon aria-hidden />
                  Odepnij
                </Button>
              )}
            </div>
          </div>
        )
      })}
      <Dialog open={picking !== null} onOpenChange={(open) => !open && setPicking(null)}>
        <DialogContent className="sm:max-w-xl">
          <DialogHeader>
            <DialogTitle>{PARTS.find((item) => item.part === picking)?.label}</DialogTitle>
            <DialogDescription>
              {summary.supplier
                ? `Od ${summary.supplier.name}, najpierw z największą liczbą artykułów zwrotu.`
                : 'Zwrot nie ma dostawcy: wyszukiwanie po samych artykułach.'}
            </DialogDescription>
          </DialogHeader>
          {candidates.isPending ? (
            <LoadingPanel className="h-48 rounded-xl" />
          ) : candidates.isError ? (
            <p className="text-sm text-destructive">Nie udało się wczytać dokumentów.</p>
          ) : (
            picking && (
              <CandidateList
                part={picking}
                candidates={candidates.data}
                busy={actions.attach.isPending}
                onPick={(documentId) => attach(picking, documentId)}
              />
            )
          )}
        </DialogContent>
      </Dialog>
    </div>
  )
}

function CandidateList({
  part,
  candidates,
  busy,
  onPick,
}: {
  part: AttachPart
  candidates: SupplierReturnCandidates
  busy: boolean
  onPick: (id: string) => void
}) {
  const rows =
    part === 'invoice'
      ? candidates.invoices.map((item) => ({
          id: item.invoice.id,
          title: item.invoice.invoiceNumber,
          meta: [
            item.invoice.issueDate && date.format(new Date(item.invoice.issueDate)),
            item.invoice.netAmount != null && `${money.format(item.invoice.netAmount)} zł netto`,
          ],
          common: item.common,
        }))
      : (part === 'delivery' ? candidates.deliveries : candidates.corrections).map((item) => ({
          id: item.document.id,
          title: documentTitle(item.document),
          meta: [
            item.document.documentDate && date.format(new Date(item.document.documentDate)),
            item.document.supplierDocumentNumber && `nr dostawcy ${item.document.supplierDocumentNumber}`,
            item.document.purchaseValue != null && `${money.format(item.document.purchaseValue)} zł netto`,
          ],
          common: item.common as number | null,
        }))
  if (rows.length === 0) {
    return <p className="py-6 text-center text-sm text-muted-foreground">Nie ma pasujących dokumentów.</p>
  }
  return (
    <ul className="max-h-[60vh] divide-y overflow-y-auto rounded-lg border">
      {rows.map((row) => (
        <li key={row.id}>
          <button
            type="button"
            disabled={busy}
            onClick={() => onPick(row.id)}
            className="flex w-full items-center gap-3 px-3 py-2 text-left text-sm hover:bg-muted disabled:opacity-60"
          >
            <span className="min-w-0 flex-1">
              <span className="block font-medium">{row.title}</span>
              <span className="block text-xs text-muted-foreground tabular-nums">
                {row.meta.filter(Boolean).join(' · ')}
              </span>
            </span>
            <span className="shrink-0 text-xs tabular-nums">
              {row.common == null ? (
                <span className="text-muted-foreground">XML nieodczytany</span>
              ) : (
                <span className={cn(row.common > 0 && 'font-semibold text-emerald-700 dark:text-emerald-300')}>
                  wspólnych: {row.common} z {candidates.lineCount}
                </span>
              )}
            </span>
          </button>
        </li>
      ))}
    </ul>
  )
}

function documentTitle(document: SupplierReturnDocument) {
  if (document.type === 'PZ') return `Bon ${document.bonNumber}`
  return `${document.type === 'WZ' ? 'Korekta od dostawcy' : 'Korekta błędu sklepu'} ${document.number}`
}

function DocumentLink({ document }: { document: SupplierReturnDocument }) {
  return (
    <span className="grid text-sm">
      <Link
        to={`/dokumenty/${document.type.toLowerCase()}/${document.id}`}
        className="font-medium underline-offset-2 hover:underline"
      >
        {documentTitle(document)}
      </Link>
      <span className="text-xs text-muted-foreground tabular-nums">
        {document.documentDate && date.format(new Date(document.documentDate))}
        {document.purchaseValue != null && ` · ${money.format(document.purchaseValue)} zł netto`}
      </span>
    </span>
  )
}

function ReturnRows({ details }: { details: SupplierReturnDetails }) {
  const { items } = details
  return (
    <Card className="gap-0 overflow-hidden p-0">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>ITM8</TableHead>
            <TableHead>EAN</TableHead>
            <TableHead>Nazwa</TableHead>
            <TableHead>Marka</TableHead>
            <TableHead className="text-right">Zakup netto</TableHead>
            <TableHead className="text-right">Zakup × ilość</TableHead>
            <TableHead className="text-right">Ilość</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {items.rows.map((row, index) => (
            <TableRow key={`${row.itm8}-${index}`}>
              <TableCell className="tabular-nums">{row.itm8.replace(/^0+/, '')}</TableCell>
              <TableCell className="tabular-nums">{row.ean}</TableCell>
              <TableCell className="min-w-56 whitespace-normal">
                <span className="flex items-center gap-1.5">
                  <span className="font-medium">{row.name}</span>
                  {row.known ? (
                    <ArticleStatsButton article={{ itm8: row.itm8, name: row.name }} />
                  ) : (
                    <Badge className="bg-rose-200 text-rose-950 dark:bg-rose-900/70 dark:text-rose-100">
                      spoza kartoteki
                    </Badge>
                  )}
                </span>
              </TableCell>
              <TableCell>{row.brand}</TableCell>
              <TableCell className="text-right tabular-nums">
                {row.purchasePrice == null ? '–' : money.format(row.purchasePrice)}
              </TableCell>
              <TableCell className="text-right tabular-nums">
                {row.purchaseValue == null ? '–' : money.format(row.purchaseValue)}
              </TableCell>
              <TableCell className="text-right text-base font-semibold tabular-nums">
                {quantity.format(row.quantity)}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
        <TableFooter>
          <TableRow>
            <TableCell colSpan={5}>Razem: {items.rows.length} artykułów</TableCell>
            <TableCell className="text-right tabular-nums">{money.format(items.totalPurchaseValue)}</TableCell>
            <TableCell className="text-right tabular-nums">{quantity.format(items.totalQuantity)}</TableCell>
          </TableRow>
        </TableFooter>
      </Table>
    </Card>
  )
}
