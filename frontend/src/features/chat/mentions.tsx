import { useState, type KeyboardEvent, type ReactNode, type RefObject } from 'react'
import { AtSignIcon, UsersRoundIcon } from 'lucide-react'

import { UserAvatar } from '@/components/UserAvatar'
import type { ChatMention, ChatMentionDepartment, ChatPerson } from '@/lib/types'
import { cn } from '@/lib/utils'

/** Do wyboru po „@”: osoba (`user:<id>`), dział (`department:<id>`) albo wszyscy (`all`). */
export type MentionOption = {
  token: string
  label: string
  kind: ChatMention['kind']
  hint: string
  avatarUrl?: string | null
}

const MAX_OPTIONS = 8
/** „@” i to, co po nim wpisano do kursora (bez spacji): to szukamy. */
const QUERY = /(^|\s)@([^\s@]{0,40})$/

const normalize = (text: string) =>
  text.normalize('NFD').replace(/\p{M}/gu, '').replace(/ł/g, 'l').replace(/Ł/g, 'L').toLowerCase()

/** Pasuje, gdy któreś słowo nazwy zaczyna się od wpisanego tekstu (bez wielkości liter i polskich znaków). */
function matches(label: string, query: string): boolean {
  if (!query) return true
  const wanted = normalize(query)
  return normalize(label)
    .split(/\s+/)
    .some((word) => word.startsWith(wanted))
}

export function mentionOptions(
  members: ChatPerson[],
  departments: ChatMentionDepartment[],
  canMentionAll: boolean,
  meId: string,
): MentionOption[] {
  return [
    ...(canMentionAll ? [{ token: 'all', label: 'wszyscy', kind: 'ALL' as const, hint: 'cała rozmowa' }] : []),
    ...departments.map((department) => ({
      token: `department:${department.id}`,
      label: department.name,
      kind: 'DEPARTMENT' as const,
      hint: `dział · ${department.memberCount} ${department.memberCount === 1 ? 'osoba' : 'osób'}`,
    })),
    ...members
      .filter((member) => member.id !== meId)
      .map((member) => ({
        token: `user:${member.id}`,
        label: member.name,
        kind: 'USER' as const,
        hint: '',
        avatarUrl: member.avatarUrl,
      })),
  ]
}

/**
 * Wybór oznaczenia w polu wiadomości: po „@” lista pasujących osób, działów i „wszyscy”; strzałki, Enter albo Tab
 * wybiera, Esc chowa. Wybrane trzyma jako token → nazwa, a przy wysyłce bierze tylko te, które dalej są w treści.
 */
export function useMentionPicker(
  input: RefObject<HTMLTextAreaElement | null>,
  text: string,
  setText: (text: string) => void,
  options: MentionOption[],
) {
  const [query, setQuery] = useState<{ text: string; start: number } | null>(null)
  const [active, setActive] = useState(0)
  const [picked, setPicked] = useState<Map<string, string>>(new Map())

  const found = query ? options.filter((option) => matches(option.label, query.text)).slice(0, MAX_OPTIONS) : []
  const open = query !== null && found.length > 0

  /** Po każdej zmianie treści albo kursora: czy przed kursorem stoi „@coś”. */
  const update = (value: string, caret: number) => {
    const match = QUERY.exec(value.slice(0, caret))
    if (!match) {
      setQuery(null)
      return
    }
    const start = caret - match[2].length - 1
    setQuery((current) => {
      if (current?.start !== start || current.text !== match[2]) setActive(0)
      return { text: match[2], start }
    })
  }

  const choose = (option: MentionOption) => {
    if (!query) return
    const element = input.current
    const caret = element?.selectionStart ?? text.length
    const inserted = `@${option.label} `
    setText(text.slice(0, query.start) + inserted + text.slice(caret))
    setPicked((current) => new Map(current).set(option.token, option.label))
    setQuery(null)
    const position = query.start + inserted.length
    requestAnimationFrame(() => {
      element?.focus()
      element?.setSelectionRange(position, position)
    })
  }

  /** {@code true}: klawisz obsłużyła lista, pole ma go już nie brać (np. Enter nie wysyła). */
  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>): boolean => {
    if (!open) return false
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      const step = event.key === 'ArrowDown' ? 1 : -1
      setActive((current) => (current + step + found.length) % found.length)
      return true
    }
    if ((event.key === 'Enter' && !event.shiftKey) || event.key === 'Tab') {
      event.preventDefault()
      choose(found[Math.min(active, found.length - 1)])
      return true
    }
    if (event.key === 'Escape') {
      event.preventDefault()
      setQuery(null)
      return true
    }
    return false
  }

  /** Tokeny oznaczeń, które dalej stoją w treści jako `@nazwa`. */
  const tokens = (body: string) => [...picked].filter(([, label]) => body.includes(`@${label}`)).map(([token]) => token)

  const reset = () => {
    setPicked(new Map())
    setQuery(null)
  }

  const list = open ? (
    <MentionList options={found} active={Math.min(active, found.length - 1)} onChoose={choose} onHover={setActive} />
  ) : null

  return { update, onKeyDown, tokens, reset, list, open }
}

