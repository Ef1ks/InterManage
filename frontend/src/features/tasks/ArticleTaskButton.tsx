import type { SalesChartTarget } from '@/features/sales/api'
import { AssignTaskButton } from '@/features/tasks/AssignTaskButton'
import { taskMoney, taskNumber, useTaskSource } from '@/features/tasks/task-helpers'
import type { SalesArticle } from '@/lib/types'

/**
 * „Zleć” w karcie artykułu (okno statystyk z każdego raportu i Magazyn): zadanie z tym jednym artykułem, ze stanem,
 * rotacją i ceną z chwili zlecenia, i z widokiem, z którego zlecono.
 */
export function ArticleTaskButton({
  target,
  article,
}: {
  target: Extract<SalesChartTarget, { kind: 'article' }>
  article: SalesArticle | null
}) {
  const page = useTaskSource('ARTICLE')
  const name = article?.name ?? target.name ?? target.itm8.replace(/^0+/, '')
  const source = { ...page, label: `${page.label} · ${name}` }
  const brand = article?.brand ?? target.brand ?? null
  const details = article
    ? [
        `stan ${taskNumber(article.stock, 3)}`,
        article.rotation !== null ? `rotacja ${taskNumber(article.rotation, 2)}` : null,
        article.salePrice !== null ? `cena ${taskMoney(article.salePrice)}` : null,
      ]
        .filter(Boolean)
        .join(' · ')
    : null
  return (
    <AssignTaskButton
      title={`Sprawdź: ${name}`}
      items={[
        {
          itm8: target.itm8,
          ean: article?.ean ?? target.ean ?? null,
          name: [name, brand].filter(Boolean).join(' · '),
          details,
        },
      ]}
      source={source}
      label="Zleć"
    />
  )
}
