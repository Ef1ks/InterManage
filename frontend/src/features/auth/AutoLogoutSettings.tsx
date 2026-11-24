import { toast } from 'sonner'

import { SelectField, SwitchField } from '@/components/form'
import { Card, CardContent } from '@/components/ui/card'
import { useAutoLogout, usePinSettings, useSetAutoLogout } from '@/features/auth/api'
import type { AutoLogout } from '@/lib/types'

const TIMES = [5, 10, 15, 20, 30, 45, 60, 120, 240, 480]

function timeLabel(minutes: number) {
  if (minutes < 60 || minutes % 60 !== 0) return `${minutes} min`
  const hours = minutes / 60
  if (hours === 1) return '1 godzina'
  return hours < 5 ? `${hours} godziny` : `${hours} godzin`
}

/** Czy konto wylogowuje się samo i po jakim czasie; każda zmiana zapisuje się od razu. */
export function AutoLogoutSettings() {
  const query = useAutoLogout()
  const pin = usePinSettings()
  const save = useSetAutoLogout()

  const settings = query.data
  const pinRequired = pin.data?.pinRequired ?? true
  const enabled = settings?.enabled ?? true
  const disabled = !settings || save.isPending || !pinRequired

  const change = (values: Partial<AutoLogout>, message: string) => {
    if (!settings) return
    save.mutate(
      { ...settings, ...values },
      {
        onSuccess: () => toast.success(message),
        onError: () => toast.error('Nie udało się zapisać'),
      },
    )
  }

  const minutes = settings?.minutes ?? null
  // Domyślny czas aplikacji może być spoza listy.
  const times = minutes === null || TIMES.includes(minutes) ? TIMES : [...TIMES, minutes].sort((a, b) => a - b)

  return (
    <Card className="self-start lg:col-start-2">
      <CardContent>
        <h2 className="text-base font-semibold">Automatyczne wylogowywanie</h2>
        <p className="mt-1 mb-5 text-sm text-muted-foreground">
          {query.isError
            ? 'Nie udało się wczytać ustawień. Odśwież stronę.'
            : pinRequired
              ? 'Po wylogowaniu wracasz na konto PIN-em, a bez PIN-u hasłem. Ustawienie działa na każdym komputerze.'
              : 'Nie działa, bo na Twoje konto można przejść bez PIN-u. Najpierw włącz „Wymagaj PIN-u przy przejściu na to konto”.'}
        </p>
        <div className="grid gap-4">
          <SwitchField
            label="Wylogowuj mnie automatycznie"
            description={
              enabled
                ? 'Gdy przez wybrany czas nie ruszysz myszą ani klawiaturą w Intermanage.'
                : 'Bez „Nie wylogowuj mnie” przy logowaniu konto i tak wyloguje się po zamknięciu przeglądarki albo po 8 godzinach bez połączenia.'
            }
            checked={enabled}
            onCheckedChange={(next) =>
              change(
                { enabled: next },
                next ? 'Automatyczne wylogowywanie włączone' : 'Automatyczne wylogowywanie wyłączone',
              )
            }
            disabled={disabled}
          />
          {enabled && (
            <SelectField
              label="Po jakim czasie bez ruchu"
              options={times.map((time) => ({ value: String(time), label: timeLabel(time) }))}
              value={minutes === null ? '' : String(minutes)}
              onChange={(value) => {
                const next = Number(value)
                if (value && next !== minutes) change({ minutes: next }, `Wylogowanie po: ${timeLabel(next)}`)
              }}
              disabled={disabled}
            />
          )}
          {enabled && settings?.workdayLockAt && (
            <SwitchField
              label={`Wyloguj też o ${settings.workdayLockAt}`}
              description="Po pracy, nawet w trakcie korzystania. Konto otwarte po tej godzinie działa dalej."
              checked={settings.workdayLock}
              onCheckedChange={(next) =>
                change(
                  { workdayLock: next },
                  `Wylogowanie o ${settings.workdayLockAt} ${next ? 'włączone' : 'wyłączone'}`,
                )
              }
              disabled={disabled}
            />
          )}
        </div>
      </CardContent>
    </Card>
  )
}
