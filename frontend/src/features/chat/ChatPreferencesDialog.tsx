import { useState } from 'react'
import { BellOffIcon, UserRoundPenIcon, Volume2Icon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Switch } from '@/components/ui/switch'
import { UserAvatar } from '@/components/UserAvatar'
import { useCurrentUser } from '@/features/auth/api'
import { ApiError } from '@/lib/api'
import type { ChatConversation, ChatSound } from '@/lib/types'
import { cn } from '@/lib/utils'

import { useChatSettings, useChatSettingsActions } from './api'
import { conversationTitle } from './format'
import {
  chatNotificationsEnabled,
  playChatSound,
  requestChatNotifications,
  setChatNotificationsEnabled,
} from './notifications'
import { openProfile } from './profile'

const SOUNDS: { value: ChatSound; label: string }[] = [
  { value: 'ALL', label: 'Każda wiadomość' },
  { value: 'MENTIONS', label: 'Oznaczenia i odpowiedzi' },
  { value: 'NONE', label: 'Bez dźwięku' },
]

const showError = (error: unknown) =>
  toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać ustawień')

/** Ustawienia czatu konta: dźwięk, powiadomienia w tej przeglądarce, potwierdzenia odczytu i wyciszone. */
export function ChatPreferencesDialog({
  open,
  onOpenChange,
  conversations,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  conversations: ChatConversation[]
}) {
  const me = useCurrentUser()
  const { data: settings } = useChatSettings()
  const { save, muteUser, muteConversation } = useChatSettingsActions()
  const [systemOn, setSystemOn] = useState(chatNotificationsEnabled)
  const unsupported = typeof Notification === 'undefined'
  const blocked = !unsupported && Notification.permission === 'denied'

  const mutedConversations = (settings?.mutedConversations ?? [])
    .map((id) => conversations.find((conversation) => conversation.id === id))
    .filter((conversation): conversation is ChatConversation => !!conversation)

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Ustawienia czatu</DialogTitle>
          <DialogDescription>
            Dotyczą Twojego konta, poza powiadomieniami, które ustawia każda przeglądarka.
          </DialogDescription>
        </DialogHeader>
        {settings && (
          <div className="flex flex-col gap-5">
            <Button
              variant="outline"
              className="justify-start"
              onClick={() => {
                onOpenChange(false)
                if (me) openProfile(me.id)
              }}
            >
              <UserRoundPenIcon data-icon="inline-start" />
              Mój profil: nick, opis i telefon
            </Button>
            <section>
              <h3 className="mb-2 text-sm font-medium">Dźwięk nowej wiadomości</h3>
              <div className="grid grid-cols-3 gap-1 rounded-lg bg-muted p-1">
                {SOUNDS.map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    aria-pressed={settings.sound === option.value}
                    onClick={() => {
                      save.mutate({ readReceipts: settings.readReceipts, sound: option.value }, { onError: showError })
                      if (option.value !== 'NONE') playChatSound(option.value === 'MENTIONS')
                    }}
                    className={cn(
                      'rounded-md px-2 py-1.5 text-xs font-medium transition-colors duration-100',
                      settings.sound === option.value
                        ? 'bg-background text-foreground shadow-sm'
                        : 'text-muted-foreground hover:text-foreground',
                    )}
                  >
                    {option.label}
                  </button>
                ))}
              </div>
              <Button variant="link" size="sm" className="mt-1 h-auto px-0" onClick={() => playChatSound(false)}>
                <Volume2Icon data-icon="inline-start" />
                Posłuchaj
              </Button>
            </section>

            <label className="flex items-start gap-3">
              <span className="min-w-0 flex-1">
                <span className="block text-sm font-medium">Powiadomienia systemowe w tej przeglądarce</span>
                <span className="block text-xs text-muted-foreground">
                  {unsupported
                    ? 'Ta przeglądarka ich nie obsługuje.'
                    : blocked
                      ? 'Przeglądarka je zablokowała: zezwól na nie w ustawieniach strony (kłódka przy adresie).'
                      : 'Gdy pracujesz w innej karcie albo programie, wiadomość pokaże się w rogu ekranu.'}
                </span>
              </span>
              <Switch
                checked={systemOn}
                disabled={unsupported || blocked}
                onCheckedChange={(checked) => {
                  if (!checked) {
                    setChatNotificationsEnabled(false)
                    setSystemOn(false)
                    return
                  }
                  void requestChatNotifications().then(setSystemOn)
                }}
              />
            </label>

            <label className="flex items-start gap-3">
              <span className="min-w-0 flex-1">
                <span className="block text-sm font-medium">Potwierdzenia odczytu</span>
                <span className="block text-xs text-muted-foreground">
                  Wyłączone: inni nie widzą, że przeczytałeś(-aś) ich wiadomość, a Ty nie widzisz tego u nich.
                </span>
              </span>
              <Switch
                checked={settings.readReceipts}
                onCheckedChange={(checked) =>
                  save.mutate({ readReceipts: checked, sound: settings.sound }, { onError: showError })
                }
              />
            </label>

            <section>
              <h3 className="mb-1 text-sm font-medium">Wyciszone</h3>
              {settings.mutedUsers.length === 0 && mutedConversations.length === 0 ? (
                <p className="text-xs text-muted-foreground">
                  Nikogo nie wyciszono. Osobę wyciszysz przy jej wiadomości, rozmowę dzwonkiem w jej nagłówku.
                </p>
              ) : (
                <ul className="divide-y rounded-lg border">
                  {settings.mutedUsers.map((person) => (
                    <li key={person.id} className="flex items-center gap-2 px-3 py-2">
                      <UserAvatar name={person.name} url={person.avatarUrl} className="size-7 text-[0.65rem]" />
                      <span className="min-w-0 flex-1 truncate text-sm">{person.name}</span>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => muteUser.mutate({ userId: person.id, muted: false }, { onError: showError })}
                      >
                        Odcisz
                      </Button>
                    </li>
                  ))}
                  {mutedConversations.map((conversation) => (
                    <li key={conversation.id} className="flex items-center gap-2 px-3 py-2">
                      <BellOffIcon className="size-4 shrink-0 text-muted-foreground" />
                      <span className="min-w-0 flex-1 truncate text-sm">{conversationTitle(conversation)}</span>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() =>
                          muteConversation.mutate(
                            { conversationId: conversation.id, muted: false },
                            { onError: showError },
                          )
                        }
                      >
                        Odcisz
                      </Button>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}

/** Baner na stronie czatu: prośba o powiadomienia, dopóki przeglądarka o nie nie pytała. */
export function ChatNotificationsPrompt() {
  const [state, setState] = useState(() =>
    typeof Notification === 'undefined' ? 'unsupported' : Notification.permission,
  )
  const [hidden, setHidden] = useState(() => {
    try {
      return localStorage.getItem('intermanage.chatNotificationsPrompt') === 'hidden'
    } catch {
      return false
    }
  })
  if (state !== 'default' || hidden) return null
  return (
    <div className="mb-3 flex flex-wrap items-center gap-2 rounded-xl border border-sky-300 bg-sky-100 px-3 py-2 text-sm text-sky-950 dark:border-sky-800 dark:bg-sky-950/60 dark:text-sky-100">
      <span className="min-w-0 flex-1">
        Włącz powiadomienia, żeby widzieć nowe wiadomości także w innej karcie albo programie.
      </span>
      <Button size="sm" onClick={() => void requestChatNotifications().then(() => setState(Notification.permission))}>
        Włącz powiadomienia
      </Button>
      <Button
        variant="ghost"
        size="sm"
        onClick={() => {
          setHidden(true)
          try {
            localStorage.setItem('intermanage.chatNotificationsPrompt', 'hidden')
          } catch {
            // Bez localStorage baner wróci po odświeżeniu.
          }
        }}
      >
        Nie teraz
      </Button>
    </div>
  )
}
