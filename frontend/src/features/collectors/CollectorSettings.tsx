import { useState } from 'react'
import { DownloadIcon, KeyRoundIcon, PlusIcon, PrinterIcon, ScanBarcodeIcon, Trash2Icon } from 'lucide-react'
import { toast } from 'sonner'

import { Code128 } from '@/components/Code128'
import { Form, FormActions, TextField } from '@/components/form'
import { LoadingPanel } from '@/components/LoadingPanel'
import { Badge } from '@/components/ui/badge'
import { Button, buttonVariants } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { ApiError } from '@/lib/api'
import { printCode128 } from '@/lib/code128'
import type { StoreCollector } from '@/lib/types'
import { cn } from '@/lib/utils'

import { useAddCollector, useCollectorCode, useCollectors, useRemoveCollector, useRestoreCollector } from './api'

const dateTime = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'short' })
const time = new Intl.DateTimeFormat('pl-PL', { timeStyle: 'short' })

// Bez „http://” kod jest krótszy, więc szersze kreski i łatwiejszy skan; przeglądarka sama dopisze http.
// Enter na końcu (\r): po zeskanowaniu przeglądarka od razu otwiera adres.
const INSTALLER_ADDRESS = 'intermanage.pl/k'
const INSTALLER_CODE = INSTALLER_ADDRESS + '\r'

const printInstallerCode = () =>
  printCode128(INSTALLER_CODE, 'Intermanage - instalator kolektora', INSTALLER_ADDRESS, [
    'Na kolektorze włącz DataWedge.',
    'Otwórz przeglądarkę i stuknij w pasek adresu.',
    'Zeskanuj kod. Strona instalatora otworzy się sama.',
    'Wybierz „Pobierz instalator”, potem Otwórz i Zainstaluj.',
  ])

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

type ShownCode = { id: string; code: string; expiresAt: string; pairedBefore: string | null }

/**
 * Urządzenia sklepu (Kolektory, `INVENTORY:EDIT`): dodanie daje jednorazowy kod do wpisania na kolektorze,
 * lista mówi, czy kolektor ma aktualne dane. Kod znika, gdy kolektor się nim połączy.
 */
