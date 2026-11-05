import { FileOutputIcon, TriangleAlertIcon } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'

import { CopyButton } from '@/components/CopyButton'
import { DateField, Form, FormActions } from '@/components/form'
import { SavedFolder } from '@/components/SavedFolder'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { formatDocumentDate } from '@/features/documents/format'
import { PRO_FOLDER, proContent, proFileName } from '@/features/leaflet/leaflet-rows'
import { saveBlob } from '@/lib/api'
import { todayIso } from '@/lib/dates'
import { ensureFolderAccess, saveToFolder } from '@/lib/saved-folders'

import { proLines } from './pro-file'
import type { DataEntryRow } from './rows'

const PREVIEW_LINES = 5

type Props = {
  rows: DataEntryRow[]
  /** Plik tylko z zaznaczonych wierszy (inaczej ze wszystkich artykułów tabeli). */
  selection: boolean
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * Plik cen promocji .PRO z tabeli: EAN i cena sprzedaży, jak z opracowania gazetki, w tym samym folderze
 * (N:\ALOA\PRO\IMPORT). Okres daje nazwę pliku; kod taryfy i okres wpisuje się przy wczytaniu w programie sklepu.
 */
export function ProFileDialog({ rows, selection, open, onOpenChange }: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-lg">
        <ProFileForm rows={rows} selection={selection} onDone={() => onOpenChange(false)} />
      </DialogContent>
    </Dialog>
  )
}

function ProFileForm({ rows, selection, onDone }: Omit<Props, 'open' | 'onOpenChange'> & { onDone: () => void }) {
  const [dateFrom, setDateFrom] = useState(todayIso())
  const [dateTo, setDateTo] = useState('')
  const [saving, setSaving] = useState(false)
  const { lines, withoutEan, withoutPrice, conflicts, sameAsCatalog } = proLines(rows)
  const periodError = dateTo && dateTo < dateFrom ? 'Koniec promocji nie może być przed początkiem' : undefined
  const ready = lines.length > 0 && dateTo !== '' && !periodError
  const fileName = ready ? proFileName(dateFrom, dateTo) : null
  const period = dateTo ? `${formatDocumentDate(dateFrom)} - ${formatDocumentDate(dateTo)}` : null

  const save = async () => {
    if (!fileName) return
    setSaving(true)
    try {
      // Zgoda na zapis w folderze tylko zaraz po kliknięciu.
      const folderReady = await ensureFolderAccess(PRO_FOLDER.key)
      if (folderReady) await saveToFolder(PRO_FOLDER.key, fileName, proContent({ lines }))
      else saveBlob(proContent({ lines }), fileName)
      toast.success(`Zapisano plik ${fileName}`, {
        description: `Cen: ${lines.length}, okres ${period}${
          folderReady ? '' : ' · pobrany: folder nie jest wybrany w tej przeglądarce'
        }`,
        duration: 10_000,
      })
      onDone()
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Nie udało się zapisać pliku .PRO')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Form
      onSubmit={(event) => {
        event.preventDefault()
        void save()
      }}
    >
      <DialogHeader>
        <DialogTitle>Plik cen promocji .PRO</DialogTitle>
        <DialogDescription>
          {selection ? 'Z zaznaczonych artykułów' : 'Ze wszystkich artykułów tabeli'}: EAN i cena z kolumny „Cena
          sprz.”. Przy wczytaniu w programie sklepu wpisz kod taryfy i okres.
        </DialogDescription>
      </DialogHeader>

      <div className="grid gap-3 sm:grid-cols-2">
        <DateField label="Początek promocji" value={dateFrom} onChange={setDateFrom} />
        <DateField label="Koniec promocji" value={dateTo} min={dateFrom} onChange={setDateTo} error={periodError} />
      </div>

      {lines.length > 0 && (
        <div className="overflow-hidden rounded-lg border text-sm">
          <ul className="divide-y">
            {lines.slice(0, PREVIEW_LINES).map((line) => (
              <li key={line.ean} className="flex items-center gap-3 px-3 py-1.5">
                <span className="tabular-nums text-muted-foreground">{line.ean}</span>
                <span className="min-w-0 flex-1 truncate">{line.name}</span>
                <span className="font-medium tabular-nums">{line.price.toFixed(2).replace('.', ',')} zł</span>
              </li>
            ))}
          </ul>
          {lines.length > PREVIEW_LINES && (
            <p className="border-t bg-muted px-3 py-1.5 text-xs text-muted-foreground">
              i {lines.length - PREVIEW_LINES} kolejnych, razem {lines.length}
            </p>
          )}
        </div>
      )}

      <div className="grid gap-1 empty:hidden">
        {lines.length === 0 && <Warning>Żaden artykuł nie ma EAN-u i ceny sprzedaży.</Warning>}
        {sameAsCatalog > 0 && (
          <Warning>Cena taka sama jak w kartotece: {sameAsCatalog}. Sprawdź ceny promocji.</Warning>
        )}
        {withoutPrice.length > 0 && <Warning>Bez ceny sprzedaży, nie trafią do pliku: {withoutPrice.length}.</Warning>}
        {withoutEan > 0 && <Warning>Bez EAN-u, nie trafią do pliku: {withoutEan}.</Warning>}
        {conflicts.length > 0 && (
          <Warning>Ten sam EAN w różnych cenach, w pliku pierwsza: {conflicts.join(', ')}.</Warning>
        )}
      </div>

      <SavedFolder folder={PRO_FOLDER} />

      <FormActions>
        {period && (
          <span className="mr-auto inline-flex items-center gap-1 text-sm tabular-nums">
            {period}
            <CopyButton value={period} label={`Kopiuj okres ${period}`} />
          </span>
        )}
        <Button type="submit" size="lg" loading={saving} disabled={!ready}>
          <FileOutputIcon />
          {fileName ? `Zapisz ${fileName}` : 'Zapisz plik'}
        </Button>
      </FormActions>
    </Form>
  )
}

function Warning({ children }: { children: React.ReactNode }) {
  return (
    <p className="flex items-start gap-1.5 text-sm font-medium text-amber-900 dark:text-amber-200">
      <TriangleAlertIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
      <span>{children}</span>
    </p>
  )
}
