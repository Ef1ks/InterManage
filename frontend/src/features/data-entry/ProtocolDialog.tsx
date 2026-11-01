import { useState } from 'react'
import { FileOutputIcon, SettingsIcon } from 'lucide-react'
import { Link } from 'react-router'
import { toast } from 'sonner'

import { SavedFolder } from '@/components/SavedFolder'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCan } from '@/features/auth/api'
import { storeSettingsPaths } from '@/features/store-settings/paths'
import { ApiError, downloadPostedFile, fetchPostedFile } from '@/lib/api'
import { PROTOCOLS, useProtoFolder, useProtoSettings } from '@/lib/proto-files'
import { ensureFolderAccess, saveToFolder } from '@/lib/saved-folders'
import type { ProtocolKind } from '@/lib/types'

export type ProtocolLine = { ean: string; quantity: number }

type Props = {
  storeId: string
  /** Artykuły z EAN-em i ilością różną od 0 (ze znakiem). */
  lines: ProtocolLine[]
  /** Artykuły pominięte, bo nie mają EAN-u albo ilości. */
  skipped: number
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * Wybór protokołu (PROTO1-PROTO6) z artykułów tabeli. Plik zapisuje się w folderze plików PROTO wybranym raz w
 * przeglądarce, skąd program sklepu sam go wczytuje; bez folderu (albo w przeglądarce bez tej funkcji) się pobiera.
 */
export function ProtocolDialog({ storeId, lines, skipped, open, onOpenChange }: Props) {
  const { data: settings } = useProtoSettings(storeId)
  const folder = useProtoFolder(storeId)
  const canEditSettings = useCanEditProtoSettings()
  const [pending, setPending] = useState<ProtocolKind | null>(null)
  const visible = PROTOCOLS.filter((protocol) => !settings?.hiddenProtocols.includes(protocol.kind))

  const generate = async (kind: ProtocolKind, number: number) => {
    setPending(kind)
    try {
      // Zgoda na zapis w folderze tylko zaraz po kliknięciu, zanim cokolwiek pójdzie do serwera.
      const folderReady = await ensureFolderAccess(folder.key)
      const path = `/stores/${storeId}/data-entry/protocols/${kind}`
      const fallbackName = `PROTO${number}.Traite0`
      if (!folderReady) {
        await downloadPostedFile(path, { lines }, fallbackName)
        toast.success(`Pobrano plik PROTO${number}`, { description: 'Folder plików PROTO nie jest wybrany w tej przeglądarce.' })
      } else {
        const { fileName, blob } = await fetchPostedFile(path, { lines }, fallbackName)
        await saveToFolder(folder.key, fileName, blob)
        toast.success(`Zapisano ${fileName}`)
      }
      onOpenChange(false)
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się wygenerować protokołu')
    } finally {
      setPending(null)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Wygeneruj protokół</DialogTitle>
          <DialogDescription>
            {lines.length} {lines.length === 1 ? 'artykuł' : 'artykułów'} z ilością (ujemne ze znakiem minus).
            {skipped > 0 && ` Bez EAN-u albo ilości: ${skipped}, nie trafią do pliku.`}
          </DialogDescription>
        </DialogHeader>

        {visible.length === 0 ? (
          <p className="text-sm text-muted-foreground">Wszystkie protokoły są ukryte w ustawieniach sklepu.</p>
        ) : (
          <div className="grid gap-2 sm:grid-cols-2">
            {visible.map((protocol) => (
              <Button
                key={protocol.kind}
                variant="outline"
                size="xl"
                className="h-auto justify-between gap-3 py-3"
                loading={pending === protocol.kind}
                disabled={pending !== null || lines.length === 0}
                onClick={() => generate(protocol.kind, protocol.number)}
              >
                <span className="flex items-center gap-2">
                  <FileOutputIcon aria-hidden />
                  {protocol.label}
                </span>
                <span className="font-mono text-xs text-muted-foreground">PROTO{protocol.number}</span>
              </Button>
            ))}
          </div>
        )}

        <SavedFolder folder={folder} />

        {canEditSettings && (
          <Link
            to={storeSettingsPaths.protoFiles}
            className="inline-flex items-center gap-1.5 text-sm text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
          >
            <SettingsIcon aria-hidden className="size-4" />
            Folder i widoczne protokoły w ustawieniach sklepu
          </Link>
        )}
      </DialogContent>
    </Dialog>
  )
}

function useCanEditProtoSettings() {
  const dataEntry = useCan('DATA_ENTRY', 'EDIT')
  const documents = useCan('DOCUMENTS', 'EDIT')
  const deliOrders = useCan('DELI_ORDERS', 'EDIT')
  return dataEntry || documents || deliOrders
}
