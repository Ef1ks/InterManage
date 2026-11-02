import { useState } from 'react'
import { FolderIcon, FolderOpenIcon } from 'lucide-react'
import { toast } from 'sonner'

import { TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import {
  DAILY_REPORTS,
  MASK_EXAMPLES,
  readMasks,
  REPORT_EXTENSIONS,
  REPORT_TITLES,
  sourceFolderKey,
  writeMasks,
  type DailyReport,
  type SourceMasks,
} from '@/features/imports/import-sources'
import { ApiError } from '@/lib/api'
import { folderSavingSupported, useSavedFolder } from '@/lib/saved-folders'
import type { ImportGuide } from '@/lib/types'

/**
 * Ustawienia „Odśwież dane”: folder każdego codziennego raportu (wybrany raz w tej przeglądarce) i opcjonalna maska
 * nazwy pliku, gdy w folderze leżą też inne pliki. Listy dostawców i nomenklatur wgrywa się tylko ręcznie.
 */
export function ImportSourcesDialog({
  open,
  onOpenChange,
  storeId,
  guides,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  storeId: string
  guides: ImportGuide[]
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle className="text-lg">Ustawienia importu</DialogTitle>
          <DialogDescription>
            Wskaż, gdzie zapisujesz raporty z systemu sklepu. „Odśwież dane” wgra z każdego folderu najnowszy plik,
            którego jeszcze nie było. Ustawienia zostają w tej przeglądarce, osobno dla każdego sklepu.
          </DialogDescription>
        </DialogHeader>
        {folderSavingSupported ? (
          open && <SourceList storeId={storeId} guides={guides} />
        ) : (
          <p className="text-sm text-muted-foreground">
            Odczyt plików z folderu działa w Chrome i Edge. Tutaj raporty trzeba wgrywać ręcznie.
          </p>
        )}
        <div className="flex justify-end">
          <Button size="lg" onClick={() => onOpenChange(false)}>
            Gotowe
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}

function SourceList({ storeId, guides }: { storeId: string; guides: ImportGuide[] }) {
  const [masks, setMasks] = useState<SourceMasks>(() => readMasks(storeId))

  const changeMask = (kind: DailyReport, mask: string) => {
    const next = { ...masks, [kind]: mask }
    setMasks(next)
    writeMasks(storeId, next)
  }

  return (
    <ul className="grid gap-3">
      {DAILY_REPORTS.map((kind) => (
        <SourceRow
          key={kind}
          storeId={storeId}
          kind={kind}
          suggestedPath={guides.find((guide) => guide.kind === kind)?.folderPath ?? null}
          mask={masks[kind] ?? ''}
          onMaskChange={(mask) => changeMask(kind, mask)}
        />
      ))}
    </ul>
  )
}

function SourceRow({
  storeId,
  kind,
  suggestedPath,
  mask,
  onMaskChange,
}: {
  storeId: string
  kind: DailyReport
  suggestedPath: string | null
  mask: string
  onMaskChange: (mask: string) => void
}) {
  const { name, loaded, pick } = useSavedFolder(sourceFolderKey(storeId, kind), 'read')
  const [picking, setPicking] = useState(false)
  const title = REPORT_TITLES[kind]

  const choose = async () => {
    // Bez await: schowek nie zużywa gestu kliknięcia, którego potrzebuje okno wyboru folderu.
    if (suggestedPath) navigator.clipboard?.writeText(suggestedPath).catch(() => {})
    setPicking(true)
    try {
      const handle = await pick()
      if (handle) toast.success(`${title}: pliki z folderu ${handle.name}`)
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się wybrać folderu')
    } finally {
      setPicking(false)
    }
  }

  return (
    <li className="grid gap-3 rounded-xl border bg-muted/40 p-3 sm:grid-cols-[minmax(0,1fr)_14rem] sm:items-center">
      <div className="flex min-w-0 items-center gap-3">
        {name ? (
          <FolderIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
        ) : (
          <FolderOpenIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
        )}
        <div className="min-w-0 flex-1 text-sm">
          <div className="font-medium">{title}</div>
          <div className="truncate text-xs text-muted-foreground">
            {!loaded ? (
              'Sprawdzanie folderu…'
            ) : name ? (
              <>
                Folder <span className="font-medium text-foreground">{name}</span> ·{' '}
                {REPORT_EXTENSIONS[kind].join(', ')}
              </>
            ) : suggestedPath ? (
              <>
                Ścieżkę <span className="font-mono">{suggestedPath}</span> skopiujemy do schowka: wklej ją w pasek
                adresu okna wyboru
              </>
            ) : (
              'Nie wybrano folderu: raport wgrywasz ręcznie'
            )}
          </div>
        </div>
        <Button type="button" variant="outline" size="sm" loading={picking} disabled={!loaded} onClick={choose}>
          {name ? 'Zmień' : 'Wybierz folder'}
        </Button>
      </div>
      <TextField
        label="Nazwa pliku (opcjonalnie)"
        autoComplete="off"
        value={mask}
        onChange={(event) => onMaskChange(event.target.value)}
        hint={`Np. ${MASK_EXAMPLES[kind]} (* = dowolne znaki)`}
      />
    </li>
  )
}
