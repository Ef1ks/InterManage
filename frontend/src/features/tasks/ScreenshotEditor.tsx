import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import {
  CropIcon,
  HighlighterIcon,
  MoveUpRightIcon,
  PencilIcon,
  Redo2Icon,
  SquareIcon,
  Trash2Icon,
  TypeIcon,
  Undo2Icon,
} from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { cn } from '@/lib/utils'

type Point = { x: number; y: number }
type Tool = 'pen' | 'marker' | 'arrow' | 'rect' | 'text' | 'crop'
type Shape =
  | { kind: 'path'; marker: boolean; color: string; width: number; points: Point[] }
  | { kind: 'arrow' | 'rect'; color: string; width: number; from: Point; to: Point }
  | { kind: 'text'; color: string; size: number; at: Point; text: string }
type Crop = { x: number; y: number; w: number; h: number }
type Drawing = { shapes: Shape[]; crop: Crop | null }

/** Najszerszy zapisany zrzut: ekran 4K przy powiększeniu daje 3840 px, a tyle nie trzeba, żeby wszystko przeczytać. */
const MAX_WIDTH = 2400

const TOOLS: { value: Tool; label: string; icon: typeof PencilIcon; key: string }[] = [
  { value: 'arrow', label: 'Strzałka', icon: MoveUpRightIcon, key: 'a' },
  { value: 'rect', label: 'Ramka', icon: SquareIcon, key: 'r' },
  { value: 'pen', label: 'Pióro', icon: PencilIcon, key: 'p' },
  { value: 'marker', label: 'Zakreślacz', icon: HighlighterIcon, key: 'z' },
  { value: 'text', label: 'Napis', icon: TypeIcon, key: 't' },
  { value: 'crop', label: 'Przytnij', icon: CropIcon, key: 'c' },
]

const COLORS = [
  { value: '#dc2626', label: 'Czerwony' },
  { value: '#f59e0b', label: 'Żółty' },
  { value: '#2563eb', label: 'Niebieski' },
  { value: '#16a34a', label: 'Zielony' },
  { value: '#111827', label: 'Czarny' },
  { value: '#ffffff', label: 'Biały' },
]

const SIZES = [
  { value: 1, label: 'Cienko' },
  { value: 2, label: 'Średnio' },
  { value: 3.5, label: 'Grubo' },
]

/**
 * Popisanie zrzutu przed dołączeniem do zadania: strzałki, ramki, pióro, zakreślacz, napisy i przycięcie. Rysunki są
 * listą kształtów nad obrazem (cofanie i ponawianie, Ctrl+Z / Ctrl+Y), a zapis spłaszcza je w jeden obraz WEBP.
 */
export function ScreenshotEditor({
  source,
  onCancel,
  onSave,
  saveLabel = 'Dołącz',
}: {
  /** Obraz do popisania; `null` = okno zamknięte. */
  source: Blob | null
  onCancel: () => void
  onSave: (image: Blob) => Promise<void> | void
  saveLabel?: string
}) {
  return (
    <Dialog
      open={source !== null}
      disablePointerDismissal
      onOpenChange={(open) => {
        if (!open) onCancel()
      }}
    >
      <DialogContent className="gap-3 p-4 sm:max-w-[min(96vw,110rem)]" showCloseButton={false}>
        {source && <Editor source={source} onCancel={onCancel} onSave={onSave} saveLabel={saveLabel} />}
      </DialogContent>
    </Dialog>
  )
}

