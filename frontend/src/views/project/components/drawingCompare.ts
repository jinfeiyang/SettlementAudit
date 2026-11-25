import type { BoqItemVO, MaterialMergeVO } from '@/api'
import {
  canonicalName,
  charOverlap,
  featureConflicts,
  normalizeText,
  normalizeUnit,
  similarity,
  unitFactor,
  unitFamily,
  unitsIncompatible,
} from '@/utils/match'

/** 图纸材料表解析出的一行（AI 服务 materialSummary 的字段子集） */
export interface DrawingMaterialItem {
  name?: string
  spec?: string
  unit?: string
  quantity?: number
  tableCount?: number
  /** 这条材料出自哪几张材料表（AI 服务 materials[].table 的编号） */
  tables?: number[]
  /** 逐表数量：表号 → 该表中的数量，用于展示数量计算式 */
  quantityParts?: Record<string, number>
}

export interface Bounds {
  minX: number
  minY: number
  maxX: number
  maxY: number
}

/** 材料表明细（AI 服务 materials）：靠 frame/frameSheet 标出这张表画在第几张图上 */
export interface DrawingTableItem {
  table?: number
  frame?: number | null
  frameIndex?: number | null
  frameSheet?: string | null
  frameTitle?: string | null
  /** 这张表在图纸上的范围：没画图框的图纸靠它定位 */
  box?: Bounds | null
}

/** 数量公式的一项：数值本身 + 它在图纸上的出处，供点击定位与当前页标红 */
export interface FormulaPart {
  /** 分项键，如「第 07 张」或「表 12」 */
  label: string
  text: string
  /** 该分项对应的图纸页；旧数据或表号找不到图框时为空，此时只显示数值 */
  source?: DrawingSource
  /** 该分项是否被人工修正过，界面可据此提示并提供恢复入口 */
  fixed?: boolean
}

export interface QuantityFormulaData {
  parts: FormulaPart[]
  total: string
}

const fmtQty = (value: number) =>
  value.toLocaleString('zh-CN', { maximumFractionDigits: 4 })

/**
 * 拆分数量计算式为可点击的分项：每个数值按分项键（第 N 张 / 表 N）找到对应图纸来源，
 * 点数值即可像点页码一样定位，当前查看的页对应数值由调用方标红。
 */
export function quantityFormulaData(material?: {
  quantityParts?: Record<string, number>
  quantity?: number
  unit?: string
  sources?: DrawingSource[]
}, fixedKeys?: Record<string, number>): QuantityFormulaData | null {
  const entries = Object.entries(material?.quantityParts ?? {}).filter(([, value]) => value)
  if (!entries.length) {
    return null
  }
  const sources = material?.sources ?? []
  const parts: FormulaPart[] = entries.map(([label, value]) => {
    const sheet = /^第\s*(.+?)\s*张$/.exec(label)?.[1]
    const tableNo = /^表\s*(\d+)$/.exec(label)?.[1]
    const source = sources.find((item) =>
      (sheet != null && (String(item.sheet) === sheet || String(item.frame) === sheet)) ||
      (tableNo != null && item.tables.includes(Number(tableNo))),
    )
    return {
      label,
      text: fmtQty(value),
      ...(source ? { source } : {}),
      ...(fixedKeys && label in fixedKeys ? { fixed: true } : {}),
    }
  })
  const total = material?.quantity ?? entries.reduce((sum, [, value]) => sum + value, 0)
  return { parts, total: `${fmtQty(total)}${material?.unit ?? ''}` }
}

/**
 * 把材料在图纸各表里的数量拼成计算式，供图纸定位弹窗展示：
 * 「1 + 1 + 3 + 2 = 7 套」——只列分项数量相加，不显示「第 XX 张」页码。
 * 分项全部列出，不折叠，公式末尾仍有合计便于核对。
 */
export function quantityFormula(material?: {
  quantityParts?: Record<string, number>
  quantity?: number
  unit?: string
  sources?: DrawingSource[]
}): string {
  const data = quantityFormulaData(material)
  if (!data) {
    return ''
  }
  return `${data.parts.map((part) => part.text).join(' + ')} = ${data.total}`
}

