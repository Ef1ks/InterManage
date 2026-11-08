import type { FieldValues, UseFormSetValue } from 'react-hook-form'

/** Firma z rejestru REGON (GUS) po NIP-ie, `GET /api/gus/companies?nip=`. */
export type GusCompany = {
  nip: string
  regon: string | null
  name: string | null
  /** Ulica z numerem, w miejscowości bez ulic miejscowość z numerem. */
  street: string | null
  postalCode: string | null
  city: string | null
  /** Cały adres w jednej linii dla formularzy z jednym polem adresu. */
  address: string | null
  /** Firma zakończyła działalność. */
  endedAt: string | null
}

type CompanyFields = { name: string; nip: string; address: string; postalCode: string; city: string }

/** Wpisuje dane z GUS w formularz z polami nazwa, NIP, adres (ulica), kod pocztowy i miejscowość. */
export function fillFromGus<T extends FieldValues & CompanyFields>(setValue: UseFormSetValue<T>, company: GusCompany) {
  const set = setValue as unknown as UseFormSetValue<CompanyFields>
  const options = { shouldDirty: true, shouldValidate: true }
  if (company.name) set('name', company.name, options)
  set('nip', company.nip, options)
  set('address', company.street ?? '', options)
  set('postalCode', company.postalCode ?? '', options)
  set('city', company.city ?? '', options)
}
