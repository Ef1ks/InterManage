import { ChevronRightIcon } from 'lucide-react'
import { Link, useSearchParams } from 'react-router'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { SearchInput } from '@/components/SearchInput'
import { Button } from '@/components/ui/button'
import { recordPaths, useStaffRecords } from '@/features/staff-records/api'
import { contractSummary, daysWord, formatDate } from '@/features/staff-records/labels'
import { Chip, Initials } from '@/features/staff/list-parts'
import { people } from '@/features/staff/people'
import { StoreSwitch } from '@/features/staff/StoreSwitch'
import { useCurrentStore } from '@/features/stores/current-store'
import { matchesSearch } from '@/lib/search'
import type { StaffRecordRow, StoreSummary } from '@/lib/types'
import { useSearchParamText } from '@/lib/use-search-param-text'
import { cn } from '@/lib/utils'

const ALL = 'wszystkie'
/** Filtr „do sprawdzenia”, w adresie jako `?sprawy=tak`. */
const WITH_ISSUES = 'tak'

/** Kolumny jak na liście pracowników: osoba (najszersza), umowa, urlop (do prawej), sprawy i strzałka. */
const COLUMNS = 'md:grid-cols-[minmax(0,1.3fr)_minmax(0,1.3fr)_7rem_9rem_1rem]'

const issuesWord = (count: number) =>
  count === 1 ? 'sprawa' : [2, 3, 4].includes(count % 10) && ![12, 13, 14].includes(count % 100) ? 'sprawy' : 'spraw'

/**
 * Lista Kadr: przy każdym pracowniku umowa, urlop do wykorzystania i liczba spraw do sprawdzenia. Każdy sklep ma swoich
 * ludzi, więc lista startuje od bieżącego sklepu, a pozostałe sklepy firmy są na przełączniku.
 */