/** 人工修正：材料 key → 分项键（第 N 张 / 表 N）→ 修正后的数量 */
export type QuantityFixes = Record<string, Record<string, number>>

/** 汇总多张图纸各自的人工修正：同材料的分项按键合并 */
export function mergeQuantityFixes(target: QuantityFixes, source?: QuantityFixes | null): void {
  for (const [key, parts] of Object.entries(source ?? {})) {
    target[key] = { ...(target[key] ?? {}), ...parts }
  }
}

/**
 * 把人工修正叠加到汇总后的材料上。修正只改分项并重算合计，
 * 图纸清单与工程量清单的公式、差异对比都会随之更新。
 */
export function applyQuantityFixes(materials: DrawingMaterial[], fixes: QuantityFixes): void {
  for (const material of materials) {
    const partFixes = fixes[material.key]
    if (!partFixes) {
      continue
    }
    for (const [partKey, value] of Object.entries(partFixes)) {
      if (partKey in material.quantityParts && Number.isFinite(value)) {
        material.quantityParts[partKey] = value
      }
    }
    material.quantity = round(
      Object.values(material.quantityParts).reduce((sum, value) => sum + value, 0),
    )
  }
}

/** 图框（AI 服务 frames）：张号、图名与坐标范围，用于看图时定位到这一页 */
export interface DrawingFrameItem {
  no: number
  sheet?: string | null
  title?: string | null
  minX: number
  minY: number
  maxX: number
  maxY: number
}

/** 一条材料在图纸上的出处 */
export interface DrawingSource {
  fileId: number
  fileName: string
  /** 图框合成序号（阅读顺序），没识别出图框时为 null */
  frame: number | null
  /** 图签上印的张号，如「5」 */
  sheet: string | null
  /** 图名 */
  title: string | null
  /** 该页（图框）的坐标范围，看图时按它定位 */
  bbox: Bounds | null
  /** 没识别出图框时，退而用材料表本身的范围定位到这张表所在页面 */
  tableBox: Bounds | null
  /** 这条材料在这张图上出现的材料表编号 */
  tables: number[]
}

/** 跨图纸汇总后的一行图纸清单 */
export interface DrawingMaterial {
  key: string
  name: string
  spec: string
  unit: string
  quantity: number
  /** 图纸上这条材料的数量计算式分项，如「第2张 100 + 第4张 200」 */
  quantityParts: Record<string, number>
  fileCount: number
  files: string[]
  /** 出现在哪几张图上，按图纸顺序 */
  sources: DrawingSource[]
  /** 人工合并时组内成员的原名称（未合并时为空） */
  aliases?: string[]
}

/** 单张图纸的解析结果（materialSummary + 材料表明细 + 图框） */
export interface DrawingParseResult {
  fileId: number
  fileName: string
  summary?: DrawingMaterialItem[]
  tables?: DrawingTableItem[]
  frames?: DrawingFrameItem[]
}

/** 图纸清单行 + 与清单的比对结论 */
export interface DrawingRow extends DrawingMaterial {
  /** 命中的清单行 */
  match?: BoqItemVO
  /** 1 名称一致 / 2 相似关联 */
  level?: 1 | 2
  score?: number
  /** 图纸数量 - 换算后的清单数量（图纸单位），不可换算时为 null */
  diff?: number | null
  /** 差异率（%），以清单数量为基数 */
  diffRate?: number | null
  /** 人工指定的匹配（区别于自动匹配） */
  manual?: boolean
}

/** 未匹配清单行的最近候选，标出差距方便人工判断 */
export interface NearestMatch {
  row: DrawingRow
  score: number
}

export interface ComparisonResult {
  rows: DrawingRow[]
  /** 清单行 id → 最贴近的图纸清单行，用于清单表反向展示 */
  byBoqId: Map<number, DrawingRow>
  /** 清单行 id → 分数最高的图纸候选（可能低于阈值） */
  nearestByBoqId: Map<number, NearestMatch>
  matched: number
  unmatched: number
}

