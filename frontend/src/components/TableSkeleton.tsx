import { TableCell, TableRow } from '@/components/ui/table'

/** Wiersze-zaślepki w kształcie tabeli, zamiast spinnera na środku treści. */
export function TableSkeletonRows({ columns, rows = 4 }: { columns: number; rows?: number }) {
  return Array.from({ length: rows }, (_, row) => (
    <TableRow key={row} className="hover:bg-transparent" aria-hidden>
      {Array.from({ length: columns }, (_, column) => (
        <TableCell key={column} className="py-3.5">
          <div
            className="h-3.5 animate-pulse rounded-full bg-muted motion-reduce:animate-none"
            style={{ width: `${column === columns - 1 ? 24 : 40 + ((row * 7 + column * 13) % 45)}%` }}
          />
        </TableCell>
      ))}
    </TableRow>
  ))
}
