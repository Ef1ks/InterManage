import { useEffect, useState } from 'react'
import { MailIcon, ShieldIcon, UserMinusIcon } from 'lucide-react'
import { Controller, useForm, useWatch } from 'react-hook-form'
import { toast } from 'sonner'

import { Form, FormActions, FormNote, SegmentedField, SelectField, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCurrentUser } from '@/features/auth/api'
import { useEmployeeActions, useLoginSuggestion, useStaffAccounts, useStaffStructure } from '@/features/staff/api'
import { ShiftPicker } from '@/features/staff/ShiftPicker'
import { storeLabel } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import { applyServerErrors } from '@/lib/form-errors'
import type { StaffEmployee, StaffShift, StoreSummary } from '@/lib/types'
import { useDebouncedValue } from '@/lib/use-debounced-value'

type EmployeeForm = {
  displayName: string
  email: string
  username: string
  userId: string
  positionId: string
  shifts: StaffShift[]
}

type Mode = 'invite' | 'existing'

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const MODES: { value: Mode; label: string }[] = [
  { value: 'invite', label: 'Nowe konto' },
  { value: 'existing', label: 'Ma już konto' },
]

type Props = {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Sklepy firmy dostępne dla użytkownika. */
  stores: StoreSummary[]
  /** Brak = nowy pracownik. */
  employee?: StaffEmployee
}

