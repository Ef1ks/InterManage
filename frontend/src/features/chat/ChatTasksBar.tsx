import { useState } from 'react'
import { CheckCircle2Icon, ChevronDownIcon, ListTodoIcon } from 'lucide-react'

import { useMyTasks } from '@/features/tasks/api'
import { TaskRow } from '@/features/tasks/TasksPanel'
import type { StoreTask } from '@/lib/types'
import { cn } from '@/lib/utils'

/**
 * Pasek na samej górze czatu: otwarte zadania dla mnie i dla całego sklepu. Zwinięty pokazuje liczby i pierwsze
 * tytuły, rozwinięty te same wiersze co pulpit (odhaczanie bez wychodzenia z czatu).
 */
export function ChatTasksBar({ storeId }: { storeId: string }) {
  const { data } = useMyTasks(storeId)
  const [open, setOpen] = useState(false)
  const active = (data ?? []).filter((task) => task.status !== 'ACCEPTED')
  const mine = active.filter((task) => !task.forStore)
  const store = active.filter((task) => task.forStore)
  const openMine = mine.filter((task) => task.status === 'OPEN').length
  const openStore = store.filter((task) => task.status === 'OPEN').length
  if (!data) return null

  const nothing = openMine === 0 && openStore === 0
  const titles = active
    .filter((task) => task.status === 'OPEN')
    .slice(0, 3)
    .map((task) => task.title)

  return (
    <section className="mb-2 rounded-xl border bg-card">
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        disabled={active.length === 0}
        className="flex w-full items-center gap-3 px-3 py-2 text-left disabled:cursor-default"
      >
        {nothing ? (
          <CheckCircle2Icon className="size-4 shrink-0 text-emerald-600 dark:text-emerald-400" />
        ) : (
          <ListTodoIcon className="size-4 shrink-0 text-primary" />
        )}
        <span className="flex shrink-0 items-center gap-1.5 text-sm font-medium">
          <Count value={openMine} label="dla Ciebie" />
          <Count value={openStore} label="dla sklepu" store />
        </span>
        <span className="min-w-0 flex-1 truncate text-sm text-muted-foreground">
          {nothing ? 'Nie masz zadań do zrobienia' : titles.join(' · ')}
        </span>
        {active.length > 0 && (
          <ChevronDownIcon
            aria-hidden
            className={cn(
              'size-4 shrink-0 text-muted-foreground transition-transform duration-150 motion-reduce:transition-none',
              open && 'rotate-180',
            )}
          />
        )}
      </button>
      {open && active.length > 0 && (
        <div className="grid max-h-[45svh] gap-3 overflow-y-auto border-t px-3 py-2 sm:grid-cols-2">
          <TaskColumn title="Dla Ciebie" storeId={storeId} tasks={mine} />
          <TaskColumn title="Dla całego sklepu" storeId={storeId} tasks={store} />
        </div>
      )}
    </section>
  )
}

function Count({ value, label, store = false }: { value: number; label: string; store?: boolean }) {
  return (
    <span
      className={cn(
        'rounded-full px-2 text-xs leading-5 tabular-nums',
        value === 0
          ? 'bg-muted text-muted-foreground'
          : store
            ? 'bg-violet-200 text-violet-950 dark:bg-violet-900/70 dark:text-violet-100'
            : 'bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-100',
      )}
    >
      {value} {label}
    </span>
  )
}

function TaskColumn({ title, storeId, tasks }: { title: string; storeId: string; tasks: StoreTask[] }) {
  return (
    <div>
      <h3 className="mb-1 text-xs font-semibold tracking-wide text-muted-foreground uppercase">{title}</h3>
      {tasks.length === 0 ? (
        <p className="py-1 text-sm text-muted-foreground">Nic do zrobienia</p>
      ) : (
        <ul className="-mx-2 grid gap-1">
          {tasks.map((task) => (
            <TaskRow key={task.id} storeId={storeId} task={task} showAssignee={false} />
          ))}
        </ul>
      )}
    </div>
  )
}
