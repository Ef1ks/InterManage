import { useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { toast } from 'sonner'

import { Form, FormActions, SegmentedField, SwitchField, TextField, type SegmentOption } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCurrentUser } from '@/features/auth/api'
import { useStoreRoleChange } from '@/features/store-roles/api'
import { usePermissionCategories } from '@/features/tools/categories'
import { applyServerErrors } from '@/lib/form-errors'
import type { PermissionCategory, PermissionCategoryInfo, PermissionLevel, Permissions, StoreRole } from '@/lib/types'

type Access = 'NONE' | PermissionLevel

type RankForm = {
  name: string
  description: string
  outsideHierarchy: boolean
  access: Record<string, Access>
}

const ACCESS_OPTIONS: SegmentOption<Access>[] = [
  { value: 'NONE', label: 'Brak' },
  { value: 'VIEW', label: 'Podgląd' },
  { value: 'EDIT', label: 'Edycja' },
]

type Props = {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Brak = nowa ranga. */
  rank?: StoreRole
  /** Właściciel albo Aderon: może postawić rangę poza hierarchią i dać dowolne uprawnienia. */
  admin: boolean
}

export function RankFormDialog({ storeId, open, onOpenChange, rank, admin }: Props) {
  const { data: categories } = usePermissionCategories()

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-2xl">
        {open && categories && (
          <RankFormBody
            key={rank?.id ?? 'new'}
            storeId={storeId}
            rank={rank}
            admin={admin}
            categories={categories}
            onDone={() => onOpenChange(false)}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

function RankFormBody({
  storeId,
  rank,
  admin,
  categories,
  onDone,
}: {
  storeId: string
  rank?: StoreRole
  admin: boolean
  categories: PermissionCategoryInfo[]
  onDone: () => void
}) {
  const me = useCurrentUser()
  const change = useStoreRoleChange(storeId)
  const [formError, setFormError] = useState<string | null>(null)
  const isAderon = rank?.admin ?? false

  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<RankForm>({
    defaultValues: {
      name: rank?.name ?? '',
      description: rank?.description ?? '',
      outsideHierarchy: rank?.outsideHierarchy ?? false,
      access: Object.fromEntries(categories.map((c) => [c.code, rank?.permissions[c.code] ?? 'NONE'])),
    },
  })

  /** Bez Aderona i właściciela randze daje się najwyżej własne uprawnienia (sprawdza też serwer). */
  const allowed = (category: PermissionCategory, access: Access) => {
    if (admin || access === 'NONE') return true
    const own = me.permissions[category]
    return own === 'EDIT' || (own === 'VIEW' && access === 'VIEW')
  }

  const onSubmit = handleSubmit((values) => {
    setFormError(null)
    const permissions: Permissions = {}
    for (const [category, access] of Object.entries(values.access)) {
      if (access !== 'NONE') permissions[category as PermissionCategory] = access
    }
    const request = {
      name: values.name,
      description: values.description,
      outsideHierarchy: admin ? values.outsideHierarchy : undefined,
      permissions,
    }
    change.mutate(rank ? { type: 'update', id: rank.id, request } : { type: 'create', request }, {
      onSuccess: () => {
        toast.success(rank ? 'Zapisano zmiany rangi' : 'Dodano rangę')
        onDone()
      },
      onError: (error) => setFormError(applyServerErrors(error, setError, ['name', 'description'])),
    })
  })

  return (
    <Form onSubmit={onSubmit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">{rank ? `Ranga: ${rank.name}` : 'Nowa ranga'}</DialogTitle>
        <DialogDescription>
          {isAderon
            ? 'Aderon ma w tym sklepie wszystkie uprawnienia. Można zmienić tylko nazwę i opis.'
            : rank
              ? 'Zmiany działają od razu u wszystkich osób z tą rangą w tym sklepie.'
              : 'Nowa ranga staje nad Pracownikiem, a jej miejsce zmienisz przeciągnięciem na liście.'}
        </DialogDescription>
      </DialogHeader>

      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          label="Nazwa rangi"
          hint="np. Kierownik zmiany"
          autoFocus={!rank}
          error={errors.name?.message}
          {...register('name', {
            required: 'Podaj nazwę rangi',
            maxLength: { value: 100, message: 'Maksymalnie 100 znaków' },
          })}
        />
        <TextField
          label="Opis (opcjonalnie)"
          error={errors.description?.message}
          {...register('description', { maxLength: { value: 500, message: 'Maksymalnie 500 znaków' } })}
        />
      </div>

      {admin && !isAderon && (
        <Controller
          control={control}
          name="outsideHierarchy"
          render={({ field }) => (
            <SwitchField
              label="Poza hierarchią"
              description="Jak Księgowość i Kadry: same uprawnienia, bez nikogo pod sobą i bez zlecania zadań. Taką rangą zarządza Aderon i właściciel."
              checked={field.value}
              onCheckedChange={field.onChange}
            />
          )}
        />
      )}

      {!isAderon && (
        <fieldset className="grid gap-2">
          <legend className="mb-2 text-sm font-medium">Dostęp do narzędzi w tym sklepie</legend>
          <div className="divide-y rounded-xl border">
            {categories.map((category) => (
              <div key={category.code} className="flex flex-wrap items-center justify-between gap-3 px-3 py-2.5">
                <div className="min-w-0">
                  <div className="text-sm font-medium">{category.label}</div>
                  <div className="text-xs text-muted-foreground">{category.description}</div>
                </div>
                <Controller
                  control={control}
                  name={`access.${category.code}`}
                  render={({ field }) => (
                    <SegmentedField
                      label={`Dostęp: ${category.label}`}
                      options={ACCESS_OPTIONS.map((option) => ({
                        ...option,
                        disabled: !allowed(category.code, option.value),
                      }))}
                      value={field.value}
                      onChange={field.onChange}
                    />
                  )}
                />
              </div>
            ))}
          </div>
          <div className="space-y-1 px-1 text-xs text-muted-foreground">
            <p>
              <span className="font-medium text-foreground">Podgląd</span> wystarczy do codziennej pracy narzędziem:
              wyszukiwanie, przeglądanie, wydruki i pliki dla programu sklepu.
            </p>
            <p>
              <span className="font-medium text-foreground">Edycja</span> to dodatkowo zmiana wspólnych danych i
              ustawień narzędzia. Randze dajesz najwyżej takie uprawnienia, jakie masz sam.
            </p>
          </div>
        </fieldset>
      )}

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onDone}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={change.isPending}>
          {rank ? 'Zapisz zmiany' : 'Dodaj rangę'}
        </Button>
      </FormActions>
    </Form>
  )
}
