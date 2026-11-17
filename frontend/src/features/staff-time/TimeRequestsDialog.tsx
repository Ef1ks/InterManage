import { useState } from 'react'
import { ChevronLeftIcon, ChevronRightIcon, InboxIcon, PrinterIcon, Undo2Icon } from 'lucide-react'
import { useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { SegmentedField, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCan } from '@/features/auth/api'
import { formatDay } from '@/features/staff-schedule/schedule-utils'
import {
  REQUEST_KINDS,
  REQUEST_STATUSES,
  printTimeRequests,
  useTimeRequestActions,
  useTimeRequestCount,
  useTimeRequests,
} from '@/features/staff-time/requests-api'
import { clockOf, hm } from '@/features/staff-time/time-utils'
import { ApiError } from '@/lib/api'
import { addDays, addMonths, currentMonth, formatMonth } from '@/lib/dates'
import type { TimeRequest } from '@/lib/types'
import { cn } from '@/lib/utils'

const failed = (cause: unknown, fallback: string) => (cause instanceof ApiError ? cause.message : fallback)

const stamp = (at: string) =>
  new Date(at).toLocaleString('pl-PL', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })

/**
 * Przycisk „Wnioski” z liczbą czekających na tę osobę (kierownik działu albo kadry) i okno z wnioskami. Widać go z
 * Czasem pracy albo Kadrami.
 */
export function TimeRequestsButton({ storeId }: { storeId: string }) {
  const time = useCan('STAFF_TIME')
  const records = useCan('STAFF_RECORDS')
  const visible = time || records
  const count = useTimeRequestCount(storeId, visible)
  // `?wnioski=tak` (link z „Do reakcji” na pulpicie) otwiera okno od razu.
  const [params, setParams] = useSearchParams()
  const [open, setOpen] = useState(() => params.get('wnioski') === 'tak')
  const close = () => {
    setOpen(false)
    if (params.has('wnioski')) {
      setParams(
        (current) => {
          const next = new URLSearchParams(current)
          next.delete('wnioski')
          return next
        },
        { replace: true },
      )
    }
  }
  if (!visible) return null
  const waiting = count.data?.total ?? 0
  return (
    <>
      <Button
        type="button"
        variant="outline"
        title="Wnioski pracowników: nadgodziny, wyjścia, spóźnienia, L4"
        onClick={() => setOpen(true)}
      >
        <InboxIcon aria-hidden />
        Wnioski
        {waiting > 0 && (
          <span className="rounded-full bg-amber-300 px-1.5 text-xs font-semibold text-amber-950 tabular-nums dark:bg-amber-500">
            {waiting}
          </span>
        )}
      </Button>
      <TimeRequestsDialog open={open} storeId={storeId} onClose={close} />
    </>
  )
}

type Mode = 'open' | 'month'

