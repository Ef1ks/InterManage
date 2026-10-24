import { useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { toast } from 'sonner'

import { Form, FormActions, SegmentedField, TextField, type SegmentOption } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useSaveRole } from '@/features/settings/api'
import { usePermissionCategories } from '@/features/tools/categories'
import { applyServerErrors } from '@/lib/form-errors'
import type { PermissionCategory, PermissionCategoryInfo, PermissionLevel, Permissions, Role } from '@/lib/types'

type Access = 'NONE' | PermissionLevel

type RoleForm = {
  name: string
  description: string
  access: Record<string, Access>
}

const ACCESS_OPTIONS: SegmentOption<Access>[] = [
  { value: 'NONE', label: 'Brak' },
  { value: 'VIEW', label: 'Podgląd' },
  { value: 'EDIT', label: 'Edycja' },
]

type Props = {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Brak = nowa rola. */
  role?: Role
}

export function RoleFormDialog({ open, onOpenChange, role }: Props) {
  const { data: categories } = usePermissionCategories()

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-2xl">
        {/* Klucz resetuje formularz przy każdym otwarciu dla innej roli. */}
        {open && categories && (
          <RoleFormBody
            key={role?.id ?? 'new'}
            role={role}
            categories={categories}
            onDone={() => onOpenChange(false)}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

type BodyProps = {
  role?: Role
  categories: PermissionCategoryInfo[]
  onDone: () => void
}

function RoleFormBody({ role, categories, onDone }: BodyProps) {
  const saveRole = useSaveRole()
  const [formError, setFormError] = useState<string | null>(null)

  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<RoleForm>({
    defaultValues: {
      name: role?.name ?? '',
      description: role?.description ?? '',
      access: Object.fromEntries(categories.map((c) => [c.code, role?.permissions[c.code] ?? 'NONE'])),
    },
  })

  const onSubmit = handleSubmit((values) => {
    setFormError(null)
    const permissions: Permissions = {}
    for (const [category, access] of Object.entries(values.access)) {
      if (access !== 'NONE') permissions[category as PermissionCategory] = access
    }
    saveRole.mutate(
      { id: role?.id, request: { name: values.name, description: values.description, permissions } },
      {
        onSuccess: () => {
          toast.success(role ? 'Zapisano zmiany roli' : 'Utworzono rolę')
          onDone()
        },
        onError: (error) => setFormError(applyServerErrors(error, setError, ['name', 'description'])),
      },
    )
  })

  return (
    <Form onSubmit={onSubmit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">{role ? 'Edycja roli' : 'Nowa rola'}</DialogTitle>
        <DialogDescription>
          Zmiany uprawnień działają od razu u wszystkich zalogowanych użytkowników z tą rolą.
        </DialogDescription>
      </DialogHeader>

      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          label="Nazwa roli"
          hint="np. Kierownik działu"
          autoFocus={!role}
          error={errors.name?.message}
          {...register('name', {
            required: 'Podaj nazwę roli',
            maxLength: { value: 100, message: 'Maksymalnie 100 znaków' },
          })}
        />
        <TextField
          label="Opis (opcjonalnie)"
          error={errors.description?.message}
          {...register('description', { maxLength: { value: 500, message: 'Maksymalnie 500 znaków' } })}
        />
      </div>

      <fieldset className="grid gap-2">
        <legend className="mb-2 text-sm font-medium">Dostęp do narzędzi</legend>
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
                    options={ACCESS_OPTIONS}
                    value={field.value}
                    onChange={field.onChange}
                  />
                )}
              />
            </div>
          ))}
        </div>
        <p className="px-1 text-xs text-muted-foreground">Edycja obejmuje też podgląd.</p>
      </fieldset>

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onDone}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={saveRole.isPending}>
          {role ? 'Zapisz zmiany' : 'Utwórz rolę'}
        </Button>
      </FormActions>
    </Form>
  )
}
