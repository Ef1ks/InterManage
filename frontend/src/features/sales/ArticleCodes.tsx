import { BarcodeIcon, XIcon } from 'lucide-react'

import { CopyButton } from '@/components/CopyButton'
import { Button } from '@/components/ui/button'
import { formatMoney, formatQuantity } from '@/features/documents/format'
import { useArticleCodes } from '@/features/sales/api'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { formatShortDate } from '@/lib/dates'
import type { ArticleCode } from '@/lib/types'
import { cn } from '@/lib/utils'

/** ITM8 w Mercalys wpisuje się bez zer wiodących; w bazie ma 13 cyfr. */
const shortItm8 = (itm8: string) => itm8.replace(/^0+/, '') || '0'

/**
 * Kody artykułu w nagłówku statystyk: ITM8 i EAN w osobnych polach z kopiowaniem, a obok przycisk karty
 * „Przypisane kody” (EAN-y z kartoteki, multikodów i raportów).
 */
export function ArticleCodeFields({
  itm8,
  ean,
  codesOpen,
  onToggleCodes,
}: {
  itm8: string
  ean: string | null | undefined
  codesOpen: boolean
  onToggleCodes: () => void
}) {
  return (
    <div className="flex flex-wrap items-stretch gap-2">
      <CodeField label="ITM8" value={shortItm8(itm8)} title={`ITM8 w bazie: ${itm8}`} />
      <CodeField label="EAN" value={ean || null} />
      <Button
        variant={codesOpen ? 'secondary' : 'outline'}
        size="sm"
        className="h-auto self-stretch"
        aria-expanded={codesOpen}
        onClick={onToggleCodes}
      >
        <BarcodeIcon />
        Przypisane kody
      </Button>
    </div>
  )
}

function CodeField({ label, value, title }: { label: string; value: string | null; title?: string }) {
  return (
    <div className="flex items-center gap-2 rounded-lg border bg-card py-1 pr-1 pl-3" title={title}>
      <span className="text-xs text-muted-foreground">{label}</span>
      <span className="font-mono text-sm font-semibold tabular-nums select-all">{value ?? '—'}</span>
      {value ? (
        <CopyButton value={value} label={`Kopiuj ${label} ${value}`} />
      ) : (
        <span aria-hidden className="size-6" />
      )}
    </div>
  )
}

/**
 * Karta z kodami przypisanymi do artykułu: każdy EAN raz (połączony bez zer wiodących), skąd jest (kartoteka,
 * multikody, raporty kartoteki), dane z multikodów i inne artykuły (ITM8) z tym samym kodem.
 */
export function ArticleCodesCard({ storeId, itm8, onClose }: { storeId: string; itm8: string; onClose: () => void }) {
  const codes = useArticleCodes(storeId, itm8, true)
  const eans = codes.data?.eans ?? []
  const current = eans.filter(isCurrent)
  const past = eans.filter((code) => !isCurrent(code))
  const shared = eans.filter((code) => code.otherArticles.length > 0).length

  return (
    <section className="grid gap-3 rounded-lg border bg-card p-4" aria-label="Przypisane kody">
      <div className="flex items-start justify-between gap-3">
        <div className="grid gap-0.5">
          <h3 className="text-sm font-semibold">Przypisane kody</h3>
          <p className="text-xs text-muted-foreground">
            EAN-y z kartoteki, multikodów i raportów kartoteki dla ITM8 {shortItm8(itm8)}.
          </p>
        </div>
        <Button variant="ghost" size="icon-sm" aria-label="Zamknij kody" onClick={onClose}>
          <XIcon />
        </Button>
      </div>

      {codes.isPending ? (
        <div aria-hidden className="h-24 animate-pulse rounded-md bg-muted motion-reduce:animate-none" />
      ) : codes.isError ? (
        <p className="text-sm text-muted-foreground">{codes.error.message || 'Nie udało się pobrać kodów artykułu.'}</p>
      ) : (
        <>
          <div className="flex flex-wrap gap-2 text-xs">
            <Count label="obowiązujące EAN-y" value={current.length} />
            {past.length > 0 && <Count label="dawne" value={past.length} />}
            {shared > 0 && <Count label="wspólne z innym ITM8" value={shared} warn />}
          </div>
          <CodeList codes={current} />
          {past.length > 0 && (
            <details className="group">
              <summary className="cursor-pointer text-sm text-muted-foreground select-none hover:text-foreground">
                Dawne kody ({past.length})
              </summary>
              <div className="mt-2">
                <CodeList codes={past} />
              </div>
            </details>
          )}
        </>
      )}
    </section>
  )
}

