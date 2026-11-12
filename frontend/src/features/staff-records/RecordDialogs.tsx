import { useEffect, useRef, useState, type ReactNode } from 'react'
import { LockIcon } from 'lucide-react'
import { Controller, useForm, useWatch, type Control, type FieldPath, type FieldValues } from 'react-hook-form'

import { DateField, Form, FormActions, FormNote, SelectField, SwitchField, TextField } from '@/components/form'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useRecordActions, type RecordEntryPath } from '@/features/staff-records/api'
import {
  CONTRACT_KINDS,
  DEGREES,
  EDUCATION_LEVELS,
  EARLIER_JOB_KINDS,
  EMPLOYMENT_TYPES,
  LEAVE_KINDS,
  WORK_PERIOD_KINDS,
} from '@/features/staff-records/labels'
import { ApiError } from '@/lib/api'
import type {
  ContractKind,
  DisabilityDegree,
  EducationLevel,
  EmploymentType,
  LeaveKind,
  StaffContract,
  StaffDisability,
  StaffLeave,
  StaffProfile,
  StaffWorkPeriod,
  WorkPeriodKind,
} from '@/lib/types'

/** Które okno jest otwarte i jaki wpis poprawia; brak {@code entry} = nowy wpis. */
export type RecordEditor =
  | { type: 'contract'; entry?: StaffContract }
  | { type: 'contract-change'; contract: StaffContract }
  | { type: 'period'; entry?: StaffWorkPeriod }
  | { type: 'disability'; entry?: StaffDisability }
  | { type: 'leave'; entry?: StaffLeave; date?: string }
  | { type: 'profile' }
  | { type: 'opening' }

type Props = {
  storeId: string
  employeeId: string
  editor: RecordEditor | null
  profile: StaffProfile | null
  /** Wpisane wcześniejsze zatrudnienie: pierwszej pracy w życiu nie da się wtedy zaznaczyć. */
  earlierJob?: boolean
  year: number
  onClose: () => void
}

export function RecordDialog({ storeId, employeeId, editor, profile, earlierJob = false, year, onClose }: Props) {
  const shared = { storeId, employeeId, onDone: onClose }
  return (
    <Dialog open={editor !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="gap-5 p-6 sm:max-w-md">
        {editor?.type === 'contract' && <ContractForm {...shared} entry={editor.entry} />}
        {editor?.type === 'contract-change' && <ContractChangeForm {...shared} contract={editor.contract} />}
        {editor?.type === 'period' && (
          <PeriodForm {...shared} entry={editor.entry} firstJob={profile?.firstJob ?? false} />
        )}
        {editor?.type === 'disability' && <DisabilityForm {...shared} entry={editor.entry} />}
        {editor?.type === 'leave' && <LeaveForm {...shared} entry={editor.entry} date={editor.date} />}
        {editor?.type === 'profile' && <ProfileForm {...shared} profile={profile} earlierJob={earlierJob} />}
        {editor?.type === 'opening' && <OpeningForm {...shared} year={year} />}
      </DialogContent>
    </Dialog>
  )
}

type FormProps = { storeId: string; employeeId: string; onDone: () => void }

const failed = (error: unknown) => (error instanceof ApiError ? error.message : 'Nie udało się zapisać zmiany')

/** Zapis wpisu kartoteki z błędem nad formularzem. */
function useEntrySave({ storeId, employeeId, onDone }: FormProps, kind: RecordEntryPath, entryId?: string) {
  const { saveEntry } = useRecordActions(storeId, employeeId)
  const [error, setError] = useState<string | null>(null)
  const save = (json: unknown) => {
    setError(null)
    saveEntry.mutate({ kind, entryId, json }, { onSuccess: onDone, onError: (cause) => setError(failed(cause)) })
  }
  return { save, error, pending: saveEntry.isPending }
}

function Shell({
  title,
  description,
  error,
  pending,
  onSubmit,
  onCancel,
  children,
}: {
  title: string
  description?: string
  error: string | null
  pending: boolean
  onSubmit: () => void
  onCancel: () => void
  children: ReactNode
}) {
  return (
    <Form
      error={error}
      onSubmit={(event) => {
        event.preventDefault()
        onSubmit()
      }}
    >
      <DialogHeader>
        <DialogTitle className="text-lg">{title}</DialogTitle>
        {description && <DialogDescription>{description}</DialogDescription>}
      </DialogHeader>
      {children}
      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onCancel}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={pending}>
          Zapisz
        </Button>
      </FormActions>
    </Form>
  )
}