export function CollectorSettings({ storeId }: { storeId: string }) {
  const [shown, setShown] = useState<ShownCode | null>(null)
  const collectors = useCollectors(storeId, { waiting: shown !== null })
  const add = useAddCollector(storeId)
  const newCode = useCollectorCode(storeId)
  const remove = useRemoveCollector(storeId)
  const restore = useRestoreCollector(storeId)
  const [name, setName] = useState('')

  if (collectors.isPending) return <LoadingPanel className="h-72 rounded-xl" />
  if (collectors.isError) {
    return (
      <p className="text-sm text-red-700 dark:text-red-300">
        {errorMessage(collectors.error, 'Nie udało się wczytać kolektorów.')}
      </p>
    )
  }
  const active = collectors.data.filter((collector) => !collector.deletedAt)
  const removed = collectors.data.filter((collector) => collector.deletedAt)
  const pairing = shown && active.find((collector) => collector.id === shown.id)
  const justPaired = !!pairing && pairing.pairedAt !== shown?.pairedBefore

  const showCode = (result: StoreCollector[], before: StoreCollector[]) => {
    const created = result.find((collector) => collector.pairCode)
    if (!created?.pairCode || !created.pairExpiresAt) return
    setShown({
      id: created.id,
      code: created.pairCode,
      expiresAt: created.pairExpiresAt,
      pairedBefore: before.find((collector) => collector.id === created.id)?.pairedAt ?? null,
    })
  }

  const submit = () => {
    const value = name.trim()
    if (!value) return
    add.mutate(value, {
      onSuccess: (result) => {
        showCode(result, collectors.data)
        setName('')
      },
      onError: (error) => toast.error(errorMessage(error, 'Nie udało się dodać kolektora.')),
    })
  }

  const requestCode = (collector: StoreCollector) =>
    newCode.mutate(collector.id, {
      onSuccess: (result) => showCode(result, collectors.data),
      onError: (error) => toast.error(errorMessage(error, 'Nie udało się utworzyć kodu.')),
    })

  const removeCollector = (collector: StoreCollector) =>
    remove.mutate(collector.id, {
      onSuccess: () => {
        if (shown?.id === collector.id) setShown(null)
        toast.success(`Usunięto ${collector.name}. Nie pobierze już danych.`, {
          action: {
            label: 'Przywróć',
            onClick: () =>
              restore.mutate(collector.id, {
                onError: (error) => toast.error(errorMessage(error, 'Nie udało się przywrócić kolektora.')),
              }),
          },
        })
      },
      onError: (error) => toast.error(errorMessage(error, 'Nie udało się usunąć kolektora.')),
    })

  return (
    <div className="grid gap-4">
      {shown && pairing && (
        <Card className="grid gap-3 p-4" aria-live="polite">
          <h3 className="font-medium">{justPaired ? `${pairing.name} jest połączony` : `Kod dla: ${pairing.name}`}</h3>
          {justPaired ? (
            <p className="rounded-lg bg-emerald-200 px-3 py-2 text-sm text-emerald-950 dark:bg-emerald-900/60 dark:text-emerald-100">
              Zostaw go na podstawce, aż pobierze dane sklepu.
            </p>
          ) : (
            <>
              <p className="text-4xl font-semibold tracking-wide tabular-nums">
                {shown.code.slice(0, 4)} {shown.code.slice(4)}
              </p>
              <Code128 value={shown.code} />
              <p className="max-w-prose text-sm text-muted-foreground">
                Postaw kolektor na podstawce, uruchom na nim Intermanage i zeskanuj ten kod albo go wpisz. Działa raz,
                do {time.format(new Date(shown.expiresAt))}.
              </p>
            </>
          )}
          <div>
            <Button variant="outline" size="sm" onClick={() => setShown(null)}>
              Gotowe
            </Button>
          </div>
        </Card>
      )}

      <Card className="grid gap-4 p-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="grid max-w-prose min-w-0 flex-1 basis-64 gap-1">
            <h3 className="font-medium">Instalator</h3>
            <p className="text-sm text-muted-foreground">
              Na kolektorze otwórz przeglądarkę, wpisz adres{' '}
              <span className="font-mono font-medium text-foreground">http://intermanage.pl/k</span> i uruchom pobrany
              plik. Bez sieci na kolektorze: pobierz instalator na komputerze i skopiuj go na kolektor. Kolejne wersje
              kolektor pobiera sam na podstawce.
            </p>
            <p className="text-sm text-muted-foreground">
              Z włączonym DataWedge adresu nie trzeba wpisywać: otwórz przeglądarkę na kolektorze, stuknij w pasek
              adresu i zeskanuj kod poniżej. Strona otworzy się sama. Z ekranu laser czyta słabo, wydrukowany kod czyta
              zawsze.
            </p>
          </div>
          <a href="/api/collector/setup" download className={buttonVariants({ variant: 'outline' })}>
            <DownloadIcon aria-hidden />
            Pobierz instalator
          </a>
        </div>
        <div className="grid justify-items-start gap-2">
          <Code128 value={INSTALLER_CODE} />
          <Button variant="outline" size="sm" onClick={printInstallerCode}>
            <PrinterIcon aria-hidden />
            Drukuj kod
          </Button>
        </div>
      </Card>

      <Card className="grid gap-3 p-4">
        <h3 className="font-medium">Kolektory sklepu</h3>
        {active.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            Brak kolektorów. Dodaj pierwszy, a dostaniesz kod do wpisania na nim.
          </p>
        ) : (
          <ul className="grid gap-2">
            {active.map((collector) => (
              <li key={collector.id} className="flex items-start gap-3 rounded-xl border px-3 py-2.5 text-sm">
                <ScanBarcodeIcon aria-hidden className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
                <div className="grid min-w-0 flex-1 gap-1">
                  <p className="flex flex-wrap items-center gap-2 font-medium">
                    <span className="break-words">{collector.name}</span>
                    <CollectorState collector={collector} />
                    {collector.newVersion && (
                      <Badge
                        className="bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-100"
                        title="Kolektor pobierze ją sam, gdy stanie na podstawce"
                      >
                        czeka na wersję {collector.newVersion}
                      </Badge>
                    )}
                  </p>
                  <p className="text-xs text-muted-foreground">
                    {collector.catalogSyncedAt
                      ? `Dane pobrane ${dateTime.format(new Date(collector.catalogSyncedAt))}`
                      : 'Jeszcze nie pobrał danych'}
                    {collector.lastSeenAt &&
                      `, ostatnio na podstawce ${dateTime.format(new Date(collector.lastSeenAt))}`}
                    {collector.lastVersion && `, wersja ${collector.lastVersion}`}
                  </p>
                </div>
                <Button
                  variant="ghost"
                  size="icon-sm"
                  aria-label={`Nowy kod dla ${collector.name}`}
                  title="Nowy kod (po wyczyszczeniu kolektora)"
                  onClick={() => requestCode(collector)}
                  disabled={newCode.isPending}
                >
                  <KeyRoundIcon aria-hidden />
                </Button>
                <Button
                  variant="ghost"
                  size="icon-sm"
                  aria-label={`Usuń ${collector.name}`}
                  title="Usuń kolektor (przestaje pobierać dane)"
                  onClick={() => removeCollector(collector)}
                  disabled={remove.isPending}
                >
                  <Trash2Icon aria-hidden />
                </Button>
              </li>
            ))}
          </ul>
        )}
        <Form
          onSubmit={(event) => {
            event.preventDefault()
            submit()
          }}
        >
          <TextField
            label="Nazwa nowego kolektora, np. Kolektor 1"
            value={name}
            onChange={(event) => setName(event.target.value)}
            maxLength={100}
          />
          <FormActions>
            <Button type="submit" loading={add.isPending} disabled={!name.trim()}>
              <PlusIcon aria-hidden />
              Dodaj kolektor
            </Button>
          </FormActions>
        </Form>
        {removed.length > 0 && (
          <div className="grid gap-1 border-t pt-3">
            <p className="text-xs font-medium text-muted-foreground">Usunięte</p>
            {removed.map((collector) => (
              <div key={collector.id} className="flex items-center justify-between gap-2 text-sm text-muted-foreground">
                <span className="min-w-0 break-words">{collector.name}</span>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => restore.mutate(collector.id)}
                  disabled={restore.isPending}
                >
                  Przywróć
                </Button>
              </div>
            ))}
          </div>
        )}
      </Card>
    </div>
  )
}

function CollectorState({ collector }: { collector: StoreCollector }) {
  const state = !collector.pairedAt ? 'waiting' : collector.catalogCurrent ? 'current' : 'old'
  return (
    <Badge
      className={cn(
        state === 'waiting' && 'bg-muted text-foreground',
        state === 'current' && 'bg-emerald-200 text-emerald-950 dark:bg-emerald-900/70 dark:text-emerald-100',
        state === 'old' && 'bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100',
      )}
    >
      {state === 'waiting' ? 'niepołączony' : state === 'current' ? 'aktualne dane' : 'stare dane'}
    </Badge>
  )
}
