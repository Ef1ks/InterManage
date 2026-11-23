import { Loader2Icon } from 'lucide-react'
import type { ReactNode } from 'react'

import { LoadingPanel } from '@/components/LoadingPanel'
import { UserAvatar } from '@/components/UserAvatar'
import type { ChatOther, ChatPerson, ChatWorking } from '@/lib/types'
import { cn } from '@/lib/utils'

import { usePeople } from './api'
import { lastSeen } from './format'

type Props = {
  storeId: string
  meId: string
  /** Klik w osobę otwiera rozmowę z nią. */
  onOpen: (userId: string) => void
  /** Osoba, z którą rozmowa właśnie się otwiera (kółko przy niej, reszta zablokowana do końca). */
  opening?: string | null
  /**
   * Członkowie otwartej rozmowy: panel pokazuje tylko ich (w pracy i pozostali), a członków spoza sklepu na końcu.
   * Bez nich (czat całego sklepu albo brak otwartej rozmowy) wszystkie osoby sklepu.
   */
  members?: ChatPerson[]
}

const short = (value: string | null) => (value ? value.slice(0, 5) : '')

/**
 * Osoby sklepu: na górze w pracy teraz (zmiana w grafiku albo odbita karta; aktywny po odbiciu), niżej reszta
 * z ostatnią aktywnością w aplikacji.
 */
export function PeoplePanel({ storeId, meId, onOpen, opening = null, members }: Props) {
  const { data: people, isPending } = usePeople(storeId)
  if (isPending || !people) return <LoadingPanel className="m-3 h-64 rounded-xl" />

  const memberIds = members ? new Set(members.map((member) => member.id)) : null
  const data = memberIds
    ? {
        ...people,
        working: people.working.filter((person) => memberIds.has(person.userId)),
        others: people.others.filter((person) => memberIds.has(person.userId)),
      }
    : people
  const listed = new Set([...data.working, ...data.others].map((person) => person.userId))
  const elsewhere = (members ?? []).filter((member) => !listed.has(member.id))
  const punched = data.working.filter((person) => person.punched)
  return (
    <div className="flex h-full min-h-0 flex-col overflow-y-auto py-3">
      <Section title="W pracy" count={`${punched.length} z ${data.working.length}`}>
        {data.working.length === 0 && <Empty>Nikt nie ma teraz zmiany w grafiku.</Empty>}
        {data.working.map((person) => (
          <PersonRow
            key={person.userId}
            person={person}
            me={person.userId === meId}
            onOpen={onOpen}
            opening={opening}
            active={person.punched}
            detail={workingDetail(person, data.hours)}
          />
        ))}
      </Section>
      <Section title="Pozostali" count={String(data.others.length + elsewhere.length)}>
        {data.others.map((person) => (
          <PersonRow
            key={person.userId}
            person={person}
            me={person.userId === meId}
            onOpen={onOpen}
            opening={opening}
            active={person.online}
            detail={lastSeen(person.lastSeenAt, person.online)}
          />
        ))}
        {elsewhere.map((member) => (
          <PersonRow
            key={member.id}
            person={{ userId: member.id, name: member.name, avatarUrl: member.avatarUrl }}
            me={member.id === meId}
            onOpen={onOpen}
            opening={opening}
            active={false}
            detail="z innego sklepu"
          />
        ))}
      </Section>
    </div>
  )
}

/** Bez dostępu do Czasu pracy bez godzin: stanowisko i to, czy odbił(a) kartę. */
function workingDetail(person: ChatWorking, hours: boolean): string {
  if (!hours) {
    const state = person.punched ? 'w pracy' : 'nie odbił(a) karty'
    return person.position ? `${person.position} · ${state}` : state
  }
  const shift = person.start ? `${short(person.start)}–${short(person.end)}` : 'poza grafikiem'
  if (!person.since) return `${shift} · nie odbił(a) karty`
  return `${shift} · od ${person.since.slice(11, 16)}`
}

function Section({ title, count, children }: { title: string; count: string; children: ReactNode }) {
  return (
    <section className="mb-4">
      <h3 className="mb-1 flex items-baseline justify-between px-4 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
        {title}
        <span className="font-normal tabular-nums normal-case">{count}</span>
      </h3>
      <ul>{children}</ul>
    </section>
  )
}

function Empty({ children }: { children: ReactNode }) {
  return <li className="px-4 py-2 text-xs text-muted-foreground">{children}</li>
}

function PersonRow({
  person,
  me,
  active,
  detail,
  onOpen,
  opening,
}: {
  person: Pick<ChatWorking | ChatOther, 'userId' | 'name' | 'avatarUrl'>
  me: boolean
  active: boolean
  detail: string
  onOpen: (userId: string) => void
  opening: string | null
}) {
  const loading = opening === person.userId
  return (
    <li>
      <button
        type="button"
        disabled={me || opening !== null}
        aria-busy={loading}
        onClick={() => onOpen(person.userId)}
        title={me ? undefined : `Napisz do: ${person.name}`}
        className="flex w-full items-center gap-2.5 px-4 py-1.5 text-left hover:bg-muted disabled:hover:bg-transparent"
      >
        <span className="relative shrink-0">
          <UserAvatar
            name={person.name}
            url={person.avatarUrl}
            className={cn('size-8 text-xs', !active && 'opacity-70')}
          />
          {loading && (
            <span className="absolute inset-0 flex items-center justify-center rounded-full bg-card/70">
              <Loader2Icon className="size-4 animate-spin" aria-hidden />
            </span>
          )}
          <span
            aria-hidden
            className={cn(
              'absolute -right-0.5 -bottom-0.5 size-3 rounded-full border-2 border-card',
              active ? 'bg-emerald-500' : 'bg-zinc-300 dark:bg-zinc-600',
            )}
          />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-medium">
            {person.name}
            {me && <span className="font-normal text-muted-foreground"> (Ty)</span>}
          </span>
          <span className="block truncate text-xs text-muted-foreground">{detail}</span>
        </span>
      </button>
    </li>
  )
}
