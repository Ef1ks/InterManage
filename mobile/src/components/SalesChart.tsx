import { useMemo, useState } from 'react'
import { Pressable, Text, View, type LayoutChangeEvent } from 'react-native'
import Svg, { Line, Rect } from 'react-native-svg'

import { money, quantity, shortDate } from '@/lib/format'
import { useTheme } from '@/lib/theme'
import type { SalesHistory } from '@/lib/types'

type Bar = { from: string; to: string; quantity: number; gross: number; loss: number }

/** Dni, a przy długim okresie tygodnie liczone wstecz od ostatniego dnia (jak wykres w aplikacji). */
function bars(history: SalesHistory, weekly: boolean): Bar[] {
  const days = history.days.map((day) => ({
    from: day.date,
    to: day.date,
    quantity: day.figures.quantity,
    gross: day.figures.saleValueGross,
    loss: day.lossValue,
  }))
  if (!weekly) return days
  const result: Bar[] = []
  for (let end = days.length; end > 0; end -= 7) {
    const chunk = days.slice(Math.max(0, end - 7), end)
    result.unshift({
      from: chunk[0].from,
      to: chunk[chunk.length - 1].to,
      quantity: chunk.reduce((sum, day) => sum + day.quantity, 0),
      gross: chunk.reduce((sum, day) => sum + day.gross, 0),
      loss: chunk.reduce((sum, day) => sum + day.loss, 0),
    })
  }
  return result
}

export function SalesChart({ history, weekly }: { history: SalesHistory; weekly: boolean }) {
  const t = useTheme()
  const [width, setWidth] = useState(0)
  const [selected, setSelected] = useState<number | null>(null)
  const data = useMemo(() => bars(history, weekly), [history, weekly])
  const height = 150
  const max = Math.max(1, ...data.map((bar) => bar.quantity))
  const average = data.length ? data.reduce((sum, bar) => sum + bar.quantity, 0) / data.length : 0
  const slot = data.length ? width / data.length : 0
  const gap = slot > 6 ? Math.min(3, slot * 0.25) : 0
  const active = selected != null ? data[selected] : null

  const touch = (x: number) => {
    if (!slot) return
    setSelected(Math.max(0, Math.min(data.length - 1, Math.floor(x / slot))))
  }

  return (
    <View style={{ gap: 8 }}>
      <View style={{ minHeight: 36 }}>
        {active ? (
          <>
            <Text style={{ color: t.text, fontWeight: '600', fontVariant: ['tabular-nums'] }}>
              {active.from === active.to
                ? shortDate(active.from)
                : `${shortDate(active.from)} – ${shortDate(active.to)}`}
              {': '}
              {quantity(active.quantity)} szt. · {money(active.gross)}
            </Text>
            {active.loss !== 0 && <Text style={{ color: t.danger, fontSize: 12 }}>Straty {money(active.loss)}</Text>}
          </>
        ) : (
          <Text style={{ color: t.mutedText, fontSize: 13 }}>
            Dotknij słupka, żeby zobaczyć {weekly ? 'tydzień' : 'dzień'}. Kreska = średnia.
          </Text>
        )}
      </View>
      <Pressable
        onLayout={(event: LayoutChangeEvent) => setWidth(event.nativeEvent.layout.width)}
        onPressIn={(event) => touch(event.nativeEvent.locationX)}
        style={{ height }}
      >
        {width > 0 && (
          <Svg width={width} height={height}>
            {data.map((bar, index) => {
              const barHeight = bar.quantity > 0 ? Math.max(2, (bar.quantity / max) * (height - 4)) : 1
              return (
                <Rect
                  key={bar.from}
                  x={index * slot + gap / 2}
                  y={height - barHeight}
                  width={Math.max(1, slot - gap)}
                  height={barHeight}
                  rx={slot > 8 ? 2 : 0}
                  fill={selected === index ? t.primary : bar.quantity > 0 ? t.bar : t.barMuted}
                />
              )
            })}
            <Line
              x1={0}
              x2={width}
              y1={height - (average / max) * (height - 4)}
              y2={height - (average / max) * (height - 4)}
              stroke={t.danger}
              strokeWidth={1}
              strokeDasharray="4 3"
            />
          </Svg>
        )}
      </Pressable>
      {data.length > 0 && (
        <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
          <Text style={{ color: t.mutedText, fontSize: 11 }}>{shortDate(data[0].from)}</Text>
          <Text style={{ color: t.mutedText, fontSize: 11 }}>{shortDate(data[data.length - 1].to)}</Text>
        </View>
      )}
    </View>
  )
}
