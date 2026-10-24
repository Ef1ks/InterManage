import { PageHeader } from '@/components/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Card, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { useCan } from '@/features/auth/api'
import { usePermissionCategories } from '@/features/tools/categories'
import type { PermissionCategory } from '@/lib/types'

/** Tymczasowa strona kategorii, dopóki moduł nie powstanie. */
export function ToolPlaceholderPage({ category }: { category: PermissionCategory }) {
  const { data: categories = [] } = usePermissionCategories()
  const info = categories.find((c) => c.code === category)
  const canEdit = useCan(category, 'EDIT')

  return (
    <>
      <PageHeader
        title={info?.label ?? ''}
        description={info?.description}
        actions={<Badge variant="secondary">{canEdit ? 'Podgląd i edycja' : 'Tylko podgląd'}</Badge>}
      />
      <Card>
        <CardHeader>
          <CardTitle>Moduł w przygotowaniu</CardTitle>
          <CardDescription>Ta sekcja pojawi się w kolejnych etapach.</CardDescription>
        </CardHeader>
      </Card>
    </>
  )
}
