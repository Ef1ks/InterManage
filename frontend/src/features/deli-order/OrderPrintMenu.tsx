import { PrinterIcon } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { type OrderPrintVariant, orderPrintPath, useOrderPrintSections } from '@/features/deli-order/api'
import { ApiError, printFile } from '@/lib/api'

const VARIANTS: { variant: OrderPrintVariant; label: string; hint: string; form: boolean }[] = [
  { variant: 'ORDER', label: 'Zamówienie', hint: 'Zamówione pozycje z ilościami', form: false },
  { variant: 'ORDER_BLANK', label: 'Zamówienie in blanco', hint: 'Zamówione pozycje, bez ilości', form: false },
  {
    variant: 'FORM_BLANK',
    label: 'Cały formularz in blanco',
    hint: 'Wszystkie pozycje formularza, bez ilości',
    form: true,
  },
  {
    variant: 'FORM_WITH_QUANTITIES',
    label: 'Cały formularz z ilościami',
    hint: 'Wszystkie pozycje, zamówione ilości wpisane, reszta do dopisania',
    form: true,
  },
]

type Props = { storeId: string; orderId: string }

/**
 * Wydruk zamówienia w wybranym wariancie i z wybranych działów, bo kartki działów trafiają do różnych osób, a pracownicy
 * dopisują na nich długopisem towar ponad zamówienie.
 */
export function OrderPrintMenu({ storeId, orderId }: Props) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button variant="outline" size="xl" onClick={() => setOpen(true)}>
        <PrinterIcon />
        Drukuj
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="gap-5 p-6 sm:max-w-lg">
          {open && <PrintForm storeId={storeId} orderId={orderId} onDone={() => setOpen(false)} />}
        </DialogContent>
      </Dialog>
    </>
  )
}

function PrintForm({ storeId, orderId, onDone }: Props & { onDone: () => void }) {
  const [variant, setVariant] = useState<OrderPrintVariant>('ORDER')
  // Odznaczone, nie zaznaczone: wybór zostaje przy zmianie wariantu, a nowa lista działów startuje z pełną.
  const [skipped, setSkipped] = useState<string[]>([])
  const [printing, setPrinting] = useState(false)
  const sections = useOrderPrintSections(storeId, orderId, true)

  const wholeForm = VARIANTS.find((item) => item.variant === variant)?.form ?? false
  const available = (wholeForm ? sections.data?.form : sections.data?.order) ?? []
  const chosen = available.filter((section) => !skipped.includes(section))
  const noForm = sections.data?.form.length === 0

  const toggle = (section: string) =>
    setSkipped((current) =>
      current.includes(section) ? current.filter((item) => item !== section) : [...current, section],
    )

  const print = async () => {
    setPrinting(true)
    try {
      await printFile(orderPrintPath(storeId, orderId, variant, chosen.length === available.length ? [] : chosen))
      onDone()
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się przygotować wydruku')
    } finally {
      setPrinting(false)
    }
  }

  return (
    <>
      <DialogHeader>
        <DialogTitle>Drukuj zamówienie</DialogTitle>
        <DialogDescription>Każdy dział drukuje się na osobnej kartce.</DialogDescription>
      </DialogHeader>

      <RadioGroup
        value={variant}
        onValueChange={(value) => setVariant(value as OrderPrintVariant)}
        aria-label="Wariant wydruku"
        className="gap-1"
      >
        {VARIANTS.map(({ variant: value, label, hint, form }) => (
          <label
            key={value}
            className="flex cursor-pointer items-start gap-3 rounded-lg px-2 py-1.5 hover:bg-muted has-disabled:cursor-not-allowed has-disabled:opacity-50"
          >
            <RadioGroupItem value={value} disabled={form && noForm} className="mt-0.5" />
            <span className="grid text-sm">
              <span className="font-medium">{label}</span>
              <span className="text-xs text-muted-foreground">
                {form && noForm ? 'Nie masz już dostępu do formularza dostawcy' : hint}
              </span>
            </span>
          </label>
        ))}
      </RadioGroup>

      <fieldset className="grid gap-1">
        <legend className="mb-1 flex w-full items-center justify-between gap-2 text-sm font-medium">
          Działy
          {available.length > 1 && (
            <span className="flex gap-1">
              <Button variant="ghost" size="sm" onClick={() => setSkipped([])}>
                Wszystkie
              </Button>
              <Button variant="ghost" size="sm" onClick={() => setSkipped(available)}>
                Żaden
              </Button>
            </span>
          )}
        </legend>
        {sections.isError ? (
          <p className="text-sm text-destructive">Nie udało się wczytać działów.</p>
        ) : sections.isPending ? (
          <p className="text-sm text-muted-foreground">Wczytuję działy…</p>
        ) : available.length === 0 ? (
          <p className="text-sm text-muted-foreground">Brak działów do wydruku.</p>
        ) : (
          available.map((section) => (
            <label
              key={section}
              className="flex cursor-pointer items-center gap-3 rounded-lg px-2 py-1.5 text-sm hover:bg-muted"
            >
              <input
                type="checkbox"
                checked={!skipped.includes(section)}
                onChange={() => toggle(section)}
                className="size-4 accent-foreground"
              />
              {section}
            </label>
          ))
        )}
      </fieldset>

      <Button size="xl" loading={printing} disabled={chosen.length === 0} onClick={print}>
        <PrinterIcon />
        {chosen.length === available.length ? 'Drukuj' : `Drukuj działy: ${chosen.length}`}
      </Button>
    </>
  )
}
