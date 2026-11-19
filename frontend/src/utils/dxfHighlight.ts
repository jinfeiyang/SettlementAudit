/**
 * 把 DXF 里对应材料名称的文字改成红色（组码 62=1）。
 *
 * 图纸预览要的是“文字本身变红”，不是叠红框：在字节层面补一组颜色码，
 * 字体、字高、位置全部保持原样。只按 0x0A 切行——UTF-8 汉字里含
 * 0x85、0x0B 这类字节，按 Unicode 换行切会把汉字拆开，永远匹配不到。
 *
 * 材料表里名称和规格是同一行里的两个文字实体（如「光纤分纤箱」+「48芯」），
 * 所以先按行归组，再要求该行同时出现规格，避免 48 芯和 24 芯一起标红。
 */

const LF = 0x0a
const CR = 0x0d
const SPACE = 0x20
const TAB = 0x09
/** 同一行文字的 y 允许的误差：表格行距约 4，取 1 不会串行 */
const ROW_TOLERANCE = 1

let utf8Fatal: TextDecoder | null = null
let gbk: TextDecoder | null = null

interface DxfText {
  text: string
  y: number
  /** 文字实体所在的块/模型空间：不同块里 y 相同的行不能算同一行 */
  container: number
  valueIndex: number
  colorIndex: number
  end: number
}

export function highlightDxfText(
  buffer: ArrayBuffer,
  needle: string,
  spec = '',
  fallback = '',
): ArrayBuffer {
  const target = normalize(needle)
  const specText = normalize(spec)
  const fallbackText = normalize(fallback)
  const bytes = new Uint8Array(buffer)
  if (!target || bytes.length < 4) {
    return buffer
  }

  const starts: number[] = [0]
  for (let i = 0; i < bytes.length; i += 1) {
    if (bytes[i] === LF) {
      starts.push(i + 1)
    }
  }
  const count = starts.length
  if (count < 2) {
    return buffer
  }

  const endOf = (index: number) => (index + 1 < count ? starts[index + 1] : bytes.length)
  const trim = (index: number) => {
    let begin = starts[index]
    let end = endOf(index)
    while (begin < end && isBlank(bytes[begin])) {
      begin += 1
    }
    while (end > begin && isBlank(bytes[end - 1])) {
      end -= 1
    }
    return [begin, end] as const
  }
  const codeOf = (index: number) => {
    const [begin, end] = trim(index)
    let text = ''
    for (let i = begin; i < end; i += 1) {
      text += String.fromCharCode(bytes[i])
    }
    return text
  }
  const textOf = (index: number) => {
    const [begin, end] = trim(index)
    return decodeValue(bytes.subarray(begin, end))
  }
  const numberAt = (entityEnd: number, begin: number, code: string) => {
    for (let k = begin; k + 1 < entityEnd; k += 2) {
      if (codeOf(k) === code) {
        return Number.parseFloat(textOf(k + 1))
      }
    }
    return 0
  }

  const entities: DxfText[] = []
  let container = 0
  let i = 0
  while (i + 1 < count) {
    if (codeOf(i) !== '0') {
      i += 2
      continue
    }
    const type = textOf(i + 1).toUpperCase()
    let end = i + 2
    while (end + 1 < count && codeOf(end) !== '0') {
      end += 2
    }
    if (type === 'SECTION' || type === 'ENDSEC' || type === 'BLOCK' || type === 'ENDBLK') {
      container += 1
    } else if (type === 'TEXT' || type === 'MTEXT') {
      let valueIndex = -1
      let colorIndex = -1
      let chunks = ''
      for (let k = i + 2; k + 1 < end; k += 2) {
        const code = codeOf(k)
        if (code === '3' && type === 'MTEXT') {
          chunks += textOf(k + 1)
        } else if (code === '1' && valueIndex < 0) {
          valueIndex = k + 1
        } else if (code === '62') {
          colorIndex = k + 1
        }
      }
      if (valueIndex >= 0) {
        entities.push({
          text: normalize(cleanMText(chunks + textOf(valueIndex))),
          y: numberAt(end, i + 2, '20'),
          container,
          valueIndex,
          colorIndex,
          end,
        })
      }
    }
    i = end
  }

  // 同一容器内按 y 归行：表格一行的名称/规格是同一基线上的两个文字
  const rows = new Map<number, Array<{ y: number; items: DxfText[] }>>()
  for (const item of entities) {
    let list = rows.get(item.container)
    if (!list) {
      list = []
      rows.set(item.container, list)
    }
    let row = list.find((candidate) => Math.abs(candidate.y - item.y) <= ROW_TOLERANCE)
    if (!row) {
      row = { y: item.y, items: [] }
      list.push(row)
    }
    row.items.push(item)
  }

  // 按优先级找要标红的行，先要求"整格文字与目标相同"，再放宽到包含。
  // 整格优先是关键：点材料「光分纤箱」时只标材料表那一格，
  // 不会把工程量表里「安装光分纤箱、光分路箱墙壁式」这整句也标红；
  // 反过来点安装行时也只标同名的工程量行，不会碰材料格。
  const candidates: Array<[string, string, MatchMode]> = fallbackText
    ? [
        [target, '', 'exact'],
        [target, '', 'contains'],
        [fallbackText, specText, 'exact'],
        [fallbackText, specText, 'contains'],
        [fallbackText, '', 'contains'],
        [fallbackText, '', 'partial'],
      ]
    : [
        // 只有材料名称时先按规格锁定，避免 48芯 / 24芯 一起标红
        [target, specText, 'exact'],
        [target, '', 'exact'],
        [target, specText, 'contains'],
        [target, '', 'contains'],
        [target, '', 'partial'],
      ]
  let marked: DxfText[] = []
  for (const [name, rowSpec, mode] of candidates) {
    marked = collectMarked(rows, name, rowSpec, mode)
    if (marked.length) {
      break
    }
  }
  if (!marked.length) {
    return buffer
  }

  let crlf = false
  for (let index = 0; index < bytes.length; index += 1) {
    if (bytes[index] === LF) {
      crlf = index > 0 && bytes[index - 1] === CR
      break
    }
  }
  const encoder = new TextEncoder()
  const eol = crlf ? '\r\n' : '\n'
  // 实体本来随层色时补一组 62=1；已有颜色码则直接换值
  const colorPair = encoder.encode(` 62${eol}  1${eol}`)
  const colorValue = encoder.encode(`  1${eol}`)

  const replacements = new Map<number, Uint8Array>()
  const insertions = new Map<number, Uint8Array>()
  for (const item of marked) {
    if (item.colorIndex >= 0) {
      replacements.set(item.colorIndex, colorValue)
    } else {
      insertions.set(item.end, colorPair)
    }
  }

  let size = 0
  for (let index = 0; index < count; index += 1) {
    size += insertions.get(index)?.length ?? 0
    size += replacements.get(index)?.length ?? endOf(index) - starts[index]
  }
  const output = new Uint8Array(size)
  let offset = 0
  for (let index = 0; index < count; index += 1) {
    const inserted = insertions.get(index)
    if (inserted) {
      output.set(inserted, offset)
      offset += inserted.length
    }
    const replaced = replacements.get(index)
    if (replaced) {
      output.set(replaced, offset)
      offset += replaced.length
    } else {
      const begin = starts[index]
      const end = endOf(index)
      output.set(bytes.subarray(begin, end), offset)
      offset += end - begin
    }
  }
  return output.buffer
}

