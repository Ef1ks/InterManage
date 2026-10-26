import { useState } from 'react'
import { MailIcon, StoreIcon } from 'lucide-react'
import { Controller, useForm, useWatch } from 'react-hook-form'
import { toast } from 'sonner'

import { Form, FormActions, FormNote, MultiComboboxField, SelectField, SwitchField, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCreateUser, useRoles, useStores, useUpdateUser } from '@/features/settings/api'
import { storeLabel } from '@/features/stores/current-store'
import { applyServerErrors } from '@/lib/form-errors'
import type { Role, Store, User } from '@/lib/types'

type UserForm = {
  username: string
  displayName: string
  email: string
  roleId: string
  /** Tylko przy edycji: hasło tymczasowe, puste = bez zmiany. */
  password: string
  enabled: boolean
  storeIds: string[]
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
  const { data: stores } = useStores()

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-md">
        {open && roles && stores && (
          <UserFormBody
            key={user?.id ?? 'new'}
            user={user}
            roles={roles}
            stores={stores}
            onDone={() => onOpenChange(false)}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

function UserFormBody({
  user,
  roles,
  stores,
  onDone,
}: {
  user?: User
  roles: Role[]
  stores: Store[]
  onDone: () => void
}) {
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
      roleId: user?.roleId ?? '',
      password: '',
      enabled: user?.enabled ?? true,
      storeIds: user?.storeIds ?? [],
    },
  })
  const selectedRoleId = useWatch({ control, name: 'roleId' })
  const ownerRoleSelected = roles.some((role) => role.id === selectedRoleId && role.owner)

  const onError = (error: unknown) =>
    setFormError(
      applyServerErrors(error, setError, ['username', 'displayName', 'email', 'roleId', 'password'], {
        newPassword: 'password',
      }),
    )

  const onSubmit = handleSubmit((values) => {
    setFormError(null)
    const roleId = values.roleId
    const storeIds = values.storeIds
    if (isNew) {
      createUser.mutate(
        { username: values.username, displayName: values.displayName, email: values.email, roleId, storeIds },
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
            storeIds,
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

  const roleOptions = roles.map((role) => ({ value: role.id, label: role.name }))
  const storeOptions = stores.map((store) => ({
    value: store.id,
    label: storeLabel(store),
    hint: store.active ? undefined : 'Wyłączony - pracownik go nie zobaczy',
  }))

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

      {ownerRoleSelected ? (
        <FormNote icon={<StoreIcon />}>Właściciel ma dostęp do wszystkich sklepów.</FormNote>
      ) : storeOptions.length === 0 ? (
        <FormNote icon={<StoreIcon />}>
          Nie ma jeszcze żadnego sklepu, a bez sklepu pracownik nie zaloguje się. Dodaj sklep w Ustawieniach → Sklepy.
        </FormNote>
      ) : (
        <Controller
          control={control}
          name="storeIds"
          render={({ field }) => (
            <MultiComboboxField
              label="Sklepy"
              description={
                field.value.length === 0 ? (
                  <span className="text-amber-700 dark:text-amber-400">
                    Bez sklepu pracownik nie zaloguje się do aplikacji.
                  </span>
                ) : (
                  'Pracownik zobaczy dane tylko z wybranych sklepów.'
                )
              }
              options={storeOptions}
              value={field.value}
              onChange={field.onChange}
              placeholder="Wpisz numer albo nazwę sklepu"
              emptyText="Nie ma sklepu o takim numerze ani nazwie"
            />
          )}
        />
      )}

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
