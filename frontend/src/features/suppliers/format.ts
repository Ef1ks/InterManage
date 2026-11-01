import { TOOL_ROUTES } from '@/features/tools/categories'
import type { SupplierContactKind } from '@/lib/types'

export const supplierPaths = {
  list: TOOL_ROUTES.SUPPLIERS.path,
  supplier: (id: string) => `${TOOL_ROUTES.SUPPLIERS.path}/${id}`,
}

/** Rodzaje kontaktów w kolejności z backendu (przedstawiciel na górze). */
export const CONTACT_KINDS: { value: SupplierContactKind; label: string }[] = [
  { value: 'REPRESENTATIVE', label: 'Przedstawiciel handlowy' },
  { value: 'ORDERS', label: 'Zamówienia / biuro' },
  { value: 'ACCOUNTING', label: 'Księgowość / faktury' },
  { value: 'COMPLAINTS', label: 'Reklamacje' },
  { value: 'OTHER', label: 'Inny kontakt' },
]

export const contactKindLabel = (kind: SupplierContactKind) =>
  CONTACT_KINDS.find((option) => option.value === kind)?.label ?? kind

/** Adres strony bez protokołu i ukośnika na końcu: "https://www.sokolow.pl/" -> "www.sokolow.pl". */
export function websiteLabel(url: string) {
  return url.replace(/^https?:\/\//i, '').replace(/\/$/, '')
}

/** Numer do linku tel: bez spacji i myślników. */
export function phoneHref(phone: string) {
  return `tel:${phone.replace(/[^\d+]/g, '')}`
}

const dateTimeFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'medium', timeStyle: 'short' })
const dateFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'medium' })

export const formatDateTime = (iso: string) => dateTimeFormat.format(new Date(iso))
export const formatDate = (iso: string) => dateFormat.format(new Date(iso))
