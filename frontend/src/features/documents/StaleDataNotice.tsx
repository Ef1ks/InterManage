import { TriangleAlertIcon } from 'lucide-react'
import { Link } from 'react-router'

import { Button } from '@/components/ui/button'
import { useCan } from '@/features/auth/api'
import { TOOL_ROUTES } from '@/features/tools/categories'
import { formatReportDate, isReportFresh } from '@/lib/report-freshness'

/** Baner, gdy najnowszy raport Wejścia/Wyjścia nie jest z dzisiaj: listy mogą nie mieć dzisiejszych dokumentów. */
export function StaleDataNotice({ generatedAt }: { generatedAt: string | null }) {
  const canImport = useCan('IMPORTS', 'EDIT')
  if (isReportFresh(generatedAt)) return null

  return (
    <div className="mb-6 flex flex-wrap items-center gap-x-4 gap-y-3 rounded-xl border border-amber-300 bg-amber-50 px-4 py-3 text-amber-900 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-200">
      <TriangleAlertIcon aria-hidden className="size-5 shrink-0" />
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium">
          {generatedAt ? 'Dane są nieaktualne i wymagają wgrania' : 'Nie wgrano jeszcze raportu dokumentów'}
        </p>
        <p className="text-xs tabular-nums opacity-80">
          {generatedAt && `Najnowszy raport jest z ${formatReportDate(generatedAt)}. `}
          Dzisiejsze dokumenty pojawią się po wgraniu dzisiejszego raportu Wejścia/Wyjścia w Imporcie danych.
        </p>
      </div>
      {canImport && (
        <Button variant="outline" render={<Link to={TOOL_ROUTES.IMPORTS.path} />}>
          Wgraj raport
        </Button>
      )}
    </div>
  )
}
