import type { BoqItemVO } from '@/api'
import { unitFactor, unitFamily } from '@/utils/match'

/** 图纸侧按线缆图层汇总出的长度 */
export interface CablePart {
  label: string
  category: string
  meters: number
}

export interface DrawingCable {
  total: number
  parts: CablePart[]
  /**
   * cable：取自线缆图层；route：退到管道/架空等路由图层；
   * annotation：图没画线缆图层，用图上的段长标注（5.7、管453.7）合计；none：没量到
   */
  source: 'cable' | 'route' | 'annotation' | 'none'
}

/** 路由图层关键词：图纸不画光缆线、只画管道路由时的兜底口径 */
const ROUTE_WORDS = ['管道', '管孔', '排管', '架空', '加挂', '吊线', '直埋', '墙壁', '槽道', '桥架', '线槽']

const NOISE_WORDS = ['标注', '文字', '图框', '边框', '图签', '标题', '表格', '设备', '说明', '阴影', '填充']

/** 清单侧与光缆长度对应的一行 */
export interface CableRow {
  id: number
  rowNo?: number
  itemName: string
  /** 规格程式（清单的项目特征描述），如 GYTS-24B1、24 芯 */
  spec?: string
  quantity: number
  unit?: string
  meters: number
  category: string
}

export type CableStatus = 'OK' | 'LESS' | 'NO_DRAWING' | 'NO_BOQ' | 'NO_BASIS'

/** 清单侧的可比口径：光缆施工量 / 单规格甲供材料 / 多芯数甲供材料（口径不同，不判少计） */
export type CableBasis = 'quota' | 'material' | 'multi-spec'

export interface CableCheck {
  status: CableStatus
  /** 清单侧取数口径 */
  basis: CableBasis
  /** 比对口径：全部 / 管道 / 架空… */
  scope: string
  /** 图纸计算长度（米） */
  computed: number
  /** 清单申报长度（米），无对应清单行时为 null */
  reported: number | null
  /** 清单 - 图纸（米） */
  diff: number | null
  diffRate: number | null
  parts: CablePart[]
  rows: CableRow[]
}

/** 敷设方式分类：清单按名称归类，图纸按图层名归类，两边对上才按类别比 */
const CATEGORIES: { key: string; label: string; words: string[] }[] = [
  { key: 'pipe', label: '管道', words: ['管道', '排管', '管孔', '孔管', '穿管'] },
  { key: 'overhead', label: '架空/加挂', words: ['架空', '加挂', '吊线'] },
  { key: 'buried', label: '直埋', words: ['直埋', '埋设'] },
  { key: 'wall', label: '墙壁', words: ['墙壁', '墙面'] },
  { key: 'duct', label: '槽道/桥架', words: ['槽道', '桥架', '线槽'] },
]

const CABLE_WORDS = ['光缆', '电缆', '线缆', '导线', '纤芯']

/** 从名称/图层名判断敷设方式，判断不出返回 other */
export function cableCategory(text?: string | null): string {
  const value = (text ?? '').replace(/\s/g, '')
  if (!value) {
    return 'other'
  }
  for (const category of CATEGORIES) {
    if (category.words.some((word) => value.includes(word))) {
      return category.key
    }
  }
  return 'other'
}

export function categoryLabel(key: string): string {
  return CATEGORIES.find((item) => item.key === key)?.label ?? '其他'
}

/**
 * 单张图纸的线缆图层 → 分类长度；多张图纸用 mergeDrawingCable 合并。
 * 有些竣工图只在管道/架空路由线上画长度、没有单独的线缆图层，
 * 这时退回路由图层取量，界面上会标注数据来源，避免把空值当 0 去比。
 */
