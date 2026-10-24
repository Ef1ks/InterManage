import { useState } from 'react'
import { MailIcon, PencilIcon, PlusIcon } from 'lucide-react'
import { toast } from 'sonner'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { TableSkeletonRows } from '@/components/TableSkeleton'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCurrentUser } from '@/features/auth/api'
import { useResendActivationLink, useUsers } from '@/features/settings/api'
import { UserFormDialog } from '@/features/settings/UserFormDialog'
import { ApiError } from '@/lib/api'
import type { User } from '@/lib/types'

export function UsersPage() {
  const me = useCurrentUser()
  const { data: users, isPending, isError, refetch } = useUsers()
  const resend = useResendActivationLink()
  const [editor, setEditor] = useState<{ open: boolean; user?: User }>({ open: false })

  const resendLink = (user: User) =>
    resend.mutate(user.id, {
      onSuccess: () =>
        toast.success('Wysłano nowy link aktywacyjny', {
          description: `Na adres ${user.email}. Poprzedni link przestał działać.`,
        }),
      onError: (error) =>
        toast.error(error instanceof ApiError ? error.message : 'Nie udało się wysłać linku'),
    })

  return (
    <>
      <PageHeader
        title="Użytkownicy"
        description="Konta pracowników i przypisane im role."
        actions={
          <Button size="lg" onClick={() => setEditor({ open: true })}>
            <PlusIcon />
            Zaproś pracownika
          </Button>
        }
      />

      {isError ? (
        <FullPageMessage
          title="Nie udało się wczytać użytkowników"
          action={{ label: 'Spróbuj ponownie', onClick: () => refetch() }}
          inline
        />
      ) : (
        <Card className="py-0">
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="pl-4">Pracownik</TableHead>
                <TableHead>Login</TableHead>
                <TableHead>Rola</TableHead>
                <TableHead>Status</TableHead>
                <TableHead className="w-24 pr-4">
                  <span className="sr-only">Akcje</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {isPending ? (
                <TableSkeletonRows columns={5} />
              ) : (
                users.map((user) => (
                  <TableRow key={user.id}>
                    <TableCell className="py-3 pl-4">
                      <div className="font-medium">
                        {user.displayName}
                        {user.id === me.id && <span className="ml-1.5 text-xs font-normal text-muted-foreground">(Ty)</span>}
                      </div>
                      {user.email && <div className="text-xs text-muted-foreground">{user.email}</div>}
                    </TableCell>
                    <TableCell className="text-muted-foreground">{user.username}</TableCell>
                    <TableCell>{user.roleName}</TableCell>
                    <TableCell>
                      <UserStatus user={user} />
                    </TableCell>
                    <TableCell className="pr-4 text-right">
                      <div className="flex justify-end gap-1">
                        {user.pendingActivation && user.enabled && (
                          <Button
                            variant="ghost"
                            size="icon-sm"
                            aria-label={`Wyślij ponownie link aktywacyjny do ${user.displayName}`}
                            title="Wyślij link ponownie"
                            disabled={resend.isPending && resend.variables === user.id}
                            onClick={() => resendLink(user)}
                          >
                            <MailIcon />
                          </Button>
                        )}
                        <Button
                          variant="ghost"
                          size="icon-sm"
                          aria-label={`Edytuj użytkownika ${user.displayName}`}
                          title="Edytuj"
                          onClick={() => setEditor({ open: true, user })}
                        >
                          <PencilIcon />
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </Card>
      )}

      <UserFormDialog
        open={editor.open}
        user={editor.user}
        onOpenChange={(open) => setEditor((current) => ({ ...current, open }))}
      />
    </>
  )
}

function UserStatus({ user }: { user: User }) {
  if (!user.enabled) {
    return <Badge variant="destructive">Zablokowany</Badge>
  }
  if (user.pendingActivation) {
    return (
      <Badge variant="outline" className="border-amber-300 bg-amber-50 text-amber-800 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-300">
        Czeka na aktywację
      </Badge>
    )
  }
  return (
    <Badge variant="outline" className="gap-1.5">
      <span aria-hidden className="size-1.5 rounded-full bg-emerald-500" />
      Aktywny
    </Badge>
  )
}
