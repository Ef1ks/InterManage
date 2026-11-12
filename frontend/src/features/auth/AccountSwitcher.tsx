import { useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useNavigate } from 'react-router'
import { ArrowLeftIcon, ChevronRightIcon, KeyRoundIcon, LockKeyholeIcon, LogOutIcon, UserPlusIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Form, FormActions, SwitchField, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { UserAvatar } from '@/components/UserAvatar'
import {
  useAddAccount,
  useCurrentUser,
  useLogoutAll,
  useRemoveAccount,
  useSwitchAccount,
  useSwitchAccounts,
} from '@/features/auth/api'
import { ApiError } from '@/lib/api'
import type { Me, SwitchAccount, SwitchUnlock } from '@/lib/types'

const UNLOCK_HINTS: Record<SwitchUnlock, string> = {
  NONE: 'bez PIN-u',
  PIN: 'PIN',
  PASSWORD: 'hasło konta',
}

function errorText(error: unknown): string | null {
  if (!error) return null
  return error instanceof ApiError ? error.message : 'Nie udało się połączyć z serwerem.'
}

/**
 * Konta zalogowane w tle: klik przechodzi na konto (po PIN-ie albo haśle, jeśli konto tego wymaga).
 * Wspólne dla okna „Zmień konto” i strony logowania.
 */
export function AccountSwitcher({ onSwitched }: { onSwitched: (me: Me) => void }) {
  const accounts = useSwitchAccounts()
  const switchAccount = useSwitchAccount()
  const removeAccount = useRemoveAccount()
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const list = accounts.data ?? []
  // Z listy, a nie z chwili kliknięcia: po błędnych PIN-ach konto może wymagać już hasła.
  const selected = list.find((account) => account.id === selectedId) ?? null

  const choose = (account: SwitchAccount) => {
    switchAccount.reset()
    if (account.unlock === 'NONE') {
      switchAccount.mutate({ id: account.id }, { onSuccess: onSwitched })
    } else {
      setSelectedId(account.id)
    }
  }

  const remove = (account: SwitchAccount) =>
    removeAccount.mutate(account.id, {
      onSuccess: () => toast.success(`Wylogowano: ${account.displayName}`),
      onError: () => toast.error('Nie udało się wylogować konta'),
    })

  if (selected) {
    return (
      <UnlockForm
        key={selected.id}
        account={selected}
        pending={switchAccount.isPending}
        error={errorText(switchAccount.error)}
        onBack={() => {
          switchAccount.reset()
          setSelectedId(null)
        }}
        onSubmit={(secret) => switchAccount.mutate({ id: selected.id, ...secret }, { onSuccess: onSwitched })}
      />
    )
  }

  if (accounts.isPending) {
    return <p className="py-3 text-sm text-muted-foreground">Wczytywanie kont…</p>
  }
  if (list.length === 0) {
    return <p className="py-3 text-sm text-muted-foreground">Żadne inne konto nie jest zalogowane w tle.</p>
  }

  return (
    <div className="grid gap-2">
      {switchAccount.error && !selected && (
        <p role="alert" className="text-sm text-destructive">
          {errorText(switchAccount.error)}
        </p>
      )}
      <ul className="grid gap-1.5">
        {list.map((account) => (
          <li key={account.id} className="group/account flex items-center gap-1">
            <button
              type="button"
              onClick={() => choose(account)}
              disabled={switchAccount.isPending}
              className="flex min-w-0 flex-1 items-center gap-3 rounded-xl border bg-background px-3 py-2.5 text-left transition-colors duration-150 hover:bg-muted/60 focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none disabled:opacity-60 dark:bg-input/30 dark:hover:bg-input/50"
            >
              <UserAvatar name={account.displayName} url={account.avatar?.url} className="size-9 text-xs" />
              <span className="grid min-w-0 flex-1">
                <span className="truncate text-sm font-medium">{account.displayName}</span>
                <span className="truncate text-xs text-muted-foreground">
                  {account.username} · {UNLOCK_HINTS[account.unlock]}
                </span>
              </span>
              {account.unlock === 'NONE' ? (
                <ChevronRightIcon className="size-4 text-muted-foreground" aria-hidden />
              ) : (
                <LockKeyholeIcon className="size-4 text-muted-foreground" aria-hidden />
              )}
            </button>
            <Button
              variant="ghost"
              size="icon"
              title={`Wyloguj konto ${account.displayName}`}
              aria-label={`Wyloguj konto ${account.displayName}`}
              onClick={() => remove(account)}
              disabled={removeAccount.isPending}
            >
              <LogOutIcon />
            </Button>
          </li>
        ))}
      </ul>
    </div>
  )
}

function UnlockForm({
  account,
  pending,
  error,
  onBack,
  onSubmit,
}: {
  account: SwitchAccount
  pending: boolean
  error: string | null
  onBack: () => void
  onSubmit: (secret: { pin?: string; password?: string }) => void
}) {
  const usesPin = account.unlock === 'PIN'
  const {
    register,
    handleSubmit,
    resetField,
    formState: { errors },
  } = useForm<{ secret: string }>({ defaultValues: { secret: '' } })

  const submit = handleSubmit(({ secret }) => {
    onSubmit(usesPin ? { pin: secret } : { password: secret })
    // Błędny PIN wpisuje się od nowa, bez kasowania znaków.
    resetField('secret')
  })

  return (
    <Form onSubmit={submit} error={error}>
      <div className="flex items-center gap-3">
        <UserAvatar name={account.displayName} url={account.avatar?.url} className="size-11 text-sm" />
        <div className="grid min-w-0">
          <span className="truncate font-medium">{account.displayName}</span>
          <span className="truncate text-xs text-muted-foreground">{account.username}</span>
        </div>
      </div>
      <TextField
        key={account.unlock}
        label={usesPin ? 'PIN' : 'Hasło konta'}
        type="password"
        inputMode={usesPin ? 'numeric' : undefined}
        autoComplete={usesPin ? 'off' : 'current-password'}
        maxLength={usesPin ? 8 : undefined}
        autoFocus
        hint={usesPin ? undefined : 'To konto nie ma PIN-u albo PIN został zablokowany po błędnych próbach.'}
        error={errors.secret?.message}
        {...register('secret', { required: usesPin ? 'Podaj PIN' : 'Podaj hasło' })}
      />
      <FormActions>
        <Button type="button" variant="ghost" onClick={onBack}>
          <ArrowLeftIcon />
          Inne konto
        </Button>
        <Button type="submit" size="lg" loading={pending}>
          <KeyRoundIcon />
          Przełącz
        </Button>
      </FormActions>
    </Form>
  )
}

type AddAccountForm = { username: string; password: string; rememberMe: boolean }

function AddAccountForm({ onBack, onAdded }: { onBack: () => void; onAdded: (me: Me) => void }) {
  const addAccount = useAddAccount()
  const {
    register,
    handleSubmit,
    control,
    formState: { errors },
  } = useForm<AddAccountForm>({ defaultValues: { username: '', password: '', rememberMe: false } })

  const submit = handleSubmit((values) => addAccount.mutate(values, { onSuccess: onAdded }))

  return (
    <Form onSubmit={submit} error={errorText(addAccount.error)}>
      <TextField
        label="Login"
        autoComplete="off"
        autoCapitalize="none"
        spellCheck={false}
        autoFocus
        error={errors.username?.message}
        {...register('username', { required: 'Podaj login' })}
      />
      <TextField
        label="Hasło"
        type="password"
        autoComplete="off"
        error={errors.password?.message}
        {...register('password', { required: 'Podaj hasło' })}
      />
      <Controller
        control={control}
        name="rememberMe"
        render={({ field }) => (
          <SwitchField
            label="Nie wylogowuj tego konta"
            description="Konto zostanie zalogowane przez 30 dni od ostatniego użycia."
            checked={field.value}
            onCheckedChange={field.onChange}
          />
        )}
      />
      <FormActions>
        <Button type="button" variant="ghost" onClick={onBack}>
          <ArrowLeftIcon />
          Wróć
        </Button>
        <Button type="submit" size="lg" loading={addAccount.isPending}>
          Zaloguj i przełącz
        </Button>
      </FormActions>
    </Form>
  )
}

/** Okno „Zmień konto” z menu konta: konta w tle, dodanie kolejnego konta, wylogowanie wszystkich. */
export function SwitchAccountDialog({
  open,
  onOpenChange,
  onSwitched,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onSwitched: (me: Me) => void
}) {
  const me = useCurrentUser()
  const logoutAll = useLogoutAll()
  const navigate = useNavigate()
  const [adding, setAdding] = useState(false)

  const changeOpen = (next: boolean) => {
    if (!next) setAdding(false)
    onOpenChange(next)
  }

  const switched = (next: Me) => {
    changeOpen(false)
    toast.success(`Przełączono na konto: ${next.displayName}`, {
      description: `${me.displayName} zostaje zalogowany w tle.`,
    })
    onSwitched(next)
  }

  return (
    <Dialog open={open} onOpenChange={changeOpen}>
      <DialogContent className="gap-5 p-6 sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{adding ? 'Dodaj konto' : 'Zmień konto'}</DialogTitle>
          <DialogDescription>
            {adding
              ? `Zaloguj kolejne konto. ${me.displayName} zostanie zalogowany w tle.`
              : 'Pozostałe konta zostają zalogowane w tle. Przejście na konto wymaga jego PIN-u.'}
          </DialogDescription>
        </DialogHeader>

        {adding ? (
          <AddAccountForm onBack={() => setAdding(false)} onAdded={switched} />
        ) : (
          <>
            <div className="flex items-center gap-3 rounded-xl bg-muted/70 px-3 py-2.5">
              <UserAvatar name={me.displayName} url={me.avatar?.url} className="size-9 text-xs" />
              <div className="grid min-w-0 flex-1">
                <span className="truncate text-sm font-medium">{me.displayName}</span>
                <span className="truncate text-xs text-muted-foreground">Aktywne konto</span>
              </div>
            </div>
            <AccountSwitcher onSwitched={switched} />
            <div className="flex flex-col-reverse gap-2 border-t pt-4 sm:flex-row sm:justify-between">
              <Button
                variant="ghost"
                loading={logoutAll.isPending}
                onClick={() =>
                  logoutAll.mutate(undefined, {
                    onSuccess: () => {
                      changeOpen(false)
                      navigate('/logowanie', { replace: true })
                    },
                    onError: () => toast.error('Nie udało się wylogować'),
                  })
                }
              >
                <LogOutIcon />
                Wyloguj wszystkie konta
              </Button>
              <Button variant="outline" onClick={() => setAdding(true)}>
                <UserPlusIcon />
                Dodaj konto
              </Button>
            </div>
          </>
        )}
      </DialogContent>
    </Dialog>
  )
}
