import { ChevronRightIcon } from 'lucide-react'
import { Link, Navigate, useLocation } from 'react-router'

import { PageHeader } from '@/components/PageHeader'
import { useNavGroups } from '@/components/layout/navigation'
import { useCurrentUser } from '@/features/auth/api'
import { usePermissionCategories } from '@/features/tools/categories'
import { cn } from '@/lib/utils'

/** Strona głównej kategorii z menu: kafelki jej narzędzi. Przy jednym dostępnym narzędziu od razu do niego. */
export function NavGroupPage() {
  const me = useCurrentUser()
  const { pathname } = useLocation()
  const categories = usePermissionCategories()
  const group = useNavGroups(me).find((candidate) => candidate.path === pathname)

  // Nazwy narzędzi przychodzą z backendu: bez nich nie wiadomo jeszcze, które grupy są dostępne.
  if (categories.isPending) return null
  if (!group) return <Navigate to="/" replace />
  if (group.items.length === 1) return <Navigate to={group.items[0].path} replace />

  return (
    <>
      <PageHeader title={group.label} description={group.description} />
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
        {group.items.map((item) => (
          <Link
            key={item.key}
            to={item.path}
            className={cn(
              'group flex flex-col gap-3 rounded-xl border bg-card p-4 transition-[border-color,box-shadow] duration-150 outline-none',
              'hover:border-foreground/20 hover:shadow-[0_2px_8px_-2px_rgb(0_0_0/0.08)] focus-visible:ring-3 focus-visible:ring-ring/50',
            )}
          >
            <div className="flex items-start justify-between gap-3">
              <span className="flex size-10 items-center justify-center rounded-lg bg-muted text-foreground">
                <item.icon aria-hidden className="size-5" />
              </span>
              <span className="flex items-center gap-2">
                {item.badge}
                <ChevronRightIcon
                  aria-hidden
                  className="size-4 text-muted-foreground transition-transform duration-150 group-hover:translate-x-0.5"
                />
              </span>
            </div>
            <div className="min-w-0">
              <h2 className="font-semibold">{item.label}</h2>
              <p className="mt-0.5 text-sm text-muted-foreground">{item.description}</p>
            </div>
          </Link>
        ))}
      </div>
    </>
  )
}
