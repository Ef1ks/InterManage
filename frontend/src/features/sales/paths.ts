import { TOOL_ROUTES } from '@/features/tools/categories'

const base = TOOL_ROUTES.SALES.path

export const salesPaths = {
  overview: base,
  comparison: `${base}/porownanie`,
  deliCosts: `${base}/koszty-garmazu`,
  periodSummary: `${base}/podsumowanie`,
  priceCheck: `${base}/zakup-bazowy-poza-baza`,
  overpayments: `${base}/przeplacone-artykuly`,
  lowMargins: `${base}/za-niskie-marze`,
  topSales: `${base}/topy`,
  gratis: `${base}/gratisy`,
  protocols: `${base}/protokoly`,
  promotions: `${base}/promocje`,
  zeroStock: `${base}/zerowki`,
  slowMoving: `${base}/niesprzedajace-sie`,
}
