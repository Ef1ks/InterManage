import type { ReactNode } from 'react'
import { NavLink } from 'react-router'

import { cn } from '@/lib/utils'

/** Zakładka podstrony w szarym pasku pod nagłówkiem strony. */
export function SubNavLink({ to, end, children }: { to: string; end?: boolean; children: ReactNode }) {
  return (
    <NavLink
      to={to}
      end={end}
      className={({ isActive }) =>
        cn(
          'flex shrink-0 items-center gap-2 rounded-lg px-3.5 py-1.5 text-sm font-medium whitespace-nowrap text-muted-foreground transition-[background-color,color,box-shadow] duration-150 outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50',
          isActive && 'bg-background text-foreground shadow-[0_1px_2px_rgb(0_0_0/0.08)]',
        )
      }
    >
      {children}
    </NavLink>
  )
}
