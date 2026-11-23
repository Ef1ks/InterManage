import { ScrollView } from 'react-native'

import { useDockInset } from '@/components/FloatingDock'
import { LinkCard } from '@/components/report-parts'
import { Card, Muted } from '@/components/ui'
import { useAuth } from '@/lib/auth'

/** Raporty sprzedaży dostępne w telefonie; pozostałe są w przeglądarce. */
export default function ReportsScreen() {
  const { canSeeReports, resultStores } = useAuth()
  const dockInset = useDockInset()

  return (
    <ScrollView contentContainerStyle={{ padding: 20, gap: 12, paddingBottom: dockInset }}>
      {resultStores.length > 0 ? (
        <LinkCard
          title="Wyniki sklepów"
          description="Tydzień wobec roku temu, cel marży i ostatni dzień ze sprzedażą w każdym sklepie."
          href="/store-results"
        />
      ) : null}
      {canSeeReports ? (
        <>
          <LinkCard
            title="Porównanie sprzedaży"
            description="Dzień, tydzień albo miesiąc wobec roku temu, od rejonów do artykułów."
            href="/reports/comparison"
          />
          <LinkCard
            title="Podsumowanie sprzedaży i strat"
            description="Marża, likwidacje i marża po stratach według rejonów, z celem marży na rok."
            href="/reports/summary"
          />
          <LinkCard
            title="Raport lady"
            description="Mięso, garmaż i drób: sprzedaż i marża po likwidacjach, także wobec innych sklepów."
            href="/reports/counter"
          />
          <LinkCard
            title="Braki towaru"
            description="Co się skończyło albo zaraz skończy, do sprawdzenia na sali."
            href="/reports/stock-gaps"
          />
          <Muted style={{ paddingHorizontal: 4, lineHeight: 19 }}>
            Pozostałe raporty, wydruki i ustawienia raportów są w aplikacji w przeglądarce.
          </Muted>
        </>
      ) : (
        <Card>
          <Muted style={{ fontSize: 15 }}>Twoja ranga w tym sklepie nie ma dostępu do raportów sprzedaży.</Muted>
        </Card>
      )}
    </ScrollView>
  )
}
