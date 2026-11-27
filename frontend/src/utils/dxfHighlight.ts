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

/** 图纸定位到某一页时传入该图框范围，只标红这一页的文字 */
export interface DxfBounds {
  minX: number
  minY: number
  maxX: number
  maxY: number
}

interface DxfText {
  text: string
  x: number
  y: number
  /** 文字实体所在的块/模型空间：不同块里 y 相同的行不能算同一行 */
  container: number
  valueIndex: number
  colorIndex: number
  end: number
}

type Point = { x: number; y: number }

interface InsertRef {
  parent: number
  target: string
  x: number
  y: number
  sx: number
  sy: number
  rot: number
}

export function highlightDxfText(
  buffer: ArrayBuffer,
  needle: string,
  spec = '',
  fallback = '',
  bounds?: DxfBounds | DxfBounds[] | null,
): ArrayBuffer {
  const target = normalize(needle)
  const specText = normalize(spec)
  const fallbackText = normalize(fallback)
  const bytes = new Uint8Array(buffer)
  if (!target || bytes.length < 4) {
    return buffer
  }
  // 传单页只标那一页；传多页则每张关联页都标。空数组表示不限制（兼容旧调用）。
  const scope = !bounds ? [] : Array.isArray(bounds) ? bounds : [bounds]
  const inScope = (x: number, y: number) =>
    !scope.length ||
    scope.some((b) => x >= b.minX && x <= b.maxX && y >= b.minY && y <= b.maxY)

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
  const valueAt = (entityEnd: number, begin: number, code: string) => {
    for (let k = begin; k + 1 < entityEnd; k += 2) {
      if (codeOf(k) === code) {
        return textOf(k + 1)
      }
    }
    return ''
  }
  const numberOr = (entityEnd: number, begin: number, code: string, fallback: number) => {
    const value = Number.parseFloat(valueAt(entityEnd, begin, code))
    return Number.isFinite(value) ? value : fallback
  }

  /** 逐实体遍历；回调拿到的 container 是该实体所在的块/段编号 */
  const forEachEntity = (visit: (type: string, begin: number, end: number, container: number) => void) => {
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
      visit(type, i, end, container)
      if (type === 'SECTION' || type === 'ENDSEC' || type === 'BLOCK' || type === 'ENDBLK') {
        container += 1
      }
      i = end
    }
  }

  /** 容器号 → 块名：BLOCKS 段里 TEXT 的坐标是块局部坐标，要经 INSERT 换算到模型空间 */
  const blockNameByContainer = new Map<number, string>()
  /** 块名 → 所有 INSERT 引用：同一表格块可能在不同图页各插一份 */
  const insertsByName = new Map<string, InsertRef[]>()
  // DXF 里 BLOCKS 段先于 ENTITIES 段，必须先扫一遍 INSERT 才能换算块内文字坐标
  forEachEntity((type, begin, end, container) => {
    if (type === 'BLOCK') {
      const name = valueAt(end, begin + 2, '2')
      if (name) {
        blockNameByContainer.set(container + 1, name)
      }
    } else if (type === 'INSERT') {
      const target = valueAt(end, begin + 2, '2')
      if (target) {
        const list = insertsByName.get(target) ?? []
        list.push({
          parent: container,
          target,
          x: numberOr(end, begin + 2, '10', 0),
          y: numberOr(end, begin + 2, '20', 0),
          sx: numberOr(end, begin + 2, '41', 1),
          sy: numberOr(end, begin + 2, '42', 1),
          rot: numberOr(end, begin + 2, '50', 0),
        })
        insertsByName.set(target, list)
      }
    }
  })

  const entities: DxfText[] = []
  forEachEntity((type, begin, end, container) => {
    if (type !== 'TEXT' && type !== 'MTEXT') {
      return
    }
    let valueIndex = -1
    let colorIndex = -1
    let chunks = ''
    for (let k = begin + 2; k + 1 < end; k += 2) {
      const code = codeOf(k)
      if (code === '3' && type === 'MTEXT') {
        chunks += textOf(k + 1)
      } else if (code === '1' && valueIndex < 0) {
        valueIndex = k + 1
      } else if (code === '62') {
        colorIndex = k + 1
      }
    }
    if (valueIndex < 0) {
      return
    }
    const y = numberAt(end, begin + 2, '20')
    const x = numberAt(end, begin + 2, '10')
    // 块里的文字是块局部坐标，先按 INSERT（可嵌套）换算到模型空间再按页框过滤，
    // 否则材料表文字永远落在页框外，标红会整片消失。
    const points = modelPointsOf(
      { x, y }, blockNameByContainer.get(container), blockNameByContainer, insertsByName,
    )
    if (!points.some((point) => inScope(point.x, point.y))) {
      return
    }
    const anchor = points[0] ?? { x, y }
    entities.push({
      text: normalize(cleanMText(chunks + textOf(valueIndex))),
      x: anchor.x,
      y: anchor.y,
      container,
      valueIndex,
      colorIndex,
      end,
    })
  })

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

  // 按优先级找要标红的行。整格文字优先于包含：点材料「光分纤箱」时只标材料表那一格，
  // 不会把工程量表里「安装光分纤箱、光分路箱墙壁式」这整句也标红；反过来点安装行时
  // 也只标同名的工程量行，不会碰材料格。
  // 有规格时必须先用「名称 + 同一行规格」锁定：材料表里 GYTS-12B1 / GYTS-24B1 两行
  // 都叫「光缆」，若先按不带规格的「光缆」匹配，两条会一起被标红。只有图上确实
  // 找不到带规格的行，才退到不带规格的兜底匹配。
  const names = fallbackText && fallbackText !== target ? [target, fallbackText] : [target]
  const candidates: Array<[string, string, MatchMode]> = []
  const addCandidates = (mode: MatchMode, spec: string) => {
    for (const name of names) {
      candidates.push([name, spec, mode])
    }
  }
  if (specText) {
    addCandidates('exact', specText)
    addCandidates('contains', specText)
  }
  addCandidates('exact', '')
  addCandidates('contains', '')
  addCandidates('partial', '')
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

