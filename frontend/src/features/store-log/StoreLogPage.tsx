import { useDeferredValue, useMemo, useState } from 'react'
import { ChevronDownIcon } from 'lucide-react'
import { useSearchParams } from 'react-router'

import { DateField, SegmentedField, SelectField } from '@/components/form'
import { FullPageMessage } from '@/components/FullPageMessage'
import { LoadingPanel } from '@/components/LoadingPanel'
import { PageHeader } from '@/components/PageHeader'
import { SearchInput } from '@/components/SearchInput'
import { Card } from '@/components/ui/card'
import { useCurrentUser } from '@/features/auth/api'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { useStoreLog, useStoreLogFilters, type StoreLogQuery } from '@/features/store-log/api'
import { formatLongDate, toIsoDate } from '@/lib/dates'
import type { StoreEvent, StoreEventKind } from '@/lib/types'
import { useLoadMore } from '@/lib/use-load-more'
import { useSearchParamText } from '@/lib/use-search-param-text'
import { cn } from '@/lib/utils'

type KindFilter = 'all' | 'changes' | 'failed' | 'system'

const KINDS: { value: KindFilter; label: string }[] = [
  { value: 'all', label: 'Wszystko' },
  { value: 'changes', label: 'Zmiany' },
  { value: 'failed', label: 'Odrzucone' },
  { value: 'system', label: 'Problemy systemu' },
]

const KIND_PARAM: Record<KindFilter, string | null> = {
  all: null,
  changes: 'zmiany',
  failed: 'odrzucone',
  system: 'system',
}

const KIND_API: Record<KindFilter, StoreEventKind | null> = {
  all: null,
  changes: 'ACTION',
  failed: 'FAILED',
  system: 'SYSTEM',
}

/** Co znaczy odmowa serwera, krótko dla kierownika. */
function statusLabel(status: number | null) {
  if (status == null) return null
  if (status >= 500) return 'błąd serwera'
  switch (status) {
    case 400:
    case 422:
      return 'błędne dane'
    case 401:
      return 'bez logowania'
    case 403:
      return 'brak uprawnień'
    case 404:
      return 'nie znaleziono'
    case 409:
      return 'konflikt z danymi'
    case 413:
      return 'za duży plik'
    case 429:
      return 'za dużo prób'
    default:
      return status >= 400 ? `odrzucone (${status})` : null
  }
}

const DOT: Record<string, string> = {
  ACTION: 'bg-emerald-500',
  FAILED: 'bg-amber-500',
  WARN: 'bg-amber-500',
  ERROR: 'bg-red-600',
}

function dotOf(event: StoreEvent) {
  if (event.kind === 'SYSTEM' || event.level === 'ERROR') return DOT[event.level] ?? DOT.WARN
  return DOT[event.kind]
}

/**
 * Dziennik sklepu dla kierowników: kto, kiedy i co zmienił w wybranym sklepie, odrzucone próby (brak uprawnień,
 * konflikt) i ostrzeżenia z logów backendu. Właściciel przełącza się też na dziennik firmy (ustawienia, zadania w tle).
 */
