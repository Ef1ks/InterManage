import { MailIcon } from 'lucide-react'
import { NavLink, Navigate, Route, Routes } from 'react-router'
import { toast } from 'sonner'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { useCan } from '@/features/auth/api'
import { useDeliOverview, useRespondInvitation } from '@/features/deli-order/api'
import { FormEditor } from '@/features/deli-order/FormEditor'
import { OrderDetail } from '@/features/deli-order/OrderDetail'
import { OrdersList } from '@/features/deli-order/OrdersList'
import { OrderTab } from '@/features/deli-order/OrderTab'
import { deliPaths } from '@/features/deli-order/paths'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import type { DeliInvitation, StoreSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

/** Zamówienie garmażu: zamawianie, wysłane i przychodzące zamówienia oraz własny formularz, w zakładkach-podstronach. */
export function DeliOrderPage() {
  const store = useCurrentStore()
  if (!store) {
    return (
      <>
        <PageHeader title="Zamówienie garmażu" />
        <FullPageMessage title="Brak sklepu" description="Zamówienia są składane i przyjmowane w konkretnym sklepie." inline />
      </>
    )
  }
  return <StoreDeliOrders key={store.id} store={store} />
}

function StoreDeliOrders({ store }: { store: StoreSummary }) {
  const { data: overview } = useDeliOverview(store.id)
  const newCount = overview?.newIncomingCount ?? 0

  return (
    <>
      <PageHeader title="Zamówienie garmażu" description={storeLabel(store)} />

      {overview?.pendingInvitations.map((invitation) => (
        <InvitationBanner key={invitation.id} storeId={store.id} invitation={invitation} />
      ))}

      <nav aria-label="Zamówienie garmażu" className="mb-6 flex gap-1 overflow-x-auto rounded-xl bg-muted p-1 sm:w-fit">
        <SubNavLink to={deliPaths.order} end>
          Zamów
        </SubNavLink>
        <SubNavLink to={deliPaths.sent}>Wysłane</SubNavLink>
        <SubNavLink to={deliPaths.incoming}>
          Przychodzące
          {newCount > 0 && (
            <span className="rounded-full bg-amber-500 px-1.5 text-[0.7rem] leading-4 font-semibold text-white tabular-nums">
              {newCount}
            </span>
          )}
        </SubNavLink>
        <SubNavLink to={deliPaths.form}>Formularz</SubNavLink>
      </nav>

      <Routes>
        <Route index element={<OrderTab store={store} />} />
        <Route path="wyslane" element={<OrdersList storeId={store.id} kind="sent" />} />
        <Route path="przychodzace" element={<OrdersList storeId={store.id} kind="incoming" />} />
        <Route path="zamowienia/:orderId" element={<OrderDetail storeId={store.id} />} />
        <Route path="formularz" element={<FormEditor store={store} />} />
        <Route path="*" element={<Navigate to={deliPaths.order} replace />} />
      </Routes>
    </>
  )
}

function SubNavLink({ to, end, children }: { to: string; end?: boolean; children: React.ReactNode }) {
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

/** Zaproszenie innego sklepu do jego formularza: przyjąć może osoba z uprawnieniem Edycja. */
function InvitationBanner({ storeId, invitation }: { storeId: string; invitation: DeliInvitation }) {
  const canEdit = useCan('DELI_ORDERS', 'EDIT')
  const respond = useRespondInvitation(storeId)

  const answer = (accept: boolean) =>
    respond.mutate(
      { invitationId: invitation.id, accept },
      {
        onSuccess: () =>
          toast.success(
            accept
              ? `Możesz zamawiać ze sklepu ${storeLabel(invitation.formStore)}`
              : `Odrzucono zaproszenie sklepu ${storeLabel(invitation.formStore)}`,
          ),
        onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się odpowiedzieć'),
      },
    )

  return (
    <div className="mb-4 flex flex-wrap items-center gap-x-4 gap-y-3 rounded-xl border bg-card px-4 py-3 animate-in fade-in-0 duration-200 motion-reduce:animate-none">
      <MailIcon aria-hidden className="size-5 shrink-0 text-muted-foreground" />
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium">{storeLabel(invitation.formStore)} zaprasza Cię do swojego formularza zamówień</p>
        <p className="text-xs text-muted-foreground">
          {canEdit
            ? 'Po przyjęciu zobaczysz jego formularz w zakładce Zamów i będziesz mógł wysyłać mu zamówienia.'
            : 'Zaproszenie może przyjąć osoba z uprawnieniem do edycji zamówień garmażu.'}
        </p>
      </div>
      {canEdit && (
        <div className="flex gap-2">
          <Button variant="outline" disabled={respond.isPending} onClick={() => answer(false)}>
            Odrzuć
          </Button>
          <Button loading={respond.isPending && respond.variables?.accept} onClick={() => answer(true)}>
            Przyjmij
          </Button>
        </div>
      )}
    </div>
  )
}
