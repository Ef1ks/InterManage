import { useState } from 'react'
import { Controller, useForm, useWatch } from 'react-hook-form'
import { toast } from 'sonner'

import { DateField, Form, FormActions, FormNote, SegmentedField, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { documentPdfPath, useCreateCashDocument } from '@/features/cash/api'
import { KIND_LABELS, OPERATIONS, parseAmount } from '@/features/cash/format'
import { printFile } from '@/lib/api'
import { todayIso } from '@/lib/dates'
import { applyServerErrors } from '@/lib/form-errors'
import type { CashDocumentKind } from '@/lib/types'
import { cn } from '@/lib/utils'

type Values = {
  kind: CashDocumentKind
  issueDate: string
  amount: string
  operation: string
  title: string
  counterparty: string
  counterpartyAddress: string
}

/**
 * Nowy dokument KP albo KW, jak w dotychczasowym arkuszu: data, kwota, „Za co” (operacja z listy i opis). Kontrahent
 * i adres są na dole i nie są wymagane. Wydruk jest przy dokumencie w raporcie i historii.
 */
export function CashDocumentDialog({
  storeId,
  kind,
  minDate,
  onClose,
}: {
  storeId: string
  /** `null` = okno zamknięte. */
  kind: CashDocumentKind | null
  /** Najwcześniejsza dozwolona data: po zakończonym raporcie i nie przed stanem początkowym. */
  minDate?: string
  onClose: () => void
}) {
  return (
    <Dialog open={kind !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="gap-5 p-6 sm:max-w-lg">
        {kind && <DocumentForm key={kind} storeId={storeId} kind={kind} minDate={minDate} onDone={onClose} />}
      </DialogContent>
    </Dialog>
  )
}

function DocumentForm({
  storeId,
  kind: initialKind,
  minDate,
  onDone,
}: {
  storeId: string
  kind: CashDocumentKind
  minDate?: string
  onDone: () => void
}) {
  const create = useCreateCashDocument(storeId)
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    control,
    handleSubmit,
    setError,
    setValue,
    formState: { errors, isSubmitting },
  } = useForm<Values>({
    defaultValues: {
      kind: initialKind,
      issueDate: todayIso(),
      amount: '',
      operation: OPERATIONS[initialKind][0],
      title: '',
      counterparty: '',
      counterpartyAddress: '',
    },
  })
  const kind = useWatch({ control, name: 'kind' })
  const operation = useWatch({ control, name: 'operation' })
  const receipt = kind === 'KP'

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      const document = await create.mutateAsync({
        kind: values.kind,
        issueDate: values.issueDate,
        amount: parseAmount(values.amount) ?? 0,
        operation: values.operation.trim(),
        title: values.title.trim() || null,
        counterparty: values.counterparty.trim() || null,
        counterpartyAddress: values.counterpartyAddress.trim() || null,
      })
      onDone()
      const print = () =>
        printFile(documentPdfPath(storeId, document.id)).catch(() => {
          toast.error('Nie udało się otworzyć wydruku')
        })
      toast.success(`Wystawiono ${document.number}`, { action: { label: 'Drukuj', onClick: print } })
    } catch (error) {
      setFormError(
        applyServerErrors(error, setError, [
          'kind',
          'issueDate',
          'amount',
          'operation',
          'title',
          'counterparty',
          'counterpartyAddress',
        ]),
      )
    }
  })

  return (
    <Form onSubmit={onSubmit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">
          {KIND_LABELS[kind]} {kind}
        </DialogTitle>
        <DialogDescription>
          {receipt ? 'Wpływ gotówki do kasy.' : 'Wypłata gotówki z kasy.'} Numer nada program, a wydruk będzie zawsze
          dostępny przy dokumencie.
        </DialogDescription>
      </DialogHeader>

      <Controller
        control={control}
        name="kind"
        render={({ field }) => (
          <SegmentedField
            label="Rodzaj dokumentu"
            value={field.value}
            onChange={(next) => {
              field.onChange(next)
              // Operacja z listy drugiego rodzaju nie pasuje (utarg to zawsze wpłata).
              if (OPERATIONS[field.value].includes(operation)) setValue('operation', OPERATIONS[next][0])
            }}
            options={[
              { value: 'KP', label: 'KP · wpłata' },
              { value: 'KW', label: 'KW · wypłata' },
            ]}
          />
        )}
      />

      <div className="grid gap-4 sm:grid-cols-2">
        <Controller
          control={control}
          name="issueDate"
          rules={{ required: 'Podaj datę' }}
          render={({ field }) => (
            <DateField
              label="Dnia"
              value={field.value}
              onChange={field.onChange}
              min={minDate}
              max={todayIso()}
              shortcuts={[]}
              error={errors.issueDate?.message}
            />
          )}
        />
        <TextField
          label="Kwota zł"
          inputMode="decimal"
          autoComplete="off"
          autoFocus
          error={errors.amount?.message}
          {...register('amount', {
            validate: (value) => {
              const amount = parseAmount(value)
              if (amount === null) return 'Podaj kwotę, np. 120,50'
              return amount > 0 || 'Kwota musi być większa od zera'
            },
          })}
        />
      </div>

      <div className="grid gap-2">
        <span className="text-sm font-medium">Za co</span>
        <div className="flex flex-wrap gap-1.5" role="group" aria-label="Operacja z listy">
          {OPERATIONS[kind].map((preset) => (
            <button
              key={preset}
              type="button"
              aria-pressed={operation === preset}
              onClick={() => setValue('operation', preset, { shouldValidate: true })}
              className={cn(
                'rounded-lg border px-3 py-1.5 text-sm outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/40',
                operation === preset && 'border-foreground bg-foreground text-background hover:bg-foreground/90',
              )}
            >
              {preset}
            </button>
          ))}
        </div>
        <TextField
          label="Operacja (wybierz wyżej albo wpisz)"
          autoComplete="off"
          error={errors.operation?.message}
          {...register('operation', {
            validate: (value) => value.trim() !== '' || 'Wybierz albo wpisz operację',
            maxLength: { value: 100, message: 'Najwyżej 100 znaków' },
          })}
        />
        <TextField
          label="Opis (np. ZDRAPKOMAT, STACJA PALIW 1 ZMIANA)"
          autoComplete="off"
          error={errors.title?.message}
          {...register('title', { maxLength: { value: 500, message: 'Najwyżej 500 znaków' } })}
        />
      </div>

      <details className="group rounded-xl border px-3 py-2">
        <summary className="cursor-pointer text-sm text-muted-foreground select-none">
          {receipt ? 'Wpłacający' : 'Odbierający'} i adres (opcjonalnie)
        </summary>
        <div className="mt-3 grid gap-3">
          <TextField
            label={receipt ? 'Od kogo' : 'Komu'}
            autoComplete="off"
            error={errors.counterparty?.message}
            {...register('counterparty', { maxLength: { value: 200, message: 'Najwyżej 200 znaków' } })}
          />
          <TextField
            label="Adres"
            autoComplete="off"
            error={errors.counterpartyAddress?.message}
            {...register('counterpartyAddress', { maxLength: { value: 300, message: 'Najwyżej 300 znaków' } })}
          />
        </div>
      </details>

      <FormNote>
        Wystawionego dokumentu nie da się zmienić. Pomyłkę anuluje się (numer zostaje w historii) i wystawia nowy
        dokument. O 23:00 raport dnia kończy się sam i dokumentów z tego dnia nie da się już dodać ani anulować.
      </FormNote>

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onDone}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={isSubmitting}>
          Wystaw dokument
        </Button>
      </FormActions>
    </Form>
  )
}
