import type { ReactNode } from 'react'
import { ArrowDownIcon, ArrowUpIcon, ChevronsUpDownIcon } from 'lucide-react'

import { TableHead } from '@/components/ui/table'
import type { SortState } from '@/lib/table-sort'
import { cn } from '@/lib/utils'

type Props<K extends string> = {
  column: K
  sort: SortState<K>
  onSort: (column: K) => void
  children: ReactNode
  /** Kolumny z liczbami wyrównujemy do prawej. */
  align?: 'left' | 'right'
  className?: string
}

/** Nagłówek kolumny, który sortuje tabelę po kliknięciu; strzałka pokazuje bieżący kierunek. */
export function SortableHead<K extends string>({ column, sort, onSort, children, align = 'left', className }: Props<K>) {
  const active = sort.key === column
  const Icon = !active ? ChevronsUpDownIcon : sort.direction === 'asc' ? ArrowUpIcon : ArrowDownIcon
  return (
    <TableHead
      aria-sort={active ? (sort.direction === 'asc' ? 'ascending' : 'descending') : 'none'}
      className={cn(align === 'right' && 'text-right', className)}
    >
      <button
        type="button"
        onClick={() => onSort(column)}
        className={cn(
          '-mx-1.5 inline-flex items-center gap-1 rounded-md px-1.5 py-1 font-medium transition-colors duration-150 outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50',
          align === 'right' && 'flex-row-reverse',
          active ? 'text-foreground' : 'text-muted-foreground',
        )}
      >
        {children}
        <Icon aria-hidden className={cn('size-3.5 shrink-0', !active && 'opacity-50')} />
      </button>
    </TableHead>
  )
}
