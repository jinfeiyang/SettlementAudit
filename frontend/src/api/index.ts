import request, { download } from './request'

export interface PageResult<T> {
  total: number
  page: number
  size: number
  records: T[]
}

export interface UserVO {
  id: number
  username: string
  realName?: string
  roleCode: string
  roleName: string
}

export interface ProjectVO {
  id: number
  projectNo: string
  projectName: string
  projectManager?: string
  engType?: string
  engTypeName?: string
  contractNo?: string
  orderNo?: string
  builderUnit?: string
  supervisorUnit?: string
  constructionFee?: number
  city?: string
  settleAmount?: number
  status: string
  statusName: string
  remark?: string
  createTime?: string
  updateTime?: string
}

export interface FileVO {
  id: number
  projectId: number
  fileType: string
  fileName: string
  fileSize?: number
  parseStatus: string
  parseStatusName: string
  parseRate?: number
  parseMessage?: string
  createTime?: string
}

export interface Overview {
  projectId: number
  projectName: string
  status: string
  statusName: string
  fileCount: number
  boqCount: number
  issueTotal: number
  issueHigh: number
  issueMedium: number
  issueLow: number
  issuePending: number
  issueConfirmed: number
  issueRejected: number
  settleAmount?: number
  boqTotal?: number
  checkAmount?: number
  hitRate?: number
  feeDeviation?: number
  reportId?: number
  reportStatus?: string
  lastCheckTime?: string
}

export interface BoqItemVO {
  id: number
  projectId: number
  rowNo?: number
  /** QUOTA 工程量 / MATERIAL 乙供材料 / SUPPLY_MATERIAL 甲供材料 / SUPPLY_EQUIPMENT 甲供设备 */
  itemType?: string
  itemTypeName?: string
  sourceSheet?: string
  itemCode?: string
  itemName: string
  featureDesc?: string
  unit?: string
  quantity?: number
  unitPrice?: number
  totalPrice?: number
  stdItemCode?: string
  stdItemName?: string
  engType?: string
  matchQuotaCode?: string
  matchQuotaName?: string
  matchScore?: number
  matchLevel?: number
}

/** 图纸上的一条文字标注（含坐标与所属图框），用于按段名回查图纸页 */
export interface DrawingTextLabel {
  text: string
  x: number
  y: number
  layer?: string
  /** 图框合成序号，未落入图框时为 null */
  frame: number | null
  /** 图签张号 / 图名 */
  sheet?: string | null
  title?: string | null
  /** 该页（图框）范围，定位时用 */
  bbox?: { minX: number; minY: number; maxX: number; maxY: number } | null
}

/** 表七光缆施工基准价逐段明细（B4 重复计费数据源） */
export interface FixedSegmentVO {
  id: number
  rowNo?: number
  segmentName?: string
  coreCount?: number
  length?: number
  amount?: number
  dedupKey?: string
  /** 0 无重复；>0 同组为疑似重复段 */
  dupGroup: number
  /** REVERSED 起止点反转 / SIMILAR 名称高度相似 */
  dupType?: string
}

/** 手工匹配结论：MATCH 指定图纸材料 / UNMATCH 取消匹配 */
export interface MatchOverrideVO {
  boqItemId: number
  drawingKey?: string | null
  manualType: 'MATCH' | 'UNMATCH'
}

export interface IssueVO {
  id: number
  projectId: number
  projectName?: string
  boqItemId?: number
  issueType: string
  issueTypeName: string
  level: number
  levelName: string
  ruleCode?: string
  refType?: string
  refValue?: string
  title: string
  detail?: string
  aiNote?: string
  aiModel?: string
  /** 归因依据 JSON：清单行/定额子目/取费规则/判定口径 */
  aiReferences?: string
  reviewStatus: string
  reviewStatusName: string
  reviewTime?: string
  reviewComment?: string
  rowNo?: number
  itemCode?: string
  itemName?: string
  unit?: string
  quantity?: number
  unitPrice?: number
  totalPrice?: number
}

export interface IssueStats {
  total: number
  high: number
  medium: number
  low: number
  pending: number
  confirmed: number
  rejected: number
}

export interface AsyncTaskVO {
  id: number
  projectId?: number
  taskType: string
  status: string
  progress: number
  total: number
  message?: string
  resultJson?: string
  createTime?: string
  updateTime?: string
}

