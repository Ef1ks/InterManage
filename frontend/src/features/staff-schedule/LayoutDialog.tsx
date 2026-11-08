import { Fragment, useRef, useState } from 'react'
import {
  closestCenter,
  type CollisionDetection,
  DndContext,
  type DragEndEvent,
  type DragStartEvent,
  KeyboardSensor,
  MeasuringStrategy,
  type Modifier,
  PointerSensor,
  useSensor,
  useSensors,
} from '@dnd-kit/core'
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { GripVerticalIcon, PlusIcon, Trash2Icon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { useSaveScheduleLayout, type ScheduleLayout, type ScheduleScope } from '@/features/staff-schedule/api'
import { peopleWord } from '@/features/staff/shifts'
import { ApiError } from '@/lib/api'
import type { Schedule } from '@/lib/types'
import { cn } from '@/lib/utils'

type Person = { id: string; name: string; hint: string }
type Group = { key: string; id: string | null; name: string; people: Person[] }

/** Pracownik należy do grupy jako dana, a nie przez miejsce na liście, więc nagłówek nie stanie między jej pracownikami. */
type Draft = { ungrouped: Person[]; groups: Group[] }

const UNGROUPED = 'ungrouped'
const GROUPS = 'groups'

type DragData = { scope: string }

/** Wiersz przenosi się tylko wśród swoich: grupa wśród grup, pracownik w swojej grupie. */
const sameScope: CollisionDetection = (args) => {
  const scope = (args.active.data.current as DragData | undefined)?.scope
  return closestCenter({
    ...args,
    droppableContainers: args.droppableContainers.filter(
      (container) => (container.data.current as DragData | undefined)?.scope === scope,
    ),
  })
}

/**
 * Przeciągany wiersz zostaje między swoimi; bez tego powiększałby przewijany obszar bez końca. Granice z {@code offsetTop},
 * bo nie zmieniają go ani transformacje rozsuwanych wierszy, ani przewijanie.
 */
const insideScope: Modifier = ({ transform, active }) => {
  const scope = (active?.data.current as DragData | undefined)?.scope
  const rows = [...document.querySelectorAll<HTMLElement>(`[data-drag-scope="${scope}"]`)]
  const current = rows.find((row) => row.dataset.dragKey === String(active?.id))
  if (!current) return { ...transform, x: 0 }
  const min = Math.min(...rows.map((row) => row.offsetTop)) - current.offsetTop
  const max =
    Math.max(...rows.map((row) => row.offsetTop + row.offsetHeight)) - current.offsetTop - current.offsetHeight
  return { ...transform, x: 0, y: Math.min(Math.max(transform.y, min), max) }
}

/** Bez własnych grup okno startuje od tego, co widać w tabeli: podziału na stanowiska. */
function startingDraft(schedule: Schedule): Draft {
  const team = schedule.rows.filter((row) => !row.guest && !row.civil)
  const person = (row: Schedule['rows'][number]): Person => ({
    id: row.employeeId,
    name: row.name,
    hint: row.positionName,
  })
  if (schedule.groups.length === 0) {
    return {
      ungrouped: [],
      groups: schedule.positions.flatMap((position) => {
        const people = team.filter((row) => row.positionId === position.id).map(person)
        return people.length === 0 ? [] : [{ key: `position-${position.id}`, id: null, name: position.name, people }]
      }),
    }
  }
  return {
    ungrouped: team.filter((row) => row.groupId === null).map(person),
    groups: schedule.groups.map((group) => ({
      key: group.id,
      id: group.id,
      name: group.name,
      people: team.filter((row) => row.groupId === group.id).map(person),
    })),
  }
}

function layoutOf(draft: Draft): ScheduleLayout {
  return {
    ungrouped: draft.ungrouped.map((person) => person.id),
    groups: draft.groups.map((group) => ({
      id: group.id,
      name: group.name.trim(),
      employeeIds: group.people.map((person) => person.id),
    })),
  }
}

/** Pracownik przeniesiony wyborem przy wierszu trafia na koniec wskazanej grupy. */
function moveToGroup(draft: Draft, person: Person, target: string): Draft {
  const without = (people: Person[]) => people.filter((other) => other.id !== person.id)
  return {
    ungrouped: target === UNGROUPED ? [...without(draft.ungrouped), person] : without(draft.ungrouped),
    groups: draft.groups.map((group) => ({
      ...group,
      people: group.key === target ? [...without(group.people), person] : without(group.people),
    })),
  }
}

/** Układ w tabeli przed zmianą, do „Cofnij”. Bez własnych grup cofnięcie czyści grupy i kolejność. */
function currentLayout(schedule: Schedule): ScheduleLayout {
  const team = schedule.rows.filter((row) => !row.guest && !row.civil)
  return {
    ungrouped: team.filter((row) => row.groupId === null).map((row) => row.employeeId),
    groups: schedule.groups.map((group) => ({
      id: null,
      name: group.name,
      employeeIds: team.filter((row) => row.groupId === group.id).map((row) => row.employeeId),
    })),
  }
}

export function LayoutDialog({
  open,
  scope,
  schedule,
  onClose,
}: {
  open: boolean
  scope: ScheduleScope
  schedule: Schedule
  onClose: () => void
}) {
  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent className="gap-4 p-6 sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>Grupy i kolejność</DialogTitle>
          <DialogDescription>
            Przeciągaj grupy i pracowników w ich grupach. Do innej grupy przenosisz pracownika wyborem przy jego
            wierszu. Układ dotyczy tego działu w tym sklepie i każdego miesiąca.
          </DialogDescription>
        </DialogHeader>
        {open && <LayoutEditor scope={scope} schedule={schedule} onDone={onClose} />}
      </DialogContent>
    </Dialog>
  )
}

