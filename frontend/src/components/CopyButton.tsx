import { useEffect, useState } from 'react'
import { CheckIcon, CopyIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

/**
 * Mały przycisk kopiowania tekstu do schowka (np. numeru dokumentu). Nie przepuszcza kliknięcia dalej, więc działa
 * w klikalnym wierszu tabeli; po skopiowaniu na chwilę pokazuje ptaszek.
 */
export function CopyButton({ value, label, className }: { value: string; label: string; className?: string }) {
  const [copied, setCopied] = useState(false)
  useEffect(() => {
    if (!copied) return
    const timeout = window.setTimeout(() => setCopied(false), 1500)
    return () => window.clearTimeout(timeout)
  }, [copied])

  const copy = (event: React.MouseEvent) => {
    event.stopPropagation()
    navigator.clipboard
      .writeText(value)
      .then(() => setCopied(true))
      .catch(() => toast.error('Nie udało się skopiować'))
  }

  return (
    <Button
      variant="ghost"
      size="icon-xs"
      aria-label={label}
      title={copied ? 'Skopiowano' : label}
      onClick={copy}
      onKeyDown={(event) => event.stopPropagation()}
      className={cn('text-muted-foreground', className)}
    >
      {copied ? <CheckIcon /> : <CopyIcon />}
    </Button>
  )
}
