import { useMutation, useQuery } from '@tanstack/react-query'

import { TOOL_ROUTES } from '@/features/tools/categories'
import { api } from '@/lib/api'
import type {
  ProductionBase,
  ProductionCard,
  ProductionCardSummary,
  ProductionImportResult,
  ProductionSchema,
} from '@/lib/types'

const base = TOOL_ROUTES.DELI_PRODUCTION.path

export const productionPaths = {
  cards: base,
  partners: `${base}/sklepy-zaprzyjaznione`,
  base: `${base}/baza`,
  create: `${base}/karta/nowa`,
  card: (id: string) => `${base}/karta/${id}`,
}

const productionKey = (storeId: string) => ['stores', storeId, 'production'] as const
const path = (storeId: string) => `/stores/${storeId}/production`

export const formPdfPath = (storeId: string) => `${path(storeId)}/form/pdf`
export const cardPdfPath = (storeId: string, cardId: string) => `${path(storeId)}/cards/${cardId}/pdf`

export type CardScope = 'OWN' | 'PARTNERS'

export function useProductionBase(storeId: string) {
  return useQuery({
    queryKey: [...productionKey(storeId), 'base'],
    queryFn: () => api<ProductionBase>(`${path(storeId)}/base`),
  })
}

export function useProductionCards(storeId: string, from: string, to: string, scope: CardScope) {
  return useQuery({
    queryKey: [...productionKey(storeId), 'cards', scope, from, to],
    queryFn: () => api<ProductionCardSummary[]>(`${path(storeId)}/cards?from=${from}&to=${to}&scope=${scope}`),
  })
}

export function useProductionCard(storeId: string, id: string | undefined) {
  return useQuery({
    queryKey: [...productionKey(storeId), 'card', id],
    queryFn: () => api<ProductionCard>(`${path(storeId)}/cards/${id}`),
    enabled: id != null,
  })
}

export type CardForm = {
  productionDate: string
  lines: { articleId: string; quantity: number; startHour: number }[]
}

export function useSaveCard(storeId: string, id: string | undefined) {
  return useMutation({
    mutationFn: (form: CardForm) =>
      api<ProductionCard>(id ? `${path(storeId)}/cards/${id}` : `${path(storeId)}/cards`, {
        method: id ? 'PUT' : 'POST',
        json: form,
      }),
  })
}

export function useDeleteCard(storeId: string) {
  return useMutation({
    mutationFn: (id: string) => api(`${path(storeId)}/cards/${id}`, { method: 'DELETE' }),
  })
}

export function useRestoreCard(storeId: string) {
  return useMutation({
    mutationFn: (id: string) => api<ProductionCard>(`${path(storeId)}/cards/${id}/restore`, { method: 'POST' }),
  })
}

export type SchemaForm = Omit<ProductionSchema, 'id' | 'articleCount'>
export type ArticleForm = { code: string | null; name: string; nomenclature: string | null; schemaId: string }

/** Zmiany bazy: każda zwraca całą bazę (schematy z liczbą artykułów, artykuły, pieczątkę). */
export function useBaseMutations(storeId: string) {
  const p = path(storeId)
  const saveSchema = useMutation({
    mutationFn: ({ id, form }: { id?: string; form: SchemaForm }) =>
      api<ProductionBase>(id ? `${p}/schemas/${id}` : `${p}/schemas`, { method: id ? 'PUT' : 'POST', json: form }),
  })
  const deleteSchema = useMutation({
    mutationFn: (id: string) => api(`${p}/schemas/${id}`, { method: 'DELETE' }),
  })
  const restoreSchema = useMutation({
    mutationFn: (id: string) => api<ProductionBase>(`${p}/schemas/${id}/restore`, { method: 'POST' }),
  })
  const saveArticle = useMutation({
    mutationFn: ({ id, form }: { id?: string; form: ArticleForm }) =>
      api<ProductionBase>(id ? `${p}/articles/${id}` : `${p}/articles`, { method: id ? 'PUT' : 'POST', json: form }),
  })
  const changeSchema = useMutation({
    mutationFn: (json: { articleIds: string[]; schemaId: string }) =>
      api<ProductionBase>(`${p}/articles/schema`, { method: 'PUT', json }),
  })
  const deleteArticle = useMutation({
    mutationFn: (id: string) => api(`${p}/articles/${id}`, { method: 'DELETE' }),
  })
  const restoreArticle = useMutation({
    mutationFn: (id: string) => api<ProductionBase>(`${p}/articles/${id}/restore`, { method: 'POST' }),
  })
  const saveStamp = useMutation({
    mutationFn: (stamp: string) => api<ProductionBase>(`${p}/settings`, { method: 'PUT', json: { stamp } }),
  })
  const importBase = useMutation({
    mutationFn: (file: File) => {
      const form = new FormData()
      form.append('file', file)
      return api<ProductionImportResult>(`${p}/base/import`, { method: 'POST', formData: form })
    },
  })
  return {
    saveSchema,
    deleteSchema,
    restoreSchema,
    saveArticle,
    changeSchema,
    deleteArticle,
    restoreArticle,
    saveStamp,
    importBase,
  }
}

/** Karty produkcji przy ruchu międzymagazynowym sklepu (moduł Dokumenty). */
const documentCardsPath = (storeId: string, type: string, documentId: string) =>
  `/stores/${storeId}/documents/${type}/${documentId}/production-cards`

export const documentCardPdfPath = (storeId: string, type: string, documentId: string, cardId: string) =>
  `${documentCardsPath(storeId, type, documentId)}/${cardId}/pdf`

export function useDocumentProductionCards(storeId: string, type: string, documentId: string) {
  return useQuery({
    queryKey: ['stores', storeId, 'documents', type, documentId, 'production-cards'],
    queryFn: () => api<ProductionCardSummary[]>(documentCardsPath(storeId, type, documentId)),
  })
}

export function useProductionCardCandidates(storeId: string, type: string, documentId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['stores', storeId, 'documents', type, documentId, 'production-cards', 'candidates'],
    queryFn: () => api<ProductionCardSummary[]>(`${documentCardsPath(storeId, type, documentId)}/candidates`),
    enabled,
  })
}

export function useLinkProductionCard(storeId: string, type: string, documentId: string) {
  const p = documentCardsPath(storeId, type, documentId)
  const link = useMutation({
    mutationFn: (cardId: string) => api<ProductionCardSummary[]>(p, { method: 'POST', json: { cardId } }),
  })
  const unlink = useMutation({
    mutationFn: (cardId: string) => api<ProductionCardSummary[]>(`${p}/${cardId}`, { method: 'DELETE' }),
  })
  return { link, unlink }
}

/** Karty produkcji przy ruchu międzymagazynowym do wprowadzenia (sklep docelowy). */
const incomingCardsPath = (storeId: string, transferId: string) =>
  `/stores/${storeId}/incoming-transfers/${transferId}/production-cards`

export const incomingCardPdfPath = (storeId: string, transferId: string, cardId: string) =>
  `${incomingCardsPath(storeId, transferId)}/${cardId}/pdf`

export function useIncomingProductionCards(storeId: string, transferId: string) {
  return useQuery({
    queryKey: ['stores', storeId, 'incoming-transfers', transferId, 'production-cards'],
    queryFn: () => api<ProductionCardSummary[]>(incomingCardsPath(storeId, transferId)),
  })
}
