import { FileSearchIcon, XIcon } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'

import { FileDropZone } from '@/components/FileDropZone'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import type { DataEntryRow } from '@/features/data-entry/rows'
import { formatDocumentDate } from '@/features/documents/format'
import { ApiError } from '@/lib/api'
import type { TemporaryNote } from '@/lib/types'

import { useReadTemporaryNote } from './api'
import { findDifferences } from './differences'
import { DifferencesView } from './DifferencesView'

type Props = {
  storeId: string
  /** Wszystkie wiersze tabeli (bez względu na filtry). */
  rows: DataEntryRow[]
  open: boolean
  onOpenChange: (open: boolean) => void
}

const lineCount = (count: number) =>
  count === 1
    ? 'linia'
    : count % 10 >= 2 && count % 10 <= 4 && (count % 100 < 12 || count % 100 > 14)
      ? 'linie'
      : 'linii'

/**
 * „Znajdź różnice”: tabela dostawy z bazy kontra to, co wprowadzono do programu sklepu (tymczasowe bony dostawy
 * z Mercalys, osobno spożywka 538 i alkohol 88110, ile plików trzeba). Pliki zostają w oknie do zamknięcia strony,
 * a wynik liczy się od nowa z bieżącej tabeli po każdym kliknięciu.
 */
export function DifferencesDialog({ storeId, rows, open, onOpenChange }: Props) {
  const [notes, setNotes] = useState<TemporaryNote[]>([])
  const [result, setResult] = useState<ReturnType<typeof findDifferences> | null>(null)
  const read = useReadTemporaryNote(storeId)

  const addFile = (file: File) =>
    read.mutate(file, {
      onSuccess: (note) => {
        setNotes((current) => [...current.filter((existing) => existing.fileName !== note.fileName), note])
        setResult(null)
      },
      onError: (error) => {
        const rowErrors =
          error instanceof ApiError && Array.isArray(error.problem.rowErrors)
            ? (error.problem.rowErrors as string[])
            : []
        toast.error(error instanceof ApiError ? error.message : 'Nie udało się odczytać pliku', {
          description: rowErrors.length > 0 ? rowErrors.slice(0, 5).join('\n') : undefined,
          duration: 10_000,
        })
      },
    })

  const remove = (fileName: string) => {
    setNotes((current) => current.filter((note) => note.fileName !== fileName))
    setResult(null)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-5xl">
        <DialogHeader>
          <DialogTitle>Znajdź różnice</DialogTitle>
          <DialogDescription>
            Porównuje tabelę ({rows.length} wierszy) z tym, co jest w programie sklepu: wgraj tymczasowe bony dostawy z
            Mercalys, osobno dla spożywki (538) i alkoholu (88110). Artykuły z ilością 0, których nie ma w programie,
            pomijamy.
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-2">
          {notes.map((note) => (
            <div
              key={note.fileName}
              className="flex items-center gap-3 rounded-lg border bg-card px-3 py-2 text-sm tabular-nums"
            >
              <div className="min-w-0 flex-1">
                <p className="truncate font-medium">{note.fileName}</p>
                <p className="text-xs text-muted-foreground">
                  {[
                    note.supplierCode && `Dostawca ${note.supplierCode}`,
                    note.number && `dostawa ${note.number}`,
                    note.createdAt && formatDocumentDate(note.createdAt),
                    `${note.lines.length} ${lineCount(note.lines.length)}`,
                  ]
                    .filter(Boolean)
                    .join(' · ')}
                </p>
              </div>
              <Button
                variant="ghost"
                size="icon-sm"
                onClick={() => remove(note.fileName)}
                aria-label={`Usuń plik ${note.fileName}`}
              >
                <XIcon />
              </Button>
            </div>
          ))}
          <FileDropZone
            className="min-h-24 py-4"
            accept={['.xlsx', '.xls', '.xlsm']}
            pendingFile={read.isPending ? (read.variables ?? undefined) : undefined}
            onFile={addFile}
            onReject={(fileName) => toast.error(`${fileName} to nie plik Excela`)}
            title={notes.length === 0 ? 'Przeciągnij tymczasowy bon dostawy z Mercalys' : 'Dodaj kolejny bon'}
            hint="albo kliknij, żeby wybrać plik (.xlsx, .xls)"
          />
        </div>

        {result && <DifferencesView result={result} />}

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Zamknij
          </Button>
          <Button
            disabled={notes.length === 0 || read.isPending}
            onClick={() => setResult(findDifferences(rows, notes))}
          >
            <FileSearchIcon />
            Znajdź różnice
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