function DateInput<T extends FieldValues>({
  control,
  name,
  label,
  optional,
  required,
}: {
  control: Control<T>
  name: FieldPath<T>
  label: string
  /** Podpis skrótu czyszczącego datę, np. Bez końca. */
  optional?: string
  required?: string
}) {
  return (
    <Controller
      control={control}
      name={name}
      rules={{ required }}
      render={({ field, fieldState }) => (
        <DateField
          label={label}
          value={field.value ?? ''}
          onChange={field.onChange}
          error={fieldState.error?.message}
          shortcuts={optional ? [{ label: optional, value: '' }] : []}
        />
      )}
    />
  )
}

function Choice<T extends FieldValues>({
  control,
  name,
  label,
  options,
  required,
}: {
  control: Control<T>
  name: FieldPath<T>
  label: string
  options: { value: string; label: string }[]
  required?: string
}) {
  return (
    <Controller
      control={control}
      name={name}
      rules={{ required }}
      render={({ field, fieldState }) => (
        <SelectField
          label={label}
          options={options}
          value={field.value ?? ''}
          onChange={field.onChange}
          onBlur={field.onBlur}
          error={fieldState.error?.message}
        />
      )}
    />
  )
}

function Toggle<T extends FieldValues>({
  control,
  name,
  label,
  description,
  disabled,
}: {
  control: Control<T>
  name: FieldPath<T>
  label: string
  description: string
  disabled?: boolean
}) {
  return (
    <Controller
      control={control}
      name={name}
      render={({ field }) => (
        <SwitchField
          label={label}
          description={description}
          checked={field.value}
          onCheckedChange={field.onChange}
          disabled={disabled}
        />
      )}
    />
  )
}

const orNull = (value: string) => value || null

type ContractValues = {
  kind: ContractKind
  employmentType: EmploymentType | ''
  startDate: string
  endDate: string
  fteNumerator: string
  fteDenominator: string
  fixedTermExempt: boolean
  paidLeaveDays: string
}

