import { useDeferredValue, useMemo, useState } from 'react'
import { Radio } from '@base-ui/react/radio'
import { RadioGroup } from '@base-ui/react/radio-group'
import { GiftIcon, PlusIcon } from 'lucide-react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router'
import { toast } from 'sonner'

import { Form, FormActions, TextField } from '@/components/form'
import { SearchInput } from '@/components/SearchInput'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCan } from '@/features/auth/api'
import { useCreateSupplier, useDocumentGratis, useSuppliers } from '@/features/documents/api'
import { formatMoney } from '@/features/documents/format'
import { TOOL_ROUTES } from '@/features/tools/categories'
import { ApiError } from '@/lib/api'
import { applyServerErrors } from '@/lib/form-errors'
import { matchesSearch } from '@/lib/search'
import type { Document, DocumentGratis, DocumentSummary, Supplier } from '@/lib/types'

const dateFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short' })

/** Pozycja-znacznik z systemu sklepu, np. "GRATIS SPOŻYWKA MIX" (jak {@code DocumentLine.isGratisItem} w backendzie). */
const isGratisItem = (name: string | null) => name?.toUpperCase().includes('GRATIS') ?? false

/** Na liście: od kogo jest gratis albo podpowiedź, że dokument z pozycją GRATIS może nim być. */
export function GratisBadge({ document }: { document: DocumentSummary }) {
  if (document.gratis) {
    return (
      <Badge
        variant="outline"
        title={`Gratis od ${document.gratis.supplierName}`}
        className="max-w-48 border-emerald-300/70 bg-emerald-50 text-emerald-800 dark:border-emerald-500/30 dark:bg-emerald-500/10 dark:text-emerald-300"
      >
        <GiftIcon aria-hidden />
        <span className="truncate">Gratis · {document.gratis.supplierName}</span>
      </Badge>
    )
  }
  if (document.gratisCandidate) {
    return (
      <Badge
        variant="outline"
        title="W dokumencie jest pozycja GRATIS"
        className="border-amber-300 bg-amber-50 text-amber-800 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-300"
      >
        <GiftIcon aria-hidden />
        Może być gratisem
      </Badge>
    )
  }
  return null
}

/**
 * Gratis w szczegółach przeniesienia stanów: kto i od jakiego dostawcy go zarejestrował, albo przycisk rejestracji.
 * Cofnięcie niczego nie kasuje - toast pozwala od razu przywrócić.
 */
export function DocumentGratisSection({ storeId, document }: { storeId: string; document: Document }) {
  const canEdit = useCan('DOCUMENTS', 'EDIT')
  const [open, setOpen] = useState(false)
  const { register, cancel, restore } = useDocumentGratis(storeId, document.type.toLowerCase(), document.id)

  if (!document.gratisAllowed || (!canEdit && !document.gratis)) return null

  const onError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać zmiany')

  const restoreGratis = () =>
    restore.mutate(undefined, { onSuccess: () => toast.success('Przywrócono gratis'), onError })

  const cancelGratis = () =>
    cancel.mutate(undefined, {
      onSuccess: () =>
        toast.success('Cofnięto rejestrację gratisu', { action: { label: 'Przywróć', onClick: restoreGratis } }),
      onError,
    })

  return (
    <section aria-labelledby="document-gratis" className="mb-6">
      {document.gratis ? (
        <div className="flex flex-wrap items-center gap-x-4 gap-y-3 rounded-xl border border-emerald-300/70 bg-emerald-50/60 px-4 py-3 dark:border-emerald-500/30 dark:bg-emerald-500/5">
          <GiftIcon aria-hidden className="size-5 shrink-0 text-emerald-700 dark:text-emerald-400" />
          <div className="min-w-0 flex-1 text-sm">
            <h3 id="document-gratis" className="font-semibold">
              Gratis od {document.gratis.supplierName}
              {document.gratis.supplierCode && (
                <span className="ml-1.5 font-normal text-muted-foreground tabular-nums">{document.gratis.supplierCode}</span>
              )}
            </h3>
            <p className="text-muted-foreground tabular-nums">
              {formatMoney(document.saleValueGross)} zł brutto · {registeredLine(document.gratis)}
            </p>
          </div>
          {canEdit && (
            <div className="flex gap-2">
              <Button variant="outline" onClick={() => setOpen(true)}>
                Zmień dostawcę
              </Button>
              <Button variant="ghost" loading={cancel.isPending} onClick={cancelGratis}>
                Cofnij
              </Button>
            </div>
          )}
        </div>
      ) : (
        <div className="flex flex-wrap items-center gap-x-4 gap-y-3 rounded-xl border bg-card px-4 py-3">
          <GiftIcon aria-hidden className="size-5 shrink-0 text-muted-foreground" />
          <div className="min-w-0 flex-1 text-sm">
            <h3 id="document-gratis" className="font-semibold">
              {document.gratisCandidate ? 'To przeniesienie może być gratisem' : 'Gratis od dostawcy?'}
            </h3>
            <p className="text-muted-foreground">
              {document.gratisCandidate
                ? 'W dokumencie jest pozycja GRATIS. Jeśli to gratis, wskaż dostawcę, od którego przyszedł.'
                : 'Jeśli towar z tego dokumentu to gratis, wskaż dostawcę, od którego przyszedł.'}
            </p>
          </div>
          <Button onClick={() => setOpen(true)}>
            <GiftIcon />
            Zarejestruj jako gratis
          </Button>
        </div>
      )}

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="gap-5 p-6 sm:max-w-lg">
          {open && (
            <GratisDialogBody
              storeId={storeId}
              document={document}
              saving={register.isPending}
              onSubmit={(supplierId) =>
                register.mutate(supplierId, {
                  onSuccess: (saved) => {
                    toast.success(`Zarejestrowano gratis od ${saved.gratis?.supplierName ?? 'dostawcy'}`)
                    setOpen(false)
                  },
                  onError,
                })
              }
              onCancel={() => setOpen(false)}
            />
          )}
        </DialogContent>
      </Dialog>
    </section>
  )
}

