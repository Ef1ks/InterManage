import { useState } from 'react'
import { toast } from 'sonner'

import {
  Form,
  FormActions,
  FormNote,
  MultiComboboxField,
  SegmentedField,
  SelectField,
  TextField,
} from '@/components/form'
import { Button } from '@/components/ui/button'
import { useTimeRules, useTimeRulesActions, type TimeRulesInput } from '@/features/staff-time/api'
import { ApiError } from '@/lib/api'
import type { TimeRequestApprover, TimeRuleOffer, TimeRules } from '@/lib/types'
import { LoadingPanel } from '@/components/LoadingPanel'

const failed = (cause: unknown, fallback: string) => (cause instanceof ApiError ? cause.message : fallback)

const ROUNDING_OPTIONS = [
  { value: '0', label: 'Bez zaokrąglania' },
  { value: '5', label: 'Do 5 min' },
  { value: '10', label: 'Do 10 min' },
  { value: '15', label: 'Do 15 min' },
  { value: '30', label: 'Do 30 min' },
]

const APPROVERS: { value: TimeRequestApprover; label: string }[] = [
  { value: 'MANAGER', label: 'Kierownik działu' },
  { value: 'HR', label: 'Kadry' },
]

const approverLabel = (approver: TimeRequestApprover) => (approver === 'HR' ? 'kadry' : 'kierownik działu')

/** Opis zasad w jednej linii, np. przy propozycji od innego sklepu. */
function describe(rules: TimeRulesInput) {
  const rounding = rules.roundingMinutes > 0 ? `zaokrąglanie do ${rules.roundingMinutes} min` : 'bez zaokrąglania'
  return `${rounding}, blokada ${rules.punchCooldownSeconds} s, wnioski zatwierdza ${approverLabel(rules.finalApprover)}`
}

const valuesOf = (rules: TimeRules): TimeRulesInput => ({
  roundingMinutes: rules.roundingMinutes,
  punchCooldownSeconds: rules.punchCooldownSeconds,
  finalApprover: rules.finalApprover,
})

/** Zasady odbić sklepu (zaokrąglanie, blokada ponownego odbicia, kto zatwierdza wnioski) i ich wymiana ze sklepami. */
export function TimeRulesSection({ storeId }: { storeId: string }) {
  const { data } = useTimeRules(storeId, true)
  if (!data) return <LoadingPanel className="h-40 rounded-xl" />
  // Klucz: po zapisie albo przyjęciu propozycji formularz startuje od nowych wartości.
  return (
    <RulesForm
      key={`${data.roundingMinutes}-${data.punchCooldownSeconds}-${data.finalApprover}`}
      storeId={storeId}
      rules={data}
    />
  )
}

