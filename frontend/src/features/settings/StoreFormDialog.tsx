import { useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { toast } from 'sonner'

import { Form, FormActions, SwitchField, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useSaveStore } from '@/features/settings/api'
import { applyServerErrors } from '@/lib/form-errors'
import type { Store } from '@/lib/types'

type StoreForm = {
  number: string
  name: string
  address: string
  active: boolean
}

type Props = {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Brak = nowy sklep. */
  store?: Store
}

export function StoreFormDialog({ open, onOpenChange, store }: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-md">
        {open && <StoreFormBody key={store?.id ?? 'new'} store={store} onDone={() => onOpenChange(false)} />}
      </DialogContent>
    </Dialog>
  )
}

function StoreFormBody({ store, onDone }: { store?: Store; onDone: () => void }) {
  const saveStore = useSaveStore()
  const [formError, setFormError] = useState<string | null>(null)
  const isNew = store === undefined

  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<StoreForm>({
    defaultValues: {
      number: store?.number ?? '',
      name: store?.name ?? '',
      address: store?.address ?? '',
      active: store?.active ?? true,
    },
  })

  const onSubmit = handleSubmit((values) => {
    setFormError(null)
    saveStore.mutate(
      { id: store?.id, request: { ...values, number: values.number.trim() } },
      {
        onSuccess: (saved) => {
          toast.success(isNew ? `Dodano sklep ${saved.number}` : 'Zapisano zmiany')
          onDone()
        },
        onError: (error) => setFormError(applyServerErrors(error, setError, ['number', 'name', 'address'])),
      },
    )
  })

  return (
    <Form onSubmit={onSubmit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">{isNew ? 'Nowy sklep' : `Sklep ${store.number}`}</DialogTitle>
        <DialogDescription>
          Numer musi się zgadzać z numerem punktu sprzedaży w raportach, inaczej import zostanie odrzucony.
        </DialogDescription>
      </DialogHeader>

      <TextField
        label="Numer punktu sprzedaży"
        inputMode="numeric"
        autoComplete="off"
        autoFocus={isNew}
        hint="Np. 10023 - jest w pierwszym wierszu raportu."
        error={errors.number?.message}
        {...register('number', {
          required: 'Podaj numer sklepu',
          pattern: { value: /^\s*\d{1,20}\s*$/, message: 'Numer sklepu to same cyfry, np. 10023' },
        })}
      />

      <TextField
        label="Nazwa"
        autoComplete="off"
        hint="Tak sklep będzie widoczny w przełączniku, np. Zielona Góra - Kisielińska."
        error={errors.name?.message}
        {...register('name', {
          required: 'Podaj nazwę sklepu',
          maxLength: { value: 100, message: 'Maksymalnie 100 znaków' },
        })}
      />

      <TextField
        label="Adres (opcjonalnie)"
        autoComplete="off"
        error={errors.address?.message}
        {...register('address', { maxLength: { value: 255, message: 'Maksymalnie 255 znaków' } })}
      />

      {!isNew && (
        <Controller
          control={control}
          name="active"
          render={({ field }) => (
            <SwitchField
              label="Sklep aktywny"
              description="Wyłączony sklep znika pracownikom z wyboru. Dane i historia importów zostają."
              checked={field.value}
              onCheckedChange={field.onChange}
            />
          )}
        />
      )}

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onDone}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={saveStore.isPending}>
          {isNew ? 'Dodaj sklep' : 'Zapisz zmiany'}
        </Button>
      </FormActions>
    </Form>
  )
}
