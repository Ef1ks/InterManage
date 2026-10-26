import { ChevronRightIcon } from 'lucide-react'
import { Link } from 'react-router'

import { FullPageMessage } from '@/components/FullPageMessage'
import { useDocumentsOverview } from '@/features/documents/api'
import { DOCUMENT_ICONS, FALLBACK_ICON } from '@/features/documents/document-icons'
import { countFormat, documentPaths, documentsWord, formatDocumentDate } from '@/features/documents/format'
import { StaleDataNotice } from '@/features/documents/StaleDataNotice'
import type { DocumentTypeSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

/** Wejście do modułu: kafelek na każdy typ dokumentu, dopiero w nim lista dokumentów tego typu. */
export function DocumentTypesGrid({ storeId }: { storeId: string }) {
  const { data, isPending, isError, refetch } = useDocumentsOverview(storeId)

  if (isError) {
    return (
      <FullPageMessage
        title="Nie udało się wczytać dokumentów"
        action={{ label: 'Spróbuj ponownie', onClick: () => refetch() }}
        inline
      />
    )
  }

  return (
    <>
      {data && <StaleDataNotice generatedAt={data.lastReportGeneratedAt} />}
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        {isPending
          ? Array.from({ length: 8 }, (_, index) => (
              <div key={index} aria-hidden className="h-36 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
            ))
          : data.types.map((type) => <TypeTile key={type.code} type={type} />)}
      </div>
    </>
  )
}

function TypeTile({ type }: { type: DocumentTypeSummary }) {
  const Icon = DOCUMENT_ICONS[type.code] ?? FALLBACK_ICON
  const empty = type.documentCount === 0
  return (
    <Link
      to={documentPaths.list(type.code)}
      className={cn(
        'group flex flex-col gap-3 rounded-xl border bg-card p-4 transition-[border-color,box-shadow] duration-150 outline-none',
        'hover:border-foreground/20 hover:shadow-[0_2px_8px_-2px_rgb(0_0_0/0.08)] focus-visible:ring-3 focus-visible:ring-ring/50',
      )}
    >
      <div className="flex items-start justify-between gap-3">
        <span
          className={cn(
            'flex size-10 items-center justify-center rounded-lg bg-muted text-foreground',
            empty && 'text-muted-foreground',
          )}
        >
          <Icon aria-hidden className="size-5" />
        </span>
        <ChevronRightIcon
          aria-hidden
          className="size-4 text-muted-foreground transition-transform duration-150 group-hover:translate-x-0.5"
        />
      </div>
      <div className="min-w-0">
        <h2 className="font-semibold">{type.label}</h2>
        <p className="mt-0.5 text-sm text-muted-foreground">{type.description}</p>
      </div>
      <p className={cn('mt-auto text-sm tabular-nums', empty ? 'text-muted-foreground' : 'font-medium')}>
        {empty
          ? 'Brak dokumentów'
          : `${countFormat.format(type.documentCount)} ${documentsWord(type.documentCount)}`}
        {type.lastDocumentDate && (
          <span className="font-normal text-muted-foreground"> · ostatni {formatDocumentDate(type.lastDocumentDate).split(',')[0]}</span>
        )}
      </p>
    </Link>
  )
}