export function RecordsList({ storeId }: { storeId: string }) {
  const { data: rows, isError, refetch } = useStaffRecords(storeId)
  const current = useCurrentStore()
  const { text, setText, debounced } = useSearchParamText('szukaj')
  const [params, setParams] = useSearchParams()

  const store = params.get('sklep') ?? storeId
  const allStores = store === ALL
  const department = params.get('dzial') ?? ALL
  const onlyIssues = params.get('sprawy') === WITH_ISSUES

  const setFilter = (param: string, value: string) =>
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous)
        const fallback = param === 'sklep' ? storeId : ALL
        if (value === fallback || value === '') next.delete(param)
        else next.set(param, value)
        return next
      },
      { replace: true },
    )

  // Sklepy i działy biorą się z samych wierszy: Kadry mają własne uprawnienie i nie czytają listy sklepów z Pracowników.
  const stores = new Map<string, StoreSummary>()
  if (current) stores.set(current.id, current)
  for (const row of rows ?? []) {
    if (!stores.has(row.storeId)) {
      stores.set(row.storeId, { id: row.storeId, number: row.storeNumber, name: row.storeName, active: true })
    }
  }
  const storeList = [...stores.values()].sort((a, b) => a.number.localeCompare(b.number))
  const manyStores = storeList.length > 1

  const inScope = (rows ?? []).filter(
    (row) =>
      (allStores || row.storeId === store) &&
      matchesSearch([row.displayName, row.positionName, row.departmentName, contractSummary(row)], debounced),
  )
  const scopeWorking = inScope.filter((row) => row.endedAt === null)
  const issuesCount = scopeWorking.filter((row) => row.warningCount > 0).length
  const departments = [...new Map(scopeWorking.map((row) => [row.departmentId, row.departmentName])).entries()].sort(
    (a, b) => a[1].localeCompare(b[1], 'pl'),
  )
  const visible = inScope.filter(
    (row) => (department === ALL || row.departmentId === department) && (!onlyIssues || row.warningCount > 0),
  )
  const working = visible.filter((row) => row.endedAt === null)
  const ended = visible.filter((row) => row.endedAt !== null)
  const groups = departments
    .map(([id, name]) => ({ id, name, rows: working.filter((row) => row.departmentId === id) }))
    .filter((group) => group.rows.length > 0)

  const clearFilters = () => {
    setText('')
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous)
        for (const key of ['dzial', 'sprawy', 'szukaj']) next.delete(key)
        return next
      },
      { replace: true },
    )
  }

  return (
    <>
      <PageHeader
        title="Kadry"
        description="Umowy, staż, urlopy i terminy pracowników. Dane tylko dla osób upoważnionych."
      />
      {isError ? (
        <FullPageMessage
          title="Nie udało się wczytać kadr"
          action={{ label: 'Spróbuj ponownie', onClick: () => void refetch() }}
          inline
        />
      ) : !rows ? (
        <div className="h-72 animate-pulse rounded-2xl bg-muted" aria-busy />
      ) : rows.length === 0 ? (
        <FullPageMessage
          title="Nie ma jeszcze pracowników"
          description="Dodaj ich w zakładce Pracownicy, a tutaj uzupełnisz umowy i urlopy."
          inline
        />
      ) : (
        <>
          <div className="mb-3 flex flex-wrap items-center gap-2">
            <SearchInput
              value={text}
              onChange={setText}
              placeholder="Szukaj: nazwisko, stanowisko, umowa"
              className="w-full min-w-0 sm:w-80"
            />
            {manyStores && (
              <StoreSwitch
                label="Sklep"
                stores={storeList}
                value={store}
                onChange={(value) => setFilter('sklep', value)}
                all={ALL}
              />
            )}
          </div>

          {departments.length > 1 && (
            <nav aria-label="Działy" className="-mx-1 mb-5 flex gap-1.5 overflow-x-auto px-1 pb-1">
              <Chip active={department === ALL} onClick={() => setFilter('dzial', ALL)} count={scopeWorking.length}>
                Wszystkie działy
              </Chip>
              {departments.map(([id, name]) => (
                <Chip
                  key={id}
                  active={department === id}
                  onClick={() => setFilter('dzial', id)}
                  count={scopeWorking.filter((row) => row.departmentId === id).length}
                >
                  {name}
                </Chip>
              ))}
            </nav>
          )}

          <div className="mb-2 flex flex-wrap items-baseline gap-x-3 gap-y-1 text-sm">
            <span className="font-medium tabular-nums">{people(working.length)}</span>
            {issuesCount > 0 && (
              <button
                type="button"
                aria-pressed={onlyIssues}
                onClick={() => setFilter('sprawy', onlyIssues ? ALL : WITH_ISSUES)}
                className={cn(
                  'inline-flex items-center gap-1.5 rounded-md px-1.5 py-0.5 text-muted-foreground outline-none transition-colors duration-150 hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring',
                  onlyIssues && 'bg-amber-200 text-amber-950 hover:text-amber-950 dark:bg-amber-900 dark:text-amber-50',
                )}
              >
                <span aria-hidden className="size-2 rounded-full bg-amber-500" />
                <span className="tabular-nums">{`${people(issuesCount)} ze sprawami do sprawdzenia`}</span>
              </button>
            )}
          </div>

          {groups.length === 0 && ended.length === 0 ? (
            <div className="rounded-2xl border bg-card px-6 py-14 text-center">
              <p className="font-medium">Nikt nie pasuje do filtrów</p>
              <p className="mt-1 text-sm text-muted-foreground">Zmień wyszukiwanie albo pokaż wszystkie działy.</p>
              <Button variant="outline" className="mt-4" onClick={clearFilters}>
                Wyczyść filtry
              </Button>
            </div>
          ) : (
            <div className="grid gap-4">
              {groups.length > 0 && (
                <div className="overflow-hidden rounded-2xl border bg-card">
                  <div
                    aria-hidden
                    className={cn('hidden gap-x-4 border-b px-4 py-2.5 text-xs text-muted-foreground md:grid', COLUMNS)}
                  >
                    <span className="pl-12">Pracownik</span>
                    <span>Umowa</span>
                    <span className="text-right" title="Urlop wypoczynkowy do wykorzystania w tym roku">
                      Urlop
                    </span>
                    <span>Do sprawdzenia</span>
                  </div>
                  {groups.map((group) => (
                    <section key={group.id} aria-label={group.name}>
                      <h2 className="flex items-baseline gap-2 bg-muted/60 px-4 py-2 text-sm font-medium">
                        {group.name}
                        <span className="text-xs font-normal text-muted-foreground tabular-nums">
                          {people(group.rows.length)}
                        </span>
                      </h2>
                      <ul className="divide-y">
                        {group.rows.map((row) => (
                          <RecordRow key={row.employeeId} row={row} showStore={manyStores && allStores} />
                        ))}
                      </ul>
                    </section>
                  ))}
                </div>
              )}

              {ended.length > 0 && (
                <details className="group rounded-2xl border bg-card">
                  <summary className="flex cursor-pointer list-none items-center gap-2 px-4 py-3 text-sm font-medium outline-none select-none focus-visible:ring-2 focus-visible:ring-ring [&::-webkit-details-marker]:hidden">
                    <ChevronRightIcon
                      aria-hidden
                      className="size-4 text-muted-foreground transition-transform duration-200 ease-out group-open:rotate-90 motion-reduce:transition-none"
                    />
                    Zakończyli pracę
                    <span className="text-xs font-normal text-muted-foreground tabular-nums">
                      {people(ended.length)}
                    </span>
                  </summary>
                  <ul className="divide-y border-t">
                    {ended.map((row) => (
                      <RecordRow key={row.employeeId} row={row} showStore={manyStores && allStores} ended />
                    ))}
                  </ul>
                </details>
              )}
            </div>
          )}
        </>
      )}
    </>
  )
}

