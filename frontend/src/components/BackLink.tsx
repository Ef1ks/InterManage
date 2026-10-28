import type { ReactNode } from 'react'
import { ArrowLeftIcon } from 'lucide-react'
import { Link, useNavigate } from 'react-router'

import { previousPathname } from '@/lib/history-trail'
import { cn } from '@/lib/utils'

/**
 * Powrót u góry podstrony. Gdy poprzedni ekran był w aplikacji, cofa historię (wraca tam, skąd przyszliśmy, np. do
 * faktur KSeF, z filtrami i przewinięciem); etykieta zostaje, gdy to ten sam ekran co {@code to}, inaczej „Wstecz”.
 * Bez historii (link z zewnątrz, odświeżenie) prowadzi do {@code to}.
 */
export function BackLink({ to, children, className }: { to: string; children: ReactNode; className?: string }) {
  const navigate = useNavigate()
  const previous = previousPathname()
  const target = to.split('?')[0]

  return (
    <Link
      to={to}
      onClick={(event) => {
        if (previous && !event.metaKey && !event.ctrlKey && !event.shiftKey && event.button === 0) {
          event.preventDefault()
          navigate(-1)
        }
      }}
      className={cn(
        'mb-4 inline-flex w-fit items-center gap-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground',
        className,
      )}
    >
      <ArrowLeftIcon className="size-4" />
      {previous && previous !== target ? 'Wstecz' : children}
    </Link>
  )
}
