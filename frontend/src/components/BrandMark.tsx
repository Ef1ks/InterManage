import { cn } from '@/lib/utils'

/** Znak aplikacji: napis Intermanage (hero.webp), a w wersji kompaktowej kwadratowe logo (favicon.svg). Jedyne miejsce, gdzie czerwień marki gra pierwsze skrzypce. */
export function BrandMark({ className, compact }: { className?: string; compact?: boolean }) {
  return (
    <div className={cn('flex items-center', className)}>
      {compact ? (
        <img src="/favicon.svg" alt="Intermanage" className="size-8 shrink-0" />
      ) : (
        <img src="/hero.webp" alt="Intermanage" className="h-7 w-auto" />
      )}
    </div>
  )
}