export function EmployeeDialog({ storeId, open, onOpenChange, stores, employee }: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-lg">
        {open && (
          <EmployeeFormBody
            key={employee?.id ?? 'new'}
            storeId={storeId}
            stores={stores}
            employee={employee}
            onDone={() => onOpenChange(false)}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

function EmployeeFormBody({
  storeId,
  stores,
  employee,
  onDone,
}: {
  storeId: string
  stores: StoreSummary[]
  employee?: StaffEmployee
  onDone: () => void
}) {
  const me = useCurrentUser()
  // Nowy pracownik powstaje w wybranym sklepie, a zmiany przechodzą przez bieżący, bo serwer widzi całą firmę.
  const [targetStoreId, setTargetStoreId] = useState(storeId)
  const { invite, assign, update, end, restore } = useEmployeeActions(targetStoreId)
  const [mode, setMode] = useState<Mode>('invite')
  const [formError, setFormError] = useState<string | null>(null)
  const [loginEdited, setLoginEdited] = useState(false)
  const isNew = employee === undefined
  const existing = isNew && mode === 'existing'
  const { data: accounts = [] } = useStaffAccounts(targetStoreId, isNew)

  const {
    register,
    control,
    handleSubmit,
    setError,
    setValue,
    formState: { errors },
  } = useForm<EmployeeForm>({
    defaultValues: {
      displayName: employee?.displayName ?? '',
      email: employee?.email ?? '',
      username: '',
      userId: '',
      positionId: employee?.positionId ?? '',
      shifts: employee?.shifts ?? [],
    },
  })

  const displayName = useWatch({ control, name: 'displayName' })
  const positionId = useWatch({ control, name: 'positionId' })
  const userId = useWatch({ control, name: 'userId' })
  const name = useDebouncedValue(displayName.trim(), 250)
  const { data: suggestion } = useLoginSuggestion(storeId, isNew && !existing && !loginEdited ? name : '')
  useEffect(() => {
    if (!loginEdited && suggestion) setValue('username', suggestion.username)
  }, [loginEdited, suggestion, setValue])

  // Godziny stanowiska są osobne w każdym sklepie, więc bierzemy je ze sklepu pracownika.
  const { data: departments = [] } = useStaffStructure(employee?.storeId ?? targetStoreId)
  const positions = departments.flatMap((department) =>
    department.positions.map((position) => ({ ...position, departmentName: department.name })),
  )
  const position = positions.find((candidate) => candidate.id === positionId)
  const positionOptions = positions.map((candidate) => ({
    value: candidate.id,
    label: `${candidate.departmentName} · ${candidate.name}`,
  }))
  const keepsOwnerRole =
    employee?.ownerAccount || (existing && accounts.some((account) => account.id === userId && account.owner))
  const accountOptions = accounts.map((account) => ({
    value: account.id,
    label: `${account.displayName} (${account.username})`,
  }))

  const onError = (error: unknown) =>
    setFormError(applyServerErrors(error, setError, ['displayName', 'email', 'username', 'userId', 'positionId']))

  const onSubmit = handleSubmit((values) => {
    setFormError(null)
    const { positionId: chosenPosition, shifts } = values
    if (employee) {
      update.mutate(
        {
          id: employee.id,
          request: { displayName: values.displayName, email: values.email || null, positionId: chosenPosition, shifts },
        },
        {
          onSuccess: () => {
            toast.success('Zapisano zmiany')
            onDone()
          },
          onError,
        },
      )
    } else if (existing) {
      assign.mutate(
        { userId: values.userId, positionId: chosenPosition, shifts },
        {
          onSuccess: (created) => {
            toast.success(`${created.displayName} jest już na liście pracowników`)
            onDone()
          },
          onError,
        },
      )
    } else {
      invite.mutate(
        {
          displayName: values.displayName,
          email: values.email,
          username: values.username,
          positionId: chosenPosition,
          shifts,
        },
        {
          onSuccess: (created) => {
            toast.success('Zaproszenie wysłane', {
              description: `${created.displayName} (login ${created.username}) dostanie link na ${created.email}.`,
            })
            onDone()
          },
          onError,
        },
      )
    }
  })

  const endEmployment = (target: StaffEmployee) =>
    end.mutate(target.id, {
      onSuccess: () => {
        toast.success(`${target.displayName}: koniec pracy`, {
          description: target.ownerAccount ? undefined : 'Konto jest zablokowane.',
          action: {
            label: 'Przywróć',
            onClick: () =>
              restore.mutate(target.id, {
                onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się przywrócić'),
              }),
          },
        })
        onDone()
      },
      onError: (error) => setFormError(error instanceof ApiError ? error.message : 'Nie udało się zapisać zmiany'),
    })

  return (
    <Form onSubmit={onSubmit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">{employee ? employee.displayName : 'Dodaj pracownika'}</DialogTitle>
        <DialogDescription>
          {employee
            ? `Login: ${employee.username}`
            : existing
              ? 'Osoba, która ma już konto w aplikacji, dostaje stanowisko w tym sklepie.'
              : 'Pracownik dostanie e-mail z linkiem do aktywacji konta.'}
        </DialogDescription>
      </DialogHeader>

      {isNew && (
        <SegmentedField label="Konto pracownika" options={MODES} value={mode} onChange={setMode} className="w-fit" />
      )}

      {isNew && stores.length > 1 && (
        <SelectField
          label="Sklep"
          options={stores.map((store) => ({ value: store.id, label: storeLabel(store) }))}
          value={targetStoreId}
          onChange={setTargetStoreId}
        />
      )}

      {existing ? (
        <Controller
          control={control}
          name="userId"
          rules={{ required: 'Wybierz konto' }}
          render={({ field }) => (
            <SelectField
              label="Konto"
              options={accountOptions}
              value={field.value}
              onChange={field.onChange}
              onBlur={field.onBlur}
              error={errors.userId?.message}
              hint={
                accountOptions.length === 0 ? 'Wszystkie konta z dostępem do sklepu mają już stanowisko.' : undefined
              }
            />
          )}
        />
      ) : (
        <>
          <TextField
            label="Imię i nazwisko"
            autoComplete="off"
            autoFocus={isNew}
            error={errors.displayName?.message}
            {...register('displayName', {
              required: 'Podaj imię i nazwisko',
              maxLength: { value: 100, message: 'Maksymalnie 100 znaków' },
            })}
          />
          <TextField
            label="Adres e-mail"
            type="email"
            inputMode="email"
            autoComplete="off"
            autoCapitalize="none"
            spellCheck={false}
            error={errors.email?.message}
            {...register('email', {
              required: isNew || employee?.pendingActivation ? 'Podaj adres e-mail' : false,
              pattern: { value: EMAIL_PATTERN, message: 'Podaj poprawny adres e-mail' },
              maxLength: { value: 255, message: 'Maksymalnie 255 znaków' },
            })}
          />
          {isNew && (
            <TextField
              label="Login"
              autoComplete="off"
              autoCapitalize="none"
              spellCheck={false}
              hint="Pierwsza litera imienia i nazwisko. Gdy taki login już jest, dostaje numer, np. jkowalski2."
              error={errors.username?.message}
              {...register('username', {
                pattern: {
                  value: /^[A-Za-z0-9._-]{3,50}$/,
                  message: 'Login: 3-50 znaków, litery, cyfry, kropka, myślnik lub podkreślnik',
                },
                onChange: () => setLoginEdited(true),
              })}
            />
          )}
        </>
      )}

      <Controller
        control={control}
        name="positionId"
        rules={{ required: 'Wybierz stanowisko' }}
        render={({ field }) => (
          <SelectField
            label="Stanowisko"
            options={positionOptions}
            value={field.value}
            onChange={field.onChange}
            onBlur={field.onBlur}
            error={errors.positionId?.message}
          />
        )}
      />

      {position && (
        <FormNote icon={<ShieldIcon />}>
          {keepsOwnerRole
            ? 'Właściciel zostaje właścicielem, stanowisko nie zmienia jego uprawnień.'
            : `Uprawnienia w aplikacji: rola ${position.roleName}.`}
        </FormNote>
      )}

      <div className="grid gap-2">
        <p className="text-sm text-muted-foreground">Stałe godziny pracy</p>
        <Controller
          control={control}
          name="shifts"
          render={({ field }) => (
            <ShiftPicker
              value={field.value}
              onChange={field.onChange}
              options={position?.shifts ?? []}
              addLabel="Inne godziny"
            />
          )}
        />
      </div>

      {isNew && !existing && (
        <FormNote icon={<MailIcon />}>
          Po kliknięciu w link pracownik ustawi własne hasło i zaloguje się także na telefonie. Link jest ważny 3 dni, w
          razie potrzeby wyślesz nowy z listy pracowników.
        </FormNote>
      )}

      <FormActions className="sm:items-center">
        {employee && employee.userId !== me.id && (
          <Button
            type="button"
            variant="ghost"
            size="lg"
            loading={end.isPending}
            onClick={() => endEmployment(employee)}
            className="text-destructive hover:text-destructive sm:mr-auto"
          >
            <UserMinusIcon />
            Koniec pracy
          </Button>
        )}
        <Button type="button" variant="outline" size="lg" onClick={onDone}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={invite.isPending || assign.isPending || update.isPending}>
          {employee ? 'Zapisz zmiany' : existing ? 'Dodaj pracownika' : 'Wyślij zaproszenie'}
        </Button>
      </FormActions>
    </Form>
  )
}
