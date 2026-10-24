/** Typy odpowiadające DTO z backendu (com.intermanage.backend). */

export type PermissionCategory = 'SALES' | 'INVENTORY' | 'ORDERS' | 'STAFF' | 'IMPORTS'

export type PermissionLevel = 'VIEW' | 'EDIT'

export type Permissions = Partial<Record<PermissionCategory, PermissionLevel>>

export type Me = {
  id: number
  username: string
  displayName: string
  email: string | null
  roleName: string
  owner: boolean
  permissions: Permissions
  /** Nowe konto albo hasło tymczasowe: zanim zrobi cokolwiek innego, musi ustawić własne hasło. */
  passwordChangeRequired: boolean
}

export type PermissionCategoryInfo = {
  code: PermissionCategory
  label: string
  description: string
}

export type Role = {
  id: number
  name: string
  description: string | null
  owner: boolean
  permissions: Permissions
  userCount: number
  createdAt: string
}

export type RoleRequest = {
  name: string
  description: string
  permissions: Permissions
}

export type User = {
  id: number
  username: string
  displayName: string
  email: string | null
  roleId: number
  roleName: string
  owner: boolean
  enabled: boolean
  /** Zaproszony, ale jeszcze nie kliknął linku aktywacyjnego. */
  pendingActivation: boolean
  createdAt: string
}

export type CreateUserRequest = {
  username: string
  displayName: string
  email: string
  roleId: number
}

export type UpdateUserRequest = {
  displayName: string
  email: string | null
  roleId: number
  enabled: boolean
  newPassword?: string
}
