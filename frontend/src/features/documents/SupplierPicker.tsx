import { useDeferredValue, useMemo, useState } from 'react'
import { Radio } from '@base-ui/react/radio'
import { RadioGroup } from '@base-ui/react/radio-group'
import { PlusIcon } from 'lucide-react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router'
import { toast } from 'sonner'

import { Form, FormActions, TextField } from '@/components/form'
import { SearchInput } from '@/components/SearchInput'
import { Button } from '@/components/ui/button'
import { DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCreateSupplier, useSuppliers } from '@/features/documents/api'
import { TOOL_ROUTES } from '@/features/tools/categories'
import { applyServerErrors } from '@/lib/form-errors'
import { matchesSearch } from '@/lib/search'
import type { Supplier } from '@/lib/types'

/**
 * Wybór dostawcy z listy sklepu z wyszukiwarką (nazwa, kod, NIP, miasto); podpowiedź na górze, dopóki nie szukasz.
 * {@code onAdd} pokazuje przycisk dodania dostawcy spoza listy (z wpisanym tekstem jako nazwą).
 */
export function SupplierPicker({
  storeId,
  selectedId,
  onSelect,
  suggested,
  onAdd,
}: {
  storeId: string
  selectedId: string | null
  onSelect: (id: string) => void
  suggested: Supplier | null
  onAdd?: (query: string) => void
}) {
  const { data: suppliers, isPending, isError, refetch } = useSuppliers(storeId)
  const [query, setQuery] = useState('')
  const deferredQuery = useDeferredValue(query)

  const visible = useMemo(() => {
    const matching = (suppliers ?? []).filter((supplier) =>
      matchesSearch([supplier.name, supplier.keyword, supplier.code, supplier.nip, supplier.city], deferredQuery),
    )
    if (!deferredQuery && suggested) {
      return [suggested, ...matching.filter((supplier) => supplier.id !== suggested.id)]
    }
    return matching
  }, [suppliers, deferredQuery, suggested])

  if (isError) {
    return (
      <p className="text-sm text-muted-foreground">
        Nie udało się wczytać dostawców.{' '}
        <Button type="button" variant="link" className="h-auto p-0" onClick={() => refetch()}>
          Spróbuj ponownie
        </Button>
      </p>
    )
  }

  return (
    <div className="grid gap-2">
      <SearchInput value={query} onChange={setQuery} placeholder="Szukaj dostawcy: nazwa, kod, NIP, miasto" />
      <div className="h-72 overflow-y-auto overscroll-contain rounded-xl border">
        {isPending ? (
          <div aria-hidden className="m-2 h-24 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
        ) : visible.length === 0 ? (
          <EmptySuppliers searching={suppliers.length > 0} canAdd={onAdd != null} />
        ) : (
          <RadioGroup
            aria-label="Dostawca"
            value={selectedId}
            onValueChange={(value) => onSelect(value as string)}
            className="grid p-1"
          >
            {visible.map((supplier) => (
              <SupplierOption key={supplier.id} supplier={supplier} suggested={supplier.id === suggested?.id} />
            ))}
          </RadioGroup>
        )}
      </div>
      {onAdd && (
        <Button type="button" variant="ghost" className="justify-self-start" onClick={() => onAdd(query.trim())}>
          <PlusIcon />
          Dodaj dostawcę spoza listy
        </Button>
      )}
    </div>
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

function EmptySuppliers({ searching, canAdd }: { searching: boolean; canAdd: boolean }) {
  return (
    <div className="grid h-full place-content-center gap-1 px-6 text-center text-sm">
      {searching ? (
        <p className="text-muted-foreground">
          Żaden dostawca nie pasuje.{canAdd && ' Możesz dodać go poniżej.'}
        </p>
      ) : (
        <>
          <p className="font-medium">Nie ma jeszcze listy dostawców</p>
          <p className="text-muted-foreground">
            Wgraj raport „Lista dostawców” w{' '}
            <Link to={TOOL_ROUTES.IMPORTS.path} className="font-medium text-foreground underline-offset-4 hover:underline">
              Imporcie danych
            </Link>
            {canAdd ? ' albo dodaj dostawcę ręcznie.' : '.'}
          </p>
        </>
      )}
    </div>
  )
}

type SupplierForm = { name: string; nip: string }

/** Dostawca, którego nie ma w systemie sklepu (np. przedstawiciel z gratisami) - zostaje tylko w aplikacji. */
export function AddSupplierForm({
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
