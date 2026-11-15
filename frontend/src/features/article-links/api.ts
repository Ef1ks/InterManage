import { useMutation } from '@tanstack/react-query'
import { toast } from 'sonner'

import { api, ApiError } from '@/lib/api'
import type { ArticleLinkRequest } from '@/lib/types'

/** `api()` sam dokłada `/api`. */
const linksPath = (storeId: string) => `/stores/${storeId}/article-links`

/**
 * Połączenia tego samego towaru w różnych sklepach (raport lady, porównanie cen mięsa). Raporty czytają je same, a
 * po każdej zmianie MutationCache odświeża zapytania na ekranie. Każda zmiana ma toast z cofnięciem.
 */
export function useArticleLinks(storeId: string) {
  const link = useMutation({
    mutationFn: (request: ArticleLinkRequest) =>
      api<{ id: string }>(linksPath(storeId), { method: 'POST', json: request }),
  })
  const unlink = useMutation({
    mutationFn: (id: string) => api<void>(`${linksPath(storeId)}/${id}`, { method: 'DELETE' }),
  })
  const restore = useMutation({
    mutationFn: (id: string) => api<void>(`${linksPath(storeId)}/${id}/restore`, { method: 'POST' }),
  })

  const onError = (fallback: string) => (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : fallback)

  return {
    pending: link.isPending || unlink.isPending || restore.isPending,
    link: (request: ArticleLinkRequest, label: string, onDone?: () => void) =>
      link.mutate(request, {
        onError: onError('Nie udało się połączyć artykułów'),
        onSuccess: (created) => {
          onDone?.()
          toast.success(`Połączono: ${label}`, {
            action: {
              label: 'Cofnij',
              onClick: () => unlink.mutate(created.id, { onError: onError('Nie udało się cofnąć połączenia') }),
            },
          })
        },
      }),
    unlink: (id: string, label: string, onDone?: () => void) =>
      unlink.mutate(id, {
        onError: onError('Nie udało się odłączyć artykułów'),
        onSuccess: () => {
          onDone?.()
          toast.success(`Odłączono: ${label}`, {
            action: {
              label: 'Przywróć',
              onClick: () => restore.mutate(id, { onError: onError('Nie udało się przywrócić połączenia') }),
            },
          })
        },
      }),
  }
}
