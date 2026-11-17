/**
 * 文本归一化与相似度工具。
 * 口径与后端 TextUtil 保持一致：同一份「图纸材料名 vs 清单名称」在前后端算出的强弱应当一致，
 * 否则界面上的「相似关联」会和核查结论对不上。
 */

const STOP_WORDS = new Set(['的', '及', '与', '和', '或', '等', '项', '工程', '安装', '施工', '制作', '敷设', '及其'])

/** 全角转半角 + 去空白 + 小写 */
export function normalizeText(raw?: string | null): string {
  if (!raw) {
    return ''
  }
  let out = ''
  for (const ch of raw) {
    const code = ch.charCodeAt(0)
    if (code >= 0xff01 && code <= 0xff5e) {
      out += String.fromCharCode(code - 0xfee0)
    } else if (code === 0x3000) {
      out += ' '
    } else {
      out += ch
    }
  }
  return out
    .replace(/[\s\u3000]+/g, '')
    .replace(/[（]/g, '(')
    .replace(/[）]/g, ')')
    .replace(/、/g, '')
    .toLowerCase()
    .trim()
}

/** 单位归一化：平方米->m2，米->m，百米/千米保留（换算校验用） */
export function normalizeUnit(unit?: string | null): string {
  const u = normalizeText(unit)
  switch (u) {
    case '平方米':
    case '平米':
    case '㎡':
    case 'm2':
      return 'm2'
    case '立方米':
    case 'm3':
    case '方':
      return 'm3'
    case '米':
    case 'm':
    case '延米':
      return 'm'
    case '百米':
    case '100m':
      return '百米'
    case '千米':
    case '公里':
    case 'km':
      return 'km'
    case '台':
    case '台套':
      return '台'
    case '个':
    case '只':
    case '件':
      return '个'
    case '套':
    case '组':
      return '套'
    case '吨':
    case 't':
      return '吨'
    case '公斤':
    case '千克':
    case 'kg':
      return 'kg'
    default:
      return u
  }
}

/**
 * 计量单位所属量纲。
 * 「架 / 台 / 个 / 套」都是计件口径，可以按 1:1 比数量；
 * 长度（米/百米/千米）和计件之间不能直接比，出现这类组合说明配错了行。
 */
export function unitFamily(unit?: string | null): string {
  const u = normalizeUnit(unit)
  switch (u) {
    case 'm':
    case '百米':
    case 'km':
      return 'length'
    case 'm2':
      return 'area'
    case 'm3':
      return 'volume'
    case '吨':
    case 'kg':
      return 'weight'
    case '个':
    case '台':
    case '套':
    case '架':
    case '组':
    case '块':
    case '面':
    case '只':
    case '件':
    case '条':
    case '根':
    case '处':
    case '端':
    case '芯':
    case '盘':
    case '卷':
    case '座':
      return 'count'
    default:
      return ''
  }
}

/**
 * 单位换算系数：把「第一个单位」的数量换算成「第二个单位」。
 * 例：unitFactor('米', '百米') = 0.01（100 米 = 1 百米），无法换算时返回 null。
 */
export function unitFactor(from?: string | null, to?: string | null): number | null {
  const a = normalizeUnit(from)
  const b = normalizeUnit(to)
  if (!a || !b) {
    return null
  }
  if (a === b) {
    return 1
  }
  // 计件口径互通：甲供设备常用「架」「套」，图纸材料表常写「台」「个」
  if (unitFamily(a) === 'count' && unitFamily(b) === 'count') {
    return 1
  }
  switch (`${a}>${b}`) {
    case 'm>百米':
      return 0.01
    case '百米>m':
      return 100
    case 'm>km':
      return 0.001
    case 'km>m':
      return 1000
    case '吨>kg':
      return 1000
    case 'kg>吨':
      return 0.001
    default:
      return null
  }
}

/** 量纲明确冲突（长度 vs 计件这类），用于直接判定「配错行」 */
export function unitsIncompatible(a?: string | null, b?: string | null): boolean {
  const fa = unitFamily(a)
  const fb = unitFamily(b)
  return Boolean(fa && fb && fa !== fb)
}