function Editor({
  source,
  onCancel,
  onSave,
  saveLabel,
}: {
  source: Blob
  onCancel: () => void
  onSave: (image: Blob) => Promise<void> | void
  saveLabel: string
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const [base, setBase] = useState<HTMLCanvasElement | null>(null)
  const [loadError, setLoadError] = useState(false)
  const [tool, setTool] = useState<Tool>('arrow')
  const [color, setColor] = useState(COLORS[0].value)
  const [size, setSize] = useState(2)
  const [drawing, setDrawing] = useState<Drawing>({ shapes: [], crop: null })
  const [past, setPast] = useState<Drawing[]>([])
  const [future, setFuture] = useState<Drawing[]>([])
  const [draft, setDraft] = useState<Shape | null>(null)
  const [cropDraft, setCropDraft] = useState<{ from: Point; to: Point } | null>(null)
  const [text, setText] = useState<{ at: Point; value: string; scale: number } | null>(null)
  const [saving, setSaving] = useState(false)
  const pointerRef = useRef<number | null>(null)
  const textRef = useRef<HTMLInputElement>(null)
  const textOpen = text !== null

  // Fokus dopiero po kliknięciu: domyślna akcja wciśnięcia myszy przenosi fokus i od razu zamykała pole napisu.
  useEffect(() => {
    if (!textOpen) return
    const frame = requestAnimationFrame(() => textRef.current?.focus())
    return () => cancelAnimationFrame(frame)
  }, [textOpen])

  // Obraz zmniejszony do MAX_WIDTH: kształty mają współrzędne w jego pikselach, więc zapis jest 1:1.
  useEffect(() => {
    let cancelled = false
    createImageBitmap(source)
      .then((bitmap) => {
        if (cancelled) return
        const scale = Math.min(1, MAX_WIDTH / bitmap.width)
        const canvas = document.createElement('canvas')
        canvas.width = Math.round(bitmap.width * scale)
        canvas.height = Math.round(bitmap.height * scale)
        const context = canvas.getContext('2d')
        context?.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
        bitmap.close()
        setBase(canvas)
      })
      .catch(() => !cancelled && setLoadError(true))
    return () => {
      cancelled = true
    }
  }, [source])

  const unit = base ? Math.max(2, base.width / 600) : 2

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas || !base) return
    canvas.width = base.width
    canvas.height = base.height
  }, [base])

  useEffect(() => {
    const canvas = canvasRef.current
    const context = canvas?.getContext('2d')
    if (!canvas || !context || !base) return
    render(context, base, draft ? [...drawing.shapes, draft] : drawing.shapes)
    const crop = cropDraft ? normalize(cropDraft.from, cropDraft.to) : drawing.crop
    if (crop) shadeOutside(context, crop, unit)
  }, [base, drawing, draft, cropDraft, unit])

  const commit = useCallback(
    (next: Drawing) => {
      setPast([...past, drawing])
      setFuture([])
      setDrawing(next)
    },
    [past, drawing],
  )

  const undo = useCallback(() => {
    if (past.length === 0) return
    setFuture([drawing, ...future])
    setDrawing(past[past.length - 1])
    setPast(past.slice(0, -1))
  }, [past, future, drawing])

  const redo = useCallback(() => {
    if (future.length === 0) return
    setPast([...past, drawing])
    setDrawing(future[0])
    setFuture(future.slice(1))
  }, [past, future, drawing])

  const commitText = useCallback(() => {
    if (text && text.value.trim() !== '') {
      commit({
        ...drawing,
        shapes: [
          ...drawing.shapes,
          { kind: 'text', color, size: unit * (6 + size * 3), at: text.at, text: text.value.trim() },
        ],
      })
    }
    setText(null)
  }, [text, commit, drawing, color, size, unit])

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (text) return
      const target = event.target as HTMLElement | null
      if (target?.closest('input, textarea')) return
      const key = event.key.toLowerCase()
      if ((event.ctrlKey || event.metaKey) && key === 'z') {
        event.preventDefault()
        if (event.shiftKey) redo()
        else undo()
      } else if ((event.ctrlKey || event.metaKey) && key === 'y') {
        event.preventDefault()
        redo()
      } else if (!event.ctrlKey && !event.metaKey && !event.altKey) {
        const found = TOOLS.find((option) => option.key === key)
        if (found) setTool(found.value)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [undo, redo, text])

  const toImage = (event: ReactPointerEvent<HTMLCanvasElement>): Point => {
    const canvas = event.currentTarget
    const rect = canvas.getBoundingClientRect()
    return {
      x: ((event.clientX - rect.left) * canvas.width) / rect.width,
      y: ((event.clientY - rect.top) * canvas.height) / rect.height,
    }
  }

  const onPointerDown = (event: ReactPointerEvent<HTMLCanvasElement>) => {
    if (event.button !== 0 || !base) return
    const at = toImage(event)
    if (tool === 'text') {
      if (text) commitText()
      // Skala widocznego obrazu: pole napisu stoi nad nim w miejscu kliknięcia.
      setText({ at, value: '', scale: event.currentTarget.getBoundingClientRect().width / base.width })
      return
    }
    event.currentTarget.setPointerCapture(event.pointerId)
    pointerRef.current = event.pointerId
    const width = unit * size
    if (tool === 'crop') setCropDraft({ from: at, to: at })
    else if (tool === 'pen' || tool === 'marker')
      setDraft({ kind: 'path', marker: tool === 'marker', color, width, points: [at] })
    else setDraft({ kind: tool, color, width, from: at, to: at })
  }

  const onPointerMove = (event: ReactPointerEvent<HTMLCanvasElement>) => {
    if (pointerRef.current !== event.pointerId) return
    const at = toImage(event)
    if (cropDraft) {
      setCropDraft({ from: cropDraft.from, to: at })
      return
    }
    setDraft((current) => {
      if (!current || current.kind === 'text') return current
      if (current.kind === 'path') return { ...current, points: [...current.points, at] }
      return { ...current, to: at }
    })
  }

  const onPointerUp = (event: ReactPointerEvent<HTMLCanvasElement>) => {
    if (pointerRef.current !== event.pointerId) return
    pointerRef.current = null
    if (cropDraft) {
      const crop = normalize(cropDraft.from, cropDraft.to)
      setCropDraft(null)
      // Samo kliknięcie zdejmuje przycięcie.
      commit({ ...drawing, crop: crop.w > unit * 10 && crop.h > unit * 10 ? crop : null })
      return
    }
    if (!draft) return
    const tiny =
      draft.kind !== 'path' &&
      draft.kind !== 'text' &&
      Math.hypot(draft.to.x - draft.from.x, draft.to.y - draft.from.y) < unit * 3
    if (!tiny) commit({ ...drawing, shapes: [...drawing.shapes, draft] })
    setDraft(null)
  }

  const save = async () => {
    if (!base) return
    setSaving(true)
    try {
      await onSave(await exportImage(base, drawing))
    } finally {
      setSaving(false)
    }
  }

  const textSize = unit * (6 + size * 3)

  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-lg">Popisz zrzut</DialogTitle>
        <DialogDescription>
          Zaznacz, o co chodzi: strzałka, ramka, napis. Przytnij, żeby zostało samo to, co ważne.
        </DialogDescription>
      </DialogHeader>

      <div className="flex flex-wrap items-center gap-x-4 gap-y-2" role="toolbar" aria-label="Narzędzia rysowania">
        <div className="flex items-center gap-1 rounded-lg bg-muted p-1">
          {TOOLS.map((option) => (
            <Button
              key={option.value}
              type="button"
              size="sm"
              variant={tool === option.value ? 'default' : 'ghost'}
              aria-pressed={tool === option.value}
              title={`${option.label} (${option.key.toUpperCase()})`}
              onClick={() => setTool(option.value)}
            >
              <option.icon />
              <span className="hidden lg:inline">{option.label}</span>
            </Button>
          ))}
        </div>
        <div className="flex items-center gap-1.5" role="radiogroup" aria-label="Kolor">
          {COLORS.map((option) => (
            <button
              key={option.value}
              type="button"
              role="radio"
              aria-checked={color === option.value}
              aria-label={option.label}
              title={option.label}
              onClick={() => setColor(option.value)}
              className={cn(
                'size-7 rounded-full border border-black/20 ring-offset-2 ring-offset-background transition-shadow dark:border-white/30',
                color === option.value && 'ring-2 ring-ring',
              )}
              style={{ backgroundColor: option.value }}
            />
          ))}
        </div>
        <div className="flex items-center gap-1 rounded-lg bg-muted p-1" role="radiogroup" aria-label="Grubość">
          {SIZES.map((option) => (
            <button
              key={option.value}
              type="button"
              role="radio"
              aria-checked={size === option.value}
              title={option.label}
              aria-label={option.label}
              onClick={() => setSize(option.value)}
              className={cn(
                'grid size-8 place-items-center rounded-md',
                size === option.value ? 'bg-background shadow-sm dark:bg-input/30' : 'hover:bg-background/60',
              )}
            >
              <span
                className="block rounded-full bg-foreground"
                style={{ width: 4 + option.value * 4, height: 4 + option.value * 4 }}
              />
            </button>
          ))}
        </div>
        <div className="flex items-center gap-1">
          <Button
            type="button"
            size="icon-sm"
            variant="ghost"
            onClick={undo}
            disabled={past.length === 0}
            aria-label="Cofnij (Ctrl+Z)"
            title="Cofnij (Ctrl+Z)"
          >
            <Undo2Icon />
          </Button>
          <Button
            type="button"
            size="icon-sm"
            variant="ghost"
            onClick={redo}
            disabled={future.length === 0}
            aria-label="Ponów (Ctrl+Y)"
            title="Ponów (Ctrl+Y)"
          >
            <Redo2Icon />
          </Button>
          <Button
            type="button"
            size="sm"
            variant="ghost"
            onClick={() => commit({ shapes: [], crop: null })}
            disabled={drawing.shapes.length === 0 && drawing.crop === null}
          >
            <Trash2Icon />
            Wyczyść
          </Button>
        </div>
      </div>

      <div className="grid min-h-40 place-items-center overflow-hidden rounded-lg bg-muted/60 p-2">
        {loadError ? (
          <p className="text-sm text-destructive">Nie udało się odczytać obrazu.</p>
        ) : (
          <div className="relative inline-block">
            <canvas
              ref={canvasRef}
              onPointerDown={onPointerDown}
              onMouseDown={(event) => event.preventDefault()}
              onPointerMove={onPointerMove}
              onPointerUp={onPointerUp}
              onPointerCancel={onPointerUp}
              className={cn(
                'block max-h-[calc(100svh-17rem)] max-w-full touch-none rounded shadow-sm',
                tool === 'text' ? 'cursor-text' : 'cursor-crosshair',
                !base && 'invisible',
              )}
              aria-label="Zrzut do popisania"
            />
            {text && (
              <input
                ref={textRef}
                value={text.value}
                onChange={(event) => setText({ ...text, value: event.target.value })}
                onBlur={commitText}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') {
                    event.preventDefault()
                    commitText()
                  } else if (event.key === 'Escape') {
                    event.preventDefault()
                    event.stopPropagation()
                    setText(null)
                  }
                }}
                placeholder="Napis…"
                aria-label="Napis na zrzucie"
                className="absolute min-w-40 rounded border border-dashed border-foreground/40 bg-transparent px-0.5 font-bold outline-none"
                style={{
                  left: text.at.x * text.scale,
                  top: text.at.y * text.scale,
                  fontSize: Math.max(12, textSize * text.scale),
                  lineHeight: 1.1,
                  color,
                  textShadow: outlineFor(color),
                }}
              />
            )}
          </div>
        )}
      </div>

      <div className="flex flex-wrap items-center justify-end gap-2">
        <Button type="button" variant="outline" size="lg" onClick={onCancel} disabled={saving}>
          Anuluj
        </Button>
        <Button type="button" size="lg" onClick={() => void save()} loading={saving} disabled={!base}>
          {saveLabel}
        </Button>
      </div>
    </>
  )
}

