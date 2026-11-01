import { useState } from 'react'
import { PlusIcon, Trash2Icon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { roundPrice } from '@/features/data-entry/rows'
import { usePriceRoundingRules, useSavePriceRounding } from '@/features/store-settings/api'
import { ApiError } from '@/lib/api'
import type { PriceRoundingRule } from '@/lib/types'

type DraftRule = { key: number; from: string; to: string; ending: string }

const moneyText = new Intl.NumberFormat('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2, useGrouping: false })
const toText = (value: number | null) => (value == null ? '' : moneyText.format(value))

/** Kwota z pola: przecinek albo kropka, „zł” pomijane, puste = null; {@code undefined} = błędna wartość. */
function parseMoney(text: string): number | null | undefined {
  const trimmed = text.replace(/\s|zł/gi, '').replace(',', '.')
  if (trimmed === '') return null
  return /^\d{1,8}(\.\d{1,2})?$/.test(trimmed) ? Number(trimmed) : undefined
}

let nextKey = 0
const draftOf = (rules: PriceRoundingRule[]): DraftRule[] =>
  rules.map((rule) => ({ key: nextKey++, from: toText(rule.priceFrom), to: toText(rule.priceTo), ending: toText(rule.ending) }))

type Parsed = { priceFrom: number; priceTo: number | null; ending: number }

/** Reguła z pól albo komunikat, co poprawić. */
function check(rule: DraftRule): Parsed | string {
  const priceFrom = parseMoney(rule.from)
  const priceTo = parseMoney(rule.to)
  const ending = parseMoney(rule.ending)
  if (priceFrom == null) return 'Podaj cenę „od”'
  if (priceTo === undefined) return 'Błędna cena „do”'
  if (priceTo !== null && priceTo <= priceFrom) return '„Do” musi być większe niż „od”'
  if (ending == null || ending <= 0 || ending >= 1) return 'Końcówka od 0,01 do 0,99'
  return { priceFrom, priceTo, ending }
}

/**
 * Zaokrąglanie cen sugerowanych we wprowadzaniu danych: przedziały cen z końcówką, do której cena idzie w górę
 * (0,49 = końcówki ,49 i ,99; 0,99 = zawsze ,99). Zapis zastępuje całą listę.
 */
export function PriceRoundingRules({ storeId }: { storeId: string }) {
  const { data, isPending, isError, refetch } = usePriceRoundingRules(storeId)
  const save = useSavePriceRounding(storeId)
  const [draft, setDraft] = useState<DraftRule[] | null>(null)

  if (isError) {
    return (
      <Card className="items-center px-6 py-8 text-center">
        <p className="font-medium">Nie udało się wczytać zaokrągleń</p>
        <Button variant="outline" onClick={() => refetch()}>
          Spróbuj ponownie
        </Button>
      </Card>
    )
  }

  const rules = draft ?? draftOf(data ?? [])
  const checked = rules.map(check)
  const valid = checked.filter((rule): rule is Parsed => typeof rule !== 'string').sort((a, b) => a.priceFrom - b.priceFrom)
  const overlap = valid.some(
    (rule, i) => i > 0 && (valid[i - 1].priceTo === null || valid[i - 1].priceTo! > rule.priceFrom),
  )
  const invalid = valid.length !== rules.length || overlap

  const update = (key: number, field: 'from' | 'to' | 'ending', text: string) =>
    setDraft(rules.map((rule) => (rule.key === key ? { ...rule, [field]: text } : rule)))
  const add = () => {
    // Nowy przedział zaczyna się tam, gdzie kończy ostatni.
    const last = valid.at(-1)
    setDraft([...rules, { key: nextKey++, from: last?.priceTo != null ? toText(last.priceTo) : '', to: '', ending: '0,99' }])
  }
  const submit = () =>
    save.mutate(valid, {
      onSuccess: () => {
        setDraft(null)
        toast.success('Zapisano zaokrąglanie cen')
      },
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać zaokrągleń'),
    })

  return (
    <Card className="gap-3 p-4">
      <div>
        <h3 className="font-medium">Zaokrąglanie cen sugerowanych</h3>
        <p className="text-sm text-muted-foreground">
          Cena sugerowana z przedziału idzie w górę do końcówki: 0,49 daje ceny na ,49 i ,99 (3,12 → 3,49, 3,60 → 3,99),
          a 0,99 zawsze na ,99 (6,20 → 6,99). „Od” wchodzi do przedziału, „do” już nie; puste „do” = bez górnej granicy.
          Ceny spoza przedziałów i CENA_CAD zostają bez zmian.
        </p>
      </div>

      {isPending ? (
        <div aria-hidden className="h-24 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
      ) : (
        <div className="grid gap-2">
          {rules.length > 0 && (
            <div className="grid grid-cols-[1fr_1fr_1fr_2fr_auto] gap-2 text-sm font-medium text-muted-foreground">
              <span>Od zł</span>
              <span>Do zł</span>
              <span>Końcówka</span>
              <span>Przykład</span>
              <span className="w-8" />
            </div>
          )}
          {rules.map((rule, i) => {
            const result = checked[i]
            // Przykład: cena 12 gr powyżej początku przedziału, o ile jeszcze w nim jest.
            const sample =
              typeof result === 'string' || (result.priceTo !== null && result.priceFrom + 0.12 >= result.priceTo)
                ? null
                : result.priceFrom + 0.12
            return (
              <div key={rule.key} className="grid grid-cols-[1fr_1fr_1fr_2fr_auto] items-center gap-2">
                {/* Siatka do wpisywania kwot: pola bez pływającej etykiety, opisane nagłówkiem kolumny. */}
                <Input inputMode="decimal" aria-label="Od zł" value={rule.from} onChange={(event) => update(rule.key, 'from', event.target.value)} className="text-right" />
                <Input inputMode="decimal" aria-label="Do zł" placeholder="bez limitu" value={rule.to} onChange={(event) => update(rule.key, 'to', event.target.value)} className="text-right" />
                <Input inputMode="decimal" aria-label="Końcówka" value={rule.ending} onChange={(event) => update(rule.key, 'ending', event.target.value)} className="text-right" />
                <span className="text-sm tabular-nums">
                  {typeof result === 'string' ? (
                    <span className="text-destructive">{result}</span>
                  ) : sample !== null ? (
                    `${moneyText.format(sample)} → ${moneyText.format(roundPrice(sample, [{ ...result, createdAt: null, createdBy: null }]))} zł`
                  ) : null}
                </span>
                <Button
                  variant="ghost"
                  size="icon-sm"
                  aria-label="Usuń przedział"
                  onClick={() => setDraft(rules.filter((item) => item.key !== rule.key))}
                >
                  <Trash2Icon />
                </Button>
              </div>
            )
          })}
          {rules.length === 0 && <p className="text-sm text-muted-foreground">Ceny sugerowane nie są zaokrąglane.</p>}
          {overlap && <p className="text-sm text-destructive">Przedziały nachodzą na siebie.</p>}
        </div>
      )}

      <div className="flex flex-wrap items-center justify-between gap-3">
        <Button variant="outline" onClick={add} disabled={isPending}>
          <PlusIcon />
          Dodaj przedział
        </Button>
        <div className="flex gap-2">
          {draft && (
            <Button variant="ghost" onClick={() => setDraft(null)}>
              Anuluj
            </Button>
          )}
          <Button loading={save.isPending} disabled={!draft || invalid} onClick={submit}>
            Zapisz zaokrąglanie
          </Button>
        </div>
      </div>
    </Card>
  )
}
