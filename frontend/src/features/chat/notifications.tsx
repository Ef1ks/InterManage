import { useEffect, useRef } from 'react'
import { useNavigate } from 'react-router'
import { toast } from 'sonner'

import { useCurrentUser } from '@/features/auth/api'
import { onLiveMessage, type LiveMessage } from '@/lib/live-events'
import type { ChatSettings } from '@/lib/types'

import { useChatSettings } from './api'

/*
 * Nowa wiadomość na czacie ma być zauważalna: w otwartej karcie toast z treścią (jeden na rozmowę, kolejne go
 * podmieniają) i dźwięk, w karcie w tle powiadomienie systemowe (gdy ktoś je włączył), do tego licznik w tytule karty
 * i kropka na ikonie strony. Wyciszona osoba nie powiadamia wcale, wyciszona rozmowa tylko oznaczeniem albo
 * odpowiedzią na moją wiadomość. Otwarta rozmowa na ekranie nie powiadamia.
 */

export const CHAT_PATH = '/czat'
export const CONVERSATION_PARAM = 'rozmowa'

const NOTIFICATIONS_KEY = 'intermanage.chatNotifications'
const CLAIM_KEY = 'intermanage.chatNotified'
/** Karta w tle czeka chwilę, żeby powiadomienie wzięła karta na ekranie, jeśli jest. */
const HIDDEN_TAB_DELAY_MS = 300

// ---------------------------------------------------------------- powiadomienia systemowe

/** Powiadomienia systemowe czatu w tej przeglądarce: bez wyboru włączone, gdy przeglądarka już na nie pozwala. */
export function chatNotificationsEnabled(): boolean {
  if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return false
  try {
    return localStorage.getItem(NOTIFICATIONS_KEY) !== '0'
  } catch {
    return true
  }
}

export function setChatNotificationsEnabled(enabled: boolean) {
  try {
    localStorage.setItem(NOTIFICATIONS_KEY, enabled ? '1' : '0')
  } catch {
    // Bez localStorage zostaje domyślne.
  }
}

/** Prośba o zgodę przeglądarki (musi iść z kliknięcia). */
export async function requestChatNotifications(): Promise<boolean> {
  if (typeof Notification === 'undefined') return false
  const permission =
    Notification.permission === 'default' ? await Notification.requestPermission() : Notification.permission
  setChatNotificationsEnabled(permission === 'granted')
  return permission === 'granted'
}

// ---------------------------------------------------------------- dźwięk

let audio: AudioContext | null = null

/** Przeglądarka gra dźwięk dopiero po geście na stronie: kontekst powstaje przy pierwszym kliknięciu albo klawiszu. */
function unlockAudio() {
  if (audio || typeof AudioContext === 'undefined') return
  try {
    audio = new AudioContext()
  } catch {
    audio = null
  }
}

/** Krótki, miękki dwutonowy sygnał; oznaczenie trzy tony, wyżej. Bez pliku dźwiękowego. */
export function playChatSound(strong: boolean) {
  if (!audio) return
  if (audio.state === 'suspended') void audio.resume().catch(() => undefined)
  const notes = strong ? [880, 1175, 1568] : [784, 1047]
  const start = audio.currentTime + 0.01
  notes.forEach((frequency, index) => {
    const at = start + index * 0.11
    const oscillator = audio!.createOscillator()
    const gain = audio!.createGain()
    oscillator.type = 'sine'
    oscillator.frequency.value = frequency
    gain.gain.setValueAtTime(0, at)
    gain.gain.linearRampToValueAtTime(0.16, at + 0.015)
    gain.gain.exponentialRampToValueAtTime(0.0001, at + 0.32)
    oscillator.connect(gain).connect(audio!.destination)
    oscillator.start(at)
    oscillator.stop(at + 0.34)
  })
}

// ---------------------------------------------------------------- odbiór wiadomości

/** Jedna karta na wiadomość gra dźwięk i daje powiadomienie systemowe (karty przeglądarki dostają tę samą wiadomość). */
function claim(messageId: string): boolean {
  try {
    if (localStorage.getItem(CLAIM_KEY) === messageId) return false
    localStorage.setItem(CLAIM_KEY, messageId)
    return true
  } catch {
    return true
  }
}

function viewing(conversationId: string): boolean {
  return (
    document.visibilityState === 'visible' &&
    window.location.pathname === CHAT_PATH &&
    new URLSearchParams(window.location.search).get(CONVERSATION_PARAM) === conversationId
  )
}

const text = (value: unknown) => (typeof value === 'string' && value ? value : undefined)
const firstName = (name: string) => name.split(' ')[0]

/** Liczba nowych w toaście rozmowy, zeruje się, gdy toast zniknie. */
const pending = new Map<string, number>()

function newMessagesLabel(count: number): string {
  if (count === 1) return ''
  const last = count % 10
  const lastTwo = count % 100
  const word = last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? 'nowe' : 'nowych'
  return ` · ${count} ${word}`
}

type Incoming = {
  conversationId: string
  messageId: string
  author: string
  title: string | undefined
  preview: string
  mentioned: boolean
  replyToMe: boolean
}

function incoming(message: LiveMessage, meId: string): Incoming | null {
  if (message.type !== 'message' || message.authorId === meId) return null
  return {
    conversationId: String(message.conversationId),
    messageId: String(message.messageId),
    author: text(message.authorName) ?? 'Ktoś',
    title: text(message.title),
    preview: text(message.preview) ?? '',
    mentioned: Array.isArray(message.mentioned) && message.mentioned.includes(meId),
    replyToMe: message.replyToAuthorId === meId,
  }
}

