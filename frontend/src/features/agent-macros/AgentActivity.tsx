import { Popover } from '@base-ui/react/popover'
import { CircleCheckIcon, CircleSlashIcon, LoaderCircleIcon, MonitorPlayIcon, TriangleAlertIcon } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { useCan } from '@/features/auth/api'
import { useCurrentStore } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import type { AgentActivity as Activity, AgentMacroJob } from '@/lib/types'
import { cn } from '@/lib/utils'

import {
  cancelMacro,
  isActive,
  markLoadedByHand,
  onComputer,
  type RecentMacro,
  restoreMacro,
  retryMacro,
  useAgentActivity,
} from './api'

const time = (value: string) => new Date(value).toLocaleTimeString('pl-PL', { hour: '2-digit', minute: '2-digit' })

const LABELS: Record<AgentMacroJob['status'], string> = {
  QUEUED: 'w kolejce',
  OFFERED: 'odliczanie',
  RUNNING: 'trwa',
  DONE: 'zakończone',
  FAILED: 'błąd',
  NO_COMPUTER: 'brak wolnego komputera',
  CANCELLED: 'anulowane',
}

const failed = (job: AgentMacroJob) => job.status === 'FAILED' || job.status === 'NO_COMPUTER'

/**
 * Co robi teraz komputer sklepu: makro albo aktualizacja danych, a w okienku ostatnie makra z wynikiem. Stan jest
 * z serwera, więc jest widoczny na każdej stronie i po odświeżeniu.
 */
export function AgentActivity() {
  const store = useCurrentStore()
  const canView = useCan('DATA_ENTRY', 'VIEW')
  const canEdit = useCan('DATA_ENTRY', 'EDIT')
  const { data } = useAgentActivity(store?.id, canView)
  if (!store || !canView || !data || (data.jobs.length === 0 && !data.update && !data.waitingFiles)) return null

  const active = data.jobs.find((job) => onComputer(job))
  const queued = data.jobs.filter((job) => job.status === 'QUEUED').length + (data.waitingFiles ? 1 : 0)
  const waiting = queued > 0 ? `w kolejce: ${queued}` : null
  const current = active
    ? `${active.title}${active.computer ? ` · ${active.computer}` : ''}`
    : data.update
      ? `Aktualizacja danych · ${data.update.computer}`
      : null
  const text = current && waiting ? `${current} · ${waiting}` : (current ?? (waiting && `Makra ${waiting}`))
  const problem = !text && data.jobs[0]?.fresh && failed(data.jobs[0])
  return (
    <Popover.Root>
      <Popover.Trigger
        aria-label={text ? `Komputer sklepu: ${text}` : 'Ostatnie makra na komputerach sklepu'}
        title={text ?? 'Ostatnie makra na komputerach sklepu'}
        className={cn(
          'flex h-8 max-w-64 items-center gap-1.5 rounded-full px-2 text-xs font-medium outline-none focus-visible:ring-2 focus-visible:ring-ring',
          text
            ? 'bg-sky-200 text-sky-950 dark:bg-sky-950 dark:text-sky-100'
            : problem
              ? 'bg-red-200 text-red-950 dark:bg-red-950 dark:text-red-100'
              : 'text-muted-foreground hover:bg-muted hover:text-foreground',
        )}
      >
        {text ? (
          <LoaderCircleIcon className="size-4 shrink-0 animate-spin motion-reduce:animate-none" />
        ) : problem ? (
          <TriangleAlertIcon className="size-4 shrink-0" />
        ) : (
          <MonitorPlayIcon className="size-4 shrink-0" />
        )}
        {text && <span className="hidden truncate md:block">{text}</span>}
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Positioner side="bottom" align="end" sideOffset={6} collisionPadding={12} className="z-50">
          <Popover.Popup className="grid max-h-[70vh] w-96 max-w-[calc(100vw-2rem)] origin-(--transform-origin) gap-2 overflow-auto rounded-xl glass-float p-3 text-sm text-popover-foreground outline-none transition-[opacity,scale] duration-150 data-ending-style:scale-95 data-ending-style:opacity-0 data-starting-style:scale-95 data-starting-style:opacity-0 motion-reduce:transition-none">
            <Popover.Title className="px-1 font-semibold">Komputery sklepu</Popover.Title>
            {data.update && (
              <div className="flex items-start gap-2 rounded-lg bg-sky-200 px-3 py-2 text-sky-950 dark:bg-sky-950 dark:text-sky-100">
                <LoaderCircleIcon className="mt-0.5 size-4 shrink-0 animate-spin motion-reduce:animate-none" />
                <p>
                  <span className="font-medium">Aktualizacja danych z Mercalys</span>
                  <br />
                  {data.update.computer}, od {time(data.update.startedAt)}
                </p>
              </div>
            )}
            <ul className="grid gap-1">
              {data.waitingFiles && <WaitingFilesRow files={data.waitingFiles} />}
              {data.jobs.map((job, index) => (
                <JobRow
                  key={job.id}
                  storeId={store.id}
                  job={job}
                  canEdit={canEdit}
                  // Ponawia się tylko najnowsze makro swojego rodzaju.
                  newest={data.jobs.findIndex((other) => other.kind === job.kind) === index}
                />
              ))}
            </ul>
          </Popover.Popup>
        </Popover.Positioner>
      </Popover.Portal>
    </Popover.Root>
  )
}

