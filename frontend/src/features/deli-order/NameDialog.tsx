import { useState } from 'react'
import { useForm } from 'react-hook-form'

import { Form, FormActions, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { applyServerErrors } from '@/lib/form-errors'

export type NameDialogState = {
  title: string
  description?: string
  label: string
  initialName?: string
  submitLabel: string
  /** Zapis nazwy; odrzucona obietnica z ApiError pokazuje błąd w formularzu. */
  onSubmit: (name: string) => Promise<unknown>
}

/** Nazwa działu albo kategorii formularza: nowa albo zmieniana. `null` = okno zamknięte. */
export function NameDialog({ state, onClose }: { state: NameDialogState | null; onClose: () => void }) {
  return (
    <Dialog open={state !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="gap-5 p-6 sm:max-w-sm">
        {state && <NameForm key={state.title + (state.initialName ?? '')} state={state} onDone={onClose} />}
      </DialogContent>
    </Dialog>
  )
}

function NameForm({ state, onDone }: { state: NameDialogState; onDone: () => void }) {
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<{ name: string }>({ defaultValues: { name: state.initialName ?? '' } })

  const onSubmit = handleSubmit(async ({ name }) => {
    setFormError(null)
    try {
      await state.onSubmit(name.trim())
      onDone()
    } catch (error) {
      setFormError(applyServerErrors(error, setError, ['name']))
    }
  })

  return (
    <Form onSubmit={onSubmit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">{state.title}</DialogTitle>
        {state.description && <DialogDescription>{state.description}</DialogDescription>}
      </DialogHeader>
      <TextField
        label={state.label}
        autoComplete="off"
        autoFocus
        error={errors.name?.message}
        {...register('name', {
          validate: (value) => value.trim() !== '' || 'Podaj nazwę',
          maxLength: { value: 100, message: 'Maksymalnie 100 znaków' },
        })}
      />
      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onDone}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={isSubmitting}>
          {state.submitLabel}
        </Button>
      </FormActions>
    </Form>
  )
}
