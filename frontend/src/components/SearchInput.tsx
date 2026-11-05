import { SearchIcon, XIcon } from 'lucide-react'
import type { Ref } from 'react'

import { cn } from '@/lib/utils'

type Props = {
  value: string
  onChange: (value: string) => void
  /** Podpowiedź, po czym można szukać; służy też jako etykieta dostępności. */
  placeholder: string
  className?: string
  inputRef?: Ref<HTMLInputElement>
  autoFocus?: boolean
  onEnter?: () => void
}

/** Filtr listy na żywo, z przyciskiem czyszczenia. */
export function SearchInput({ value, onChange, placeholder, className, inputRef, autoFocus, onEnter }: Props) {
  return (
    <div className={cn('relative', className)}>
      <SearchIcon aria-hidden className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
      <input
        ref={inputRef}
        type="search"
        autoFocus={autoFocus}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Escape') onChange('')
          if (event.key === 'Enter') onEnter?.()
        }}
        aria-label={placeholder}
        placeholder={placeholder}
        className="h-10 w-full rounded-xl border border-input bg-background dark:bg-input/30 pr-9 pl-9 text-sm transition-[border-color,box-shadow] duration-150 outline-none placeholder:text-muted-foreground focus:border-ring focus:ring-3 focus:ring-ring/30 [&::-webkit-search-cancel-button]:hidden"
      />
      {value && (
        <button
          type="button"
          aria-label="Wyczyść wyszukiwanie"
          onClick={() => onChange('')}
          className="absolute top-1/2 right-2 flex size-6 -translate-y-1/2 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
        >
          <XIcon className="size-3.5" />
        </button>
      )}
    </div>
  )
}
