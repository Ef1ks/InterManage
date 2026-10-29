import { TOOL_ROUTES } from '@/features/tools/categories'

const base = TOOL_ROUTES.SALES.path

export const salesPaths = {
  overview: base,
  comparison: `${base}/porownanie`,
  deliCosts: `${base}/koszty-garmazu`,
  periodSummary: `${base}/podsumowanie`,
  priceCheck: `${base}/zakup-bazowy-poza-baza`,
  gratis: `${base}/gratisy`,
}
