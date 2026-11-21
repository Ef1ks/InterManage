import { BotIcon } from 'lucide-react'

import { Card } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { REPORT_TITLES } from '@/features/imports/import-sources'
import type { AgentReportFile } from '@/lib/types'
import { cn } from '@/lib/utils'

const dateTimeFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'short' })

const STATUS: Record<AgentReportFile['status'], { label: string; className: string }> = {
  UPLOADING: {
    label: 'Wysyła',
    className: 'bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-100',
  },
  IMPORTING: {
    label: 'Wgrywa',
    className: 'bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-100',
  },
  IMPORTED: {
    label: 'Wgrany',
    className: 'bg-emerald-200 text-emerald-950 dark:bg-emerald-900/70 dark:text-emerald-100',
  },
  FAILED: {
    label: 'Błąd',
    className: 'bg-red-200 text-red-950 dark:bg-red-900/70 dark:text-red-100',
  },
  INTERRUPTED: {
    label: 'Przerwany',
    className: 'bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100',
  },
}

/** Krótki opis ostatniej próby agenta dla karty raportu (zamiast wyniku „Odśwież dane”). */
export function agentNote(file: AgentReportFile | undefined): { tone: 'muted' | 'warning'; text: string } {
  if (!file) return { tone: 'muted', text: 'Wgrywa agent sklepu: sam, gdy w folderze pojawi się nowy plik.' }
  const at = dateTimeFormat.format(new Date(file.finishedAt ?? file.startedAt))
  switch (file.status) {
    case 'UPLOADING':
      return { tone: 'muted', text: `Agent wysyła ${file.fileName}…` }
    case 'IMPORTING':
      return { tone: 'muted', text: `Agent wgrywa ${file.fileName}…` }
    case 'IMPORTED':
      return { tone: 'muted', text: `Agent wgrał ${file.fileName} (${at}).` }
    case 'FAILED':
      return { tone: 'warning', text: `Agent nie wgrał ${file.fileName}: ${file.error ?? 'błąd importu'}` }
    case 'INTERRUPTED':
      return { tone: 'warning', text: `${file.fileName}: ${file.error ?? 'przerwane'}` }
  }
}

/** Ostatnie raporty wgrane przez agenta sklepu, z wynikiem i usunięciem z folderu. */
export function AgentReportFiles({ files }: { files: AgentReportFile[] }) {
  return (
    <Card className="mb-10 gap-3 p-5">
      <div className="flex items-center gap-2">
        <BotIcon aria-hidden className="size-4 text-muted-foreground" />
        <h2 className="font-heading text-base font-semibold">Raporty wgrane przez agenta sklepu</h2>
      </div>
      <p className="text-sm text-muted-foreground">
        Agent pilnuje folderów raportów i sam wgrywa nowe pliki. Wgrany raport usuwa z folderu następnego dnia.
      </p>
      {files.length === 0 ? (
        <p className="text-sm text-muted-foreground">Agent nie wgrał jeszcze żadnego raportu.</p>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Raport</TableHead>
              <TableHead>Plik</TableHead>
              <TableHead>Wynik</TableHead>
              <TableHead>Kiedy</TableHead>
              <TableHead>Z folderu</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {files.map((file) => {
              const status = STATUS[file.status]
              return (
                <TableRow key={file.id}>
                  <TableCell className="whitespace-nowrap">{REPORT_TITLES[file.kind]}</TableCell>
                  <TableCell className="max-w-72 truncate" title={file.fileName}>
                    {file.fileName}
                  </TableCell>
                  <TableCell>
                    <span className={cn('rounded-md px-2 py-0.5 text-xs font-medium', status.className)}>
                      {status.label}
                    </span>
                    {file.error && (
                      <p className="mt-1 max-w-96 text-xs whitespace-normal text-muted-foreground">{file.error}</p>
                    )}
                  </TableCell>
                  <TableCell className="whitespace-nowrap tabular-nums">
                    {dateTimeFormat.format(new Date(file.finishedAt ?? file.startedAt))}
                  </TableCell>
                  <TableCell className="whitespace-nowrap text-muted-foreground">
                    {file.removedFromDiskAt
                      ? `usunięty ${dateTimeFormat.format(new Date(file.removedFromDiskAt))}`
                      : '—'}
                  </TableCell>
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      )}
    </Card>
  )
}
