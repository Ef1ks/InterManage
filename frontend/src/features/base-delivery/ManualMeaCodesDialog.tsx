import { KeyboardIcon } from 'lucide-react'
import { useMemo, useState } from 'react'
import { toast } from 'sonner'

import { TextareaField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { ApiError } from '@/lib/api'
import type { MeaOverview } from '@/lib/types'
import { cn } from '@/lib/utils'

import { useMeaChange } from './api'

const MAX_CODE_LENGTH = 20

/** Kody z wklejonego tekstu: rozdzielone spacją, nową linią, przecinkiem albo średnikiem, bez powtórzeń. */
function parseCodes(text: string) {
  return [
    ...new Set(
      text
        .split(/[\s,;]+/)
        .map((code) => code.trim())
        .filter(Boolean),
    ),
  ]
}

/**
 * Kody MEA wpisane w Mercalys ręcznie, zanim sklep zaczął korzystać ze strony: potwierdzenie bez listy do wpisania,
 * więc kod z listy znika, a kod z późniejszej dostawy albo gazetki już się na niej nie pojawi.
 */
export function ManualMeaCodesButton({ storeId, overview }: { storeId: string; overview: MeaOverview | undefined }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button variant="outline" onClick={() => setOpen(true)}>
        <KeyboardIcon />
        Wpisane wcześniej
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="gap-5 p-6 sm:max-w-lg">
          {open && <ManualCodesForm storeId={storeId} overview={overview} onDone={() => setOpen(false)} />}
        </DialogContent>
      </Dialog>
    </>
  )
}

function ManualCodesForm({
  storeId,
  overview,
  onDone,
}: {
  storeId: string
  overview: MeaOverview | undefined
  onDone: () => void
}) {
  const change = useMeaChange(storeId)
  const [text, setText] = useState('')
  const codes = useMemo(() => parseCodes(text), [text])

  const pending = useMemo(() => new Set((overview?.pending ?? []).map((item) => item.code)), [overview])
  const confirmed = useMemo(
    () =>
      new Set((overview?.confirmations ?? []).filter((item) => item.deletedAt === null).flatMap((item) => item.codes)),
    [overview],
  )
  const tooLong = codes.filter((code) => code.length > MAX_CODE_LENGTH)
  const valid = codes.filter((code) => code.length <= MAX_CODE_LENGTH)
  const onList = valid.filter((code) => pending.has(code))
  const already = valid.filter((code) => confirmed.has(code))
  const ahead = valid.filter((code) => !pending.has(code) && !confirmed.has(code))
  const toSave = valid.filter((code) => !confirmed.has(code))

  const run = (action: Parameters<typeof change.mutate>[0], onDone: (result: MeaOverview) => void) =>
    change.mutate(action, {
      onSuccess: onDone,
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać kodów MEA'),
    })

  const save = () => {
    const previous = overview?.confirmations[0]?.id
    run({ type: 'confirm', codes: toSave }, (result) => {
      onDone()
      const latest = result.confirmations[0]
      if (!latest || latest.id === previous) {
        toast('Wszystkie te kody były już wpisane')
        return
      }
      toast.success(`Zapisano kody wpisane wcześniej: ${latest.codes.length}`, {
        action: { label: 'Cofnij', onClick: () => run({ type: 'delete', id: latest.id }, () => undefined) },
      })
    })
  }

  return (
    <>
      <DialogHeader>
        <DialogTitle>Kody MEA wpisane wcześniej</DialogTitle>
        <DialogDescription>
          Wpisz albo wklej kody, które są już w Mercalys (np. wpisane ręcznie, zanim sklep zaczął korzystać ze strony).
          Kody z listy znikną z niej, a kody, których jeszcze nie ma w żadnym dokumencie, nie pojawią się po kolejnej
          dostawie ani gazetce.
        </DialogDescription>
      </DialogHeader>

      <TextareaField
        label="Kody MEA (każdy w nowej linii, po spacji albo przecinku)"
        rows={6}
        value={text}
        onChange={(event) => setText(event.target.value)}
        autoFocus
        className="font-mono"
        error={
          tooLong.length > 0 ? `Za długie kody (najwyżej ${MAX_CODE_LENGTH} znaków): ${tooLong.join(', ')}` : undefined
        }
      />

      {valid.length > 0 && (
        <div className="grid gap-2 text-sm">
          <CodeGroup
            label="Z listy do wpisania"
            codes={onList}
            className="bg-blue-200 text-blue-950 dark:bg-blue-900 dark:text-blue-50"
          />
          <CodeGroup
            label="Jeszcze w żadnym dokumencie (zapiszą się na zapas)"
            codes={ahead}
            className="bg-amber-200 text-amber-950 dark:bg-amber-900 dark:text-amber-50"
          />
          <CodeGroup
            label="Już potwierdzone (pominięte)"
            codes={already}
            className="bg-stone-200 text-stone-900 dark:bg-stone-800 dark:text-stone-100"
          />
        </div>
      )}

      <div className="flex justify-end gap-2">
        <Button variant="outline" onClick={onDone}>
          Anuluj
        </Button>
        <Button onClick={save} loading={change.isPending} disabled={toSave.length === 0 || tooLong.length > 0}>
          Zapisz jako wpisane ({toSave.length})
        </Button>
      </div>
    </>
  )
}

function CodeGroup({ label, codes, className }: { label: string; codes: string[]; className: string }) {
  if (codes.length === 0) return null
  return (
    <div>
      <p className="mb-1 text-muted-foreground">
        {label}: {codes.length}
      </p>
      <div className="flex flex-wrap gap-1">
        {codes.map((code) => (
          <span key={code} className={cn('rounded px-1.5 font-semibold tabular-nums', className)}>
            {code}
          </span>
        ))}
      </div>
    </div>
  )
}
