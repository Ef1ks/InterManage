import { Link } from 'react-router'

import { PageHeader } from '@/components/PageHeader'
import { Card, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { hasPermission, useCurrentUser } from '@/features/auth/api'
import { AttentionCard } from '@/features/dashboard/AttentionCard'
import { PresenceCard } from '@/features/dashboard/PresenceCard'
import { StoreResultsPanel } from '@/features/dashboard/StoreResultsPanel'
import { useCurrentStore } from '@/features/stores/current-store'
import { TOOL_ROUTES, isTool, usePermissionCategories } from '@/features/tools/categories'
import { cn } from '@/lib/utils'

export function DashboardPage() {
  const me = useCurrentUser()
  const { data: categories = [] } = usePermissionCategories()
  const tools = categories.flatMap((category) =>
    isTool(category.code) && hasPermission(me, category.code) ? [{ ...category, code: category.code }] : [],
  )
  const store = useCurrentStore()
  const showResults = hasPermission(me, 'DASHBOARD')
  const showPresence = hasPermission(me, 'STAFF_TIME')

  return (
    <>
      <PageHeader title={`Witaj, ${me.displayName}`} description={`Rola: ${me.roleName}`} />

      {/* Najważniejsze informacje sklepu, dokładane stopniowo: wyniki (uprawnienie Pełny pulpit) na 2/3 szerokości,
          obok obecność i sprawy do reakcji. Bez wyników kafelki stoją obok siebie. */}
      {store ? (
        <div className={cn('mb-6 grid gap-4', showResults ? 'lg:grid-cols-3' : 'sm:grid-cols-2')}>
          {showResults ? (
            <div className="min-w-0 lg:col-span-2">
              <StoreResultsPanel storeId={store.id} storeNumber={store.number} />
            </div>
          ) : null}
          <div className={showResults ? 'flex min-w-0 flex-col gap-4' : 'contents'}>
            {showPresence ? <PresenceCard storeId={store.id} /> : null}
            <AttentionCard storeId={store.id} />
          </div>
        </div>
      ) : null}

      {tools.length === 0 ? (
        <Card>
          <CardHeader>
            <CardTitle>Brak przypisanych narzędzi</CardTitle>
            <CardDescription>
              Twoja rola nie ma jeszcze dostępu do żadnej kategorii. Poproś właściciela o nadanie uprawnień.
            </CardDescription>
          </CardHeader>
        </Card>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {tools.map((category) => {
            const { path, icon: Icon } = TOOL_ROUTES[category.code]
            return (
              <Link
                key={category.code}
                to={path}
                className="rounded-xl focus-visible:ring-3 focus-visible:ring-ring/50"
              >
                <Card className="h-full transition-colors hover:bg-muted/50">
                  <CardHeader>
                    <Icon className="mb-2 size-5 text-muted-foreground" />
                    <CardTitle>{category.label}</CardTitle>
                    <CardDescription>{category.description}</CardDescription>
                  </CardHeader>
                </Card>
              </Link>
            )
          })}
        </div>
      )}
    </>
  )
}
