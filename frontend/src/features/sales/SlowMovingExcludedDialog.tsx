import { useState } from 'react'
import { EyeOffIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCan } from '@/features/auth/api'
import { useExcludeSlowMoving, useRemoveSlowMovingExcluded, useSlowMovingExcluded } from '@/features/sales/api'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { ApiError } from '@/lib/api'
import type { SlowMovingExcludedItem } from '@/lib/types'

/** Lista artykułów pomijanych w raporcie, przycisk widać dopiero, gdy jest na niej co najmniej jeden. */
export function SlowMovingExcludedDialog({ storeId }: { storeId: string }) {
  const excluded = useSlowMovingExcluded(storeId)
  const remove = useRemoveSlowMovingExcluded(storeId)
  const exclude = useExcludeSlowMoving(storeId)
  const canEdit = useCan('SALES', 'EDIT')
  const [open, setOpen] = useState(false)
  const items = excluded.data ?? []
  if (items.length === 0 && !open) return null

  const onError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się zmienić listy pomijanych')
  const bringBack = (item: SlowMovingExcludedItem) =>
    remove.mutate(item.id, {
      onError,
      onSuccess: () =>
        toast.success(`Wróciło do raportu: ${item.name ?? item.itm8}`, {
          action: { label: 'Cofnij', onClick: () => exclude.mutate(item.itm8, { onError }) },
        }),
    })

  return (
    <>
      <Button variant="outline" size="lg" onClick={() => setOpen(true)}>
        <EyeOffIcon />
        Pomijane ({items.length})
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="gap-4 p-6 sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle className="text-lg">Pomijane artykuły</DialogTitle>
            <DialogDescription>
              Tych artykułów nie ma w raporcie ani na wydruku. Lista jest wspólna dla całego sklepu.
            </DialogDescription>
          </DialogHeader>
          {items.length === 0 ? (
            <p className="py-6 text-center text-sm text-muted-foreground">Żaden artykuł nie jest pomijany.</p>
          ) : (
            <ul className="divide-y">
              {items.map((item) => (
                <li key={item.id} className="flex flex-wrap items-center gap-x-4 gap-y-1 py-2 text-sm">
                  <div className="min-w-0 flex-1 basis-64">
                    <p className="font-medium">
                      {item.name ?? 'Bez nazwy'}
                      {item.brand && <span className="ml-1.5 font-normal text-muted-foreground">{item.brand}</span>}
                      <ArticleStatsButton article={item} className="ml-1" />
                    </p>
                    <p className="text-xs text-muted-foreground tabular-nums">
                      ITM8 {item.itm8.replace(/^0+/, '')}
                      {item.ean && ` · EAN ${item.ean}`} · {new Date(item.excludedAt).toLocaleDateString('pl-PL')}
                      {item.excludedBy && ` · ${item.excludedBy}`}
                    </p>
                  </div>
                  {canEdit && (
                    <Button variant="outline" size="sm" onClick={() => bringBack(item)}>
                      Przywróć do raportu
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </DialogContent>
      </Dialog>
    </>
  )
}
