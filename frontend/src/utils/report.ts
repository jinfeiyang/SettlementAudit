/** 报告正文排版：把一段文字拆成「普通段落 / 分条罗列」，两类用不同的缩进规则 */

export interface ReportTextLine {
  text: string
  /** 分条行（1. xxx / 一、xxx / （1）xxx / - xxx / 第X条）不套首行缩进 */
  list: boolean
}

/** 分条序号：阿拉伯数字、括号数字、中文序号、项目符号、条款序号 */
const ENUM_PATTERN = /^(?:\d{1,2}[.、)）]|[（(]\d{1,2}[)）]|[一二三四五六七八九十]+[、.]|[-•·*]|第[一二三四五六七八九十百]+[条章节项])/

/**
 * 一段正文按换行拆行；同一行里塞了多个分条（「…结论。2. 方法：…」）时，
 * 在「空格 + 序号」或「句号 + 序号」处再拆一次，
 * 但序号后紧跟数字的不拆（1.5 元、0.5m 这类小数不能被当成第 1 条）。
 */
export function reportTextLines(raw?: string | null): ReportTextLine[] {
  if (!raw) {
    return []
  }
  return raw
    .split(/\r?\n/)
    .flatMap((line) => line.split(/(?<=\s)(?=\d{1,2}[.、](?!\d))|(?<=[。；;])\s*(?=\d{1,2}[.、](?!\d))/))
    .map((line) => line.trim())
    .filter((line) => line !== '')
    .map((line) => ({ text: line, list: ENUM_PATTERN.test(line) }))
}
