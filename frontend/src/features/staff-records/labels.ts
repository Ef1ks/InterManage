import type {
  ContractKind,
  DisabilityDegree,
  EducationLevel,
  EmploymentType,
  LeaveKind,
  SeniorityLength,
  StaffRecordRow,
  WorkPeriodKind,
} from '@/lib/types'

export const CONTRACT_KINDS: { value: ContractKind; label: string }[] = [
  { value: 'EMPLOYMENT', label: 'Umowa o pracę' },
  { value: 'MANDATE', label: 'Umowa zlecenie' },
  { value: 'B2B', label: 'B2B' },
]

export const EMPLOYMENT_TYPES: { value: EmploymentType; label: string }[] = [
  { value: 'TRIAL', label: 'Na okres próbny' },
  { value: 'FIXED', label: 'Na czas określony' },
  { value: 'INDEFINITE', label: 'Na czas nieokreślony' },
]

export const DEGREES: { value: DisabilityDegree; label: string }[] = [
  { value: 'LIGHT', label: 'Lekki' },
  { value: 'MODERATE', label: 'Umiarkowany' },
  { value: 'SEVERE', label: 'Znaczny' },
]

export const WORK_PERIOD_KINDS: { value: WorkPeriodKind; label: string }[] = [
  { value: 'EMPLOYMENT', label: 'Umowa o pracę' },
  { value: 'MANDATE', label: 'Zlecenie lub umowa agencyjna' },
  { value: 'BUSINESS', label: 'Działalność gospodarcza' },
  { value: 'ABROAD', label: 'Praca za granicą' },
]

/** Wcześniejsze zatrudnienie wyklucza pierwszą pracę w życiu (art. 153 § 1 KP); zlecenie i działalność nie. */
export const EARLIER_JOB_KINDS: WorkPeriodKind[] = ['EMPLOYMENT', 'ABROAD']

/** Lata szkoły liczone do stażu urlopowego (art. 155 § 1 KP). */
export const EDUCATION_LEVELS: { value: EducationLevel; label: string }[] = [
  { value: 'NONE', label: 'Podstawowe lub bez wliczanej szkoły' },
  { value: 'VOCATIONAL', label: 'Zasadnicza zawodowa (do 3 lat)' },
  { value: 'GENERAL_SECONDARY', label: 'Średnia ogólnokształcąca (4 lata)' },
  { value: 'SECONDARY_VOCATIONAL', label: 'Średnia zawodowa (do 5 lat)' },
  { value: 'POST_SECONDARY', label: 'Policealna (6 lat)' },
  { value: 'HIGHER', label: 'Wyższa (8 lat)' },
]

export const LEAVE_KINDS: { value: LeaveKind; label: string; limit?: number }[] = [
  { value: 'ANNUAL', label: 'Urlop wypoczynkowy' },
  { value: 'ON_DEMAND', label: 'Urlop na żądanie', limit: 4 },
  { value: 'CHILD_CARE', label: 'Opieka nad dzieckiem do 14 lat', limit: 2 },
  { value: 'FORCE_MAJEURE', label: 'Zwolnienie z powodu siły wyższej', limit: 2 },
  { value: 'CARE', label: 'Urlop opiekuńczy', limit: 5 },
  { value: 'REHABILITATION', label: 'Turnus rehabilitacyjny', limit: 21 },
  { value: 'OCCASIONAL', label: 'Urlop okolicznościowy' },
  { value: 'UNPAID', label: 'Urlop bezpłatny' },
]

export const labelOf = <T extends string>(options: { value: T; label: string }[], value: T | null | undefined) =>
  options.find((option) => option.value === value)?.label ?? ''

/** RRRR-MM-DD → DD.MM.RRRR */
export function formatDate(value: string | null | undefined) {
  if (!value) return ''
  const [year, month, day] = value.split('-')
  return `${day}.${month}.${year}`
}

export const dateRange = (from: string, to: string | null) =>
  `${formatDate(from)} – ${to ? formatDate(to) : 'bezterminowo'}`

function plural(count: number, one: string, few: string, many: string) {
  if (count === 1) return one
  const lastDigit = count % 10
  const lastTwo = count % 100
  return lastDigit >= 2 && lastDigit <= 4 && (lastTwo < 12 || lastTwo > 14) ? few : many
}

export const daysWord = (count: number) => plural(Math.abs(count), 'dzień', 'dni', 'dni')

/** 12 lat 3 mies. 5 dni */
export function formatLength({ years, months, days }: SeniorityLength) {
  const parts = [
    years > 0 && `${years} ${plural(years, 'rok', 'lata', 'lat')}`,
    months > 0 && `${months} mies.`,
    days > 0 && `${days} ${daysWord(days)}`,
  ].filter(Boolean)
  return parts.length > 0 ? parts.join(' ') : 'brak'
}

export const fteLabel = (numerator: number, denominator: number) =>
  numerator === denominator ? 'pełny etat' : `${numerator}/${denominator} etatu`

export function contractSummary(row: StaffRecordRow) {
  if (!row.contractKind) return 'Brak umowy'
  if (row.contractKind !== 'EMPLOYMENT') return labelOf(CONTRACT_KINDS, row.contractKind)
  const type = labelOf(EMPLOYMENT_TYPES, row.employmentType).toLowerCase()
  return `Umowa o pracę ${type}, ${fteLabel(row.fteNumerator ?? 1, row.fteDenominator ?? 1)}`
}
