import { useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { Navigate, useLocation, useNavigate } from 'react-router'

import { Form, SwitchField, TextField } from '@/components/form'
import { AuthLayout } from '@/components/layout/AuthLayout'
import { Button } from '@/components/ui/button'
import { AccountSwitcher } from '@/features/auth/AccountSwitcher'
import { useLogin, useMe, useSwitchAccounts } from '@/features/auth/api'
import { requestPriceFolderAccess } from '@/features/data-entry/price-files'
import { ApiError } from '@/lib/api'
import type { Me, SwitchAccount } from '@/lib/types'

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
  const accounts = useSwitchAccounts()
  // undefined = jeszcze bez wyboru: domyślnie PIN ostatnio używanego konta.
  const [chosenId, setChosenId] = useState<string | null | undefined>(undefined)
  const wantedId = chosenId === undefined ? (lastLockedAccount(accounts.data)?.id ?? null) : chosenId
  // Konto wylogowane w międzyczasie nie trzyma schowanego formularza hasła.
  const selectedId = accounts.data?.some((account) => account.id === wantedId) ? wantedId : null

  const {
    register,
    handleSubmit,
    control,
    formState: { errors },
  } = useForm<LoginForm>({ defaultValues: { username: '', password: '', rememberMe: false } })

  if (me) {
    return <Navigate to={me.passwordChangeRequired ? '/ustaw-haslo' : from} replace />
  }

  const onSubmit = handleSubmit((values) => {
    // Kliknięcie „Zaloguj” to gest, więc od razu zgoda na folder z plikami cen z kasy (na komputerze, gdzie go wybrano).
    requestPriceFolderAccess()
    login.mutate(values, {
      onSuccess: (user) => navigate(user.passwordChangeRequired ? '/ustaw-haslo' : from, { replace: true }),
    })
  })

  const errorMessage =
    login.error instanceof ApiError ? login.error.message : login.error ? 'Nie udało się połączyć z serwerem.' : null

  return (
    <AuthLayout
      title="Zaloguj się"
      description="Panel zarządzania sklepem."
      footer="Nie pamiętasz hasła? Poproś właściciela sklepu o hasło tymczasowe."
    >
      <BackgroundAccounts
        selectedId={selectedId}
        onSelectedIdChange={setChosenId}
        onSwitched={(user) => navigate(user.passwordChangeRequired ? '/ustaw-haslo' : from, { replace: true })}
      />
      {/* Przy PIN-ie konta z listy formularz hasła się chowa („Inne konto” wraca do listy i do niego). */}
      {!selectedId && (
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
      )}
    </AuthLayout>
  )
}

/**
 * Ostatnio używane konto w tle, o ile wymaga PIN-u albo hasła: serwer dopisuje odkładane konto na koniec listy.
 * Konto bez PIN-u nie jest wybierane samo, bo przejście na nie to jedno kliknięcie.
 */
function lastLockedAccount(accounts: SwitchAccount[] | undefined) {
  const last = accounts?.at(-1)
  return last && last.unlock !== 'NONE' ? last : null
}

/** Konta, które zostały zalogowane w tle na tym komputerze: przejście na nie PIN-em zamiast hasła. */
function BackgroundAccounts({
  onSwitched,
  selectedId,
  onSelectedIdChange,
}: {
  onSwitched: (me: Me) => void
  selectedId: string | null
  onSelectedIdChange: (id: string | null) => void
}) {
  const accounts = useSwitchAccounts()
  if (!accounts.data?.length) return null

  return (
    <section aria-labelledby="background-accounts" className="mb-6 grid gap-3 border-b pb-6">
      <h2 id="background-accounts" className="text-sm font-medium">
        Konta zalogowane na tym komputerze
      </h2>
      <AccountSwitcher onSwitched={onSwitched} selectedId={selectedId} onSelectedIdChange={onSelectedIdChange} />
      {!selectedId && <p className="text-xs text-muted-foreground">Albo zaloguj się innym kontem:</p>}
    </section>
  )
}
