import { useState, type ReactNode } from 'react'
import { DownloadIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { downloadRcpFile } from '@/features/staff-time/api'
import { ApiError } from '@/lib/api'
import { formatMonth } from '@/lib/dates'

type Props = {
  open: boolean
  storeId: string
  /** RRRR-MM */
  month: string
  onClose: () => void
}

/**
 * Plik ewidencji czasu pracy dla Symfonii R2Płatnik z instrukcją importu krok po kroku (pomoc Symfonii: „Import
 * ewidencji czasu pracy”). Pobranie jest w kroku 2, żeby kadry miały instrukcję przed oczami.
 */
export function SymfoniaExportDialog({ open, storeId, month, onClose }: Props) {
  const [loading, setLoading] = useState(false)

  const download = () => {
    setLoading(true)
    downloadRcpFile(storeId, month)
      .catch((cause) =>
        toast.error(cause instanceof ApiError ? cause.message : 'Nie udało się przygotować pliku do Symfonii'),
      )
      .finally(() => setLoading(false))
  }

  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent className="gap-5 p-6 sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Eksport do Symfonii R2Płatnik · {formatMonth(month)}</DialogTitle>
          <DialogDescription>
            Plik z czasem pracy wszystkich pracowników sklepu z umową o pracę, za dni, które już minęły. Pracownika
            wskazuje numer karty RCP nadany w aplikacji.
          </DialogDescription>
        </DialogHeader>

        <ol className="grid gap-4">
          <Step number={1} title="Raz dla każdego pracownika: wpisz numer karty RCP">
            W R2Płatniku otwórz pracownika, <b>Aktualne zatrudnienie</b>, i w polu <b>Numer karty RCP</b> wpisz jego nr
            RCP z aplikacji. Numer jest w profilu w Kadrach i w ustawieniach Czasu pracy (koło zębate, lista kart). Nie
            zmienia się, więc wystarczy zrobić to raz.
          </Step>
          <Step number={2} title="Pobierz plik">
            <div className="grid gap-2">
              <span>
                Liczą się dni zakończone, dzisiejszy po końcu zmiany i odbiciu wyjścia. Przed pobraniem rozstrzygnij
                czas poza grafikiem: bez decyzji nie wchodzi do pliku.
              </span>
              <Button type="button" className="w-fit" loading={loading} onClick={download}>
                <DownloadIcon aria-hidden />
                Pobierz plik za {formatMonth(month)}
              </Button>
            </div>
          </Step>
          <Step number={3} title="Otwórz import w R2Płatniku">
            Z menu głównego wybierz <b>Zbiory - Import danych - Import RCP - Import ewidencji czasu pracy</b>.
          </Step>
          <Step number={4} title="Ustaw format pliku (tylko za pierwszym razem)">
            <ul className="grid list-disc gap-1 pl-4">
              <li>
                Zakładka <b>Ustawienia</b>: rekordy o <b>zmiennej długości</b>, <b>znak rozdzielający pola</b> to
                średnik <code className="rounded bg-muted px-1">;</code>, <b>znak ograniczający pola</b> zostaw pusty.
              </li>
              <li>
                <b>Format daty</b>: dzień-miesiąc-rok z myślnikami (np. 02-11-2026), <b>format czasu</b>: godzina i
                minuty (np. 7:45).
              </li>
              <li>
                Zakładka <b>Zaawansowane</b>: zaznacz <b>Domyślne</b>. Pola w pliku idą po kolei: numer karty RCP, data,
                godzina wejścia, godzina wyjścia, zal. godz. wejścia, zal. godz. wyjścia.
              </li>
            </ul>
          </Step>
          <Step number={5} title="Zaimportuj">
            Zakładka <b>Import</b>: <b>Od daty</b> i <b>Do daty</b> to pierwszy i ostatni dzień miesiąca,{' '}
            <b>Plik źródłowy</b> to pobrany plik (rcp-…-{month}.txt). <b>Zakładaj nowych pracowników</b> zostaw
            odznaczone: pracownik bez numeru karty w R2Płatniku zostanie pominięty, a nie założony drugi raz.
          </Step>
          <Step number={6} title="Sprawdź i uzupełnij">
            W ewidencji czasu pracy sprawdź kilka osób. Urlopy i inne nieobecności wpisz w R2Płatniku ręcznie, bo ten
            import przenosi tylko godziny pracy. R2Płatnik sumuje wpisy z jednego dnia, więc przed ponownym importem
            tego samego miesiąca usuń wpisy z poprzedniego, żeby godziny się nie zdublowały.
          </Step>
        </ol>
      </DialogContent>
    </Dialog>
  )
}

function Step({ number, title, children }: { number: number; title: string; children: ReactNode }) {
  return (
    <li className="grid grid-cols-[1.75rem_1fr] gap-3">
      <span
        aria-hidden
        className="flex size-7 items-center justify-center rounded-full bg-foreground text-sm font-semibold text-background tabular-nums"
      >
        {number}
      </span>
      <div className="grid gap-1 text-sm">
        <p className="font-medium">{title}</p>
        <div className="text-muted-foreground [&_b]:font-medium [&_b]:text-foreground">{children}</div>
      </div>
    </li>
  )
}
