import type { NomenclatureOption } from '@/lib/types'

/** Dzieci każdej pozycji nomenklatury po kodzie rodzica; rejony są pod pustym kluczem. */
export type NomenclatureTree = Map<string, NomenclatureOption[]>

export type Mark = 'on' | 'some' | 'off'

export function nomenclatureTree(options: NomenclatureOption[]): NomenclatureTree {
  const tree: NomenclatureTree = new Map()
  for (const option of options) {
    const parent = option.code.slice(0, -2)
    tree.set(parent, [...(tree.get(parent) ?? []), option])
  }
  return tree
}

/** Wybór to najkrótsze kody: zaznaczony rejon obejmuje swoje rodziny i podrodziny bez wymieniania ich. */
export function markOf(selection: string[], code: string): Mark {
  if (selection.some((selected) => code.startsWith(selected))) return 'on'
  return selection.some((selected) => selected.startsWith(code)) ? 'some' : 'off'
}

/** Zaznaczenie pozycji; gdy zaznaczone są wszystkie dzieci, wybór zwija się do rodzica. */
function added(tree: NomenclatureTree, selection: string[], code: string) {
  let next = [...selection.filter((selected) => !selected.startsWith(code)), code]
  for (let current = code; current.length > 2; current = current.slice(0, -2)) {
    const parent = current.slice(0, -2)
    const siblings = tree.get(parent) ?? []
    if (!siblings.every((sibling) => next.includes(sibling.code))) break
    next = [...next.filter((selected) => !selected.startsWith(parent)), parent]
  }
  return next
}

/** Odznaczenie pozycji; gdy była objęta rodzicem, rodzic rozwija się do reszty swoich dzieci. */
function removed(tree: NomenclatureTree, selection: string[], code: string) {
  const covering = selection.find((selected) => code.startsWith(selected))
  const next = selection.filter((selected) => selected !== covering && !selected.startsWith(code))
  for (let current = covering; current && current !== code;) {
    const children = tree.get(current) ?? []
    const path = children.find((child) => code.startsWith(child.code))
    next.push(...children.filter((child) => child !== path).map((child) => child.code))
    current = path?.code
  }
  return next
}

export function withMark(tree: NomenclatureTree, selection: string[], code: string, on: boolean) {
  return (on ? added(tree, selection, code) : removed(tree, selection, code)).sort()
}