function silenced(event: Incoming, authorId: string, settings: ChatSettings | undefined): boolean {
  if (!settings) return false
  if (settings.mutedUsers.some((person) => person.id === authorId)) return true
  return settings.mutedConversations.includes(event.conversationId) && !event.mentioned && !event.replyToMe
}

/** W układzie aplikacji: powiadamia o wiadomościach z każdej rozmowy, poza otwartą na ekranie. */
export function useChatNotifications() {
  const me = useCurrentUser()
  const meId = me?.id
  const navigate = useNavigate()
  const { data: settings } = useChatSettings()
  const settingsRef = useRef(settings)
  useEffect(() => {
    settingsRef.current = settings
  }, [settings])
  const navigateRef = useRef(navigate)
  useEffect(() => {
    navigateRef.current = navigate
  }, [navigate])

  useEffect(() => {
    const unlock = () => unlockAudio()
    window.addEventListener('pointerdown', unlock, { once: true, capture: true })
    window.addEventListener('keydown', unlock, { once: true, capture: true })
    return () => {
      window.removeEventListener('pointerdown', unlock, { capture: true })
      window.removeEventListener('keydown', unlock, { capture: true })
    }
  }, [])

  useEffect(() => {
    if (!meId) return
    return onLiveMessage((_store, message) => {
      if (message.topic !== 'chat') return
      const event = incoming(message, meId)
      if (!event || viewing(event.conversationId)) return
      if (silenced(event, String(message.authorId), settingsRef.current)) return
      const strong = event.mentioned || event.replyToMe
      const open = () =>
        navigateRef.current(`${CHAT_PATH}?${CONVERSATION_PARAM}=${encodeURIComponent(event.conversationId)}`)

      if (document.visibilityState === 'visible') {
        showToast(event, strong, open)
        if (claim(event.messageId)) sound(strong, settingsRef.current)
        return
      }
      window.setTimeout(() => {
        if (!claim(event.messageId)) return
        sound(strong, settingsRef.current)
        systemNotification(event, strong, open)
      }, HIDDEN_TAB_DELAY_MS)
    })
  }, [meId])
}

function sound(strong: boolean, settings: ChatSettings | undefined) {
  const mode = settings?.sound ?? 'MENTIONS'
  if (mode === 'ALL' || (mode === 'MENTIONS' && strong)) playChatSound(strong)
}

function heading(event: Incoming): { title: string; body: string } {
  const what = event.mentioned ? ' oznaczył(a) Cię' : event.replyToMe ? ' odpowiada Ci' : ''
  if (!event.title) return { title: event.author + what, body: event.preview }
  return { title: event.title, body: `${firstName(event.author)}${what}: ${event.preview}` }
}

function showToast(event: Incoming, strong: boolean, open: () => void) {
  const id = `chat:${event.conversationId}`
  const count = (pending.get(id) ?? 0) + 1
  pending.set(id, count)
  const { title, body } = heading(event)
  const show = strong ? toast.warning : toast.info
  show(title + newMessagesLabel(count), {
    id,
    description: body,
    duration: strong ? 15_000 : 8_000,
    action: {
      label: 'Otwórz',
      onClick: () => {
        pending.delete(id)
        open()
      },
    },
    onDismiss: () => pending.delete(id),
    onAutoClose: () => pending.delete(id),
  })
}

function systemNotification(event: Incoming, strong: boolean, open: () => void) {
  if (!chatNotificationsEnabled()) return
  const { title, body } = heading(event)
  try {
    const notification = new Notification(title, {
      body,
      // Jedna rozmowa = jedno powiadomienie, kolejne je podmieniają.
      tag: `chat:${event.conversationId}`,
      icon: '/favicon.svg',
      silent: true,
      requireInteraction: strong,
    })
    notification.onclick = () => {
      window.focus()
      open()
      notification.close()
    }
  } catch {
    // Niektóre przeglądarki pozwalają na powiadomienia tylko z service workera.
  }
}

// ---------------------------------------------------------------- tytuł karty i ikona

let baseIcon: HTMLImageElement | null = null
let originalHref: string | null = null

/** Kropka z liczbą na ikonie strony, gdy są nieprzeczytane wiadomości. */
export function useFaviconBadge(count: number) {
  useEffect(() => {
    const link = document.querySelector<HTMLLinkElement>('link[rel="icon"]')
    if (!link) return
    originalHref ??= link.href
    if (count === 0) {
      link.href = originalHref
      return
    }
    let cancelled = false
    const draw = (image: HTMLImageElement) => {
      if (cancelled) return
      const size = 64
      const canvas = document.createElement('canvas')
      canvas.width = size
      canvas.height = size
      const context = canvas.getContext('2d')
      if (!context) return
      context.drawImage(image, 0, 0, size, size)
      const radius = 18
      context.beginPath()
      context.arc(size - radius, radius, radius, 0, Math.PI * 2)
      context.fillStyle = '#dc2626'
      context.fill()
      context.lineWidth = 4
      context.strokeStyle = '#ffffff'
      context.stroke()
      context.fillStyle = '#ffffff'
      context.font = 'bold 24px system-ui, sans-serif'
      context.textAlign = 'center'
      context.textBaseline = 'middle'
      context.fillText(count > 9 ? '9+' : String(count), size - radius, radius + 1)
      link.href = canvas.toDataURL('image/png')
    }
    if (baseIcon?.complete) {
      draw(baseIcon)
    } else {
      const image = new Image()
      image.onload = () => {
        baseIcon = image
        draw(image)
      }
      image.src = originalHref
    }
    return () => {
      cancelled = true
    }
  }, [count])
}
