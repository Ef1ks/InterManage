import { toast } from 'sonner'

import { LoadingPanel } from '@/components/LoadingPanel'
import { Card } from '@/components/ui/card'
import { Switch } from '@/components/ui/switch'
import { ApiError } from '@/lib/api'
import type { AgentFeatureKey } from '@/lib/types'

import { useAgentFeatures, useSetAgentFeature } from './api'

type Item = { key: AgentFeatureKey; title: string; description: string }

const GROUPS: { title: string; items: Item[] }[] = [
  {
    title: 'Pliki i foldery',
    items: [
      {
        key: 'FILE_SAVING',
        title: 'Zapis plików dla programu sklepu',
        description: 'Wyłączone: pliki zapisuje przeglądarka, jak bez agenta.',
      },
      {
        key: 'PRICE_FILES',
        title: 'Ceny z kasy',
        description: 'Wysyłanie zmian cen z kasy. Wyłączone: czyta je przeglądarka.',
      },
      {
        key: 'PRICE_CONTROL_FILES',
        title: 'Potwierdzanie zmian cen',
        description: 'Sprawdzanie, czy program sklepu wczytał zmianę cen. Wyłączone: sprawdza przeglądarka.',
      },
      {
        key: 'ATHOS',
        title: 'Wysyłki ATHOS',
        description: 'Odczyt dziennika zamówień MEA. Wyłączone: czyta go przeglądarka.',
      },
      {
        key: 'PRODUCT_FILE_CLEANUP',
        title: 'Czyszczenie folderu nowych artykułów',
        description: 'Usuwanie plików artykułów z poprzednich dni (agent od wersji 1.12.2).',
      },
    ],
  },
  {
    title: 'Raporty i dane',
    items: [
      {
        key: 'REPORTS',
        title: 'Wgrywanie raportów',
        description: 'Raporty z folderów Importu danych. Bez tego nie działa też aktualizacja danych.',
      },
      {
        key: 'REPORT_CLEANUP',
        title: 'Usuwanie wgranych raportów',
        description: 'Wgrany raport znika z folderu następnego dnia.',
      },
      {
        key: 'DATA_UPDATE',
        title: 'Aktualizacja danych przy starcie komputera',
        description: 'Wyłączone: raporty z Mercalys uruchamiasz z menu agenta.',
      },
    ],
  },
  {
    title: 'Makra w Mercalys',
    items: [
      {
        key: 'PRODUCT_FILE_MACRO',
        title: 'Nowe i zmienione artykuły',
        description: 'Po zapisie pliku komputer sklepu sam wczytuje go do Mercalys.',
      },
      {
        key: 'PRICE_CONTROL_MACRO',
        title: 'Zmiany cen',
        description: 'Zmiana cen, która pojawi się w folderze, sama wchodzi do Mercalys.',
      },
      {
        key: 'CAD_PRIORITY_MACRO',
        title: 'ITM8 z cadencier',
        description: 'Po aktualizacji danych komputer sam ustawia ITM8 priorytetowy i aktywny.',
      },
      {
        key: 'REMOTE_MACROS',
        title: 'Makra zlecane z przeglądarki',
        description: 'Uzupełnij ITM8, kody MEA, dostawa z bazy i ITM8 z cadencier na wolnym komputerze.',
      },
    ],
  },
]

const errorMessage = (error: unknown) =>
  error instanceof ApiError ? error.message : 'Nie udało się zapisać ustawienia.'

/** Co agent robi w sklepie: każdą funkcję włącza i wyłącza osoba z edycją wprowadzania danych, zapis od razu. */
export function AgentFeatures({ storeId }: { storeId: string }) {
  const features = useAgentFeatures(storeId)
  const setFeature = useSetAgentFeature(storeId)
  if (features.isPending) return <LoadingPanel className="h-96 rounded-xl" />
  if (features.isError) return <p className="text-sm text-red-700 dark:text-red-300">{errorMessage(features.error)}</p>
  const enabled = new Map(features.data.map((item) => [item.feature, item.enabled]))

  const change = (item: Item, value: boolean) =>
    setFeature.mutate(
      { feature: item.key, enabled: value },
      {
        onSuccess: () =>
          toast.success(`${item.title}: ${value ? 'włączone' : 'wyłączone'}`, {
            action: { label: 'Cofnij', onClick: () => setFeature.mutate({ feature: item.key, enabled: !value }) },
          }),
        onError: (error) => toast.error(errorMessage(error)),
      },
    )

  return (
    <div className="grid gap-4">
      <p className="text-sm text-muted-foreground">
        Przełączniki dotyczą całego sklepu. Pliki, które czekały na wyłączone makro, zostają do wczytania ręcznie. Jeden
        komputer wyłączysz w oknie ustawień agenta na tym komputerze.
      </p>
      {GROUPS.map((group) => (
        <Card key={group.title} className="grid gap-3 p-4">
          <h3 className="font-medium">{group.title}</h3>
          {group.items.map((item) => (
            <div key={item.key} className="flex items-start justify-between gap-4 border-t pt-3">
              <div className="grid gap-1">
                <h4 className="text-sm font-medium">{item.title}</h4>
                <p className="text-sm text-muted-foreground">{item.description}</p>
              </div>
              <Switch
                aria-label={item.title}
                checked={enabled.get(item.key) ?? false}
                disabled={setFeature.isPending}
                onCheckedChange={(value) => change(item, value)}
              />
            </div>
          ))}
        </Card>
      ))}
    </div>
  )
}
