import { type FormEvent, type ReactElement, useMemo, useState } from 'react'
import { CheckIcon, PencilIcon, PlusIcon, TagIcon, Trash2Icon, UsersIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Form, FormActions, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { type LossControlChange, useChangeLossControlGroups } from '@/features/sales/api'
import { ApiError } from '@/lib/api'
import type { LossControlGroup } from '@/lib/types'
import { cn } from '@/lib/utils'

/** Paleta kolorów dostawców do kontroli strat; nazwa koloru trafia do bazy (`LossControlGroupService.COLORS`). */
export const CONTROL_COLORS = [
  {
    key: 'red',
    label: 'Czerwony',
    dot: 'bg-red-500',
    chip: 'bg-red-200 text-red-950 dark:bg-red-900/70 dark:text-red-100',
  },
  {
    key: 'orange',
    label: 'Pomarańczowy',
    dot: 'bg-orange-500',
    chip: 'bg-orange-200 text-orange-950 dark:bg-orange-900/70 dark:text-orange-100',
  },
  {
    key: 'amber',
    label: 'Bursztynowy',
    dot: 'bg-amber-400',
    chip: 'bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100',
  },
  {
    key: 'lime',
    label: 'Limonkowy',
    dot: 'bg-lime-500',
    chip: 'bg-lime-200 text-lime-950 dark:bg-lime-900/70 dark:text-lime-100',
  },
  {
    key: 'green',
    label: 'Zielony',
    dot: 'bg-green-600',
    chip: 'bg-green-200 text-green-950 dark:bg-green-900/70 dark:text-green-100',
  },
  {
    key: 'teal',
    label: 'Morski',
    dot: 'bg-teal-500',
    chip: 'bg-teal-200 text-teal-950 dark:bg-teal-900/70 dark:text-teal-100',
  },
  {
    key: 'sky',
    label: 'Błękitny',
    dot: 'bg-sky-500',
    chip: 'bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-100',
  },
  {
    key: 'blue',
    label: 'Niebieski',
    dot: 'bg-blue-600',
    chip: 'bg-blue-200 text-blue-950 dark:bg-blue-900/70 dark:text-blue-100',
  },
  {
    key: 'violet',
    label: 'Fioletowy',
    dot: 'bg-violet-600',
    chip: 'bg-violet-200 text-violet-950 dark:bg-violet-900/70 dark:text-violet-100',
  },
  {
    key: 'fuchsia',
    label: 'Fuksja',
    dot: 'bg-fuchsia-500',
    chip: 'bg-fuchsia-200 text-fuchsia-950 dark:bg-fuchsia-900/70 dark:text-fuchsia-100',
  },
  {
    key: 'pink',
    label: 'Różowy',
    dot: 'bg-pink-500',
    chip: 'bg-pink-200 text-pink-950 dark:bg-pink-900/70 dark:text-pink-100',
  },
  {
    key: 'slate',
    label: 'Grafitowy',
    dot: 'bg-slate-600',
    chip: 'bg-slate-300 text-slate-950 dark:bg-slate-700 dark:text-slate-100',
  },
] as const

const colorOf = (key: string) => CONTROL_COLORS.find((color) => color.key === key) ?? CONTROL_COLORS.at(-1)!

/** ITM8 → dostawcy do kontroli, do których artykuł jest podpięty. */
export function useControlGroupsByItm8(groups: LossControlGroup[]) {
  return useMemo(() => {
    const byItm8 = new Map<string, LossControlGroup[]>()
    for (const group of groups) {
      for (const itm8 of group.itm8s) byItm8.set(itm8, [...(byItm8.get(itm8) ?? []), group])
    }
    return byItm8
  }, [groups])
}

/** Kropki z kolorami dostawców do kontroli przy artykule. */
export function ControlDots({ groups }: { groups: LossControlGroup[] | undefined }) {
  if (!groups || groups.length === 0) return null
  return (
    <span className="inline-flex shrink-0 items-center gap-1" title={groups.map((group) => group.name).join(', ')}>
      {groups.map((group) => (
        <span key={group.id} className={cn('size-2.5 rounded-full', colorOf(group.color).dot)} aria-hidden />
      ))}
      <span className="sr-only">Dostawcy do kontroli: {groups.map((group) => group.name).join(', ')}</span>
    </span>
  )
}

const errorText = (error: unknown) =>
  error instanceof ApiError ? error.message : 'Nie udało się zmienić dostawców do kontroli'

/**
 * Menu przypisania artykułów do dostawców do kontroli: zaznaczony = wszystkie artykuły są u tego dostawcy. Klik
 * podpina albo odpina, z „Cofnij” w komunikacie.
 */
export function ControlGroupMenu({
  storeId,
  groups,
  itm8s,
  trigger,
  onCreate,
}: {
  storeId: string
  groups: LossControlGroup[]
  itm8s: string[]
  trigger: ReactElement
  onCreate: (itm8s: string[]) => void
}) {
  const change = useChangeLossControlGroups(storeId)
  const count = itm8s.length === 1 ? 'artykuł' : `${itm8s.length} art.`
  const toggle = (group: LossControlGroup, on: boolean) => {
    const members = new Set(group.itm8s)
    const affected = itm8s.filter((itm8) => members.has(itm8) !== on)
    if (affected.length === 0) return
    const undo: LossControlChange = { action: on ? 'remove' : 'add', id: group.id, itm8s: affected }
    change.mutate(
      { action: on ? 'add' : 'remove', id: group.id, itm8s: affected },
      {
        onError: (error) => toast.error(errorText(error)),
        onSuccess: () =>
          toast.success(on ? `Dodano do: ${group.name}` : `Usunięto z: ${group.name}`, {
            action: {
              label: 'Cofnij',
              onClick: () => change.mutate(undo, { onError: (e) => toast.error(errorText(e)) }),
            },
          }),
      },
    )
  }
  return (
    <DropdownMenu>
      <DropdownMenuTrigger render={trigger} />
      <DropdownMenuContent align="start" className="min-w-60" onClick={(event) => event.stopPropagation()}>
        <DropdownMenuGroup>
          <DropdownMenuLabel>Dostawca do kontroli ({count})</DropdownMenuLabel>
          {groups.map((group) => {
            const members = new Set(group.itm8s)
            const all = itm8s.every((itm8) => members.has(itm8))
            return (
              <DropdownMenuCheckboxItem
                key={group.id}
                checked={all}
                closeOnClick={false}
                onCheckedChange={(checked) => toggle(group, checked)}
              >
                <span className={cn('size-2.5 shrink-0 rounded-full', colorOf(group.color).dot)} aria-hidden />
                <span className="truncate">{group.name}</span>
              </DropdownMenuCheckboxItem>
            )
          })}
        </DropdownMenuGroup>
        {groups.length > 0 && <DropdownMenuSeparator />}
        <DropdownMenuItem onClick={() => onCreate(itm8s)}>
          <PlusIcon />
          Nowy dostawca do kontroli…
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

/** Przycisk przy artykule otwierający `ControlGroupMenu`. */
export function ControlGroupButton(props: Omit<Parameters<typeof ControlGroupMenu>[0], 'trigger'>) {
  return (
    <ControlGroupMenu
      {...props}
      trigger={
        <Button
          variant="ghost"
          size="icon-sm"
          aria-label="Przypisz do dostawcy do kontroli"
          title="Przypisz do dostawcy do kontroli"
          onClick={(event) => event.stopPropagation()}
        >
          <TagIcon />
        </Button>
      }
    />
  )
}

export type EditorState = { group: LossControlGroup | null; itm8s: string[] } | null

/** Nowy dostawca do kontroli albo zmiana nazwy i koloru; nowy od razu dostaje artykuły, z których go założono. */
export function ControlGroupEditor({
  storeId,
  groups,
  state,
  onClose,
}: {
  storeId: string
  groups: LossControlGroup[]
  state: EditorState
  onClose: () => void
}) {
  return (
    <Dialog open={state != null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="gap-4 p-6 sm:max-w-md">
        {state && (
          <EditorForm
            key={state.group?.id ?? 'new'}
            storeId={storeId}
            groups={groups}
            state={state}
            onClose={onClose}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

function EditorForm({
  storeId,
  groups,
  state,
  onClose,
}: {
  storeId: string
  groups: LossControlGroup[]
  state: NonNullable<EditorState>
  onClose: () => void
}) {
  const change = useChangeLossControlGroups(storeId)
  const used = new Set(groups.map((group) => group.color))
  const [name, setName] = useState(state.group?.name ?? '')
  const [color, setColor] = useState<string>(
    state.group?.color ?? CONTROL_COLORS.find((item) => !used.has(item.key))?.key ?? CONTROL_COLORS[0].key,
  )
  const [error, setError] = useState<string | null>(null)
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (name.trim() === '') {
      setError('Podaj nazwę dostawcy')
      return
    }
    try {
      if (state.group) {
        await change.mutateAsync({ action: 'update', id: state.group.id, name, color })
      } else {
        const result = await change.mutateAsync({ action: 'create', name, color })
        const created = result.find(
          (group) => group.name.toLowerCase() === name.trim().replace(/\s+/g, ' ').toLowerCase(),
        )
        if (created && state.itm8s.length > 0) {
          await change.mutateAsync({ action: 'add', id: created.id, itm8s: state.itm8s })
        }
      }
      toast.success(state.group ? 'Zapisano dostawcę do kontroli' : `Dodano dostawcę: ${name.trim()}`)
      onClose()
    } catch (caught) {
      setError(errorText(caught))
    }
  }
  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-lg">
          {state.group ? 'Zmień dostawcę do kontroli' : 'Nowy dostawca do kontroli'}
        </DialogTitle>
        <DialogDescription>
          {state.itm8s.length > 0
            ? `Od razu trafi do niego ${state.itm8s.length === 1 ? 'wybrany artykuł' : `${state.itm8s.length} wybranych artykułów`}.`
            : 'Grupa artykułów, po której filtrujesz raport, np. towar jednego dostawcy do rozliczenia strat.'}
        </DialogDescription>
      </DialogHeader>
      <Form onSubmit={submit} error={error}>
        <TextField
          label="Nazwa"
          value={name}
          onChange={(event) => setName(event.target.value)}
          autoFocus
          maxLength={80}
        />
        <fieldset className="grid gap-2">
          <legend className="mb-2 text-sm font-medium">Kolor</legend>
          <div className="flex flex-wrap gap-2">
            {CONTROL_COLORS.map((item) => (
              <button
                key={item.key}
                type="button"
                aria-label={item.label}
                aria-pressed={color === item.key}
                title={item.label}
                onClick={() => setColor(item.key)}
                className={cn(
                  'flex size-8 items-center justify-center rounded-full text-white ring-offset-2 ring-offset-background transition-transform duration-150 outline-none focus-visible:ring-3 focus-visible:ring-ring/50 active:scale-95',
                  item.dot,
                  color === item.key && 'ring-2 ring-foreground',
                )}
              >
                {color === item.key && <CheckIcon className="size-4" />}
              </button>
            ))}
          </div>
        </fieldset>
        <FormActions>
          <Button type="button" variant="outline" onClick={onClose}>
            Anuluj
          </Button>
          <Button type="submit" loading={change.isPending}>
            {state.group ? 'Zapisz' : 'Dodaj dostawcę'}
          </Button>
        </FormActions>
      </Form>
    </>
  )
}

/**
 * Pasek nad tabelą: dostawcy do kontroli jako przyciski filtra (kilka naraz) i okno zarządzania. Bez dostawców tylko
 * przycisk zakładania.
 */
export function ControlFilterBar({
  storeId,
  groups,
  active,
  onActive,
  canEdit,
  onEdit,
}: {
  storeId: string
  groups: LossControlGroup[]
  active: string[]
  onActive: (ids: string[]) => void
  canEdit: boolean
  onEdit: (state: EditorState) => void
}) {
  const [manage, setManage] = useState(false)
  const change = useChangeLossControlGroups(storeId)
  if (groups.length === 0 && !canEdit) return null
  const remove = (group: LossControlGroup) =>
    change.mutate(
      { action: 'delete', id: group.id },
      {
        onError: (error) => toast.error(errorText(error)),
        onSuccess: () => {
          onActive(active.filter((id) => id !== group.id))
          toast.success(`Usunięto dostawcę: ${group.name}`, {
            action: {
              label: 'Przywróć',
              onClick: () =>
                change.mutate({ action: 'restore', id: group.id }, { onError: (e) => toast.error(errorText(e)) }),
            },
          })
        },
      },
    )
  return (
    <div className="mb-3 flex flex-wrap items-center gap-2">
      <span className="text-sm font-medium">Dostawcy do kontroli:</span>
      {groups.length === 0 && (
        <span className="text-sm text-muted-foreground">
          jeszcze żadnego, przypisz artykuły ikoną etykiety przy artykule.
        </span>
      )}
      {groups.map((group) => {
        const on = active.includes(group.id)
        return (
          <button
            key={group.id}
            type="button"
            aria-pressed={on}
            onClick={() => onActive(on ? active.filter((id) => id !== group.id) : [...active, group.id])}
            className={cn(
              'flex h-8 items-center gap-1.5 rounded-full border px-3 text-sm transition-[background-color,border-color,transform] duration-150 outline-none focus-visible:ring-3 focus-visible:ring-ring/30 active:scale-[0.97]',
              on ? cn(colorOf(group.color).chip, 'border-transparent font-medium') : 'bg-card hover:bg-muted',
            )}
          >
            <span className={cn('size-2.5 rounded-full', colorOf(group.color).dot)} aria-hidden />
            {group.name}
            <span className="tabular-nums opacity-70">{group.itm8s.length}</span>
          </button>
        )
      })}
      {active.length > 0 && (
        <Button variant="ghost" size="sm" onClick={() => onActive([])}>
          Wszyscy
        </Button>
      )}
      {canEdit && (
        <Button variant="outline" size="sm" onClick={() => setManage(true)}>
          <UsersIcon />
          Zarządzaj
        </Button>
      )}

      <Dialog open={manage} onOpenChange={setManage}>
        <DialogContent className="gap-4 p-6 sm:max-w-lg">
          <DialogHeader>
            <DialogTitle className="text-lg">Dostawcy do kontroli</DialogTitle>
            <DialogDescription>
              Własne grupy artykułów sklepu. Artykuły dodajesz ikoną etykiety przy artykule albo dla zaznaczonych naraz;
              raport filtruje się po kolorach, a wydruk podaje nazwę dostawcy.
            </DialogDescription>
          </DialogHeader>
          {groups.length === 0 ? (
            <p className="py-4 text-center text-sm text-muted-foreground">
              Nie ma jeszcze żadnego dostawcy do kontroli.
            </p>
          ) : (
            <ul className="divide-y">
              {groups.map((group) => (
                <li key={group.id} className="flex items-center gap-3 py-2 text-sm">
                  <span className={cn('size-3 shrink-0 rounded-full', colorOf(group.color).dot)} aria-hidden />
                  <span className="min-w-0 flex-1 truncate font-medium">{group.name}</span>
                  <span className="text-muted-foreground tabular-nums">{group.itm8s.length} art.</span>
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    aria-label={`Zmień: ${group.name}`}
                    onClick={() => onEdit({ group, itm8s: [] })}
                  >
                    <PencilIcon />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    aria-label={`Usuń: ${group.name}`}
                    onClick={() => remove(group)}
                  >
                    <Trash2Icon />
                  </Button>
                </li>
              ))}
            </ul>
          )}
          <FormActions>
            <Button onClick={() => onEdit({ group: null, itm8s: [] })}>
              <PlusIcon />
              Nowy dostawca
            </Button>
          </FormActions>
        </DialogContent>
      </Dialog>
    </div>
  )
}
