import { useState } from 'react'
import { toast } from 'sonner'

import { DateField, Form, FormActions } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useChangeDeliveryDate } from '@/features/deli-order/api'
import { ApiError } from '@/lib/api'
import { formatLongDate, todayIso } from '@/lib/dates'
import type { DeliOrder } from '@/lib/types'

type Props = { storeId: string; order: DeliOrder; open: boolean; onOpenChange: (open: boolean) => void }

/** Zmiana daty dostawy niezrealizowanego zamówienia, np. gdy przy składaniu wybrano zły dzień. */
export function DeliveryDateDialog({ storeId, order, open, onOpenChange }: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-sm">
        {open && <DeliveryDateForm storeId={storeId} order={order} onDone={() => onOpenChange(false)} />}
      </DialogContent>
    </Dialog>
  )
}

function DeliveryDateForm({ storeId, order, onDone }: { storeId: string; order: DeliOrder; onDone: () => void }) {
  const change = useChangeDeliveryDate(storeId, order.id)
  const [date, setDate] = useState(order.deliveryDate)
  const [error, setError] = useState<string | null>(null)

  const onSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    if (date === order.deliveryDate) {
      onDone()
      return
    }
    setError(null)
    change.mutate(date, {
      onSuccess: () => {
        toast.success(`Dostawa przeniesiona na ${formatLongDate(date)}`)
        onDone()
      },
      onError: (failure) => setError(failure instanceof ApiError ? failure.message : 'Nie udało się zmienić daty'),
    })
  }

  return (
    <Form onSubmit={onSubmit} error={error}>
      <DialogHeader>
        <DialogTitle className="text-lg">Zmień datę dostawy</DialogTitle>
        <DialogDescription>
          Obie strony zobaczą nową datę, a przy zamówieniu zostanie dopisek, na kiedy je złożono.
        </DialogDescription>
      </DialogHeader>
      <DateField label="Data dostawy" value={date} min={todayIso()} onChange={setDate} />
      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onDone}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={change.isPending}>
          Zapisz datę
        </Button>
      </FormActions>
    </Form>
  )
}
