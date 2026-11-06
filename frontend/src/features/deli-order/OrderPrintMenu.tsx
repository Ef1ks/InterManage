import { ChevronDownIcon, PrinterIcon } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { type OrderPrintVariant, orderPrintPath } from '@/features/deli-order/api'
import { ApiError, printFile } from '@/lib/api'

const VARIANTS: { variant: OrderPrintVariant; label: string; hint: string }[] = [
  { variant: 'ORDER', label: 'Zamówienie', hint: 'Zamówione pozycje z ilościami' },
  { variant: 'ORDER_BLANK', label: 'Zamówienie in blanco', hint: 'Zamówione pozycje, bez ilości' },
  { variant: 'FORM_BLANK', label: 'Cały formularz in blanco', hint: 'Wszystkie pozycje formularza, bez ilości' },
  {
    variant: 'FORM_WITH_QUANTITIES',
    label: 'Cały formularz z ilościami',
    hint: 'Wszystkie pozycje, zamówione ilości wpisane, reszta do dopisania',
  },
]

/** Wydruk zamówienia w wybranym wariancie, żeby pracownicy mogli dopisać długopisem towar ponad zamówienie. */
export function OrderPrintMenu({ storeId, orderId }: { storeId: string; orderId: string }) {
  const [pending, setPending] = useState(false)

  const print = async (variant: OrderPrintVariant) => {
    setPending(true)
    try {
      await printFile(orderPrintPath(storeId, orderId, variant))
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się przygotować wydruku')
    } finally {
      setPending(false)
    }
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger render={<Button variant="outline" size="xl" loading={pending} />}>
        <PrinterIcon />
        Drukuj
        <ChevronDownIcon />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-72">
        {VARIANTS.map(({ variant, label, hint }) => (
          <DropdownMenuItem key={variant} onClick={() => print(variant)} className="items-start py-2">
            <span className="grid">
              <span>{label}</span>
              <span className="text-xs text-muted-foreground">{hint}</span>
            </span>
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
