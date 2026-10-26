import { useState } from 'react'
import { SendIcon, XIcon } from 'lucide-react'
import { toast } from 'sonner'

import { MultiComboboxField } from '@/components/form'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useInvitableStores, useInvitations, useInviteStores, useRevokeInvitation } from '@/features/deli-order/api'
import { storeLabel } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import type { DeliInvitation, InvitationStatus } from '@/lib/types'

/** Zapraszanie innych sklepów do formularza (tryb edycji). Po akceptacji sklep widzi formularz i może zamawiać. */
export function InvitationsPanel({ storeId }: { storeId: string }) {
  const { data: invitations = [] } = useInvitations(storeId, true)
  const { data: stores = [] } = useInvitableStores(storeId, true)
  const invite = useInviteStores(storeId)
  const revoke = useRevokeInvitation(storeId)
  const [selected, setSelected] = useState<string[]>([])

  // Sklepy z czekającym albo przyjętym zaproszeniem nie trafiają na listę; odrzucone można zaprosić ponownie.
  const invitedIds = new Set(
    invitations.filter((invitation) => invitation.status !== 'DECLINED').map((invitation) => invitation.store.id),
  )
  const options = stores
    .filter((store) => !invitedIds.has(store.id))
    .map((store) => ({ value: store.id, label: storeLabel(store) }))

  const send = () =>
    invite.mutate(selected, {
      onSuccess: () => {
        toast.success(selected.length === 1 ? 'Zaproszenie wysłane' : `Wysłano ${selected.length} zaproszenia`)
        setSelected([])
      },
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się wysłać zaproszeń'),
    })

  return (
    <Card className="mb-6 gap-4 px-4 py-4">
      <div>
        <h2 className="text-base font-semibold">Sklepy zamawiające z tego formularza</h2>
        <p className="mt-0.5 text-sm text-muted-foreground">
          Zaproszony sklep musi przyjąć zaproszenie. Potem widzi ten formularz z Twoimi produktami i wysyła Ci zamówienia.
        </p>
      </div>

      {invitations.length > 0 && (
        <ul className="divide-y rounded-xl border">
          {invitations.map((invitation) => (
            <li key={invitation.id} className="flex items-center gap-3 px-3 py-2.5">
              <span className="min-w-0 flex-1 truncate text-sm tabular-nums">{storeLabel(invitation.store)}</span>
              <InvitationBadge status={invitation.status} />
              <Button
                variant="ghost"
                size="icon-sm"
                aria-label={revokeLabel(invitation)}
                title={revokeLabel(invitation)}
                disabled={revoke.isPending}
                onClick={() =>
                  revoke.mutate(invitation.id, {
                    onSuccess: () => toast.success(`${storeLabel(invitation.store)} nie ma już dostępu do formularza`),
                    onError: (error) =>
                      toast.error(error instanceof ApiError ? error.message : 'Nie udało się cofnąć zaproszenia'),
                  })
                }
              >
                <XIcon />
              </Button>
            </li>
          ))}
        </ul>
      )}

      <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
        <MultiComboboxField
          className="flex-1"
          label="Zaproś sklepy"
          options={options}
          value={selected}
          onChange={setSelected}
          placeholder="Wpisz numer albo nazwę sklepu"
          emptyText="Nie ma sklepu o takim numerze ani nazwie"
        />
        <Button size="xl" disabled={selected.length === 0} loading={invite.isPending} onClick={send}>
          <SendIcon />
          Zaproś
        </Button>
      </div>
    </Card>
  )
}

function revokeLabel(invitation: DeliInvitation) {
  return invitation.status === 'ACCEPTED'
    ? `Odbierz dostęp sklepowi ${storeLabel(invitation.store)}`
    : `Cofnij zaproszenie dla ${storeLabel(invitation.store)}`
}

function InvitationBadge({ status }: { status: InvitationStatus }) {
  if (status === 'ACCEPTED') {
    return (
      <Badge variant="outline" className="gap-1.5">
        <span aria-hidden className="size-1.5 rounded-full bg-emerald-500" />
        Zamawia
      </Badge>
    )
  }
  if (status === 'PENDING') {
    return (
      <Badge variant="outline" className="border-amber-300 bg-amber-50 text-amber-800 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-300">
        Czeka na akceptację
      </Badge>
    )
  }
  return <Badge variant="secondary">Odrzucone</Badge>
}
