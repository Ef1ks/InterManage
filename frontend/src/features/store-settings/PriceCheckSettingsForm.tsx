import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'

import { Form, FormActions, FormNote, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { usePriceCheckSettings, useSavePriceCheckSettings } from '@/features/sales/api'
import { ApiError } from '@/lib/api'
import { applyServerErrors } from '@/lib/form-errors'
import type { PriceCheckSettings } from '@/lib/types'

const numberFormat = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 2, useGrouping: false })
const dateFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short' })

/** Tekst z pola na liczbę: przecinek albo kropka, puste = bez minimum; {@code undefined} = błędna wartość. */
function parse(text: string): number | null | undefined {
  const trimmed = text.trim().replace(',', '.').replace('%', '')
  if (trimmed === '') return null
  if (!/^-?\d{1,3}(\.\d{1,2})?$/.test(trimmed)) return undefined
  const value = Number(trimmed)
  return value < -100 || value > 100 ? undefined : value
}

/** Minimalna akceptowana marża (% od sprzedaży netto) do raportu zakupu bazowego poza bazą. */
export function PriceCheckSettingsForm({ storeId }: { storeId: string }) {
  const { data, isError, refetch } = usePriceCheckSettings(storeId)

  if (isError) {
    return (
      <Card className="items-center px-6 py-8 text-center">
        <p className="font-medium">Nie udało się wczytać ustawień</p>
        <Button variant="outline" onClick={() => refetch()}>
          Spróbuj ponownie
        </Button>
      </Card>
    )
  }
  if (!data) {
    return <div aria-hidden className="h-40 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
  }
  return <SettingsForm key={data.updatedAt ?? 'new'} storeId={storeId} settings={data} />
}

function SettingsForm({ storeId, settings }: { storeId: string; settings: PriceCheckSettings }) {
  const save = useSavePriceCheckSettings(storeId)
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<{ minMarginPercent: string }>({
    defaultValues: {
      minMarginPercent: settings.minMarginPercent == null ? '' : numberFormat.format(settings.minMarginPercent),
    },
  })

  const submit = handleSubmit(async ({ minMarginPercent }) => {
    setFormError(null)
    try {
      await save.mutateAsync(parse(minMarginPercent) ?? null)
      toast.success('Zapisano minimalną marżę')
    } catch (error) {
      if (error instanceof ApiError) {
        setFormError(applyServerErrors(error, setError, ['minMarginPercent']))
      } else {
        setFormError('Nie udało się zapisać ustawień')
      }
    }
  })

  return (
    <Card className="p-4">
      <Form onSubmit={submit} error={formError}>
        <TextField
          label="Minimalna marża %"
          inputMode="decimal"
          autoComplete="off"
          error={errors.minMarginPercent?.message}
          hint="Od sprzedaży netto. Linie poniżej minimum są w raporcie podświetlone; puste pole wyłącza ocenę."
          {...register('minMarginPercent', {
            validate: (value) =>
              parse(value) !== undefined || 'Liczba od -100 do 100, najwyżej dwa miejsca po przecinku',
          })}
        />
        {settings.updatedAt && (
          <FormNote>
            Zmienione {dateFormat.format(new Date(settings.updatedAt))}
            {settings.updatedBy && ` przez ${settings.updatedBy}`}.
          </FormNote>
        )}
        <FormActions>
          <Button type="submit" size="xl" loading={isSubmitting} disabled={!isDirty}>
            Zapisz
          </Button>
        </FormActions>
      </Form>
    </Card>
  )
}
