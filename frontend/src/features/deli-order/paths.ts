import { TOOL_ROUTES } from '@/features/tools/categories'

/**
 * Bezwzględne ścieżki podstron zamówienia garmażu. Moduł siedzi pod trasą z gwiazdką, a w niej react-router liczy
 * ścieżki względne od całego bieżącego adresu - względny link "wyslane" doklejałby się przy każdym kliknięciu.
 */
const base = TOOL_ROUTES.DELI_ORDERS.path

export const deliPaths = {
  order: base,
  sent: `${base}/wyslane`,
  incoming: `${base}/przychodzace`,
  form: `${base}/formularz`,
  orderDetail: (orderId: string) => `${base}/zamowienia/${orderId}`,
}
