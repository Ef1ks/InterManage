import { useState } from 'react'
import { FileCodeIcon } from 'lucide-react'
import { toast } from 'sonner'

import { SavedFolder } from '@/components/SavedFolder'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { ApiError, downloadPostedFile, fetchPostedFile } from '@/lib/api'
import { ensureFolderAccess, type SavedFolderSpec, saveToFolder } from '@/lib/saved-folders'

import type { ExportPayload } from './api'
import type { DataEntryRow } from './rows'

/** Pliki XML faktur mają własny folder, wybrany raz w przeglądarce. */
const XML_FOLDER: SavedFolderSpec = { key: 'data-entry-xml', label: 'Plik XML faktury' }

export type XmlProblems = {
  invalidEan: DataEntryRow[]
  tooLong: DataEntryRow[]
  noSubfamily: DataEntryRow[]
}

type Props = {
  storeId: string
  payload: ExportPayload | null
  problems: XmlProblems
  open: boolean
  onOpenChange: (open: boolean) => void
  onGoTo: (row: DataEntryRow, key: 'ean' | 'name' | 'subfamily') => void
}

/**
 * Zapis tabeli do pliku XML faktury dla programu sklepu. Plik trafia do folderu wybranego raz w przeglądarce; bez
 * folderu (albo w przeglądarce bez tej funkcji) się pobiera. Braki tabeli są nad przyciskiem, ale nie blokują zapisu.
 */
export function XmlSaveDialog({ storeId, payload, problems, open, onOpenChange, onGoTo }: Props) {
  const [saving, setSaving] = useState(false)
  const count = payload?.rows.length ?? 0

  const save = async () => {
    if (!payload) return
    setSaving(true)
    try {
      // Zgoda na zapis w folderze tylko zaraz po kliknięciu, zanim cokolwiek pójdzie do serwera.
      const folderReady = await ensureFolderAccess(XML_FOLDER.key)
      const path = `/stores/${storeId}/data-entry/export/xml`
      if (!folderReady) {
        await downloadPostedFile(path, payload, 'faktura.xml')
        toast.success('Pobrano plik XML', { description: 'Folder na pliki XML nie jest wybrany w tej przeglądarce.' })
      } else {
        const { fileName, blob } = await fetchPostedFile(path, payload, 'faktura.xml')
        await saveToFolder(XML_FOLDER.key, fileName, blob)
        toast.success(`Zapisano ${fileName}`)
      }
      onOpenChange(false)
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać pliku XML')
    } finally {
      setSaving(false)
    }
  }

  const issues = [
    {
      rows: problems.invalidEan,
      text: 'Błędny EAN nowego artykułu (zła długość albo cyfra kontrolna)',
      key: 'ean' as const,
      show: 'Pokaż pierwszy',
    },
    { rows: problems.tooLong, text: 'Nazwa dłuższa niż 30 znaków', key: 'name' as const, show: 'Pokaż pierwszą' },
    { rows: problems.noSubfamily, text: 'Nowy artykuł bez podrodziny', key: 'subfamily' as const, show: 'Pokaż pierwszy' },
  ].filter((issue) => issue.rows.length > 0)

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Zapisz plik XML</DialogTitle>
          <DialogDescription>
            Pozycje z tabeli: {count} (bez kaucji i dostawy). Artykuły bez EAN-u trafią do pliku z kodem 0000000000000.
          </DialogDescription>
        </DialogHeader>

        {issues.length > 0 && (
          <div className="rounded-xl border border-amber-300 bg-amber-100 px-4 py-3 text-sm text-amber-950 dark:border-amber-500/40 dark:bg-amber-950/60 dark:text-amber-100">
            <p className="font-medium">Tabela ma braki</p>
            <ul className="mt-1.5 space-y-1.5">
              {issues.map((issue) => (
                <li key={issue.key}>
                  {issue.text}: {issue.rows.length}.{' '}
                  <button
                    type="button"
                    className="font-medium underline underline-offset-3"
                    onClick={() => onGoTo(issue.rows[0], issue.key)}
                  >
                    {issue.show}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        )}

        <SavedFolder folder={XML_FOLDER} />

        <Button size="xl" loading={saving} disabled={count === 0} onClick={save}>
          <FileCodeIcon />
          {issues.length > 0 ? 'Zapisz mimo to' : 'Zapisz plik XML'}
        </Button>
      </DialogContent>
    </Dialog>
  )
}
