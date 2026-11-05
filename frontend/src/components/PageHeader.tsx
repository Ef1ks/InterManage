import type { ReactNode } from 'react'

import { cn } from '@/lib/utils'

export function PageHeader({
  title,
  description,
  actions,
  actionsAtBottom,
}: {
  title: string
  description?: ReactNode
  actions?: ReactNode
  /** Działania na wysokości opisu, nie tytułu. */
  actionsAtBottom?: boolean
}) {
  return (
    <div className="mb-6 flex flex-wrap items-start justify-between gap-4">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
        {description && <p className="mt-1 text-sm text-muted-foreground">{description}</p>}
      </div>
      {actions && <div className={cn('flex gap-2', actionsAtBottom && 'self-end')}>{actions}</div>}
    </div>
  )
}
