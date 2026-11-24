import { CircleCheckIcon, LoaderCircleIcon, MonitorPlayIcon, MoreVerticalIcon, TriangleAlertIcon } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { ApiError } from '@/lib/api'
import type { AgentComputer, AgentMacroJob } from '@/lib/types'
import { cn } from '@/lib/utils'

import { cancelMacro, isActive, type MacroRequest, runMacro } from './api'

/** Makra, których podsumowanie warto pokazać także po udanym przebiegu. */
const SHOWN_RESULTS: AgentMacroJob['kind'][] = ['MEA_CODES', 'BASE_RECEIVE', 'BASE_CLOSE']

/** Stan zlecenia makra w oknie: jedno naraz, wynik zostaje do następnego zlecenia. */
export function useRemoteMacro(storeId: string) {
  const [job, setJob] = useState<AgentMacroJob | null>(null)
  const [starting, setStarting] = useState(false)

  /** Zleca makro i zwraca wynik, a przy błędzie żądania (pokazanym w powiadomieniu) `null`. */
  const run = async (request: MacroRequest) => {
    setStarting(true)
    setJob(null)
    try {
      return await runMacro(storeId, request, (next) => {
        setStarting(false)
        setJob(next)
      })
    } catch (error) {
      setJob(null)
      toast.error('Nie udało się zlecić makra', {
        description: error instanceof ApiError ? error.message : 'Brak połączenia z serwerem',
      })
      return null
    } finally {
      setStarting(false)
    }
  }

  const cancel = async () => {
    if (!job) return
    try {
      setJob(await cancelMacro(storeId, job.id))
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się anulować makra')
    }
  }

  return { job, busy: starting || (job !== null && isActive(job)), run, cancel }
}

type ButtonProps = {
  computers: AgentComputer[]
  label: string
  disabled?: boolean
  busy: boolean
  size?: 'default' | 'xl'
  onRun: (agentId?: string) => void
}

/** Przycisk zlecenia; wybór konkretnego komputera jest schowany pod kropkami, bo zwykle wybiera serwer. */
export function RemoteMacroButton({ computers, label, disabled, busy, size = 'default', onRun }: ButtonProps) {
  return (
    <div className="flex items-center gap-1">
      <Button size={size} disabled={disabled} loading={busy} onClick={() => onRun()}>
        <MonitorPlayIcon />
        {label}
      </Button>
      <DropdownMenu>
        <DropdownMenuTrigger
          render={
            <Button
              variant="ghost"
              size="icon"
              disabled={disabled || busy}
              aria-label="Wybierz komputer"
              title="Wybierz komputer"
            />
          }
        >
          <MoreVerticalIcon />
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-80">
          {computers.map((computer) => (
            <DropdownMenuItem
              key={computer.id}
              disabled={!computer.available}
              onClick={() => onRun(computer.id)}
              className="flex-col items-start gap-0"
            >
              <span className="font-medium">
                {computer.name}
                {computer.machineName && (
                  <span className="font-normal text-muted-foreground"> · {computer.machineName}</span>
                )}
              </span>
              <span className="text-xs text-muted-foreground">
                {computer.reason ?? (computer.free ? 'wolny' : 'ktoś na nim pracuje')}
              </span>
            </DropdownMenuItem>
          ))}
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  )
}

/** Przebieg zlecenia: gdzie czeka na start, gdzie działa, kto odmówił i wynik. */
export function RemoteMacroStatus({ job, onCancel }: { job: AgentMacroJob; onCancel: () => void }) {
  const failed = job.status === 'FAILED' || job.status === 'NO_COMPUTER'
  const done = job.status === 'DONE'
  const texts: Record<AgentMacroJob['status'], string> = {
    OFFERED: `Odliczanie na komputerze ${job.computer ?? ''}…`,
    RUNNING: `Makro działa na komputerze ${job.computer ?? ''}`,
    DONE: `Makro zakończone na komputerze ${job.computer ?? ''}`,
    FAILED: `Makro zatrzymane na komputerze ${job.computer ?? ''}`,
    NO_COMPUTER: 'Żaden komputer nie jest wolny',
    CANCELLED: 'Makro anulowane',
  }
  return (
    <div
      role="status"
      className={cn(
        'flex items-start gap-3 rounded-lg px-3 py-2.5 text-sm',
        failed
          ? 'bg-red-200 text-red-950 dark:bg-red-950 dark:text-red-100'
          : done
            ? 'bg-emerald-200 text-emerald-950 dark:bg-emerald-950 dark:text-emerald-100'
            : job.status === 'CANCELLED'
              ? 'bg-muted'
              : 'bg-sky-200 text-sky-950 dark:bg-sky-950 dark:text-sky-100',
      )}
    >
      {isActive(job) ? (
        <LoaderCircleIcon className="mt-0.5 size-4 shrink-0 animate-spin motion-reduce:animate-none" />
      ) : done ? (
        <CircleCheckIcon className="mt-0.5 size-4 shrink-0" />
      ) : (
        <TriangleAlertIcon className="mt-0.5 size-4 shrink-0" />
      )}
      <div className="grid min-w-0 flex-1 gap-0.5">
        <p className="font-medium">{texts[job.status]}</p>
        {isActive(job) && job.declined.length > 0 && (
          <p>Wcześniej anulowane albo bez odpowiedzi: {job.declined.join(', ')}</p>
        )}
        {(failed || (done && SHOWN_RESULTS.includes(job.kind))) && job.message && (
          <p className="max-h-32 overflow-auto break-words whitespace-pre-line">{job.message}</p>
        )}
      </div>
      {job.status === 'OFFERED' && (
        <Button variant="outline" size="sm" onClick={onCancel}>
          Anuluj
        </Button>
      )}
    </div>
  )
}
