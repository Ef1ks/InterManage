import { type FormEvent, type ReactElement, useMemo, useState } from 'react'
import { CheckIcon, PencilIcon, PlusIcon, TagIcon, Trash2Icon, UsersIcon } from 'lucide-react'
import { HexColorInput, HexColorPicker } from 'react-colorful'
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

/** Gotowe próbki pod próbnikiem; kolor zapisuje się jako `#rrggbb`. */
export const COLOR_PRESETS = [
  '#ef4444',
  '#f97316',
  '#f59e0b',
  '#eab308',
  '#84cc16',
  '#22c55e',
  '#14b8a6',
  '#06b6d4',
  '#0ea5e9',
  '#3b82f6',
  '#6366f1',
  '#8b5cf6',
  '#d946ef',
  '#ec4899',
  '#a16207',
  '#64748b',
]

/** Kolory z pierwszej wersji palety (nazwy w bazie) jako HEX. */
const LEGACY_COLORS: Record<string, string> = {
  red: '#ef4444',
  orange: '#f97316',
  amber: '#fbbf24',
  lime: '#84cc16',
  green: '#16a34a',
  teal: '#14b8a6',
  sky: '#0ea5e9',
  blue: '#2563eb',
  violet: '#7c3aed',
  fuchsia: '#d946ef',
  pink: '#ec4899',
  slate: '#475569',
}

export const hexOf = (color: string) => LEGACY_COLORS[color] ?? color

/** Mocne tło wiersza w kolorze dostawcy, czytelne w jasnym i ciemnym motywie (mieszane z tłem karty). */
export function tintStyle(color: string | undefined, strength = 45) {
  return color ? { backgroundColor: `color-mix(in srgb, ${hexOf(color)} ${strength}%, var(--card))` } : undefined
}

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
  // Jeden dostawca to już kolor całego wiersza; kropki pokazują, że artykuł jest u kilku.
  if (!groups || groups.length < 2) return null
  return (
    <span className="inline-flex shrink-0 items-center gap-1" title={groups.map((group) => group.name).join(', ')}>
      {groups.map((group) => (
        <span
          key={group.id}
          className="size-2.5 rounded-full"
          style={{ backgroundColor: hexOf(group.color) }}
          aria-hidden
        />
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
                <span
                  className="size-2.5 shrink-0 rounded-full"
                  style={{ backgroundColor: hexOf(group.color) }}
                  aria-hidden
                />
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
  const used = new Set(groups.map((group) => hexOf(group.color)))
  const [name, setName] = useState(state.group?.name ?? '')
  const [color, setColor] = useState<string>(
    hexOf(state.group?.color ?? COLOR_PRESETS.find((preset) => !used.has(preset)) ?? COLOR_PRESETS[0]),
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
        <fieldset className="grid gap-3">
          <legend className="mb-2 text-sm font-medium">Kolor</legend>
          <div className="flex flex-wrap items-start gap-4">
            <HexColorPicker color={color} onChange={setColor} className="control-color-picker" />
            <div className="grid min-w-40 flex-1 gap-3">
              <div className="grid grid-cols-4 gap-1.5">
                {COLOR_PRESETS.map((preset) => (
                  <button
                    key={preset}
                    type="button"
                    aria-label={`Kolor ${preset}`}
                    aria-pressed={color === preset}
                    title={preset}
                    onClick={() => setColor(preset)}
                    style={{ backgroundColor: preset }}
                    className={cn(
                      'flex size-8 items-center justify-center rounded-lg text-white ring-offset-2 ring-offset-background transition-transform duration-150 outline-none focus-visible:ring-3 focus-visible:ring-ring/50 active:scale-95',
                      color === preset && 'ring-2 ring-foreground',
                    )}
                  >
                    {color === preset && <CheckIcon className="size-4" />}
                  </button>
                ))}
              </div>
              <label className="flex items-center gap-2 text-sm">
                <span className="text-muted-foreground">HEX</span>
                <HexColorInput
                  color={color}
                  onChange={setColor}
                  prefixed
                  aria-label="Kolor w zapisie HEX"
                  className="h-9 w-28 rounded-lg border border-input bg-background px-2 font-mono text-sm uppercase outline-none focus:border-ring focus:ring-3 focus:ring-ring/30 dark:bg-input/30"
                />
              </label>
              <div className="rounded-lg border px-3 py-2 text-sm" style={tintStyle(color)}>
                Tak wygląda wiersz artykułu
              </div>
            </div>
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
            style={on ? tintStyle(group.color, 60) : undefined}
            onClick={() => onActive(on ? active.filter((id) => id !== group.id) : [...active, group.id])}
            className={cn(
              'flex h-8 items-center gap-1.5 rounded-full border px-3 text-sm transition-[background-color,border-color,transform] duration-150 outline-none focus-visible:ring-3 focus-visible:ring-ring/30 active:scale-[0.97]',
              on ? 'border-transparent font-medium' : 'bg-card hover:bg-muted',
            )}
          >
            <span className="size-2.5 rounded-full" style={{ backgroundColor: hexOf(group.color) }} aria-hidden />
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
                  <span
                    className="size-3 shrink-0 rounded-full"
                    style={{ backgroundColor: hexOf(group.color) }}
                    aria-hidden
                  />
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
