import { useRef, useState } from 'react'
import { FileCheckIcon, PaperclipIcon, PlusIcon, PrinterIcon, Trash2Icon, Undo2Icon } from 'lucide-react'
import { toast } from 'sonner'

import { DateField, Form, FormActions, FormNote, SelectField, TextField, TimeField } from '@/components/form'
import { GusButton } from '@/components/GusButton'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { dayMonth, formatDay } from '@/features/staff-schedule/schedule-utils'
import {
  printDecision,
  signedFileUrl,
  useSignedFile,
  useSimulator,
  useTimeBalance,
  useTimeDecisions,
  useTimeSettings,
  useTimeSettingsActions,
} from '@/features/staff-time/api'
import { STATUS_STYLES, clockOf, hm } from '@/features/staff-time/time-utils'
import { ApiError } from '@/lib/api'
import { todayIso } from '@/lib/dates'
import type { TimeCardRow, TimeCards, TimeDecisionKind, TimeSegment, TimeSettings } from '@/lib/types'
import { cn } from '@/lib/utils'

const failed = (cause: unknown, fallback: string) => (cause instanceof ApiError ? cause.message : fallback)

const PRINTED: TimeDecisionKind[] = ['EMPLOYER_ORDER', 'EMPLOYEE_REQUEST']

const OVERTIME_CHOICES: {
  decision: 'EMPLOYER_ORDER' | 'EMPLOYEE_REQUEST' | 'REJECTED'
  label: string
  description: string
}[] = [
  {
    decision: 'EMPLOYER_ORDER',
    label: 'Na polecenie pracodawcy',
    description: 'Liczy się do czasu pracy jako nadgodziny. Drukuje się polecenie pracy w godzinach nadliczbowych.',
  },
  {
    decision: 'EMPLOYEE_REQUEST',
    label: 'Na wniosek pracownika',
    description:
      'Pracownik chciał popracować dłużej, a Ty się zgadzasz. Liczy się do czasu pracy, drukuje się wniosek ze zgodą.',
  },
  {
    decision: 'REJECTED',
    label: 'Odrzuć: własna inicjatywa',
    description: 'Nikt nie polecił tej pracy. Czas się nie liczy, w karcie zostaje czas według grafiku.',
  },
]

