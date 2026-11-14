import { FileOutputIcon, TriangleAlertIcon } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'

import { CopyButton } from '@/components/CopyButton'
import { SavedFolder } from '@/components/SavedFolder'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { formatDocumentDate } from '@/features/documents/format'
import { ApiError, saveBlob } from '@/lib/api'
import { ensureFolderAccess, type SavedFolderSpec, saveToFolder } from '@/lib/saved-folders'

import { PRO_FOLDER, type ProFile, proContent } from './leaflet-rows'

type Props = {
  files: ProFile[]
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Teksty okna, gdy pliki nie są z gazetki. */
  copy?: typeof LEAFLET_COPY
  /** Ostrzeżenie nad tabelą plików. */
  notice?: string
  /** Plik etykiet zapisywany razem z plikami .PRO; {@code save} zwraca opis do komunikatu. */
  labels?: { folder: SavedFolderSpec; save: (files: ProFile[], folderReady: boolean) => Promise<string> }
}

const LEAFLET_COPY = {
  description:
    'Z pozycji bez kodu MEA (puste albo DDD), jeden plik na każdy okres od-do. W pliku są EAN-y artykułów z kartoteki i ceny z gazetki; artykuł z kilkoma EAN-ami w gazetce trafia do pliku raz. Przy wczytaniu pliku w programie sklepu wpisz jego kod taryfy.',
  empty: 'Gazetka nie ma pozycji do opracowania.',
  withoutPrice: 'bez ceny (rabat bez ceny z kartoteki)',
}

const articles = (count: number) =>
  `${count} ${count === 1 ? 'artykuł' : count % 10 >= 2 && count % 10 <= 4 && (count % 100 < 12 || count % 100 > 14) ? 'artykuły' : 'artykułów'}`

/**
 * Pliki cen promocji .PRO z pozycji do opracowania: jeden okres = jeden plik, z kodem taryfy do wpisania przy
 * wczytaniu w programie sklepu. Pliki zapisują się w folderze wybranym raz w przeglądarce (N:\ALOA\PRO\IMPORT);
 * bez niego się pobierają. Z {@code labels} zapis tworzy też plik etykiet; jego błąd nie cofa zapisanych plików .PRO.
 */
export function ProFilesDialog({ files, open, onOpenChange, copy = LEAFLET_COPY, notice, labels }: Props) {
  const [saving, setSaving] = useState(false)
  const ready = files.filter((file) => file.lines.length > 0)

  const save = async () => {
    setSaving(true)
    try {
      // Zgoda na zapis w folderze tylko zaraz po kliknięciu.
      const folderReady = await ensureFolderAccess(PRO_FOLDER.key)
      const labelsFolderReady = labels ? await ensureFolderAccess(labels.folder.key) : false
      for (const file of ready) {
        if (folderReady) await saveToFolder(PRO_FOLDER.key, file.fileName, proContent(file))
        else saveBlob(proContent(file), file.fileName)
      }
      toast.success(`Zapisano ceny promocji: ${ready.length}`, {
        description: `${ready.map((file) => `kod taryfy ${file.tariff}`).join(', ')}${
          folderReady ? '' : ' · pobrane: folder nie jest wybrany w tej przeglądarce'
        }`,
        duration: 10_000,
      })
      if (labels) {
        try {
          toast.success('Utworzono etykiety', { description: await labels.save(ready, labelsFolderReady) })
        } catch (error) {
          toast.error(error instanceof ApiError ? error.message : 'Nie udało się utworzyć etykiet')
        }
      }
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Nie udało się zapisać cen promocji')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-4xl">
        <DialogHeader>
          <DialogTitle>Ceny promocji</DialogTitle>
          <DialogDescription>{copy.description}</DialogDescription>
        </DialogHeader>

        {notice && (
          <p className="flex items-start gap-1.5 text-sm font-medium text-amber-900 dark:text-amber-200">
            <TriangleAlertIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
            <span>{notice}</span>
          </p>
        )}

        {files.length === 0 ? (
          <p className="text-sm text-muted-foreground">{copy.empty}</p>
        ) : (
          <div className="overflow-x-auto rounded-lg border">
            <table className="w-full text-sm">
              <thead className="bg-muted text-left text-xs text-muted-foreground">
                <tr>
                  <th className="px-3 py-2 font-medium">Kod taryfy</th>
                  <th className="px-3 py-2 font-medium">Okres</th>
                  <th className="px-3 py-2 font-medium">Plik</th>
                  <th className="px-3 py-2 text-right font-medium">W pliku</th>
                  <th className="px-3 py-2 font-medium">Pominięte</th>
                </tr>
              </thead>
              <tbody>
                {files.map((file) => (
                  <tr key={file.fileName} className="border-t align-top">
                    <td className="px-3 py-2">
                      <span className="inline-flex items-center gap-1 font-semibold tabular-nums">
                        {file.tariff}
                        <CopyButton value={file.tariff} label={`Kopiuj kod taryfy ${file.tariff}`} />
                      </span>
                    </td>
                    <td className="px-3 py-2 whitespace-nowrap tabular-nums">
                      <span className="inline-flex items-center gap-1">
                        {formatDocumentDate(file.dateFrom)} – {formatDocumentDate(file.dateTo)}
                        <CopyButton
                          value={`${formatDocumentDate(file.dateFrom)} - ${formatDocumentDate(file.dateTo)}`}
                          label={`Kopiuj okres ${formatDocumentDate(file.dateFrom)} - ${formatDocumentDate(file.dateTo)}`}
                        />
                      </span>
                    </td>
                    <td className="px-3 py-2 font-medium">{file.fileName}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{articles(file.lines.length)}</td>
                    <td className="px-3 py-2 text-xs">
                      <Skipped label="bez artykułu z kartoteki" names={file.withoutProduct.map((line) => line.name)} />
                      <Skipped label={copy.withoutPrice} names={file.withoutPrice.map((line) => line.name)} />
                      <Skipped label="ten sam artykuł w różnych cenach, w pliku pierwsza" names={file.conflicts} />
                      {file.withoutProduct.length + file.withoutPrice.length + file.conflicts.length === 0 && (
                        <span className="text-muted-foreground">–</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div className="flex flex-wrap items-center gap-3">
          <Button size="xl" loading={saving} disabled={ready.length === 0} onClick={save}>
            <FileOutputIcon />
            Zapisz ceny promocji{labels ? ' i etykiety' : ''} ({ready.length})
          </Button>
          <SavedFolder folder={PRO_FOLDER} />
          {labels && <SavedFolder folder={labels.folder} />}
        </div>
      </DialogContent>
    </Dialog>
  )
}

function Skipped({ label, names }: { label: string; names: string[] }) {
  if (names.length === 0) return null
  return (
    <details className="mb-1">
      <summary className="flex cursor-pointer items-center gap-1 font-medium text-amber-900 dark:text-amber-200">
        <TriangleAlertIcon className="size-3.5" />
        {names.length} {label}
      </summary>
      <ul className="mt-1 ml-5 list-disc text-muted-foreground">
        {names.map((name, index) => (
          <li key={`${name}-${index}`}>{name}</li>
        ))}
      </ul>
    </details>
  )
}
