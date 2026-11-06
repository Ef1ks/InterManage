import { SubNavLink } from '@/components/SubNavLink'
import { usePartnerStores } from '@/features/deli-prices/api'
import { pricePaths } from '@/features/deli-prices/format'

/** Własne kalkulacje i kalkulacje każdego sklepu zaprzyjaźnionego; bez znajomych pasek się nie pokazuje. */
export function CalculatorTabs({ storeId }: { storeId: string }) {
  const { data: partners = [] } = usePartnerStores(storeId)
  if (partners.length === 0) return null

  return (
    <nav aria-label="Kalkulacje sklepów" className="mb-6 flex gap-1 overflow-x-auto rounded-xl bg-muted p-1 sm:w-fit">
      <SubNavLink to={pricePaths.list} end>
        Nasze kalkulacje
      </SubNavLink>
      {partners.map((partner) => (
        <SubNavLink key={partner.id} to={pricePaths.partner(partner.id)}>
          {partner.name}
        </SubNavLink>
      ))}
    </nav>
  )
}