/** Pliki nowych artykułów zapisane przez agenta, na które makro jeszcze nie ruszyło (np. trwa inne makro sklepu). */
function WaitingFilesRow({ files }: { files: NonNullable<Activity['waitingFiles']> }) {
  return (
    <li className="grid gap-1 rounded-lg px-1 py-1.5">
      <div className="flex items-start gap-2">
        <LoaderCircleIcon className="mt-0.5 size-4 shrink-0 animate-spin text-sky-600 motion-reduce:animate-none dark:text-sky-400" />
        <div className="min-w-0 flex-1">
          <p className="font-medium break-words">
            Wczytanie nowych artykułów{files.count > 1 ? ` (${files.count} pliki)` : ''}
          </p>
          <p className="text-xs text-muted-foreground">
            {time(files.since)} · {LABELS.QUEUED} · {files.requestedBy}
          </p>
          <p className="mt-0.5 text-xs break-words text-muted-foreground">
            Czeka, aż komputer sklepu skończy inne makro. Ruszy samo.
          </p>
        </div>
      </div>
    </li>
  )
}

function JobRow({
  storeId,
  job,
  canEdit,
  newest,
}: {
  storeId: string
  job: RecentMacro
  canEdit: boolean
  newest: boolean
}) {
  const [busy, setBusy] = useState(false)
  const act = async (action: () => Promise<unknown>) => {
    setBusy(true)
    try {
      await action()
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Brak połączenia z serwerem')
    } finally {
      setBusy(false)
    }
  }
  const canRetry = newest && failed(job) && (job.kind === 'PRODUCT_FILES' || job.kind === 'PRICE_CONTROL')
  return (
    <li className="grid gap-1 rounded-lg px-1 py-1.5">
      <div className="flex items-start gap-2">
        {isActive(job) ? (
          <LoaderCircleIcon className="mt-0.5 size-4 shrink-0 animate-spin text-sky-600 motion-reduce:animate-none dark:text-sky-400" />
        ) : job.status === 'DONE' ? (
          <CircleCheckIcon className="mt-0.5 size-4 shrink-0 text-emerald-600 dark:text-emerald-400" />
        ) : job.status === 'CANCELLED' ? (
          <CircleSlashIcon className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
        ) : (
          <TriangleAlertIcon className="mt-0.5 size-4 shrink-0 text-red-600 dark:text-red-400" />
        )}
        <div className="min-w-0 flex-1">
          <p className="font-medium break-words">{job.title}</p>
          <p className="text-xs text-muted-foreground">
            {time(job.startedAt ?? job.createdAt)} · {LABELS[job.status]}
            {job.status === 'QUEUED' && job.queuePosition && ` (${job.queuePosition}.)`}
            {job.computer && ` · ${job.computer}`} · {job.requestedBy}
          </p>
          {job.status === 'QUEUED' && job.waitReason && (
            <p className="mt-0.5 text-xs break-words text-muted-foreground">Czeka: {job.waitReason}</p>
          )}
          {failed(job) && job.message && (
            <p className="mt-1 max-h-24 overflow-auto text-xs break-words whitespace-pre-line">{job.message}</p>
          )}
        </div>
      </div>
      {((canEdit && (job.status === 'OFFERED' || job.status === 'QUEUED')) || canRetry) && (
        <div className="flex flex-wrap gap-2 pl-6">
          {canEdit && (job.status === 'OFFERED' || job.status === 'QUEUED') && (
            <Button
              size="sm"
              variant="outline"
              loading={busy}
              onClick={() =>
                act(async () => {
                  await cancelMacro(storeId, job.id)
                  toast.success('Makro anulowane', {
                    action: {
                      label: 'Przywróć',
                      onClick: () => {
                        restoreMacro(storeId, job.id).catch((error) =>
                          toast.error(error instanceof ApiError ? error.message : 'Nie udało się przywrócić makra'),
                        )
                      },
                    },
                  })
                })
              }
            >
              Anuluj
            </Button>
          )}
          {canRetry && canEdit && (
            <Button size="sm" loading={busy} onClick={() => act(() => retryMacro(storeId, job.id))}>
              Spróbuj ponownie
            </Button>
          )}
          {canRetry && job.kind === 'PRODUCT_FILES' && (
            <Button
              size="sm"
              variant="outline"
              disabled={busy}
              onClick={() => act(() => markLoadedByHand(storeId, job.id))}
            >
              Wczytane ręcznie
            </Button>
          )}
        </div>
      )}
    </li>
  )
}