export function toDrawingCable(measure?: Record<string, any> | null): DrawingCable {
  const cableLayers: Record<string, number> = measure?.cableLayers ?? {}
  const cableParts = layerParts(cableLayers)
  if (cableParts.length) {
    return { total: round(sumParts(cableParts)), parts: cableParts, source: 'cable' }
  }
  const lengthByLayer: Record<string, number> = measure?.lengthByLayer ?? {}
  const routeParts = layerParts(lengthByLayer).filter((part) => isRouteLabel(part.label))
  if (routeParts.length) {
    return { total: round(sumParts(routeParts)), parts: routeParts, source: 'route' }
  }
  // 很多竣工图不画线缆图层，只在每段路由旁标长度（5.7、2.5、管453.7），用标注合计兜底
  const labelMeters = round(Number(measure?.annotationLengthMeters) || 0)
  if (labelMeters > 0) {
    const byKind: Record<string, number> = measure?.annotationByKind ?? {}
    const parts = Object.keys(byKind)
      .map((category) => ({
        label: categoryLabel(category),
        category,
        meters: round(Number(byKind[category]) || 0),
      }))
      .filter((part) => part.meters > 0)
    return { total: labelMeters, parts, source: 'annotation' }
  }
  const meters = round(Number(measure?.cableLengthMeters) || 0)
  return { total: meters, parts: [], source: meters > 0 ? 'cable' : 'none' }
}

/** 多张图纸合并：同名图层相加 */
export function mergeDrawingCable(measures: (Record<string, any> | null | undefined)[]): DrawingCable {
  const merged = new Map<string, CablePart>()
  let source: DrawingCable['source'] = 'none'
  const rank = { none: 0, annotation: 1, route: 2, cable: 3 } as const
  measures.forEach((measure) => {
    const drawing = toDrawingCable(measure)
    if (rank[drawing.source] > rank[source]) {
      source = drawing.source
    }
    drawing.parts.forEach((part) => {
      const hit = merged.get(part.label)
      if (hit) {
        hit.meters = round(hit.meters + part.meters)
      } else {
        merged.set(part.label, { ...part })
      }
    })
  })
  const parts = [...merged.values()]
  return { total: round(sumParts(parts)), parts, source }
}

export function cableSourceText(source: DrawingCable['source']): string {
  switch (source) {
    case 'route':
      return '未识别到线缆图层，取自管道/架空等路由图层'
    case 'annotation':
      return '未识别到线缆/路由图层，取自图上段长标注合计'
    case 'cable':
      return '取自线缆图层'
    default:
      return '未识别到线缆图层，也没有可用的长度标注'
  }
}

function layerParts(layers: Record<string, number>): CablePart[] {
  return Object.keys(layers)
    .map((layer) => ({
      label: layer,
      category: cableCategory(layer),
      meters: round(Number(layers[layer]) || 0),
    }))
    .filter((part) => part.meters > 0 && !NOISE_WORDS.some((word) => part.label.includes(word)))
}

function isRouteLabel(label: string): boolean {
  return ROUTE_WORDS.some((word) => label.includes(word))
}

function sumParts(parts: CablePart[]): number {
  return parts.reduce((sum, part) => sum + part.meters, 0)
}

/** 名称是否属于光缆/电缆类（不含单位判断，供界面标记用） */
export function isCableName(name?: string | null): boolean {
  return CABLE_WORDS.some((word) => (name ?? '').includes(word))
}

function isCableRow(item: BoqItemVO): boolean {
  const name = item.itemName ?? ''
  if (!CABLE_WORDS.some((word) => name.includes(word))) {
    return false
  }
  // 只认长度类单位，光缆接头盒、终端盒这种计件物资不参与长度比对
  return unitFamily(item.unit) === 'length'
}

function toCableRow(item: BoqItemVO): CableRow {
  const factor = unitFactor(item.unit, 'm') ?? 1
  const quantity = Number(item.quantity) || 0
  return {
    id: item.id,
    rowNo: item.rowNo,
    itemName: item.itemName,
    spec: item.featureDesc,
    quantity,
    unit: item.unit,
    meters: round(quantity * factor),
    category: cableCategory(item.itemName),
  }
}

/** 规格里的芯数，用来判断甲供光缆是不是按芯数分成了多行 */
function specKey(item: BoqItemVO): string {
  const text = `${item.featureDesc ?? ''} ${item.itemName ?? ''}`
  const match = text.match(/(\d+)\s*芯/)
  return match ? `${match[1]}芯` : text.trim()
}