export function StoreLogPage() {
  const me = useCurrentUser()
  const store = useCurrentStore()
  const [params, setParams] = useSearchParams()
  const { text: query, setText: setQuery } = useSearchParamText('szukaj')
  const deferredQuery = useDeferredValue(query)
  const company = me.owner && params.get('zakres') === 'firma'
  const storeId = company ? null : (store?.id ?? null)

  const kindParam = params.get('rodzaj')
  const kind = (Object.keys(KIND_PARAM) as KindFilter[]).find((key) => KIND_PARAM[key] === kindParam) ?? 'all'
  const filter: StoreLogQuery = {
    kind: KIND_API[kind],
    module: params.get('modul'),
    userId: params.get('osoba'),
    from: params.get('od'),
    to: params.get('do'),
    query: deferredQuery,
  }

  const setParam = (key: string, value: string | null) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        if (value) next.set(key, value)
        else next.delete(key)
        return next
      },
      { replace: true },
    )

  if (!store && !company) {
    return (
      <>
        <PageHeader title="Dziennik sklepu" />
        <FullPageMessage title="Brak sklepu" description="Dziennik jest prowadzony osobno dla każdego sklepu." inline />
      </>
    )
  }

  return (
    <div>
      <PageHeader
        title="Dziennik sklepu"
        description={
          company
            ? 'Zmiany poza sklepami (użytkownicy, role, sklepy) i problemy zadań w tle.'
            : `Kto, kiedy i co zmienił w sklepie ${store ? storeLabel(store) : ''}, odrzucone próby i problemy systemu.`
        }
        actions={
          me.owner && (
            <SegmentedField
              label="Zakres dziennika"
              options={[
                { value: 'store', label: 'Sklep' },
                { value: 'company', label: 'Firma' },
              ]}
              value={company ? 'company' : 'store'}
              onChange={(value) =>
                setParams(
                  (current) => {
                    const next = new URLSearchParams(current)
                    if (value === 'company') next.set('zakres', 'firma')
                    else next.delete('zakres')
                    // Moduły i osoby są inne w dzienniku firmy.
                    next.delete('modul')
                    next.delete('osoba')
                    return next
                  },
                  { replace: true },
                )
              }
            />
          )
        }
      />
      <StoreLog
        key={storeId ?? 'company'}
        storeId={storeId}
        filter={filter}
        query={query}
        setQuery={setQuery}
        kind={kind}
        setParam={setParam}
      />
    </div>
  )
}

function StoreLog({
  storeId,
  filter,
  query,
  setQuery,
  kind,
  setParam,
}: {
  storeId: string | null
  filter: StoreLogQuery
  query: string
  setQuery: (value: string) => void
  kind: KindFilter
  setParam: (key: string, value: string | null) => void
}) {
  const { data, isPending, isError, refetch, fetchNextPage, hasNextPage, isFetchingNextPage } = useStoreLog(
    storeId,
    filter,
  )
  const { data: options } = useStoreLogFilters(storeId)
  const events = useMemo(() => data?.pages.flatMap((page) => page.items) ?? [], [data])
  const days = useMemo(() => byDay(events), [events])
  const loadMoreRef = useLoadMore<HTMLDivElement>(
    () => fetchNextPage(),
    Boolean(hasNextPage) && !isFetchingNextPage,
    events.length,
  )
  const filtered =
    filter.kind != null ||
    filter.module != null ||
    filter.userId != null ||
    filter.from != null ||
    filter.to != null ||
    filter.query.trim() !== ''

  return (
    <>
      <div className="mb-4 flex flex-wrap items-end gap-x-3 gap-y-3">
        <SearchInput
          className="w-full sm:w-72"
          value={query}
          onChange={setQuery}
          placeholder="Szukaj w opisie zdarzenia"
        />
        <SegmentedField
          label="Rodzaj zdarzeń"
          options={KINDS}
          value={kind}
          onChange={(value) => setParam('rodzaj', KIND_PARAM[value])}
        />
        <SelectField
          className="w-48"
          label="Moduł"
          options={[
            { value: '', label: 'Wszystkie moduły' },
            ...(options?.modules ?? []).map((m) => ({ value: m.key, label: m.label })),
          ]}
          value={filter.module ?? ''}
          onChange={(value) => setParam('modul', value || null)}
        />
        <SelectField
          className="w-52"
          label="Osoba"
          options={[
            { value: '', label: 'Wszyscy' },
            ...(options?.users ?? []).map((u) => ({ value: u.id, label: u.name })),
          ]}
          value={filter.userId ?? ''}
          onChange={(value) => setParam('osoba', value || null)}
        />
        <DateField className="w-40" label="Od" value={filter.from ?? ''} onChange={(v) => setParam('od', v || null)} />
        <DateField className="w-40" label="Do" value={filter.to ?? ''} onChange={(v) => setParam('do', v || null)} />
      </div>

      {isError ? (
        <FullPageMessage
          title="Nie udało się wczytać dziennika"
          action={{ label: 'Spróbuj ponownie', onClick: () => refetch() }}
          inline
        />
      ) : isPending ? (
        <LoadingPanel className="h-96 rounded-xl" />
      ) : events.length === 0 ? (
        <Card className="items-center gap-2 px-6 py-12 text-center">
          <p className="font-medium">{filtered ? 'Nic nie pasuje do filtrów' : 'Dziennik jest jeszcze pusty'}</p>
          <p className="max-w-md text-sm text-muted-foreground">
            {filtered
              ? 'Zmień filtry albo wyczyść wyszukiwanie.'
              : 'Każda zmiana w sklepie (dokumenty, grafik, kasa, ceny…) pojawi się tutaj z osobą i godziną.'}
          </p>
        </Card>
      ) : (
        <div className="space-y-5">
          {days.map(([day, items]) => (
            <section key={day}>
              <h2 className="mb-2 text-sm font-medium text-muted-foreground">{formatLongDate(day)}</h2>
              <Card className="gap-0 divide-y py-0">
                {items.map((event) => (
                  <EventRow key={event.id} event={event} />
                ))}
              </Card>
            </section>
          ))}
          <div ref={loadMoreRef} />
          {isFetchingNextPage && <LoadingPanel className="h-24 rounded-xl" />}
        </div>
      )}
    </>
  )
}