/** INSERT 的缩放 → 旋转 → 平移，和 DXF 的块引用变换一致 */
function transformInsert(ref: InsertRef, point: Point): Point {
  const sx = ref.sx || 1
  const sy = ref.sy || 1
  const angle = ref.rot * Math.PI / 180
  const cos = Math.cos(angle)
  const sin = Math.sin(angle)
  const px = point.x * sx
  const py = point.y * sy
  return { x: ref.x + px * cos - py * sin, y: ref.y + px * sin + py * cos }
}

/**
 * 块内文字坐标 → 模型空间坐标。一个块可能被多处 INSERT，也可能嵌在别的块里，
 * 所以沿 INSERT 链递归展开；返回所有落点用于页框过滤。
 */
function modelPointsOf(
  local: Point,
  blockName: string | undefined,
  blockNameByContainer: Map<number, string>,
  insertsByName: Map<string, InsertRef[]>,
): Point[] {
  if (!blockName) {
    return [local]
  }
  const walk = (name: string, points: Point[], seen: Set<string>): Point[] => {
    if (seen.has(name)) {
      return points
    }
    const refs = insertsByName.get(name) ?? []
    if (!refs.length) {
      return points
    }
    const nextSeen = new Set(seen).add(name)
    const out: Point[] = []
    for (const ref of refs) {
      const moved = points.map((point) => transformInsert(ref, point))
      const parent = blockNameByContainer.get(ref.parent)
      out.push(...(parent ? walk(parent, moved, nextSeen) : moved))
      if (out.length > 4000) {
        break
      }
    }
    return out
  }
  return walk(blockName, [local], new Set())
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
