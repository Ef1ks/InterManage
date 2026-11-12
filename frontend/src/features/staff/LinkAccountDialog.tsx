import { useState } from 'react'
import { LinkIcon, MailIcon } from 'lucide-react'
import { Controller, useForm } from 'react-hook-form'
import { toast } from 'sonner'

import { Form, FormActions, FormNote, SegmentedField, SelectField, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useEmployeeActions, useLoginSuggestion, useStaffAccounts } from '@/features/staff/api'
import { applyServerErrors } from '@/lib/form-errors'
import type { StaffEmployee } from '@/lib/types'

type Mode = 'existing' | 'invite'

const MODES: { value: Mode; label: string }[] = [
  { value: 'existing', label: 'Ma już konto' },
  { value: 'invite', label: 'Wyślij zaproszenie' },
]

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

type Values = { userId: string; email: string; username: string }

type Props = {
  storeId: string
  /** Pracownik bez konta; brak = okno zamknięte. */
  employee?: StaffEmployee
  onClose: () => void
}

/**
 * Konto dla pracownika założonego bez konta: istniejące (osoba ma już konto bez stanowiska) albo nowe z zaproszeniem.
 * Grafik, kadry i czas pracy zostają przy tym samym pracowniku, a imię i nazwisko bierze się odtąd z konta.
 */
export function LinkAccountDialog({ storeId, employee, onClose }: Props) {
  return (
    <Dialog open={employee !== undefined} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="gap-5 p-6 sm:max-w-lg">
        {employee && <LinkAccountBody key={employee.id} storeId={storeId} employee={employee} onDone={onClose} />}
      </DialogContent>
    </Dialog>
  )
}

function LinkAccountBody({
  storeId,
  employee,
  onDone,
}: {
  storeId: string
  employee: StaffEmployee
  onDone: () => void
}) {
  const [mode, setMode] = useState<Mode>('existing')
  const [formError, setFormError] = useState<string | null>(null)
  const { linkAccount, inviteAccount } = useEmployeeActions(storeId)
  const { data: accounts = [] } = useStaffAccounts(storeId, true)
  const { data: suggestion } = useLoginSuggestion(storeId, mode === 'invite' ? employee.displayName : '')
  const {
    control,
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<Values>({ defaultValues: { userId: '', email: '', username: '' } })

  const onError = (error: unknown) => setFormError(applyServerErrors(error, setError, ['userId', 'email', 'username']))

  const onSubmit = handleSubmit((values) => {
    setFormError(null)
    if (mode === 'existing') {
      linkAccount.mutate(
        { id: employee.id, userId: values.userId },
        {
          onSuccess: (linked) => {
            toast.success(`${linked.displayName} ma teraz konto ${linked.username}`)
            onDone()
          },
          onError,
        },
      )
    } else {
      inviteAccount.mutate(
        { id: employee.id, email: values.email, username: values.username },
        {
          onSuccess: (linked) => {
            toast.success('Zaproszenie wysłane', {
              description: `${linked.displayName} (login ${linked.username}) dostanie link na ${linked.email}.`,
            })
            onDone()
          },
          onError,
        },
      )
    }
  })

  return (
    <Form onSubmit={onSubmit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">Konto dla: {employee.displayName}</DialogTitle>
        <DialogDescription>
          Grafik, kadry i czas pracy zostają przy tym pracowniku. Uprawnienia konta wynikają ze stanowiska{' '}
          {employee.positionName}.
        </DialogDescription>
      </DialogHeader>

      <SegmentedField label="Konto" options={MODES} value={mode} onChange={setMode} className="w-fit" />

      {mode === 'existing' ? (
        <Controller
          control={control}
          name="userId"
          rules={{ required: 'Wybierz konto' }}
          render={({ field }) => (
            <SelectField
              label="Konto"
              options={accounts.map((account) => ({
                value: account.id,
                label: `${account.displayName} (${account.username})`,
              }))}
              value={field.value}
              onChange={field.onChange}
              onBlur={field.onBlur}
              error={errors.userId?.message}
              hint={
                accounts.length === 0
                  ? 'Nie ma konta bez stanowiska z dostępem do tego sklepu. Wyślij zaproszenie.'
                  : 'Konta z dostępem do sklepu, które nie mają jeszcze stanowiska.'
              }
            />
          )}
        />
      ) : (
        <>
          <TextField
            label="Adres e-mail"
            type="email"
            inputMode="email"
            autoComplete="off"
            autoCapitalize="none"
            spellCheck={false}
            error={errors.email?.message}
            {...register('email', {
              required: 'Podaj adres e-mail',
              pattern: { value: EMAIL_PATTERN, message: 'Podaj poprawny adres e-mail' },
              maxLength: { value: 255, message: 'Maksymalnie 255 znaków' },
            })}
          />
          <TextField
            label="Login"
            autoComplete="off"
            autoCapitalize="none"
            spellCheck={false}
            hint={suggestion ? `Puste = ${suggestion.username}` : 'Puste = pierwsza litera imienia i nazwisko.'}
            error={errors.username?.message}
            {...register('username', {
              pattern: {
                value: /^[A-Za-z0-9._-]{3,50}$/,
                message: 'Login: 3-50 znaków, litery, cyfry, kropka, myślnik lub podkreślnik',
              },
            })}
          />
          <FormNote icon={<MailIcon />}>Po kliknięciu w link pracownik ustawi własne hasło.</FormNote>
        </>
      )}

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onDone}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={linkAccount.isPending || inviteAccount.isPending}>
          <LinkIcon />
          {mode === 'existing' ? 'Podepnij konto' : 'Wyślij zaproszenie'}
        </Button>
      </FormActions>
    </Form>
  )
}
