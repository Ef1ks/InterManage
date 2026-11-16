import { useEffect } from 'react'

/**
 * Zaznaczanie wielu pól wyboru przeciągnięciem myszy, w całej aplikacji (też w oknach, bo nasłuch jest na dokumencie).
 * Wciśnięcie na polu wyboru (albo jego etykiecie) i przeciągnięcie w górę lub w dół ustawia wszystkie pola po drodze
 * na stan przeciwny do pierwszego pola: pierwsze niezaznaczone = zaznaczanie, zaznaczone = odznaczanie. Liczy się wiersz
 * (`tr`, `li`, `[role=row]`), więc kursor nie musi trafiać w samo pole. Zwykły klik działa jak dotąd: tryb przeciągania
 * rusza dopiero po wejściu na inny wiersz. Przy krawędzi przewijanej listy albo okna widok przewija się sam.
 *
 * Pola biorą udział tylko z tego samego zakresu co pierwsze (`[data-drag-select-scope]`, tabela, lista, okno), bez pól
 * z nagłówka tabeli („zaznacz wszystkie”) i wyłączonych. `data-drag-select="off"` wyłącza mechanizm dla elementu
 * (lista z własnym przeciąganiem). Zmiana stanu idzie przez `click()`, więc obsługa w komponentach się nie zmienia.
 */

type Box = HTMLInputElement | HTMLElement

const BOX = 'input[type="checkbox"], [role="checkbox"]'
const ROW = 'tr, li, [role="row"], [role="option"], [data-drag-row]'
const SCOPE = '[data-drag-select-scope], table, ul, ol, [role="listbox"], [role="dialog"], [data-slot="dialog-content"]'

function boxAt(target: EventTarget | null): Box | null {
  if (!(target instanceof Element)) return null
  if (target.closest('[data-drag-select="off"]')) return null
  const direct = target.closest<HTMLElement>(BOX)
  if (direct) return direct
  // Etykieta z polem w środku (np. <label><input type="checkbox" /> Nazwa</label>).
  const label = target.closest('label')
  return label?.querySelector<HTMLElement>(BOX) ?? null
}

const isChecked = (box: Box) =>
  box instanceof HTMLInputElement ? box.checked : box.getAttribute('aria-checked') === 'true'

const isDisabled = (box: Box) =>
  box instanceof HTMLInputElement
    ? box.disabled
    : box.getAttribute('aria-disabled') === 'true' || box.hasAttribute('data-disabled')

const rowOf = (box: Box) => box.closest<HTMLElement>(ROW) ?? box.closest('label') ?? box

function scrollParent(element: Element): HTMLElement | null {
  for (let node = element.parentElement; node; node = node.parentElement) {
    const { overflowY } = getComputedStyle(node)
    if ((overflowY === 'auto' || overflowY === 'scroll') && node.scrollHeight > node.clientHeight) return node
  }
  return null
}

export function useCheckboxDragSelect() {
  useEffect(() => {
    const onPointerDown = (event: PointerEvent) => {
      if (event.button !== 0 || event.pointerType !== 'mouse' || event.defaultPrevented) return
      const start = boxAt(event.target)
      if (!start || isDisabled(start) || start.closest('thead')) return
      const scope = start.closest(SCOPE) ?? document.body
      const value = !isChecked(start)
      const scroller = scrollParent(start)
      let pointer = { x: event.clientX, y: event.clientY }
      let last: Box = start
      let dragging = false
      let frame = 0
      // Wiersze liczymy od nowa tylko po ruchu kursora albo przewinięciu, nie w każdej klatce.
      let moved = false

      const candidates = () =>
        [...scope.querySelectorAll<HTMLElement>(BOX)].filter(
          (box) =>
            !isDisabled(box) &&
            !box.closest('thead') &&
            !box.closest('[data-drag-select="off"]') &&
            (box.closest(SCOPE) ?? document.body) === scope,
        )

      // Pole z wiersza na wysokości kursora, a poza wierszami najbliższe w pionie.
      const boxUnderPointer = (boxes: HTMLElement[]) => {
        let best: HTMLElement | null = null
        let distance = Infinity
        for (const box of boxes) {
          const rect = rowOf(box).getBoundingClientRect()
          if (rect.height === 0) continue
          if (pointer.y >= rect.top && pointer.y < rect.bottom) return box
          const gap = pointer.y < rect.top ? rect.top - pointer.y : pointer.y - rect.bottom
          if (gap < distance) {
            distance = gap
            best = box
          }
        }
        return best
      }

      const tick = () => {
        const area = scroller?.getBoundingClientRect() ?? { top: 0, bottom: window.innerHeight }
        const edge = 40
        const step =
          pointer.y < area.top + edge
            ? -Math.min(Math.ceil((area.top + edge - pointer.y) / 3), 30)
            : pointer.y > area.bottom - edge
              ? Math.min(Math.ceil((pointer.y - area.bottom + edge) / 3), 30)
              : 0
        if (dragging && step !== 0) {
          if (scroller) scroller.scrollTop += step
          else window.scrollBy(0, step)
          moved = true
        }
        if (!moved) {
          frame = requestAnimationFrame(tick)
          return
        }
        moved = false

        const boxes = candidates()
        const target = boxUnderPointer(boxes)
        if (target && target !== last) {
          if (!dragging) {
            dragging = true
            document.body.style.userSelect = 'none'
            window.getSelection()?.removeAllRanges()
          }
          // Szybki ruch przeskakuje wiersze między klatkami: cały zakres od poprzedniego pola, razem z pierwszym.
          const from = boxes.indexOf(last)
          const to = boxes.indexOf(target)
          const range = from < 0 ? [target] : boxes.slice(Math.min(from, to), Math.max(from, to) + 1)
          for (const box of range) if (isChecked(box) !== value) box.click()
          if (!range.includes(start) && isChecked(start) !== value) start.click()
          last = target
        }
        frame = requestAnimationFrame(tick)
      }

      const onMove = (move: PointerEvent) => {
        pointer = { x: move.clientX, y: move.clientY }
        moved = true
      }
      const stop = () => {
        cancelAnimationFrame(frame)
        window.removeEventListener('pointermove', onMove)
        window.removeEventListener('pointerup', stop)
        window.removeEventListener('pointercancel', stop)
        if (!dragging) return
        document.body.style.userSelect = ''
        // Klik po puszczeniu przycisku (np. na pierwszym polu) przełączyłby pole drugi raz.
        const swallow = (click: MouseEvent) => {
          click.preventDefault()
          click.stopPropagation()
        }
        window.addEventListener('click', swallow, { capture: true, once: true })
        window.setTimeout(() => window.removeEventListener('click', swallow, { capture: true }), 0)
      }

      window.addEventListener('pointermove', onMove)
      window.addEventListener('pointerup', stop)
      window.addEventListener('pointercancel', stop)
      frame = requestAnimationFrame(tick)
    }

    document.addEventListener('pointerdown', onPointerDown)
    return () => document.removeEventListener('pointerdown', onPointerDown)
  }, [])
}