/**
 * 清单侧取数口径：
 * - 有「敷设光缆」这类施工量行就只用它（甲供材料是领用量，不能混进来重复算）；
 * - 只有甲供材料行时，单一规格可以比；
 * - 按芯数分成好几行（4芯/8芯/12芯/24芯）时口径不同——那是同一段路由上并行的多根光缆，
 *   和图纸路由长度不是一个量，不能直接比。
 */
export function cableBasis(items: BoqItemVO[]): { basis: CableBasis; rows: CableRow[] } {
  const all = items.filter(isCableRow)
  const quotaRows = all.filter((item) => (item.itemType ?? 'QUOTA') === 'QUOTA')
  if (quotaRows.length) {
    return { basis: 'quota', rows: quotaRows.map(toCableRow) }
  }
  const specs = new Set(all.map(specKey))
  return { basis: specs.size > 1 ? 'multi-spec' : 'material', rows: all.map(toCableRow) }
}

/** 兼容旧调用：只返回折算后的清单行 */
export function cableRows(items: BoqItemVO[]): CableRow[] {
  return cableBasis(items).rows
}

/**
 * 光缆长度核查：清单申报长度不得小于图纸计算长度。
 * 画图算出来的量是实际工作量，清单（结算）只能大于等于它；
 * 小于说明少计，直接给错误结论。
 */
export function checkCableLength(drawing: DrawingCable, items: BoqItemVO[], scopeCategory?: string): CableCheck {
  const { basis, rows: allRows } = cableBasis(items)
  // 图纸有对应敷设方式的图层时按类别比对，否则退回全部口径
  const hasScope = Boolean(
    scopeCategory && scopeCategory !== 'other' && drawing.parts.some((part) => part.category === scopeCategory),
  )
  const parts = hasScope ? drawing.parts.filter((part) => part.category === scopeCategory) : drawing.parts
  const computed = hasScope ? round(parts.reduce((sum, part) => sum + part.meters, 0)) : drawing.total
  const rows = hasScope ? allRows.filter((row) => row.category === scopeCategory) : allRows
  const reported = rows.length ? round(rows.reduce((sum, row) => sum + row.meters, 0)) : null
  const scope = hasScope ? categoryLabel(scopeCategory as string) : '全部'

  if (computed <= 0) {
    return { status: 'NO_DRAWING', basis, scope, computed: 0, reported, diff: null, diffRate: null, parts, rows }
  }
  if (reported === null) {
    return { status: 'NO_BOQ', basis, scope, computed, reported: null, diff: null, diffRate: null, parts, rows }
  }
  const diff = round(reported - computed)
  const diffRate = computed === 0 ? null : round((diff / computed) * 100)
  // 多芯数甲供光缆是同一路由上并行的多根光缆，和图纸路由长度不是一个量，不下判定
  if (basis === 'multi-spec') {
    return { status: 'NO_BASIS', basis, scope, computed, reported, diff, diffRate, parts, rows }
  }
  // 容差 0.01 米：四舍五入的尾差不算少计
  const status: CableStatus = diff < -0.01 ? 'LESS' : 'OK'
  return { status, basis, scope, computed, reported, diff, diffRate, parts, rows }
}

/** 按敷设方式分类的比对行：管道、架空各算各的，避免合计掩盖某一类少计 */
export interface CableCategoryLine {
  category: string
  label: string
  computed: number
  reported: number | null
  status: CableStatus
}

