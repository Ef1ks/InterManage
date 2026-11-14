import { useRef, useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { ImageUpIcon, StampIcon, Trash2Icon } from 'lucide-react'
import { toast } from 'sonner'

import { DateField, Form, FormActions, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { stampUrl, useRemoveStamp, useRestoreStamp, useSetOpeningBalance, useUploadStamp } from '@/features/cash/api'
import { formatDateTime, formatMoney, parseAmount } from '@/features/cash/format'
import { ApiError } from '@/lib/api'
import { formatShortDate, todayIso } from '@/lib/dates'
import { applyServerErrors } from '@/lib/form-errors'
import type { CashSettings } from '@/lib/types'

/** Ustawienia kasy sklepu: pieczątka do wydruków i stan kasy na początek raportu. */
export function CashSettingsDialog({
  storeId,
  settings,
  open,
  onClose,
}: {
  storeId: string
  settings: CashSettings | undefined
  open: boolean
  onClose: () => void
}) {
  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent className="gap-6 p-6 sm:max-w-lg">
        <DialogHeader>
          <DialogTitle className="text-lg">Ustawienia kasy</DialogTitle>
          <DialogDescription>Pieczątka drukuje się na każdym KP, KW i raporcie kasowym.</DialogDescription>
        </DialogHeader>
        <StampSection storeId={storeId} stamp={settings?.stamp ?? null} />
        <OpeningBalanceSection
          key={settings?.openingBalance?.createdAt ?? 'none'}
          storeId={storeId}
          opening={settings?.openingBalance ?? null}
        />
      </DialogContent>
    </Dialog>
  )
}

/** Skan pieczątki (PNG albo JPG). Wymiana i usunięcie dają toast „Przywróć”. */
export function StampSection({ storeId, stamp }: { storeId: string; stamp: CashSettings['stamp'] }) {
  const input = useRef<HTMLInputElement>(null)
  const upload = useUploadStamp(storeId)
  const remove = useRemoveStamp(storeId)
  const restore = useRestoreStamp(storeId)

  const restoreAction = (stampId: string) => ({
    label: 'Przywróć',
    onClick: () =>
      restore.mutate(stampId, {
        onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się przywrócić'),
      }),
  })

  const onFile = (file: File | undefined) => {
    if (!file) return
    const previous = stamp?.id
    upload.mutate(file, {
      onSuccess: () =>
        toast.success(previous ? 'Wymieniono pieczątkę' : 'Wgrano pieczątkę', {
          action: previous ? restoreAction(previous) : undefined,
        }),
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się wgrać pieczątki'),
    })
  }

  const onRemove = () => {
    if (!stamp) return
    remove.mutate(undefined, {
      onSuccess: () => toast.success('Usunięto pieczątkę', { action: restoreAction(stamp.id) }),
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się usunąć pieczątki'),
    })
  }

  return (
    <section className="space-y-3">
      <h3 className="text-sm font-semibold">Pieczątka sklepu</h3>
      <div className="flex flex-wrap items-center gap-4">
        <div className="flex h-24 w-56 items-center justify-center rounded-lg border border-dashed bg-white p-2">
          {stamp ? (
            <img
              src={stampUrl(storeId, stamp.id)}
              alt="Pieczątka sklepu"
              className="max-h-full max-w-full object-contain"
            />
          ) : (
            <StampIcon className="size-8 text-muted-foreground/60" aria-hidden />
          )}
        </div>
        <div className="grid gap-2">
          <Button variant="outline" onClick={() => input.current?.click()} loading={upload.isPending}>
            <ImageUpIcon />
            {stamp ? 'Wymień skan' : 'Wgraj skan'}
          </Button>
          {stamp && (
            <Button variant="ghost" onClick={onRemove} loading={remove.isPending}>
              <Trash2Icon />
              Usuń
            </Button>
          )}
        </div>
      </div>
      <p className="text-xs text-muted-foreground">
        {stamp
          ? `${stamp.fileName} · wgrał ${stamp.createdBy ?? 'nieznany'} ${formatDateTime(stamp.createdAt)}. `
          : 'Bez pieczątki nie da się wystawić dokumentu. '}
        PNG albo JPG, najlepiej skan na białym tle. Wystawione dokumenty drukują się dalej ze swoją pieczątką.
      </p>
      <input
        ref={input}
        type="file"
        accept="image/png,image/jpeg"
        className="sr-only"
        tabIndex={-1}
        onChange={(event) => {
          onFile(event.target.files?.[0])
          event.target.value = ''
        }}
      />
    </section>
  )
}

function OpeningBalanceSection({ storeId, opening }: { storeId: string; opening: CashSettings['openingBalance'] }) {
  const save = useSetOpeningBalance(storeId)
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<{ date: string; amount: string }>({
    defaultValues: {
      date: opening?.date ?? todayIso(),
      amount: opening ? String(opening.amount).replace('.', ',') : '',
    },
  })

  const onSubmit = handleSubmit(async ({ date, amount }) => {
    setFormError(null)
    try {
      await save.mutateAsync({ date, amount: parseAmount(amount) ?? 0 })
      toast.success('Zapisano stan początkowy kasy')
    } catch (error) {
      setFormError(applyServerErrors(error, setError, ['date', 'amount']))
    }
  })

  return (
    <section className="space-y-3 border-t pt-5">
      <div>
        <h3 className="text-sm font-semibold">Stan kasy na początek</h3>
        <p className="text-xs text-muted-foreground">
          {opening
            ? `Obecnie ${formatMoney(opening.amount)} na ${formatShortDate(opening.date)} (${opening.createdBy ?? 'nieznany'}).`
            : 'Gotówka w kasie w dniu, od którego prowadzisz raport w programie. Bez niego raport liczy od zera.'}
        </p>
      </div>
      <Form onSubmit={onSubmit} error={formError} className="gap-3">
        <div className="grid gap-3 sm:grid-cols-2">
          <Controller
            control={control}
            name="date"
            rules={{ required: 'Podaj datę' }}
            render={({ field }) => (
              <DateField
                label="Na początek dnia"
                value={field.value}
                onChange={field.onChange}
                max={todayIso()}
                shortcuts={[]}
                error={errors.date?.message}
              />
            )}
          />
          <TextField
            label="Stan kasy zł"
            inputMode="decimal"
            autoComplete="off"
            error={errors.amount?.message}
            {...register('amount', {
              validate: (value) => parseAmount(value) !== null || 'Podaj kwotę, np. 500,00',
            })}
          />
        </div>
        <FormActions className="pt-0">
          <Button type="submit" variant="outline" loading={isSubmitting} disabled={!isDirty}>
            Zapisz stan początkowy
          </Button>
        </FormActions>
      </Form>
    </section>
  )
}
