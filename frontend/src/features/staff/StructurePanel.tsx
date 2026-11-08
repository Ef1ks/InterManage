import { useState } from 'react'
import { PencilIcon, PlusIcon, Trash2Icon } from 'lucide-react'
import { Controller, useForm } from 'react-hook-form'
import { toast } from 'sonner'

import { Form, FormActions, SelectField, TextField } from '@/components/form'
import { FullPageMessage } from '@/components/FullPageMessage'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCan } from '@/features/auth/api'
import { useStaffRoles, useStaffStructure, useStructureActions, type PositionForm } from '@/features/staff/api'
import { peopleWord, weekSummary } from '@/features/staff/shifts'
import { ShiftPicker } from '@/features/staff/ShiftPicker'
import { StoreSwitch } from '@/features/staff/StoreSwitch'
import { ApiError } from '@/lib/api'
import { applyServerErrors } from '@/lib/form-errors'
import type { StaffDepartment, StaffPosition, StoreSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

type DepartmentEditor = { open: boolean; department?: StaffDepartment }
type PositionEditor = { open: boolean; departmentId?: string; position?: StaffPosition }

/**
 * Działy firmy i ich stanowiska, wspólne dla jej sklepów. Rola stanowiska jest jedna, a godziny do wyboru każdy sklep
 * ma swoje, dlatego przy kilku sklepach panel pokazuje je dla wybranego.
 */
export function StructurePanel({ storeId: currentStoreId, stores }: { storeId: string; stores: StoreSummary[] }) {
  const canEdit = useCan('STAFF', 'EDIT')
  const [storeId, setStoreId] = useState(currentStoreId)
  const { data: departments, isError, refetch } = useStaffStructure(storeId)
  const { removeDepartment, restoreDepartment, removePosition, restorePosition } = useStructureActions(storeId)
  const [departmentEditor, setDepartmentEditor] = useState<DepartmentEditor>({ open: false })
  const [positionEditor, setPositionEditor] = useState<PositionEditor>({ open: false })

  const onError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać zmiany')

  if (isError) {
    return (
      <FullPageMessage
        title="Nie udało się wczytać działów"
        action={{ label: 'Spróbuj ponownie', onClick: () => void refetch() }}
        inline
      />
    )
  }
  if (!departments) {
    return <div className="h-40 animate-pulse rounded-xl bg-muted" aria-busy />
  }

  const deleteDepartment = (department: StaffDepartment) =>
    removeDepartment.mutate(department.id, {
      onSuccess: () =>
        toast.success(`Usunięto dział ${department.name}`, {
          action: { label: 'Przywróć', onClick: () => restoreDepartment.mutate(department.id, { onError }) },
        }),
      onError,
    })

  const deletePosition = (position: StaffPosition) =>
    removePosition.mutate(position.id, {
      onSuccess: () =>
        toast.success(`Usunięto stanowisko ${position.name}`, {
          action: { label: 'Przywróć', onClick: () => restorePosition.mutate(position.id, { onError }) },
        }),
      onError,
    })

  const positionCount = departments.reduce((sum, department) => sum + department.positions.length, 0)
  const peopleCount = departments.reduce(
    (sum, department) => sum + department.positions.reduce((inner, position) => inner + position.employeeCount, 0),
    0,
  )

  return (
    <>
      <div className="mb-3 flex flex-wrap items-center gap-x-4 gap-y-2">
        {departments.length > 0 && (
          <p className="text-sm">
            <span className="font-medium tabular-nums">
              {departments.length}{' '}
              {departments.length === 1 ? 'dział' : plural(departments.length, 'działy', 'działów')}
            </span>
            <span className="text-muted-foreground">
              {' '}
              · {positionCount} {positionCount === 1 ? 'stanowisko' : plural(positionCount, 'stanowiska', 'stanowisk')}{' '}
              · {peopleCount} {peopleWord(peopleCount)}
            </span>
          </p>
        )}
        <div className="flex w-full flex-wrap items-center gap-2 sm:ml-auto sm:w-auto">
          {stores.length > 1 && (
            <StoreSwitch label="Godziny w sklepie" stores={stores} value={storeId} onChange={setStoreId} />
          )}
          {canEdit && (
            <Button
              onClick={() => setDepartmentEditor({ open: true })}
              className="h-10 w-full rounded-xl px-4 sm:w-auto"
            >
              <PlusIcon />
              Dodaj dział
            </Button>
          )}
        </div>
      </div>

      {departments.length === 0 ? (
        <div className="rounded-2xl border bg-card px-6 py-14 text-center">
          <p className="font-medium">Nie ma jeszcze działów</p>
          <p className="mt-1 text-sm text-muted-foreground">
            Załóż pierwszy dział, np. Kasy albo Garmaż, a w nim stanowiska. Działy są wspólne dla sklepów firmy.
          </p>
        </div>
      ) : (
        <>
          <div className="overflow-hidden rounded-2xl border bg-card">
            <div
              aria-hidden
              className={cn(
                'hidden gap-x-4 border-b px-4 py-2.5 text-xs text-muted-foreground md:grid',
                canEdit ? POSITION_COLUMNS : POSITION_COLUMNS_READ,
              )}
            >
              <span>Stanowisko</span>
              <span>Rola w aplikacji</span>
              <span className="text-right">Osoby</span>
              <span>Godziny do wyboru{stores.length > 1 ? ' w tym sklepie' : ''}</span>
            </div>
            {departments.map((department) => (
              <section key={department.id} aria-label={department.name}>
                <div className="flex min-h-11 items-center gap-2 bg-muted/60 px-4 py-1.5">
                  <h2 className="min-w-0 truncate text-sm font-medium">{department.name}</h2>
                  <span className="shrink-0 text-xs text-muted-foreground tabular-nums">
                    {department.positions.length}{' '}
                    {department.positions.length === 1
                      ? 'stanowisko'
                      : plural(department.positions.length, 'stanowiska', 'stanowisk')}
                  </span>
                  {canEdit && (
                    <div className="ml-auto flex shrink-0 items-center gap-0.5">
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => setPositionEditor({ open: true, departmentId: department.id })}
                        aria-label={`Dodaj stanowisko w dziale ${department.name}`}
                      >
                        <PlusIcon />
                        <span className="sr-only sm:not-sr-only">Stanowisko</span>
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        aria-label={`Zmień nazwę działu ${department.name}`}
                        title="Zmień nazwę"
                        onClick={() => setDepartmentEditor({ open: true, department })}
                        className="text-muted-foreground hover:text-foreground"
                      >
                        <PencilIcon />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        aria-label={`Usuń dział ${department.name}`}
                        title="Usuń dział"
                        onClick={() => deleteDepartment(department)}
                        className="text-muted-foreground hover:text-foreground"
                      >
                        <Trash2Icon />
                      </Button>
                    </div>
                  )}
                </div>
                {department.positions.length === 0 ? (
                  <p className="px-4 py-3 text-sm text-muted-foreground">Dział nie ma jeszcze stanowisk.</p>
                ) : (
                  <ul className="divide-y">
                    {department.positions.map((position) => (
                      <PositionRow
                        key={position.id}
                        position={position}
                        canEdit={canEdit}
                        onEdit={() => setPositionEditor({ open: true, position })}
                        onDelete={() => deletePosition(position)}
                      />
                    ))}
                  </ul>
                )}
              </section>
            ))}
          </div>
        </>
      )}

      <DepartmentDialog
        storeId={storeId}
        editor={departmentEditor}
        onClose={() => setDepartmentEditor((current) => ({ ...current, open: false }))}
      />
      <PositionDialog
        storeId={storeId}
        hoursStore={stores.length > 1 ? stores.find((store) => store.id === storeId) : undefined}
        editor={positionEditor}
        departments={departments}
        onClose={() => setPositionEditor((current) => ({ ...current, open: false }))}
      />
    </>
  )
}

