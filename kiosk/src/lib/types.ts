/** Typy API z backendu (`TimeClockController`, `StaffTimeController`, logowanie mobilne), tylko używane. */

export type ReaderInfo = {
  readerName: string
  storeId: string
  storeNumber: string
  storeName: string
}

export type PunchResult = {
  punchId: string
  employeeName: string
  direction: 'IN' | 'OUT'
  punchedAt: string
  /** Wyjście, które można jeszcze zamienić na przerwę (nie było dziś przerwy). */
  breakAvailable: boolean
  /** Wejście po przerwie. */
  breakEnded: boolean
}

export type PermissionLevel = 'VIEW' | 'EDIT'

export type Me = {
  id: string
  username: string
  displayName: string
  owner: boolean
  permissions: Partial<Record<string, PermissionLevel>>
  passwordChangeRequired: boolean
  stores: { id: string; number: string; name: string }[]
}

export type MobileLogin = { token: string; me: Me }

/** Pracownik sklepu z kartą: `cardUid` = karta NFC, `rcpNumber` = numer karty RCP z Symfonii. */
export type EmployeeCard = {
  employeeId: string
  name: string
  departmentName: string | null
  cardUid: string | null
  rcpNumber: number | null
}

export type TimeSettings = {
  cards: EmployeeCard[]
}
