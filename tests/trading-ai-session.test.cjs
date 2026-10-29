const {test}=require('node:test');
const assert=require('node:assert/strict');
const {createPoller}=require('../backend/src/main/resources/static/trading-ai-session.js');
const flush=()=>new Promise(resolve=>setImmediate(resolve));
function setup(poll) {
  const jobs=[],results=[],errors=[];
  const controller=createPoller({poll,onResult:r=>results.push(r),onError:e=>errors.push(e),
    schedule:(fn,delay)=>{const job={fn,delay};jobs.push(job);return job;},
    cancel:job=>{job.cancelled=true;}});
  return {controller,jobs,results,errors};
}
test('automatic analysis schedules five-second checks and stops',async()=>{
  let count=0;const state=setup(async()=>({count:++count}));
  state.controller.start();await flush();
  assert.equal(count,1);assert.equal(state.jobs[0].delay,5000);
  await state.jobs[0].fn();assert.equal(count,2);
  state.controller.stop();await state.jobs.at(-1).fn();assert.equal(count,2);
});
test('slow requests never overlap and stale results are discarded after restart',async()=>{
  let release,count=0;const state=setup(()=>{count++;return new Promise(resolve=>release=resolve);});
  state.controller.start();state.controller.stop();state.controller.start();
  assert.equal(count,1);release({old:true});await flush();assert.deepEqual(state.results,[]);
  const pending=state.jobs.at(-1).fn();assert.equal(count,2);
  state.controller.stop();release({late:true});await pending;assert.deepEqual(state.results,[]);
});
test('rate limits honor Retry-After rather than polling the broker repeatedly',async()=>{
  const error=Object.assign(new Error('Busy'),{retryAfterMs:30000});
  const state=setup(async()=>{throw error;});state.controller.start();await flush();
  assert.equal(state.jobs[0].delay,30000);assert.equal(state.errors.length,1);state.controller.stop();
});