/** 型号里的关键参数：芯数、对数、端口、孔位、容量、截面、电压电流等 */
const FEATURE_PATTERNS: { label: string; re: RegExp }[] = [
  { label: '芯', re: /(\d+)\s*芯/g },
  { label: '对', re: /(\d+)\s*对/g },
  { label: '口', re: /(\d+)\s*(?:口|端口)/g },
  { label: '孔', re: /(\d+)\s*孔/g },
  { label: 'U', re: /(\d+)\s*u(?![a-z])/g },
  { label: '芯数/位数', re: /(\d+)\s*(?:位|路|通道)/g },
  { label: '截面', re: /(\d+(?:\.\d+)?)\s*(?:mm2|mm²|平方)/g },
  { label: 'A', re: /(\d+(?:\.\d+)?)\s*a(?![a-z])/g },
  { label: 'V', re: /(\d+(?:\.\d+)?)\s*v(?![a-z])/g },
  { label: 'W', re: /(\d+(?:\.\d+)?)\s*(?:kw|w)(?![a-z])/g },
  { label: 'dB', re: /(\d+(?:\.\d+)?)\s*db/g },
]

/**
 * 型号参数识别用的轻量归一化：只做全角转半角 + 小写，保留空格和连字符。
 * 不能直接用 normalizeText：它会把「GYTA53 24芯」粘成「gyta5324芯」，
 * 芯数就被识别成 5324，和图纸的 24 芯误判为不同型号。
 */
function normalizeLight(raw?: string | null): string {
  if (!raw) {
    return ''
  }
  let out = ''
  for (const ch of raw) {
    const code = ch.charCodeAt(0)
    if (code >= 0xff01 && code <= 0xff5e) {
      out += String.fromCharCode(code - 0xfee0)
    } else if (code === 0x3000) {
      out += ' '
    } else {
      out += ch
    }
  }
  return out.toLowerCase()
}

function featuresOf(text: string): Map<string, Set<string>> {
  const found = new Map<string, Set<string>>()
  const source = normalizeLight(text)
  FEATURE_PATTERNS.forEach(({ label, re }) => {
    re.lastIndex = 0
    let hit = re.exec(source)
    while (hit) {
      const bucket = found.get(label) ?? new Set<string>()
      bucket.add(hit[1])
      found.set(label, bucket)
      hit = re.exec(source)
    }
  })
  return found
}

/**
 * 型号参数是否相互矛盾：两侧都写了同一类参数（如芯数）但取值不同，直接判为不同产品。
 * 「OFD框48芯」撞「OFD框24芯」就是名称一样、芯数不一致，光看名称相似度会误配。
 */
export function featureConflicts(a?: string | null, b?: string | null): boolean {
  const left = featuresOf(a ?? '')
  if (!left.size) {
    return false
  }
  const right = featuresOf(b ?? '')
  if (!right.size) {
    return false
  }
  for (const [label, values] of left) {
    const other = right.get(label)
    if (!other) {
      continue
    }
    let shared = false
    values.forEach((value) => {
      if (other.has(value)) {
        shared = true
      }
    })
    if (!shared) {
      return true
    }
  }
  return false
}

const tokenCache = new Map<string, string[]>()

/** 关键词切分：中文双字 + 英文/数字词 */
export function tokens(raw?: string | null): string[] {
  const s = normalizeText(raw)
  const cached = tokenCache.get(s)
  if (cached) {
    return cached
  }
  const list: string[] = []
  for (const seg of s.split(/[^\u4e00-\u9fa5a-z0-9.]+/)) {
    if (!seg) {
      continue
    }
    if (/^[a-z0-9.]+$/.test(seg)) {
      list.push(seg)
      continue
    }
    for (let i = 0; i < seg.length - 1; i += 1) {
      const bi = seg.slice(i, i + 2)
      if (!STOP_WORDS.has(bi)) {
        list.push(bi)
      }
    }
    if (seg.length === 1) {
      list.push(seg)
    }
  }
  tokenCache.set(s, list)
  return list
}

/**
 * 字符重合度（0~1）：名称里「ODF框」和「ODF架」这类只差一个字的口径差异，
 * 双字切分算不出相似度（框/架 各成一个词），按字符重合度兜底才不会漏配。
 */
export function charOverlap(a?: string | null, b?: string | null): number {
  const left = normalizeText(a)
  const right = normalizeText(b)
  if (!left || !right) {
    return 0
  }
  const counts = new Map<string, number>()
  for (const ch of left) {
    counts.set(ch, (counts.get(ch) ?? 0) + 1)
  }
  let shared = 0
  for (const ch of right) {
    const remain = counts.get(ch) ?? 0
    if (remain > 0) {
      counts.set(ch, remain - 1)
      shared += 1
    }
  }
  return (2 * shared) / (left.length + right.length)
}

/** 双字 Jaccard 相似度（0~1） */
export function similarity(a?: string | null, b?: string | null): number {
  const sa = new Set(tokens(a))
  const sb = new Set(tokens(b))
  if (!sa.size || !sb.size) {
    return 0
  }
  let inter = 0
  sa.forEach((token) => {
    if (sb.has(token)) {
      inter += 1
    }
  })
  return inter / (sa.size + sb.size - inter)
}
