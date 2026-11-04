import { randomUUID } from 'node:crypto';
import type { Pool } from 'pg';
import { SYSTEM } from './postgres.ts';

// Synthetic development/test content. This function does not create credentials.
export async function seedExam(pool: Pool) {
  const client = await pool.connect();
  const gradeId = randomUUID(), subjectId = randomUUID();
  const items = [
    { type: 'single', title: '计算机的输入设备是？', options: ['显示器','键盘','打印机','音箱'], referenceAnswer: '键盘' },
    { type: 'multiple', title: '请选择输入设备。', options: ['键盘','鼠标','显示器'], referenceAnswer: '["键盘","鼠标"]' },
    { type: 'boolean', title: '1 字节等于 8 位。', options: [], referenceAnswer: 'true' },
    { type: 'fill', title: '定义网页内容结构的语言是____。', options: [], referenceAnswer: 'HTML' },
    { type: 'short', title: '请说明开启双重验证的作用。', options: [], referenceAnswer: '增加密码以外的验证，降低账号被盗风险。' },
  ].map(item => ({ ...item, id: randomUUID(), questionId: randomUUID(), questionVersionId: randomUUID(), maxScore: '20.00', explanation: '请对照参考答案自行确认得分。', subjectId }));
  const papers: Record<string, string> = {}, versions: Record<string, string> = {};
  try {
    await client.query('BEGIN');
    await client.query('INSERT INTO grades(id,title,created_by) VALUES($1,$2,$3)', [gradeId,`合成年级-${gradeId}`,SYSTEM]);
    await client.query('INSERT INTO subjects(id,title,created_by) VALUES($1,$2,$3)', [subjectId,'计算机基础',SYSTEM]);
    for (const item of items) {
      await client.query('INSERT INTO questions(id,subject_id,created_by) VALUES($1,$2,$3)', [item.questionId,subjectId,SYSTEM]);
      await client.query('INSERT INTO question_versions(id,question_id,version,content,created_by) VALUES($1,$2,1,$3,$4)', [item.questionVersionId,item.questionId,item,SYSTEM]);
    }
    for (const type of ['fixed','timed','task']) {
      const id = randomUUID(), versionId = randomUUID(); papers[type] = id; versions[type] = versionId;
      await client.query("INSERT INTO papers(id,title,type,status,duration_seconds,start_at,end_at,created_by) VALUES($1,$2,$3,'published',600,$4,$5,$6)", [id,'计算机基础练习',type,'2026-01-01T00:00:00Z','2027-01-01T00:00:00Z',SYSTEM]);
      const paperItems = items.map(item => ({ ...item, id: type === 'fixed' ? item.id : randomUUID() }));
      const snapshot = { title: '计算机基础练习', durationSeconds: 600, items: paperItems };
      await client.query('INSERT INTO paper_versions(id,paper_id,version,snapshot,created_by) VALUES($1,$2,1,$3,$4)', [versionId,id,snapshot,SYSTEM]);
      for (const [i,item] of paperItems.entries()) await client.query('INSERT INTO paper_items(id,paper_version_id,question_version_id,position,max_score,created_by) VALUES($1,$2,$3,$4,$5,$6)', [item.id,versionId,item.questionVersionId,i+1,item.maxScore,SYSTEM]);
    }
    await client.query('COMMIT');
    return { gradeId, subjectId, items, papers, versions };
  } catch (error) { await client.query('ROLLBACK'); throw error; }
  finally { client.release(); }
}
