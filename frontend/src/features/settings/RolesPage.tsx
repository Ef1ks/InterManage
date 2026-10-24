import { useState } from 'react'
import { PencilIcon, PlusIcon, Trash2Icon } from 'lucide-react'
import { toast } from 'sonner'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { TableSkeletonRows } from '@/components/TableSkeleton'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useDeleteRole, useRoles } from '@/features/settings/api'
import { RoleFormDialog } from '@/features/settings/RoleFormDialog'
import { usePermissionCategories } from '@/features/tools/categories'
import { ApiError } from '@/lib/api'
import type { Role } from '@/lib/types'

export function RolesPage() {
  const { data: roles, isPending, isError, refetch } = useRoles()
  const { data: categories = [] } = usePermissionCategories()
  const deleteRole = useDeleteRole()

  const [editor, setEditor] = useState<{ open: boolean; role?: Role }>({ open: false })
  const [toDelete, setToDelete] = useState<Role | null>(null)

  const confirmDelete = () => {
    if (!toDelete) return
    deleteRole.mutate(toDelete.id, {
      onSuccess: () => toast.success(`Usunięto rolę „${toDelete.name}”`),
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się usunąć roli'),
      onSettled: () => setToDelete(null),
    })
  }

  return (
    <>
      <PageHeader
        title="Role i uprawnienia"
        description="Twórz role i decyduj, do których narzędzi mają dostęp."
        actions={
          <Button size="lg" onClick={() => setEditor({ open: true })}>
            <PlusIcon />
            Nowa rola
          </Button>
        }
      />

      {isError ? (
        <FullPageMessage
          title="Nie udało się wczytać ról"
          action={{ label: 'Spróbuj ponownie', onClick: () => refetch() }}
          inline
        />
      ) : (
        <Card className="py-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Rola</TableHead>
                <TableHead>Uprawnienia</TableHead>
                <TableHead className="text-right">Użytkownicy</TableHead>
                <TableHead className="w-24" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {isPending ? (
                <TableSkeletonRows columns={4} />
              ) : (
                roles.map((role) => (
                <TableRow key={role.id}>
                  <TableCell className="align-top">
                    <div className="flex items-center gap-2 font-medium">
                      {role.name}
                      {role.owner && <Badge variant="secondary">systemowa</Badge>}
                    </div>
                    {role.description && (
                      <div className="max-w-64 truncate text-xs text-muted-foreground">{role.description}</div>
                    )}
                  </TableCell>
                  <TableCell className="align-top whitespace-normal">
                    {role.owner ? (
                      <span className="text-sm text-muted-foreground">Pełny dostęp do wszystkiego</span>
                    ) : (
                      <div className="flex flex-wrap gap-1">
                        {categories
                          .filter((c) => role.permissions[c.code])
                          .map((c) => (
                            <Badge key={c.code} variant={role.permissions[c.code] === 'EDIT' ? 'default' : 'outline'}>
                              {c.label}: {role.permissions[c.code] === 'EDIT' ? 'edycja' : 'podgląd'}
                            </Badge>
                          ))}
                        {Object.keys(role.permissions).length === 0 && (
                          <span className="text-sm text-muted-foreground">Brak dostępu do narzędzi</span>
                        )}
                      </div>
                    )}
                  </TableCell>
                  <TableCell className="text-right align-top">{role.userCount}</TableCell>
                  <TableCell className="text-right align-top">
                    {!role.owner && (
                      <div className="flex justify-end gap-1">
                        <Button
                          variant="ghost"
                          size="icon-sm"
                          aria-label={`Edytuj rolę ${role.name}`}
                          onClick={() => setEditor({ open: true, role })}
                        >
                          <PencilIcon />
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon-sm"
                          aria-label={`Usuń rolę ${role.name}`}
                          onClick={() => setToDelete(role)}
                        >
                          <Trash2Icon />
                        </Button>
                      </div>
                    )}
                  </TableCell>
                </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </Card>
      )}

      <RoleFormDialog
        open={editor.open}
        role={editor.role}
        onOpenChange={(open) => setEditor((current) => ({ ...current, open }))}
      />

      <AlertDialog open={toDelete !== null} onOpenChange={(open) => !open && setToDelete(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Usunąć rolę „{toDelete?.name}”?</AlertDialogTitle>
            <AlertDialogDescription>
              {toDelete && toDelete.userCount > 0
                ? `Rola jest przypisana do ${toDelete.userCount} użytkowników - najpierw zmień im rolę.`
                : 'Tej operacji nie można cofnąć.'}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Anuluj</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={confirmDelete}
              disabled={deleteRole.isPending || (toDelete?.userCount ?? 0) > 0}
            >
              Usuń
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
