import type { DeliOrderItem } from '@/lib/types'

/** Pozycje w kolejności formularza, pogrupowane po dziale i kategorii (nazwy z chwili złożenia zamówienia). */
export function groupItems(items: DeliOrderItem[]) {
  const sections: { section: string; categories: { category: string; items: DeliOrderItem[] }[] }[] = []
  for (const item of items) {
    let section = sections.find((s) => s.section === item.sectionName)
    if (!section) sections.push((section = { section: item.sectionName, categories: [] }))
    let category = section.categories.find((c) => c.category === item.categoryName)
    if (!category) section.categories.push((category = { category: item.categoryName, items: [] }))
    category.items.push(item)
  }
  return sections
}
