import { useState } from 'react'

import { cn } from '@/lib/utils'

/** Inicjały z imienia i nazwiska: pierwsza litera pierwszego i ostatniego słowa (Anna Maria Nowak → AN). */
function initialsOf(name: string) {
  const words = name.trim().split(/\s+/).filter(Boolean)
  if (words.length === 0) return '?'
  const first = words[0][0] ?? ''
  const last = words.length > 1 ? (words[words.length - 1][0] ?? '') : ''
  return (first + last).toUpperCase()
}

/**
 * Zdjęcie profilowe w kółku, a bez zdjęcia (albo gdy się nie wczyta) inicjały. Rozmiar i tekst daje `className`
 * (np. `size-6 text-[0.625rem]`).
 */
export function UserAvatar({ name, url, className }: { name: string; url?: string | null; className?: string }) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null)
  const showImage = url && failedUrl !== url

  return (
    <span
      aria-hidden
      className={cn(
        'inline-flex shrink-0 items-center justify-center overflow-hidden rounded-full bg-muted font-semibold text-muted-foreground select-none',
        className,
      )}
    >
      {showImage ? (
        <img src={url} alt="" className="size-full object-cover" onError={() => setFailedUrl(url)} />
      ) : (
        initialsOf(name)
      )}
    </span>
  )
}
