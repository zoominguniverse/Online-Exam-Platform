import { createHash, randomUUID } from 'node:crypto';
import { Failure, owned, notFound, cents, decimal, validateAnswers } from '../domain/contracts.ts';
import type { Principal, Repository, Row, ListQuery } from '../domain/contracts.ts';

function canonical(value: any): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') return `{${Object.keys(value).sort().map(k => `${JSON.stringify(k)}:${canonical(value[k])}`).join(',')}}`;
  return JSON.stringify(value);
}
export class Exams {
  private repo: Repository;
  private now: () => Date;
  constructor(repo: Repository, now: () => Date = () => new Date()) { this.repo = repo; this.now = now; }
  async write(actor: Principal, method: string, path: string, key: string, body: Row, action: (r: Repository) => Promise<Row>): Promise<Row> {
    const scope = `${actor.id}:${method}:${path}`;
    const digest = createHash('sha256').update(canonical(body)).digest('hex');
    return this.repo.transaction(async r => {
      await r.lockKey(`${scope}:${key}`);
      const previous = await r.idempotency(scope, key);
      if (previous && new Date(previous.expires_at) > this.now()) {
        if (previous.digest !== digest) throw new Failure('BIZ_IDEMPOTENCY_CONFLICT', '幂等键已用于其他内容，请使用新的键。');
        return previous.result;
      }
      const result = await action(r);
      if (!result.rejected) await r.remember(scope, key, digest, result, this.now());
      return result;
    });
  }
  private view(row: Row, answers: Row[], at = this.now()) {
    row = this.effective(row,at);
    const snapshot = structuredClone(row.snapshot);
    if (row.status === 'running') for (const item of snapshot.items) { delete item.referenceAnswer; delete item.explanation; }
    return { id: row.id, paperVersionId: row.paper_version_id, taskId: row.task_id, status: row.status, version: row.version, startedAt: row.started_at, deadline: row.deadline, submittedAt: row.submitted_at, total: row.total, snapshot, answers };
  }
  private effective(row: Row,at: Date) {
    return row.status === 'running' && at >= new Date(row.deadline) ? { ...row, status: 'submitted', submitted_at: row.deadline } : row;
  }
  private async freeze(r: Repository, row: Row, requestId: string) {
    if (row.status === 'running' && this.now() >= new Date(row.deadline)) {
      row = await r.updateAttempt(row.id, row.version, { status: 'submitted', submittedAt: row.deadline });
      await r.audit('00000000-0000-4000-8000-000000000001', 'attempt.expired', row.id, requestId);
      await r.enqueue(row.owner_id, 'attempt.submitted', row.id);
    }
    return row;
  }
  async start(actor: Principal, sourceId: string, isTask: boolean, key: string, path: string, requestId: string) {
    return this.write(actor, 'POST', path, key, {}, async r => {
      const task = isTask ? await r.task(sourceId) : null;
      if (isTask && (!task || !(await r.eligible(sourceId, actor.id)))) throw notFound();
      if (isTask) {
        await r.lockKey(`task:${sourceId}:${actor.id}`);
        const previous = await r.taskAttempt(sourceId, actor.id);
        if (previous) return this.view(await this.freeze(r, previous, requestId), await r.answers(previous.id));
      }
      const paper = await r.paper(isTask ? task!.paper_id : sourceId);
      if (!paper || paper.status !== 'published' || (!isTask && paper.type === 'task') || (isTask && task!.status !== 'published')) throw notFound();
      const now = this.now();
      const window = isTask ? task! : paper;
      if ((isTask || paper.type === 'timed') && (now < new Date(window.start_at) || now >= new Date(window.end_at))) throw new Failure('BIZ_UNAVAILABLE', '当前不在允许作答的时段内，请查看有效时间。');
      const duration = isTask ? task!.snapshot.durationSeconds : paper.duration_seconds;
      const deadline = new Date(Math.min(now.getTime() + duration * 1000, (isTask || paper.type === 'timed') ? new Date(window.end_at).getTime() : Infinity));
      const row = await r.createAttempt({ id: randomUUID(), ownerId: actor.id, paperVersionId: isTask ? task!.paper_version_id : paper.paper_version_id, taskId: isTask ? sourceId : null, snapshot: isTask ? task!.snapshot : paper.snapshot, now, deadline });
      await r.audit(actor.id, 'attempt.started', row.id, requestId);
      return this.view(row, []);
    });
  }
  async detail(actor: Principal, id: string, requestId: string) {
    const at=this.now();
    const row = await this.repo.attempt(id); owned(row, actor);
    return this.view(row, await this.repo.answers(id),at);
  }
  async answers(actor: Principal, id: string, body: Row, key: string, submit: boolean, requestId: string) {
    const result = await this.write(actor, submit ? 'POST' : 'PUT', `/api/v1/attempts/${id}/${submit ? 'submissions' : 'answers'}`, key, body, async r => {
      let row = await r.attempt(id); owned(row, actor);
      if (row.status === 'running' && this.now() >= new Date(row.deadline)) {
        row = await this.freeze(r, row, requestId);
        return { rejected: true, attemptId: id };
      }
      if (row.status !== 'running') throw new Failure('BIZ_STATE_CONFLICT', '答卷已提交，请刷新查看记录。');
      if (row.version !== body.version) throw new Failure('BIZ_VERSION_CONFLICT', '答卷已更新，请刷新后重试。');
      validateAnswers(row.snapshot.items, body.answers);
      await r.saveAnswers(id, body.answers);
      row = await r.updateAttempt(id, body.version, { status: submit ? 'submitted' : 'running', submittedAt: submit ? this.now() : null });
      await r.audit(actor.id, submit ? 'attempt.submitted' : 'attempt.saved', id, requestId);
      if (submit) await r.enqueue(actor.id, 'attempt.submitted', id);
      return this.view(row, await r.answers(id));
    });
    // Freeze must commit even though the late payload is rejected.
    if (result.rejected) throw new Failure('BIZ_DEADLINE_REACHED', '截止时间已到，已保存的草稿已冻结，请查看答卷。', { attemptId: id });
    return result;
  }
  async grade(actor: Principal, id: string, body: Row, key: string, requestId: string) {
    return this.write(actor, 'POST', `/api/v1/attempts/${id}/gradings`, key, body, async r => {
      let row = await r.attempt(id); owned(row, actor);
      if (row.version !== body.version) throw new Failure('BIZ_VERSION_CONFLICT', '答卷已更新，请刷新查看最新结果。');
      row = await this.freeze(r, row, requestId);
      if (row.status !== 'submitted') throw new Failure('BIZ_STATE_CONFLICT', '仅待批改答卷可确认评分，请刷新查看。');
      const items = row.snapshot.items;
      if (body.scores.length !== items.length || new Set(body.scores.map((s: Row) => s.itemId)).size !== items.length) throw new Failure('PARAM_INVALID', '须为每道题且仅提供一次评分。');
      let total = 0;
      for (const score of body.scores) {
        const item = items.find((i: Row) => i.id === score.itemId);
        if (!item || cents(score.score) > cents(item.maxScore)) throw new Failure('PARAM_INVALID', '题目不存在或评分超过满分。');
        total += cents(score.score);
      }
      if (total > 99999999) throw new Failure('PARAM_INVALID', '试卷总分超过支持上限，请联系管理员。');
      await r.createGrading(row, body.scores, decimal(total), actor.id);
      const updated = await r.updateAttempt(id, row.version, { status: 'completed', submittedAt: row.submitted_at, total: decimal(total) });
      await r.audit(actor.id, 'attempt.graded', id, requestId);
      await r.enqueue(actor.id, 'attempt.graded', id);
      return this.view(updated, await r.answers(id));
    });
  }
  async paper(actor: Principal, id: string) {
    const paper = await this.repo.paper(id);
    if (!paper || paper.status !== 'published' || paper.type === 'task') throw notFound();
    const snapshot = structuredClone(paper.snapshot);
    for (const item of snapshot.items) { delete item.referenceAnswer; delete item.explanation; }
    return { id: paper.id, type: paper.type, startAt: paper.start_at, endAt: paper.end_at, durationSeconds: paper.duration_seconds, snapshot };
  }
  async list(actor: Principal, kind: 'papers' | 'attempts' | 'mistakes', query: ListQuery, requestId: string) {
    const at=this.now();
    const result = await this.repo.list(kind, actor.id, query, at);
    if (kind === 'attempts') result.items = await Promise.all(result.items.map(async (row: Row) => this.view(row, await this.repo.answers(row.id),at)));
    if (kind === 'papers') result.items = result.items.map((row: Row) => ({ id: row.id, title: row.title, type: row.type, startAt: row.start_at, endAt: row.end_at, durationSeconds: row.duration_seconds }));
    if (kind === 'mistakes') result.items = result.items.map((row: Row) => ({ id: row.id, questionId: row.question_id, lastAttemptId: row.last_attempt_id, snapshot: row.snapshot }));
    return result;
  }
  async freezeDue(ids: string[], requestId: string) {
    for (const id of ids) await this.repo.transaction(async r => { const row = await r.attempt(id); if (row) await this.freeze(r, row, requestId); });
  }
  async mistake(actor: Principal,id: string) {
    const row=await this.repo.mistake(id); owned(row,actor);
    return {id:row.id,questionId:row.question_id,lastAttemptId:row.last_attempt_id,snapshot:row.snapshot};
  }
}
