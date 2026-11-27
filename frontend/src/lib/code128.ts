/**
 * Kod kreskowy Code 128 (zestaw B: cyfry, litery, znaki ASCII 32-126, znaki sterujące przez SHIFT) rysowany w SVG.
 * Zawsze czarny na białym tle, także w ciemnym motywie, bo czytnik kolektora potrzebuje kontrastu.
 */

// Szerokości kresek i przerw (6 elementów, bez znaku stopu) dla wartości 0-106.
const PATTERNS = [
  '212222',
  '222122',
  '222221',
  '121223',
  '121322',
  '131222',
  '122213',
  '122312',
  '132212',
  '221213',
  '221312',
  '231212',
  '112232',
  '122132',
  '122231',
  '113222',
  '123122',
  '123221',
  '223211',
  '221132',
  '221231',
  '213212',
  '223112',
  '312131',
  '311222',
  '321122',
  '321221',
  '312212',
  '322112',
  '322211',
  '212123',
  '212321',
  '232121',
  '111323',
  '131123',
  '131321',
  '112313',
  '132113',
  '132311',
  '211313',
  '231113',
  '231311',
  '112133',
  '112331',
  '132131',
  '113123',
  '113321',
  '133121',
  '313121',
  '211331',
  '231131',
  '213113',
  '213311',
  '213131',
  '311123',
  '311321',
  '331121',
  '312113',
  '312311',
  '332111',
  '314111',
  '221411',
  '431111',
  '111224',
  '111422',
  '121124',
  '121421',
  '141122',
  '141221',
  '112214',
  '112412',
  '122114',
  '122411',
  '142112',
  '142211',
  '241211',
  '221114',
  '413111',
  '241112',
  '134111',
  '111242',
  '121142',
  '121241',
  '114212',
  '124112',
  '124211',
  '411212',
  '421112',
  '421211',
  '212141',
  '214121',
  '412121',
  '111143',
  '111341',
  '131141',
  '114113',
  '114311',
  '411113',
  '411311',
  '113141',
  '114131',
  '311141',
  '411131',
  '211412',
  '211214',
  '211232',
]
const START_B = 104
const SHIFT = 98
const STOP = '2331112'
// Pusty margines po obu stronach w szerokościach najwęższej kreski. Czytnik laserowy potrzebuje go dużo,
// szczególnie przy kodzie z ekranu.
const QUIET = 20

function modules(text: string): number[] {
  const values = [START_B]
  for (const char of text) {
    const code = char.charCodeAt(0)
    if (code < 32) {
      // Znak sterujący (np. Enter) z zestawu A przez SHIFT, czytnik wyśle go jak klawisz.
      values.push(SHIFT, code + 64)
      continue
    }
    if (code > 126) throw new Error(`Znak spoza Code 128B: ${char}`)
    values.push(code - 32)
  }
  const checksum = values.reduce((sum, value, index) => sum + value * Math.max(index, 1), 0) % 103
  values.push(checksum)
  const widths = values.map((value) => PATTERNS[value]).join('') + STOP
  return [...widths].map(Number)
}

/** Kreski kodu w jednostkach najwęższej kreski, z marginesami; `total` = szerokość całości. */
export function code128Bars(value: string): { bars: { x: number; width: number }[]; total: number } {
  const widths = modules(value)
  const total = widths.reduce((sum, width) => sum + width, 0) + QUIET * 2
  const bars: { x: number; width: number }[] = []
  let x = QUIET
  widths.forEach((width, index) => {
    if (index % 2 === 0) bars.push({ x, width })
    x += width
  })
  return { bars, total }
}

/** Kod jako SVG do wydruku: szerokość najwęższej kreski i wysokość w milimetrach. */
export function code128Svg(value: string, moduleMm: number, heightMm: number): string {
  const { bars, total } = code128Bars(value)
  const rects = bars
    .map((bar) => `<rect x="${bar.x}" y="0" width="${bar.width}" height="${heightMm}" fill="#000"/>`)
    .join('')
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${total} ${heightMm}" width="${total * moduleMm}mm" height="${heightMm}mm" preserveAspectRatio="none" shape-rendering="crispEdges">${rects}</svg>`
}

const escape = (text: string) =>
  text.replace(/[&<>"]/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[char] ?? char)

/** Drukuje kartkę A4 z kodem (kreska 0,5 mm), podpisem i krokami pod spodem. */
export function printCode128(value: string, title: string, label: string, steps: string[]) {
  const html = `<!doctype html><html><head><meta charset="utf-8"><title>${escape(title)}</title><style>
@page{size:A4;margin:15mm}body{font-family:Arial,sans-serif;color:#000;margin:0}h1{font-size:20px;margin:0 0 6mm}
.code{margin:10mm 0 3mm}.label{font:16px monospace;margin-bottom:10mm}ol{font-size:14px;line-height:1.6;padding-left:6mm}
</style></head><body><h1>${escape(title)}</h1><div class="code">${code128Svg(value, 0.5, 30)}</div>
<div class="label">${escape(label)}</div><ol>${steps.map((step) => `<li>${escape(step)}</li>`).join('')}</ol></body></html>`
  const frame = document.createElement('iframe')
  frame.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0;visibility:hidden'
  frame.srcdoc = html
  frame.onload = () => {
    frame.contentWindow?.focus()
    frame.contentWindow?.print()
    setTimeout(() => frame.remove(), 60_000)
  }
  document.body.append(frame)
}
