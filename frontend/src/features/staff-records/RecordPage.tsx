import { useState, type ReactNode } from 'react'
import {
  ChevronLeftIcon,
  ChevronRightIcon,
  CircleAlertIcon,
  InfoIcon,
  PencilIcon,
  PlusIcon,
  Trash2Icon,
  TriangleAlertIcon,
} from 'lucide-react'
import { useParams } from 'react-router'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useCan } from '@/features/auth/api'
import { recordPaths, useRecordActions, useStaffRecord, type RecordEntryPath } from '@/features/staff-records/api'
import {
  CONTRACT_KINDS,
  dateRange,
  EARLIER_JOB_KINDS,
  daysWord,
  DEGREES,
  EDUCATION_LEVELS,
  EMPLOYMENT_TYPES,
  formatDate,
  formatLength,
  fteLabel,
  LEAVE_KINDS,
  labelOf,
  WORK_PERIOD_KINDS,
} from '@/features/staff-records/labels'
import { RecordDialog, type RecordEditor } from '@/features/staff-records/RecordDialogs'
import { ApiError } from '@/lib/api'
import { todayIso } from '@/lib/dates'
import type { StaffRecord, StaffWarning } from '@/lib/types'
import { cn } from '@/lib/utils'

/** Profil pracownika w Kadrach: sprawy do sprawdzenia, umowy, staż, urlop, orzeczenie i terminy. */
export function RecordPage({ storeId }: { storeId: string }) {
  const { id = '' } = useParams()
  const [year, setYear] = useState(() => new Date().getFullYear())
  const { data: record, isError, refetch } = useStaffRecord(storeId, id, year)
  const [editor, setEditor] = useState<RecordEditor | null>(null)
  const canEdit = useCan('STAFF_RECORDS', 'EDIT')

  if (isError) {
    return (
      <>
        <BackLink to={recordPaths.list}>Kadry</BackLink>
        <FullPageMessage
          title="Nie udało się wczytać profilu"
          action={{ label: 'Spróbuj ponownie', onClick: () => void refetch() }}
          inline
        />
      </>
    )
  }
  if (!record) {
    return <div className="h-40 animate-pulse rounded-xl bg-muted" aria-busy />
  }

  const { person } = record
  return (
    <>
      <BackLink to={recordPaths.list}>Kadry</BackLink>
      <PageHeader
        title={person.displayName}
        description={`${person.departmentName} · ${person.positionName} · sklep ${person.storeNumber} · rola ${person.roleName}`}
      />
      {person.endedAt && (
        <p className="mb-4 rounded-xl bg-muted px-4 py-3 text-sm text-muted-foreground">
          Ta osoba zakończyła pracę. Dane zostają w kartotece.
        </p>
      )}
      {record.warnings.length > 0 && <Warnings warnings={record.warnings} />}

      <div className="grid items-start gap-4 lg:grid-cols-2">
        <div className="grid gap-4">
          <Contracts record={record} canEdit={canEdit} storeId={storeId} onEdit={setEditor} />
          <SeniorityCard record={record} canEdit={canEdit} storeId={storeId} onEdit={setEditor} />
          <Deadlines record={record} canEdit={canEdit} storeId={storeId} onEdit={setEditor} />
        </div>
        <div className="grid gap-4">
          <Leave record={record} year={year} onYear={setYear} canEdit={canEdit} storeId={storeId} onEdit={setEditor} />
          <Disability record={record} canEdit={canEdit} storeId={storeId} onEdit={setEditor} />
        </div>
      </div>

      <p className="mt-6 max-w-3xl text-xs text-muted-foreground">
        Wyliczenia i ostrzeżenia wynikają z wpisanych danych i przepisów, ale nie znają wszystkich okoliczności. Program
        pomaga, a decyzję podejmują kadry.
      </p>

      <RecordDialog
        storeId={storeId}
        employeeId={id}
        editor={editor}
        profile={record.profile}
        earlierJob={record.workPeriods.some((period) => EARLIER_JOB_KINDS.includes(period.kind))}
        year={year}
        onClose={() => setEditor(null)}
      />
    </>
  )
}

