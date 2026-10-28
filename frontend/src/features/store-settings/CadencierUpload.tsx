import { useState } from 'react'
import { CircleCheckIcon, FileWarningIcon } from 'lucide-react'

import { FileDropZone } from '@/components/FileDropZone'
import { ImportError } from '@/components/ImportError'
import { ReportFreshness } from '@/components/ReportFreshness'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Card } from '@/components/ui/card'
import { countFormat, formatDocumentDate } from '@/features/documents/format'
import { useCadencier, useImportCadencier } from '@/features/sales/api'
import type { CadencierImport } from '@/lib/types'

const ACCEPTED_EXTENSIONS = ['.xlsx', '.xlsm']
const dateTimeFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'short' })

/**
 * Lista „OBECNE W CADENCIER” (ITM, EAN) z systemu sklepu: artykuły z formularza zamówień do bazy. Obowiązuje
 * najnowsza wgrana lista; raport zakupu bazowego poza bazą sprawdza tylko te artykuły.
 */
export function CadencierUpload({ storeId }: { storeId: string }) {
  const overview = useCadencier(storeId)
  const upload = useImportCadencier(storeId)
  const [rejectedFile, setRejectedFile] = useState<string | null>(null)
  const latest = overview.data?.imports[0]

  return (
    <Card className="gap-3 p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h3 className="font-medium">Artykuły w cadencier</h3>
          <p className="text-sm text-muted-foreground tabular-nums">
            {!overview.data
              ? ' '
              : latest
                ? `${countFormat.format(overview.data.itemCount)} artykułów · wgrana ${dateTimeFormat.format(new Date(latest.importedAt))}${latest.importedBy ? ` przez ${latest.importedBy}` : ''}`
                : 'Lista nie jest jeszcze wgrana'}
          </p>
        </div>
        {latest && <ReportFreshness generatedAt={latest.reportGeneratedAt ?? latest.importedAt} />}
      </div>

      <FileDropZone
        className="min-h-28 py-5"
        accept={ACCEPTED_EXTENSIONS}
        pendingFile={upload.isPending ? upload.variables : undefined}
        onFile={(file) => {
          setRejectedFile(null)
          upload.mutate(file)
        }}
        onReject={(fileName) => {
          upload.reset()
          setRejectedFile(fileName)
        }}
        title="Przeciągnij listę OBECNE W CADENCIER albo kliknij"
        hint="Plik .xlsx z kolumnami ITM i EAN. Nowa lista zastępuje poprzednią."
      />

      <div aria-live="polite">
        {rejectedFile && (
          <Alert variant="destructive">
            <FileWarningIcon />
            <AlertTitle>Plik {rejectedFile} ma złe rozszerzenie</AlertTitle>
            <AlertDescription>Wgraj listę zapisaną jako .xlsx lub .xlsm.</AlertDescription>
          </Alert>
        )}
        {upload.isError && <ImportError error={upload.error} />}
        {upload.isSuccess && <ImportResult result={upload.data} />}
      </div>
    </Card>
  )
}

function ImportResult({ result }: { result: CadencierImport }) {
  return (
    <Alert className="animate-in fade-in-0 slide-in-from-top-1 duration-200 motion-reduce:animate-none">
      <CircleCheckIcon className="text-emerald-600 dark:text-emerald-400" />
      <AlertTitle>
        Wgrano {countFormat.format(result.totalRows)} artykułów
        {result.reportGeneratedAt && <> z listy z {formatDocumentDate(result.reportGeneratedAt)}</>}
      </AlertTitle>
      <AlertDescription className="tabular-nums">
        Nowe względem poprzedniej listy: {countFormat.format(result.addedCount)} · ubyło:{' '}
        {countFormat.format(result.removedCount)}
      </AlertDescription>
    </Alert>
  )
}
