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
import { formatDocumentDate, formatPrice, formatQuantity } from '@/features/documents/format'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { ApiError } from '@/lib/api'
import type { TemporaryNote } from '@/lib/types'
import { cn } from '@/lib/utils'

import { useReadTemporaryNote } from './api'
import { DIFFERENCE_LABELS, type DifferenceKind, findDifferences } from './differences'

type Props = {
  storeId: string
  /** Wszystkie wiersze tabeli (bez względu na filtry). */
  rows: DataEntryRow[]
  open: boolean
  onOpenChange: (open: boolean) => void
}

const KIND_STYLES: Record<DifferenceKind, string> = {
  missing: 'bg-red-300 text-red-950 dark:bg-red-800 dark:text-red-50',
  extra: 'bg-violet-300 text-violet-950 dark:bg-violet-800 dark:text-violet-50',
  quantity: 'bg-amber-300 text-amber-950 dark:bg-amber-800 dark:text-amber-50',
  price: 'bg-sky-300 text-sky-950 dark:bg-sky-800 dark:text-sky-50',
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

  const counts = new Map<DifferenceKind, number>()
  for (const difference of result?.differences ?? [])
    for (const kind of difference.kinds) counts.set(kind, (counts.get(kind) ?? 0) + 1)

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

        {result && (
          <div className="flex min-h-0 flex-col gap-3">
            {result.differences.length === 0 ? (
              <p className="rounded-lg bg-emerald-200 px-3 py-2 text-sm font-medium text-emerald-950 dark:bg-emerald-900 dark:text-emerald-50">
                Brak różnic: {result.compared} artykułów zgadza się z programem.
              </p>
            ) : (
              <>
                <div className="flex flex-wrap items-center gap-1.5 text-sm">
                  <span className="mr-1 font-medium">Różnice: {result.differences.length}</span>
                  {[...counts.entries()].map(([kind, count]) => (
                    <span key={kind} className={cn('rounded-md px-2 py-0.5 font-medium', KIND_STYLES[kind])}>
                      {DIFFERENCE_LABELS[kind]} {count}
                    </span>
                  ))}
                </div>
                <div className="max-h-[50vh] overflow-auto rounded-lg border">
                  <table className="w-full text-sm tabular-nums">
                    <thead className="sticky top-0 bg-muted text-left text-xs text-muted-foreground">
                      <tr>
                        <th className="px-2 py-1.5 font-medium">Rodzaj</th>
                        <th className="px-2 py-1.5 font-medium">ITM8</th>
                        <th className="px-2 py-1.5 font-medium">EAN</th>
                        <th className="px-2 py-1.5 font-medium">Artykuł</th>
                        <th className="px-2 py-1.5 font-medium">Nośnik</th>
                        <th className="px-2 py-1.5 text-right font-medium">W tabeli</th>
                        <th className="px-2 py-1.5 text-right font-medium">W programie</th>
                        <th className="px-2 py-1.5 text-right font-medium">Różnica</th>
                        <th className="px-2 py-1.5 text-right font-medium">Cena w tabeli</th>
                        <th className="px-2 py-1.5 text-right font-medium">Cena w programie</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y">
                      {result.differences.map((difference) => {
                        const delta =
                          difference.programQuantity === null
                            ? 0
                            : difference.programQuantity - difference.sheetQuantity
                        const priceDiffers = difference.kinds.includes('price')
                        return (
                          <tr key={difference.key}>
                            <td className="px-2 py-1.5">
                              <span className="flex flex-wrap gap-1">
                                {difference.kinds.map((kind) => (
                                  <span
                                    key={kind}
                                    className={cn(
                                      'rounded px-1.5 text-xs font-medium whitespace-nowrap',
                                      KIND_STYLES[kind],
                                    )}
                                  >
                                    {DIFFERENCE_LABELS[kind]}
                                  </span>
                                ))}
                              </span>
                            </td>
                            <td className="px-2 py-1.5">{difference.itm8?.replace(/^0+/, '')}</td>
                            <td className="px-2 py-1.5">{difference.ean}</td>
                            <td className="max-w-64 px-2 py-1.5">
                              <span className="flex items-center gap-1">
                                <span className="min-w-0 truncate" title={difference.name}>
                                  {difference.name}
                                </span>
                                {difference.articleItm8 && (
                                  <ArticleStatsButton
                                    article={{
                                      itm8: difference.articleItm8,
                                      name: difference.name,
                                      ean: difference.ean,
                                    }}
                                  />
                                )}
                              </span>
                            </td>
                            <td className="px-2 py-1.5">{difference.carriers.join(', ')}</td>
                            <td className="px-2 py-1.5 text-right">{formatQuantity(difference.sheetQuantity)}</td>
                            <td className="px-2 py-1.5 text-right">
                              {difference.programQuantity === null ? '' : formatQuantity(difference.programQuantity)}
                            </td>
                            <td className="px-2 py-1.5 text-right font-medium">
                              {delta === 0 ? '' : `${delta > 0 ? '+' : ''}${formatQuantity(delta)}`}
                            </td>
                            <td className={cn('px-2 py-1.5 text-right', priceDiffers && 'font-medium')}>
                              {formatPrice(difference.sheetPrice)}
                            </td>
                            <td className={cn('px-2 py-1.5 text-right', priceDiffers && 'font-medium')}>
                              {formatPrice(difference.programPrice)}
                            </td>
                          </tr>
                        )
                      })}
                    </tbody>
                  </table>
                </div>
              </>
            )}
          </div>
        )}

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
