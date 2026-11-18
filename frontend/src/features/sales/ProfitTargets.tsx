import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { SettingsIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Form, FormActions, FormNote, SegmentedField, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCan } from '@/features/auth/api'
import { formatMoney } from '@/features/documents/format'
import { useProfitTargets, useSaveProfitTarget } from '@/features/sales/api'
import { ApiError } from '@/lib/api'
import { applyServerErrors } from '@/lib/form-errors'
import type { ProfitTarget } from '@/lib/types'
import { cn } from '@/lib/utils'
import { yearShare } from './year-share'
import { LoadingPanel } from '@/components/LoadingPanel'

const inputFormat = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 2, useGrouping: false })
const dateFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short' })
const percentFormat = new Intl.NumberFormat('pl-PL', { minimumFractionDigits: 1, maximumFractionDigits: 1 })

/** Kwota z pola: spacje, przecinek albo kropka, „zł”; puste = bez celu, `undefined` = błędna wartość. */
function parseMoney(text: string): number | null | undefined {
  const trimmed = text.replace(/zł/i, '').replace(/[\s ]/g, '').replace(',', '.')
  if (trimmed === '') return null
  if (!/^\d{1,12}(\.\d{1,2})?$/.test(trimmed)) return undefined
  const value = Number(trimmed)
  return value > 0 ? value : undefined
}

const moneyRule = (value: string) =>
  parseMoney(value) !== undefined || 'Kwota większa od zera, najwyżej dwa miejsca po przecinku'

/**
 * Koło zębate podsumowania sprzedaży i strat: roczny cel zysku sklepu (kwota marży i marży po stratach), który
 * kafelki raportu porównują z wynikiem od 1 stycznia do końca okresu. Bez prawa zmian okno tylko pokazuje cele.
 */
export function ProfitTargetSettings({ storeId, year }: { storeId: string; year: number }) {
  const canEdit = useCan('SALES', 'EDIT')
  const [open, setOpen] = useState(false)
  const [selectedYear, setSelectedYear] = useState(year)
  const targets = useProfitTargets(storeId, open)
  const years = [year - 1, year, year + 1]
  const target = targets.data?.find((item) => item.year === selectedYear) ?? null

  return (
    <>
      <Button
        variant="outline"
        size="icon-lg"
        aria-label="Ustawienia raportu: cel zysku na rok"
        title="Ustawienia raportu: cel zysku na rok"
        onClick={() => {
          setSelectedYear(year)
          setOpen(true)
        }}
      >
        <SettingsIcon />
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="gap-5 p-6 sm:max-w-lg">
          <DialogHeader>
            <DialogTitle className="text-lg">Cel zysku na rok</DialogTitle>
            <DialogDescription>
              Kwota, którą sklep chce zarobić w roku. Kafelki marży pokazują, ile z niej osiągnięto od 1 stycznia do
              końca wybranego okresu.
            </DialogDescription>
          </DialogHeader>
          <SegmentedField
            label="Rok"
            options={years.map((item) => ({ value: String(item), label: String(item) }))}
            value={String(selectedYear)}
            onChange={(next) => setSelectedYear(Number(next))}
          />
          {targets.isError ? (
            <FormNote>Nie udało się wczytać celów.</FormNote>
          ) : !targets.data ? (
            <LoadingPanel className="h-40 rounded-xl" />
          ) : canEdit ? (
            <TargetForm
              key={`${selectedYear}:${target?.createdAt ?? 'new'}`}
              storeId={storeId}
              year={selectedYear}
              target={target}
            />
          ) : (
            <FormNote>
              {target
                ? [
                    target.marginValue != null && `Marża: ${formatMoney(target.marginValue)} zł.`,
                    target.marginAfterLossesValue != null &&
                      `Marża po stratach: ${formatMoney(target.marginAfterLossesValue)} zł.`,
                  ]
                    .filter(Boolean)
                    .join(' ')
                : `Rok ${selectedYear} nie ma celu. Cel ustawia osoba z edycją sprzedaży.`}
            </FormNote>
          )}
        </DialogContent>
      </Dialog>
    </>
  )
}

type TargetValues = { marginValue: string; marginAfterLossesValue: string }

