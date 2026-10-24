import { cn } from '@/lib/utils'

/** Znak aplikacji: czerwony kafelek z inicjałem i nazwa. Jedyne miejsce, gdzie czerwień marki gra pierwsze skrzypce. */
export function BrandMark({ className, compact }: { className?: string; compact?: boolean }) {
  return (
    <div className={cn('flex items-center gap-2.5', className)}>
      <span
        aria-hidden
        className="flex size-8 items-center justify-center rounded-lg bg-brand text-[0.95rem] font-semibold text-brand-foreground shadow-[inset_0_-1px_0_rgb(0_0_0/0.15)]"
      >
        I
      </span>
      {!compact && <span className="text-[0.95rem] font-semibold tracking-tight">Intermanage</span>}
    </div>
  )
}
