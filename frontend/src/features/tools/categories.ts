import { useQuery } from '@tanstack/react-query'
import {
  BoxesIcon,
  CalculatorIcon,
  ChartColumnIcon,
  ClipboardListIcon,
  FileSpreadsheetIcon,
  FileTextIcon,
  ReceiptTextIcon,
  SheetIcon,
  TruckIcon,
  UsersIcon,
  type LucideIcon,
} from 'lucide-react'

import { api } from '@/lib/api'
import type { PermissionCategory, PermissionCategoryInfo } from '@/lib/types'

/**
 * Ścieżki i ikony narzędzi po stronie frontu. Nazwy i opisy kategorii przychodzą z backendu
 * (PermissionCategory.java) - nowa kategoria wymaga wpisu w obu miejscach.
 */
export const TOOL_ROUTES: Record<PermissionCategory, { path: string; icon: LucideIcon }> = {
  SALES: { path: '/sprzedaz', icon: ChartColumnIcon },
  INVENTORY: { path: '/magazyn', icon: BoxesIcon },
  ORDERS: { path: '/zamowienia', icon: TruckIcon },
  STAFF: { path: '/pracownicy', icon: UsersIcon },
  IMPORTS: { path: '/import', icon: FileSpreadsheetIcon },
  DELI_ORDERS: { path: '/zamowienie-garmazu', icon: ClipboardListIcon },
  DOCUMENTS: { path: '/dokumenty', icon: FileTextIcon },
  DELI_PRICES: { path: '/kalkulator-garmazu', icon: CalculatorIcon },
  KSEF_INVOICES: { path: '/faktury-ksef', icon: ReceiptTextIcon },
  DATA_ENTRY: { path: '/wprowadzanie-danych', icon: SheetIcon },
}

export function usePermissionCategories() {
  return useQuery({
    queryKey: ['permission-categories'],
    queryFn: () => api<PermissionCategoryInfo[]>('/permission-categories'),
    staleTime: Infinity,
  })
}