/** 一张已解析图纸的文字标注索引（施工光缆段落名回查图纸页用） */
export interface DrawingLabelDoc {
  fileId: number
  fileName: string
  labels: import('@/api').DrawingTextLabel[]
  /** 各图框页上的材料规格（图纸材料表），用于把段名命中页收敛到有对应光缆规格的页 */
  frameSpecs?: Map<number, string[]>
  /** 图框列表，供手动关联时选择具体页 */
  frames?: Array<{
    no: number
    sheet?: string | null
    title?: string | null
    minX: number
    minY: number
    maxX: number
    maxY: number
  }>
}

/** 段落名命中的图纸来源：载着页码/图名/坐标，直接交给 DrawingJump 定位 */
export interface SegmentDrawingHit {
  source: DrawingSource
  /** 命中的图纸标注原文，便于核对是不是这一段 */
  matched: string[]
}

/**
 * 用施工光缆段落名（如「玉环玉城街道外环西路.GJ327-玉环新供电局大楼.GJ276」）在图纸文字标注里回查所在页。
 *
 * 图纸上通常把整段名或它的某一端印成一条标注，所以只做「长文本之间」的正向包含：
 * 标注与段名相同、标注是段名的一段、或段名是标注的一段，且参与比较的文本至少 8 字。
 * 绝不能用「短标注 ⊂ 端点」的反向匹配——图纸里满屏的「1」「2」数字标注会命中所有段，
 * 造成每段都关联几十页的假阳性。
 * 有整段名命中的页时只认这些页；结果按文件顺序 + 图框序号（第几张）排列。
 */
const SEGMENT_LABEL_MIN = 8

export function findSegmentDrawingHits(
  segmentName: string | undefined,
  docs: DrawingLabelDoc[],
  /** 该段芯数对应的光缆规格（如 GYTS-12B1）：命中多页时只保留印有该规格材料表的页 */
  spec = '',
): SegmentDrawingHit[] {
  const name = normalizeText(segmentName)
  if (!name) {
    return []
  }

  const grouped = new Map<string, SegmentDrawingHit & { count: number; whole: boolean; docIndex: number }>()
  const docOrder = new Map(docs.map((doc, index) => [doc.fileId, index]))
  for (const doc of docs) {
    for (const label of doc.labels) {
      const text = normalizeText(label.text)
      // 两端都要够长才比较，彻底排除「1」「2」「GJ」这类短标注
      if (text.length < SEGMENT_LABEL_MIN || name.length < SEGMENT_LABEL_MIN) {
        continue
      }
      const same = text === name
      // 图框上的整段名常和多段标题拼在一格（「段名A^J段名B」），
      // 只有 text === name 会漏判；标注包含完整段名同样算整段命中。
      // 反过来「标注是段名的一段」（端点地名）不算，避免误并进别的分幅页。
      const whole = same || text.includes(name)
      const contains = whole || name.includes(text)
      if (!contains) {
        continue
      }
      // 只认落在图框里的标注：图框外的文字（图签、封面说明）没有页码，
      // 关联到它们会得到无法定位的「材料表」来源，例如段名重复出现在图签上时。
      if (label.frame == null) {
        continue
      }
      const frameKey = String(label.frame)
      const groupKey = `${doc.fileId}:${frameKey}`
      const hit = grouped.get(groupKey)
      if (hit) {
        hit.count += 1
        hit.whole = hit.whole || whole
        if (!hit.matched.includes(label.text)) {
          hit.matched.push(label.text)
        }
        continue
      }
      grouped.set(groupKey, {
        source: {
          fileId: doc.fileId,
          fileName: doc.fileName,
          frame: label.frame,
          sheet: label.sheet ?? null,
          title: label.title ?? null,
          bbox: label.bbox ?? null,
          // 没画图框时退而用文字坐标构造一个小范围，跳转后能放大到段名所在处
          tableBox: label.bbox
            ? null
            : { minX: label.x - 5, minY: label.y - 5, maxX: label.x + 5, maxY: label.y + 5 },
          tables: [],
        },
        matched: [label.text],
        count: 1,
        whole,
        docIndex: docOrder.get(doc.fileId) ?? 0,
      })
    }
  }
  const all = [...grouped.values()]
  // 有整段名完全相等的页时只认这些页：端点地名会在多张图重复出现，
  // 一旦图上印了完整段名，端点包含命中的页就是噪音，必须丢掉。
  const exact = all.filter((hit) => hit.whole)
  let picked = exact.length ? exact : all
  // 段名端点（如酒店SF1/SF3）可能在多张分幅图重复印出，但只有一页印了该段光缆
  // 规格的材料表。传入规格时收敛到那些页，避免关联一堆印了站名却没有对应光缆的页。
  if (spec) {
    const target = normalizeText(spec)
    const hasSpec = picked.filter((hit) => {
      const frame = hit.source.frame
      if (frame == null) {
        return false
      }
      const doc = docs.find((item) => item.fileId === hit.source.fileId)
      const specs = doc?.frameSpecs?.get(frame) ?? []
      return specs.some((item) => normalizeText(item).includes(target) || target.includes(normalizeText(item)))
    })
    if (hasSpec.length) {
      picked = hasSpec
    }
  }
  // 按图纸页次序展示：先文件顺序，再图框序号（no 即「第几张」）；无图框的排最后
  return picked
    .sort((a, b) => {
      if (a.docIndex !== b.docIndex) {
        return a.docIndex - b.docIndex
      }
      const fa = a.source.frame
      const fb = b.source.frame
      if (fa == null && fb == null) {
        return 0
      }
      if (fa == null) {
        return 1
      }
      if (fb == null) {
        return -1
      }
      return fa - fb
    })
    .map(({ source, matched }) => ({ source, matched }))
}