function normalize(from: Point, to: Point): Crop {
  return {
    x: Math.min(from.x, to.x),
    y: Math.min(from.y, to.y),
    w: Math.abs(to.x - from.x),
    h: Math.abs(to.y - from.y),
  }
}

/** Kontur napisu w kolorze odwrotnym, żeby był czytelny na każdym tle. */
function contrastOf(color: string) {
  return color === '#ffffff' || color === '#f59e0b' ? '#111827' : '#ffffff'
}

function outlineFor(color: string) {
  const outline = contrastOf(color)
  return `-1px -1px 0 ${outline}, 1px -1px 0 ${outline}, -1px 1px 0 ${outline}, 1px 1px 0 ${outline}`
}

function render(context: CanvasRenderingContext2D, base: HTMLCanvasElement, shapes: Shape[]) {
  context.clearRect(0, 0, base.width, base.height)
  context.drawImage(base, 0, 0)
  for (const shape of shapes) drawShape(context, shape)
}

function drawShape(context: CanvasRenderingContext2D, shape: Shape) {
  context.save()
  context.lineCap = 'round'
  context.lineJoin = 'round'
  if (shape.kind === 'path') {
    context.strokeStyle = shape.color
    context.lineWidth = shape.marker ? shape.width * 5 : shape.width
    if (shape.marker) {
      context.globalAlpha = 0.35
      context.lineCap = 'square'
    }
    context.beginPath()
    shape.points.forEach((point, index) =>
      index === 0 ? context.moveTo(point.x, point.y) : context.lineTo(point.x, point.y),
    )
    if (shape.points.length === 1) context.lineTo(shape.points[0].x + 0.1, shape.points[0].y)
    context.stroke()
  } else if (shape.kind === 'rect') {
    const box = normalize(shape.from, shape.to)
    context.strokeStyle = shape.color
    context.lineWidth = shape.width
    context.strokeRect(box.x, box.y, box.w, box.h)
  } else if (shape.kind === 'arrow') {
    drawArrow(context, shape.from, shape.to, shape.color, shape.width)
  } else if (shape.kind === 'text') {
    context.font = `700 ${shape.size}px system-ui, "Segoe UI", sans-serif`
    context.textBaseline = 'top'
    context.lineWidth = Math.max(2, shape.size / 7)
    context.strokeStyle = contrastOf(shape.color)
    context.fillStyle = shape.color
    shape.text.split('\n').forEach((line, index) => {
      const y = shape.at.y + index * shape.size * 1.15
      context.strokeText(line, shape.at.x, y)
      context.fillText(line, shape.at.x, y)
    })
  }
  context.restore()
}

