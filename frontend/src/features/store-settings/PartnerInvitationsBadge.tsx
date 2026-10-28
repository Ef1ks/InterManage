import { useStorePartners } from '@/features/store-settings/api'
import { useCurrentStore } from '@/features/stores/current-store'

/** Licznik w menu: zaproszenia do grona sklepów zaprzyjaźnionych, które czekają na odpowiedź wybranego sklepu. */
export function PartnerInvitationsBadge() {
  const store = useCurrentStore()
  const { data } = useStorePartners(store?.id ?? '', store !== null)
  const count = data?.partners.filter((partner) => !partner.sent && !partner.acceptedAt).length ?? 0
  if (count === 0) return null
  return (
    <span
      aria-label={`${count} zaproszeń do sprawdzenia`}
      className="rounded-full bg-amber-500 px-1.5 text-[0.7rem] leading-[1.125rem] font-semibold text-white tabular-nums animate-in zoom-in-50 fade-in-0 duration-200 motion-reduce:animate-none"
    >
      {count}
    </span>
  )
}