/** Kolumny jak na liście pracowników: nazwa, rola, liczba osób, godziny i stała kolumna na dwie ikony. */
const POSITION_COLUMNS = 'md:grid-cols-[minmax(0,1.1fr)_minmax(0,0.9fr)_4rem_minmax(0,1.3fr)_4.25rem]'
const POSITION_COLUMNS_READ = 'md:grid-cols-[minmax(0,1.1fr)_minmax(0,0.9fr)_4rem_minmax(0,1.3fr)]'

const plural = (count: number, few: string, many: string) =>
  [2, 3, 4].includes(count % 10) && ![12, 13, 14].includes(count % 100) ? few : many

function PositionRow({
  position,
  canEdit,
  onEdit,
  onDelete,
}: {
  position: StaffPosition
  canEdit: boolean
  onEdit: () => void
  onDelete: () => void
}) {
  return (
    <li
      className={cn(
        'grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-1 px-4 py-3',
        canEdit ? POSITION_COLUMNS : POSITION_COLUMNS_READ,
      )}
    >
      <div className="min-w-0">
        <div className="truncate font-medium">{position.name}</div>
        {/* Na telefonie rola i liczba osób idą pod nazwą, bo kolumn nie ma. */}
        <div className="truncate text-xs text-muted-foreground md:hidden">
          rola {position.roleName} · {position.employeeCount} {peopleWord(position.employeeCount)}
        </div>
      </div>
      <div className="hidden min-w-0 truncate text-sm md:block">{position.roleName}</div>
      <div
        className={cn(
          'hidden text-right text-sm tabular-nums md:block',
          position.employeeCount === 0 && 'text-muted-foreground',
        )}
      >
        {position.employeeCount}
      </div>
      <div className="col-start-1 row-start-2 min-w-0 text-sm tabular-nums md:col-start-4 md:row-start-1">
        {position.shifts.length === 0 ? (
          <span className="text-muted-foreground">Bez godzin do wyboru</span>
        ) : (
          weekSummary(position.shifts).map((line) => (
            <div key={line.days} className="truncate">
              <span className="text-xs text-muted-foreground">{line.days}</span> {line.hours}
            </div>
          ))
        )}
      </div>
      {canEdit && (
        <div className="col-start-2 row-span-2 row-start-1 flex justify-end gap-0.5 md:col-start-5 md:row-span-1">
          <Button
            variant="ghost"
            size="icon"
            aria-label={`Edytuj stanowisko ${position.name}`}
            title="Edytuj"
            onClick={onEdit}
            className="text-muted-foreground hover:text-foreground"
          >
            <PencilIcon />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            aria-label={`Usuń stanowisko ${position.name}`}
            title="Usuń"
            onClick={onDelete}
            className="text-muted-foreground hover:text-foreground"
          >
            <Trash2Icon />
          </Button>
        </div>
      )}
    </li>
  )
}

