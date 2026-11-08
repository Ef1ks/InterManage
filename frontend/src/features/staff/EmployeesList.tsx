import { useState } from 'react'
import { ArrowRightLeftIcon, ChevronRightIcon, MailIcon, PencilIcon, PlusIcon, UndoIcon } from 'lucide-react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { FullPageMessage } from '@/components/FullPageMessage'
import { SearchInput } from '@/components/SearchInput'
import { Button } from '@/components/ui/button'
import { useCan } from '@/features/auth/api'
import { recordPaths } from '@/features/staff-records/api'
import { staffPaths, useEmployeeActions, useStaffEmployees, useStaffStructure } from '@/features/staff/api'
import { EmployeeDialog } from '@/features/staff/EmployeeDialog'
import { Chip, Initials } from '@/features/staff/list-parts'
import { people } from '@/features/staff/people'
import { StoreSwitch } from '@/features/staff/StoreSwitch'
import { weekSummary } from '@/features/staff/shifts'
import { TransferDialog } from '@/features/staff/TransferDialog'
import { ApiError } from '@/lib/api'
import { matchesSearch } from '@/lib/search'
import type { StaffEmployee, StoreSummary } from '@/lib/types'
import { useSearchParamText } from '@/lib/use-search-param-text'
import { cn } from '@/lib/utils'

const ALL = 'wszystkie'
/** Filtr „czeka na aktywację”, w adresie jako `?status=aktywacja`. */
const PENDING = 'aktywacja'

/**
 * Kolumny tabeli: pracownik (najszerszy), stanowisko, stałe godziny i stała kolumna przycisków na trzy ikony, więc
 * wszystkie wiersze i nagłówek mają te same krawędzie bez względu na to, ile przycisków ma dana osoba.
 */
const COLUMNS = 'md:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_minmax(0,1fr)_6.5rem]'
const COLUMNS_READ = 'md:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_minmax(0,1fr)]'