/** Cały wiersz prowadzi do profilu w Kadrach. */
function RecordRow({ row, showStore, ended }: { row: StaffRecordRow; showStore: boolean; ended?: boolean }) {
  return (
    <li>
      <Link
        to={recordPaths.record(row.employeeId)}
        className={cn(
          'grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-1.5 px-4 py-3 transition-colors duration-150 outline-none hover:bg-muted/50 focus-visible:bg-muted/50',
          COLUMNS,
        )}
      >
        <div className="flex min-w-0 items-center gap-3">
          <Initials name={row.displayName} muted={ended} />
          <div className="min-w-0">
            <div className={cn('truncate font-medium', ended && 'text-muted-foreground')}>{row.displayName}</div>
            <div className="truncate text-xs text-muted-foreground">
              {showStore && <span title={row.storeName}>sklep {row.storeNumber} · </span>}
              {ended ? `${row.departmentName} · ${row.positionName}` : row.positionName}
            </div>
          </div>
        </div>
        <div className="col-start-1 row-start-2 min-w-0 pl-12 text-sm md:col-start-2 md:row-start-1 md:pl-0">
          <div className={cn('md:truncate', !row.contractKind && 'text-muted-foreground')}>{contractSummary(row)}</div>
          {row.contractEnd && (
            <div className="truncate text-xs text-muted-foreground">do {formatDate(row.contractEnd)}</div>
          )}
        </div>
        <div
          className={cn(
            'col-start-1 row-start-3 pl-12 text-sm tabular-nums md:col-start-3 md:row-start-1 md:pl-0 md:text-right',
            row.remainingLeave === null && 'hidden md:block',
          )}
        >
          {row.remainingLeave === null ? (
            <span className="text-muted-foreground">—</span>
          ) : (
            <span className={cn(row.remainingLeave < 0 && 'font-medium text-red-700 dark:text-red-300')}>
              {row.remainingLeave} {daysWord(row.remainingLeave)}
              <span className="text-xs text-muted-foreground md:hidden"> urlopu</span>
            </span>
          )}
        </div>
        <div
          className={cn(
            'col-start-1 row-start-4 pl-12 md:col-start-4 md:row-start-1 md:pl-0',
            row.warningCount === 0 && 'hidden md:block',
          )}
        >
          <Issues row={row} />
        </div>
        <ChevronRightIcon
          aria-hidden
          className="col-start-2 row-span-4 row-start-1 size-4 text-muted-foreground md:col-start-5 md:row-span-1"
        />
      </Link>
    </li>
  )
}

/** Kropka z liczbą zamiast plakietki: czerwona przy sprawie pilnej, bursztynowa przy ostrzeżeniu. */
function Issues({ row }: { row: StaffRecordRow }) {
  if (row.warningCount === 0) return <span className="text-sm text-muted-foreground">—</span>
  const critical = row.worstWarning === 'ERROR'
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 text-sm',
        critical ? 'font-medium text-red-700 dark:text-red-300' : 'text-amber-800 dark:text-amber-300',
      )}
    >
      <span
        aria-hidden
        className={cn('size-2 rounded-full', critical ? 'bg-red-600 dark:bg-red-400' : 'bg-amber-500')}
      />
      <span className="tabular-nums">{row.warningCount}</span> {issuesWord(row.warningCount)}
    </span>
  )
}