function drawArrow(context: CanvasRenderingContext2D, from: Point, to: Point, color: string, width: number) {
  const angle = Math.atan2(to.y - from.y, to.x - from.x)
  const length = Math.hypot(to.x - from.x, to.y - from.y)
  const head = Math.min(length * 0.6, Math.max(width * 4, 14))
  // Biała obwódka pod strzałką: na ciemnym i kolorowym tle widać ją tak samo dobrze.
  for (const [stroke, extra] of [
    [contrastOf(color), width * 0.8],
    [color, 0],
  ] as const) {
    context.strokeStyle = stroke
    context.fillStyle = stroke
    context.lineWidth = width + extra
    context.beginPath()
    context.moveTo(from.x, from.y)
    context.lineTo(to.x - Math.cos(angle) * head * 0.6, to.y - Math.sin(angle) * head * 0.6)
    context.stroke()
    context.beginPath()
    context.moveTo(to.x, to.y)
    context.lineTo(to.x - head * Math.cos(angle - Math.PI / 7), to.y - head * Math.sin(angle - Math.PI / 7))
    context.lineTo(to.x - head * Math.cos(angle + Math.PI / 7), to.y - head * Math.sin(angle + Math.PI / 7))
    context.closePath()
    if (extra > 0) {
      context.lineWidth = extra
      context.stroke()
    }
    context.fill()
  }
}