function DepartmentDialog({
  storeId,
  editor,
  onClose,
}: {
  storeId: string
  editor: DepartmentEditor
  onClose: () => void
}) {
  return (
    <Dialog open={editor.open} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="gap-5 p-6 sm:max-w-sm">
        {editor.open && (
          <DepartmentForm
            key={editor.department?.id ?? 'new'}
            storeId={storeId}
            department={editor.department}
            onDone={onClose}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

function DepartmentForm({
  storeId,
  department,
  onDone,
}: {
  storeId: string
  department?: StaffDepartment
  onDone: () => void
}) {
  const { saveDepartment } = useStructureActions(storeId)
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<{ name: string }>({ defaultValues: { name: department?.name ?? '' } })

  const onSubmit = handleSubmit(({ name }) => {
    setFormError(null)
    saveDepartment.mutate(
      { id: department?.id, name },
      { onSuccess: onDone, onError: (error) => setFormError(applyServerErrors(error, setError, ['name'])) },
    )
  })

  return (
    <Form onSubmit={onSubmit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">{department ? 'Nazwa działu' : 'Nowy dział'}</DialogTitle>
        <DialogDescription>Dział grupuje stanowiska i jest wspólny dla wszystkich sklepów firmy.</DialogDescription>
      </DialogHeader>
      <TextField
        label="Nazwa"
        autoComplete="off"
        autoFocus
        error={errors.name?.message}
        {...register('name', {
          required: 'Podaj nazwę działu',
          maxLength: { value: 80, message: 'Maksymalnie 80 znaków' },
        })}
      />
      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onDone}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={saveDepartment.isPending}>
          Zapisz
        </Button>
      </FormActions>
    </Form>
  )
}

function PositionDialog({
  storeId,
  hoursStore,
  editor,
  departments,
  onClose,
}: {
  storeId: string
  hoursStore?: StoreSummary
  editor: PositionEditor
  departments: StaffDepartment[]
  onClose: () => void
}) {
  return (
    <Dialog open={editor.open} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="gap-5 p-6 sm:max-w-lg">
        {editor.open && (
          <PositionFormBody
            key={editor.position?.id ?? 'new'}
            storeId={storeId}
            hoursStore={hoursStore}
            editor={editor}
            departments={departments}
            onDone={onClose}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

function PositionFormBody({
  storeId,
  hoursStore,
  editor,
  departments,
  onDone,
}: {
  storeId: string
  hoursStore?: StoreSummary
  editor: PositionEditor
  departments: StaffDepartment[]
  onDone: () => void
}) {
  const { data: roles = [], isSuccess: rolesLoaded } = useStaffRoles(storeId)
  const { savePosition } = useStructureActions(storeId)
  const [formError, setFormError] = useState<string | null>(null)
  const { position } = editor
  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<PositionForm>({
    defaultValues: {
      departmentId: position?.departmentId ?? editor.departmentId ?? '',
      name: position?.name ?? '',
      roleId: position?.roleId ?? '',
      shifts: position?.shifts ?? [],
    },
  })

  const onSubmit = handleSubmit((form) => {
    setFormError(null)
    savePosition.mutate(
      { id: position?.id, form },
      {
        onSuccess: () => {
          toast.success(position ? 'Zapisano stanowisko' : 'Dodano stanowisko')
          onDone()
        },
        onError: (error) => setFormError(applyServerErrors(error, setError, ['departmentId', 'name', 'roleId'])),
      },
    )
  })

  const roleOptions = roles
    .filter((role) => role.grantable || role.id === position?.roleId)
    .map((role) => ({ value: role.id, label: role.name }))

  return (
    <Form onSubmit={onSubmit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">{position ? position.name : 'Nowe stanowisko'}</DialogTitle>
        <DialogDescription>
          Rola daje uprawnienia w aplikacji wszystkim pracownikom na tym stanowisku.
        </DialogDescription>
      </DialogHeader>

      <TextField
        label="Nazwa stanowiska"
        autoComplete="off"
        autoFocus={!position}
        error={errors.name?.message}
        {...register('name', {
          required: 'Podaj nazwę stanowiska',
          maxLength: { value: 80, message: 'Maksymalnie 80 znaków' },
        })}
      />
      <Controller
        control={control}
        name="departmentId"
        rules={{ required: 'Wybierz dział' }}
        render={({ field }) => (
          <SelectField
            label="Dział"
            options={departments.map((department) => ({ value: department.id, label: department.name }))}
            value={field.value}
            onChange={field.onChange}
            onBlur={field.onBlur}
            error={errors.departmentId?.message}
          />
        )}
      />
      <Controller
        control={control}
        name="roleId"
        rules={{ required: 'Wybierz rolę' }}
        render={({ field }) => (
          <SelectField
            label="Rola"
            options={roleOptions}
            value={field.value}
            onChange={field.onChange}
            onBlur={field.onBlur}
            error={errors.roleId?.message}
            hint={
              rolesLoaded && roleOptions.length === 0
                ? 'Nie ma ról do wyboru. Właściciel zakłada je w Ustawieniach, w zakładce Role.'
                : position && position.employeeCount > 0
                  ? 'Zmiana roli od razu zmieni uprawnienia pracowników na tym stanowisku.'
                  : undefined
            }
          />
        )}
      />

      <div className="grid gap-2">
        <p className="text-sm text-muted-foreground">
          {hoursStore ? `Godziny pracy do wyboru w sklepie ${hoursStore.number}` : 'Godziny pracy do wyboru'}
        </p>
        <Controller
          control={control}
          name="shifts"
          render={({ field }) => (
            <ShiftPicker value={field.value} onChange={field.onChange} addLabel="Dodaj godziny" addForWholeWeek />
          )}
        />
      </div>

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onDone}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={savePosition.isPending}>
          Zapisz
        </Button>
      </FormActions>
    </Form>
  )
}