const WARNING_STYLES = {
  ERROR: {
    icon: CircleAlertIcon,
    className: 'bg-red-200 text-red-950 dark:bg-red-500/30 dark:text-red-100',
  },
  WARNING: {
    icon: TriangleAlertIcon,
    className: 'bg-amber-200 text-amber-950 dark:bg-amber-500/30 dark:text-amber-100',
  },
  INFO: { icon: InfoIcon, className: 'bg-muted text-foreground' },
}

function Warnings({ warnings }: { warnings: StaffWarning[] }) {
  return (
    <ul aria-label="Do sprawdzenia" className="mb-4 grid gap-1.5">
      {warnings.map((warning) => {
        const { icon: Icon, className } = WARNING_STYLES[warning.severity]
        return (
          <li
            key={warning.message}
            className={cn('flex items-start gap-2.5 rounded-xl px-3 py-2.5 text-sm', className)}
          >
            <Icon aria-hidden className="mt-0.5 size-4 shrink-0" />
            <span>
              {warning.message}
              {warning.basis && <span className="opacity-70"> ({warning.basis})</span>}
            </span>
          </li>
        )
      })}
    </ul>
  )
}

function Section({ title, actions, children }: { title: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <Card className="gap-3 p-4">
      <div className="flex min-h-8 items-center justify-between gap-2">
        <h2 className="font-medium">{title}</h2>
        {actions && <div className="flex shrink-0 items-center gap-1">{actions}</div>}
      </div>
      {children}
    </Card>
  )
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-4 text-sm">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="text-right tabular-nums">{children}</dd>
    </div>
  )
}

function Empty({ children }: { children: ReactNode }) {
  return <p className="text-sm text-muted-foreground">{children}</p>
}

/** Wiersz wpisu z poprawianiem i usuwaniem; usunięcie to znacznik z „Przywróć”. */
function EntryRow(props: Parameters<typeof RemovableRow>[0] & { onEdit: () => void }) {
  return <RemovableRow {...props} />
}

function RemovableRow({
  storeId,
  employeeId,
  kind,
  entryId,
  label,
  canEdit,
  onEdit,
  children,
}: {
  storeId: string
  employeeId: string
  kind: RecordEntryPath
  entryId: string
  label: string
  canEdit: boolean
  /** Brak = wpis można tylko usunąć (aneks poprawia się usunięciem i nowym wpisem). */
  onEdit?: () => void
  children: ReactNode
}) {
  const { removeEntry, restoreEntry } = useRecordActions(storeId, employeeId)
  const onError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać zmiany')

  const remove = () =>
    removeEntry.mutate(
      { kind, entryId },
      {
        onSuccess: () =>
          toast.success(`Usunięto: ${label}`, {
            action: { label: 'Przywróć', onClick: () => restoreEntry.mutate({ kind, entryId }, { onError }) },
          }),
        onError,
      },
    )

  return (
    <li className="flex items-start justify-between gap-2 border-t pt-2.5 text-sm first:border-t-0 first:pt-0">
      <div className="min-w-0">{children}</div>
      {canEdit && (
        <div className="flex shrink-0 gap-0.5">
          {onEdit && (
            <Button variant="ghost" size="icon-sm" aria-label={`Popraw: ${label}`} title="Popraw" onClick={onEdit}>
              <PencilIcon />
            </Button>
          )}
          <Button variant="ghost" size="icon-sm" aria-label={`Usuń: ${label}`} title="Usuń" onClick={remove}>
            <Trash2Icon />
          </Button>
        </div>
      )}
    </li>
  )
}

type CardProps = {
  record: StaffRecord
  canEdit: boolean
  storeId: string
  onEdit: (editor: RecordEditor) => void
}

function AddButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <Button variant="ghost" onClick={onClick}>
      <PlusIcon />
      {label}
    </Button>
  )
}

function Contracts({ record, canEdit, storeId, onEdit }: CardProps) {
  return (
    <Section
      title="Umowy"
      actions={canEdit && <AddButton label="Umowa" onClick={() => onEdit({ type: 'contract' })} />}
    >
      {record.contracts.length === 0 ? (
        <Empty>Brak umów. Bez umowy program nie policzy urlopu ani stażu.</Empty>
      ) : (
        <ul className="grid gap-2.5">
          {record.contracts.map((contract) => {
            const label = labelOf(CONTRACT_KINDS, contract.kind)
            return (
              <EntryRow
                key={contract.id}
                storeId={storeId}
                employeeId={record.person.employeeId}
                kind="contracts"
                entryId={contract.id}
                label={label}
                canEdit={canEdit}
                onEdit={() => onEdit({ type: 'contract', entry: contract })}
              >
                <div className="font-medium">
                  {label}
                  {contract.employmentType && (
                    <span className="font-normal">
                      {' '}
                      {labelOf(EMPLOYMENT_TYPES, contract.employmentType).toLowerCase()}
                    </span>
                  )}
                </div>
                <div className="text-xs text-muted-foreground tabular-nums">
                  {dateRange(contract.startDate, contract.endDate)}
                  {contract.kind === 'EMPLOYMENT' && ` · ${fteLabel(contract.fteNumerator, contract.fteDenominator)}`}
                  {contract.fixedTermExempt && ' · poza limitem 33 miesięcy'}
                </div>
                {canEdit && contract.kind === 'EMPLOYMENT' && (
                  <Button
                    variant="ghost"
                    size="sm"
                    className="-ml-2.5 mt-0.5 text-muted-foreground"
                    onClick={() => onEdit({ type: 'contract-change', contract })}
                  >
                    <PlusIcon />
                    Aneks
                  </Button>
                )}
              </EntryRow>
            )
          })}
        </ul>
      )}
      <ContractChanges record={record} canEdit={canEdit} storeId={storeId} />
    </Section>
  )
}

/** Aneksy wszystkich umów, od najnowszego. */
function ContractChanges({ record, canEdit, storeId }: Omit<CardProps, 'onEdit'>) {
  const changes = record.contracts
    .flatMap((contract) => contract.changes)
    .sort((a, b) => b.validFrom.localeCompare(a.validFrom))
  if (changes.length === 0) return null
  return (
    <ul aria-label="Aneksy" className="grid gap-2.5 border-t pt-2.5">
      {changes.map((change) => {
        const label = `aneks od ${formatDate(change.validFrom)}`
        return (
          <RemovableRow
            key={change.id}
            storeId={storeId}
            employeeId={record.person.employeeId}
            kind="contract-changes"
            entryId={change.id}
            label={label}
            canEdit={canEdit}
          >
            <div className="font-medium">{fteLabel(change.fteNumerator, change.fteDenominator)}</div>
            <div className="text-xs text-muted-foreground tabular-nums">Aneks od {formatDate(change.validFrom)}</div>
          </RemovableRow>
        )
      })}
    </ul>
  )
}