/** Przyciemnienie poza przycięciem (tylko na ekranie, zapis wycina sam obszar). */
function shadeOutside(context: CanvasRenderingContext2D, crop: Crop, unit: number) {
  const { width, height } = context.canvas
  context.save()
  context.fillStyle = 'rgba(0, 0, 0, 0.5)'
  context.beginPath()
  context.rect(0, 0, width, height)
  context.rect(crop.x, crop.y, crop.w, crop.h)
  context.fill('evenodd')
  context.setLineDash([unit * 3, unit * 2])
  context.strokeStyle = '#ffffff'
  context.lineWidth = Math.max(1, unit / 2)
  context.strokeRect(crop.x, crop.y, crop.w, crop.h)
  context.restore()
}

async function exportImage(base: HTMLCanvasElement, drawing: Drawing): Promise<Blob> {
  const full = document.createElement('canvas')
  full.width = base.width
  full.height = base.height
  const context = full.getContext('2d')
  if (!context) throw new Error('Przeglądarka nie pozwala zapisać obrazu')
  render(context, base, drawing.shapes)
  let output = full
  const crop = drawing.crop
  if (crop) {
    output = document.createElement('canvas')
    output.width = Math.round(crop.w)
    output.height = Math.round(crop.h)
    output.getContext('2d')?.drawImage(full, crop.x, crop.y, crop.w, crop.h, 0, 0, crop.w, crop.h)
  }
  return new Promise<Blob>((resolve, reject) =>
    output.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error('Nie udało się zapisać obrazu'))),
      'image/webp',
      0.9,
    ),
  )
}
