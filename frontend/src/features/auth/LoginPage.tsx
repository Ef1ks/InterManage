import { Controller, useForm } from 'react-hook-form'
import { Navigate, useLocation, useNavigate } from 'react-router'

import { Form, SwitchField, TextField } from '@/components/form'
import { AuthLayout } from '@/components/layout/AuthLayout'
import { Button } from '@/components/ui/button'
import { useLogin, useMe } from '@/features/auth/api'
import { ApiError } from '@/lib/api'

type LoginForm = {
  username: string
  password: string
  rememberMe: boolean
}

export function LoginPage() {
  const { data: me } = useMe()
  const login = useLogin()
  const navigate = useNavigate()
  const location = useLocation()
  const from = (location.state as { from?: string } | null)?.from ?? '/'

  const {
    register,
    handleSubmit,
    control,
    formState: { errors },
  } = useForm<LoginForm>({ defaultValues: { username: '', password: '', rememberMe: false } })

  if (me) {
    return <Navigate to={me.passwordChangeRequired ? '/ustaw-haslo' : from} replace />
  }

  const onSubmit = handleSubmit((values) =>
    login.mutate(values, {
      onSuccess: (user) => navigate(user.passwordChangeRequired ? '/ustaw-haslo' : from, { replace: true }),
    }),
  )

  const errorMessage =
    login.error instanceof ApiError ? login.error.message : login.error ? 'Nie udało się połączyć z serwerem.' : null

  return (
    <AuthLayout
      title="Zaloguj się"
      description="Panel zarządzania sklepem."
      footer="Nie pamiętasz hasła? Poproś właściciela sklepu o hasło tymczasowe."
    >
      <Form onSubmit={onSubmit} error={errorMessage}>
        <TextField
          label="Login"
          autoComplete="username"
          autoCapitalize="none"
          spellCheck={false}
          autoFocus
          error={errors.username?.message}
          {...register('username', { required: 'Podaj login' })}
        />
        <TextField
          label="Hasło"
          type="password"
          autoComplete="current-password"
          error={errors.password?.message}
          {...register('password', { required: 'Podaj hasło' })}
        />
        <Controller
          control={control}
          name="rememberMe"
          render={({ field }) => (
            <SwitchField
              label="Nie wylogowuj mnie"
              description="Zostaniesz zalogowany przez 30 dni od ostatniego użycia. Nie zaznaczaj na wspólnym komputerze."
              checked={field.value}
              onCheckedChange={field.onChange}
            />
          )}
        />
        <Button type="submit" size="xl" loading={login.isPending} className="mt-2 w-full">
          Zaloguj się
        </Button>
      </Form>
    </AuthLayout>
  )
}
