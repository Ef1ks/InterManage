import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

type Props = {
  title: string
  description?: string
  action?: { label: string; onClick: () => void }
  /** Wewnątrz layoutu aplikacji zamiast na całym ekranie. */
  inline?: boolean
}

export function FullPageMessage({ title, description, action, inline }: Props) {
  return (
    <div
      className={cn(
        'flex flex-col items-center justify-center gap-3 p-6 text-center',
        inline ? 'min-h-[50vh]' : 'min-h-svh',
      )}
    >
      <h1 className="text-lg font-semibold">{title}</h1>
      {description && <p className="max-w-md text-sm text-muted-foreground">{description}</p>}
      {action && (
        <Button variant="outline" onClick={action.onClick}>
          {action.label}
        </Button>
      )}
    </div>
  )
}
