import { Link } from 'react-router'

import { PageHeader } from '@/components/PageHeader'
import { Card, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { hasPermission, useCurrentUser } from '@/features/auth/api'
import { TOOL_ROUTES, usePermissionCategories } from '@/features/tools/categories'

export function DashboardPage() {
  const me = useCurrentUser()
  const { data: categories = [] } = usePermissionCategories()
  const tools = categories.filter((category) => hasPermission(me, category.code))

  return (
    <>
      <PageHeader title={`Witaj, ${me.displayName}`} description={`Rola: ${me.roleName}`} />

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
              <Link key={category.code} to={path} className="rounded-xl focus-visible:ring-3 focus-visible:ring-ring/50">
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
