import { useState } from 'react'
import { MailIcon } from 'lucide-react'
import { Controller, useForm } from 'react-hook-form'
import { toast } from 'sonner'

import { Form, FormActions, FormNote, SelectField, SwitchField, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCreateUser, useRoles, useUpdateUser } from '@/features/settings/api'
import { applyServerErrors } from '@/lib/form-errors'
import type { Role, User } from '@/lib/types'

type UserForm = {
  username: string
  displayName: string
  email: string
  roleId: string
  /** Tylko przy edycji: hasło tymczasowe, puste = bez zmiany. */
  password: string
  enabled: boolean
}

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

type Props = {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Brak = nowy użytkownik (zaproszenie). */
  user?: User
}

export function UserFormDialog({ open, onOpenChange, user }: Props) {
  const { data: roles } = useRoles()

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-md">
        {open && roles && (
          <UserFormBody key={user?.id ?? 'new'} user={user} roles={roles} onDone={() => onOpenChange(false)} />
        )}
      </DialogContent>
    </Dialog>
  )
}

function UserFormBody({ user, roles, onDone }: { user?: User; roles: Role[]; onDone: () => void }) {
  const createUser = useCreateUser()
  const updateUser = useUpdateUser()
  const [formError, setFormError] = useState<string | null>(null)
  const isNew = user === undefined
  const isPending = createUser.isPending || updateUser.isPending

  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<UserForm>({
    defaultValues: {
      username: user?.username ?? '',
      displayName: user?.displayName ?? '',
      email: user?.email ?? '',
      roleId: user ? String(user.roleId) : '',
      password: '',
      enabled: user?.enabled ?? true,
    },
  })

  const onError = (error: unknown) =>
    setFormError(
      applyServerErrors(error, setError, ['username', 'displayName', 'email', 'roleId', 'password'], {
        newPassword: 'password',
      }),
    )

  const onSubmit = handleSubmit((values) => {
    setFormError(null)
    const roleId = Number(values.roleId)
    if (isNew) {
      createUser.mutate(
        { username: values.username, displayName: values.displayName, email: values.email, roleId },
        {
          onSuccess: (created) => {
            toast.success('Zaproszenie wysłane', {
              description: `${created.displayName} dostanie link aktywacyjny na ${created.email}.`,
            })
            onDone()
          },
          onError,
        },
      )
    } else {
      updateUser.mutate(
        {
          id: user.id,
          request: {
            displayName: values.displayName,
            email: values.email || null,
            roleId,
            enabled: values.enabled,
            newPassword: values.password || undefined,
          },
        },
        {
          onSuccess: () => {
            toast.success('Zapisano zmiany')
            onDone()
          },
          onError,
        },
      )
    }
  })

  const roleOptions = roles.map((role) => ({ value: String(role.id), label: role.name }))

  return (
    <Form onSubmit={onSubmit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">{isNew ? 'Zaproś pracownika' : user.displayName}</DialogTitle>
        <DialogDescription>
          {isNew ? 'Pracownik dostanie e-mail z linkiem do aktywacji konta.' : `Login: ${user.username}`}
        </DialogDescription>
      </DialogHeader>

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

      {isNew && (
        <TextField
          label="Login"
          autoComplete="off"
          autoCapitalize="none"
          spellCheck={false}
          hint="Litery, cyfry, kropka, myślnik lub podkreślnik."
          error={errors.username?.message}
          {...register('username', {
            required: 'Podaj login',
            pattern: {
              value: /^[A-Za-z0-9._-]{3,50}$/,
              message: 'Login: 3-50 znaków, litery, cyfry, kropka, myślnik lub podkreślnik',
            },
          })}
        />
      )}

      <TextField
        label="Adres e-mail"
        type="email"
        autoComplete="off"
        autoCapitalize="none"
        spellCheck={false}
        error={errors.email?.message}
        {...register('email', {
          required: isNew || user.pendingActivation ? 'Podaj adres e-mail' : false,
          pattern: { value: EMAIL_PATTERN, message: 'Podaj poprawny adres e-mail' },
          maxLength: { value: 255, message: 'Maksymalnie 255 znaków' },
        })}
      />

      <Controller
        control={control}
        name="roleId"
        rules={{ required: 'Wybierz rolę' }}
        render={({ field }) => (
          <SelectField
            label="Rola"
            options={roleOptions}
            value={field.value}
            onChange={field.onChange}
            onBlur={field.onBlur}
            error={errors.roleId?.message}
          />
        )}
      />

      {isNew ? (
        <FormNote icon={<MailIcon />}>
          Po kliknięciu w link pracownik ustawi własne hasło. Link jest ważny 3 dni - w razie potrzeby wyślesz nowy z
          listy użytkowników.
        </FormNote>
      ) : (
        <>
          {!user.pendingActivation && (
            <TextField
              label="Hasło tymczasowe (opcjonalnie)"
              type="password"
              autoComplete="new-password"
              hint="Zostaw puste, aby nie zmieniać. Użytkownik zostanie wylogowany i po zalogowaniu ustawi własne hasło."
              error={errors.password?.message}
              {...register('password', {
                validate: (value) =>
                  value === '' || (value.length >= 8 && value.length <= 72) || 'Hasło musi mieć od 8 do 72 znaków',
              })}
            />
          )}
          <Controller
            control={control}
            name="enabled"
            render={({ field }) => (
              <SwitchField
                label="Konto aktywne"
                description="Zablokowany użytkownik zostanie od razu wylogowany."
                checked={field.value}
                onCheckedChange={field.onChange}
              />
            )}
          />
        </>
      )}

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onDone}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={isPending}>
          {isNew ? 'Wyślij zaproszenie' : 'Zapisz zmiany'}
        </Button>
      </FormActions>
    </Form>
  )
}
