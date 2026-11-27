import { useLayoutEffect, useRef, useState } from 'react'

import { code128Bars } from '@/lib/code128'

/**
 * Kod na ekranie, zawsze w całości. Najwęższa kreska ma tyle pełnych pikseli, ile się mieści (najwyżej `module`),
 * bo kreski ułamkowej szerokości rozmywają się na krawędziach i czytnik je gubi. Dopiero gdy nawet 1 px się nie
 * mieści, kod zwęża się do szerokości miejsca.
 */
export function Code128({ value, height = 96, module = 3 }: { value: string; height?: number; module?: number }) {
  const { bars, total } = code128Bars(value)
  const box = useRef<HTMLDivElement>(null)
  const [space, setSpace] = useState<number | null>(null)

  useLayoutEffect(() => {
    const element = box.current
    if (!element) return
    const observer = new ResizeObserver(() => setSpace(element.clientWidth))
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  const fitting = space == null ? 1 : Math.min(module, Math.floor(space / total))
  const width = fitting >= 1 ? total * fitting : (space ?? total)

  return (
    <div ref={box} className="w-full min-w-0">
      <svg
        role="img"
        aria-label={`Kod kreskowy: ${value.trim()}`}
        viewBox={`0 0 ${total} ${height}`}
        width={width}
        height={height}
        preserveAspectRatio="none"
        className="block rounded-md bg-white"
        shapeRendering="crispEdges"
      >
        {bars.map((bar) => (
          <rect key={bar.x} x={bar.x} y={0} width={bar.width} height={height} fill="#000" />
        ))}
      </svg>
    </div>
  )
}
