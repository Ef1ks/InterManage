import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { CheckIcon, ExternalLinkIcon, MessageSquareWarningIcon, Trash2Icon } from 'lucide-react'
import { toast } from 'sonner'

import { TextareaField } from '@/components/form'
import { LoadingPanel } from '@/components/LoadingPanel'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import {
  FEEDBACK_KINDS,
  FEEDBACK_STATUSES,
  feedbackAttachmentUrl,
  useFeedbackActions,
  useFeedbackCounts,
  useFeedbackList,
} from '@/features/feedback/api'
import { inStore } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import type { FeedbackCounts, FeedbackReport, FeedbackStatus } from '@/lib/types'
import { cn } from '@/lib/utils'

const dateTime = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'short' })

type Tab = FeedbackStatus | 'ALL'

const TABS: { value: Tab; param: string; label: string; count?: keyof FeedbackCounts }[] = [
  { value: 'NEW', param: 'nowe', label: 'Nowe', count: 'fresh' },
  { value: 'IN_PROGRESS', param: 'w-trakcie', label: 'W trakcie', count: 'inProgress' },
  { value: 'DONE', param: 'zrobione', label: 'Zrobione', count: 'done' },
  { value: 'REJECTED', param: 'odrzucone', label: 'Odrzucone', count: 'rejected' },
  { value: 'ALL', param: 'wszystkie', label: 'Wszystkie' },
]

const STATUS_ORDER: FeedbackStatus[] = ['NEW', 'IN_PROGRESS', 'DONE', 'REJECTED']

function errorText(error: unknown) {
  return error instanceof ApiError ? error.message : 'Spróbuj jeszcze raz.'
}

/** Zgłoszenia poprawek i błędów od pracowników ze zrzutami; prowadzi je właściciel (stan, notatka, usunięcie). */
export function FeedbackPage() {
  const [params, setParams] = useSearchParams()
  const tab = TABS.find((candidate) => candidate.param === params.get('stan')) ?? TABS[0]
  const list = useFeedbackList(tab.value === 'ALL' ? null : tab.value)
  const counts = useFeedbackCounts(true)
  const [viewing, setViewing] = useState<{ src: string; title: string } | null>(null)

  return (
    <div>
      <PageHeader
        title="Zgłoszenia"
        description="Poprawki i błędy zgłoszone przez pracowników przyciskiem w prawym górnym rogu, ze zrzutami ekranu."
      />
      <div className="mb-4 flex flex-wrap gap-1.5" role="tablist" aria-label="Stan zgłoszeń">
        {TABS.map((candidate) => {
          const count = candidate.count ? counts.data?.[candidate.count] : undefined
          const active = candidate.value === tab.value
          return (
            <Button
              key={candidate.value}
              role="tab"
              aria-selected={active}
              variant={active ? 'default' : 'outline'}
              size="sm"
              onClick={() =>
                setParams(
                  (current) => {
                    const next = new URLSearchParams(current)
                    if (candidate === TABS[0]) next.delete('stan')
                    else next.set('stan', candidate.param)
                    return next
                  },
                  { replace: true },
                )
              }
            >
              {candidate.label}
              {count !== undefined && count > 0 && <span className="tabular-nums opacity-80">{count}</span>}
            </Button>
          )
        })}
      </div>

      {list.isPending ? (
        <LoadingPanel className="h-72 rounded-xl" />
      ) : list.isError ? (
        <Card className="p-6 text-sm text-destructive">Nie udało się wczytać zgłoszeń. {errorText(list.error)}</Card>
      ) : list.data.length === 0 ? (
        <Card className="grid place-items-center gap-2 p-10 text-center text-sm text-muted-foreground">
          <MessageSquareWarningIcon className="size-8 opacity-50" />
          {tab.value === 'NEW' ? 'Nie ma nowych zgłoszeń.' : 'Nie ma tu zgłoszeń.'}
        </Card>
      ) : (
        <div className="grid gap-3">
          {list.data.map((report) => (
            <FeedbackCard key={report.id} report={report} onView={setViewing} />
          ))}
        </div>
      )}

      <Dialog open={viewing !== null} onOpenChange={(open) => !open && setViewing(null)}>
        <DialogContent className="gap-3 p-4 sm:max-w-5xl">
          <DialogHeader>
            <DialogTitle>{viewing?.title}</DialogTitle>
          </DialogHeader>
          {viewing && (
            <a href={viewing.src} target="_blank" rel="noreferrer" title="Otwórz w nowej karcie">
              <img src={viewing.src} alt={viewing.title} className="max-h-[75svh] w-full rounded-lg object-contain" />
            </a>
          )}
        </DialogContent>
      </Dialog>
    </div>
  )
}