function SeniorityCard({ record, canEdit, storeId, onEdit }: CardProps) {
  const { seniority, leaveYear } = record
  const today = todayIso()
  return (
    <Section
      title="Staż pracy"
      actions={canEdit && <AddButton label="Okres" onClick={() => onEdit({ type: 'period' })} />}
    >
      <dl className="grid gap-1.5">
        <Fact label="W tej firmie">{formatLength(seniority.inCompany)}</Fact>
        <Fact label="Łącznie">{formatLength(seniority.work)}</Fact>
        <Fact label="Do urlopu, ze szkołą">{formatLength(seniority.forLeave)}</Fact>
        {seniority.higherLeaveFrom && seniority.higherLeaveFrom > today && (
          <Fact label="26 dni urlopu od roku">{seniority.higherLeaveFrom.slice(0, 4)}</Fact>
        )}
        {leaveYear.fullDays === 26 && <Fact label="Wymiar">26 dni (10 lat stażu)</Fact>}
      </dl>
      {record.workPeriods.length > 0 && (
        <ul className="mt-1 grid gap-2.5 border-t pt-2.5">
          {record.workPeriods.map((period) => {
            const label = labelOf(WORK_PERIOD_KINDS, period.kind)
            return (
              <EntryRow
                key={period.id}
                storeId={storeId}
                employeeId={record.person.employeeId}
                kind="work-periods"
                entryId={period.id}
                label={label}
                canEdit={canEdit}
                onEdit={() => onEdit({ type: 'period', entry: period })}
              >
                <div className="font-medium">{period.employer ?? label}</div>
                <div className="text-xs text-muted-foreground tabular-nums">
                  {dateRange(period.startDate, period.endDate)}
                  {period.employer && ` · ${label}`}
                  {period.kind !== 'EMPLOYMENT' && !period.documented && ' · czeka na zaświadczenie'}
                </div>
              </EntryRow>
            )
          })}
        </ul>
      )}
    </Section>
  )
}

function Deadlines({ record, canEdit, onEdit }: CardProps) {
  const { profile } = record
  return (
    <Section
      title="Dane i terminy"
      actions={
        canEdit && (
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label="Popraw dane i terminy"
            title="Popraw"
            onClick={() => onEdit({ type: 'profile' })}
          >
            <PencilIcon />
          </Button>
        )
      }
    >
      <dl className="grid gap-1.5">
        <Fact label="Nr karty RCP">
          <span className="font-mono tabular-nums" title="Numer do wpisania w Symfonii (Aktualne zatrudnienie)">
            {record.person.rcpNumber ?? '–'}
          </span>
        </Fact>
        <Fact label="Data urodzenia">{formatDate(profile?.birthDate) || '–'}</Fact>
        <Fact label="Szkoła">{labelOf(EDUCATION_LEVELS, profile?.educationLevel) || '–'}</Fact>
        <Fact label="Badania lekarskie do">{formatDate(profile?.medicalExamUntil) || '–'}</Fact>
        <Fact label="Szkolenie BHP do">{formatDate(profile?.bhpTrainingUntil) || '–'}</Fact>
        <Fact label="Badania sanitarne do">{formatDate(profile?.sanitaryUntil) || '–'}</Fact>
      </dl>
    </Section>
  )
}