export function EmployeesList({ storeId, stores }: { storeId: string; stores: StoreSummary[] }) {
  const canEdit = useCan('STAFF', 'EDIT')
  const canSeeRecords = useCan('STAFF_RECORDS')
  const employees = useStaffEmployees(storeId)
  const structure = useStaffStructure(storeId)
  const { resendInvitation, restore } = useEmployeeActions(storeId)
  const { text, setText, debounced } = useSearchParamText('szukaj')
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const [editor, setEditor] = useState<{ open: boolean; employee?: StaffEmployee }>({ open: false })
  const [moving, setMoving] = useState<StaffEmployee>()
  const manyStores = stores.length > 1

  const onError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać zmiany')

  if (employees.isError || structure.isError) {
    return (
      <FullPageMessage
        title="Nie udało się wczytać pracowników"
        action={{ label: 'Spróbuj ponownie', onClick: () => void employees.refetch() }}
        inline
      />
    )
  }
  if (!employees.data || !structure.data) {
    return <div className="h-72 animate-pulse rounded-2xl bg-muted" aria-busy />
  }

  const departments = structure.data
  const hasPositions = departments.some((department) => department.positions.length > 0)
  const department = params.get('dzial') ?? ALL
  const onlyPending = params.get('status') === PENDING
  // Każdy sklep ma swoich ludzi, więc lista startuje od bieżącego sklepu; całą firmę pokazuje wybór „Wszystkie sklepy”.
  const store = params.get('sklep') ?? storeId
  const allStores = store === ALL

  // Liczby przy działach liczą się z wyszukiwania i sklepu, ale nie z wybranego działu: widać, gdzie są trafienia.
  const inScope = employees.data.filter(
    (employee) =>
      (allStores || employee.storeId === store) &&
      matchesSearch(
        [employee.displayName, employee.username, employee.email, employee.positionName, employee.departmentName],
        debounced,
      ),
  )
  const scopeWorking = inScope.filter((employee) => employee.endedAt === null)
  const pendingCount = scopeWorking.filter((employee) => employee.pendingActivation && employee.enabled).length
  const visible = inScope.filter(
    (employee) =>
      (department === ALL || employee.departmentId === department) &&
      (!onlyPending || (employee.pendingActivation && employee.enabled)),
  )
  const working = visible.filter((employee) => employee.endedAt === null)
  const ended = visible.filter((employee) => employee.endedAt !== null)
  const known = new Set(departments.map((group) => group.id))
  // Osoba z działem spoza struktury (np. usuniętym) nie może zniknąć z listy: liczba nad tabelą musi się zgadzać.
  const orphans = working.filter((employee) => !known.has(employee.departmentId))
  const groups = [
    ...departments.map((group) => ({
      id: group.id,
      name: group.name,
      employees: working.filter((employee) => employee.departmentId === group.id),
    })),
    { id: 'bez-dzialu', name: 'Bez działu', employees: orphans },
  ].filter((group) => group.employees.length > 0)
  const departmentCounts = new Map(
    departments.map((group) => [group.id, scopeWorking.filter((e) => e.departmentId === group.id).length]),
  )

  const setFilter = (param: string, value: string) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        const fallback = param === 'sklep' ? storeId : ALL
        if (value === fallback || value === '') next.delete(param)
        else next.set(param, value)
        return next
      },
      { replace: true },
    )

  const resend = (employee: StaffEmployee) =>
    resendInvitation.mutate(employee.id, {
      onSuccess: () =>
        toast.success('Wysłano nowy link aktywacyjny', {
          description: `Na adres ${employee.email}. Poprzedni link przestał działać.`,
        }),
      onError,
    })

  if (!hasPositions && employees.data.length === 0) {
    return (
      <FullPageMessage
        title="Najpierw załóż dział i stanowisko"
        description="Pracownik dostaje stanowisko, a z nim uprawnienia w aplikacji."
        action={
          canEdit ? { label: 'Przejdź do działów', onClick: () => void navigate(staffPaths.structure) } : undefined
        }
        inline
      />
    )
  }

  const filtered = department !== ALL || onlyPending || debounced.trim() !== ''

  return (
    <>
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <SearchInput
          value={text}
          onChange={setText}
          placeholder="Szukaj: imię, login, stanowisko"
          className="w-full min-w-0 sm:w-80"
        />
        {manyStores && (
          <StoreSwitch
            label="Sklep"
            stores={stores}
            value={store}
            onChange={(value) => setFilter('sklep', value)}
            all={ALL}
          />
        )}
        {canEdit && (
          <Button
            onClick={() => setEditor({ open: true })}
            className="h-10 w-full rounded-xl px-4 sm:ml-auto sm:w-auto"
          >
            <PlusIcon />
            Dodaj pracownika
          </Button>
        )}
      </div>

      {departments.length > 1 && (
        <nav aria-label="Działy" className="-mx-1 mb-5 flex gap-1.5 overflow-x-auto px-1 pb-1">
          <Chip active={department === ALL} onClick={() => setFilter('dzial', ALL)} count={scopeWorking.length}>
            Wszystkie działy
          </Chip>
          {departments.map((group) => (
            <Chip
              key={group.id}
              active={department === group.id}
              onClick={() => setFilter('dzial', group.id)}
              count={departmentCounts.get(group.id) ?? 0}
            >
              {group.name}
            </Chip>
          ))}
        </nav>
      )}

      <div className="mb-2 flex flex-wrap items-baseline gap-x-3 gap-y-1 text-sm">
        <span className="font-medium tabular-nums">{people(working.length)}</span>
        {pendingCount > 0 && (
          <button
            type="button"
            aria-pressed={onlyPending}
            onClick={() => setFilter('status', onlyPending ? ALL : PENDING)}
            className={cn(
              'inline-flex items-center gap-1.5 rounded-md px-1.5 py-0.5 text-muted-foreground outline-none transition-colors duration-150 hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring',
              onlyPending && 'bg-amber-200 text-amber-950 hover:text-amber-950 dark:bg-amber-900 dark:text-amber-50',
            )}
          >
            <span aria-hidden className="size-2 rounded-full bg-amber-500" />
            <span className="tabular-nums">{pendingCount}</span> czeka na aktywację
          </button>
        )}
      </div>

      {groups.length === 0 && ended.length === 0 ? (
        <div className="rounded-2xl border bg-card px-6 py-14 text-center">
          <p className="font-medium">
            {employees.data.length === 0 ? 'Nie ma jeszcze pracowników' : 'Nikt nie pasuje do filtrów'}
          </p>
          <p className="mt-1 text-sm text-muted-foreground">
            {employees.data.length === 0
              ? 'Dodaj pierwszą osobę: dostanie link do ustawienia hasła.'
              : 'Zmień wyszukiwanie albo pokaż wszystkie działy.'}
          </p>
          {filtered && employees.data.length > 0 && (
            <Button
              variant="outline"
              className="mt-4"
              onClick={() => {
                setText('')
                setParams(
                  (current) => {
                    const next = new URLSearchParams(current)
                    next.delete('dzial')
                    next.delete('status')
                    next.delete('szukaj')
                    return next
                  },
                  { replace: true },
                )
              }}
            >
              Wyczyść filtry
            </Button>
          )}
        </div>
      ) : (
        <div className="grid gap-4">
          {groups.length > 0 && (
            <div className="overflow-hidden rounded-2xl border bg-card">
              <div
                aria-hidden
                className={cn(
                  'hidden gap-x-4 border-b px-4 py-2.5 text-xs text-muted-foreground md:grid',
                  canEdit ? COLUMNS : COLUMNS_READ,
                )}
              >
                <span className="pl-12">Pracownik</span>
                <span>Stanowisko</span>
                <span>Stałe godziny</span>
              </div>
              {groups.map((group) => (
                <section key={group.id} aria-label={group.name}>
                  <h2 className="flex items-baseline gap-2 bg-muted/60 px-4 py-2 text-sm font-medium">
                    {group.name}
                    <span className="text-xs font-normal text-muted-foreground tabular-nums">
                      {people(group.employees.length)}
                    </span>
                  </h2>
                  <ul className="divide-y">
                    {group.employees.map((employee) => (
                      <EmployeeRow
                        key={employee.id}
                        employee={employee}
                        canEdit={canEdit}
                        showStore={manyStores && allStores}
                        canSeeRecord={canSeeRecords}
                        onEdit={() => setEditor({ open: true, employee })}
                        onResend={() => resend(employee)}
                        onMove={manyStores ? () => setMoving(employee) : undefined}
                      />
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
                <span className="text-xs font-normal text-muted-foreground tabular-nums">{people(ended.length)}</span>
              </summary>
              <ul className="divide-y border-t">
                {ended.map((employee) => (
                  <li key={employee.id} className="flex items-center gap-3 px-4 py-3">
                    <Initials name={employee.displayName} muted />
                    <div className="min-w-0 flex-1">
                      <div className="truncate text-sm font-medium text-muted-foreground">{employee.displayName}</div>
                      <div className="truncate text-xs text-muted-foreground">
                        {manyStores && allStores && `${employee.storeNumber} · `}
                        {employee.departmentName} · {employee.positionName}
                      </div>
                    </div>
                    {canEdit && (
                      <Button
                        variant="outline"
                        size="sm"
                        loading={restore.isPending && restore.variables === employee.id}
                        onClick={() =>
                          restore.mutate(employee.id, {
                            onSuccess: () => toast.success(`${employee.displayName} wraca do pracy`),
                            onError,
                          })
                        }
                      >
                        <UndoIcon />
                        Przywróć
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
            </details>
          )}
        </div>
      )}

      <EmployeeDialog
        storeId={storeId}
        open={editor.open}
        employee={editor.employee}
        stores={stores}
        onOpenChange={(open) => setEditor((current) => ({ ...current, open }))}
      />
      <TransferDialog storeId={storeId} stores={stores} employee={moving} onClose={() => setMoving(undefined)} />
    </>
  )
}

function EmployeeRow({
  employee,
  canEdit,
  showStore,
  canSeeRecord,
  onEdit,
  onResend,
  onMove,
}: {
  employee: StaffEmployee
  canEdit: boolean
  showStore: boolean
  canSeeRecord: boolean
  onEdit: () => void
  onResend: () => void
  /** Brak = firma nie ma innego sklepu, do którego można przenieść. */
  onMove?: () => void
}) {
  const pending = employee.pendingActivation && employee.enabled
  return (
    <li
      className={cn(
        'grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-1.5 px-4 py-3',
        canEdit ? COLUMNS : COLUMNS_READ,
      )}
    >
      <div className="flex min-w-0 items-center gap-3">
        <Initials name={employee.displayName} />
        <div className="min-w-0">
          {canSeeRecord ? (
            <Link
              to={recordPaths.record(employee.id)}
              className="block truncate font-medium underline-offset-4 outline-none hover:underline focus-visible:underline"
            >
              {employee.displayName}
            </Link>
          ) : (
            <span className="block truncate font-medium">{employee.displayName}</span>
          )}
          <div className="flex min-w-0 items-center gap-1.5 text-xs text-muted-foreground">
            <span className="truncate">{employee.username}</span>
            <Status employee={employee} />
          </div>
        </div>
      </div>
      <div className="col-start-1 row-start-2 min-w-0 pl-12 text-sm md:col-start-2 md:row-start-1 md:pl-0">
        <div className="truncate">{employee.positionName}</div>
        <div className="truncate text-xs text-muted-foreground">
          {showStore && <span title={employee.storeName}>sklep {employee.storeNumber} · </span>}
          rola {employee.roleName}
        </div>
      </div>
      <div
        className={cn(
          'col-start-1 row-start-3 min-w-0 pl-12 text-sm tabular-nums md:col-start-3 md:row-start-1 md:pl-0',
          // Na telefonie pusta kreska dokładałaby wiersz bez treści.
          employee.shifts.length === 0 && 'hidden md:block',
        )}
      >
        {employee.shifts.length === 0 ? (
          <span className="text-muted-foreground">—</span>
        ) : (
          weekSummary(employee.shifts).map((line) => (
            <div key={line.days} className="truncate">
              <span className="text-xs text-muted-foreground">{line.days}</span> {line.hours}
            </div>
          ))
        )}
      </div>
      {canEdit && (
        <div className="col-start-2 row-span-3 row-start-1 flex justify-end gap-0.5 self-start md:col-start-4 md:row-span-1 md:self-center">
          {pending && (
            <RowAction
              label={`Wyślij ponownie link aktywacyjny do ${employee.displayName}`}
              title="Wyślij link ponownie"
              onClick={onResend}
            >
              <MailIcon />
            </RowAction>
          )}
          {onMove && (
            <RowAction
              label={`Przenieś do innego sklepu: ${employee.displayName}`}
              title="Przenieś do innego sklepu"
              onClick={onMove}
            >
              <ArrowRightLeftIcon />
            </RowAction>
          )}
          <RowAction label={`Edytuj pracownika ${employee.displayName}`} title="Edytuj" onClick={onEdit}>
            <PencilIcon />
          </RowAction>
        </div>
      )}
    </li>
  )
}

/** Przyciski w wierszu są ciche (szare), żeby przy stu osobach nie były najgłośniejsze na ekranie. */
function RowAction({
  label,
  title,
  onClick,
  children,
}: {
  label: string
  title: string
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <Button
      variant="ghost"
      size="icon"
      aria-label={label}
      title={title}
      onClick={onClick}
      className="text-muted-foreground hover:text-foreground"
    >
      {children}
    </Button>
  )
}

/** Jeden znak stanu przy loginie: kropka i krótki tekst zamiast plakietki, bo nowe konta bywają w większości. */
function Status({ employee }: { employee: StaffEmployee }) {
  if (!employee.enabled) {
    return (
      <span className="flex shrink-0 items-center gap-1 text-red-700 dark:text-red-300">
        <span aria-hidden className="size-1.5 rounded-full bg-red-600 dark:bg-red-400" />
        zablokowany
      </span>
    )
  }
  if (employee.pendingActivation) {
    return (
      <span className="flex shrink-0 items-center gap-1 text-amber-800 dark:text-amber-300">
        <span aria-hidden className="size-1.5 rounded-full bg-amber-500" />
        {/* Na telefonie krótko, żeby nie ucinać loginu; pełny opis w podpowiedzi i dla czytnika ekranu. */}
        <span className="sm:hidden" title="Czeka na aktywację konta">
          czeka<span className="sr-only"> na aktywację</span>
        </span>
        <span className="hidden sm:inline">czeka na aktywację</span>
      </span>
    )
  }
  return null
}