/** 低于该分数视为没对上，宁可显示「未匹配」也不乱配 */
const MATCH_THRESHOLD = 0.6

const MATERIAL_TYPES = new Set(['MATERIAL', 'SUPPLY_MATERIAL', 'SUPPLY_EQUIPMENT'])

/**
 * 汇总多张图纸的材料表：
 * 名称 + 规格 + 单位一致视为同一种材料，数量相加，来源图纸去重。
 * 同时记下每条材料画在哪几张图上（材料表 → 图框），图纸清单里可以直接跳过去看。
 */
export function mergeDrawingMaterials(results: DrawingParseResult[]): DrawingMaterial[] {
  const merged = new Map<string, DrawingMaterial>()
  results.forEach(({ fileId, fileName, summary, tables, frames }) => {
    const tablesByNo = new Map<number, DrawingTableItem>()
    ;(tables ?? []).forEach((table) => {
      if (typeof table.table === 'number') {
        tablesByNo.set(table.table, table)
      }
    })
    const framesByNo = new Map<number, DrawingFrameItem>()
    ;(frames ?? []).forEach((frame) => framesByNo.set(frame.no, frame))

    ;(summary ?? []).forEach((item) => {
      const name = (item.name ?? '').trim()
      const quantity = Number(item.quantity)
      if (!name || Number.isNaN(quantity)) {
        return
      }
      const spec = (item.spec ?? '').trim()
      const unit = (item.unit ?? '').trim()
      // 同物异名（新立光交 / 新建光缆交接箱）合成一行，否则同一件器材会被拆成 1+1
      const key = `${canonicalName(name)}|${normalizeText(spec)}|${normalizeUnit(unit)}`
      const sources = sourcesOf(item.tables, tablesByNo, framesByNo, fileId, fileName)
      // 数量分项：表号映射到所在张号，显示成「第 N 张 数量 + … = 合计」
      const parts: Record<string, number> = {}
      Object.entries(item.quantityParts ?? {}).forEach(([tableNo, value]) => {
        const table = tablesByNo.get(Number(tableNo))
        const sheet = table?.frameSheet
        const partKey = sheet ? `第 ${sheet} 张` : `表 ${tableNo}`
        parts[partKey] = round((parts[partKey] ?? 0) + Number(value))
      })
      // 没有逐表分项就不造「数量 = 合计」的占位项——那不是分页相加公式，
      // 会在图纸定位弹窗里显示成误导性的「数量 6 = 6处」。无分项时留空，不显示公式。
      const hit = merged.get(key)
      if (hit) {
        hit.quantity = round(hit.quantity + quantity)
        // 合并后展示更完整的写法（字多的一般是全称）
        if (name.length > hit.name.length) {
          hit.name = name
        }
        Object.entries(parts).forEach(([partKey, value]) => {
          hit.quantityParts[partKey] = round((hit.quantityParts[partKey] ?? 0) + value)
        })
        if (!hit.files.includes(fileName)) {
          hit.files.push(fileName)
          hit.fileCount = hit.files.length
        }
        mergeSources(hit.sources, sources)
        return
      }
      merged.set(key, {
        key,
        name,
        spec,
        unit,
        quantity: round(quantity),
        quantityParts: parts,
        fileCount: 1,
        files: [fileName],
        sources,
      })
    })
  })
  return [...merged.values()].sort((a, b) => b.quantity - a.quantity || a.name.localeCompare(b.name, 'zh-CN'))
}