export function cableCategoryLines(drawing: DrawingCable, items: BoqItemVO[]): CableCategoryLine[] {
  // 标注兜底时拿不到分类长度，只做总量比对，不出分类表免得每类都显示「图纸无此图层」
  if (!drawing.parts.length) {
    return []
  }
  const { basis, rows } = cableBasis(items)
  // 口径不可比时也不出分类结论
  if (basis === 'multi-spec') {
    return []
  }
  const categories = new Set<string>()
  drawing.parts.forEach((part) => categories.add(part.category))
  rows.forEach((row) => categories.add(row.category))
  return [...categories]
    .map((category) => {
      const computed = round(
        drawing.parts.filter((part) => part.category === category).reduce((sum, part) => sum + part.meters, 0),
      )
      const scoped = rows.filter((row) => row.category === category)
      const reported = scoped.length ? round(scoped.reduce((sum, row) => sum + row.meters, 0)) : null
      let status: CableStatus = 'OK'
      if (computed <= 0) {
        status = 'NO_DRAWING'
      } else if (reported === null) {
        status = 'NO_BOQ'
      } else if (reported - computed < -0.01) {
        status = 'LESS'
      }
      return { category, label: categoryLabel(category), computed, reported, status }
    })
    .sort((a, b) => b.computed - a.computed)
}

export function cableStatusText(check: CableCheck): string {
  switch (check.status) {
    case 'LESS':
      return '清单小于图纸，疑似少计'
    case 'OK':
      return '清单不小于图纸，通过'
    case 'NO_BASIS':
      return '清单按芯数分行，是领用量不是路由长度，不判定'
    case 'NO_BOQ':
      return '清单无光缆类工程量，无法比对'
    default:
      return '图纸未识别到线缆或标注长度'
  }
}

/**
 * 图上长度取数口径（后端 annotationBasis）：
 * table=材料表光缆数量、summary=图上印的合计、segments=逐段标注之和。
 * 三种口径的可信度、能否按敷设方式拆开都不一样，界面上要写清楚。
 */
export function annotationBasisText(basis?: string | null): string {
  switch (basis) {
    case 'table':
      return '取自材料表里的光缆数量'
    case 'summary':
      return '取自图上印的「合计」'
    case 'segments':
      return '取自图上逐段标注之和'
    case 'mixed':
      return '部分张取材料表、部分张取标注，口径不一'
    default:
      return '未识别到长度标注'
  }
}

/**
 * 按张核对（后端 annotationBySheet）：每张图的光缆长度是从哪儿取的、取了多少，
 * 以及这张图的长度由材料表里哪几行光缆组成（用户可以拿图纸逐行对）。
 * 按图签张号升序排，跟翻图纸的顺序一致。
 */
export function annotationSheets(measure?: Record<string, any> | null): {
  sheet: string
  sourceLabel: string
  meters: number
  count: number
  composition: string
}[] {
  const rows: any[] = measure?.annotationBySheet ?? []
  return rows
    .map((row) => {
      const items: { label: string; meters: number }[] = row.items ?? []
      return {
        sheet: String(row.sheet ?? ''),
        sourceLabel: row.source === 'table' ? '材料表' : row.source === 'summary' ? '图上合计' : '逐段标注',
        meters: round(Number(row.meters) || 0),
        count: Number(row.count) || 0,
        // 「通信光缆 24芯」只留规格，凑成「24芯 3104.4 + 12芯 2612.4」这样一眼能对上表
        composition: items
          .map((item) => `${String(item.label ?? '').replace(/^通信光缆\s*/, '')} ${round(Number(item.meters) || 0)}`)
          .join(' + '),
        order: Number(/(\d+)/.exec(String(row.sheet ?? ''))?.[1] ?? 1e6),
      }
    })
    .filter((row) => row.meters > 0)
    .sort((a, b) => a.order - b.order)
    .map(({ order: _order, ...row }) => row)
}

/** 图上长度标注的按图层合计（后端 annotationByLayer），用于「单个图层核算」展示 */
export function annotationLayers(measure?: Record<string, any> | null): {
  layer: string
  count: number
  meters: number
}[] {
  const byLayer: Record<string, { count?: number; meters?: number }> = measure?.annotationByLayer ?? {}
  return Object.keys(byLayer)
    .map((layer) => ({
      layer,
      count: Number(byLayer[layer].count) || 0,
      meters: round(Number(byLayer[layer].meters) || 0),
    }))
    .filter((row) => row.count > 0)
    .sort((a, b) => b.meters - a.meters)
}

function round(value: number): number {
  return Math.round(value * 100) / 100
}
