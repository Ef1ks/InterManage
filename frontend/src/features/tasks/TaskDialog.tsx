import { useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { LinkIcon, ListPlusIcon, XIcon } from 'lucide-react'
import { toast } from 'sonner'

import {
  DateField,
  Form,
  FormActions,
  FormNote,
  SegmentedField,
  SelectField,
  TextareaField,
  TextField,
} from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { CatalogListDialog } from '@/features/data-entry/CatalogListDialog'
import {
  MAX_TASK_ITEMS,
  type TaskItemDraft,
  type TaskSource,
  useCreateTask,
  useTaskActions,
  useTaskAssignees,
  useTaskAttachmentActions,
  useUpdateTask,
} from '@/features/tasks/api'
import { DraftAttachments, TaskAttachments } from '@/features/tasks/TaskAttachments'
import { localShot, useTaskSource, type LocalShot } from '@/features/tasks/task-helpers'
import { addDays, todayIso } from '@/lib/dates'
import { applyServerErrors } from '@/lib/form-errors'
import type { StoreTask, TaskPriority } from '@/lib/types'

const LAST_ASSIGNEE_KEY = 'intermanage.taskAssignee'

/** Co zlecić: z raportu przychodzi tytuł, pozycje (artykuły z danymi z chwili zlecenia) i widok raportu. */
export type TaskDraft = {
  title?: string
  items?: TaskItemDraft[]
  source?: TaskSource | null
  /** Zrzuty zrobione przed otwarciem okna (szybki zrzut z rogu ekranu). */
  shots?: Blob[]
}

type Values = {
  title: string
  description: string
  assigneeId: string
  dueDate: string
  priority: TaskPriority
}

const PRIORITIES = [
  { value: 'NORMAL' as const, label: 'Zwykłe' },
  { value: 'URGENT' as const, label: 'Pilne' },
]

/**
 * Zlecenie zadania (albo jego zmiana, gdy podano {@code task}). Pozycje i widok raportu przychodzą z miejsca, z którego
 * zlecono; przy nowym zadaniu można dołożyć artykuły z listy towarów i usunąć zbędne pozycje.
 */
export function TaskDialog({
  storeId,
  open,
  onOpenChange,
  draft,
  task,
}: {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  draft?: TaskDraft | null
  task?: StoreTask | null
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-xl">
        {open && (
          <TaskForm storeId={storeId} draft={draft ?? null} task={task ?? null} onDone={() => onOpenChange(false)} />
        )}
      </DialogContent>
    </Dialog>
  )
}

function readLastAssignee() {
  try {
    return localStorage.getItem(LAST_ASSIGNEE_KEY) ?? ''
  } catch {
    return ''
  }
}

function TaskForm({
  storeId,
  draft,
  task,
  onDone,
}: {
  storeId: string
  draft: TaskDraft | null
  task: StoreTask | null
  onDone: () => void
}) {
  const assignees = useTaskAssignees(storeId, true)
  const create = useCreateTask(storeId)
  const update = useUpdateTask(storeId)
  const { remove, restore } = useTaskActions(storeId)
  const attachments = useTaskAttachmentActions(storeId)
  const [shots, setShots] = useState<LocalShot[]>(() => (draft?.shots ?? []).map(localShot))
  // Każde zadanie wskazuje miejsce, w którym je zlecono: bez źródła z raportu to strona, na której jesteśmy.
  const pageSource = useTaskSource('PAGE')
  const newSource = draft?.source ?? pageSource
  const [items, setItems] = useState<TaskItemDraft[]>(() => draft?.items?.slice(0, MAX_TASK_ITEMS) ?? [])
  const [picking, setPicking] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)
  const source = task
    ? task.sourceUrl
      ? { label: task.sourceLabel ?? 'raport', url: task.sourceUrl }
      : null
    : newSource
  const tooMany = (draft?.items?.length ?? 0) > MAX_TASK_ITEMS

  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<Values>({
    defaultValues: task
      ? {
          title: task.title,
          description: task.description ?? '',
          assigneeId: task.assignee.id,
          dueDate: task.dueDate ?? '',
          priority: task.priority,
        }
      : {
          title: draft?.title ?? '',
          description: '',
          assigneeId: readLastAssignee(),
          dueDate: '',
          priority: 'NORMAL',
        },
  })

  const assigneeOptions = (assignees.data ?? []).map((person) => ({
    value: person.id,
    label: `${person.displayName} · ${person.roleName}`,
  }))

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null)
    // Zapamiętany wykonawca mógł stracić dostęp do sklepu: wtedy pole jest puste, a wybór trzeba zrobić od nowa.
    if (!assigneeOptions.some((option) => option.value === values.assigneeId)) {
      setError('assigneeId', { message: 'Wybierz, kto ma to zrobić' })
      return
    }
    const input = {
      title: values.title.trim(),
      description: values.description.trim() || null,
      dueDate: values.dueDate || null,
      priority: values.priority,
      assigneeId: values.assigneeId,
    }
    try {
      if (task) {
        await update.mutateAsync({ id: task.id, input })
        toast.success('Zapisano zadanie')
      } else {
        const created = await create.mutateAsync({
          ...input,
          sourceKind: newSource.kind,
          sourceLabel: newSource.label,
          sourceUrl: newSource.url,
          items,
        })
        // Zrzuty idą po zleceniu: zadanie już jest, więc nieudany zrzut nie cofa zlecenia.
        let failedShots = 0
        for (const shot of shots) {
          try {
            await attachments.add.mutateAsync({ taskId: created.id, file: shot.blob })
          } catch {
            failedShots += 1
          }
        }
        if (failedShots > 0) {
          toast.error(`Nie udało się dołączyć ${failedShots} z ${shots.length} zrzutów`, {
            description: 'Dołącz je jeszcze raz w zadaniu na pulpicie.',
          })
        }
        try {
          localStorage.setItem(LAST_ASSIGNEE_KEY, values.assigneeId)
        } catch {
          // Bez zapamiętania następne okno zacznie od pustego wyboru.
        }
        toast.success(`Zlecono: ${created.assignee.displayName}`, {
          description: created.title,
          action: {
            label: 'Cofnij',
            onClick: () =>
              remove.mutate(created.id, {
                onSuccess: () =>
                  toast('Usunięto zadanie', {
                    action: { label: 'Przywróć', onClick: () => restore.mutate(created.id) },
                  }),
              }),
          },
        })
      }
      onDone()
    } catch (error) {
      setFormError(applyServerErrors(error, setError, ['title', 'description', 'assigneeId', 'dueDate']))
    }
  })

  const inTable = new Set(items.flatMap((item) => (item.ean ? [item.ean.replace(/^0+/, '')] : [])))

  return (
    <Form onSubmit={onSubmit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">{task ? 'Zmień zadanie' : 'Zleć zadanie'}</DialogTitle>
        <DialogDescription>
          {task
            ? 'Pozycje i raport zostają takie, jak przy zleceniu.'
            : 'Zadanie pojawi się na pulpicie wykonawcy i w jego telefonie.'}
        </DialogDescription>
      </DialogHeader>

      <TextField
        label="Co trzeba zrobić"
        autoFocus
        error={errors.title?.message}
        {...register('title', {
          validate: (value) => value.trim() !== '' || 'Wpisz, co trzeba zrobić',
          maxLength: { value: 200, message: 'Najwyżej 200 znaków' },
        })}
      />
      <TextareaField
        label="Opis (opcjonalnie)"
        rows={2}
        error={errors.description?.message}
        {...register('description', { maxLength: { value: 4000, message: 'Najwyżej 4000 znaków' } })}
      />
      <Controller
        control={control}
        name="assigneeId"
        rules={{ validate: (value) => value !== '' || 'Wybierz, kto ma to zrobić' }}
        render={({ field, fieldState }) => (
          <SelectField
            label={assignees.isPending ? 'Kto (wczytuję…)' : 'Kto ma to zrobić'}
            options={assigneeOptions}
            value={assigneeOptions.some((option) => option.value === field.value) ? field.value : ''}
            onChange={field.onChange}
            onBlur={field.onBlur}
            error={fieldState.error?.message}
          />
        )}
      />
      <div className="grid items-start gap-4 sm:grid-cols-[1fr_auto]">
        <Controller
          control={control}
          name="dueDate"
          render={({ field }) => (
            <DateField
              label="Termin (opcjonalnie)"
              value={field.value}
              onChange={field.onChange}
              min={todayIso()}
              error={errors.dueDate?.message}
              shortcuts={[
                { label: 'Dziś', value: todayIso() },
                { label: 'Jutro', value: addDays(todayIso(), 1) },
                { label: 'Za tydzień', value: addDays(todayIso(), 7) },
              ]}
            />
          )}
        />
        <Controller
          control={control}
          name="priority"
          render={({ field }) => (
            <SegmentedField
              label="Priorytet"
              options={PRIORITIES}
              value={field.value}
              onChange={field.onChange}
              className="sm:mt-2"
            />
          )}
        />
      </div>

      {!task && (
        <div className="grid gap-2">
          <div className="flex items-center justify-between gap-2">
            <p className="text-sm font-medium">Pozycje do odhaczenia{items.length > 0 ? ` (${items.length})` : ''}</p>
            <Button type="button" variant="ghost" size="sm" onClick={() => setPicking(true)}>
              <ListPlusIcon />
              Dodaj artykuły
            </Button>
          </div>
          {items.length > 0 ? (
            <ul className="max-h-56 divide-y overflow-y-auto rounded-lg border text-sm">
              {items.map((item, index) => (
                <li key={`${item.itm8 ?? item.name}-${index}`} className="flex items-center gap-2 px-3 py-1.5">
                  <span className="min-w-0 flex-1">
                    <span className="block truncate">{item.name}</span>
                    {item.details && (
                      <span className="block truncate text-xs text-muted-foreground">{item.details}</span>
                    )}
                  </span>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    aria-label={`Usuń ${item.name}`}
                    onClick={() => setItems((current) => current.filter((_, at) => at !== index))}
                  >
                    <XIcon />
                  </Button>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-sm text-muted-foreground">Bez pozycji wykonawca odhacza całe zadanie naraz.</p>
          )}
          {tooMany && (
            <p className="text-sm text-amber-700 dark:text-amber-400">
              Widok ma więcej artykułów niż {MAX_TASK_ITEMS}. Wzięto pierwsze {MAX_TASK_ITEMS}; zawęź filtr w raporcie,
              żeby zlecić resztę osobno.
            </p>
          )}
        </div>
      )}

      {task ? (
        <TaskAttachments storeId={storeId} task={task} />
      ) : (
        <DraftAttachments shots={shots} onChange={setShots} />
      )}

      {source && (
        <FormNote icon={<LinkIcon />}>
          Wykonawca otworzy z zadania widok „{source.label}” z tymi samymi filtrami.
        </FormNote>
      )}

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onDone}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={isSubmitting}>
          {task ? 'Zapisz' : 'Zleć'}
        </Button>
      </FormActions>

      {picking && (
        <CatalogListDialog
          storeId={storeId}
          open={picking}
          onOpenChange={setPicking}
          inTable={inTable}
          lastSaleDate={null}
          title="Dodaj artykuły do zadania"
          onPick={(article) => {
            if (items.length >= MAX_TASK_ITEMS) {
              toast.error(`Zadanie może mieć najwyżej ${MAX_TASK_ITEMS} pozycji`)
              return
            }
            if (items.some((item) => item.itm8 === article.itm8)) {
              toast(`${article.name ?? article.itm8} już jest w zadaniu`)
              return
            }
            setItems((current) => [
              ...current,
              {
                itm8: article.itm8,
                ean: article.ean ?? null,
                name: [article.name, article.brand].filter(Boolean).join(' · ') || article.itm8,
                details: article.stock !== null && article.stock !== undefined ? `stan ${article.stock}` : null,
              },
            ])
            toast.success(`Dodano: ${article.name ?? article.itm8}`)
          }}
        />
      )}
    </Form>
  )
}
