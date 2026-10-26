import {
  ArrowLeftRightIcon,
  FilePenLineIcon,
  FileTextIcon,
  GiftIcon,
  PackageCheckIcon,
  PackageXIcon,
  StoreIcon,
  TruckIcon,
  Undo2Icon,
  WrenchIcon,
  type LucideIcon,
} from 'lucide-react'

/** Ikony kafelków po kodzie typu z backendu (DocumentType.java); nowy typ bez ikony dostaje {@link FALLBACK_ICON}. */
export const DOCUMENT_ICONS: Partial<Record<string, LucideIcon>> = {
  PZ: PackageCheckIcon,
  PK: WrenchIcon,
  WZ: FilePenLineIcon,
  PL: PackageXIcon,
  PPP: ArrowLeftRightIcon,
  ZW: Undo2Icon,
  MM: TruckIcon,
  RWPO: StoreIcon,
  RWREP: GiftIcon,
}

export const FALLBACK_ICON = FileTextIcon
