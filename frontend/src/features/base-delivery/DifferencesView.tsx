import { formatPrice, formatQuantity } from '@/features/documents/format'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { cn } from '@/lib/utils'

import { DIFFERENCE_LABELS, type DifferenceKind, type findDifferences } from './differences'

const KIND_STYLES: Record<DifferenceKind, string> = {
  missing: 'bg-red-300 text-red-950 dark:bg-red-800 dark:text-red-50',
  extra: 'bg-violet-300 text-violet-950 dark:bg-violet-800 dark:text-violet-50',
  quantity: 'bg-amber-300 text-amber-950 dark:bg-amber-800 dark:text-amber-50',
  price: 'bg-sky-300 text-sky-950 dark:bg-sky-800 dark:text-sky-50',
}

/** Wynik „Znajdź różnice”: liczby według rodzaju i tabela różnic. */
export function DifferencesView({ result }: { result: ReturnType<typeof findDifferences> }) {
  const counts = new Map<DifferenceKind, number>()
  for (const difference of result.differences)
    for (const kind of difference.kinds) counts.set(kind, (counts.get(kind) ?? 0) + 1)

  return (
    <div className="flex min-h-0 flex-col gap-3">
      {result.differences.length === 0 ? (
        <p className="rounded-lg bg-emerald-200 px-3 py-2 text-sm font-medium text-emerald-950 dark:bg-emerald-900 dark:text-emerald-50">
          Brak różnic: {result.compared} artykułów zgadza się z programem.
        </p>
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-1.5 text-sm">
            <span className="mr-1 font-medium">Różnice: {result.differences.length}</span>
            {[...counts.entries()].map(([kind, count]) => (
              <span key={kind} className={cn('rounded-md px-2 py-0.5 font-medium', KIND_STYLES[kind])}>
                {DIFFERENCE_LABELS[kind]} {count}
              </span>
            ))}
          </div>
          <div className="max-h-[50vh] overflow-auto rounded-lg border">
            <table className="w-full text-sm tabular-nums">
              <thead className="sticky top-0 bg-muted text-left text-xs text-muted-foreground">
                <tr>
                  <th className="px-2 py-1.5 font-medium">Rodzaj</th>
                  <th className="px-2 py-1.5 font-medium">ITM8</th>
                  <th className="px-2 py-1.5 font-medium">EAN</th>
                  <th className="px-2 py-1.5 font-medium">Artykuł</th>
                  <th className="px-2 py-1.5 font-medium">Nośnik</th>
                  <th className="px-2 py-1.5 text-right font-medium">W tabeli</th>
                  <th className="px-2 py-1.5 text-right font-medium">W programie</th>
                  <th className="px-2 py-1.5 text-right font-medium">Różnica</th>
                  <th className="px-2 py-1.5 text-right font-medium">Cena w tabeli</th>
                  <th className="px-2 py-1.5 text-right font-medium">Cena w programie</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {result.differences.map((difference) => {
                  const delta =
                    difference.programQuantity === null ? 0 : difference.programQuantity - difference.sheetQuantity
                  const priceDiffers = difference.kinds.includes('price')
                  return (
                    <tr key={difference.key}>
                      <td className="px-2 py-1.5">
                        <span className="flex flex-wrap gap-1">
                          {difference.kinds.map((kind) => (
                            <span
                              key={kind}
                              className={cn('rounded px-1.5 text-xs font-medium whitespace-nowrap', KIND_STYLES[kind])}
                            >
                              {DIFFERENCE_LABELS[kind]}
                            </span>
                          ))}
                        </span>
                      </td>
                      <td className="px-2 py-1.5">{difference.itm8?.replace(/^0+/, '')}</td>
                      <td className="px-2 py-1.5">{difference.ean}</td>
                      <td className="max-w-64 px-2 py-1.5">
                        <span className="flex items-center gap-1">
                          <span className="min-w-0 truncate" title={difference.name}>
                            {difference.name}
                          </span>
                          {difference.articleItm8 && (
                            <ArticleStatsButton
                              article={{
                                itm8: difference.articleItm8,
                                name: difference.name,
                                ean: difference.ean,
                              }}
                            />
                          )}
                        </span>
                      </td>
                      <td className="px-2 py-1.5">{difference.carriers.join(', ')}</td>
                      <td className="px-2 py-1.5 text-right">{formatQuantity(difference.sheetQuantity)}</td>
                      <td className="px-2 py-1.5 text-right">
                        {difference.programQuantity === null ? '' : formatQuantity(difference.programQuantity)}
                      </td>
                      <td className="px-2 py-1.5 text-right font-medium">
                        {delta === 0 ? '' : `${delta > 0 ? '+' : ''}${formatQuantity(delta)}`}
                      </td>
                      <td className={cn('px-2 py-1.5 text-right', priceDiffers && 'font-medium')}>
                        {formatPrice(difference.sheetPrice)}
                      </td>
                      <td className={cn('px-2 py-1.5 text-right', priceDiffers && 'font-medium')}>
                        {formatPrice(difference.programPrice)}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  )
}
