import {
  CheckCircle2Icon,
  ChevronRightIcon,
  ClipboardListIcon,
  HandshakeIcon,
  TimerIcon,
  TruckIcon,
} from 'lucide-react'
import type { ComponentType } from 'react'
import { Link } from 'react-router'

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { useCan } from '@/features/auth/api'
import { useDeliOverview } from '@/features/deli-order/api'
import { useIncomingTransfersCount } from '@/features/documents/api'
import { useStorePartners } from '@/features/store-settings/api'
import { useTimeAttention } from '@/features/staff-time/api'

type Item = {
  key: string
  label: string
  to: string
  icon: ComponentType<{ className?: string }>
  count: number
  pending: boolean
}

/**
 * Sprawy, które czekają na kierownika w wybranym sklepie: te same liczniki co w menu, zebrane w jednym miejscu, każda
 * pozycja tylko z uprawnieniem do swojego narzędzia. Pozycje z zerem się chowają; gdy nic nie czeka, kafelek to mówi.
 */
export function AttentionCard({ storeId }: { storeId: string }) {
  const canTime = useCan('STAFF_TIME', 'EDIT')
  const canDeli = useCan('DELI_ORDERS')
  const canDocuments = useCan('DOCUMENTS')
  const canPartners = useCan('DOCUMENTS', 'EDIT')

  const time = useTimeAttention(storeId, canTime)
  const deli = useDeliOverview(storeId, canDeli)
  const transfers = useIncomingTransfersCount(storeId, canDocuments)
  const partners = useStorePartners(storeId, canPartners)

  const items: Item[] = []
  if (canTime) {
    items.push({
      key: 'time',
      label: 'Dni do sprawdzenia w czasie pracy',
      to: '/czas-pracy',
      icon: TimerIcon,
      count: time.data?.total ?? 0,
      pending: time.isPending,
    })
  }
  if (canDeli) {
    items.push(
      {
        key: 'deli-orders',
        label: 'Nowe zamówienia garmażu',
        to: '/zamowienie-garmazu/przychodzace',
        icon: ClipboardListIcon,
        count: deli.data?.newIncomingCount ?? 0,
        pending: deli.isPending,
      },
      {
        key: 'deli-invitations',
        label: 'Zaproszenia do formularza garmażu',
        to: '/zamowienie-garmazu',
        icon: ClipboardListIcon,
        count: deli.data?.pendingInvitations.length ?? 0,
        pending: deli.isPending,
      },
    )
  }
  if (canDocuments) {
    items.push({
      key: 'transfers',
      label: 'Ruchy międzymagazynowe do wprowadzenia',
      to: '/dokumenty/do-wprowadzenia',
      icon: TruckIcon,
      count: transfers.data ?? 0,
      pending: transfers.isPending,
    })
  }
  if (canPartners) {
    items.push({
      key: 'partners',
      label: 'Zaproszenia od sklepów zaprzyjaźnionych',
      to: '/ustawienia-sklepu/sklepy-zaprzyjaznione',
      icon: HandshakeIcon,
      count: partners.data?.partners.filter((partner) => !partner.sent && !partner.acceptedAt).length ?? 0,
      pending: partners.isPending,
    })
  }

  if (items.length === 0) return null
  const waiting = items.filter((item) => item.count > 0)
  const loading = items.some((item) => item.pending)

  return (
    <Card>
      <CardHeader>
        <CardTitle>Do reakcji</CardTitle>
        <CardDescription>Sprawy czekające w tym sklepie</CardDescription>
      </CardHeader>
      <CardContent>
        {waiting.length > 0 ? (
          <ul className="-mx-2 space-y-0.5">
            {waiting.map(({ key, label, to, icon: Icon, count }) => (
              <li key={key}>
                <Link
                  to={to}
                  className="group flex items-center gap-3 rounded-lg px-2 py-2 text-sm transition-colors hover:bg-muted/60 focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
                >
                  <Icon className="size-4 shrink-0 text-muted-foreground" />
                  <span className="min-w-0 flex-1">{label}</span>
                  <span className="rounded-full bg-amber-300 px-2 text-xs leading-5 font-semibold text-amber-950 tabular-nums dark:bg-amber-500/80 dark:text-amber-950">
                    {count}
                  </span>
                  <ChevronRightIcon className="size-4 shrink-0 text-muted-foreground transition-transform duration-150 ease-out group-hover:translate-x-0.5 motion-reduce:transition-none" />
                </Link>
              </li>
            ))}
          </ul>
        ) : loading ? (
          <div className="h-16 animate-pulse rounded-lg bg-muted" />
        ) : (
          <p className="flex items-center gap-2 text-sm text-muted-foreground">
            <CheckCircle2Icon className="size-4 text-emerald-600 dark:text-emerald-400" />
            Nic nie czeka, wszystko załatwione
          </p>
        )}
      </CardContent>
    </Card>
  )
}