function MentionList({
  options,
  active,
  onChoose,
  onHover,
}: {
  options: MentionOption[]
  active: number
  onChoose: (option: MentionOption) => void
  onHover: (index: number) => void
}) {
  return (
    <ul
      role="listbox"
      aria-label="Oznacz"
      className="glass-float absolute right-0 bottom-full left-0 mb-2 max-h-72 overflow-y-auto rounded-xl p-1"
    >
      {options.map((option, index) => (
        <li key={option.token} role="option" aria-selected={index === active}>
          <button
            type="button"
            // Klik nie może zabrać fokusu polu, inaczej kursor i zaznaczenie znikają przed wstawieniem.
            onMouseDown={(event) => event.preventDefault()}
            onClick={() => onChoose(option)}
            onMouseEnter={() => onHover(index)}
            className={cn(
              'flex w-full items-center gap-2.5 rounded-lg px-2 py-1.5 text-left text-sm',
              index === active && 'bg-muted',
            )}
          >
            {option.kind === 'USER' ? (
              <UserAvatar name={option.label} url={option.avatarUrl} className="size-7 text-[0.7rem]" />
            ) : (
              <span
                className={cn(
                  'flex size-7 shrink-0 items-center justify-center rounded-full',
                  option.kind === 'ALL'
                    ? 'bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100'
                    : 'bg-violet-200 text-violet-950 dark:bg-violet-900/70 dark:text-violet-100',
                )}
              >
                {option.kind === 'ALL' ? <AtSignIcon className="size-4" /> : <UsersRoundIcon className="size-4" />}
              </span>
            )}
            <span className="min-w-0 flex-1 truncate font-medium">{option.label}</span>
            {option.hint && <span className="shrink-0 text-xs text-muted-foreground">{option.hint}</span>}
          </button>
        </li>
      ))}
    </ul>
  )
}

/** Treść wiadomości z wyróżnionymi `@oznaczeniami` (tylko te, które serwer zapisał przy wiadomości). */
export function MentionText({ body, mentions, mine }: { body: string; mentions: ChatMention[]; mine: boolean }) {
  if (mentions.length === 0) return <>{body}</>
  const labels = [...new Set(mentions.map((mention) => mention.label))]
    .sort((a, b) => b.length - a.length)
    .map((label) => label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
  const pattern = new RegExp(`@(?:${labels.join('|')})`, 'g')
  const parts: ReactNode[] = []
  let last = 0
  for (const match of body.matchAll(pattern)) {
    const index = match.index ?? 0
    if (index > last) parts.push(body.slice(last, index))
    parts.push(
      <span
        key={index}
        className={cn(
          'rounded px-0.5 font-semibold',
          mine ? 'bg-primary-foreground/20' : 'bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-100',
        )}
      >
        {match[0]}
      </span>,
    )
    last = index + match[0].length
  }
  if (last < body.length) parts.push(body.slice(last))
  return <>{parts}</>
}