/** Decyzja o odcinku poza grafikiem albo braku czasu. Po przyjęciu nadgodzin od razu otwiera się wydruk. */
export function SegmentDialog({
  storeId,
  departmentId,
  row,
  segment,
  onClose,
}: {
  storeId: string
  departmentId: string
  row: TimeCardRow | null
  segment: TimeSegment | null
  onClose: () => void
}) {
  return (
    <Dialog open={row !== null && segment !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="gap-4 p-6 sm:max-w-md">
        {row && segment && (
          <SegmentBody
            key={segment.part + segment.start}
            storeId={storeId}
            departmentId={departmentId}
            row={row}
            segment={segment}
            onClose={onClose}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

function SegmentBody({
  storeId,
  departmentId,
  row,
  segment,
  onClose,
}: {
  storeId: string
  departmentId: string
  row: TimeCardRow
  segment: TimeSegment
  onClose: () => void
}) {
  const { decide, remove, restore } = useTimeDecisions(storeId, departmentId)
  const [note, setNote] = useState(segment.note ?? '')
  // Decyzja zapisana w tym oknie, zanim karty pobiorą się od nowa: okno od razu pokazuje wydruk i dołączanie.
  const [justDecided, setJustDecided] = useState<{
    id: string
    decision: 'EMPLOYER_ORDER' | 'EMPLOYEE_REQUEST'
  } | null>(null)
  const overtime = segment.part === 'OVERTIME'
  const decisionId = segment.decisionId ?? justDecided?.id ?? null
  const status = segment.decisionId ? segment.status : (justDecided?.decision ?? segment.status)
  const decided = decisionId !== null

  const choose = (decision: TimeDecisionKind) =>
    decide.mutate(
      {
        employeeId: row.employeeId,
        kind: overtime ? 'OVERTIME' : 'SHORTFALL',
        start: segment.start,
        end: segment.end,
        decision,
        note: note.trim() || null,
      },
      {
        onSuccess: ({ id }) => {
          if (decision === 'EMPLOYER_ORDER' || decision === 'EMPLOYEE_REQUEST') {
            // Okno zostaje: wydruk i podpisany dokument są krok dalej, nie od razu.
            setJustDecided({ id, decision })
            toast.success('Nadgodziny przyjęte')
          } else {
            onClose()
            toast.success(decision === 'REJECTED' ? 'Odrzucone: czas liczy się według grafiku' : 'Usprawiedliwione')
          }
        },
        onError: (cause) => toast.error(failed(cause, 'Nie udało się zapisać decyzji')),
      },
    )

  const undo = (id: string) =>
    remove.mutate(id, {
      onSuccess: () => {
        onClose()
        toast.success('Decyzja cofnięta', {
          action: {
            label: 'Przywróć',
            onClick: () =>
              restore.mutate(id, {
                onError: (cause) => toast.error(failed(cause, 'Nie udało się przywrócić decyzji')),
              }),
          },
        })
      },
      onError: (cause) => toast.error(failed(cause, 'Nie udało się cofnąć decyzji')),
    })

  return (
    <>
      <DialogHeader>
        <DialogTitle>{overtime ? 'Czas poza grafikiem' : 'Brak czasu z grafiku'}</DialogTitle>
        <DialogDescription>
          {row.name} · {formatDay(segment.start.slice(0, 10))} · {clockOf(segment.start)}–{clockOf(segment.end)} (
          {hm(segment.minutes)})
        </DialogDescription>
      </DialogHeader>
      <div className="flex items-center gap-2 rounded-lg bg-muted p-2.5 text-sm">
        <span aria-hidden className={cn('size-3 shrink-0 rounded-sm', STATUS_STYLES[status].bar)} />
        <span>
          {STATUS_STYLES[status].label}
          {segment.decidedBy && <span className="text-muted-foreground"> · {segment.decidedBy}</span>}
        </span>
      </div>
      {decided ? (
        <>
          {PRINTED.includes(status as TimeDecisionKind) && (
            <SignedDocument
              storeId={storeId}
              decisionId={decisionId}
              noun={status === 'EMPLOYER_ORDER' ? 'polecenie' : 'wniosek'}
              file={segment.decisionId ? segment.signedFile : null}
            />
          )}
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="outline" loading={remove.isPending} onClick={() => undo(decisionId)}>
              <Undo2Icon aria-hidden />
              Cofnij decyzję
            </Button>
          </div>
        </>
      ) : (
        <>
          {overtime && <OvertimeBalance storeId={storeId} row={row} segment={segment} />}
          <TextField label="Uwaga (opcjonalnie)" value={note} onChange={(event) => setNote(event.target.value)} />
          <div className="grid gap-2">
            {overtime ? (
              OVERTIME_CHOICES.map((choice) => (
                <button
                  key={choice.decision}
                  type="button"
                  disabled={decide.isPending}
                  onClick={() => choose(choice.decision)}
                  className="flex items-start gap-3 rounded-xl border bg-background p-3 text-left outline-none transition-colors hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-60 dark:bg-input/30"
                >
                  <span
                    aria-hidden
                    className={cn('mt-1 size-3 shrink-0 rounded-sm', STATUS_STYLES[choice.decision].bar)}
                  />
                  <span className="grid gap-0.5">
                    <span className="text-sm font-medium">{choice.label}</span>
                    <span className="text-xs text-muted-foreground">{choice.description}</span>
                  </span>
                </button>
              ))
            ) : (
              <Button type="button" loading={decide.isPending} onClick={() => choose('JUSTIFIED')}>
                Usprawiedliw: liczy się jak w grafiku
              </Button>
            )}
          </div>
          {!overtime && (
            <p className="text-xs text-muted-foreground">
              Bez usprawiedliwienia brakujące minuty nie wchodzą do czasu pracy.
            </p>
          )}
        </>
      )}
    </>
  )
}

/**
 * Koszt decyzji, zanim się ją podejmie: saldo okresu pracownika (jak w grafiku) i to, czym stanie się przyjęty
 * odcinek: część ponad limit dobowy to nadgodziny dobowe (czas wolny albo dodatek), reszta podnosi saldo okresu, które
 * wyrównuje się krótszym grafikiem do końca okresu.
 */
function OvertimeBalance({ storeId, row, segment }: { storeId: string; row: TimeCardRow; segment: TimeSegment }) {
  const date = segment.start.slice(0, 10)
  const balance = useTimeBalance(storeId, row.employeeId, date, true)
  if (!balance.data) {
    return <div className="h-16 animate-pulse rounded-lg bg-muted" aria-busy />
  }
  const data = balance.data
  const day = row.days.find((card) => card.date === date)
  const afterDay = (day?.worked ?? 0) + segment.minutes
  const daily = Math.min(segment.minutes, Math.max(0, afterDay - data.dailyLimitMinutes))
  const toBalance = segment.minutes - daily
  const saldo = data.normMinutes === null ? null : data.workedMinutes - data.normMinutes
  const sign = (minutes: number) => `${minutes > 0 ? '+' : ''}${hm(minutes)}`
  return (
    <div
      className={cn(
        'grid gap-1 rounded-lg p-2.5 text-xs',
        saldo !== null && saldo + toBalance > 0
          ? 'bg-amber-200 text-amber-950 dark:bg-amber-900 dark:text-amber-50'
          : 'bg-muted',
      )}
    >
      <p>
        <span className="font-medium">
          Okres {dayMonth(data.periodStart)}–{dayMonth(data.periodEnd)}:
        </span>{' '}
        {data.normMinutes === null
          ? `${hm(data.workedMinutes)} (bez wymiaru)`
          : `${hm(data.workedMinutes)} z ${hm(data.normMinutes)} (${sign(saldo ?? 0)})`}
        {data.toCompensateMinutes > 0 && <> · nadgodziny dobowe do odebrania {hm(data.toCompensateMinutes)}</>}
      </p>
      <p>
        Przyjęcie doda {hm(segment.minutes)}
        {daily > 0 && (
          <>
            , z tego {hm(daily)} ponad {hm(data.dailyLimitMinutes)} dnia to nadgodziny dobowe
          </>
        )}
        {toBalance > 0 && saldo !== null && (
          <>
            {daily > 0 ? ', a ' : ': '}
            {hm(toBalance)} podniesie saldo okresu do {sign(saldo + toBalance)}
            {saldo + toBalance > 0 && <>; wyrównasz to krótszym grafikiem do {dayMonth(data.periodEnd)}</>}
          </>
        )}
        .
      </p>
      <p className="text-muted-foreground">
        {data.actualUntil ? `Do ${dayMonth(data.actualUntil)} godziny z kart, dalej z grafiku.` : 'Godziny z grafiku.'}
      </p>
    </div>
  )
}

/**
 * Polecenie albo wniosek o nadgodziny: wydruk do podpisu i podpisany dokument (skan, zdjęcie) dołączony do decyzji,
 * żeby został w programie z podpisem pracodawcy.
 */
function SignedDocument({
  storeId,
  decisionId,
  noun,
  file,
}: {
  storeId: string
  decisionId: string
  noun: 'polecenie' | 'wniosek'
  file: TimeSegment['signedFile']
}) {
  const { attach, remove, restore } = useSignedFile(storeId, decisionId)
  // Polecenie jest rodzaju nijakiego, wniosek męskiego.
  const signed = `${noun === 'polecenie' ? 'podpisane' : 'podpisany'} ${noun}`
  const input = useRef<HTMLInputElement>(null)
  const [printing, setPrinting] = useState(false)

  const print = () => {
    setPrinting(true)
    printDecision(storeId, decisionId)
      .catch((cause) => toast.error(failed(cause, 'Nie udało się otworzyć wydruku')))
      .finally(() => setPrinting(false))
  }

  const upload = (picked: File | undefined) => {
    if (!picked) return
    attach.mutate(picked, {
      onSuccess: () => toast.success(`Dołączono ${signed}`),
      onError: (cause) => toast.error(failed(cause, 'Nie udało się dołączyć pliku')),
    })
  }

  const detach = (fileId: string) =>
    remove.mutate(undefined, {
      onSuccess: () =>
        toast.success('Usunięto dołączony plik', {
          action: {
            label: 'Przywróć',
            onClick: () =>
              restore.mutate(fileId, {
                onError: (cause) => toast.error(failed(cause, 'Nie udało się przywrócić pliku')),
              }),
          },
        }),
      onError: (cause) => toast.error(failed(cause, 'Nie udało się usunąć pliku')),
    })

  return (
    <div className="grid gap-3 rounded-xl border p-3">
      <p className="text-sm text-muted-foreground">
        Wydrukuj {noun}, daj do podpisu i dołącz skan albo zdjęcie podpisanego dokumentu.
      </p>
      {file ? (
        <div className="flex items-center gap-2 rounded-lg bg-emerald-100 p-2.5 text-sm text-emerald-950 dark:bg-emerald-950/60 dark:text-emerald-100">
          <FileCheckIcon aria-hidden className="size-4 shrink-0" />
          <a
            href={signedFileUrl(storeId, decisionId)}
            target="_blank"
            rel="noreferrer"
            className="min-w-0 flex-1 truncate font-medium underline-offset-2 hover:underline"
          >
            {file.fileName}
          </a>
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            aria-label="Usuń dołączony plik"
            title="Usuń dołączony plik"
            loading={remove.isPending}
            onClick={() => detach(file.id)}
          >
            <Trash2Icon aria-hidden />
          </Button>
        </div>
      ) : (
        <div className="rounded-lg bg-amber-100 p-2.5 text-sm text-amber-950 dark:bg-amber-950/60 dark:text-amber-100">
          Brak podpisanego dokumentu
        </div>
      )}
      <div className="flex flex-wrap gap-2">
        <Button type="button" variant="outline" loading={printing} onClick={print}>
          <PrinterIcon aria-hidden />
          Wydrukuj {noun}
        </Button>
        <Button type="button" variant="outline" loading={attach.isPending} onClick={() => input.current?.click()}>
          <PaperclipIcon aria-hidden />
          {file ? 'Wymień plik' : `Dołącz ${signed}`}
        </Button>
        <input
          ref={input}
          type="file"
          accept="application/pdf,image/jpeg,image/png,image/webp"
          className="hidden"
          onChange={(event) => {
            upload(event.target.files?.[0])
            event.target.value = ''
          }}
        />
      </div>
    </div>
  )
}

/** Symulator odbić (tylko właściciel): odbicie tak, jakby pracownik przyłożył kartę do czytnika. */
export function SimulatorDialog({
  open,
  storeId,
  cards,
  date,
  onClose,
}: {
  open: boolean
  storeId: string
  cards: TimeCards
  date: string
  onClose: () => void
}) {
  const { punch, remove } = useSimulator(storeId)
  // Osoba z umową o pracę i zleceniem ma dwa wiersze, a odbija kartę raz: lista bez powtórzeń.
  const people = cards.rows.filter(
    (item, index) => cards.rows.findIndex((other) => other.employeeId === item.employeeId) === index,
  )
  const [employeeId, setEmployeeId] = useState(people[0]?.employeeId ?? '')
  const [day, setDay] = useState(date)
  const [time, setTime] = useState('')
  const row = people.find((item) => item.employeeId === employeeId)
  const punches = row?.days.find((item) => item.date === day)?.punches ?? []

  const send = (at: string | null) =>
    punch.mutate(
      { employeeId, at },
      {
        onSuccess: () => toast.success(`Odbito: ${row?.name ?? ''}${at ? ` ${clockOf(at)}` : ' teraz'}`),
        onError: (cause) => toast.error(failed(cause, 'Nie udało się odbić karty')),
      },
    )

  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent className="gap-4 p-6 sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Symulator odbić</DialogTitle>
          <DialogDescription>
            Do testów, zanim podłączymy czytnik. Kolejne odbicia pracownika to na zmianę wejście i wyjście.
          </DialogDescription>
        </DialogHeader>
        <SelectField
          label="Pracownik"
          options={people.map((item) => ({ value: item.employeeId, label: item.name }))}
          value={employeeId}
          onChange={setEmployeeId}
        />
        <Button type="button" disabled={!employeeId} loading={punch.isPending && !time} onClick={() => send(null)}>
          Odbij teraz
        </Button>
        <div className="grid grid-cols-[1fr_1fr_auto] items-start gap-2">
          <DateField label="Dzień" value={day} onChange={setDay} max={todayIso()} />
          <TimeField label="Godzina" value={time} onChange={setTime} />
          <Button
            type="button"
            variant="outline"
            className="mt-1"
            disabled={!employeeId || !day || !time}
            onClick={() => send(`${day}T${time}:00`)}
          >
            Odbij
          </Button>
        </div>
        {punches.length > 0 && (
          <ul className="grid gap-1 text-sm">
            {punches.map((item, index) => (
              <li key={item.id} className="flex items-center justify-between gap-2 rounded-lg bg-muted px-3 py-1.5">
                <span className="tabular-nums">
                  {index % 2 === 0 ? 'Wejście' : 'Wyjście'} {clockOf(item.at)}
                  {item.source === 'READER' && <span className="text-muted-foreground"> · czytnik</span>}
                </span>
                {item.source === 'SIMULATED' && (
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    aria-label="Usuń odbicie"
                    onClick={() =>
                      remove.mutate(item.id, {
                        onError: (cause) => toast.error(failed(cause, 'Nie udało się usunąć odbicia')),
                      })
                    }
                  >
                    <Trash2Icon aria-hidden />
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </DialogContent>
    </Dialog>
  )
}

/** Dane pracodawcy do wydruków, czytniki NFC i karty pracowników sklepu. */
export function TimeSettingsDialog({
  open,
  storeId,
  onClose,
}: {
  open: boolean
  storeId: string
  onClose: () => void
}) {
  const { data } = useTimeSettings(storeId, open)
  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent className="gap-5 p-6 sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Ustawienia czasu pracy</DialogTitle>
          <DialogDescription>
            Dane pracodawcy trafiają na polecenia i wnioski o nadgodziny. Czytnik przedstawia się kluczem, a karta
            wskazuje pracownika. Tolerancję i przerwy ustawia się w ustawieniach działu w Grafiku.
          </DialogDescription>
        </DialogHeader>
        {data ? (
          <SettingsBody storeId={storeId} settings={data} />
        ) : (
          <div className="h-48 animate-pulse rounded-xl bg-muted" aria-busy />
        )}
      </DialogContent>
    </Dialog>
  )
}

function SettingsBody({ storeId, settings }: { storeId: string; settings: TimeSettings }) {
  const actions = useTimeSettingsActions(storeId)
  const [employer, setEmployer] = useState({
    name: settings.employer.name ?? '',
    address: settings.employer.address ?? '',
    nip: settings.employer.nip ?? '',
    regon: settings.employer.regon ?? '',
    representative: settings.employer.representative ?? '',
  })
  const [readerName, setReaderName] = useState('')
  const [token, setToken] = useState<string | null>(null)
  const [cardDrafts, setCardDrafts] = useState<Record<string, string>>({})
  const set = (field: keyof typeof employer, value: string) => setEmployer({ ...employer, [field]: value })

  return (
    <div className="grid gap-6">
      <Form
        onSubmit={(event) => {
          event.preventDefault()
          actions.saveEmployer.mutate(
            {
              name: employer.name,
              address: employer.address || null,
              nip: employer.nip || null,
              regon: employer.regon || null,
              representative: employer.representative || null,
            },
            {
              onSuccess: () => toast.success('Zapisano dane pracodawcy'),
              onError: (cause) => toast.error(failed(cause, 'Nie udało się zapisać danych')),
            },
          )
        }}
      >
        <p className="text-sm font-medium">Dane pracodawcy</p>
        {!settings.employer.saved && (
          <FormNote>
            {settings.employer.fromGus
              ? 'Podpowiedź z GUS po NIP-ie sklepu. Sprawdź dane i zapisz: trafią na polecenia i wnioski.'
              : 'Wpisz pełną nazwę firmy (nie nazwę sklepu z aplikacji) albo pobierz ją z GUS i zapisz, zanim wydrukujesz.'}
          </FormNote>
        )}
        <div className="flex items-start gap-2">
          <TextField
            label="NIP"
            inputMode="numeric"
            className="min-w-0 flex-1"
            value={employer.nip}
            onChange={(event) => set('nip', event.target.value)}
          />
          <GusButton
            nip={employer.nip}
            onFound={(company) =>
              setEmployer({
                ...employer,
                nip: company.nip,
                name: company.name ?? employer.name,
                address: company.address ?? '',
                regon: company.regon ?? '',
              })
            }
          />
        </div>
        <TextField label="Nazwa firmy" value={employer.name} onChange={(event) => set('name', event.target.value)} />
        <TextField label="Adres" value={employer.address} onChange={(event) => set('address', event.target.value)} />
        <TextField label="REGON" value={employer.regon} onChange={(event) => set('regon', event.target.value)} />
        <TextField
          label="Osoba reprezentująca (opcjonalnie)"
          value={employer.representative}
          onChange={(event) => set('representative', event.target.value)}
        />
        <FormActions>
          <Button type="submit" loading={actions.saveEmployer.isPending} disabled={!employer.name.trim()}>
            Zapisz dane
          </Button>
        </FormActions>
      </Form>

      <section className="grid gap-3">
        <p className="text-sm font-medium">Czytniki kart</p>
        {settings.readers.length === 0 && (
          <p className="text-sm text-muted-foreground">Brak czytników. Odbicia na razie tylko z symulatora.</p>
        )}
        <ul className="grid gap-1">
          {settings.readers.map((reader) => (
            <li key={reader.id} className="flex items-center justify-between gap-2 rounded-lg bg-muted px-3 py-1.5">
              <span className="text-sm">{reader.name}</span>
              <Button
                type="button"
                variant="ghost"
                size="icon-sm"
                aria-label={`Usuń czytnik ${reader.name}`}
                onClick={() =>
                  actions.removeReader.mutate(reader.id, {
                    onSuccess: () =>
                      toast.success(`Usunięto czytnik ${reader.name}`, {
                        action: { label: 'Przywróć', onClick: () => actions.restoreReader.mutate(reader.id) },
                      }),
                    onError: (cause) => toast.error(failed(cause, 'Nie udało się usunąć czytnika')),
                  })
                }
              >
                <Trash2Icon aria-hidden />
              </Button>
            </li>
          ))}
        </ul>
        {token && (
          <FormNote>
            Klucz nowego czytnika (widać go tylko teraz): <code className="break-all select-all">{token}</code>. Czytnik
            wysyła odbicia na <code>POST /api/time-clock/punches</code> z nagłówkiem{' '}
            <code>Authorization: Bearer &lt;klucz&gt;</code>.
          </FormNote>
        )}
        <div className="grid grid-cols-[1fr_auto] items-start gap-2">
          <TextField
            label="Nazwa czytnika"
            value={readerName}
            onChange={(event) => setReaderName(event.target.value)}
          />
          <Button
            type="button"
            variant="outline"
            className="mt-1"
            disabled={!readerName.trim()}
            loading={actions.addReader.isPending}
            onClick={() =>
              actions.addReader.mutate(readerName.trim(), {
                onSuccess: (result) => {
                  setReaderName('')
                  setToken(result.readers.find((reader) => reader.token)?.token ?? null)
                },
                onError: (cause) => toast.error(failed(cause, 'Nie udało się dodać czytnika')),
              })
            }
          >
            <PlusIcon aria-hidden />
            Dodaj
          </Button>
        </div>
      </section>

      <section className="grid gap-3">
        <div>
          <p className="text-sm font-medium">Karty NFC pracowników</p>
          <p className="text-xs text-muted-foreground">
            Nr RCP nadaje aplikacja: wpisz go raz w Symfonii w Aktualnym zatrudnieniu (numer karty RCP), a import
            ewidencji czasu pracy rozpozna pracownika.
          </p>
        </div>
        <ul className="grid gap-2">
          {settings.cards.map((card) => (
            <li key={card.employeeId} className="grid grid-cols-[1fr_12rem_auto] items-center gap-2">
              <span className="min-w-0 text-sm">
                <span className="block truncate font-medium">{card.name}</span>
                <span className="block truncate text-xs text-muted-foreground">
                  {card.departmentName}
                  {card.rcpNumber !== null && (
                    <>
                      {' '}
                      · nr RCP <span className="tabular-nums">{card.rcpNumber}</span>
                    </>
                  )}
                </span>
              </span>
              <TextField
                label="Numer karty"
                value={cardDrafts[card.employeeId] ?? card.cardUid ?? ''}
                onChange={(event) => setCardDrafts({ ...cardDrafts, [card.employeeId]: event.target.value })}
              />
              {(cardDrafts[card.employeeId] ?? card.cardUid ?? '') !== (card.cardUid ?? '') ? (
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => {
                    const value = (cardDrafts[card.employeeId] ?? '').trim()
                    const done = {
                      onSuccess: () => {
                        const next = { ...cardDrafts }
                        delete next[card.employeeId]
                        setCardDrafts(next)
                      },
                      onError: (cause: unknown) => toast.error(failed(cause, 'Nie udało się zapisać karty')),
                    }
                    if (value) actions.saveCard.mutate({ employeeId: card.employeeId, cardUid: value }, done)
                    else
                      actions.removeCard.mutate(card.employeeId, {
                        ...done,
                        onSuccess: () => {
                          done.onSuccess()
                          const previous = card.cardUid
                          if (previous)
                            toast.success(`Odpięto kartę: ${card.name}`, {
                              action: {
                                label: 'Przywróć',
                                onClick: () =>
                                  actions.saveCard.mutate({ employeeId: card.employeeId, cardUid: previous }),
                              },
                            })
                        },
                      })
                  }}
                >
                  Zapisz
                </Button>
              ) : (
                <span className="w-16" />
              )}
            </li>
          ))}
        </ul>
      </section>
    </div>
  )
}
