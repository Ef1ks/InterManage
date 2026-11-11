import { useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { ImageIcon, Trash2Icon, UploadIcon } from 'lucide-react'
import { toast } from 'sonner'

import { BackgroundLayers } from '@/components/layout/AppBackground'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import {
  meQueryKey,
  useBackgroundVeil,
  useCurrentUser,
  useRemoveBackground,
  useRestoreBackground,
  useUploadBackground,
} from '@/features/auth/api'
import { prepareScan } from '@/features/meat/format'
import { ApiError } from '@/lib/api'
import type { BackgroundChange, Me } from '@/lib/types'

const VEIL_SAVE_DELAY_MS = 400

/** Własne zdjęcie w tle aplikacji, zapisane przy koncie (widać je na każdym komputerze po zalogowaniu). */
export function BackgroundSettings() {
  const { background } = useCurrentUser()
  const queryClient = useQueryClient()
  const upload = useUploadBackground()
  const remove = useRemoveBackground()
  const restore = useRestoreBackground()
  const saveVeil = useBackgroundVeil()
  const input = useRef<HTMLInputElement>(null)
  const veilTimer = useRef<number | undefined>(undefined)
  const [preparing, setPreparing] = useState(false)

  useEffect(() => () => window.clearTimeout(veilTimer.current), [])

  const onError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać zdjęcia w tle')

  const offerRestore = (change: BackgroundChange, message: string) => {
    const previousId = change.previousId
    toast.success(message, {
      action: previousId ? { label: 'Przywróć', onClick: () => restore.mutate(previousId, { onError }) } : undefined,
    })
  }

  const onFile = async (file: File | undefined) => {
    if (!file) return
    setPreparing(true)
    // Zdjęcie z aparatu (kilka MB) zmniejszone do 2400 px: na tło wystarczy, a wczytuje się szybciej.
    const prepared = await prepareScan(file).finally(() => setPreparing(false))
    upload.mutate(prepared, {
      onSuccess: (change) =>
        offerRestore(change, change.previousId ? 'Zmieniono zdjęcie w tle' : 'Ustawiono zdjęcie w tle'),
      onError,
    })
  }

  // Suwak zmienia tło od razu w całej aplikacji, a zapisuje się chwilę po puszczeniu.
  const onVeil = (veilPercent: number) => {
    queryClient.setQueryData<Me | null>(meQueryKey, (me) =>
      me?.background ? { ...me, background: { ...me.background, veilPercent } } : me,
    )
    window.clearTimeout(veilTimer.current)
    veilTimer.current = window.setTimeout(() => saveVeil.mutate(veilPercent, { onError }), VEIL_SAVE_DELAY_MS)
  }

  const busy = preparing || upload.isPending

  return (
    <Card className="lg:col-span-2">
      <CardContent className="grid gap-5 sm:grid-cols-[14rem_1fr] sm:items-start">
        <div className="relative isolate aspect-video overflow-hidden rounded-lg border bg-muted/30">
          {background ? (
            <>
              <BackgroundLayers key={background.id} background={background} className="absolute" />
              {/* Makieta karty z tekstem: widać, czy przy tym przesłonięciu treść jest czytelna. */}
              <div className="absolute inset-x-4 top-4 grid gap-1.5 rounded-md border bg-card p-2.5 shadow-sm">
                <div className="h-2 w-2/3 rounded-full bg-foreground/70" />
                <div className="h-1.5 w-1/2 rounded-full bg-muted-foreground/50" />
              </div>
              <div className="absolute bottom-3 left-4 text-xs font-medium">Tekst na tle</div>
            </>
          ) : (
            <div className="flex size-full flex-col items-center justify-center gap-1.5 text-muted-foreground">
              <ImageIcon className="size-6" />
              <span className="text-xs">Bez zdjęcia</span>
            </div>
          )}
        </div>

        <div className="grid gap-4">
          <div>
            <h2 className="text-base font-semibold">Tło aplikacji</h2>
            <p className="mt-1 text-sm text-muted-foreground">
              Twoje zdjęcie pod całą aplikacją. Zapisuje się przy koncie, więc widzisz je na każdym komputerze po
              zalogowaniu. JPG, PNG albo WEBP.
            </p>
          </div>

          {background && (
            <label className="grid gap-2 text-sm">
              <span className="flex items-baseline justify-between gap-3">
                <span className="font-medium">Przesłonięcie zdjęcia</span>
                <span className="text-muted-foreground tabular-nums">{background.veilPercent}%</span>
              </span>
              <input
                type="range"
                min={0}
                max={95}
                step={5}
                value={background.veilPercent}
                onChange={(event) => onVeil(Number(event.target.value))}
                className="w-full accent-foreground"
              />
              <span className="text-xs text-muted-foreground">
                Im więcej, tym bledsze zdjęcie (w ciemnym motywie ciemniejsze) i czytelniejszy tekst.
              </span>
            </label>
          )}

          <div className="flex flex-wrap gap-2">
            <input
              ref={input}
              type="file"
              accept="image/jpeg,image/png,image/webp"
              className="hidden"
              onChange={(event) => {
                void onFile(event.target.files?.[0])
                event.target.value = ''
              }}
            />
            <Button type="button" variant="outline" loading={busy} onClick={() => input.current?.click()}>
              <UploadIcon />
              {background ? 'Zmień zdjęcie' : 'Wybierz zdjęcie'}
            </Button>
            {background && (
              <Button
                type="button"
                variant="ghost"
                loading={remove.isPending}
                onClick={() =>
                  remove.mutate(undefined, {
                    onSuccess: (change) => offerRestore(change, 'Usunięto zdjęcie w tle'),
                    onError,
                  })
                }
              >
                <Trash2Icon />
                Usuń zdjęcie
              </Button>
            )}
          </div>
        </div>
      </CardContent>
    </Card>
  )
}
