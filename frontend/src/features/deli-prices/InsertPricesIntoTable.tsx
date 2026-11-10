import { InsertIntoDataEntry } from '@/features/data-entry/InsertIntoDataEntry'
import { emptyRow, withScannedProduct } from '@/features/data-entry/rows'

export type PriceLine = { ean: string; salePrice: number }

/**
 * Produkty ze zmienioną ceną trafiają do tabeli wprowadzania danych, gdzie „Zmień ceny” wystawia plik zmian cen.
 * Wiersze jak po wpisaniu EAN-u, z ceną sprzedaży z kalkulacji. `catalog` zostaje z kartoteki, więc tabela widzi zmianę
 * ceny. Ilość na sztywno, bo kod z etykiety wagi przeliczałby ją z ceny.
 */
export function InsertPricesIntoTable({ storeId, lines }: { storeId: string; lines: PriceLine[] }) {
  return (
    <InsertIntoDataEntry
      storeId={storeId}
      eans={lines.map((line) => line.ean)}
      buildRows={(byEan) =>
        lines.map(({ ean, salePrice }) => ({
          ...withScannedProduct({ ...emptyRow(), ean }, byEan.get(ean.replace(/^0+/, '')) ?? null),
          quantity: 1,
          salePrice,
        }))
      }
      label={`Do wprowadzania danych (${lines.length})`}
      title="Produkty, których cena różni się od ceny na półce"
      successTitle="Zmienione ceny w tabeli"
      successDescription={(count) => `Produkty: ${count}. Wskaż dostawcę i kliknij „Zmień ceny”, żeby wystawić plik.`}
      askDescription="Zastąpić je produktami ze zmienioną ceną czy dopisać produkty na końcu?"
    />
  )
}
