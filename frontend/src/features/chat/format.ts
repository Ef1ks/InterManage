import type { ChatConversation } from '@/lib/types'

const time = new Intl.DateTimeFormat('pl-PL', { hour: '2-digit', minute: '2-digit' })
const dayMonth = new Intl.DateTimeFormat('pl-PL', { day: 'numeric', month: 'short' })
const weekday = new Intl.DateTimeFormat('pl-PL', { weekday: 'short' })
const fullDay = new Intl.DateTimeFormat('pl-PL', { weekday: 'long', day: 'numeric', month: 'long' })

function sameDay(a: Date, b: Date) {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
}

/** Godzina wiadomości. */
export function messageTime(iso: string): string {
  return time.format(new Date(iso))
}

/** Na liście rozmów: dziś godzina, w tym tygodniu dzień, dalej data. */
export function listTime(iso: string): string {
  const date = new Date(iso)
  const now = new Date()
  if (sameDay(date, now)) return time.format(date)
  if (now.getTime() - date.getTime() < 6 * 86_400_000) return weekday.format(date)
  return dayMonth.format(date)
}

/** Nagłówek dnia w rozmowie. */
export function dayLabel(iso: string): string {
  const date = new Date(iso)
  const now = new Date()
  if (sameDay(date, now)) return 'Dziś'
  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  if (sameDay(date, yesterday)) return 'Wczoraj'
  return fullDay.format(date)
}

export function isSameDay(a: string, b: string): boolean {
  return sameDay(new Date(a), new Date(b))
}

/** „Teraz”, „5 min temu”, „3 godz. temu”, „wczoraj”, „4 dni temu”. */
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

export function fileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} kB`
  return `${(bytes / 1024 / 1024).toFixed(1).replace('.', ',')} MB`
}

export function filesLabel(count: number): string {
  if (count === 1) return 'plik'
  const last = count % 10
  const lastTwo = count % 100
  return last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? `${count} pliki` : `${count} plików`
}
