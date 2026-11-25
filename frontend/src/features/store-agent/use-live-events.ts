import { useEffect } from 'react'
import { useQueryClient, type QueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'

import { markLoadedByHand, retryMacro } from '@/features/agent-macros/api'
import { hasPermission, useCurrentUser } from '@/features/auth/api'
import { REPORT_TITLES } from '@/features/imports/import-sources'
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

type Tone = 'message' | 'success' | 'warning' | 'error'

/** Błąd zostaje na ekranie dłużej, bo ktoś musi na niego zareagować. */
const DURATION: Record<Tone, number> = { message: 6_000, success: 6_000, warning: 15_000, error: 30_000 }

function announce(title: string, body: string | undefined, tag: string, tone: Tone = 'message') {
  toast[tone](title, { description: body, id: tag, duration: DURATION[tone] })
  notify(title, body, tag)
}

const text = (value: unknown) => (typeof value === 'string' && value ? value : undefined)

export function useLiveEvents() {
  const me = useCurrentUser()
  const store = useCurrentStore()
  const queryClient = useQueryClient()
  const storeId = store?.id
  const userId = me?.id
  // O pracy agenta dowiadują się osoby, które z niej korzystają, a nie każdy z dostępem do sklepu.
  const hearsAgent = hasPermission(me, 'DATA_ENTRY') || hasPermission(me, 'IMPORTS') || hasPermission(me, 'STORE_AGENT')
  const hearsCollectors = hasPermission(me, 'DATA_ENTRY')
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
                text(message.error),
                `file-queue:${String(message.id)}`,
                'error',
              )
            }
            break
          case 'collector-lists':
            invalidate('collector-lists')
            if (message.action === 'received' && hearsCollectors) {
              announce(
                `Dane z kolektora: ${text(message.collector) ?? ''}`,
                `Pozycji: ${String(message.lines)}. Wybierz protokół w Kolektory, Dane z kolektorów.`,
                `collector-list:${String(message.id)}`,
                'success',
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
            invalidate('agent-macros', 'recent')
            if (message.kind === 'PRODUCT_FILES' && message.status === 'DRAFTS') {
              invalidate()
              emitDraftsChanged(messageStore, eansOf(message))
            }
            if (message.kind === 'PRODUCT_FILES' && message.userId === userId) {
              notifyProductFiles(messageStore, message)
            } else if (message.kind === 'PRICE_CONTROL' && message.userId === userId) {
              notifyPriceControl(messageStore, message)
            } else if (hearsAgent) {
              notifyMacro(message)
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
            if (hearsAgent && message.action === 'imported') notifyImported(messageStore, message)
            if (hearsAgent && message.action === 'failed') {
              announce(
                `Agent nie wgrał raportu ${String(message.fileName ?? '')}`,
                text(message.error),
                `imports:${String(message.id)}`,
                'error',
              )
            }
            break
          case 'agent':
            void queryClient.invalidateQueries({ queryKey: agentsKey(messageStore) })
            if (message.action === 'update-finished') invalidate('agent-macros', 'recent')
            if (hearsAgent) notifyAgent(messageStore, message)
            break
        }
      }),
    [queryClient, userId, hearsAgent, hearsCollectors],
  )
}

/** Agent albo odtwarzacz przestał działać, wrócił, albo skończyła się aktualizacja danych z Mercalys. */
function notifyAgent(storeId: string, message: LiveMessage) {
  const computer = text(message.computer) ?? 'komputer sklepu'
  switch (message.action) {
    case 'offline':
      announce(
        'Agent sklepu przestał działać',
        'Zapis plików i pilnowanie folderów wróciły do przeglądarki.',
        `agent:${storeId}`,
        'error',
      )
      break
    case 'online':
      announce('Agent sklepu znów działa', undefined, `agent:${storeId}`, 'success')
      break
    case 'computer-offline':
      announce(
        `Agent przestał działać: ${computer}`,
        'Pozostałe komputery sklepu pracują dalej. Wyłączony komputer też tak wygląda.',
        `agent-computer:${String(message.agentId)}`,
        'warning',
      )
      break
    case 'player-stopped':
      announce(
        `AutoJob nie działa: ${computer}`,
        'Makra nie ruszą na tym komputerze, dopóki ktoś nie uruchomi AutoJob (albo makra wyłączono w ustawieniach agenta).',
        `agent-player:${String(message.agentId)}`,
        'warning',
      )
      break
    case 'update-finished': {
      const tag = `agent-update:${storeId}`
      if (message.ok === false) {
        announce(`Aktualizacja danych nie doszła do końca: ${computer}`, text(message.message), tag, 'error')
      } else {
        const title = message.ok === true ? 'Dane z Mercalys zaktualizowane' : 'Koniec aktualizacji danych z Mercalys'
        announce(`${title}: ${computer}`, text(message.message), tag, message.ok === true ? 'success' : 'message')
      }
      break
    }
  }
}

/** Koniec makra na komputerze sklepu; osoba, która zapisała plik makra serwera, ma własne powiadomienie z ponowieniem. */
function notifyMacro(message: LiveMessage) {
  const title = text(message.title) ?? 'Makro'
  const tag = `agent-macro:${String(message.id)}`
  const computer = text(message.computer)
  switch (message.status) {
    case 'DONE':
      announce(`Zakończone: ${title}`, computer, tag, 'success')
      break
    case 'FAILED':
      announce(`Błąd makra: ${title}`, [computer, text(message.message)].filter(Boolean).join('\n'), tag, 'error')
      break
    case 'NO_COMPUTER':
      // Zmiana cen i ITM8 z cadencier ponawiają się same, więc nie ma na co reagować.
      if (message.kind === 'PRODUCT_FILES') {
        announce(`Makro nie ruszyło: ${title}`, text(message.message), tag, 'warning')
      }
      break
    case 'QUEUED':
      if (text(message.postponed)) announce(`Makro odłożone: ${title}`, text(message.postponed), tag)
      break
  }
}

/** Raporty wgrywane jeden po drugim (codzienna aktualizacja) to jedno powiadomienie z listą. */
const IMPORTED_TOGETHER_MS = 10 * 60_000
const imported = new Map<string, { at: number; names: string[] }>()

function notifyImported(storeId: string, message: LiveMessage) {
  const kind = String(message.kind)
  const name = kind in REPORT_TITLES ? REPORT_TITLES[kind as keyof typeof REPORT_TITLES] : kind
  const last = imported.get(storeId)
  const names = last && Date.now() - last.at < IMPORTED_TOGETHER_MS ? last.names : []
  const all = names.includes(name) ? names : [...names, name]
  imported.set(storeId, { at: Date.now(), names: all })
  announce(
    all.length === 1 ? 'Agent wgrał raport' : `Agent wgrał raporty: ${all.length}`,
    all.join(', '),
    `agent-imports:${storeId}`,
    'success',
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
