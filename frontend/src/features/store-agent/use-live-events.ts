import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'

import { useCurrentUser } from '@/features/auth/api'
import { useCurrentStore } from '@/features/stores/current-store'
import { connectLive, onLiveMessage, type LiveMessage } from '@/lib/live-events'

import { agentsKey, useAgentStatus } from './api'

/*
 * Wiadomości na żywo w układzie aplikacji: jedno połączenie z bieżącym sklepem (lib/live-events), a każda wiadomość
 * odświeża tylko zapytania swojego tematu, nie wszystkie. Do tego krótkie powiadomienia: toast w aplikacji, a gdy
 * karta jest w tle i ktoś włączył je w Ustawieniach sklepu → Agent sklepu, powiadomienie systemowe.
 */

const NOTIFICATIONS_KEY = 'intermanage.agentNotifications'

export function systemNotificationsEnabled(): boolean {
  try {
    return localStorage.getItem(NOTIFICATIONS_KEY) === '1'
  } catch {
    return false
  }
}

export function setSystemNotificationsEnabled(enabled: boolean) {
  try {
    if (enabled) localStorage.setItem(NOTIFICATIONS_KEY, '1')
    else localStorage.removeItem(NOTIFICATIONS_KEY)
  } catch {
    // Bez localStorage ustawienie nie zostaje.
  }
}

function notify(title: string, body: string | undefined, tag: string) {
  if (document.visibilityState === 'visible' || !systemNotificationsEnabled()) return
  if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return
  try {
    // Ten sam `tag` w kilku kartach: przeglądarka pokazuje jedno powiadomienie.
    new Notification(title, { body, tag, icon: '/favicon.svg' })
  } catch {
    // Niektóre przeglądarki pozwalają na powiadomienia tylko z service workera.
  }
}

function announce(title: string, body: string | undefined, tag: string) {
  toast.message(title, { description: body, id: tag })
  notify(title, body, tag)
}

export function useLiveEvents() {
  const me = useCurrentUser()
  const store = useCurrentStore()
  const queryClient = useQueryClient()
  const storeId = store?.id
  const userId = me?.id
  useAgentStatus(storeId)

  useEffect(() => {
    if (!storeId || !userId) return
    return connectLive(storeId, userId)
  }, [storeId, userId])

  useEffect(
    () =>
      onLiveMessage((messageStore, message: LiveMessage) => {
        const invalidate = (...key: string[]) =>
          void queryClient.invalidateQueries({ queryKey: ['stores', messageStore, ...key] })
        switch (message.topic) {
          case 'file-queue':
            invalidate('file-queue')
            if (message.action === 'failed' && message.by === 'agent') {
              announce(
                `Agent nie zapisał pliku ${String(message.fileName ?? '')}`,
                typeof message.error === 'string' ? message.error : undefined,
                `file-queue:${String(message.id)}`,
              )
            }
            break
          case 'price-files':
            invalidate('price-files')
            break
          case 'price-control':
            invalidate('data-entry', 'price-control-files')
            invalidate('data-entry', 'price-control-pending')
            if (message.action === 'loaded') {
              const supplier = typeof message.supplier === 'string' ? ` ${message.supplier}` : ''
              announce(`Program sklepu wczytał zmianę cen${supplier}`, undefined, `price-control:${String(message.id)}`)
            }
            break
          case 'athos':
            invalidate('mea')
            break
          case 'imports':
            // Wgrany raport zmienia dane całego sklepu (raporty, kartoteka, dokumenty), w toku tylko listę agenta.
            if (message.action === 'imported') invalidate()
            else invalidate('imports', 'agent-files')
            if (message.action === 'failed') {
              announce(
                `Agent nie wgrał raportu ${String(message.fileName ?? '')}`,
                typeof message.error === 'string' ? message.error : undefined,
                `imports:${String(message.id)}`,
              )
            }
            break
          case 'agent':
            void queryClient.invalidateQueries({ queryKey: agentsKey(messageStore) })
            if (message.action === 'offline') {
              announce(
                'Agent sklepu jest offline',
                'Zapis plików i pilnowanie folderów wróciły do przeglądarki.',
                `agent:${messageStore}`,
              )
            } else if (message.action === 'online') {
              toast.message('Agent sklepu jest znów online', { id: `agent:${messageStore}` })
            }
            break
        }
      }),
    [queryClient],
  )
}