/** 人工合并组：同物异名的多条材料合成一行参与匹配 */
export interface MaterialMergeGroup {
  groupKey: string
  displayName?: string | null
  memberKeys: string[]
}

/** 图纸材料的人工编辑：材料键不变，只覆盖展示字段与数量 */
export interface MaterialEdit {
  materialKey: string
  name?: string | null
  spec?: string | null
  unit?: string | null
  quantity?: number | null
}

/**
 * 叠加人工编辑：按材料键覆盖名称/规格/单位/数量。
 * 材料键保持不变，已有匹配、合并组不会因为改名而断开关联。
 */
export function applyMaterialEdits(
  materials: DrawingMaterial[],
  edits: MaterialEdit[],
): DrawingMaterial[] {
  if (!edits.length) {
    return materials
  }
  const byKey = new Map(edits.map((edit) => [edit.materialKey, edit]))
  materials.forEach((material) => {
    const edit = byKey.get(material.key)
    if (!edit) {
      return
    }
    if (edit.name?.trim()) {
      material.name = edit.name.trim()
    }
    if (edit.spec !== null && edit.spec !== undefined) {
      material.spec = edit.spec
    }
    if (edit.unit !== null && edit.unit !== undefined) {
      material.unit = edit.unit
    }
    if (edit.quantity !== null && edit.quantity !== undefined) {
      material.quantity = edit.quantity
      // 人工改过数量后，不再按图纸分项累加，公式直接展示修正值
      material.quantityParts = { 人工修正: edit.quantity }
    }
  })
  return materials
}

/** 后端记录（一个成员一条）按 groupKey 聚合成分组 */
export function toMergeGroups(rows: MaterialMergeVO[]): MaterialMergeGroup[] {
  const groups = new Map<string, MaterialMergeGroup>()
  rows.forEach((row) => {
    const exist = groups.get(row.groupKey)
    if (exist) {
      if (!exist.memberKeys.includes(row.memberKey)) {
        exist.memberKeys.push(row.memberKey)
      }
      return
    }
    groups.set(row.groupKey, {
      groupKey: row.groupKey,
      displayName: row.displayName,
      memberKeys: [row.memberKey],
    })
  })
  return [...groups.values()].filter((group) => group.memberKeys.length > 1)
}

/**
 * 叠加人工合并：组内材料合成一行，数量按分项相加，来源图页合并，原名保留在 aliases。
 * 合并后的行用 groupKey 作 key，后续自动匹配、人工匹配与数量对比都按合并行计算。
 */
