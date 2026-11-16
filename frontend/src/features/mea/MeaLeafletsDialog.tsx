import { ChevronDownIcon, RefreshCwIcon } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'

import { FileDropZone } from '@/components/FileDropZone'
import { ImportError } from '@/components/ImportError'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { ApiError } from '@/lib/api'

import { useMeaLeaflets, useSyncMeaLeaflets, useUploadMeaLeaflet } from './api'

type Props = {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * Opisy gazetek z centrali: pobierają się same w tle z portalu (co kwadrans, dziesięć najnowszych gazetek), a po nich
 * formularze MEA pokazują formę promocji, megapromocję, limit i cenę dla klienta. „Sprawdź portal” robi to od razu,
 * a ręczne wgranie pliku zostaje na wypadek, gdyby portal nie działał.
 */
export function MeaLeafletsDialog({ storeId, open, onOpenChange }: Props) {
  const list = useMeaLeaflets(storeId, open)
  const sync = useSyncMeaLeaflets(storeId)
  const upload = useUploadMeaLeaflet(storeId)
  const [file, setFile] = useState<File | undefined>()
  const [manual, setManual] = useState(false)

  const checkPortal = () =>
    sync.mutate(undefined, {
      onSuccess: ({ downloaded }) =>
        toast.success(downloaded > 0 ? `Pobrano nowe opisy gazetek: ${downloaded}` : 'Nowych opisów gazetek nie ma'),
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się sprawdzić portalu'),
    })

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) {
          upload.reset()
          setManual(false)
        }
        onOpenChange(next)
      }}
    >
      <DialogContent className="gap-5 p-6 sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>Opisy gazetek</DialogTitle>
          <DialogDescription>
            Opisy z centrali (forma promocji, megapromocje, limity, ceny dla klienta) pobierają się same z portalu Grupy
            Muszkieterów, wspólnie dla wszystkich sklepów. Aktualizacja gazetki w portalu zastępuje poprzedni opis.
          </DialogDescription>
        </DialogHeader>

        <section className="grid gap-2">
          <div className="flex items-center justify-between gap-3">
            <h3 className="text-sm font-medium">Gazetki z opisem</h3>
            <Button variant="outline" size="sm" onClick={checkPortal} loading={sync.isPending}>
              <RefreshCwIcon />
              Sprawdź portal
            </Button>
          </div>
          {list.isPending ? (
            <p className="text-sm text-muted-foreground">Wczytywanie…</p>
          ) : list.data && list.data.length > 0 ? (
            <ul className="max-h-72 divide-y overflow-y-auto rounded-lg border">
              {list.data.map((leaflet) => (
                <li key={leaflet.id} className="flex items-center gap-3 px-3 py-2 text-sm">
                  <span className="font-semibold tabular-nums">
                    {leaflet.week}/{leaflet.year}
                  </span>
                  <span className="min-w-0 truncate text-muted-foreground" title={leaflet.fileName}>
                    {leaflet.fileName}
                  </span>
                  <span className="ml-auto shrink-0 text-xs text-muted-foreground tabular-nums">
                    {leaflet.lineCount} poz. · {leaflet.fromPortal ? 'z portalu' : (leaflet.uploadedBy ?? 'wgrany')}{' '}
                    {new Date(leaflet.uploadedAt).toLocaleString('pl-PL', {
                      day: 'numeric',
                      month: 'numeric',
                      hour: '2-digit',
                      minute: '2-digit',
                    })}
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-sm text-muted-foreground">
              Nie ma jeszcze żadnego opisu. Pobiorą się przy najbliższym sprawdzeniu portalu.
            </p>
          )}
        </section>

        <section className="grid gap-2">
          <button
            type="button"
            onClick={() => setManual((value) => !value)}
            aria-expanded={manual}
            className="flex items-center gap-1 justify-self-start text-sm text-muted-foreground hover:text-foreground"
          >
            <ChevronDownIcon className={manual ? 'size-4 rotate-180' : 'size-4'} />
            Portal nie działa? Wgraj plik opisu ręcznie
          </button>
          {manual && (
            <>
              <FileDropZone
                accept={['.xlsx']}
                pendingFile={upload.isPending ? file : undefined}
                title="Przeciągnij opis gazetki albo kliknij"
                hint="np. Gazetka nr 50_2026 BOŻE NARODZENIE 3.xlsx"
                className="py-6"
                onReject={(name) => toast.error(`${name}: wgraj opis gazetki .xlsx`)}
                onFile={(chosen) => {
                  setFile(chosen)
                  upload.mutate(chosen, {
                    onSuccess: (leaflet) =>
                      toast.success(`Wgrano opis gazetki ${leaflet.week}/${leaflet.year}`, {
                        description: `${leaflet.lineCount} pozycji`,
                      }),
                  })
                }}
              />
              {upload.error && <ImportError error={upload.error} />}
            </>
          )}
        </section>
      </DialogContent>
    </Dialog>
  )
}
