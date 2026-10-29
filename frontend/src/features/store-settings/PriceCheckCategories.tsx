import { useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { PencilIcon, PlusIcon, Trash2Icon } from 'lucide-react'
import { toast } from 'sonner'

import { Form, FormActions, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Switch } from '@/components/ui/switch'
import { usePriceCheckCategories, usePriceCheckCategoryActions, useSalesNomenclatures } from '@/features/sales/api'
import { ApiError } from '@/lib/api'
import { applyServerErrors } from '@/lib/form-errors'
import type { PriceCheckCategory } from '@/lib/types'
import { cn } from '@/lib/utils'

type FormValues = { name: string; regionCodes: string[] }

type RegionOption = { code: string; name: string }

/** Wszystkie rejony nomenklatury naraz, każdy z przełącznikiem; kolejność według kodu. */
function RegionSwitches({
  options,
  value,
  onChange,
  error,
}: {
  options: RegionOption[]
  value: string[]
  onChange: (value: string[]) => void
  error?: string
}) {
  const selected = new Set(value)
  // Rejon zapisany w kategorii, którego nie ma już w nomenklaturze, zostaje na liście, żeby dało się go wyłączyć.
  const all = [
    ...options,
    ...value.filter((code) => !options.some((option) => option.code === code)).map((code) => ({ code, name: '' })),
  ].sort((a, b) => a.code.localeCompare(b.code))
  const toggle = (code: string, on: boolean) =>
    onChange(on ? [...value, code].sort() : value.filter((item) => item !== code))

  return (
    <fieldset className="grid gap-2">
      <legend className="mb-2 flex w-full items-baseline justify-between gap-3 text-sm font-medium">
        Rejony nomenklatury
        <span className="text-xs font-normal text-muted-foreground tabular-nums">Włączone: {selected.size}</span>
      </legend>
      {all.length === 0 ? (
        <p className="rounded-lg border border-dashed px-4 py-5 text-center text-sm text-muted-foreground">
          Wgraj nomenklaturę w Imporcie danych.
        </p>
      ) : (
        <ul
          className={cn(
            'max-h-80 divide-y overflow-y-auto rounded-lg border',
            error && 'border-destructive',
          )}
        >
          {all.map((region) => (
            <li key={region.code}>
              <label className="flex cursor-pointer items-center gap-3 px-3 py-2 text-sm transition-colors hover:bg-muted/60">
                <span className="w-6 text-muted-foreground tabular-nums">{region.code}</span>
                <span className="min-w-0 flex-1 truncate">{region.name || 'Rejon spoza nomenklatury'}</span>
                <Switch
                  checked={selected.has(region.code)}
                  onCheckedChange={(on) => toggle(region.code, on)}
                  aria-label={`${region.code} ${region.name}`}
                />
              </label>
            </li>
          ))}
        </ul>
      )}
      {error && <p className="text-sm text-destructive">{error}</p>}
    </fieldset>
  )
}

/** Nazwy rejonów nomenklatury sklepu po kodzie ("57" -> "NAPOJE"). */
function useRegionNames(storeId: string) {
  const nomenclatures = useSalesNomenclatures(storeId)
  const regions = (nomenclatures.data ?? []).filter((option) => option.level === 'REGION')
  return { regions, names: new Map(regions.map((region) => [region.code, region.name])) }
}

/**
 * Własne kategorie raportu „Zakup bazowy poza bazą”: druga linia zakładek pod „Do weryfikacji” i „Po incydencie
 * z bazy”. Kategoria to nazwa i rejony nomenklatury; przyjęcie należy do niej, gdy ma artykuł z któregoś rejonu.
 */
export function PriceCheckCategories({ storeId }: { storeId: string }) {
  const categories = usePriceCheckCategories(storeId)
  const { regions, names } = useRegionNames(storeId)
  const { save, remove, restore } = usePriceCheckCategoryActions(storeId)
  const [editing, setEditing] = useState<PriceCheckCategory | 'new' | null>(null)
  const onError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać zmiany')

  const removeCategory = (category: PriceCheckCategory) =>
    remove.mutate(category.id, {
      onSuccess: () =>
        toast.success(`Usunięto kategorię „${category.name}”`, {
          action: { label: 'Przywróć', onClick: () => restore.mutate(category.id, { onError }) },
        }),
      onError,
    })

  return (
    <Card className="gap-3 p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 className="font-medium">Kategorie raportu</h3>
          <p className="max-w-xl text-sm text-muted-foreground">
            Własne zakładki pod „Do weryfikacji” i „Po incydencie z bazy”. Przyjęcie trafia do kategorii, gdy ma
            artykuł z wybranego rejonu nomenklatury.
          </p>
        </div>
        <Button variant="outline" onClick={() => setEditing('new')}>
          <PlusIcon />
          Dodaj kategorię
        </Button>
      </div>

      {categories.isError ? (
        <p className="text-sm text-destructive">Nie udało się wczytać kategorii.</p>
      ) : !categories.data ? (
        <div aria-hidden className="h-16 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
      ) : categories.data.length === 0 ? (
        <p className="rounded-lg border border-dashed px-4 py-5 text-center text-sm text-muted-foreground">
          Nie ma jeszcze kategorii. Dodaj np. „Napoje” z rejonem napojów, żeby mieć je w osobnej zakładce.
        </p>
      ) : (
        <ul className="divide-y rounded-lg border">
          {categories.data.map((category) => (
            <li key={category.id} className="flex flex-wrap items-center gap-x-3 gap-y-2 px-3 py-2.5">
              <div className="min-w-0 flex-1">
                <p className="font-medium">{category.name}</p>
                <ul className="mt-1 flex flex-wrap gap-1.5" aria-label="Rejony">
                  {category.regionCodes.map((code) => (
                    <li
                      key={code}
                      className="rounded-md bg-muted px-2 py-0.5 text-xs text-muted-foreground tabular-nums"
                    >
                      {code}
                      {names.get(code) && <span className="text-foreground"> {names.get(code)}</span>}
                    </li>
                  ))}
                </ul>
              </div>
              <div className="flex gap-1">
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label={`Zmień kategorię ${category.name}`}
                  onClick={() => setEditing(category)}
                >
                  <PencilIcon />
                </Button>
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label={`Usuń kategorię ${category.name}`}
                  disabled={remove.isPending}
                  onClick={() => removeCategory(category)}
                >
                  <Trash2Icon />
                </Button>
              </div>
            </li>
          ))}
        </ul>
      )}

      <Dialog open={editing !== null} onOpenChange={(open) => !open && setEditing(null)}>
        <DialogContent className="gap-5 p-6 sm:max-w-lg">
          {editing && (
            <CategoryForm
              category={editing === 'new' ? null : editing}
              regionOptions={regions.map((region) => ({ code: region.code, name: region.name }))}
              onSubmit={(values) =>
                save.mutateAsync({ id: editing === 'new' ? undefined : editing.id, ...values })
              }
              onDone={(saved) => {
                setEditing(null)
                toast.success(editing === 'new' ? `Dodano kategorię „${saved.name}”` : 'Zapisano kategorię')
              }}
              onCancel={() => setEditing(null)}
            />
          )}
        </DialogContent>
      </Dialog>
    </Card>
  )
}

function CategoryForm({
  category,
  regionOptions,
  onSubmit,
  onDone,
  onCancel,
}: {
  category: PriceCheckCategory | null
  regionOptions: RegionOption[]
  onSubmit: (values: FormValues) => Promise<PriceCheckCategory>
  onDone: (saved: PriceCheckCategory) => void
  onCancel: () => void
}) {
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({
    defaultValues: { name: category?.name ?? '', regionCodes: category?.regionCodes ?? [] },
  })

  const submit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      onDone(await onSubmit({ name: values.name.trim(), regionCodes: values.regionCodes }))
    } catch (error) {
      setFormError(applyServerErrors(error, setError, ['name', 'regionCodes']))
    }
  })

  return (
    <Form onSubmit={submit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">{category ? 'Zmień kategorię' : 'Nowa kategoria raportu'}</DialogTitle>
        <DialogDescription>Nazwa zakładki i rejony nomenklatury, z których przyjęcia mają w niej być.</DialogDescription>
      </DialogHeader>
      <TextField
        label="Nazwa kategorii"
        autoComplete="off"
        autoFocus
        error={errors.name?.message}
        {...register('name', {
          validate: (value) => value.trim() !== '' || 'Podaj nazwę kategorii',
          maxLength: { value: 80, message: 'Maksymalnie 80 znaków' },
        })}
      />
      <Controller
        control={control}
        name="regionCodes"
        rules={{ validate: (value) => value.length > 0 || 'Włącz co najmniej jeden rejon' }}
        render={({ field }) => (
          <RegionSwitches
            options={regionOptions}
            value={field.value}
            onChange={field.onChange}
            error={errors.regionCodes?.message}
          />
        )}
      />
      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onCancel}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={isSubmitting}>
          Zapisz
        </Button>
      </FormActions>
    </Form>
  )
}
