import type { ChatConversation } from './types'

// Formaty tworzone raz, nie przy każdym wierszu listy.
const time = new Intl.DateTimeFormat('pl-PL', { hour: '2-digit', minute: '2-digit' })
const dayMonth = new Intl.DateTimeFormat('pl-PL', { day: 'numeric', month: 'short' })
const weekday = new Intl.DateTimeFormat('pl-PL', { weekday: 'short' })
const fullDay = new Intl.DateTimeFormat('pl-PL', { weekday: 'long', day: 'numeric', month: 'long' })

const sameDay = (a: Date, b: Date) =>
  a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()

export const messageTime = (iso: string) => time.format(new Date(iso))

export function listTime(iso: string): string {
  const date = new Date(iso)
  const now = new Date()
  if (sameDay(date, now)) return time.format(date)
  if (now.getTime() - date.getTime() < 6 * 86_400_000) return weekday.format(date)
  return dayMonth.format(date)
}

export function dayLabel(iso: string): string {
  const date = new Date(iso)
  const now = new Date()
  if (sameDay(date, now)) return 'Dziś'
  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  if (sameDay(date, yesterday)) return 'Wczoraj'
  const label = fullDay.format(date)
  return label.charAt(0).toUpperCase() + label.slice(1)
}

export const isSameDay = (a: string, b: string) => sameDay(new Date(a), new Date(b))

export function lastSeen(iso: string | null, online: boolean): string {
  if (online) return 'online'
  if (!iso) return 'jeszcze nie korzystał(a)'
  const minutes = Math.max(1, Math.round((Date.now() - new Date(iso).getTime()) / 60_000))
  if (minutes < 60) return `${minutes} min temu`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours} godz. temu`
  const days = Math.round(hours / 24)
  if (days === 1) return 'wczoraj'
  if (days < 30) return `${days} dni temu`
  return dayMonth.format(new Date(iso))
}

export function conversationTitle(conversation: ChatConversation): string {
  if (conversation.kind === 'DIRECT') return conversation.peer?.name ?? 'Rozmowa'
  if (conversation.kind === 'STORE') return conversation.name ?? defaultStoreChatName(conversation)
  return conversation.name ?? 'Grupa'
}

/** Nazwa czatu sklepu bez własnej nazwy (zarządzający czatem może nadać inną). */
export function defaultStoreChatName(conversation: ChatConversation): string {
  return `Cały sklep ${conversation.storeNumber ?? ''}`.trim()
}

export function preview(conversation: ChatConversation, meId: string): string {
  const last = conversation.lastMessage
  if (!last) {
    if (conversation.kind === 'GROUP') return `${conversation.memberCount} osób`
    return conversation.kind === 'STORE' ? 'Wszyscy z tego sklepu' : 'Napisz pierwszą wiadomość'
  }
  const who =
    last.authorId === meId ? 'Ty: ' : conversation.kind === 'DIRECT' ? '' : `${(last.authorName ?? '').split(' ')[0]}: `
  if (last.retracted) return `${who}wiadomość cofnięta`
  if (last.preview) return who + last.preview
  return `${who}${last.attachmentCount === 1 ? 'plik' : `pliki (${last.attachmentCount})`}`
}

export function fileSize(bytes: number): string {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} kB`
  return `${(bytes / 1024 / 1024).toFixed(1).replace('.', ',')} MB`
}
