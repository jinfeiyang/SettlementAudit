/** 展示格式化工具 */

export function money(value?: number | null): string {
  if (value === null || value === undefined || Number.isNaN(Number(value))) {
    return '-'
  }
  return Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

export function percent(value?: number | null, digits = 2): string {
  if (value === null || value === undefined || Number.isNaN(Number(value))) {
    return '-'
  }
  return `${Number(value).toFixed(digits)}%`
}

export function decimalPercent(value?: number | null, digits = 2): string {
  if (value === null || value === undefined || Number.isNaN(Number(value))) {
    return '-'
  }
  return `${(Number(value) * 100).toFixed(digits)}%`
}

export function datetime(value?: string | null): string {
  if (!value) {
    return '-'
  }
  return value.replace('T', ' ').slice(0, 16)
}

export function fileSize(bytes?: number | null): string {
  if (!bytes) {
    return '-'
  }
  const units = ['B', 'KB', 'MB', 'GB']
  let value = Number(bytes)
  let index = 0
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024
    index += 1
  }
  return `${value.toFixed(index === 0 ? 0 : 1)} ${units[index]}`
}

export function levelTag(level?: number): 'danger' | 'warning' | 'info' {
  if (level === 3) {
    return 'danger'
  }
  return level === 2 ? 'warning' : 'info'
}

export function statusTag(status?: string): 'success' | 'warning' | 'info' | 'danger' | 'primary' {
  switch (status) {
    case 'CHECKED':
    case 'CONFIRMED':
    case 'SUCCESS':
      return 'success'
    case 'CHECKING':
    case 'PARSING':
    case 'RUNNING':
      return 'warning'
    case 'FAILED':
      return 'danger'
    case 'REPORTED':
    case 'IMPORTED':
      return 'primary'
    default:
      return 'info'
  }
}

export function downloadName(base: string, suffix: string): string {
  return `${base}${suffix}`
}