export function applyMaterialMerges(
  materials: DrawingMaterial[],
  groups: MaterialMergeGroup[],
  fixes?: QuantityFixes,
): DrawingMaterial[] {
  if (!groups.length) {
    return materials
  }
  const groupByMember = new Map<string, MaterialMergeGroup>()
  groups.forEach((group) => group.memberKeys.forEach((key) => groupByMember.set(key, group)))
  const mergedByGroup = new Map<string, DrawingMaterial>()
  const out: DrawingMaterial[] = []
  materials.forEach((material) => {
    const group = groupByMember.get(material.key)
    if (!group) {
      out.push(material)
      return
    }
    const exist = mergedByGroup.get(group.groupKey)
    if (!exist) {
      const row: DrawingMaterial = {
        ...material,
        key: group.groupKey,
        name: group.displayName?.trim() || material.name,
        quantityParts: { ...material.quantityParts },
        files: [...material.files],
        sources: [...material.sources],
        aliases: [material.name],
      }
      // 合并行沿用组内成员的数量修正，列表上的「已改」标记与换算公式都不丢
      if (fixes) {
        fixes[row.key] = { ...(fixes[row.key] ?? {}), ...(fixes[material.key] ?? {}) }
      }
      mergedByGroup.set(group.groupKey, row)
      out.push(row)
      return
    }
    if (!exist.aliases!.includes(material.name)) {
      exist.aliases!.push(material.name)
    }
    exist.quantity = round(exist.quantity + material.quantity)
    Object.entries(material.quantityParts).forEach(([label, value]) => {
      exist.quantityParts[label] = round((exist.quantityParts[label] ?? 0) + value)
    })
    material.files.forEach((file) => {
      if (!exist.files.includes(file)) {
        exist.files.push(file)
      }
    })
    exist.fileCount = exist.files.length
    mergeSources(exist.sources, material.sources)
    if (fixes) {
      Object.assign(fixes[exist.key], fixes[material.key] ?? {})
    }
  })
  return out
}

/** 材料表编号 → 图框 → 出处；同一张图上的多张表并成一条 */
function sourcesOf(
  tableNos: number[] | undefined,
  tablesByNo: Map<number, DrawingTableItem>,
  framesByNo: Map<number, DrawingFrameItem>,
  fileId: number,
  fileName: string,
): DrawingSource[] {
  const grouped = new Map<string, DrawingSource>()
  ;(tableNos ?? []).forEach((no) => {
    const table = tablesByNo.get(no)
    const frameNo = table?.frame ?? null
    const frame = frameNo === null ? undefined : framesByNo.get(frameNo)
    const groupKey = `${frameNo ?? 'none'}`
    const tableBox = table?.box ?? null
    const exist = grouped.get(groupKey)
    if (exist) {
      exist.tables.push(no)
      exist.tableBox = unionBounds(exist.tableBox, tableBox)
    } else {
      grouped.set(groupKey, {
        fileId,
        fileName,
        frame: frameNo,
        // 表上的张号/图名优先：一张图分幅画成多个图框时，图框号跟纸上印的张号不一样
        sheet: table?.frameSheet ?? frame?.sheet ?? null,
        title: table?.frameTitle ?? frame?.title ?? null,
        bbox: frame
          ? { minX: frame.minX, minY: frame.minY, maxX: frame.maxX, maxY: frame.maxY }
          : null,
        tableBox,
        tables: [no],
      })
    }
  })
  return [...grouped.values()]
}

function unionBounds(a: Bounds | null, b: Bounds | null): Bounds | null {
  if (!a) {
    return b
  }
  if (!b) {
    return a
  }
  return {
    minX: Math.min(a.minX, b.minX),
    minY: Math.min(a.minY, b.minY),
    maxX: Math.max(a.maxX, b.maxX),
    maxY: Math.max(a.maxY, b.maxY),
  }
}

/**
 * 看图时要放大的范围：有图框就放大到整页；图纸没画图框块时退化为材料表所在区域，
 * 并往外留一圈，否则放大后只剩几行字，看不出这是哪一页。
 */
export function focusBoundsOf(source: DrawingSource): Bounds | null {
  if (source.bbox) {
    return source.bbox
  }
  const box = source.tableBox
  if (!box) {
    return null
  }
  const padX = Math.max((box.maxX - box.minX) * 1.2, 1)
  const padY = Math.max((box.maxY - box.minY) * 1.2, 1)
  return {
    minX: box.minX - padX,
    minY: box.minY - padY,
    maxX: box.maxX + padX,
    maxY: box.maxY + padY,
  }
}

/** 同一来源（同一张图的同一页）只保留一份 */
function mergeSources(target: DrawingSource[], incoming: DrawingSource[]) {
  incoming.forEach((source) => {
    const exist = target.find((item) => item.fileId === source.fileId && item.frame === source.frame)
    if (!exist) {
      target.push(source)
      return
    }
    source.tables.forEach((no) => {
      if (!exist.tables.includes(no)) {
        exist.tables.push(no)
      }
    })
    exist.tableBox = unionBounds(exist.tableBox, source.tableBox)
  })
}