/** Obowiązuje: główny EAN, kod z najnowszych multikodów albo para z raportu kartoteki bez multikodu. */
function isCurrent(code: ArticleCode) {
  if (code.main) return true
  if (code.multicode) return code.multicode.current
  return code.catalogLastSeen != null
}

function Count({ label, value, warn }: { label: string; value: number; warn?: boolean }) {
  return (
    <span
      className={cn(
        'rounded-full px-2.5 py-0.5 font-medium',
        warn ? 'bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100' : 'bg-muted',
      )}
    >
      {value} {label}
    </span>
  )
}

function CodeList({ codes }: { codes: ArticleCode[] }) {
  if (codes.length === 0) return <p className="text-sm text-muted-foreground">Brak kodów.</p>
  return (
    <ul className="divide-y rounded-md border">
      {codes.map((code) => (
        <CodeRow key={code.ean} code={code} />
      ))}
    </ul>
  )
}

function CodeRow({ code }: { code: ArticleCode }) {
  const multicode = code.multicode
  const details = [
    multicode?.saleUnit && `jedn. ${multicode.saleUnit}`,
    multicode?.salePack != null && `opak. ${formatQuantity(multicode.salePack)}`,
    multicode?.priceFactor != null && multicode.priceFactor !== 1 && `współcz. ceny ${multicode.priceFactor}`,
    multicode?.salePrice != null && `${formatMoney(multicode.salePrice)} zł`,
    multicode?.removalDate && `usunięcie ${formatShortDate(multicode.removalDate)}`,
  ].filter(Boolean)
  const catalogRange =
    code.catalogFirstSeen &&
    code.catalogLastSeen &&
    `w raportach kartoteki ${formatShortDate(code.catalogFirstSeen.slice(0, 10))} – ${formatShortDate(code.catalogLastSeen.slice(0, 10))}`

  return (
    <li className="grid gap-1.5 px-3 py-2">
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <span className="font-mono text-sm font-semibold tabular-nums">{code.ean}</span>
        <CopyButton value={code.ean} label={`Kopiuj EAN ${code.ean}`} />
        {code.main && <Badge className="bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-100">główny</Badge>}
        {multicode?.prio && (
          <Badge className="bg-violet-200 text-violet-950 dark:bg-violet-900/70 dark:text-violet-100">
            prio w multikodach
          </Badge>
        )}
        {multicode && !multicode.prio && <Badge className="bg-muted">multikod</Badge>}
        {multicode && !multicode.current && <Badge className="bg-muted">poza najnowszymi multikodami</Badge>}
        {code.catalogLastSeen && <Badge className="bg-muted">raport kartoteki</Badge>}
      </div>
      {(details.length > 0 || catalogRange) && (
        <p className="text-xs text-muted-foreground">{[...details, catalogRange].filter(Boolean).join(' · ')}</p>
      )}
      {code.otherArticles.length > 0 && (
        <div className="grid gap-1 rounded-md bg-amber-100 px-2.5 py-1.5 text-xs text-amber-950 dark:bg-amber-950/60 dark:text-amber-100">
          <span className="font-medium">Ten kod ma też:</span>
          {code.otherArticles.map((other) => (
            <span key={other.itm8} className="flex flex-wrap items-center gap-x-1.5">
              <span className="font-mono font-semibold tabular-nums">ITM8 {shortItm8(other.itm8)}</span>
              <CopyButton value={shortItm8(other.itm8)} label={`Kopiuj ITM8 ${shortItm8(other.itm8)}`} />
              <span className="min-w-0 truncate">{other.name ?? 'spoza kartoteki'}</span>
              <span className="opacity-75">· {SOURCE_LABELS[other.source]}</span>
              <ArticleStatsButton article={{ itm8: other.itm8, name: other.name }} />
            </span>
          ))}
        </div>
      )}
    </li>
  )
}

const SOURCE_LABELS: Record<ArticleCode['otherArticles'][number]['source'], string> = {
  MAIN: 'jego główny EAN',
  MULTICODE: 'w multikodach',
  CATALOG: 'w raporcie kartoteki',
}

function Badge({ className, children }: { className?: string; children: React.ReactNode }) {
  return <span className={cn('rounded-full px-2 py-0.5 text-xs font-medium', className)}>{children}</span>
}