function ContractForm({ entry, ...props }: FormProps & { entry?: StaffContract }) {
  const { save, error, pending } = useEntrySave(props, 'contracts', entry?.id)
  const {
    control,
    register,
    handleSubmit,
    setValue,
    formState: { errors },
  } = useForm<ContractValues>({
    defaultValues: {
      kind: entry?.kind ?? 'EMPLOYMENT',
      employmentType: entry?.employmentType ?? '',
      startDate: entry?.startDate ?? '',
      endDate: entry?.endDate ?? '',
      fteNumerator: String(entry?.fteNumerator ?? 1),
      fteDenominator: String(entry?.fteDenominator ?? 1),
      fixedTermExempt: entry?.fixedTermExempt ?? false,
      paidLeaveDays: entry?.paidLeaveDays == null ? '' : String(entry.paidLeaveDays),
    },
  })
  const kind = useWatch({ control, name: 'kind' })
  const type = useWatch({ control, name: 'employmentType' })
  const employment = kind === 'EMPLOYMENT'
  // Przejście na czas nieokreślony zdejmuje koniec umowy terminowej: zostawiony kończyłby umowę bezterminową.
  const previousType = useRef(type)
  useEffect(() => {
    if (type === 'INDEFINITE' && previousType.current !== 'INDEFINITE') {
      setValue('endDate', '')
    }
    previousType.current = type
  }, [type, setValue])

  return (
    <Shell
      title={entry ? 'Popraw umowę' : 'Nowa umowa'}
      description={
        entry
          ? 'Tylko pomyłka we wpisie: zmienia dane od początku umowy, a poprzednia wersja zostaje w historii. Zmianę etatu od danego dnia wpisz przez Aneks.'
          : undefined
      }
      error={error}
      pending={pending}
      onCancel={props.onDone}
      onSubmit={handleSubmit((values) =>
        save({
          kind: values.kind,
          employmentType: employment ? orNull(values.employmentType) : null,
          startDate: values.startDate,
          endDate: orNull(values.endDate),
          fteNumerator: employment ? Number(values.fteNumerator) : 1,
          fteDenominator: employment ? Number(values.fteDenominator) : 1,
          fixedTermExempt: employment && type === 'FIXED' && values.fixedTermExempt,
          paidLeaveDays: kind === 'B2B' && values.paidLeaveDays !== '' ? Number(values.paidLeaveDays) : null,
        }),
      )}
    >
      <Choice control={control} name="kind" label="Rodzaj umowy" options={CONTRACT_KINDS} required="Wybierz rodzaj" />
      {employment && (
        <Choice
          control={control}
          name="employmentType"
          label="Na jaki czas"
          options={EMPLOYMENT_TYPES}
          required="Wybierz rodzaj umowy o pracę"
        />
      )}
      <DateInput control={control} name="startDate" label="Początek" required="Podaj początek umowy" />
      <DateInput
        control={control}
        name="endDate"
        label={employment && type === 'INDEFINITE' ? 'Rozwiązanie umowy' : 'Koniec'}
        optional="Bez końca"
      />
      {employment && (
        <div className="grid grid-cols-2 gap-2">
          <TextField
            label="Etat: licznik"
            inputMode="numeric"
            error={errors.fteNumerator?.message}
            {...register('fteNumerator', {
              required: 'Podaj etat',
              pattern: { value: /^\d+$/, message: 'Sama liczba' },
            })}
          />
          <TextField
            label="Etat: mianownik"
            inputMode="numeric"
            error={errors.fteDenominator?.message}
            {...register('fteDenominator', {
              required: 'Podaj etat',
              pattern: { value: /^\d+$/, message: 'Sama liczba' },
            })}
          />
        </div>
      )}
      {employment && type === 'FIXED' && (
        <Toggle
          control={control}
          name="fixedTermExempt"
          label="Poza limitem 33 miesięcy"
          description="Zastępstwo, praca sezonowa lub dorywcza, kadencja albo obiektywne przyczyny (art. 25¹ § 4 KP)."
        />
      )}
      {kind === 'B2B' && (
        <TextField
          label="Płatne dni wolne w roku"
          inputMode="numeric"
          hint="Z umowy B2B. Puste = umowa nie przewiduje płatnej przerwy."
          error={errors.paidLeaveDays?.message}
          {...register('paidLeaveDays', {
            pattern: { value: /^\d*$/, message: 'Sama liczba' },
            validate: (value) => value === '' || Number(value) <= 366 || 'Najwyżej 366 dni',
          })}
        />
      )}
      {!employment && (
        <FormNote>
          Kodeks pracy nie obejmuje tej umowy: program nie nalicza urlopu (przy B2B tylko płatną przerwę z umowy). Stałe
          godziny i kierownictwo mogą świadczyć o stosunku pracy.
        </FormNote>
      )}
    </Shell>
  )
}

type ChangeValues = { validFrom: string; fteNumerator: string; fteDenominator: string }

