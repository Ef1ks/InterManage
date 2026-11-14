import { useQuery } from '@tanstack/react-query'
import {
  BeefIcon,
  BookUserIcon,
  BoxesIcon,
  CalculatorIcon,
  CalendarDaysIcon,
  ChartColumnIcon,
  ClipboardCheckIcon,
  ClipboardListIcon,
  FileSpreadsheetIcon,
  FileTextIcon,
  IdCardIcon,
  ReceiptTextIcon,
  SheetIcon,
  ShoppingCartIcon,
  TimerIcon,
  UsersIcon,
  WalletIcon,
  type LucideIcon,
} from 'lucide-react'

import { api } from '@/lib/api'
import type { PermissionCategory, PermissionCategoryInfo, ToolCategory } from '@/lib/types'

/**
 * Ścieżki i ikony narzędzi po stronie frontu. Nazwy i opisy kategorii przychodzą z backendu
 * (PermissionCategory.java) - nowa kategoria wymaga wpisu w obu miejscach.
 */
export const TOOL_ROUTES: Record<ToolCategory, { path: string; icon: LucideIcon }> = {
  SALES: { path: '/sprzedaz', icon: ChartColumnIcon },
  INVENTORY: { path: '/magazyn', icon: BoxesIcon },
  ORDERS: { path: '/zamowienia', icon: ShoppingCartIcon },
  STAFF: { path: '/pracownicy', icon: UsersIcon },
  IMPORTS: { path: '/import', icon: FileSpreadsheetIcon },
  DELI_ORDERS: { path: '/zamowienie-garmazu', icon: ClipboardListIcon },
  DOCUMENTS: { path: '/dokumenty', icon: FileTextIcon },
  DELI_PRICES: { path: '/kalkulator-garmazu', icon: CalculatorIcon },
  KSEF_INVOICES: { path: '/faktury-ksef', icon: ReceiptTextIcon },
  DATA_ENTRY: { path: '/wprowadzanie-danych', icon: SheetIcon },
  SUPPLIERS: { path: '/dostawcy', icon: BookUserIcon },
  MEAT_PURCHASES: { path: '/zakup-miesa', icon: BeefIcon },
  DELI_PRODUCTION: { path: '/karty-produkcji', icon: ClipboardCheckIcon },
  STAFF_RECORDS: { path: '/kadry', icon: IdCardIcon },
  STAFF_SCHEDULE: { path: '/grafik', icon: CalendarDaysIcon },
  STAFF_TIME: { path: '/czas-pracy', icon: TimerIcon },
  CASH_REPORT: { path: '/raport-kasowy', icon: WalletIcon },
}

/** Czy kategoria to narzędzie ze stroną (a nie np. zawartość pulpitu). */
export function isTool(category: PermissionCategory): category is ToolCategory {
  return category in TOOL_ROUTES
}

export function usePermissionCategories() {
  return useQuery({
    queryKey: ['permission-categories'],
    queryFn: () => api<PermissionCategoryInfo[]>('/permission-categories'),
    staleTime: Infinity,
  })
}
