import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {join,resolve} from 'node:path'
import {fileURLToPath} from 'node:url'

export function verifyBrowserEvidence(directory){
  const read=name=>JSON.parse(readFileSync(join(directory,name),'utf8'))
  const results=read('results.json'),workflow=read('workflow-summary.json'),acceptance=read('acceptance-summary.json'),entry=read('web-entry-summary.json')
  assert(results.stats.expected>=10,'The complete browser suite is required')
  for(const key of ['unexpected','skipped','flaky'])assert.equal(results.stats[key],0,key)
  const specifications=[]
  function visit(suites){for(const suite of suites){specifications.push(...(suite.specs||[]));visit(suite.suites||[])}}visit(results.suites)
  const required=['Remaining Web acceptance','ET02-N','ET02-F','ET02-B','edit during save','switching account','ET03-F','Both Web entries','Web administrator authors','Web empty and recoverable']
  for(const name of required){const spec=specifications.find(spec=>spec.title.includes(name));assert(spec?.ok,'Missing/pending real browser scenario: '+name);assert(spec.tests.every(test=>test.results.every(result=>result.status==='passed')))}
  assert.equal(workflow.result,'passed');assert.equal(acceptance.result,'passed')
  assert.deepEqual(workflow.adminFormulas,workflow.studentFormulas);assert.equal(workflow.adminFormulas.length,3)
  assert(workflow.adminFormulas.some(tex=>tex.includes('bmatrix')));assert(workflow.adminFormulas.some(tex=>tex.includes('_')&&tex.includes('^')))
  assert(workflow.browserLifecycle.events.some(event=>event.trusted&&event.visibility==='hidden'))
  assert(workflow.browserLifecycle.events.some(event=>event.trusted&&event.visibility==='visible'))
  assert.equal(acceptance.keyboardAnswer.completed,true)
  assert.deepEqual(acceptance.timedWindows.map(window=>window.status),['UPCOMING','OPEN','CLOSED'])
  assert(acceptance.timedWindows.every(window=>window.uiMatchesApi))
  assert(acceptance.taskEligibility.outsiderPageRejected&&acceptance.taskEligibility.outsiderHttpStatus===404)
  assert.deepEqual(acceptance.messageSessions,{recipients:2,independentReaderSessions:2,reads:4,readCount:1})
  assert(Object.values(acceptance.profiles).every(Boolean));assert(acceptance.canonicalRoutes.length>=7)
  assert(acceptance.emptyViews.length>=16);assert(workflow.failureRecoveries.length>=35)
  assert([...workflow.failedWrites,...acceptance.failedWrites].every(check=>check.repeatSuppressed&&check.safeInputsPreserved))
  assert(workflow.failedWrites.length>=9&&acceptance.failedWrites.length>=5)
  assert(entry.networkFailureRecovery&&entry.fieldBlurValidation)
  return{passed:true,browserTests:results.stats.expected,requiredScenarios:required.length,routes:workflow.routesChecked,errorRecoveries:workflow.failureRecoveries.length,emptyViews:acceptance.emptyViews.length,controlledWriteFailures:workflow.failedWrites.length+acceptance.failedWrites.length}
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url))console.log(JSON.stringify(verifyBrowserEvidence(process.argv[2]),null,2))
