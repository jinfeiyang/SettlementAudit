import type { BoqItemVO } from '@/api'
import {
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
}

/** 跨图纸汇总后的一行图纸清单 */
export interface DrawingMaterial {
  key: string
  name: string
  spec: string
  unit: string
  quantity: number
  fileCount: number
  files: string[]
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

/** 低于该分数视为没对上，宁可显示「未匹配」也不乱配 */
const MATCH_THRESHOLD = 0.6

const MATERIAL_TYPES = new Set(['MATERIAL', 'SUPPLY_MATERIAL', 'SUPPLY_EQUIPMENT'])

/**
 * 汇总多张图纸的材料表：
 * 名称 + 规格 + 单位一致视为同一种材料，数量相加，来源图纸去重。
 */
export function mergeDrawingMaterials(
  results: { fileName: string; summary?: DrawingMaterialItem[] }[],
): DrawingMaterial[] {
  const merged = new Map<string, DrawingMaterial>()
  results.forEach(({ fileName, summary }) => {
    ;(summary ?? []).forEach((item) => {
      const name = (item.name ?? '').trim()
      const quantity = Number(item.quantity)
      if (!name || Number.isNaN(quantity)) {
        return
      }
      const spec = (item.spec ?? '').trim()
      const unit = (item.unit ?? '').trim()
      const key = `${normalizeText(name)}|${normalizeText(spec)}|${normalizeUnit(unit)}`
      const hit = merged.get(key)
      if (hit) {
        hit.quantity = round(hit.quantity + quantity)
        if (!hit.files.includes(fileName)) {
          hit.files.push(fileName)
          hit.fileCount = hit.files.length
        }
        return
      }
      merged.set(key, { key, name, spec, unit, quantity: round(quantity), fileCount: 1, files: [fileName] })
    })
  })
  return [...merged.values()].sort((a, b) => b.quantity - a.quantity || a.name.localeCompare(b.name, 'zh-CN'))
}

/**
 * 单对「图纸材料 → 清单行」的匹配分（0~1）。
 * 先用硬规则排除明显不是同一件的：型号参数冲突（48芯 vs 24芯）、量纲冲突（米 vs 个）。
 * 再按名称相似度给基础分，规格与清单特征吻合、物资行、单位口径一致分别小幅加分。
 */
function scorePair(material: DrawingMaterial, item: BoqItemVO): number {
  const drawingName = normalizeText(material.name)
  const boqName = normalizeText(item.itemName)
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
    base = similarity(material.name, item.itemName)
  }
  // 「ODF框」对「ODF架」这类只差一个字的口径写法，双字切分算不出相似度，用字符重合度兜底
  const overlap = charOverlap(material.name, item.itemName)
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
    // 名称完全一致才算「名称一致」，其余都按「相似关联」提示人工确认
    const sameName = normalizeText(row.name) === normalizeText(hit.item.itemName)
    row.match = hit.item
    row.score = sameName ? 1 : hit.score
    row.level = sameName ? 1 : 2

    const factor = unitFactor(hit.item.unit, row.unit)
    if (factor === null) {
      row.diff = null
      row.diffRate = null
    } else {
      const boqQuantity = (hit.item.quantity ?? 0) * factor
      row.diff = round(row.quantity - boqQuantity)
      row.diffRate = boqQuantity === 0 ? null : round(((row.quantity - boqQuantity) / boqQuantity) * 100)
    }

    const previous = byBoqId.get(hit.item.id)
    if (!previous || (row.score ?? 0) > (previous.score ?? 0)) {
      byBoqId.set(hit.item.id, row)
    }
  })

  const matched = rows.filter((row) => row.match).length
  return { rows, byBoqId, nearestByBoqId, matched, unmatched: rows.length - matched }
}

function round(value: number): number {
  return Math.round(value * 1000) / 1000
}
