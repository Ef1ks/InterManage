import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'

import { Form, FormActions, SwitchField, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { usePinSettings, useSetPin, useSetPinRequired } from '@/features/auth/api'
import { applyServerErrors } from '@/lib/form-errors'

type PinForm = { currentPassword: string; pin: string; confirmPin: string }

/** PIN, którym inni przechodzą na to konto w „Zmień konto”, i możliwość przełączania bez PIN-u. */
export function PinSettings() {
  const settings = usePinSettings()
  const setPin = useSetPin()
  const setRequired = useSetPinRequired()
  const [formError, setFormError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    setError,
    reset,
    getValues,
    formState: { errors },
  } = useForm<PinForm>({ defaultValues: { currentPassword: '', pin: '', confirmPin: '' } })

  const hasPin = settings.data?.hasPin ?? false
  const pinRequired = settings.data?.pinRequired ?? true

  const onSubmit = handleSubmit(({ currentPassword, pin }) => {
    setFormError(null)
    setPin.mutate(
      { currentPassword, pin },
      {
        onSuccess: () => {
          reset()
          toast.success(hasPin ? 'PIN został zmieniony' : 'PIN został ustawiony')
        },
        onError: (error) => setFormError(applyServerErrors(error, setError, ['currentPassword', 'pin'])),
      },
    )
  })

  const changeRequired = (required: boolean) =>
    setRequired.mutate(required, {
      onSuccess: () =>
        toast.success(required ? 'Przejście na konto wymaga PIN-u' : 'Na konto można przełączyć się bez PIN-u'),
      onError: () => toast.error('Nie udało się zapisać'),
    })

  return (
    <Card>
      <CardContent>
        <h2 className="text-base font-semibold">PIN do przełączania kont</h2>
        <p className="mt-1 mb-5 text-sm text-muted-foreground">
          Gdy Twoje konto zostaje zalogowane w tle, wrócisz na nie przez „Zmień konto” w prawym górnym rogu.{' '}
          {hasPin ? 'PIN jest ustawiony.' : 'Bez PIN-u przejście wymaga hasła.'}
        </p>
        <div className="grid gap-4">
          <SwitchField
            label="Wymagaj PIN-u przy przejściu na to konto"
            description={
              pinRequired
                ? 'Każdy, kto chce przejść na Twoje konto, musi podać PIN (bez PIN-u: hasło).'
                : 'Uwaga: każdy przy tym komputerze przejdzie na Twoje konto jednym kliknięciem.'
            }
            checked={pinRequired}
            onCheckedChange={changeRequired}
            disabled={settings.isPending || setRequired.isPending}
          />
          <Form onSubmit={onSubmit} error={formError}>
            <TextField
              label="Obecne hasło"
              type="password"
              autoComplete="current-password"
              error={errors.currentPassword?.message}
              {...register('currentPassword', { required: 'Podaj obecne hasło' })}
            />
            <TextField
              label={hasPin ? 'Nowy PIN' : 'PIN'}
              type="password"
              inputMode="numeric"
              autoComplete="off"
              maxLength={8}
              hint="Od 4 do 8 cyfr. Po 5 błędnych próbach przejście wymaga hasła."
              error={errors.pin?.message}
              {...register('pin', {
                required: 'Podaj PIN',
                pattern: { value: /^\d{4,8}$/, message: 'PIN to od 4 do 8 cyfr' },
              })}
            />
            <TextField
              label="Powtórz PIN"
              type="password"
              inputMode="numeric"
              autoComplete="off"
              maxLength={8}
              error={errors.confirmPin?.message}
              {...register('confirmPin', {
                validate: (value) => value === getValues('pin') || 'PIN-y nie są takie same',
              })}
            />
            <FormActions>
              <Button type="submit" size="lg" loading={setPin.isPending}>
                {hasPin ? 'Zmień PIN' : 'Ustaw PIN'}
              </Button>
            </FormActions>
          </Form>
        </div>
      </CardContent>
    </Card>
  )
}