function registeredLine(gratis: DocumentGratis) {
  const date = dateFormat.format(new Date(gratis.registeredAt))
  return gratis.registeredBy ? `zarejestrował(a) ${gratis.registeredBy}, ${date}` : `zarejestrowano ${date}`
}

/** Dostawca, od którego najczęściej są linie dokumentu (bez pozycji GRATIS, którą wystawia sam sklep). */
function suggestedSupplierCode(document: Document) {
  const counts = new Map<string, number>()
  for (const line of document.lines) {
    if (line.supplierCode && !isGratisItem(line.name)) {
      counts.set(line.supplierCode, (counts.get(line.supplierCode) ?? 0) + 1)
    }
  }
  return [...counts.entries()].sort((a, b) => b[1] - a[1])[0]?.[0] ?? null
}

type DialogBodyProps = {
  storeId: string
  document: Document
  saving: boolean
  onSubmit: (supplierId: string) => void
  onCancel: () => void
}

/** Wybór dostawcy z listy sklepu (z podpowiedzią z linii dokumentu) albo dodanie nowego, spoza listy. */
function GratisDialogBody({ storeId, document, saving, onSubmit, onCancel }: DialogBodyProps) {
  const { data: suppliers, isPending, isError, refetch } = useSuppliers(storeId)
  const [query, setQuery] = useState('')
  const deferredQuery = useDeferredValue(query)
  const [adding, setAdding] = useState(false)
  const [picked, setPicked] = useState<string | null>(null)

  const suggested = useMemo(() => {
    const code = suggestedSupplierCode(document)
    return code ? (suppliers?.find((supplier) => supplier.code === code) ?? null) : null
  }, [document, suppliers])
  const selectedId = picked ?? document.gratis?.supplierId ?? suggested?.id ?? null

  const visible = useMemo(() => {
    const matching = (suppliers ?? []).filter((supplier) =>
      matchesSearch([supplier.name, supplier.keyword, supplier.code, supplier.nip, supplier.city], deferredQuery),
    )
    // Podpowiedź z dokumentu na górze, dopóki nie szukasz.
    if (!deferredQuery && suggested) {
      return [suggested, ...matching.filter((supplier) => supplier.id !== suggested.id)]
    }
    return matching
  }, [suppliers, deferredQuery, suggested])

  if (adding) {
    return (
      <AddSupplierForm
        storeId={storeId}
        initialName={query.trim()}
        onAdded={(supplier) => {
          setPicked(supplier.id)
          setQuery('')
          setAdding(false)
        }}
        onBack={() => setAdding(false)}
      />
    )
  }

  return (
    <Form
      onSubmit={(event) => {
        event.preventDefault()
        if (selectedId) onSubmit(selectedId)
      }}
    >
      <DialogHeader>
        <DialogTitle className="text-lg">{document.gratis ? 'Zmień dostawcę gratisu' : 'Zarejestruj jako gratis'}</DialogTitle>
        <DialogDescription className="tabular-nums">
          {document.typeSingular} {document.number} · gratis o wartości {formatMoney(document.saleValueGross)} zł brutto
          (sprzedaż brutto dokumentu).
        </DialogDescription>
      </DialogHeader>

      {isError ? (
        <p className="text-sm text-muted-foreground">
          Nie udało się wczytać dostawców.{' '}
          <Button type="button" variant="link" className="h-auto p-0" onClick={() => refetch()}>
            Spróbuj ponownie
          </Button>
        </p>
      ) : (
        <div className="grid gap-2">
          <SearchInput value={query} onChange={setQuery} placeholder="Szukaj dostawcy: nazwa, kod, NIP, miasto" />
          <div className="h-72 overflow-y-auto overscroll-contain rounded-xl border">
            {isPending ? (
              <div aria-hidden className="m-2 h-24 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
            ) : visible.length === 0 ? (
              <EmptySuppliers searching={suppliers.length > 0} />
            ) : (
              <RadioGroup
                aria-label="Dostawca"
                value={selectedId}
                onValueChange={(value) => setPicked(value as string)}
                className="grid p-1"
              >
                {visible.map((supplier) => (
                  <SupplierOption key={supplier.id} supplier={supplier} suggested={supplier.id === suggested?.id} />
                ))}
              </RadioGroup>
            )}
          </div>
          <Button type="button" variant="ghost" className="justify-self-start" onClick={() => setAdding(true)}>
            <PlusIcon />
            Dodaj dostawcę spoza listy
          </Button>
        </div>
      )}

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onCancel}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={saving} disabled={!selectedId}>
          {document.gratis ? 'Zapisz dostawcę' : 'Zarejestruj gratis'}
        </Button>
      </FormActions>
    </Form>
  )
}

