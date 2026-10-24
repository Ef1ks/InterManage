import { useState } from 'react'
import { CheckIcon } from 'lucide-react'
import { useForm, useWatch } from 'react-hook-form'
import { Navigate, useNavigate } from 'react-router'
import { toast } from 'sonner'

import { Form, TextField } from '@/components/form'
import { AuthLayout } from '@/components/layout/AuthLayout'
import { Button } from '@/components/ui/button'
import { useCurrentUser, useLogout, useSetUpPassword } from '@/features/auth/api'
import { applyServerErrors } from '@/lib/form-errors'
import { cn } from '@/lib/utils'

type SetPasswordForm = {
  newPassword: string
  confirmPassword: string
}

const MIN_LENGTH = 8

/** Obowiązkowy krok po aktywacji konta albo po haśle tymczasowym od właściciela. */
export function SetPasswordPage() {
  const me = useCurrentUser()
  const setUpPassword = useSetUpPassword()
  const logout = useLogout()
  const navigate = useNavigate()
  const [formError, setFormError] = useState<string | null>(null)

  const {
    register,
    control,
    handleSubmit,
    setError,
    getValues,
    formState: { errors },
  } = useForm<SetPasswordForm>({ defaultValues: { newPassword: '', confirmPassword: '' } })
  const newPassword = useWatch({ control, name: 'newPassword' })

  if (!me.passwordChangeRequired) {
    return <Navigate to="/" replace />
  }

  const onSubmit = handleSubmit(({ newPassword }) => {
    setFormError(null)
    setUpPassword.mutate(newPassword, {
      onSuccess: () => {
        toast.success('Hasło ustawione. Witaj w Intermanage!')
        navigate('/', { replace: true })
      },
      onError: (error) => setFormError(applyServerErrors(error, setError, ['newPassword'])),
    })
  })

  const longEnough = newPassword.length >= MIN_LENGTH

  return (
    <AuthLayout
      title="Ustaw swoje hasło"
      description={
        <>
          Cześć, {me.displayName}! Zanim zaczniesz, ustaw hasło, którym będziesz się logować jako{' '}
          <span className="font-medium text-foreground">{me.username}</span>.
        </>
      }
      footer={
        <button
          type="button"
          className="underline-offset-4 hover:text-foreground hover:underline"
          onClick={() => logout.mutate(undefined, { onSettled: () => navigate('/logowanie', { replace: true }) })}
        >
          To nie Ty? Wyloguj się
        </button>
      }
    >
      <Form onSubmit={onSubmit} error={formError}>
        <TextField
          label="Nowe hasło"
          type="password"
          autoComplete="new-password"
          autoFocus
          error={errors.newPassword?.message}
          hint={
            <span className={cn('inline-flex items-center gap-1 transition-colors', longEnough && 'text-foreground')}>
              <CheckIcon
                aria-hidden
                className={cn(
                  'size-3.5 transition-[opacity,transform] duration-200 ease-snappy',
                  longEnough ? 'scale-100 opacity-100' : 'scale-75 opacity-40',
                )}
              />
              Co najmniej {MIN_LENGTH} znaków
            </span>
          }
          {...register('newPassword', {
            required: 'Podaj nowe hasło',
            minLength: { value: MIN_LENGTH, message: `Hasło musi mieć co najmniej ${MIN_LENGTH} znaków` },
            maxLength: { value: 72, message: 'Hasło może mieć maksymalnie 72 znaki' },
          })}
        />
        <TextField
          label="Powtórz hasło"
          type="password"
          autoComplete="new-password"
          error={errors.confirmPassword?.message}
          {...register('confirmPassword', {
            validate: (value) => value === getValues('newPassword') || 'Hasła nie są takie same',
          })}
        />
        <Button type="submit" size="xl" loading={setUpPassword.isPending} className="mt-2 w-full">
          Ustaw hasło i przejdź dalej
        </Button>
      </Form>
    </AuthLayout>
  )
}