function RulesForm({ storeId, rules }: { storeId: string; rules: TimeRules }) {
  const actions = useTimeRulesActions(storeId)
  const [rounding, setRounding] = useState(String(rules.roundingMinutes))
  const [cooldown, setCooldown] = useState(String(rules.punchCooldownSeconds))
  const [approver, setApprover] = useState<TimeRequestApprover>(rules.finalApprover)
  const [targets, setTargets] = useState<string[]>([])

  const cooldownSeconds = Number(cooldown)
  const cooldownError =
    !Number.isInteger(cooldownSeconds) || cooldownSeconds < 10 || cooldownSeconds > 600
      ? 'Od 10 do 600 sekund'
      : undefined
  const draft: TimeRulesInput = {
    roundingMinutes: Number(rounding),
    punchCooldownSeconds: cooldownSeconds,
    finalApprover: approver,
  }
  const previous = valuesOf(rules)
  const dirty =
    draft.roundingMinutes !== previous.roundingMinutes ||
    draft.punchCooldownSeconds !== previous.punchCooldownSeconds ||
    draft.finalApprover !== previous.finalApprover

  const restore = (values: TimeRulesInput) =>
    actions.save.mutate(values, {
      onError: (cause) => toast.error(failed(cause, 'Nie udało się przywrócić zasad')),
    })

  const accept = (offer: TimeRuleOffer) =>
    actions.accept.mutate(offer.id, {
      onSuccess: () =>
        toast.success(`Przyjęto zasady sklepu ${offer.storeNumber}`, {
          action: { label: 'Cofnij', onClick: () => restore(previous) },
        }),
      onError: (cause) => toast.error(failed(cause, 'Nie udało się przyjąć zasad')),
    })

  return (
    <div className="grid gap-4">
      {rules.incoming.map((offer) => (
        <div
          key={offer.id}
          className="grid gap-2 rounded-xl bg-amber-200 px-4 py-3 text-amber-950 dark:bg-amber-900/60 dark:text-amber-100"
        >
          <p className="text-sm">
            <span className="font-medium">
              Sklep {offer.storeNumber} {offer.storeName} proponuje swoje zasady:
            </span>{' '}
            {describe(offer)}.
          </p>
          <div className="flex flex-wrap gap-2">
            <Button size="sm" loading={actions.accept.isPending} onClick={() => accept(offer)}>
              Przyjmij
            </Button>
            <Button
              size="sm"
              variant="outline"
              loading={actions.reject.isPending}
              onClick={() =>
                actions.reject.mutate(offer.id, {
                  onSuccess: () => toast.success('Odrzucono propozycję'),
                  onError: (cause) => toast.error(failed(cause, 'Nie udało się odrzucić propozycji')),
                })
              }
            >
              Odrzuć
            </Button>
          </div>
        </div>
      ))}

      <Form
        onSubmit={(event) => {
          event.preventDefault()
          if (cooldownError) return
          actions.save.mutate(draft, {
            onSuccess: () =>
              toast.success('Zapisano zasady odbić', { action: { label: 'Cofnij', onClick: () => restore(previous) } }),
            onError: (cause) => toast.error(failed(cause, 'Nie udało się zapisać zasad')),
          })
        }}
      >
        <p className="text-sm font-medium">Odbicia i wnioski</p>
        <SelectField
          label="Zaokrąglanie odbić"
          options={ROUNDING_OPTIONS}
          value={rounding}
          onChange={setRounding}
          hint="Wejście w górę, wyjście w dół (7:52 → 8:00, 16:07 → 16:00). Prawdziwe godziny odbić zostają zapisane i widać je w karcie."
        />
        <TextField
          label="Blokada ponownego odbicia (sekundy)"
          inputMode="numeric"
          value={cooldown}
          onChange={(event) => setCooldown(event.target.value.replace(/\D/g, ''))}
          error={cooldownError}
          hint="Ta sama karta przyłożona drugi raz w tym czasie nie zapisuje nowego odbicia. Co najmniej 10 s."
        />
        <div className="flex flex-wrap items-center justify-between gap-3">
          <span className="text-sm">Wnioski pracowników ostatecznie zatwierdza</span>
          <SegmentedField label="Kto zatwierdza wnioski" options={APPROVERS} value={approver} onChange={setApprover} />
        </div>
        {rules.saved && rules.savedAt && (
          <FormNote>
            Zapisane {new Date(rules.savedAt).toLocaleString('pl-PL', { dateStyle: 'short', timeStyle: 'short' })}
            {rules.savedBy ? ` · ${rules.savedBy}` : ''}
          </FormNote>
        )}
        <FormActions>
          <Button type="submit" loading={actions.save.isPending} disabled={!dirty || !!cooldownError}>
            Zapisz zasady
          </Button>
        </FormActions>
      </Form>

      {rules.partners.length > 0 && (
        <section className="grid gap-3">
          <p className="text-sm font-medium">Wyślij te zasady do sklepów zaprzyjaźnionych</p>
          <MultiComboboxField
            label="Sklepy"
            options={rules.partners.map((partner) => ({
              value: partner.storeId,
              label: `${partner.number} ${partner.name}`,
            }))}
            value={targets}
            onChange={setTargets}
          />
          {dirty && <FormNote>Najpierw zapisz zmiany: wysyłane są zapisane zasady.</FormNote>}
          <div className="flex justify-end">
            <Button
              variant="outline"
              disabled={targets.length === 0 || dirty}
              loading={actions.offer.isPending}
              onClick={() =>
                actions.offer.mutate(targets, {
                  onSuccess: () => {
                    toast.success(targets.length === 1 ? 'Wysłano propozycję' : `Wysłano ${targets.length} propozycje`)
                    setTargets([])
                  },
                  onError: (cause) => toast.error(failed(cause, 'Nie udało się wysłać zasad')),
                })
              }
            >
              Wyślij propozycję
            </Button>
          </div>
          {rules.outgoing.length > 0 && (
            <ul className="grid gap-1">
              {rules.outgoing.map((offer) => (
                <li key={offer.id} className="flex items-center justify-between gap-2 rounded-lg bg-muted px-3 py-1.5">
                  <span className="text-sm">
                    Czeka na sklep {offer.storeNumber}: {describe(offer)}
                  </span>
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() =>
                      actions.cancel.mutate(offer.id, {
                        onSuccess: () => toast.success('Cofnięto propozycję'),
                        onError: (cause) => toast.error(failed(cause, 'Nie udało się cofnąć propozycji')),
                      })
                    }
                  >
                    Cofnij
                  </Button>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}
    </div>
  )
}