function SupplierOption({ supplier, suggested }: { supplier: Supplier; suggested: boolean }) {
  const details = [supplier.code ?? 'dodany ręcznie', supplier.city, supplier.nip && `NIP ${supplier.nip}`]
  return (
    <label className="flex cursor-pointer items-center gap-3 rounded-lg px-3 py-2 text-sm transition-colors hover:bg-muted/60 has-data-checked:bg-muted">
      <Radio.Root
        value={supplier.id}
        className="flex size-4 shrink-0 items-center justify-center rounded-full border border-input outline-none focus-visible:ring-3 focus-visible:ring-ring/50 data-checked:border-primary data-checked:bg-primary"
      >
        <Radio.Indicator className="size-1.5 rounded-full bg-primary-foreground" />
      </Radio.Root>
      <span className="min-w-0 flex-1">
        <span className="block truncate font-medium">{supplier.name}</span>
        <span className="block truncate text-xs text-muted-foreground tabular-nums">
          {details.filter(Boolean).join(' · ')}
        </span>
      </span>
      {suggested && <span className="shrink-0 text-xs text-muted-foreground">z dokumentu</span>}
    </label>
  )
}

function EmptySuppliers({ searching }: { searching: boolean }) {
  return (
    <div className="grid h-full place-content-center gap-1 px-6 text-center text-sm">
      {searching ? (
        <p className="text-muted-foreground">Żaden dostawca nie pasuje. Możesz dodać go poniżej.</p>
      ) : (
        <>
          <p className="font-medium">Nie ma jeszcze listy dostawców</p>
          <p className="text-muted-foreground">
            Wgraj raport „Lista dostawców” w{' '}
            <Link to={TOOL_ROUTES.IMPORTS.path} className="font-medium text-foreground underline-offset-4 hover:underline">
              Imporcie danych
            </Link>{' '}
            albo dodaj dostawcę ręcznie.
          </p>
        </>
      )}
    </div>
  )
}

type SupplierForm = { name: string; nip: string }

/** Dostawca, którego nie ma w systemie sklepu (np. przedstawiciel z gratisami) - zostaje tylko w aplikacji. */
function AddSupplierForm({
  storeId,
  initialName,
  onAdded,
  onBack,
}: {
  storeId: string
  initialName: string
  onAdded: (supplier: Supplier) => void
  onBack: () => void
}) {
  const create = useCreateSupplier(storeId)
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<SupplierForm>({ defaultValues: { name: initialName, nip: '' } })

  const onSubmit = handleSubmit((values) => {
    setFormError(null)
    create.mutate(values, {
      onSuccess: (supplier) => {
        toast.success(`Dodano dostawcę ${supplier.name}`)
        onAdded(supplier)
      },
      onError: (error) => setFormError(applyServerErrors(error, setError, ['name', 'nip'])),
    })
  })

  return (
    <Form onSubmit={onSubmit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">Nowy dostawca</DialogTitle>
        <DialogDescription>
          Dla dostawcy spoza listy z systemu sklepu. Będzie do wyboru tylko w aplikacji, w tym sklepie.
        </DialogDescription>
      </DialogHeader>

      <TextField
        label="Nazwa dostawcy"
        autoComplete="off"
        autoFocus
        error={errors.name?.message}
        {...register('name', {
          validate: (value) => value.trim() !== '' || 'Podaj nazwę dostawcy',
          maxLength: { value: 200, message: 'Maksymalnie 200 znaków' },
        })}
      />
      <TextField
        label="NIP (opcjonalnie)"
        inputMode="numeric"
        autoComplete="off"
        error={errors.nip?.message}
        {...register('nip', {
          pattern: { value: /^[\d\s-]{0,20}$/, message: 'NIP to same cyfry, np. 9291243024' },
        })}
      />

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onBack}>
          Wróć do listy
        </Button>
        <Button type="submit" size="lg" loading={create.isPending}>
          Dodaj dostawcę
        </Button>
      </FormActions>
    </Form>
  )
}
