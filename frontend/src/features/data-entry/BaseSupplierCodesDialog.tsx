import { ArrowLeftIcon, PencilIcon, TagsIcon, XIcon } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'

import { FormActions, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { SupplierPicker } from '@/features/documents/SupplierPicker'
import { formatDate } from '@/features/ksef/format'
import { ApiError } from '@/lib/api'
import type { BaseSupplierCode } from '@/lib/types'
import { cn } from '@/lib/utils'

import { useBaseSupplierCodeChange, useBaseSupplierCodes } from './api'

/**
 * Kody dostawców z refaktur bazy SCA PR (`DDDKodDostawcy` w XML faktury, np. 09295 = Artek) przypisane do dostawców
 * z listy albo samą nazwą, wspólnie dla firmy. Po przypisaniu wyszukiwarka okna wczytywania znajduje refaktury po
 * nazwie dostawcy razem z jego zwykłymi fakturami.
 */
export function BaseSupplierCodesButton({ storeId }: { storeId: string }) {
  const [open, setOpen] = useState(false)
  const codes = useBaseSupplierCodes(storeId)
  const unassigned = (codes.data ?? []).filter((code) => !code.assignment && code.invoices > 0).length
  return (
    <>
      <Button
        variant="outline"
        size="sm"
        onClick={() => setOpen(true)}
        title="Kody dostawców, których faktury refakturuje baza SCA PR"
      >
        <TagsIcon />
        Kody z bazy
        {unassigned > 0 && (
          <span className="rounded-full bg-amber-300 px-1.5 text-xs font-semibold text-amber-950 tabular-nums dark:bg-amber-700 dark:text-amber-50">
            {unassigned}
          </span>
        )}
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="gap-4 p-6 sm:max-w-2xl">{open && <Body storeId={storeId} />}</DialogContent>
      </Dialog>
    </>
  )
}

function Body({ storeId }: { storeId: string }) {
  const codes = useBaseSupplierCodes(storeId)
  const [editing, setEditing] = useState<BaseSupplierCode | null>(null)
  if (editing) return <AssignForm storeId={storeId} code={editing} onDone={() => setEditing(null)} />
  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-lg">Kody dostawców z bazy</DialogTitle>
        <DialogDescription>
          Baza SCA PR refakturuje towar innych dostawców i podaje w fakturze ich kod u siebie. Przypisz kod do dostawcy,
          a wpisanie jego nazwy w wyszukiwarce pokaże też faktury wystawione przez bazę. Przypisania są wspólne dla
          sklepów firmy.
        </DialogDescription>
      </DialogHeader>
      <div className="max-h-[60svh] min-h-32 overflow-y-auto rounded-xl border">
        {codes.isPending ? (
          <div aria-hidden className="m-3 h-24 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
        ) : codes.isError ? (
          <p className="px-4 py-6 text-center text-sm text-destructive">
            {codes.error instanceof ApiError ? codes.error.message : 'Nie udało się wczytać kodów.'}
          </p>
        ) : codes.data.length === 0 ? (
          <p className="px-4 py-6 text-center text-sm text-muted-foreground">
            Żadna faktura bazy nie ma jeszcze kodu dostawcy.
          </p>
        ) : (
          <ul className="divide-y">
            {codes.data.map((code) => (
              <CodeRow key={code.code} storeId={storeId} code={code} onEdit={() => setEditing(code)} />
            ))}
          </ul>
        )}
      </div>
    </>
  )
}