/**
 * 单对「图纸材料 → 清单行」的匹配分（0~1）。
 * 先用硬规则排除明显不是同一件的：型号参数冲突（48芯 vs 24芯）、量纲冲突（米 vs 个）。
 * 再按名称相似度给基础分，规格与清单特征吻合、物资行、单位口径一致分别小幅加分。
 */
function scorePair(material: DrawingMaterial, item: BoqItemVO): number {
  // 先按同物异名归一：图纸「新立光交」与清单「光缆交接箱」是同一件器材
  const drawingName = canonicalName(material.name)
  const boqName = canonicalName(item.itemName)
  if (!drawingName || !boqName) {
    return 0
  }
  if (unitsIncompatible(material.unit, item.unit)) {
    return 0
  }
  if (featureConflicts(`${material.name} ${material.spec}`, `${item.itemName} ${item.featureDesc ?? ''}`)) {
    return 0
  }

  let base: number
  if (drawingName === boqName) {
    base = 1
  } else if (drawingName.includes(boqName) || boqName.includes(drawingName)) {
    // 一方是另一方的子串：图纸「光缆」对清单「敷设室外光缆」，按长度比给分
    const shorter = Math.min(drawingName.length, boqName.length)
    const longer = Math.max(drawingName.length, boqName.length)
    base = 0.7 + 0.25 * (shorter / longer)
  } else {
    base = similarity(drawingName, boqName)
  }
  // 「ODF框」对「ODF架」这类只差一个字的口径写法，双字切分算不出相似度，用字符重合度兜底
  const overlap = charOverlap(drawingName, boqName)
  if (overlap > base) {
    base = Math.min(0.9, overlap)
  }

  let score = base * 0.92 + similarity(material.spec, item.featureDesc) * 0.08
  // 图纸规格整段出现在清单项目特征里，基本可判定同一型号
  const spec = normalizeText(material.spec)
  if (spec && spec.length >= 3 && normalizeText(item.featureDesc).includes(spec)) {
    score += 0.05
  }
  if (MATERIAL_TYPES.has(item.itemType ?? '')) {
    score += 0.03
  }
  if (unitFamily(material.unit) && unitFamily(material.unit) === unitFamily(item.unit)) {
    score += 0.02
  }
  return Math.max(0, Math.min(1, score))
}

/** 手工匹配弹窗排序用：材料与清单行的匹配得分，越高越符合 */
export function matchScore(material: DrawingMaterial, item: BoqItemVO): number {
  return scorePair(material, item)
}

/**
 * 双向比对：每行图纸材料取分数最高的清单行，反过来记录每个清单行最贴近的图纸材料，
 * 两张表各自展示对侧的数量，便于人工按行核对。
 * 未达阈值的清单行同时记下「最近候选」，界面上能直接看出是没这张表，还是差在型号上。
 */
export function compareDrawingWithBoq(materials: DrawingMaterial[], boqItems: BoqItemVO[]): ComparisonResult {
  const rows: DrawingRow[] = materials.map((item) => ({ ...item }))
  const byBoqId = new Map<number, DrawingRow>()
  const nearestByBoqId = new Map<number, NearestMatch>()

  rows.forEach((row) => {
    let best: { item: BoqItemVO; score: number } | null = null
    boqItems.forEach((item) => {
      const score = scorePair(row, item)
      if (score > 0) {
        const nearest = nearestByBoqId.get(item.id)
        if (!nearest || score > nearest.score) {
          nearestByBoqId.set(item.id, { row, score })
        }
      }
      if (best && score <= best.score) {
        return
      }
      best = { item, score }
    })
    const hit = best as { item: BoqItemVO; score: number } | null
    if (!hit || hit.score < MATCH_THRESHOLD) {
      return
    }
    link(row, hit.item, hit.score)

    const previous = byBoqId.get(hit.item.id)
    if (!previous || (row.score ?? 0) > (previous.score ?? 0)) {
      byBoqId.set(hit.item.id, row)
    }
  })

  const matched = rows.filter((row) => row.match).length
  return { rows, byBoqId, nearestByBoqId, matched, unmatched: rows.length - matched }
}

