export type Answer = { optionId?: string; optionIds?: string[]; texts?: string[]; text?: string }
export type Question = { questionId: string; position: number; questionVersion: number; score: string;
  type: string; stemHtml: string; options: { id: string; text: string }[] }
export type Attempt = { id: string; version: number; status: string; serverNow: string; deadlineAt: string;
  automaticScore: string | null; finalScore: string | null; frozenAt: string | null;
  questions: Question[]; savedAnswers: { questionId: string; answer: Answer }[] }
export type ReviewItem = Question & { answer: Answer | null; referenceAnswer: { optionIds?: string[]; gaps?: string[]; text?: string };
  explanationHtml: string; awardedScore: string | null; subjective: boolean; empty: boolean }
export type Review = { attempt: Attempt; items: ReviewItem[]; gradingSource: string; metadata: { paperName:string;username:string;startedAt:string;elapsedSeconds:number;confirmedAt:string|null } }
export type Page<T> = { items: T[]; total: number; page: number; pageSize: number }
export const statuses: Record<string,string> = { IN_PROGRESS: '作答中', WAIT_SELF_GRADE: '待自行批改', COMPLETED: '已完成', NOT_STARTED: '未开始' }
export const time = (value: string | null) => value ? new Intl.DateTimeFormat('zh-CN', { timeZone:'Asia/Hong_Kong',dateStyle:'medium',timeStyle:'medium' }).format(new Date(value)) : '—'
export function displayAnswer(item: Pick<Question,'type'|'options'>,value: Answer | null) {
  if (!value) return '未作答'
  if (value.text !== undefined) return value.text || '未作答'
  if (value.texts) return value.texts.join('；') || '未作答'
  return (value.optionIds || [value.optionId]).map(id => item.type === 'TRUE_FALSE' ? (id === 'true' ? '正确' : '错误')
    : item.options.find(option=>option.id===id)?.text || id).join('、')
}
export function reference(item: ReviewItem) {
  const answer = item.referenceAnswer
  return answer.text ?? answer.gaps?.join('；') ?? displayAnswer(item,{ optionIds:answer.optionIds })
}
export function answered(value?: Answer) {
  return Boolean(value && (value.optionId || value.optionIds?.length || value.text?.trim() || value.texts?.some(text=>text.trim())))
}
