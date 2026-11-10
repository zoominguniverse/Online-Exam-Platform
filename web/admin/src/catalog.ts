import { read } from '../../shared/api'

export type Page<T> = { items: T[]; total: number; page: number; pageSize: number }
export type Subject = { id: string; gradeId: string; name: string; version: number }
export type Type = 'SINGLE' | 'MULTIPLE' | 'TRUE_FALSE' | 'GAP' | 'SHORT'
export type QuestionSummary = {
  id: string; subjectId: string; type: Type; score: string; difficulty: number; version: number; stemPreview: string
}
export type Question = QuestionSummary & {
  stemHtml: string
  options: { id: string; text: string }[]
  answer: { optionIds?: string[] | null; gaps?: string[] | null; text?: string | null }
  explanationHtml: string
  archived: boolean
}
export const types: Record<Type, string> = {
  SINGLE: '单选题', MULTIPLE: '多选题', TRUE_FALSE: '判断题', GAP: '填空题', SHORT: '简答题',
}

export async function allSubjects() {
  const result: Subject[] = []
  for (let page = 1; ; page++) {
    const data = await read<Page<Subject>>('/admin/subjects?pageSize=100&page=' + page)
    result.push(...data.items)
    if (page * data.pageSize >= data.total || data.items.length < data.pageSize) return result
  }
}

export type PaperType = 'FIXED' | 'TIMED' | 'TASK'
export type PaperSummary = {
  id: string; subjectId: string; type: PaperType; name: string; version: number
  draftId: string | null; publishedId: string | null; archived: boolean
}
export type PaperView = {
  paper: Omit<PaperSummary, 'name'>
  content: {
    id: string; revision: number; name: string; durationMinutes: number
    opensAt: string | null; closesAt: string | null; publishedAt: string | null
  }
  items: { position: number; groupPosition: number; groupName: string; questionId: string; questionVersion: number; score: string }[]
  totalScore: string
}
export const paperTypes: Record<PaperType, string> = { FIXED: '固定卷', TIMED: '时段卷', TASK: '任务卷' }
