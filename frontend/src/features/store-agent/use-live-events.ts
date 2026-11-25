import { useEffect } from 'react'
import { useQueryClient, type QueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'

import { markLoadedByHand, retryMacro } from '@/features/agent-macros/api'
import { useCurrentUser } from '@/features/auth/api'
import { useCurrentStore } from '@/features/stores/current-store'
import { api, ApiError } from '@/lib/api'
import { emitDraftsChanged, productFilesToast } from '@/lib/draft-events'
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
          case 'staff-time':
            // Odbicie z kiosku, przerwa albo decyzja: karty Czasu pracy, „W pracy teraz” i liczniki do reakcji.
            // Powiadomienie z nazwiskiem pokazuje tylko strona Czasu pracy (TimePage).
            void queryClient.invalidateQueries({ queryKey: ['staff-time', messageStore] })
            break
          case 'chat':
            invalidateChat(queryClient, message)
            if (message.type === 'message' && message.authorId !== userId) {
              const mentioned = Array.isArray(message.mentioned) && message.mentioned.includes(userId)
              notifyChat(mentioned)
            }
            break
          case 'agent-macro':
            if (message.kind === 'PRODUCT_FILES' && message.status === 'DRAFTS') {
              invalidate()
              emitDraftsChanged(messageStore, eansOf(message))
            }
            if (message.kind === 'PRODUCT_FILES' && message.userId === userId) {
              notifyProductFiles(messageStore, message)
            }
            if (message.kind === 'PRICE_CONTROL' && message.userId === userId) {
              notifyPriceControl(messageStore, message)
            }
            break
          case 'cad-priority':
            invalidate('cadencier-priorities')
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
    [queryClient, userId],
  )
}

/**
 * Czat odświeża tylko to, co się zmieniło: odczyt samą rozmowę (znaczniki „wyświetlona”), bo w czacie sklepu jedna
 * wiadomość daje odczyt od każdego, nowa albo cofnięta wiadomość listę, licznik i rozmowę, zmiana grupy cały czat.
 * „Pisze…” czyta sama strona czatu.
 */
function invalidateChat(queryClient: QueryClient, message: LiveMessage) {
  const thread = ['chat', 'thread', String(message.conversationId)]
  switch (message.type) {
    case 'typing':
      return
    case 'read':
      void queryClient.invalidateQueries({ queryKey: thread })
      return
    case 'conversation':
      void queryClient.invalidateQueries({ queryKey: ['chat'] })
      return
    default:
      void queryClient.invalidateQueries({ queryKey: thread })
      void queryClient.invalidateQueries({ queryKey: ['chat', 'conversations'] })
      void queryClient.invalidateQueries({ queryKey: ['chat', 'unread'] })
  }
}

/**
 * Makro, które po zapisie pliku nowych artykułów zleca sam serwer: nie ma swojego okna, więc wynik jest w powiadomieniu
 * osoby, która zapisała plik (jedno na sklep, od czekania do wyniku). Po udanym artykuły są już w bazie (`DRAFTS`),
 * nieudane da się ponowić albo potwierdzić, że wczytano je ręcznie.
 */
function notifyProductFiles(storeId: string, message: LiveMessage) {
  const id = productFilesToast(storeId)
  const macroId = String(message.id)
  const description = typeof message.message === 'string' && message.message ? message.message : undefined
  const showError = (error: unknown) => {
    toast.error(error instanceof ApiError ? error.message : 'Brak połączenia z serwerem')
  }
  switch (message.status) {
    case 'NO_SUPPLIER':
      toast.warning('Nowe artykuły trzeba wczytać ręcznie', {
        id,
        description: 'Dokument nie ma numeru dostawcy z programu sklepu.',
        duration: 20_000,
      })
      break
    case 'RUNNING':
      toast.loading('Komputer sklepu wczytuje nowe artykuły…', { id, duration: 600_000 })
      break
    case 'DONE':
      toast.success('Nowe artykuły wczytane na komputerze sklepu', { id, description, duration: 10_000 })
      notify('Nowe artykuły wczytane na komputerze sklepu', description, id)
      break
    case 'DRAFTS': {
      const ids = Array.isArray(message.draftIds) ? message.draftIds.map(String) : []
      if (ids.length === 0) break
      toast.success(`Komputer sklepu wczytał nowe artykuły: ${ids.length}`, {
        id,
        description: 'Są już w bazie. Resztę danych dopisze kolejny raport kartoteki.',
        duration: 15_000,
        action: {
          label: 'Cofnij',
          onClick: () => {
            api(`/stores/${storeId}/data-entry/product-drafts/undo`, { method: 'POST', json: { ids } })
              .then(() => {
                emitDraftsChanged(storeId, eansOf(message))
                toast('Cofnięto dodanie artykułów')
              })
              .catch(showError)
          },
        },
      })
      break
    }
    case 'FAILED':
    case 'NO_COMPUTER': {
      const title = 'Nowe artykuły nie wczytały się na komputerze sklepu'
      toast.error(title, {
        id,
        description,
        duration: Infinity,
        action: {
          label: 'Spróbuj ponownie',
          onClick: () => {
            retryMacro(storeId, macroId).catch(showError)
          },
        },
        cancel: {
          label: 'Wczytane ręcznie',
          onClick: () => {
            markLoadedByHand(storeId, macroId).catch(showError)
          },
        },
      })
      notify(title, description, id)
      break
    }
  }
}

/** Zmiana cen, którą po pojawieniu się w folderze wprowadza komputer sklepu; wynik widzi osoba, która ją zapisała. */
function notifyPriceControl(storeId: string, message: LiveMessage) {
  const id = `price-control-macro:${storeId}`
  const description = typeof message.message === 'string' && message.message ? message.message : undefined
  switch (message.status) {
    case 'RUNNING':
      toast.loading('Komputer sklepu zmienia ceny…', { id, duration: 600_000 })
      break
    case 'DONE':
      toast.success('Ceny zmienione na komputerze sklepu', { id, description, duration: 10_000 })
      notify('Ceny zmienione na komputerze sklepu', description, id)
      break
    case 'NO_COMPUTER':
      toast.message('Zmiana cen poczeka', {
        id,
        description: 'Ktoś pracuje na komputerze sklepu. Spróbujemy znów za 10 minut.',
        duration: 8_000,
      })
      break
    case 'FAILED': {
      const title = 'Ceny nie zmieniły się na komputerze sklepu'
      toast.error(title, {
        id,
        description,
        duration: Infinity,
        action: {
          label: 'Spróbuj ponownie',
          onClick: () => {
            retryMacro(storeId, String(message.id)).catch((error: unknown) => {
              toast.error(error instanceof ApiError ? error.message : 'Brak połączenia z serwerem')
            })
          },
        },
      })
      notify(title, description, id)
      break
    }
  }
}

const eansOf = (message: LiveMessage) => (Array.isArray(message.eans) ? message.eans.map(String) : [])

/** Nowa wiadomość z karty w tle: powiadomienie systemowe, gdy ktoś je włączył (tak jak przy agencie). */
function notifyChat(mentioned: boolean) {
  notify(mentioned ? 'Oznaczono Cię na czacie' : 'Nowa wiadomość na czacie', undefined, 'chat')
}
