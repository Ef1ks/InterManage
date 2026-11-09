import { CheckIcon, ClipboardCopyIcon, RotateCcwIcon, Undo2Icon } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'

import { CopyButton } from '@/components/CopyButton'
import { FullPageMessage } from '@/components/FullPageMessage'
import { HowTo } from '@/components/HowTo'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { buildMeaMacro } from '@/features/data-entry/mercalys-macro'
import { formatDocumentDate } from '@/features/documents/format'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import type { MeaOverview, StoreSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

import { useMeaChange, useMeaOverview } from './api'
import { basePath } from './paths'

/**
 * Kody MEA, które baza nadaje na bieżąco: lista kodów z wgranych dokumentów dostaw, których jeszcze nie wpisano
 * w Mercalys. Kod znika z listy dopiero po potwierdzeniu wpisania; potwierdzenie da się cofnąć.
 */
export function MeaCodesPage() {
  const store = useCurrentStore()
  if (!store) {
    return (
      <>
        <PageHeader title="Kody MEA" />
        <FullPageMessage title="Brak sklepu" description="Kody MEA wpisuje się osobno w każdym sklepie." inline />
      </>
    )
  }
  return <StoreMeaCodes key={store.id} store={store} />
}

const dateTime = (iso: string) =>
  new Date(iso).toLocaleString('pl-PL', {
    day: 'numeric',
    month: 'numeric',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })

function StoreMeaCodes({ store }: { store: StoreSummary }) {
  const overview = useMeaOverview(store.id)
  const change = useMeaChange(store.id)
  // Zaznaczone kody; nowe kody z kolejnego dokumentu są zaznaczone od razu (lista to zwykle wszystko do wpisania).
  const [unchecked, setUnchecked] = useState<Set<string>>(new Set())
  const pending = useMemo(() => overview.data?.pending ?? [], [overview.data])
  const chosen = pending.filter((item) => !unchecked.has(item.code)).map((item) => item.code)

  const run = (action: Parameters<typeof change.mutate>[0], onDone: (result: MeaOverview) => void) =>
    change.mutate(action, {
      onSuccess: onDone,
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać kodów MEA'),
    })

  const confirm = () =>
    run({ type: 'confirm', codes: chosen }, (result) => {
      setUnchecked(new Set())
      const latest = result.confirmations[0]
      toast.success(`Potwierdzono wpisanie kodów: ${chosen.length}`, {
        action: latest
          ? { label: 'Cofnij', onClick: () => run({ type: 'delete', id: latest.id }, () => undefined) }
          : undefined,
      })
    })

  const copyMacro = async () => {
    try {
      await navigator.clipboard.writeText(buildMeaMacro(chosen))
    } catch {
      toast.error('Nie udało się skopiować makra do schowka')
      return
    }
    toast.success(`Makro w schowku: kodów ${chosen.length}`, {
      description:
        'Naciśnij Alt+E (odtwarzacz od wersji 37): Lista Cadencier, każdy plik z kodem, integracja. Potem potwierdź wpisanie.',
    })
  }

  return (
    <>
      <PageHeader
        title="Kody MEA"
        description={storeLabel(store)}
        actions={
          <Button variant="outline" nativeButton={false} render={<Link to={basePath} />}>
            Dostawa z bazy
          </Button>
        }
      />

      <HowTo
        id="mea-codes"
        className="mb-4"
        title="Jak wpisać kody MEA"
        steps={[
          'Lista pokazuje kody MEA z wgranych dokumentów z bazy i gazetek, których jeszcze nie wpisano w Mercalys.',
          'Wpisz kody w Mercalys: „Makro do Mercalys” kopiuje makro, a Alt+E integruje w Liście Cadencier każdy plik z zaznaczonymi kodami. Można też skopiować same kody.',
          'Kliknij „Potwierdź wpisanie”: kody znikają z listy i nie wrócą przy kolejnych dostawach. Pomyłkę cofniesz w historii.',
        ]}
      />

      <section className="mb-8 rounded-xl border bg-card">
        <header className="flex flex-wrap items-center gap-2 border-b px-4 py-3">
          <h2 className="font-medium">Do wpisania</h2>
          <span className="rounded bg-amber-200 px-1.5 text-sm font-semibold text-amber-950 tabular-nums dark:bg-amber-900 dark:text-amber-50">
            {pending.length}
          </span>
          <div className="ml-auto flex items-center gap-2">
            {chosen.length > 0 && (
              <CopyButton value={chosen.join('\n')} label={`Kopiuj zaznaczone kody (${chosen.length})`} />
            )}
            {chosen.length > 0 && (
              <Button variant="outline" onClick={() => void copyMacro()}>
                <ClipboardCopyIcon />
                Makro do Mercalys
              </Button>
            )}
            <Button onClick={confirm} loading={change.isPending} disabled={chosen.length === 0}>
              <CheckIcon />
              Potwierdź wpisanie ({chosen.length})
            </Button>
          </div>
        </header>
        {overview.isPending ? (
          <p className="px-4 py-6 text-sm text-muted-foreground">Wczytywanie…</p>
        ) : pending.length === 0 ? (
          <p className="px-4 py-6 text-sm text-muted-foreground">
            Wszystkie kody z dokumentów z bazy i gazetek są wpisane. Nowe pojawią się po wgraniu kolejnego dokumentu
            albo gazetki.
          </p>
        ) : (
          <ul
            className="grid grid-cols-[repeat(auto-fill,minmax(14rem,1fr))] gap-2 p-3"
            aria-label="Kody MEA do wpisania"
          >
            {pending.map((item) => {
              const checked = !unchecked.has(item.code)
              return (
                <li key={item.code}>
                  <label
                    className={cn(
                      'flex cursor-pointer items-center gap-3 rounded-lg border px-3 py-2 hover:bg-accent',
                      checked && 'border-blue-600 bg-blue-100 dark:bg-blue-950/60',
                    )}
                  >
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() =>
                        setUnchecked((current) => {
                          const next = new Set(current)
                          if (next.has(item.code)) next.delete(item.code)
                          else next.add(item.code)
                          return next
                        })
                      }
                      className="size-4 accent-blue-600"
                    />
                    <span className="text-lg font-semibold tabular-nums">{item.code}</span>
                    <span className="ml-auto text-right text-xs text-muted-foreground">
                      {item.leaflet ? 'gazetka' : 'bon'} {item.deliveryNumber}
                      <br />
                      {item.leaflet && 'od '}
                      {formatDocumentDate(item.deliveryDate)}
                      {item.deliveryCount > 1 && ` · w ${item.deliveryCount} dokumentach`}
                    </span>
                  </label>
                </li>
              )
            })}
          </ul>
        )}
      </section>

      <section>
        <h2 className="mb-2 font-medium">Ostatnio wpisane</h2>
        {(overview.data?.confirmations ?? []).length === 0 ? (
          <p className="text-sm text-muted-foreground">Nie potwierdzono jeszcze żadnych kodów.</p>
        ) : (
          <ul className="grid gap-2">
            {overview.data?.confirmations.map((confirmation) => {
              const deleted = confirmation.deletedAt !== null
              return (
                <li
                  key={confirmation.id}
                  className={cn('rounded-lg border bg-card px-4 py-3', deleted && 'opacity-60')}
                >
                  <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
                    <span className="font-medium">{dateTime(confirmation.confirmedAt)}</span>
                    <span className="text-muted-foreground">{confirmation.confirmedBy ?? 'nieznany'}</span>
                    <span className="text-muted-foreground">· kodów: {confirmation.codes.length}</span>
                    {deleted && (
                      <span className="rounded bg-stone-200 px-1.5 text-xs font-medium text-stone-900 dark:bg-stone-800 dark:text-stone-100">
                        cofnięte {dateTime(confirmation.deletedAt ?? '')}
                        {confirmation.deletedBy && ` · ${confirmation.deletedBy}`}
                      </span>
                    )}
                    <Button
                      variant="ghost"
                      size="sm"
                      className="ml-auto"
                      disabled={change.isPending}
                      onClick={() =>
                        deleted
                          ? run({ type: 'restore', id: confirmation.id }, () =>
                              toast.success('Przywrócono potwierdzenie'),
                            )
                          : run({ type: 'delete', id: confirmation.id }, () =>
                              toast('Cofnięto potwierdzenie: kody wróciły do wpisania', {
                                action: {
                                  label: 'Przywróć',
                                  onClick: () => run({ type: 'restore', id: confirmation.id }, () => undefined),
                                },
                              }),
                            )
                      }
                    >
                      {deleted ? <RotateCcwIcon /> : <Undo2Icon />}
                      {deleted ? 'Przywróć' : 'Cofnij'}
                    </Button>
                  </div>
                  <p className="mt-1 text-sm tabular-nums text-muted-foreground">{confirmation.codes.join(', ')}</p>
                </li>
              )
            })}
          </ul>
        )}
      </section>
    </>
  )
}