export interface ReportSection {
  title: string
  type: 'text' | 'table'
  text?: string
  columns?: string[]
  rows?: string[][]
}

export interface ReportContent {
  sections: ReportSection[]
}

export interface ReportVO {
  id: number
  projectId: number
  projectName?: string
  title: string
  content?: string
  aiModel?: string
  status: string
  statusName: string
  version: number
  confirmTime?: string
  createTime?: string
  updateTime?: string
}

export const authApi = {
  login: (data: { username: string; password: string }) =>
    request.post<{ token: string; user: UserVO }>('/auth/login', data),
  me: () => request.get<UserVO>('/auth/me'),
}

export const projectApi = {
  page: (params: Record<string, unknown>) => request.get<PageResult<ProjectVO>>('/projects', { params }),
  create: (data: Record<string, unknown>) => request.post<ProjectVO>('/projects', data),
  update: (id: number, data: Record<string, unknown>) => request.put<ProjectVO>(`/projects/${id}`, data),
  remove: (id: number) => request.delete<void>(`/projects/${id}`),
  get: (id: number) => request.get<ProjectVO>(`/projects/${id}`),
  overview: (id: number) => request.get<Overview>(`/projects/${id}/overview`),
  transit: (id: number, target: string) => request.post<void>(`/projects/${id}/status?target=${target}`),
  files: (id: number) => request.get<FileVO[]>(`/projects/${id}/files`),
  upload: (id: number, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request.post<FileVO>(`/projects/${id}/files`, form)
  },
  parseDrawingMeta: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request.post<{
      fileName: string
      projectInfo: { label: string; value: string }[]
      summary: string
    }>('/projects/parse-drawing', form)
  },
  parseFile: (fileId: number) => request.post<void>(`/files/${fileId}/parse`),
  parseFileSync: (fileId: number) => request.post<FileVO>(`/files/${fileId}/parse-sync`),
  deleteFile: (fileId: number) => request.delete<void>(`/files/${fileId}`),
  downloadFile: (fileId: number, name: string) => download(`/files/${fileId}/download`, name),
  parseResult: (fileId: number) => request.get<Record<string, any>>(`/files/${fileId}/result`),
  /** 人工修正图纸上某材料某分项的数量，保存后随解析结果一起返回 */
  saveQuantityFix: (fileId: number, materialKey: string, partKey: string, value: number) =>
    request.put<void>(`/files/${fileId}/quantity-fix`, { materialKey, partKey, value }),
  deleteQuantityFix: (fileId: number, materialKey: string, partKey: string) =>
    request.delete<void>(`/files/${fileId}/quantity-fix`, { params: { materialKey, partKey } }),
  /**
   * 图纸预览：dxf 为交互渲染数据（前端解析），svg 为服务端渲染快照（大图降级）。
   * 带时间戳是为了绕开浏览器对 GET 的强缓存：出图结果会随后处理逻辑变化，
   * 命中缓存会让人误以为改动没生效。
   */
  drawing: (fileId: number, format: 'dxf' | 'svg' = 'dxf') =>
    request.get<Blob>(`/files/${fileId}/drawing`, {
      params: { format, _t: Date.now() },
      responseType: 'blob',
    }),
  boqTemplate: () => download('/projects/0/boq/template', '工程量清单导入模板.xlsx'),
}

export const boqApi = {
  page: (projectId: number, params: Record<string, unknown>) =>
    request.get<PageResult<BoqItemVO>>(`/projects/${projectId}/boq`, { params }),
  all: (projectId: number) => request.get<BoqItemVO[]>(`/projects/${projectId}/boq/all`),
  byRow: (projectId: number, rowNo: number) =>
    request.get<BoqItemVO | null>(`/projects/${projectId}/boq/row/${rowNo}`),
  clear: (projectId: number) => request.delete<void>(`/projects/${projectId}/boq`),
  /** 表七光缆施工基准价逐段明细（含重复段标记） */
  fixedSegments: (projectId: number) =>
    request.get<FixedSegmentVO[]>(`/projects/${projectId}/boq/fixed-segments`),
  /** 清单行的人工图纸匹配结论 */
  drawingMatches: (projectId: number) =>
    request.get<MatchOverrideVO[]>(`/projects/${projectId}/boq/drawing-matches`),
  /** 保存人工匹配：manualType=UNMATCH 表示取消匹配 */
  saveDrawingMatch: (
    projectId: number,
    boqItemId: number,
    drawingKey?: string | null,
    manualType: 'MATCH' | 'UNMATCH' = 'MATCH',
  ) =>
    request.post<void>(`/projects/${projectId}/boq/drawing-matches`, { boqItemId, drawingKey, manualType }),
  /** 恢复自动匹配 */
  resetDrawingMatch: (projectId: number, boqItemId: number) =>
    request.delete<void>(`/projects/${projectId}/boq/drawing-matches/${boqItemId}`),
  template: (projectId: number) =>
    download(`/projects/${projectId}/boq/template`, '工程量清单导入模板.xls'),
}