function LayoutEditor({ scope, schedule, onDone }: { scope: ScheduleScope; schedule: Schedule; onDone: () => void }) {
  const save = useSaveScheduleLayout(scope)
  const [draft, setDraft] = useState(() => startingDraft(schedule))
  // Przy przenoszeniu grupy lista zwija się do nagłówków.
  const [groupDrag, setGroupDrag] = useState(false)
  // Skalowany rem daje ułamki piksela i przeciągany wiersz wystaje o 1 px, a to wystarcza na pasek przewijania. Lista, która
  // się mieści, nie ma więc na czas przeciągania czego przewijać; długa przewija się dalej, także sama przy krawędzi.
  const scrollBox = useRef<HTMLDivElement>(null)
  const [fits, setFits] = useState(false)
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 4 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )
  const invalid = draft.groups.some((group) => group.name.trim() === '')
  const groupKeys = draft.groups.map((group) => group.key)
  const targets = [
    { value: UNGROUPED, label: 'Bez grupy' },
    ...draft.groups.map((group) => ({ value: group.key, label: group.name.trim() || 'Bez nazwy' })),
  ]

  const stopDragging = () => {
    setGroupDrag(false)
    setFits(false)
  }

  const onDragStart = ({ active }: DragStartEvent) => {
    const box = scrollBox.current
    const moving = (active.data.current as DragData | undefined)?.scope === GROUPS
    const height = moving ? groupKeys.length * (active.rect.current.initial?.height ?? 0) : (box?.scrollHeight ?? 0)
    setFits(box !== null && height <= box.clientHeight)
    setGroupDrag(moving)
  }

  const onDragEnd = ({ active, over }: DragEndEvent) => {
    stopDragging()
    if (!over || active.id === over.id) return
    const scope = (active.data.current as DragData).scope
    const from = String(active.id)
    const to = String(over.id)
    if (scope === GROUPS) {
      setDraft({ ...draft, groups: arrayMove(draft.groups, groupKeys.indexOf(from), groupKeys.indexOf(to)) })
      return
    }
    const reorder = (people: Person[]) => {
      const ids = people.map((person) => person.id)
      return arrayMove(people, ids.indexOf(from), ids.indexOf(to))
    }
    setDraft(
      scope === UNGROUPED
        ? { ...draft, ungrouped: reorder(draft.ungrouped) }
        : {
            ...draft,
            groups: draft.groups.map((group) =>
              group.key === scope ? { ...group, people: reorder(group.people) } : group,
            ),
          },
    )
  }

  const rename = (key: string, name: string) =>
    setDraft({ ...draft, groups: draft.groups.map((group) => (group.key === key ? { ...group, name } : group)) })

  /** Pracownicy usuniętej grupy trafiają do osób bez grupy. */
  const remove = (key: string) =>
    setDraft({
      ungrouped: [...draft.ungrouped, ...(draft.groups.find((group) => group.key === key)?.people ?? [])],
      groups: draft.groups.filter((group) => group.key !== key),
    })

  const failed = (cause: unknown) =>
    toast.error(cause instanceof ApiError ? cause.message : 'Nie udało się zapisać układu')

  const submit = () => {
    const previous = currentLayout(schedule)
    save.mutate(layoutOf(draft), {
      onError: failed,
      onSuccess: () => {
        onDone()
        toast('Zapisano grupy i kolejność', {
          action: { label: 'Cofnij', onClick: () => save.mutate(previous, { onError: failed }) },
        })
      },
    })
  }

  const people = (scopeKey: string, list: Person[]) => (
    <SortableContext items={list.map((person) => person.id)} strategy={verticalListSortingStrategy}>
      {list.map((person) => (
        <PersonRow
          key={person.id}
          person={person}
          data={{ scope: scopeKey }}
          group={scopeKey}
          targets={targets}
          onMove={(target) => setDraft(moveToGroup(draft, person, target))}
        />
      ))}
    </SortableContext>
  )

  return (
    <>
      <div
        ref={scrollBox}
        className={cn(
          'max-h-[60dvh] rounded-xl ring-1 ring-foreground/10',
          fits ? 'overflow-hidden' : 'overflow-y-auto',
        )}
      >
        <DndContext
          sensors={sensors}
          collisionDetection={sameScope}
          measuring={{ droppable: { strategy: MeasuringStrategy.Always } }}
          modifiers={[insideScope]}
          onDragStart={onDragStart}
          onDragEnd={onDragEnd}
          onDragCancel={stopDragging}
        >
          <ul>
            {!groupDrag && draft.ungrouped.length > 0 && (
              <>
                <li className="border-b border-border bg-muted px-3 py-2 text-sm font-medium text-muted-foreground">
                  Bez grupy
                </li>
                {people(UNGROUPED, draft.ungrouped)}
              </>
            )}
            <SortableContext items={groupKeys} strategy={verticalListSortingStrategy}>
              {draft.groups.map((group) => (
                <Fragment key={group.key}>
                  <GroupRow
                    group={group}
                    data={{ scope: GROUPS }}
                    collapsed={groupDrag}
                    onRename={(name) => rename(group.key, name)}
                    onRemove={() => remove(group.key)}
                  />
                  {!groupDrag &&
                    (group.people.length > 0 ? (
                      people(group.key, group.people)
                    ) : (
                      <li className="border-b border-border px-3 py-2 text-xs text-muted-foreground">
                        Brak pracowników. Przenieś ich tu wyborem przy wierszu.
                      </li>
                    ))}
                </Fragment>
              ))}
            </SortableContext>
          </ul>
        </DndContext>
      </div>
      <div className="flex flex-wrap justify-between gap-2">
        <Button
          type="button"
          variant="outline"
          onClick={() =>
            setDraft({
              ...draft,
              groups: [{ key: crypto.randomUUID(), id: null, name: 'Nowa grupa', people: [] }, ...draft.groups],
            })
          }
        >
          <PlusIcon aria-hidden />
          Dodaj grupę
        </Button>
        <div className="flex gap-2">
          <Button type="button" variant="outline" onClick={onDone}>
            Anuluj
          </Button>
          <Button type="button" loading={save.isPending} disabled={invalid} onClick={submit}>
            Zapisz
          </Button>
        </div>
      </div>
    </>
  )
}