function FeedbackCard({
  report,
  onView,
}: {
  report: FeedbackReport
  onView: (image: { src: string; title: string }) => void
}) {
  const { changeStatus, remove, restore } = useFeedbackActions()
  const [note, setNote] = useState(report.ownerNote ?? '')
  const status = FEEDBACK_STATUSES[report.status]
  const noteChanged = note.trim() !== (report.ownerNote ?? '')
  const pageLink = report.pageUrl ? (report.storeId ? inStore(report.pageUrl, report.storeId) : report.pageUrl) : null

  const setStatus = (next: FeedbackStatus) =>
    changeStatus.mutate(
      { id: report.id, status: next, note: note.trim() || null },
      {
        onSuccess: () => toast.success(`Zgłoszenie: ${FEEDBACK_STATUSES[next].label.toLowerCase()}`),
        onError: (error) => toast.error('Nie zapisano zmiany', { description: errorText(error) }),
      },
    )

  const removeReport = () =>
    remove.mutate(report.id, {
      onSuccess: () =>
        toast('Usunięto zgłoszenie', {
          action: { label: 'Przywróć', onClick: () => restore.mutate(report.id) },
        }),
      onError: (error) => toast.error('Nie usunięto zgłoszenia', { description: errorText(error) }),
    })

  return (
    <Card className="gap-3 p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <span className={cn('rounded-md px-2 py-0.5 text-xs font-semibold', status.badge)}>{status.label}</span>
          <span
            className={cn(
              'rounded-md px-2 py-0.5 text-xs font-medium',
              report.kind === 'BUG'
                ? 'bg-red-200 text-red-950 dark:bg-red-900/70 dark:text-red-100'
                : 'bg-violet-200 text-violet-950 dark:bg-violet-900/70 dark:text-violet-100',
            )}
          >
            {FEEDBACK_KINDS[report.kind]}
          </span>
          <span className="font-medium">{report.createdByName ?? 'Konto usunięte'}</span>
          <span className="text-muted-foreground">
            {dateTime.format(new Date(report.createdAt))}
            {report.storeNumber && ` · sklep ${report.storeNumber}`}
          </span>
        </div>
        <Button
          variant="ghost"
          size="icon-sm"
          aria-label="Usuń zgłoszenie"
          title="Usuń zgłoszenie"
          onClick={removeReport}
        >
          <Trash2Icon />
        </Button>
      </div>

      <p className="text-sm whitespace-pre-wrap">{report.description}</p>

      {report.attachments.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {report.attachments.map((attachment, index) => {
            const src = feedbackAttachmentUrl(report.id, attachment.id)
            return (
              <button
                key={attachment.id}
                type="button"
                onClick={() => onView({ src, title: `Zrzut ${index + 1}` })}
                className="overflow-hidden rounded-lg border bg-muted transition-transform duration-150 ease-out hover:scale-[1.02] focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none motion-reduce:transition-none"
                aria-label={`Zrzut ${index + 1}`}
              >
                <img src={src} alt="" loading="lazy" className="h-28 w-44 object-cover object-top" />
              </button>
            )
          })}
        </div>
      )}

      {pageLink && (
        <Link
          to={pageLink}
          className="inline-flex w-fit items-center gap-1.5 text-sm text-primary underline-offset-4 hover:underline"
        >
          <ExternalLinkIcon className="size-3.5" />
          {report.pageLabel ?? 'Strona'}
        </Link>
      )}

      <div className="grid gap-2 border-t pt-3">
        <TextareaField
          label="Notatka (np. co zrobiono, w której wersji)"
          value={note}
          onChange={(event) => setNote(event.target.value)}
          rows={2}
          maxLength={2000}
        />
        <div className="flex flex-wrap items-center gap-1.5">
          {STATUS_ORDER.map((candidate) => (
            <Button
              key={candidate}
              size="sm"
              variant={candidate === report.status ? 'secondary' : 'outline'}
              disabled={changeStatus.isPending || (candidate === report.status && !noteChanged)}
              onClick={() => setStatus(candidate)}
            >
              {candidate === report.status && <CheckIcon />}
              {candidate === report.status && noteChanged ? 'Zapisz notatkę' : FEEDBACK_STATUSES[candidate].label}
            </Button>
          ))}
          {report.statusChangedAt && (
            <span className="text-xs text-muted-foreground">
              {report.statusChangedByName ?? 'Konto usunięte'} · {dateTime.format(new Date(report.statusChangedAt))}
            </span>
          )}
        </div>
      </div>
    </Card>
  )
}

/** Licznik nowych zgłoszeń przy „Zgłoszeniach” w menu (tylko właściciel). */
export function NewFeedbackBadge() {
  const counts = useFeedbackCounts(true)
  const count = counts.data?.fresh ?? 0
  if (count === 0) return null
  return (
    <span
      aria-label={`${count} nowych zgłoszeń`}
      className="rounded-full bg-amber-500 px-1.5 text-[0.7rem] leading-[1.125rem] font-semibold text-white tabular-nums animate-in zoom-in-50 fade-in-0 duration-200 motion-reduce:animate-none"
    >
      {count}
    </span>
  )
}