function isBlank(byte: number) {
  return byte === SPACE || byte === TAB || byte === CR || byte === LF
}

function decodeValue(bytes: Uint8Array) {
  utf8Fatal ??= new TextDecoder('utf-8', { fatal: true })
  try {
    return utf8Fatal.decode(bytes)
  } catch {
    // 图纸可能是 GBK 代码页，按中文常见编码兜底
    gbk ??= new TextDecoder('gbk')
    return gbk.decode(bytes)
  }
}

function cleanMText(value: string) {
  return value.replace(/\\[A-Za-z][^;\\]*;|[{}]/g, '')
}

function normalize(value: string) {
  return value.replace(/\s+/g, '')
}

/** exact=整格相同，contains=文字包含目标，partial=文字是目标的一部分（材料表简写） */
type MatchMode = 'exact' | 'contains' | 'partial'

function collectMarked(
  rows: Map<number, Array<{ y: number; items: DxfText[] }>>,
  target: string,
  specText: string,
  mode: MatchMode,
): DxfText[] {
  const marked: DxfText[] = []
  if (!target) {
    return marked
  }
  for (const list of rows.values()) {
    for (const row of list) {
      const names = row.items.filter((item) => {
        if (!item.text) {
          return false
        }
        if (mode === 'exact') {
          return item.text === target
        }
        if (item.text.includes(target)) {
          return true
        }
        return mode === 'partial' && item.text.length >= 3 && target.includes(item.text)
      })
      if (!names.length) {
        continue
      }
      // 有规格时要求同一行出现该规格：24 芯那行不会被算进来
      if (specText && !row.items.some((item) => item.text.includes(specText))) {
        continue
      }
      marked.push(...names)
    }
  }
  return marked
}
