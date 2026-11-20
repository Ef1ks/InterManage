import { useState } from 'react'
import {
  CalendarClockIcon,
  CheckCircle2Icon,
  ChevronDownIcon,
  ExternalLinkIcon,
  HistoryIcon,
  PencilIcon,
  PlusIcon,
  RotateCcwIcon,
  Trash2Icon,
  UndoIcon,
} from 'lucide-react'
import { Link } from 'react-router'
import { toast } from 'sonner'

import { LoadingPanel } from '@/components/LoadingPanel'
import { SegmentedField, TextareaField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import {
  TASK_EVENTS,
  TASK_STATUSES,
  isOverdue,
  useAllTasks,
  useMyTasks,
  useTaskActions,
  useTaskHistory,
} from '@/features/tasks/api'
import { inStore } from '@/features/stores/current-store'
import { TaskAttachments } from '@/features/tasks/TaskAttachments'
import { TaskDialog } from '@/features/tasks/TaskDialog'
import { taskErrorMessage } from '@/features/tasks/task-helpers'
import { formatShortDate, todayIso } from '@/lib/dates'
import type { StoreTask, TaskStatus } from '@/lib/types'
import { cn } from '@/lib/utils'

type View = 'mine' | 'assigned'

const STATUS_ORDER: Record<TaskStatus, number> = { DONE: 0, OPEN: 1, ACCEPTED: 2 }
const MINE_ORDER: Record<TaskStatus, number> = { OPEN: 0, DONE: 1, ACCEPTED: 2 }

/**
 * Zadania na pulpicie jako lista do odhaczania. Wykonawca widzi swoje, zlecający (właściciel) przełącza na zlecone:
 * najpierw czekające na przyjęcie, potem do zrobienia, na końcu przyjęte z ostatniego tygodnia.
 */
export function TasksPanel({ storeId, canAssign }: { storeId: string; canAssign: boolean }) {
  const [view, setView] = useState<View>(canAssign ? 'assigned' : 'mine')
  const [creating, setCreating] = useState(false)
  const mine = useMyTasks(storeId)
  const all = useAllTasks(storeId, canAssign)
  const query = view === 'assigned' ? all : mine
  const order = view === 'assigned' ? STATUS_ORDER : MINE_ORDER
  const tasks = [...(query.data ?? [])].sort(
    (a, b) =>
      order[a.status] - order[b.status] ||
      (a.dueDate ?? '9999').localeCompare(b.dueDate ?? '9999') ||
      b.createdAt.localeCompare(a.createdAt),
  )
  const waiting = (all.data ?? []).filter((task) => task.status === 'DONE').length
  const myOpen = (mine.data ?? []).filter((task) => task.status === 'OPEN').length

  // Bez zadań i bez prawa zlecania panel nie zajmuje miejsca.
  if (!canAssign && mine.isSuccess && mine.data.length === 0) return null

  return (
    <Card className="mb-6">
      <CardHeader>
        <CardTitle>Zadania</CardTitle>
        <CardDescription>
          {view === 'assigned'
            ? waiting > 0
              ? `${waiting} czeka na Twoje przyjęcie`
              : 'Zadania zlecone w tym sklepie'
            : myOpen > 0
              ? `${myOpen} do zrobienia`
              : 'Twoje zadania w tym sklepie'}
        </CardDescription>
        {canAssign && (
          <CardAction className="flex flex-wrap items-center gap-2">
            <SegmentedField
              label="Widok zadań"
              options={[
                { value: 'assigned' as const, label: 'Zlecone' },
                { value: 'mine' as const, label: 'Moje' },
              ]}
              value={view}
              onChange={setView}
            />
            <Button size="sm" onClick={() => setCreating(true)}>
              <PlusIcon />
              Nowe zadanie
            </Button>
          </CardAction>
        )}
      </CardHeader>
      <CardContent>
        {query.isPending ? (
          <LoadingPanel className="h-24 rounded-lg" />
        ) : tasks.length === 0 ? (
          <p className="flex items-center gap-2 text-sm text-muted-foreground">
            <CheckCircle2Icon className="size-4 text-emerald-600 dark:text-emerald-400" />
            {view === 'assigned' ? 'Nic nie zlecono. Zadanie zlecisz też z raportu.' : 'Nie masz zadań'}
          </p>
        ) : (
          <ul className="-mx-2 grid gap-1">
            {tasks.map((task) => (
              <TaskRow key={task.id} storeId={storeId} task={task} showAssignee={view === 'assigned'} />
            ))}
          </ul>
        )}
      </CardContent>
      {creating && <TaskDialog storeId={storeId} open={creating} onOpenChange={setCreating} />}
    </Card>
  )
}

function TaskRow({ storeId, task, showAssignee }: { storeId: string; task: StoreTask; showAssignee: boolean }) {
  const [open, setOpen] = useState(false)
  const today = todayIso()
  const overdue = isOverdue(task, today)
  const actions = useTaskActions(storeId)
  const status = TASK_STATUSES[task.status]
  const closed = task.status !== 'OPEN'
  const canToggleWhole = task.canComplete && task.itemCount === 0 && task.status !== 'ACCEPTED'

  const toggleWhole = () => {
    const mutation =
      task.status === 'OPEN' ? actions.done.mutateAsync({ id: task.id }) : actions.undone.mutateAsync(task.id)
    mutation.catch((error) => toast.error(taskErrorMessage(error, 'Nie udało się zmienić zadania')))
  }

  return (
    <li className={cn('rounded-lg', open && 'bg-muted/50')}>
      <div className="flex items-start gap-3 px-2 py-2">
        {canToggleWhole ? (
          <input
            type="checkbox"
            checked={task.status === 'DONE'}
            onChange={toggleWhole}
            aria-label={task.status === 'DONE' ? `Cofnij wykonanie: ${task.title}` : `Wykonane: ${task.title}`}
            className="mt-0.5 size-4 shrink-0 accent-emerald-600"
          />
        ) : (
          <span
            aria-hidden
            className={cn(
              'mt-1 size-2.5 shrink-0 rounded-full',
              task.status === 'ACCEPTED'
                ? 'bg-emerald-500'
                : task.status === 'DONE'
                  ? 'bg-amber-500'
                  : overdue
                    ? 'bg-red-500'
                    : 'bg-sky-500',
            )}
          />
        )}
        <button
          type="button"
          onClick={() => setOpen((value) => !value)}
          aria-expanded={open}
          className="min-w-0 flex-1 text-left focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50 rounded"
        >
          <span
            className={cn(
              'block text-sm font-medium',
              task.status === 'ACCEPTED' && 'text-muted-foreground line-through',
            )}
          >
            {task.priority === 'URGENT' && (
              <span className="mr-1.5 rounded bg-red-200 px-1.5 text-xs font-semibold text-red-950 dark:bg-red-900/70 dark:text-red-100">
                Pilne
              </span>
            )}
            {task.title}
          </span>
          <span className="mt-0.5 flex flex-wrap items-center gap-x-3 gap-y-0.5 text-xs text-muted-foreground">
            {showAssignee && <span>{task.assignee.displayName}</span>}
            {task.dueDate && (
              <span
                className={cn(
                  'inline-flex items-center gap-1',
                  overdue && 'font-semibold text-red-600 dark:text-red-400',
                )}
              >
                <CalendarClockIcon className="size-3.5" />
                {overdue ? 'po terminie: ' : 'do '}
                {formatShortDate(task.dueDate)}
              </span>
            )}
            {task.itemCount > 0 && (
              <span className="tabular-nums">
                {task.checkedCount} / {task.itemCount} poz.
              </span>
            )}
            {task.sourceLabel && <span>z: {task.sourceLabel}</span>}
          </span>
        </button>
        <span className={cn('shrink-0 rounded-full px-2 text-xs leading-5 font-medium', status.badge)}>
          {status.label}
        </span>
        <ChevronDownIcon
          aria-hidden
          className={cn(
            'mt-0.5 size-4 shrink-0 text-muted-foreground transition-transform duration-150 motion-reduce:transition-none',
            open && 'rotate-180',
          )}
        />
      </div>
      {open && <TaskDetails storeId={storeId} task={task} closed={closed} />}
    </li>
  )
}

function TaskDetails({ storeId, task, closed }: { storeId: string; task: StoreTask; closed: boolean }) {
  const actions = useTaskActions(storeId)
  const [comment, setComment] = useState('')
  const [editing, setEditing] = useState(false)
  const [historyOpen, setHistoryOpen] = useState(false)
  const history = useTaskHistory(storeId, task.id, historyOpen)
  const canCheck = (task.canComplete || task.canManage) && task.status !== 'ACCEPTED'
  const fail = (fallback: string) => (error: unknown) => toast.error(taskErrorMessage(error, fallback))

  const remove = () =>
    actions.remove.mutate(task.id, {
      onSuccess: () =>
        toast('Usunięto zadanie', {
          description: task.title,
          action: { label: 'Przywróć', onClick: () => actions.restore.mutate(task.id) },
        }),
      onError: fail('Nie udało się usunąć zadania'),
    })

  return (
    <div className="grid gap-3 px-2 pb-3 pl-9 text-sm">
      {task.description && <p className="whitespace-pre-line">{task.description}</p>}
      {task.returnComment && task.status === 'OPEN' && (
        <p className="rounded-md bg-amber-200 px-2 py-1 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100">
          Do poprawy: {task.returnComment}
        </p>
      )}
      {task.doneComment && task.status !== 'OPEN' && (
        <p className="text-muted-foreground">
          {task.doneBy?.displayName ?? 'Wykonawca'}: {task.doneComment}
        </p>
      )}
      {task.sourceUrl && (
        <Link
          to={inStore(task.sourceUrl, storeId)}
          className="inline-flex w-fit items-center gap-1.5 font-medium text-primary underline-offset-4 hover:underline"
        >
          <ExternalLinkIcon className="size-4" />
          Otwórz miejsce zlecenia{task.sourceLabel ? `: ${task.sourceLabel}` : ''}
        </Link>
      )}

      {task.items.length > 0 && (
        <ul className="max-h-80 divide-y overflow-y-auto rounded-lg border bg-card">
          {task.items.map((item) => (
            <li key={item.id}>
              <label
                className={cn(
                  'flex items-start gap-2.5 px-3 py-1.5',
                  canCheck ? 'cursor-pointer hover:bg-accent' : 'cursor-default',
                )}
              >
                <input
                  type="checkbox"
                  checked={item.checked}
                  disabled={!canCheck}
                  onChange={(event) =>
                    actions.checkItem.mutate(
                      { id: task.id, itemId: item.id, checked: event.target.checked },
                      { onError: fail('Nie udało się odhaczyć pozycji') },
                    )
                  }
                  className="mt-0.5 size-4 shrink-0 accent-emerald-600"
                />
                <span className="min-w-0 flex-1">
                  <span className={cn('block', item.checked && 'text-muted-foreground line-through')}>{item.name}</span>
                  <span className="block text-xs text-muted-foreground">
                    {[item.itm8 ? `ITM8 ${item.itm8.replace(/^0+/, '')}` : null, item.ean, item.details]
                      .filter(Boolean)
                      .join(' · ')}
                    {item.checked && item.checkedByName ? ` · odhaczył(a) ${item.checkedByName}` : ''}
                  </span>
                </span>
              </label>
            </li>
          ))}
        </ul>
      )}

      <TaskAttachments storeId={storeId} task={task} />

      {(task.canComplete && task.status === 'OPEN') || (task.canManage && task.status !== 'OPEN') ? (
        <TextareaField
          label={
            task.canManage && task.status !== 'OPEN'
              ? 'Komentarz do odesłania (opcjonalnie)'
              : 'Komentarz (opcjonalnie)'
          }
          rows={2}
          value={comment}
          onChange={(event) => setComment(event.target.value)}
        />
      ) : null}

      <div className="flex flex-wrap items-center gap-2">
        {task.canComplete && task.status === 'OPEN' && (
          <Button
            size="sm"
            loading={actions.done.isPending}
            onClick={() =>
              actions.done.mutate(
                { id: task.id, comment: comment.trim() || null },
                { onSuccess: () => toast.success('Oznaczono jako wykonane'), onError: fail('Nie udało się zapisać') },
              )
            }
          >
            <CheckCircle2Icon />
            Wykonane
          </Button>
        )}
        {task.canComplete && task.status === 'DONE' && (
          <Button
            size="sm"
            variant="outline"
            onClick={() => actions.undone.mutate(task.id, { onError: fail('Nie udało się cofnąć') })}
          >
            <UndoIcon />
            Cofnij wykonanie
          </Button>
        )}
        {task.canManage && task.status === 'DONE' && (
          <Button
            size="sm"
            loading={actions.accept.isPending}
            onClick={() =>
              actions.accept.mutate(task.id, {
                onSuccess: () => toast.success('Przyjęto zadanie'),
                onError: fail('Nie udało się przyjąć'),
              })
            }
          >
            <CheckCircle2Icon />
            Przyjmij
          </Button>
        )}
        {task.canManage && closed && (
          <Button
            size="sm"
            variant="outline"
            loading={actions.sendBack.isPending}
            onClick={() =>
              actions.sendBack.mutate(
                { id: task.id, comment: comment.trim() || null },
                { onSuccess: () => toast('Odesłano do poprawy'), onError: fail('Nie udało się odesłać') },
              )
            }
          >
            <RotateCcwIcon />
            Odeślij do poprawy
          </Button>
        )}
        {task.canManage && task.status === 'OPEN' && (
          <Button
            size="sm"
            variant="outline"
            onClick={() =>
              actions.accept.mutate(task.id, {
                onSuccess: () => toast.success('Zamknięto zadanie'),
                onError: fail('Nie udało się zamknąć'),
              })
            }
          >
            <CheckCircle2Icon />
            Zamknij jako wykonane
          </Button>
        )}
        <span className="flex-1" />
        <Button size="sm" variant="ghost" onClick={() => setHistoryOpen((value) => !value)}>
          <HistoryIcon />
          Historia
        </Button>
        {task.canManage && (
          <>
            <Button size="sm" variant="ghost" onClick={() => setEditing(true)}>
              <PencilIcon />
              Zmień
            </Button>
            <Button size="sm" variant="ghost" onClick={remove} aria-label="Usuń zadanie">
              <Trash2Icon />
            </Button>
          </>
        )}
      </div>

      {historyOpen &&
        (history.isPending ? (
          <LoadingPanel className="h-16 rounded-lg" />
        ) : (
          <ol className="grid gap-0.5 text-xs text-muted-foreground">
            {(history.data ?? []).map((event) => (
              <li key={event.id}>
                {new Date(event.at).toLocaleString('pl-PL', { dateStyle: 'short', timeStyle: 'short' })} ·{' '}
                {TASK_EVENTS[event.kind]}
                {event.comment ? `: ${event.comment}` : ''}
                {event.userName ? ` (${event.userName})` : ''}
              </li>
            ))}
          </ol>
        ))}

      {editing && <TaskDialog storeId={storeId} open={editing} onOpenChange={setEditing} task={task} />}
    </div>
  )
}