function Leave({
  record,
  year,
  onYear,
  canEdit,
  storeId,
  onEdit,
}: CardProps & { year: number; onYear: (year: number) => void }) {
  const leave = record.leaveYear
  const noEmployment = !record.contracts.some((contract) => contract.kind === 'EMPLOYMENT')
  return (
    <Section
      title="Urlop"
      actions={
        <>
          <Button variant="ghost" size="icon-sm" aria-label="Poprzedni rok" onClick={() => onYear(year - 1)}>
            <ChevronLeftIcon />
          </Button>
          <span className="w-10 text-center text-sm tabular-nums">{year}</span>
          <Button variant="ghost" size="icon-sm" aria-label="Następny rok" onClick={() => onYear(year + 1)}>
            <ChevronRightIcon />
          </Button>
        </>
      }
    >
      {noEmployment ? (
        <Empty>Urlop z Kodeksu pracy przysługuje przy umowie o pracę. Przy zleceniu i B2B zasady ustala umowa.</Empty>
      ) : (
        <>
          <div className="flex items-baseline gap-2">
            <span className={cn('text-3xl font-semibold tabular-nums', leave.remainingDays < 0 && 'text-destructive')}>
              {leave.remainingDays}
            </span>
            <span className="text-sm text-muted-foreground">{daysWord(leave.remainingDays)} do wykorzystania</span>
          </div>
          <dl className="grid gap-1.5">
            <Fact label={`Wymiar za ${year} (z ${leave.fullDays})`}>{leave.annualDays}</Fact>
            {leave.additionalDays > 0 && <Fact label="Urlop dodatkowy">{leave.additionalDays}</Fact>}
            <Fact label="Z poprzednich lat">
              {leave.carriedDays}
              {canEdit && (
                <Button
                  variant="ghost"
                  size="icon-xs"
                  className="ml-1 align-middle"
                  aria-label="Popraw urlop z poprzednich lat"
                  title="Popraw"
                  onClick={() => onEdit({ type: 'opening' })}
                >
                  <PencilIcon />
                </Button>
              )}
            </Fact>
            <Fact label="Wykorzystano">{leave.usedDays}</Fact>
          </dl>
          <UsageLimits record={record} />
          {canEdit && (
            <div>
              <AddButton label="Urlop" onClick={() => onEdit({ type: 'leave' })} />
            </div>
          )}
          {record.leaves.length > 0 && (
            <ul className="grid gap-2.5 border-t pt-2.5">
              {record.leaves.map((entry) => {
                const label = labelOf(LEAVE_KINDS, entry.kind)
                return (
                  <EntryRow
                    key={entry.id}
                    storeId={storeId}
                    employeeId={record.person.employeeId}
                    kind="leaves"
                    entryId={entry.id}
                    label={label}
                    canEdit={canEdit}
                    onEdit={() => onEdit({ type: 'leave', entry })}
                  >
                    <div className="font-medium">{label}</div>
                    <div className="text-xs text-muted-foreground tabular-nums">
                      {dateRange(entry.startDate, entry.endDate)} · {entry.days} {daysWord(entry.days)}
                    </div>
                  </EntryRow>
                )
              })}
            </ul>
          )}
        </>
      )}
    </Section>
  )
}

/** Rodzaje z rocznym limitem, które już wykorzystano. */
function UsageLimits({ record }: { record: StaffRecord }) {
  const used = LEAVE_KINDS.filter((kind) => kind.limit && (record.leaveYear.usage[kind.value] ?? 0) > 0)
  if (used.length === 0) return null
  return (
    <dl className="grid gap-1.5 border-t pt-2.5">
      {used.map((kind) => (
        <Fact key={kind.value} label={kind.label}>
          {record.leaveYear.usage[kind.value]} z {kind.limit}
        </Fact>
      ))}
    </dl>
  )
}

function Disability({ record, canEdit, storeId, onEdit }: CardProps) {
  return (
    <Section
      title="Niepełnosprawność"
      actions={canEdit && <AddButton label="Orzeczenie" onClick={() => onEdit({ type: 'disability' })} />}
    >
      {record.disabilities.length === 0 ? (
        <Empty>Brak orzeczenia.</Empty>
      ) : (
        <ul className="grid gap-2.5">
          {record.disabilities.map((disability) => {
            const label = `Stopień ${labelOf(DEGREES, disability.degree).toLowerCase()}`
            return (
              <EntryRow
                key={disability.id}
                storeId={storeId}
                employeeId={record.person.employeeId}
                kind="disabilities"
                entryId={disability.id}
                label={label}
                canEdit={canEdit}
                onEdit={() => onEdit({ type: 'disability', entry: disability })}
              >
                <div className="font-medium">{label}</div>
                <div className="text-xs text-muted-foreground tabular-nums">
                  {disability.endDate
                    ? dateRange(disability.startDate, disability.endDate)
                    : `od ${formatDate(disability.startDate)}, na stałe`}
                  {disability.doctorConsent && ' · zgoda lekarza z art. 16'}
                </div>
              </EntryRow>
            )
          })}
        </ul>
      )}
      {record.rights.length > 0 && (
        <ul className="grid list-disc gap-1 border-t pt-2.5 pl-4 text-sm text-muted-foreground">
          {record.rights.map((right) => (
            <li key={right}>{right}</li>
          ))}
        </ul>
      )}
    </Section>
  )
}
