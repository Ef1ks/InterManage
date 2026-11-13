import { ChevronRightIcon } from 'lucide-react'
import { Link } from 'react-router'

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { useTimePresence } from '@/features/staff-time/api'
import type { PresencePerson } from '@/lib/types'
import { cn } from '@/lib/utils'

import { plural } from './plural'

/** Ile osób z listy widać od razu; reszta jako „i N więcej”. */
const VISIBLE = 4

const hm = (time: string | null) => (time ? time.slice(0, 5).replace(/^0/, '') : '')
const clock = (dateTime: string | null) => (dateTime ? hm(dateTime.slice(11)) : '')

/**
 * Obecność teraz: osoby z odbitym wejściem w sklepie wobec osób, które według grafiku mają w tej chwili zmianę.
 * Pod spodem kto ma zmianę, a się nie odbił, i kto jest w pracy poza grafikiem. Odświeża się co minutę.
 */
export function PresenceCard({ storeId }: { storeId: string }) {
  const { data, isPending, isError } = useTimePresence(storeId, true)

  let body
  if (isPending) {
    body = <div className="h-40 animate-pulse rounded-lg bg-muted" />
  } else if (isError) {
    body = <p className="text-sm text-muted-foreground">Nie udało się wczytać obecności.</p>
  } else {
    const complete = data.present >= data.scheduledNow
    const share = data.scheduledNow > 0 ? Math.min(data.present / data.scheduledNow, 1) : data.present > 0 ? 1 : 0
    body = (
      <div className="space-y-4">
        <div>
          <p className="flex items-baseline gap-1.5 tabular-nums">
            <span className="text-5xl leading-none font-semibold tracking-tight">{data.present}</span>
            <span className="text-2xl font-medium text-muted-foreground">/ {data.scheduledNow}</span>
          </p>
          <p className="mt-2 text-sm text-muted-foreground">
            {data.scheduledNow === 0
              ? 'Nikt nie ma teraz zmiany w grafiku'
              : `odbitych z ${data.scheduledNow} ${plural(data.scheduledNow, 'osoby', 'osób', 'osób')} w grafiku teraz`}
          </p>
          <div
            className="mt-3 h-2 overflow-hidden rounded-full bg-muted"
            role="img"
            aria-label={`${data.present} z ${data.scheduledNow} w pracy`}
          >
            <div
              className={cn(
                'h-full rounded-full transition-[width] duration-500 ease-out motion-reduce:transition-none',
                complete ? 'bg-emerald-500' : 'bg-amber-500',
              )}
              style={{ width: `${share * 100}%` }}
            />
          </div>
          <p className="mt-2 text-xs text-muted-foreground">
            Dziś w grafiku: {data.scheduledToday} {plural(data.scheduledToday, 'osoba', 'osoby', 'osób')}
            {data.nextStart
              ? ` · następna zmiana ${hm(data.nextStart)} (${data.nextCount} ${plural(data.nextCount, 'osoba', 'osoby', 'osób')})`
              : ''}
          </p>
        </div>

        {data.missing.length > 0 ? (
          <PeopleList
            title="Mają zmianę, nie odbili się"
            tone="amber"
            people={data.missing}
            detail={(person) => `zmiana ${hm(person.start)}–${hm(person.end)}`}
          />
        ) : null}
        {data.unscheduled.length > 0 ? (
          <PeopleList
            title="W pracy poza grafikiem"
            tone="sky"
            people={data.unscheduled}
            detail={(person) => `od ${clock(person.since)}`}
          />
        ) : null}
      </div>
    )
  }

  return (
    <Card>
      <CardHeader className="flex items-start justify-between gap-3">
        <div className="space-y-1">
          <CardTitle>W pracy teraz</CardTitle>
          <CardDescription>Odbite wejścia wobec grafiku</CardDescription>
        </div>
        <Link
          to="/czas-pracy"
          className="-mr-1 flex items-center gap-0.5 rounded-md px-1 text-sm text-muted-foreground transition-colors hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
        >
          Czas pracy
          <ChevronRightIcon className="size-4" />
        </Link>
      </CardHeader>
      <CardContent>{body}</CardContent>
    </Card>
  )
}

const DOTS = {
  amber: 'bg-amber-500',
  sky: 'bg-sky-500',
} as const

function PeopleList({
  title,
  tone,
  people,
  detail,
}: {
  title: string
  tone: keyof typeof DOTS
  people: PresencePerson[]
  detail: (person: PresencePerson) => string
}) {
  const hidden = people.length - VISIBLE
  return (
    <div className="space-y-1.5 border-t pt-3">
      <p className="flex items-center gap-2 text-xs font-medium text-muted-foreground">
        <span className={cn('size-2 rounded-full', DOTS[tone])} aria-hidden />
        {title} ({people.length})
      </p>
      <ul className="space-y-1 text-sm">
        {people.slice(0, VISIBLE).map((person) => (
          <li key={person.employeeId} className="flex items-baseline justify-between gap-3">
            <span className="min-w-0 truncate" title={person.position}>
              {person.name}
            </span>
            <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{detail(person)}</span>
          </li>
        ))}
      </ul>
      {hidden > 0 ? <p className="text-xs text-muted-foreground">i {hidden} więcej</p> : null}
    </div>
  )
}
