import { ClipboardCopyIcon, ScanSearchIcon } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'

import { TextareaField } from '@/components/form'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { buildMissingItm8Macro } from '@/features/data-entry/mercalys-macro'
import { formatDocumentDate } from '@/features/documents/format'
import type { BaseDelivery } from '@/lib/types'

import { buildMacro, expiryDate, parseItm8List, planMacro } from './itm8-macro'

type Props = {
  delivery: BaseDelivery | null
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * „Uzupełnij ITM8”: wklejone kody ITM8 parują się z liniami dokumentu z bazy (EAN, dostawca, sztuk w kartonie),
 * a makro dla odtwarzacza AutoHotkey (Alt+E) trafia do schowka.
 */
export function Itm8MacroDialog({ delivery, open, onOpenChange }: Props) {
  const [text, setText] = useState('')
  const codes = parseItm8List(text)
  const plan = delivery && codes.length > 0 ? planMacro(delivery, codes) : null

  /** Makro zbiera z Mercalys kody z błędem „ITM nie istnieje” do Schowka; okno zostaje otwarte, żeby je tu wkleić. */
  const collect = async () => {
    if (!delivery) return
    try {
      await navigator.clipboard.writeText(buildMissingItm8Macro(delivery.number))
    } catch {
      toast.error('Nie udało się skopiować makra do schowka')
      return
    }
    toast.success('Makro w schowku', {
      description: 'Wciśnij Alt+E. Kody ITM8 z błędem „ITM nie istnieje” trafią do schowka, potem wklej je tutaj.',
      duration: 10_000,
    })
  }

  const submit = async () => {
    if (!plan) return
    const skipped = [...plan.missing, ...plan.withoutEan, ...plan.sameEan.map((item) => item.code)]
    if (plan.missing.length > 0) {
      toast.warning(`Nie ma w dokumencie: ${plan.missing.join(', ')}`, { duration: 10_000 })
    }
    if (plan.withoutEan.length > 0) {
      toast.warning(`Bez EAN-u w dokumencie: ${plan.withoutEan.join(', ')}`, { duration: 10_000 })
    }
    if (plan.sameEan.length > 0) {
      toast.warning(`Wpisz ręcznie (ten sam EAN co inny kod): ${plan.sameEan.map((item) => item.code).join(', ')}`, {
        duration: 15_000,
      })
    }
    if (plan.items.length === 0) return
    try {
      await navigator.clipboard.writeText(buildMacro(plan.items))
    } catch {
      toast.error('Nie udało się skopiować makra do schowka')
      return
    }
    toast.success(`Makro w schowku: ${plan.items.length} poz.`, {
      description: `Przejdź do programu sklepu i wciśnij Alt+E.${skipped.length > 0 ? ` Pominięte: ${skipped.length}.` : ''}`,
    })
    onOpenChange(false)
  }

  return (
    <Dialog open={open && delivery !== null} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Uzupełnij ITM8</DialogTitle>
          <DialogDescription>
            Wklej kody ITM8 z bonu {delivery?.number}. EAN, dostawcę i sztuki w kartonie weźmiemy z dokumentu, termin to
            data dokumentu + 2 lata
            {delivery && ` (${formatDocumentDate(delivery.deliveryDate)} → ${expiryDate(delivery.deliveryDate)})`}.
          </DialogDescription>
        </DialogHeader>

        <Button variant="outline" className="justify-self-start" disabled={!delivery?.number} onClick={collect}>
          <ScanSearchIcon />
          Zbierz brakujące ITM8 z Mercalys
        </Button>

        <TextareaField
          label="Kody ITM8 (każdy w nowej linii, po przecinku albo spacji)"
          rows={8}
          value={text}
          onChange={(event) => setText(event.target.value)}
          className="font-mono"
          autoFocus
        />

        {plan && (
          <div className="grid gap-1.5 text-sm">
            <p>
              W dokumencie: <strong className="tabular-nums">{plan.items.length}</strong> z {codes.length}
            </p>
            {plan.missing.length > 0 && (
              <p className="rounded-lg bg-amber-200 px-3 py-2 text-amber-950 dark:bg-amber-900 dark:text-amber-50">
                Nie ma w dokumencie ({plan.missing.length}): {plan.missing.join(', ')}
              </p>
            )}
            {plan.withoutEan.length > 0 && (
              <p className="rounded-lg bg-amber-200 px-3 py-2 text-amber-950 dark:bg-amber-900 dark:text-amber-50">
                Bez EAN-u w dokumencie ({plan.withoutEan.length}): {plan.withoutEan.join(', ')}
              </p>
            )}
            {plan.sameEan.length > 0 && (
              <div className="rounded-lg bg-amber-200 px-3 py-2 text-amber-950 dark:bg-amber-900 dark:text-amber-50">
                <p className="font-medium">Ten sam EAN co inny kod: wpisz ręcznie ({plan.sameEan.length})</p>
                <p className="mt-0.5 text-xs">
                  Makro otwiera kartę po EAN-ie, więc kolejny ITM8 tego artykułu trafiłby na kartę z już wpisanym kodem.
                </p>
                <ul className="mt-1 font-mono text-xs">
                  {plan.sameEan.map((item) => (
                    <li key={item.code}>
                      {item.code} · EAN {item.ean} (makro wpisze {item.firstCode})
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        )}

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Anuluj
          </Button>
          <Button disabled={!plan || plan.items.length === 0} onClick={submit}>
            <ClipboardCopyIcon />
            Generuj i kopiuj makro
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
