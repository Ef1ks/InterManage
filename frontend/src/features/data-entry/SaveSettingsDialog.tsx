import { InboxIcon, ListIcon } from 'lucide-react'
import { useState } from 'react'

import { SwitchField } from '@/components/form'
import { SavedFolder } from '@/components/SavedFolder'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { FileQueueDialog } from '@/features/file-queue/FileQueueDialog'
import { setFileQueueEnabled, useFileQueueEnabled } from '@/lib/file-queue'
import { useProductFileFolder, useProtoFolder } from '@/lib/proto-files'
import type { SavedFolderSpec } from '@/lib/saved-folders'
import { useHiddenHint } from '@/lib/use-hidden-hint'
import { cn } from '@/lib/utils'

import { usePriceControlPendingCount } from './api'
import { PriceControlFilesDialog } from './PriceControlFilesDialog'
import { PriceFileStatus } from './PriceFileStatus'
import { COLUMN_FILL_PROMPT } from './column-fill'
import { PRODUCT_DRAFT_PROMPT } from './product-drafts'
import { XML_FOLDER } from './xml-save'

/**
 * Ustawienia zapisu wprowadzania danych: foldery w tej przeglądarce na plik XML faktury, plik nowych artykułów
 * (CADBEZP) i protokoły (pliki PROTO, zmiana cen CTRLPRIX, etykiety, zamówienie), wybrane raz, więc „Zapisz” zapisuje od razu bez pytania
 * o miejsce. Do tego zmiany cen czekające na wgranie (okno z listą i ponownym zapisem), a we wprowadzaniu danych folder cen z kasy. Nazw plików
 * z programu sklepu pracownik nie widzi.
 */
