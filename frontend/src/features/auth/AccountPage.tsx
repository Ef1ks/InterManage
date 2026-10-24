import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'

import { Form, FormActions, TextField } from '@/components/form'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { useChangePassword, useCurrentUser } from '@/features/auth/api'
import { applyServerErrors } from '@/lib/form-errors'

type PasswordForm = {
  currentPassword: string
  newPassword: string
  confirmPassword: string
}

export function AccountPage() {
  const me = useCurrentUser()
  const changePassword = useChangePassword()
  const [formError, setFormError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    setError,
    reset,
    getValues,
    formState: { errors },
  } = useForm<PasswordForm>({ defaultValues: { currentPassword: '', newPassword: '', confirmPassword: '' } })

  const onSubmit = handleSubmit(({ currentPassword, newPassword }) => {
    setFormError(null)
    changePassword.mutate(
      { currentPassword, newPassword },
      {
        onSuccess: () => {
          reset()
          toast.success('Hasło zostało zmienione', { description: 'Na innych urządzeniach trzeba zalogować się ponownie.' })
        },
        onError: (error) => setFormError(applyServerErrors(error, setError, ['currentPassword', 'newPassword'])),
      },
    )
  })

  return (
    <>
      <PageHeader title="Moje konto" />
      <div className="grid max-w-3xl gap-6 lg:grid-cols-[1fr_1.4fr]">
        <section aria-labelledby="account-details" className="grid content-start gap-3">
          <h2 id="account-details" className="text-sm font-medium">
            Dane konta
          </h2>
          <dl className="grid gap-3 text-sm">
            <Detail label="Imię i nazwisko" value={me.displayName} />
            <Detail label="Login" value={me.username} />
            <Detail label="E-mail" value={me.email ?? '—'} />
            <Detail label="Rola" value={me.roleName} />
          </dl>
        </section>

        <Card>
          <CardContent>
            <h2 className="text-base font-semibold">Zmiana hasła</h2>
            <p className="mt-1 mb-5 text-sm text-muted-foreground">Po zmianie zostaniesz wylogowany na innych urządzeniach.</p>
            <Form onSubmit={onSubmit} error={formError}>
              <TextField
                label="Obecne hasło"
                type="password"
                autoComplete="current-password"
                error={errors.currentPassword?.message}
                {...register('currentPassword', { required: 'Podaj obecne hasło' })}
              />
              <TextField
                label="Nowe hasło"
                type="password"
                autoComplete="new-password"
                hint="Co najmniej 8 znaków."
                error={errors.newPassword?.message}
                {...register('newPassword', {
                  required: 'Podaj nowe hasło',
                  minLength: { value: 8, message: 'Hasło musi mieć co najmniej 8 znaków' },
                  maxLength: { value: 72, message: 'Hasło może mieć maksymalnie 72 znaki' },
                })}
              />
              <TextField
                label="Powtórz nowe hasło"
                type="password"
                autoComplete="new-password"
                error={errors.confirmPassword?.message}
                {...register('confirmPassword', {
                  validate: (value) => value === getValues('newPassword') || 'Hasła nie są takie same',
                })}
              />
              <FormActions>
                <Button type="submit" size="lg" loading={changePassword.isPending}>
                  Zmień hasło
                </Button>
              </FormActions>
            </Form>
          </CardContent>
        </Card>
      </div>
    </>
  )
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <div className="grid gap-0.5">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="truncate">{value}</dd>
    </div>
  )
}
