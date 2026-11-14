import { ClipboardCopyIcon, ClipboardPasteIcon, ScanSearchIcon } from 'lucide-react'
import { type ReactNode, useState } from 'react'
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
import { buildItm8Macro, buildMissingItm8Macro } from '@/features/data-entry/mercalys-macro'
import { formatDocumentDate } from '@/features/documents/format'
import type { BaseDelivery } from '@/lib/types'

import { DELETE_AFTER_YEARS, deleteDateOf, parseItm8List, planMacro, type SkipReason } from './itm8-macro'

type Props = {
  delivery: BaseDelivery | null
  open: boolean
  onOpenChange: (open: boolean) => void
}

const SKIP_LABELS: Record<SkipReason, string> = {
  MISSING: 'Nie ma w dokumencie',
  NO_EAN: 'Bez EAN-u w dokumencie (makro nie otworzy karty)',
}

const money = (value: number) => value.toFixed(2).replace('.', ',')

/**
 * „Uzupełnij ITM8”: kody z błędem „ITM nie istnieje” (zebrane makrem z Mercalys albo wklejone) parują się z liniami
 * dokumentu z bazy, okno pokazuje, co makro wpisze, a makro dla odtwarzacza (Alt+E) trafia do schowka.
 */
export function Itm8MacroDialog({ delivery, open, onOpenChange }: Props) {
  const [text, setText] = useState('')
  const codes = parseItm8List(text)
  const plan = delivery && codes.length > 0 ? planMacro(delivery, codes) : null
  const items = plan?.items ?? []
  const skipped = plan?.skipped ?? []

  const copy = async (content: string) => {
    try {
      await navigator.clipboard.writeText(content)
      return true
    } catch {
      toast.error('Nie udało się skopiować makra do schowka')
      return false
    }
  }

  /** Makro zbiera z Mercalys kody z błędem „ITM nie istnieje” do Schowka; okno zostaje otwarte, żeby je tu wkleić. */
  const collect = async () => {
    if (!delivery) return
    const suppliers = delivery.lines.map((line) => line.supplierCode)
    if (!(await copy(buildMissingItm8Macro(delivery.number, suppliers)))) return
    toast.success('Makro w schowku', {
      description:
        'Wciśnij Alt+E (odtwarzacz od wersji 45). Makro przejdzie pliki bonu od 538 i 88110 (pliku 88110 może nie być, wtedy go pomija), a kody z błędem „ITM nie istnieje” trafią do schowka. Potem kliknij „Wklej ze schowka”.',
      duration: 10_000,
    })
  }

  const paste = async () => {
    try {
      const clipboard = await navigator.clipboard.readText()
      if (!parseItm8List(clipboard).length) {
        toast.warning('W schowku nie ma kodów ITM8')
        return
      }
      setText(clipboard)
    } catch {
      toast.error('Przeglądarka nie pozwoliła odczytać schowka. Wklej kody Ctrl+V.')
    }
  }

  const submit = async () => {
    if (items.length === 0 || !(await copy(buildItm8Macro(items)))) return
    toast.success(`Makro w schowku: ${items.length} poz.`, {
      description: `Wciśnij Alt+E, makro samo otworzy Mercalys.${skipped.length > 0 ? ` Pominięte: ${skipped.length}.` : ''}`,
    })
    onOpenChange(false)
  }

  return (
    <Dialog open={open && delivery !== null} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-5xl">
        <DialogHeader>
          <DialogTitle>Uzupełnij ITM8 · bon {delivery?.number}</DialogTitle>
          <DialogDescription>
            Makro dopisze kod ITM8 z bazy na karcie artykułu (szuka jej po EAN-ie): dostawca, ref. dostawcy, opakowanie,
            data usunięcia i cena zakupu z dokumentu.
          </DialogDescription>
        </DialogHeader>

        <div className="grid gap-6 lg:grid-cols-[19rem_minmax(0,1fr)]">
          <div className="grid content-start gap-5">
            <section className="grid gap-2">
              <StepTitle number={1}>Zbierz kody z Mercalys</StepTitle>
              <p className="text-sm text-muted-foreground">
                Makro otworzy bon w Obsłudze BL bazy i skopiuje kody z błędem „ITM nie istnieje”.
              </p>
              <Button variant="outline" className="justify-self-start" disabled={!delivery?.number} onClick={collect}>
                <ScanSearchIcon />
                Kopiuj makro zbierające
              </Button>
            </section>

            <section className="grid gap-2">
              <div className="flex items-center justify-between gap-2">
                <StepTitle number={2}>Wklej kody</StepTitle>
                <Button variant="ghost" size="sm" onClick={paste}>
                  <ClipboardPasteIcon />
                  Wklej ze schowka
                </Button>
              </div>
              <TextareaField
                label="Kody ITM8"
                hint="W liniach, po przecinku albo spacji. Zera z przodu nie mają znaczenia."
                rows={9}
                value={text}
                onChange={(event) => setText(event.target.value)}
                className="font-mono"
                autoFocus
              />
            </section>

            {delivery && (
              <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 rounded-lg bg-muted px-3 py-2.5 text-sm">
                <dt className="text-muted-foreground">Data usunięcia</dt>
                <dd className="font-medium tabular-nums">
                  {formatDocumentDate(deleteDateOf(delivery.deliveryDate))}
                  <span className="font-normal text-muted-foreground"> (bon + {DELETE_AFTER_YEARS} lata)</span>
                </dd>
                <dt className="text-muted-foreground">Aktywny</dt>
                <dd className="font-medium">odznaczony</dd>
              </dl>
            )}
          </div>

          <section className="grid min-w-0 content-start gap-3">
            <StepTitle number={3}>
              Sprawdź, co makro wpisze
              {codes.length > 0 && (
                <span className="ml-2 font-normal text-muted-foreground tabular-nums">
                  {items.length} z {codes.length}
                </span>
              )}
            </StepTitle>

            {codes.length === 0 ? (
              <p className="rounded-lg border border-dashed px-4 py-10 text-center text-sm text-muted-foreground">
                Wklej kody, a tu pojawią się artykuły z dokumentu.
              </p>
            ) : (
              <>
                {items.length > 0 && (
                  <div className="max-h-[22rem] overflow-auto rounded-lg border">
                    <table className="w-full text-sm">
                      <thead className="sticky top-0 bg-muted text-left text-xs text-muted-foreground">
                        <tr>
                          <th className="px-3 py-2 font-medium">ITM8</th>
                          <th className="px-3 py-2 font-medium">EAN</th>
                          <th className="px-3 py-2 font-medium">Artykuł</th>
                          <th className="px-3 py-2 text-right font-medium">Dost.</th>
                          <th className="px-3 py-2 text-right font-medium">Opak.</th>
                          <th className="px-3 py-2 text-right font-medium">C.Z. po rab.</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y">
                        {items.map((item) => (
                          <tr key={item.itm8}>
                            <td className="px-3 py-1.5 font-mono tabular-nums">{item.itm8}</td>
                            <td className="px-3 py-1.5 font-mono tabular-nums">{item.ean}</td>
                            <td className="max-w-0 px-3 py-1.5">
                              <span className="block truncate" title={item.line.name}>
                                {item.line.name}
                              </span>
                              {item.line.brand && (
                                <span className="block truncate text-xs text-muted-foreground">{item.line.brand}</span>
                              )}
                            </td>
                            <td className="px-3 py-1.5 text-right tabular-nums">{item.supplierCode}</td>
                            <td className="px-3 py-1.5 text-right tabular-nums">{item.pack}</td>
                            <td className="px-3 py-1.5 text-right tabular-nums">{money(item.purchasePrice)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}

                {skipped.length > 0 && (
                  <div className="rounded-lg bg-amber-200 px-3 py-2.5 text-sm text-amber-950 dark:bg-amber-900 dark:text-amber-50">
                    <p className="font-medium">Pominięte ({skipped.length}), wpisz je ręcznie</p>
                    <ul className="mt-1 grid gap-0.5">
                      {(Object.keys(SKIP_LABELS) as SkipReason[]).map((reason) => {
                        const list = skipped.filter((item) => item.reason === reason)
                        if (list.length === 0) return null
                        return (
                          <li key={reason}>
                            {SKIP_LABELS[reason]}:{' '}
                            <span className="font-mono">{list.map((item) => item.code).join(', ')}</span>
                          </li>
                        )
                      })}
                    </ul>
                  </div>
                )}
              </>
            )}
          </section>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Anuluj
          </Button>
          <Button size="xl" disabled={items.length === 0} onClick={submit}>
            <ClipboardCopyIcon />
            Kopiuj makro{items.length > 0 && ` (${items.length})`}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function StepTitle({ number, children }: { number: number; children: ReactNode }) {
  return (
    <h3 className="flex items-center gap-2 text-sm font-semibold">
      <span className="grid size-5 shrink-0 place-items-center rounded-full bg-foreground text-xs text-background">
        {number}
      </span>
      <span>{children}</span>
    </h3>
  )
}