/** 把图纸材料行挂到清单行上，并计算数量差异（自动/手工匹配共用） */
function link(row: DrawingRow, item: BoqItemVO, score: number) {
  // 名称完全一致才算「名称一致」，其余都按「相似关联」提示人工确认
  const sameName = normalizeText(row.name) === normalizeText(item.itemName)
  row.match = item
  row.score = sameName ? 1 : score
  row.level = sameName ? 1 : 2

  const factor = unitFactor(item.unit, row.unit)
  if (factor === null) {
    row.diff = null
    row.diffRate = null
  } else {
    const boqQuantity = (item.quantity ?? 0) * factor
    row.diff = round(row.quantity - boqQuantity)
    row.diffRate = boqQuantity === 0 ? null : round(((row.quantity - boqQuantity) / boqQuantity) * 100)
  }
}

/** 人工匹配结论：MATCH 指定图纸材料；UNMATCH 取消匹配（该清单行不再关联任何材料） */
export interface MatchOverride {  boqItemId: number
  drawingKey?: string | null
  manualType: 'MATCH' | 'UNMATCH'
}

/**
 * 在自动匹配结果上叠加人工结论：手工指定/取消的行以人工为准，其余保持自动结果。
 * 手工匹配的材料本身如果原来挂在别的清单行上，那条关联一并撤掉，避免一张图对两行。
 */
export function applyMatchOverrides(
  result: ComparisonResult,
  boqItems: BoqItemVO[],
  overrides: MatchOverride[],
): ComparisonResult {
  if (!overrides.length) {
    return result
  }
  const itemById = new Map(boqItems.map((item) => [item.id, item]))
  const rows = result.rows.map((row) => ({ ...row }))
  const rowByKey = new Map(rows.map((row) => [row.key, row]))
  const byBoqId = new Map(result.byBoqId)

  const reset = (row: DrawingRow) => {
    row.match = undefined
    row.level = undefined
    row.score = undefined
    row.diff = null
    row.diffRate = null
    row.manual = false
  }

  overrides.forEach((override) => {
    const item = itemById.get(override.boqItemId)
    if (!item) {
      return
    }
    const autoRow = byBoqId.get(item.id)
    if (autoRow) {
      const row = rowByKey.get(autoRow.key)
      if (row) {
        reset(row)
      }
    }
    byBoqId.delete(item.id)
    if (override.manualType !== 'MATCH' || !override.drawingKey) {
      return
    }
    const row = rowByKey.get(override.drawingKey)
    if (!row) {
      return
    }
    if (row.match && row.match.id !== item.id) {
      byBoqId.delete(row.match.id)
    }
    link(row, item, 1)
    row.manual = true
    byBoqId.set(item.id, row)
  })

  const matched = rows.filter((row) => row.match).length
  return { rows, byBoqId, nearestByBoqId: result.nearestByBoqId, matched, unmatched: rows.length - matched }
}

/**
 * 数量对比结论：一致 / 图纸偏大 / 图纸偏小 / 无法比较（单位不可换算）。
 *
 * 判定「一致」要同时满足：偏差 < 0.5% **且** 绝对差 < 0.5 个计量单位。
 * 只看百分比会把大数量下的真实差异吞掉——352 米改成 353 米只差 0.28%，
 * 但那确实是 1 米，改完数量界面却还写「数量一致」，看着像没同步。
 */
export function diffState(
  rate?: number | null,
  diff?: number | null,
): 'CONSISTENT' | 'MORE' | 'LESS' | 'NA' {
  if (rate === null || rate === undefined) {
    return 'NA'
  }
  const absDiff = diff === null || diff === undefined ? 0 : Math.abs(diff)
  if (Math.abs(rate) < 0.5 && absDiff < 0.5) {
    return 'CONSISTENT'
  }
  return rate > 0 ? 'MORE' : 'LESS'
}

/** 保留 6 位：沟千米这类小数量只留 3 位会把 0.0058 进位成 0.006，造成假的偏差 */
function round(value: number): number {
  return Math.round(value * 1e6) / 1e6
}
