import { useState } from 'react'
import { useNavigate } from 'react-router'
import { toast } from 'sonner'

import { FileDropZone } from '@/components/FileDropZone'
import { ImportError } from '@/components/ImportError'
import { useUploadStocktake } from '@/features/stocktake/api'
import { stocktakePath } from '@/features/stocktake/paths'

const numberFormat = new Intl.NumberFormat('pl-PL')

/** Wgranie raportu porównania stanów: po sukcesie otwiera inwenturę, której dotyczy raport. */
export function StocktakeUpload({ storeId, title }: { storeId: string; title: string }) {
  const navigate = useNavigate()
  const upload = useUploadStocktake(storeId)
  const [pendingFile, setPendingFile] = useState<File>()

  const start = (file: File) => {
    setPendingFile(file)
    upload.mutate(file, {
      onSuccess: (result) => {
        toast.success(`Wgrano raport: ${numberFormat.format(result.lineCount)} artykułów`, {
          description: result.wholeStore
            ? `Nowe: ${result.createdCount}, zmienione: ${result.updatedCount}, już bez różnicy: ${result.resolvedCount}.`
            : `Nowe: ${result.createdCount}, zmienione: ${result.updatedCount}.`,
        })
        navigate(`${stocktakePath}/${result.stocktakeId}`)
      },
      onSettled: () => setPendingFile(undefined),
    })
  }

  return (
    <div className="grid gap-3">
      <FileDropZone
        accept={['.pdf']}
        pendingFile={pendingFile}
        onFile={start}
        onReject={(fileName) => toast.error(`${fileName} to nie jest PDF. Wgraj porównanie stanów zapisane jako PDF.`)}
        title={title}
        hint="PDF „Porównanie stanów rzeczywistych i teoretycznych”. Raport całego sklepu czyta się do minuty."
      />
      {upload.error && <ImportError error={upload.error} />}
    </div>
  )
}
