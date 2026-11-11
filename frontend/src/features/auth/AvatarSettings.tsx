import { useRef, useState } from 'react'
import { Trash2Icon, UploadIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { UserAvatar } from '@/components/UserAvatar'
import { useCurrentUser, useRemoveAvatar, useRestoreAvatar, useUploadAvatar } from '@/features/auth/api'
import { ApiError } from '@/lib/api'
import type { AvatarChange } from '@/lib/types'

const AVATAR_SIDE = 512

/** Kwadrat ze środka zdjęcia, zmniejszony do 512 px: tyle wystarcza na każde kółko w aplikacji. */
async function prepareAvatar(file: File): Promise<File> {
  try {
    const bitmap = await createImageBitmap(file)
    const side = Math.min(bitmap.width, bitmap.height)
    const target = Math.min(side, AVATAR_SIDE)
    const canvas = document.createElement('canvas')
    canvas.width = target
    canvas.height = target
    canvas
      .getContext('2d')
      ?.drawImage(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side, 0, 0, target, target)
    bitmap.close()
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.88))
    if (!blob) return file
    return new File([blob], 'profil.jpg', { type: 'image/jpeg' })
  } catch {
    return file
  }
}

/** Zdjęcie profilowe konta: zamiast inicjałów w menu konta i w aplikacji mobilnej. */
export function AvatarSettings() {
  const me = useCurrentUser()
  const upload = useUploadAvatar()
  const remove = useRemoveAvatar()
  const restore = useRestoreAvatar()
  const input = useRef<HTMLInputElement>(null)
  const [preparing, setPreparing] = useState(false)

  const onError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać zdjęcia profilowego')

  const offerRestore = (change: AvatarChange, message: string) => {
    const previousId = change.previousId
    toast.success(message, {
      action: previousId ? { label: 'Przywróć', onClick: () => restore.mutate(previousId, { onError }) } : undefined,
    })
  }

  const onFile = async (file: File | undefined) => {
    if (!file) return
    setPreparing(true)
    const prepared = await prepareAvatar(file).finally(() => setPreparing(false))
    upload.mutate(prepared, {
      onSuccess: (change) =>
        offerRestore(change, change.previousId ? 'Zmieniono zdjęcie profilowe' : 'Ustawiono zdjęcie profilowe'),
      onError,
    })
  }

  return (
    <div className="flex items-center gap-4">
      <UserAvatar name={me.displayName} url={me.avatar?.url} className="size-16 text-xl" />
      <div className="flex flex-wrap gap-1">
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
        <Button
          type="button"
          variant="outline"
          size="sm"
          loading={preparing || upload.isPending}
          onClick={() => input.current?.click()}
        >
          <UploadIcon />
          {me.avatar ? 'Zmień zdjęcie' : 'Dodaj zdjęcie'}
        </Button>
        {me.avatar && (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            loading={remove.isPending}
            onClick={() =>
              remove.mutate(undefined, {
                onSuccess: (change) => offerRestore(change, 'Usunięto zdjęcie profilowe'),
                onError,
              })
            }
          >
            <Trash2Icon />
            Usuń
          </Button>
        )}
      </div>
    </div>
  )
}