export function TimeRequestsDialog({
  open,
  storeId,
  onClose,
}: {
  open: boolean
  storeId: string
  onClose: () => void
}) {
  const [mode, setMode] = useState<Mode>('open')
  const [month, setMonth] = useState(currentMonth)
  const [selected, setSelected] = useState<string[]>([])
  const range = mode === 'month' ? { from: `${month}-01`, to: addDays(`${addMonths(month, 1)}-01`, -1) } : null
  const requests = useTimeRequests(storeId, range, open)
  // Akcje w oknie, nie w karcie: karta znika z „Do decyzji” po decyzji, a „Cofnij” w toaście ma dalej działać.
  const actions = useTimeRequestActions(storeId)
  const items = requests.data ?? []
  const chosen = selected.filter((id) => items.some((item) => item.id === id))

  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent className="gap-4 p-6 sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>Wnioski pracowników</DialogTitle>
          <DialogDescription>
            Kierownik działu akceptuje albo odrzuca wnioski swoich ludzi. Gdy sklep zatwierdza przez kadry (ustawienia
            czasu pracy) i zawsze przy L4, zaakceptowany wniosek czeka jeszcze na kadry. Dopiero zatwierdzony liczy się
            w karcie czasu pracy.
          </DialogDescription>
        </DialogHeader>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <SegmentedField<Mode>
            label="Które wnioski"
            options={[
              { value: 'open', label: 'Do decyzji' },
              { value: 'month', label: 'Wszystkie z miesiąca' },
            ]}
            value={mode}
            onChange={(next) => {
              setMode(next)
              setSelected([])
            }}
          />
          {mode === 'month' && (
            <div className="flex items-center gap-1">
              <Button
                type="button"
                variant="ghost"
                size="icon-sm"
                aria-label="Poprzedni miesiąc"
                onClick={() => setMonth(addMonths(month, -1))}
              >
                <ChevronLeftIcon aria-hidden />
              </Button>
              <span className="min-w-36 text-center text-sm font-medium">{formatMonth(month)}</span>
              <Button
                type="button"
                variant="ghost"
                size="icon-sm"
                aria-label="Następny miesiąc"
                onClick={() => setMonth(addMonths(month, 1))}
              >
                <ChevronRightIcon aria-hidden />
              </Button>
            </div>
          )}
        </div>

        {requests.isPending ? (
          <div className="h-40 animate-pulse rounded-xl bg-muted" aria-busy />
        ) : items.length === 0 ? (
          <p className="rounded-xl bg-muted px-4 py-6 text-center text-sm text-muted-foreground">
            {mode === 'open' ? 'Żaden wniosek nie czeka na Twoją decyzję.' : 'W tym miesiącu nie ma wniosków.'}
          </p>
        ) : (
          <ul className="grid gap-2" data-drag-select-scope>
            {items.map((item) => (
              <RequestCard
                key={item.id}
                storeId={storeId}
                actions={actions}
                request={item}
                checked={chosen.includes(item.id)}
                onCheck={(checked) =>
                  setSelected((current) => (checked ? [...current, item.id] : current.filter((id) => id !== item.id)))
                }
              />
            ))}
          </ul>
        )}

        {items.length > 0 && (
          <div className="flex flex-wrap items-center justify-between gap-2 border-t pt-3">
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={() => setSelected(chosen.length === items.length ? [] : items.map((item) => item.id))}
            >
              {chosen.length === items.length ? 'Odznacz wszystkie' : 'Zaznacz wszystkie'}
            </Button>
            <Button
              type="button"
              variant="outline"
              disabled={chosen.length === 0}
              onClick={() =>
                printTimeRequests(storeId, chosen).catch((cause) =>
                  toast.error(failed(cause, 'Nie udało się przygotować wydruku')),
                )
              }
            >
              <PrinterIcon aria-hidden />
              Drukuj zaznaczone ({chosen.length})
            </Button>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}

function when(request: TimeRequest) {
  if (request.start && request.end) {
    return `${formatDay(request.workDate)} · ${clockOf(request.start)}–${clockOf(request.end)} (${hm(request.minutes ?? 0)})`
  }
  return request.dateTo && request.dateTo !== request.workDate
    ? `${formatDay(request.workDate)} – ${formatDay(request.dateTo)}`
    : formatDay(request.workDate)
}

function RequestCard({
  storeId,
  actions,
  request,
  checked,
  onCheck,
}: {
  storeId: string
  actions: ReturnType<typeof useTimeRequestActions>
  request: TimeRequest
  checked: boolean
  onCheck: (checked: boolean) => void
}) {
  const { approve, reject, undo } = actions
  const [rejecting, setRejecting] = useState(false)
  const [note, setNote] = useState('')
  const status = REQUEST_STATUSES[request.status]

  const undoStep = () =>
    undo.mutateAsync(request.id).then(
      () => toast.success('Cofnięto ostatni krok'),
      (cause) => toast.error(failed(cause, 'Nie udało się cofnąć')),
    )

  const accept = () =>
    approve.mutateAsync({ id: request.id }).then(
      (saved) =>
        toast.success(saved.status === 'APPROVED' ? 'Wniosek zatwierdzony' : 'Zaakceptowano, czeka na kadry', {
          action: { label: 'Cofnij', onClick: () => void undoStep() },
        }),
      (cause) => toast.error(failed(cause, 'Nie udało się zaakceptować wniosku')),
    )

  const refuse = () =>
    reject.mutateAsync({ id: request.id, note: note.trim() || null }).then(
      () => {
        setRejecting(false)
        toast.success('Wniosek odrzucony', { action: { label: 'Cofnij', onClick: () => void undoStep() } })
      },
      (cause) => toast.error(failed(cause, 'Nie udało się odrzucić wniosku')),
    )

  return (
    <li className="grid gap-2 rounded-xl border bg-card p-3">
      <div className="flex flex-wrap items-start gap-3">
        <input
          type="checkbox"
          className="mt-1 size-4 accent-primary"
          aria-label={`Zaznacz wniosek: ${request.employeeName}`}
          checked={checked}
          onChange={(event) => onCheck(event.target.checked)}
        />
        <div className="grid min-w-0 flex-1 gap-0.5">
          <p className="text-sm">
            <span className="font-medium">{request.employeeName}</span>
            <span className="text-muted-foreground">
              {' '}
              · {request.departmentName}, {request.positionName}
            </span>
          </p>
          <p className="text-sm font-medium">{REQUEST_KINDS[request.kind]}</p>
          <p className="text-sm text-muted-foreground tabular-nums">{when(request)}</p>
          {request.reason && <p className="text-sm">„{request.reason}”</p>}
        </div>
        <span className={cn('rounded-md px-2 py-0.5 text-xs font-medium', status.badge)}>{status.label}</span>
      </div>

      <div className="grid gap-0.5 pl-7 text-xs text-muted-foreground">
        <span>
          {request.byEmployee
            ? 'Złożony w aplikacji'
            : `Wpisany${request.createdBy ? ` przez ${request.createdBy}` : ''}`}
          , {stamp(request.createdAt)}
        </span>
        {request.manager && (
          <span>
            Kierownik: {request.manager.by ?? '—'}, {stamp(request.manager.at)}
            {request.manager.note ? ` · „${request.manager.note}”` : ''}
          </span>
        )}
        {request.hr && (
          <span>
            Kadry: {request.hr.by ?? '—'}, {stamp(request.hr.at)}
            {request.hr.note ? ` · „${request.hr.note}”` : ''}
          </span>
        )}
        {request.needsHr && request.status === 'PENDING' && <span>Po akceptacji trafi do kadr.</span>}
      </div>

      {rejecting && (
        <div className="grid gap-2 pl-7">
          <TextField
            label="Dlaczego odrzucasz (opcjonalnie)"
            value={note}
            autoFocus
            onChange={(event) => setNote(event.target.value)}
          />
        </div>
      )}

      <div className="flex flex-wrap justify-end gap-2">
        {request.canUndo && (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            loading={undo.isPending && undo.variables === request.id}
            onClick={() => void undoStep()}
          >
            <Undo2Icon aria-hidden />
            Cofnij krok
          </Button>
        )}
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() =>
            printTimeRequests(storeId, [request.id]).catch((cause) =>
              toast.error(failed(cause, 'Nie udało się przygotować wydruku')),
            )
          }
        >
          <PrinterIcon aria-hidden />
          Drukuj
        </Button>
        {request.canReject &&
          (rejecting ? (
            <>
              <Button type="button" variant="ghost" size="sm" onClick={() => setRejecting(false)}>
                Anuluj
              </Button>
              <Button
                type="button"
                variant="destructive"
                size="sm"
                loading={reject.isPending && reject.variables?.id === request.id}
                onClick={refuse}
              >
                Odrzuć wniosek
              </Button>
            </>
          ) : (
            <Button type="button" variant="outline" size="sm" onClick={() => setRejecting(true)}>
              Odrzuć
            </Button>
          ))}
        {request.canApprove && !rejecting && (
          <Button
            type="button"
            size="sm"
            loading={approve.isPending && approve.variables?.id === request.id}
            onClick={accept}
          >
            {request.status === 'MANAGER_APPROVED' || !request.needsHr ? 'Zatwierdź' : 'Akceptuj'}
          </Button>
        )}
      </div>
    </li>
  )
}