const HANDLE =
  'inline-flex size-7 shrink-0 cursor-grab touch-none items-center justify-center rounded-md text-muted-foreground outline-none hover:bg-accent hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 active:cursor-grabbing'

function GroupRow({
  group,
  data,
  collapsed,
  onRename,
  onRemove,
}: {
  group: Group
  data: DragData
  collapsed: boolean
  onRename: (name: string) => void
  onRemove: () => void
}) {
  const { attributes, listeners, setNodeRef, setActivatorNodeRef, transform, transition, isDragging } = useSortable({
    id: group.key,
    data,
  })
  return (
    <li
      ref={setNodeRef}
      data-drag-scope={data.scope}
      data-drag-key={group.key}
      style={{ transform: CSS.Translate.toString(transform), transition }}
      className={cn(
        'flex items-center gap-2 border-b border-border bg-muted px-2 py-1',
        isDragging && 'relative z-10 rounded-lg shadow-md',
      )}
    >
      <button
        type="button"
        ref={setActivatorNodeRef}
        aria-label={`Przenieś grupę: ${group.name}`}
        title="Przeciągnij, żeby zmienić miejsce grupy"
        className={HANDLE}
        {...attributes}
        {...listeners}
      >
        <GripVerticalIcon aria-hidden className="size-4" />
      </button>
      <input
        aria-label="Nazwa grupy"
        value={group.name}
        maxLength={60}
        onChange={(event) => onRename(event.target.value)}
        className="min-w-0 flex-1 rounded-md bg-transparent px-1 py-0.5 text-sm font-medium outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
      />
      {collapsed && (
        <span className="shrink-0 text-xs text-muted-foreground tabular-nums">
          {group.people.length} {peopleWord(group.people.length)}
        </span>
      )}
      <Button type="button" variant="ghost" size="icon" aria-label={`Usuń grupę ${group.name}`} onClick={onRemove}>
        <Trash2Icon aria-hidden />
      </Button>
    </li>
  )
}