function TargetForm({ storeId, year, target }: { storeId: string; year: number; target: ProfitTarget | null }) {
  const save = useSaveProfitTarget(storeId)
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<TargetValues>({
    defaultValues: {
      marginValue: target?.marginValue == null ? '' : inputFormat.format(target.marginValue),
      marginAfterLossesValue:
        target?.marginAfterLossesValue == null ? '' : inputFormat.format(target.marginAfterLossesValue),
    },
  })

  const submit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      const marginValue = parseMoney(values.marginValue) ?? null
      const marginAfterLossesValue = parseMoney(values.marginAfterLossesValue) ?? null
      await save.mutateAsync({ year, marginValue, marginAfterLossesValue })
      toast.success(
        marginValue == null && marginAfterLossesValue == null ? `Usunięto cel na ${year}` : `Zapisano cel na ${year}`,
      )
    } catch (error) {
      setFormError(
        error instanceof ApiError
          ? applyServerErrors(error, setError, ['marginValue', 'marginAfterLossesValue'])
          : 'Nie udało się zapisać celu',
      )
    }
  })

  return (
    <Form onSubmit={submit} error={formError}>
      <TextField
        label={`Marża w ${year} (zł)`}
        inputMode="decimal"
        autoComplete="off"
        error={errors.marginValue?.message}
        hint="Zysk ze sprzedaży: sprzedaż netto minus zakup FIFO."
        {...register('marginValue', { validate: moneyRule })}
      />
      <TextField
        label={`Marża po stratach w ${year} (zł)`}
        inputMode="decimal"
        autoComplete="off"
        error={errors.marginAfterLossesValue?.message}
        hint="Marża minus likwidacje. Puste pole = bez celu."
        {...register('marginAfterLossesValue', { validate: moneyRule })}
      />
      {target && (
        <FormNote>
          Ustawione {dateFormat.format(new Date(target.createdAt))}
          {target.createdByName && ` przez ${target.createdByName}`}. Wyczyść oba pola, żeby usunąć cel.
        </FormNote>
      )}
      <FormActions>
        <Button type="submit" size="xl" loading={isSubmitting} disabled={!isDirty}>
          Zapisz
        </Button>
      </FormActions>
    </Form>
  )
}

/**
 * Postęp celu roku w kafelku: ile osiągnięto od 1 stycznia, pasek z kreską planu na koniec okresu (cel rozłożony
 * równo na dni roku), zielony gdy wynik jest przed planem, bursztynowy gdy za nim.
 */
export function GoalProgress({
  year,
  to,
  target,
  achieved,
  strong,
}: {
  year: number
  to: string
  target: number
  achieved: number
  strong?: boolean
}) {
  const share = achieved / target
  const planned = yearShare(to)
  const ahead = share >= planned
  const muted = strong ? 'text-background/70' : 'text-muted-foreground'
  const toDate = dateFormat.format(new Date(`${to}T00:00:00`))
  return (
    <div className={cn('mt-1 border-t pt-2 text-sm tabular-nums', strong && 'border-background/20')}>
      <div className="flex items-baseline justify-between gap-2">
        <span className={muted}>
          Cel {year}: {formatMoney(target)} zł
        </span>
        <span className="font-semibold">{percentFormat.format(share * 100)}%</span>
      </div>
      <div
        role="meter"
        aria-label={`Cel ${year}`}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(share * 1000) / 10}
        className={cn('relative mt-1.5 h-2 overflow-hidden rounded-full', strong ? 'bg-background/20' : 'bg-muted')}
      >
        <div
          className={cn('h-full rounded-full', ahead ? 'bg-emerald-500' : 'bg-amber-500')}
          style={{ width: `${Math.min(share, 1) * 100}%` }}
        />
        <div
          aria-hidden
          title={`Plan na ${toDate}`}
          className={cn('absolute inset-y-0 w-0.5', strong ? 'bg-background' : 'bg-foreground')}
          style={{ left: `${Math.min(planned, 1) * 100}%` }}
        />
      </div>
      <p className={cn('mt-1.5', muted)}>
        do {toDate}: {formatMoney(achieved)} zł · plan {percentFormat.format(planned * 100)}%
      </p>
    </div>
  )
}