function EventRow({ event }: { event: StoreEvent }) {
  const [open, setOpen] = useState(false)
  const status = event.kind === 'SYSTEM' ? null : statusLabel(event.status)
  const time = new Date(event.occurredAt).toLocaleTimeString('pl-PL')
  return (
    <div>
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        className="flex w-full items-center gap-3 px-4 py-2.5 text-left text-sm hover:bg-muted/50"
      >
        <span className="w-16 shrink-0 text-muted-foreground tabular-nums">{time}</span>
        <span aria-hidden className={cn('size-2 shrink-0 rounded-full', dotOf(event))} />
        <span className="min-w-0 flex-1">
          <span className={cn('block truncate', event.kind === 'SYSTEM' && 'font-mono text-xs')}>{event.summary}</span>
          <span className="block truncate text-xs text-muted-foreground">
            {event.moduleLabel}
            {event.kind === 'SYSTEM' && ` · ${event.level === 'ERROR' ? 'błąd' : 'ostrzeżenie'} systemu`}
          </span>
        </span>
        {status && (
          <span className="shrink-0 rounded-full bg-amber-200 px-2 py-0.5 text-xs font-medium text-amber-950 dark:bg-amber-900/70 dark:text-amber-100">
            {status}
          </span>
        )}
        <span className="hidden w-44 shrink-0 truncate text-right text-muted-foreground sm:block">
          {event.userName ?? (event.kind === 'SYSTEM' ? 'system' : '—')}
        </span>
        <ChevronDownIcon
          aria-hidden
          className={cn(
            'size-4 shrink-0 text-muted-foreground transition-transform duration-150',
            open && 'rotate-180',
          )}
        />
      </button>
      {open && (
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 border-t bg-muted/30 px-4 py-3 text-xs">
          <dt className="text-muted-foreground">Kiedy</dt>
          <dd>{new Date(event.occurredAt).toLocaleString('pl-PL')}</dd>
          <dt className="text-muted-foreground sm:hidden">Kto</dt>
          <dd className="sm:hidden">{event.userName ?? '—'}</dd>
          {event.method && (
            <>
              <dt className="text-muted-foreground">Żądanie</dt>
              <dd className="font-mono break-all">
                {event.method} {event.path}
              </dd>
            </>
          )}
          {event.status != null && (
            <>
              <dt className="text-muted-foreground">Wynik</dt>
              <dd>
                {event.status}
                {event.durationMs != null && ` · ${event.durationMs} ms`}
              </dd>
            </>
          )}
          {event.detail && (
            <>
              <dt className="text-muted-foreground">Szczegóły</dt>
              <dd>
                <pre className="max-h-64 overflow-auto font-mono text-[0.7rem] whitespace-pre-wrap">{event.detail}</pre>
              </dd>
            </>
          )}
        </dl>
      )}
    </div>
  )
}

/** Wpisy po dniach (czas lokalny), w kolejności z serwera: od najnowszych. */
function byDay(events: StoreEvent[]): [string, StoreEvent[]][] {
  const days = new Map<string, StoreEvent[]>()
  for (const event of events) {
    const day = toIsoDate(new Date(event.occurredAt))
    const list = days.get(day)
    if (list) list.push(event)
    else days.set(day, [event])
  }
  return [...days.entries()]
}
