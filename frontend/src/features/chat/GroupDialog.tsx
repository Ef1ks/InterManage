import { useMemo, useState } from 'react'
import { CheckIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Form, FormActions, SegmentedField, TextField } from '@/components/form'
import { SearchInput } from '@/components/SearchInput'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { UserAvatar } from '@/components/UserAvatar'
import { ApiError } from '@/lib/api'
import type { ChatConversation, ChatPerson } from '@/lib/types'
import { cn } from '@/lib/utils'

import { useCandidates, useChatActions } from './api'

type Scope = 'store' | 'company'

type Props = {
  open: boolean
  onOpenChange: (open: boolean) => void
  storeId: string
  meId: string
  /** Kim jest grupa do edycji; bez niej okno zakłada nową. */
  group?: ChatConversation
  members?: ChatPerson[]
  /** Kto może założyć grupę sklepu i niezależną od sklepu. */
  canStoreGroup: boolean
  canCompanyGroup: boolean
  onSaved: (conversation: ChatConversation) => void
}

const SCOPES = [
  { value: 'store' as const, label: 'Ten sklep' },
  { value: 'company' as const, label: 'Niezależna od sklepu' },
]

export function GroupDialog(props: Props) {
  return (
    <Dialog open={props.open} onOpenChange={props.onOpenChange}>
      <DialogContent className="sm:max-w-lg">{props.open && <GroupForm {...props} />}</DialogContent>
    </Dialog>
  )
}

function GroupForm({ storeId, meId, group, members, canStoreGroup, canCompanyGroup, onSaved, onOpenChange }: Props) {
  const editing = group !== undefined
  const [name, setName] = useState(group?.name ?? '')
  const [scope, setScope] = useState<Scope>(
    editing ? (group.storeId ? 'store' : 'company') : canStoreGroup ? 'store' : 'company',
  )
  const [chosen, setChosen] = useState<Set<string>>(() => new Set((members ?? []).map((member) => member.id)))
  const [query, setQuery] = useState('')
  const [error, setError] = useState<string | null>(null)
  const { data: candidates = [], isPending } = useCandidates(storeId, scope === 'company')
  const { createGroup, rename, setMembers } = useChatActions()

  // Członkowie spoza listy (np. dodani wcześniej z innego sklepu) zostają widoczni do odznaczenia.
  const people = useMemo(() => {
    const byId = new Map<string, ChatPerson>()
    candidates.forEach((person) => byId.set(person.id, person))
    ;(members ?? []).filter((member) => member.id !== meId).forEach((member) => byId.set(member.id, member))
    return [...byId.values()].sort((a, b) => a.name.localeCompare(b.name, 'pl'))
  }, [candidates, members, meId])
  const visible = people.filter((person) => person.name.toLowerCase().includes(query.trim().toLowerCase()))

  const toggle = (id: string) =>
    setChosen((current) => {
      const next = new Set(current)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  const saving = createGroup.isPending || rename.isPending || setMembers.isPending

  const onSubmit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!name.trim()) {
      setError('Podaj nazwę grupy')
      return
    }
    setError(null)
    try {
      if (!editing) {
        const created = await createGroup.mutateAsync({
          name: name.trim(),
          storeId: scope === 'store' ? storeId : null,
          memberIds: [...chosen].filter((id) => id !== meId),
        })
        toast.success(`Grupa ${created.name} założona`)
        onSaved(created)
      } else {
        let saved = group
        if (name.trim() !== group.name) {
          saved = await rename.mutateAsync({ conversationId: group.id, name: name.trim() })
        }
        const ids = [...chosen]
        const before = new Set((members ?? []).map((member) => member.id))
        if (ids.length !== before.size || ids.some((id) => !before.has(id))) {
          saved = await setMembers.mutateAsync({ conversationId: group.id, userIds: ids })
        }
        toast.success('Zapisano grupę')
        onSaved(saved)
      }
      onOpenChange(false)
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'Nie udało się zapisać grupy')
    }
  }

  return (
    <Form onSubmit={onSubmit} error={error}>
      <DialogHeader>
        <DialogTitle className="text-lg">{editing ? 'Grupa' : 'Nowa grupa'}</DialogTitle>
        <DialogDescription>
          {editing
            ? 'Zmień nazwę albo członków. Osoba usunięta z grupy przestaje ją widzieć.'
            : 'Grupa sklepu jest na górze czatu tego sklepu. Grupa niezależna łączy osoby z różnych sklepów.'}
        </DialogDescription>
      </DialogHeader>
      <TextField label="Nazwa grupy" value={name} maxLength={80} autoFocus onChange={(e) => setName(e.target.value)} />
      {!editing && canStoreGroup && canCompanyGroup && (
        <SegmentedField
          label="Gdzie"
          options={SCOPES}
          value={scope}
          onChange={(value) => {
            setScope(value)
            setChosen(new Set())
          }}
        />
      )}
      <div className="space-y-2">
        <div className="flex items-baseline justify-between gap-2">
          <p className="text-sm font-medium">Członkowie</p>
          <p className="text-xs text-muted-foreground tabular-nums">
            {editing ? `${chosen.size} osób` : `Ty i ${[...chosen].filter((id) => id !== meId).length} osób`}
          </p>
        </div>
        <SearchInput value={query} onChange={setQuery} placeholder="Szukaj osoby" />
        <ul className="max-h-72 overflow-y-auto rounded-lg border" data-drag-select-scope>
          {isPending && <li className="px-3 py-6 text-center text-sm text-muted-foreground">Wczytuję osoby…</li>}
          {!isPending && visible.length === 0 && (
            <li className="px-3 py-6 text-center text-sm text-muted-foreground">Nikogo nie znaleziono</li>
          )}
          {visible.map((person) => {
            const selected = chosen.has(person.id)
            return (
              <li key={person.id}>
                <button
                  type="button"
                  role="checkbox"
                  aria-checked={selected}
                  onClick={() => toggle(person.id)}
                  className="flex w-full items-center gap-3 px-3 py-2 text-left text-sm hover:bg-muted"
                >
                  <UserAvatar name={person.name} url={person.avatarUrl} className="size-7 text-xs" />
                  <span className="min-w-0 flex-1 truncate">{person.name}</span>
                  <span
                    className={cn(
                      'flex size-5 items-center justify-center rounded-md border',
                      selected && 'border-primary bg-primary text-primary-foreground',
                    )}
                  >
                    {selected && <CheckIcon className="size-3.5" />}
                  </span>
                </button>
              </li>
            )
          })}
        </ul>
      </div>
      <FormActions>
        <Button type="button" variant="outline" size="xl" onClick={() => onOpenChange(false)}>
          Anuluj
        </Button>
        <Button type="submit" size="xl" loading={saving}>
          {editing ? 'Zapisz' : 'Załóż grupę'}
        </Button>
      </FormActions>
    </Form>
  )
}