function PersonRow({
  person,
  data,
  group,
  targets,
  onMove,
}: {
  person: Person
  data: DragData
  group: string
  targets: { value: string; label: string }[]
  onMove: (target: string) => void
}) {
  const { attributes, listeners, setNodeRef, setActivatorNodeRef, transform, transition, isDragging } = useSortable({
    id: person.id,
    data,
  })
  return (
    <li
      ref={setNodeRef}
      data-drag-scope={data.scope}
      data-drag-key={person.id}
      style={{ transform: CSS.Translate.toString(transform), transition }}
      className={cn(
        'flex items-center gap-2 border-b border-border bg-popover px-2 py-1',
        isDragging && 'relative z-10 rounded-lg shadow-md',
      )}
    >
      <button
        type="button"
        ref={setActivatorNodeRef}
        aria-label={`Przenieś: ${person.name}`}
        title="Przeciągnij, żeby zmienić kolejność w grupie"
        className={HANDLE}
        {...attributes}
        {...listeners}
      >
        <GripVerticalIcon aria-hidden className="size-4" />
      </button>
      <span className="min-w-0 flex-1 truncate text-sm" title={person.name}>
        {person.name}
        <span className="ml-2 text-xs text-muted-foreground">{person.hint}</span>
      </span>
      <Select items={targets} value={group} onValueChange={(value) => value && value !== group && onMove(value)}>
        <SelectTrigger aria-label={`Grupa: ${person.name}`} className="h-8 w-36 shrink-0 text-xs">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {targets.map((target) => (
            <SelectItem key={target.value} value={target.value}>
              {target.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </li>
  )
}
