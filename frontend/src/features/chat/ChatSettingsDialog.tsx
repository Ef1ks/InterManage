import { useRef, useState } from 'react'
import { ImageUpIcon, Trash2Icon } from 'lucide-react'
import { toast } from 'sonner'

import { Form, FormActions, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { ApiError } from '@/lib/api'
import type { ChatConversation } from '@/lib/types'

import { useChatActions } from './api'
import { conversationTitle, defaultStoreChatName } from './format'

/** Logo czatu: kwadrat tej wielkości (wyświetlane najwyżej ok. 40 px, z zapasem na ekrany o dużej gęstości). */
const LOGO_SIZE = 256

/**
 * Nazwa i logo czatu sklepu albo grupy (zarządzający czatem). Logo przycina się do kwadratu i zmniejsza w
 * przeglądarce, wymiana i usunięcie mają „Przywróć”.
 */
export function ChatSettingsDialog({
  conversation,
  open,
  onOpenChange,
}: {
  conversation: ChatConversation
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const { rename, setLogo, removeLogo, restoreLogo } = useChatActions()
  const [name, setName] = useState(conversation.name ?? '')
  const [error, setError] = useState<string | null>(null)
  const picker = useRef<HTMLInputElement>(null)
  const fallback = conversation.kind === 'STORE' ? defaultStoreChatName(conversation) : 'Grupa'

  const restore = (logoId: string | null) =>
    logoId
      ? { label: 'Przywróć', onClick: () => restoreLogo.mutate({ conversationId: conversation.id, logoId }) }
      : undefined

  const upload = async (file: File) => {
    try {
      const blob = await squareLogo(file)
      const change = await setLogo.mutateAsync({ conversationId: conversation.id, file: blob })
      toast.success('Zmieniono logo', { action: restore(change.previousLogoId) })
    } catch (failure) {
      toast.error(failure instanceof ApiError ? failure.message : 'Nie udało się zmienić logo')
    }
  }

  const remove = () =>
    removeLogo.mutate(conversation.id, {
      onSuccess: (change) => toast('Usunięto logo', { action: restore(change.previousLogoId) }),
      onError: (failure) => toast.error(failure instanceof ApiError ? failure.message : 'Nie udało się usunąć logo'),
    })

  const save = () => {
    const value = name.trim()
    if (!value) {
      setError('Podaj nazwę')
      return
    }
    rename.mutate(
      { conversationId: conversation.id, name: value },
      {
        onSuccess: () => {
          toast.success('Zmieniono nazwę')
          onOpenChange(false)
        },
        onError: (failure) => setError(failure instanceof ApiError ? failure.message : 'Nie udało się zmienić nazwy'),
      },
    )
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Nazwa i logo</DialogTitle>
          <DialogDescription>
            {conversation.kind === 'STORE'
              ? 'Tak czat sklepu widzą wszyscy na liście rozmów. Nazwa sklepu w aplikacji się nie zmienia.'
              : 'Tak grupę widzą wszyscy jej członkowie.'}
          </DialogDescription>
        </DialogHeader>

        <div className="flex items-center gap-4">
          <span className="flex size-16 shrink-0 items-center justify-center overflow-hidden rounded-full bg-muted">
            {conversation.logoUrl ? (
              <img src={conversation.logoUrl} alt="" className="size-full object-cover" />
            ) : (
              <ImageUpIcon className="size-6 text-muted-foreground" />
            )}
          </span>
          <div className="flex flex-wrap gap-2">
            <input
              ref={picker}
              type="file"
              accept="image/png,image/jpeg,image/webp"
              className="hidden"
              onChange={(event) => {
                const file = event.target.files?.[0]
                event.target.value = ''
                if (file) void upload(file)
              }}
            />
            <Button
              type="button"
              variant="outline"
              size="sm"
              loading={setLogo.isPending}
              onClick={() => picker.current?.click()}
            >
              <ImageUpIcon data-icon="inline-start" />
              {conversation.logoUrl ? 'Zmień logo' : 'Dodaj logo'}
            </Button>
            {conversation.logoUrl && (
              <Button type="button" variant="ghost" size="sm" loading={removeLogo.isPending} onClick={remove}>
                <Trash2Icon data-icon="inline-start" />
                Usuń
              </Button>
            )}
          </div>
        </div>

        <Form
          onSubmit={(event) => {
            event.preventDefault()
            save()
          }}
          error={error}
        >
          <TextField
            label="Nazwa"
            value={name}
            maxLength={80}
            onChange={(event) => {
              setName(event.target.value)
              setError(null)
            }}
          />
          <p className="-mt-2 text-xs text-muted-foreground">
            Teraz: {conversationTitle(conversation)}. Bez własnej nazwy: {fallback}.
          </p>
          <FormActions>
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
              Anuluj
            </Button>
            <Button type="submit" size="xl" loading={rename.isPending}>
              Zapisz nazwę
            </Button>
          </FormActions>
        </Form>
      </DialogContent>
    </Dialog>
  )
}

/** Środek obrazka jako kwadrat {@link LOGO_SIZE} px w WEBP (mały plik, łącze sklepu jest wolne). */
async function squareLogo(file: File): Promise<Blob> {
  const bitmap = await createImageBitmap(file)
  const side = Math.min(bitmap.width, bitmap.height)
  const size = Math.min(LOGO_SIZE, side)
  const canvas = document.createElement('canvas')
  canvas.width = size
  canvas.height = size
  const context = canvas.getContext('2d')
  if (!context) throw new Error('Brak obsługi obrazów w przeglądarce')
  context.drawImage(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side, 0, 0, size, size)
  bitmap.close()
  return new Promise((resolve, reject) =>
    canvas.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error('Nie udało się zapisać logo'))),
      'image/webp',
      0.9,
    ),
  )
}