export const checkApi = {
  start: (projectId: number) => request.post<{ taskId: number }>(`/projects/${projectId}/check`),
  runSync: (projectId: number) => request.post<AsyncTaskVO>(`/projects/${projectId}/check/sync`),
  last: (projectId: number) => request.get<AsyncTaskVO | null>(`/projects/${projectId}/check/last`),
}

export const taskApi = {
  get: (id: number) => request.get<AsyncTaskVO>(`/tasks/${id}`),
}

export const issueApi = {
  page: (params: Record<string, unknown>) => request.get<PageResult<IssueVO>>('/issues', { params }),
  stats: (projectId?: number) =>
    request.get<IssueStats>('/issues/stats', { params: projectId ? { projectId } : {} }),
  get: (id: number) => request.get<IssueVO>(`/issues/${id}`),
  locate: (id: number) => request.get<IssueVO>(`/issues/${id}/locate`),
  review: (id: number, data: { action: string; comment?: string }) =>
    request.post<void>(`/issues/${id}/review`, data),
  batchReview: (data: { ids: number[]; action: string; comment?: string }) =>
    request.post<number>('/issues/batch-review', data),
  export: (params: Record<string, unknown>) => download('/issues/export', '预审疑点清单.xlsx', params),
}

export const reportApi = {
  generate: (projectId: number) => request.post<ReportVO>(`/projects/${projectId}/report`),
  list: (projectId?: number) =>
    request.get<ReportVO[]>('/reports', { params: projectId ? { projectId } : {} }),
  get: (id: number) => request.get<ReportVO>(`/reports/${id}`),
  update: (id: number, data: { title?: string; content?: string }) =>
    request.put<ReportVO>(`/reports/${id}`, data),
  confirm: (id: number) => request.post<ReportVO>(`/reports/${id}/confirm`),
  remove: (id: number) => request.delete<void>(`/reports/${id}`),
  exportWord: (id: number, name: string) => download(`/reports/${id}/export`, `${name}.docx`),
}

export const ruleApi = {
  quotaPage: (params: Record<string, unknown>) =>
    request.get<PageResult<any>>('/rules/quota', { params }),
  quotaCreate: (data: Record<string, unknown>) => request.post('/rules/quota', data),
  quotaUpdate: (id: number, data: Record<string, unknown>) => request.put(`/rules/quota/${id}`, data),
  quotaDelete: (id: number) => request.delete(`/rules/quota/${id}`),
  quotaImport: (file: File) => uploadFile('/rules/quota/import', file),
  quotaTemplate: () => download('/rules/quota/template', '定额子目导入模板.xlsx'),
  feePage: (params: Record<string, unknown>) => request.get<PageResult<any>>('/rules/fee', { params }),
  feeCreate: (data: Record<string, unknown>) => request.post('/rules/fee', data),
  feeUpdate: (id: number, data: Record<string, unknown>) => request.put(`/rules/fee/${id}`, data),
  feeDelete: (id: number) => request.delete(`/rules/fee/${id}`),
  feeImport: (file: File) => uploadFile('/rules/fee/import', file),
  feeTemplate: () => download('/rules/fee/template', '取费规则导入模板.xlsx'),
}

function uploadFile(url: string, file: File) {
  const form = new FormData()
  form.append('file', file)
  return request.post<{ rows: number; imported: number; skipped: number; message: string }>(url, form)
}

export const dashboardApi = {
  stats: () => request.get<Record<string, any>>('/dashboard/stats'),
}

export interface AiSettings {
  llmAttributionEnabled: boolean
  reportAiEnabled: boolean
}

export const settingsApi = {
  get: () => request.get<AiSettings>('/settings'),
  update: (data: AiSettings) => request.put<AiSettings>('/settings', data),
}
