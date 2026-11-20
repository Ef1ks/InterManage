import { useState, type ReactNode } from 'react'
import { ClipboardCheckIcon } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { useCurrentUser } from '@/features/auth/api'
import { useCurrentStore } from '@/features/stores/current-store'
import type { TaskItemDraft, TaskSource } from '@/features/tasks/api'
import { TaskDialog } from '@/features/tasks/TaskDialog'
import { cn } from '@/lib/utils'

/**
 * „Zleć zadanie” z dowolnego miejsca: z pozycjami (artykuły z danymi z chwili zlecenia) i widokiem raportu. Przycisk
 * widzi tylko ten, kto może zlecać (właściciel i konta z włączonym zlecaniem). {@code items} może być funkcją, żeby duży widok liczył
 * pozycje dopiero po kliknięciu.
 */
export function AssignTaskButton({
  title,
  items,
  source,
  label = 'Zleć zadanie',
  iconOnly = false,
  size = 'sm',
  variant = 'outline',
  disabled,
  className,
  children,
}: {
  title?: string
  items?: TaskItemDraft[] | (() => TaskItemDraft[])
  source?: TaskSource | null
  label?: string
  iconOnly?: boolean
  size?: 'sm' | 'default' | 'icon-sm' | 'icon-xs'
  variant?: 'outline' | 'ghost' | 'default' | 'secondary'
  disabled?: boolean
  className?: string
  children?: ReactNode
}) {
  const me = useCurrentUser()
  const store = useCurrentStore()
  const [draft, setDraft] = useState<{ title?: string; items: TaskItemDraft[]; source: TaskSource | null } | null>(null)
  if (!me.canAssignTasks || !store) return null

  const open = () =>
    setDraft({ title, items: typeof items === 'function' ? items() : (items ?? []), source: source ?? null })

  return (
    <>
      <Button
        type="button"
        variant={variant}
        size={iconOnly ? 'icon-sm' : size}
        disabled={disabled}
        onClick={(event) => {
          event.stopPropagation()
          open()
        }}
        aria-label={iconOnly ? label : undefined}
        title={iconOnly ? label : undefined}
        className={cn(className)}
      >
        <ClipboardCheckIcon />
        {!iconOnly && (children ?? label)}
      </Button>
      {draft && (
        // Portal przekazuje zdarzenia w górę drzewa Reacta: klik w oknie nie może rozwijać wiersza raportu.
        <span className="contents" onClick={(event) => event.stopPropagation()}>
          <TaskDialog storeId={store.id} open onOpenChange={(value) => !value && setDraft(null)} draft={draft} />
        </span>
      )}
    </>
  )
}