function CodeRow({ storeId, code, onEdit }: { storeId: string; code: BaseSupplierCode; onEdit: () => void }) {
  const change = useBaseSupplierCodeChange(storeId)
  const assignment = code.assignment
  const remove = () => {
    if (!assignment) return
    change.mutate(
      { type: 'remove', id: assignment.id },
      {
        onSuccess: () =>
          toast(`Kod ${code.code} nie jest już przypisany do ${assignment.name}`, {
            action: { label: 'Przywróć', onClick: () => change.mutate({ type: 'restore', id: assignment.id }) },
          }),
        onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać zmiany'),
      },
    )
  }
  return (
    <li className="flex items-center gap-3 px-3 py-2.5">
      <span className="w-16 shrink-0 font-mono text-sm font-semibold tabular-nums">{code.code}</span>
      <span className="min-w-0 flex-1">
        {assignment ? (
          <span
            className="block truncate text-sm font-medium"
            title={assignment.supplierNip ? `NIP ${assignment.supplierNip}` : undefined}
          >
            {assignment.name}
          </span>
        ) : (
          <span className="inline-block rounded-md bg-amber-200 px-1.5 text-xs font-medium text-amber-950 dark:bg-amber-900 dark:text-amber-50">
            nieprzypisany
          </span>
        )}
        <span className="block truncate text-xs text-muted-foreground tabular-nums">
          {code.invoices > 0
            ? `${code.invoices} ${invoicesLabel(code.invoices)}, ostatnia ${formatDate(code.lastIssueDate ?? '')}`
            : 'brak faktur z tym kodem'}
          {assignment?.createdBy && ` · przypisał ${assignment.createdBy}`}
        </span>
      </span>
      <Button
        variant={assignment ? 'ghost' : 'outline'}
        size="sm"
        onClick={onEdit}
        className={cn(!assignment && 'font-medium')}
      >
        <PencilIcon />
        {assignment ? 'Zmień' : 'Przypisz'}
      </Button>
      {assignment && (
        <Button
          variant="ghost"
          size="icon-sm"
          onClick={remove}
          loading={change.isPending}
          aria-label={`Usuń przypisanie kodu ${code.code}`}
        >
          <XIcon />
        </Button>
      )}
    </li>
  )
}

function AssignForm({ storeId, code, onDone }: { storeId: string; code: BaseSupplierCode; onDone: () => void }) {
  const change = useBaseSupplierCodeChange(storeId)
  const [supplierId, setSupplierId] = useState<string | null>(code.assignment?.supplierId ?? null)
  const [name, setName] = useState(code.assignment && !code.assignment.supplierId ? code.assignment.name : '')
  const typed = name.trim()
  const previous = code.assignment
  const save = () =>
    change.mutate(
      { type: 'assign', code: code.code, supplierId: typed ? null : supplierId, name: typed || null },
      {
        onSuccess: (assigned) => {
          if (!assigned) return
          onDone()
          toast(`Kod ${code.code} = ${assigned.name}`, {
            description: 'Wpisz tę nazwę w wyszukiwarce faktur, żeby zobaczyć też refaktury bazy.',
            action: {
              label: 'Cofnij',
              onClick: () =>
                previous
                  ? change.mutate({ type: 'restore', id: previous.id })
                  : change.mutate({ type: 'remove', id: assigned.id }),
            },
          })
        },
        onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać zmiany'),
      },
    )
  return (
    <>
      <DialogHeader>
        <Button variant="ghost" size="sm" className="-ml-2 justify-self-start" onClick={onDone}>
          <ArrowLeftIcon />
          Wszystkie kody
        </Button>
        <DialogTitle className="text-lg">
          Kod <span className="font-mono">{code.code}</span>: który to dostawca?
        </DialogTitle>
        <DialogDescription>Wybierz dostawcę z listy albo wpisz samą nazwę, jeśli go na niej nie ma.</DialogDescription>
      </DialogHeader>
      <SupplierPicker storeId={storeId} selectedId={supplierId} onSelect={setSupplierId} suggested={null} />
      <TextField
        label="Albo sama nazwa dostawcy"
        value={name}
        maxLength={500}
        autoComplete="off"
        onChange={(event) => setName(event.target.value)}
        hint="Wpisana nazwa ma pierwszeństwo przed dostawcą zaznaczonym na liście."
      />
      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onDone}>
          Anuluj
        </Button>
        <Button type="button" size="lg" onClick={save} loading={change.isPending} disabled={!typed && !supplierId}>
          Przypisz
        </Button>
      </FormActions>
    </>
  )
}

const invoicesLabel = (count: number) =>
  count === 1
    ? 'faktura'
    : count % 10 >= 2 && count % 10 <= 4 && (count % 100 < 12 || count % 100 > 14)
      ? 'faktury'
      : 'faktur'