/** Aneks: etat od wskazanego dnia, a okres przed nim liczy się dalej z poprzedniego. */
function ContractChangeForm({ contract, ...props }: FormProps & { contract: StaffContract }) {
  const { addContractChange } = useRecordActions(props.storeId, props.employeeId)
  const [error, setError] = useState<string | null>(null)
  const {
    control,
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ChangeValues>({ defaultValues: { validFrom: '', fteNumerator: '1', fteDenominator: '2' } })

  return (
    <Shell
      title="Aneks: zmiana etatu"
      description="Etat obowiązuje od wskazanego dnia. Urlop i godziny przed tym dniem liczą się dalej z poprzedniego."
      error={error}
      pending={addContractChange.isPending}
      onCancel={props.onDone}
      onSubmit={handleSubmit((values) => {
        setError(null)
        addContractChange.mutate(
          {
            contractId: contract.id,
            json: {
              validFrom: values.validFrom,
              fteNumerator: Number(values.fteNumerator),
              fteDenominator: Number(values.fteDenominator),
            },
          },
          { onSuccess: props.onDone, onError: (cause) => setError(failed(cause)) },
        )
      })}
    >
      <DateInput control={control} name="validFrom" label="Obowiązuje od" required="Podaj datę zmiany" />
      <div className="grid grid-cols-2 gap-2">
        <TextField
          label="Etat: licznik"
          inputMode="numeric"
          error={errors.fteNumerator?.message}
          {...register('fteNumerator', { required: 'Podaj etat', pattern: { value: /^\d+$/, message: 'Sama liczba' } })}
        />
        <TextField
          label="Etat: mianownik"
          inputMode="numeric"
          error={errors.fteDenominator?.message}
          {...register('fteDenominator', {
            required: 'Podaj etat',
            pattern: { value: /^\d+$/, message: 'Sama liczba' },
          })}
        />
      </div>
      <FormNote>
        Przedłużenie umowy na czas określony to nie aneks: liczy się jako kolejna umowa (art. 25¹ § 2 KP), więc dodaj
        nową.
      </FormNote>
    </Shell>
  )
}

type PeriodValues = {
  kind: WorkPeriodKind
  employer: string
  startDate: string
  endDate: string
  documented: boolean
}

function PeriodForm({ entry, firstJob, ...props }: FormProps & { entry?: StaffWorkPeriod; firstJob: boolean }) {
  // Przy pierwszej pracy w życiu wcześniejszego zatrudnienia nie było; zostaje tylko zlecenie i działalność.
  const kinds = firstJob
    ? WORK_PERIOD_KINDS.filter((option) => !EARLIER_JOB_KINDS.includes(option.value) || option.value === entry?.kind)
    : WORK_PERIOD_KINDS
  const { save, error, pending } = useEntrySave(props, 'work-periods', entry?.id)
  const { control, register, handleSubmit } = useForm<PeriodValues>({
    defaultValues: {
      kind: entry?.kind ?? kinds[0].value,
      employer: entry?.employer ?? '',
      startDate: entry?.startDate ?? '',
      endDate: entry?.endDate ?? '',
      documented: entry?.documented ?? false,
    },
  })
  const kind = useWatch({ control, name: 'kind' })

  return (
    <Shell
      title={entry ? 'Okres pracy' : 'Wcześniejszy okres pracy'}
      description={
        firstJob
          ? 'Okresy poza tą firmą liczone do stażu. Pracownik ma zaznaczoną pierwszą pracę w życiu, więc można dodać tylko zlecenie albo działalność.'
          : 'Okresy poza tą firmą liczone do stażu. Nakładające się liczą się raz.'
      }
      error={error}
      pending={pending}
      onCancel={props.onDone}
      onSubmit={handleSubmit((values) =>
        save({ ...values, documented: values.documented || values.kind === 'EMPLOYMENT' }),
      )}
    >
      <Choice control={control} name="kind" label="Rodzaj" options={kinds} required="Wybierz rodzaj" />
      <TextField label="Pracodawca lub zleceniodawca (opcjonalnie)" autoComplete="off" {...register('employer')} />
      <DateInput control={control} name="startDate" label="Początek" required="Podaj początek" />
      <DateInput control={control} name="endDate" label="Koniec" required="Podaj koniec" />
      {kind !== 'EMPLOYMENT' && (
        <Toggle
          control={control}
          name="documented"
          label="Udokumentowane"
          description="Pracownik dostarczył zaświadczenie z ZUS albo inny dokument (art. 302¹ KP). Bez tego okres się nie liczy."
        />
      )}
    </Shell>
  )
}

type DisabilityValues = {
  degree: DisabilityDegree | ''
  startDate: string
  endDate: string
  doctorConsent: boolean
}

function DisabilityForm({ entry, ...props }: FormProps & { entry?: StaffDisability }) {
  const { save, error, pending } = useEntrySave(props, 'disabilities', entry?.id)
  const { control, handleSubmit } = useForm<DisabilityValues>({
    defaultValues: {
      degree: entry?.degree ?? '',
      startDate: entry?.startDate ?? '',
      endDate: entry?.endDate ?? '',
      doctorConsent: entry?.doctorConsent ?? false,
    },
  })

  return (
    <Shell
      title="Orzeczenie o niepełnosprawności"
      error={error}
      pending={pending}
      onCancel={props.onDone}
      onSubmit={handleSubmit((values) => save({ ...values, endDate: orNull(values.endDate) }))}
    >
      <FormNote icon={<LockIcon />}>
        Dane wrażliwe. Wpisuj tylko stopień i daty, bez rozpoznania i symbolu przyczyny. Widzą je tylko osoby z dostępem
        do Kadr.
      </FormNote>
      <Choice control={control} name="degree" label="Stopień" options={DEGREES} required="Wybierz stopień" />
      <DateInput control={control} name="startDate" label="Zaliczony do stopnia od" required="Podaj datę" />
      <DateInput control={control} name="endDate" label="Ważne do" optional="Na stałe" />
      <Toggle
        control={control}
        name="doctorConsent"
        label="Zgoda lekarza z art. 16"
        description="Na wniosek pracownika lekarz zgodził się na pracę poza normami z art. 15 ustawy o rehabilitacji."
      />
    </Shell>
  )
}

type LeaveValues = { kind: LeaveKind; startDate: string; endDate: string }

/** {@code date}: dzień, od którego zaczyna się nowy urlop (z grafiku). */
function LeaveForm({ entry, date, ...props }: FormProps & { entry?: StaffLeave; date?: string }) {
  const { save, error, pending } = useEntrySave(props, 'leaves', entry?.id)
  const { control, handleSubmit } = useForm<LeaveValues>({
    defaultValues: {
      kind: entry?.kind ?? 'ANNUAL',
      startDate: entry?.startDate ?? date ?? '',
      endDate: entry?.endDate ?? date ?? '',
    },
  })

  return (
    <Shell
      title={entry ? 'Urlop' : 'Nowy urlop'}
      error={error}
      pending={pending}
      onCancel={props.onDone}
      onSubmit={handleSubmit((values) => save(values))}
    >
      <Choice
        control={control}
        name="kind"
        label="Rodzaj"
        options={LEAVE_KINDS.map(({ value, label, limit }) => ({
          value,
          label: limit ? `${label} (do ${limit} dni w roku)` : label,
        }))}
        required="Wybierz rodzaj"
      />
      <DateInput control={control} name="startDate" label="Od" required="Podaj początek" />
      <DateInput control={control} name="endDate" label="Do" required="Podaj koniec" />
      <p className="text-sm text-muted-foreground">
        Dniami urlopu są dni z zaplanowaną pracą w grafiku (art. 154² KP), także w weekend. Dzień bez godzin nie zużywa
        urlopu ani wymiaru, więc liczba dni rośnie, gdy kierownik ułoży godziny.
      </p>
    </Shell>
  )
}

type ProfileValues = {
  birthDate: string
  educationLevel: EducationLevel | ''
  educationEndDate: string
  firstJob: boolean
  medicalExamUntil: string
  bhpTrainingUntil: string
  sanitaryUntil: string
}

function ProfileForm({
  profile,
  earlierJob,
  ...props
}: FormProps & { profile: StaffProfile | null; earlierJob: boolean }) {
  const { saveProfile } = useRecordActions(props.storeId, props.employeeId)
  const [error, setError] = useState<string | null>(null)
  const { control, handleSubmit } = useForm<ProfileValues>({
    defaultValues: {
      birthDate: profile?.birthDate ?? '',
      educationLevel: profile?.educationLevel ?? '',
      educationEndDate: profile?.educationEndDate ?? '',
      firstJob: profile?.firstJob ?? false,
      medicalExamUntil: profile?.medicalExamUntil ?? '',
      bhpTrainingUntil: profile?.bhpTrainingUntil ?? '',
      sanitaryUntil: profile?.sanitaryUntil ?? '',
    },
  })

  return (
    <Shell
      title="Dane i terminy"
      error={error}
      pending={saveProfile.isPending}
      onCancel={props.onDone}
      onSubmit={handleSubmit((values) => {
        setError(null)
        saveProfile.mutate(
          {
            birthDate: orNull(values.birthDate),
            educationLevel: orNull(values.educationLevel),
            educationEndDate: orNull(values.educationEndDate),
            firstJob: values.firstJob,
            medicalExamUntil: orNull(values.medicalExamUntil),
            bhpTrainingUntil: orNull(values.bhpTrainingUntil),
            sanitaryUntil: orNull(values.sanitaryUntil),
          },
          { onSuccess: props.onDone, onError: (cause) => setError(failed(cause)) },
        )
      })}
    >
      <DateInput control={control} name="birthDate" label="Data urodzenia" optional="Wyczyść" />
      <Choice control={control} name="educationLevel" label="Ukończona szkoła" options={EDUCATION_LEVELS} />
      <DateInput control={control} name="educationEndDate" label="Data ukończenia szkoły" optional="Wyczyść" />
      <Toggle
        control={control}
        name="firstJob"
        label="Pierwsza praca w życiu"
        description={
          earlierJob && !profile?.firstJob
            ? 'Niedostępne: w kartotece jest wcześniejsze zatrudnienie.'
            : 'W pierwszym roku urlop rośnie co miesiąc po 1/12 (art. 153 § 1 KP).'
        }
        disabled={earlierJob && !profile?.firstJob}
      />
      <DateInput control={control} name="medicalExamUntil" label="Badania lekarskie ważne do" optional="Wyczyść" />
      <DateInput control={control} name="bhpTrainingUntil" label="Szkolenie BHP ważne do" optional="Wyczyść" />
      <DateInput control={control} name="sanitaryUntil" label="Badania sanitarne ważne do" optional="Wyczyść" />
    </Shell>
  )
}

function OpeningForm({ year, ...props }: FormProps & { year: number }) {
  const { saveOpening } = useRecordActions(props.storeId, props.employeeId)
  const [error, setError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<{ year: string; days: string }>({ defaultValues: { year: String(year), days: '' } })

  return (
    <Shell
      title="Urlop z poprzednich lat"
      description="Niewykorzystane dni na początku podanego roku, gdy kartoteka startuje w trakcie zatrudnienia."
      error={error}
      pending={saveOpening.isPending}
      onCancel={props.onDone}
      onSubmit={handleSubmit((values) => {
        setError(null)
        saveOpening.mutate(
          { year: Number(values.year), days: Number(values.days) },
          { onSuccess: props.onDone, onError: (cause) => setError(failed(cause)) },
        )
      })}
    >
      <TextField
        label="Rok"
        inputMode="numeric"
        error={errors.year?.message}
        {...register('year', { required: 'Podaj rok', pattern: { value: /^\d{4}$/, message: 'Podaj rok' } })}
      />
      <TextField
        label="Dni"
        inputMode="numeric"
        error={errors.days?.message}
        {...register('days', { required: 'Podaj liczbę dni', pattern: { value: /^\d+$/, message: 'Sama liczba' } })}
      />
    </Shell>
  )
}