export function SaveSettingsDialog({
  storeId,
  open,
  onOpenChange,
  missing = [],
  withXml = true,
  extraFolders = [],
  withPriceFiles = false,
  withColumnFill = false,
}: {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Foldery, których zabrakło przy ostatnim „Zapisz” (wyróżnione). */
  missing?: SavedFolderSpec[]
  /** Folder pliku XML faktury (dostawa z bazy go nie zapisuje). */
  withXml?: boolean
  /** Foldery innych plików ekranu (np. „Sprawdź PLU” w dostawie z bazy), na końcu listy. */
  extraFolders?: { folder: SavedFolderSpec; note: string }[]
  /** Folder cen z kasy (pliki TRACEART), który przeglądarka czyta w tle. */
  withPriceFiles?: boolean
  /** Przełącznik pytania przed ustawieniem wartości w całej kolumnie (pola nad kolumnami tabeli). */
  withColumnFill?: boolean
}) {
  const productFolder = useProductFileFolder(storeId)
  const protoFolder = useProtoFolder(storeId)
  const { data: pendingCount = 0 } = usePriceControlPendingCount(storeId, open)
  const [priceControlOpen, setPriceControlOpen] = useState(false)
  const [queueOpen, setQueueOpen] = useState(false)
  const queueEnabled = useFileQueueEnabled(storeId)
  const [draftPromptOff, setDraftPromptOff] = useHiddenHint(PRODUCT_DRAFT_PROMPT)
  const [fillPromptOff, setFillPromptOff] = useHiddenHint(COLUMN_FILL_PROMPT)
  // Wybrany w tym oknie folder przestaje być brakujący od razu, bez zamykania okna.
  const [picked, setPicked] = useState<string[]>([])
  const stillMissing = missing.filter((folder) => !picked.includes(folder.key))
  const isMissing = (folder: SavedFolderSpec) => stillMissing.some((item) => item.key === folder.key)
  const changeOpen = (next: boolean) => {
    if (!next) setPicked([])
    onOpenChange(next)
  }

  const folders = [
    ...(withXml ? [{ folder: XML_FOLDER, note: 'Plik dokumentu dla programu sklepu.' }] : []),
    { folder: productFolder, note: 'Nowe i zmienione artykuły, zapisywane razem z plikiem XML.' },
    {
      folder: protoFolder,
      note: 'Protokoły, zmiana cen, etykiety i zamówienia; ten sam folder co w garmażu i przyjęciach MM.',
    },
    ...extraFolders,
  ]

  return (
    <>
      <Dialog open={open} onOpenChange={changeOpen}>
        <DialogContent className="gap-5 p-6 sm:max-w-3xl">
          <DialogHeader>
            <DialogTitle>{withPriceFiles ? 'Ustawienia zapisu i cen z kasy' : 'Ustawienia zapisu'}</DialogTitle>
            <DialogDescription>
              Foldery wybiera się raz w tej przeglądarce. Potem „Zapisz” od razu zapisuje pliki na miejscu.
            </DialogDescription>
          </DialogHeader>

          {stillMissing.length > 0 && (
            <p
              role="alert"
              className="rounded-xl border border-amber-300 bg-amber-100 px-4 py-3 text-sm font-medium text-amber-950 dark:border-amber-500/40 dark:bg-amber-950/60 dark:text-amber-100"
            >
              Wybierz zaznaczone foldery i zapisz plik jeszcze raz.
            </p>
          )}

          <div className="grid gap-4">
            {folders.map(({ folder, note }) => (
              <div key={folder.key} className="grid gap-1.5">
                <p className="text-sm font-medium">{folder.label}</p>
                <SavedFolder
                  folder={folder}
                  onPicked={() => setPicked((current) => [...current, folder.key])}
                  className={cn(
                    isMissing(folder) && 'border-amber-400 bg-amber-100/60 dark:border-amber-600 dark:bg-amber-950/40',
                  )}
                />
                <p className="text-xs text-muted-foreground">{note}</p>
              </div>
            ))}
            {withPriceFiles && <PriceFileStatus storeId={storeId} />}
            <SwitchField
              label="Pytaj o wprowadzenie nowych artykułów"
              description="Okno po zapisie pliku nowych artykułów. Wyłączone działa jak „Tak”: artykuły od razu trafiają do bazy, a powiadomienie pozwala to cofnąć."
              checked={!draftPromptOff}
              onCheckedChange={(checked) => setDraftPromptOff(!checked)}
            />
            {withColumnFill && (
              <SwitchField
                label="Pytaj przed ustawieniem całej kolumny"
                description="Pola nad marką, nomenklaturą, rodzajem i wagą: pytanie, czy zmienić tylko nowe artykuły, czy wszystkie. Wyłączone zmienia tylko nowe artykuły."
                checked={!fillPromptOff}
                onCheckedChange={(checked) => setFillPromptOff(!checked)}
              />
            )}
          </div>

          <div className="grid gap-2 rounded-lg border px-3 py-2.5">
            <SwitchField
              label="Praca dla innego sklepu: pliki do kolejki"
              description="W tej przeglądarce wszystkie pliki dla programu tego sklepu (XML, nowe artykuły, protokoły, zmiany cen, etykiety, zamówienia) czekają w kolejce zamiast iść do folderów. Na koniec, na komputerze sklepu albo po połączeniu z nim, „Zapisz wszystkie” zapisze je w folderach."
              checked={queueEnabled}
              onCheckedChange={(checked) => setFileQueueEnabled(storeId, checked)}
            />
            <div>
              <Button variant="outline" size="sm" onClick={() => setQueueOpen(true)}>
                <InboxIcon />
                Pokaż kolejkę plików
              </Button>
            </div>
          </div>

          <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border px-3 py-2.5 text-sm">
            <div>
              <p className="font-medium">Zmiany cen</p>
              <p className="text-xs text-muted-foreground">
                {pendingCount > 0
                  ? `Czekają na wgranie do programu sklepu: ${pendingCount}`
                  : 'Wszystkie zmiany cen są wgrane do programu sklepu.'}
              </p>
            </div>
            <Button variant="outline" size="sm" onClick={() => setPriceControlOpen(true)}>
              <ListIcon />
              Pokaż zmiany cen
            </Button>
          </div>
        </DialogContent>
      </Dialog>
      <PriceControlFilesDialog storeId={storeId} open={priceControlOpen} onOpenChange={setPriceControlOpen} />
      <FileQueueDialog storeId={storeId} open={queueOpen} onOpenChange={setQueueOpen} />
    </>
  )
}
