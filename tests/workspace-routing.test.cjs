const {test}=require('node:test'),assert=require('node:assert/strict');
const {routes,resolve,pathFor,createRouter}=require('../backend/src/main/resources/static/workspace-router.js');
const {createClient}=require('../backend/src/main/resources/static/workspace-api.js');
const response=(body,status=200)=>({ok:status>=200&&status<300,status,headers:{get:()=>null},text:async()=>JSON.stringify(body)});

test('all screens, old aliases and saved detail URLs resolve consistently',()=>{
  for(const name of Object.keys(routes)){assert.equal(resolve(pathFor(name)).name,name);assert.equal(resolve('/', '#'+name).name,name);}
  assert.equal(resolve('/paper-workspace.html').name,'analysis');assert.equal(resolve('/research-tools.html').name,'tools');
  assert.deepEqual(resolve('/workspace/reports/123'),{name:'reports',id:'123'});
  assert.deepEqual(resolve('/workspace/positions/INTENT_1-a'),{name:'positions',id:'INTENT_1-a'});
  for(const path of ['/workspace/nope','/api/v1/auto/status','/workspace/reports/nope','/workspace/reports/0','/workspace/positions/%3Cscript%3E'])assert.equal(resolve(path).name,'not-found');
  assert.equal(pathFor('__proto__'),'/workspace/overview');
});
test('router canonicalizes once, navigates without duplicates and restores history without writing it',()=>{
  const location={pathname:'/',search:'',hash:'#positions'},events=[],historyWrites=[];
  const write=(kind,url)=>{historyWrites.push([kind,url]);location.pathname=url;location.search='';location.hash='';};
  const router=createRouter({location,history:{replaceState:(_,__,url)=>write('replace',url),pushState:(_,__,url)=>write('push',url)},onChange:(route,changed)=>events.push([route,changed])});
  router.start();router.navigate('reports','12');router.navigate('reports','12');
  assert.deepEqual(historyWrites,[['replace','/workspace/positions'],['push','/workspace/reports/12']]);
  location.pathname='/workspace/positions';router.sync();assert.equal(router.current().name,'positions');assert.equal(historyWrites.length,2);
  assert.equal(events.at(-1)[1],true);
});
test('shared client deduplicates concurrent GETs and forgets completed requests',async()=>{
  let finish,calls=0;const client=createClient({fetch:async()=>{calls++;await new Promise(resolve=>finish=resolve);return response({value:calls});}});
  const first=client.request('/api/ai/status'),second=client.request('/api/ai/status');assert.equal(first,second);assert.equal(calls,1);finish();await first;
  const third=client.request('/api/ai/status');assert.equal(calls,2);finish();await third;
});
test('credentials stay on local v1 requests, token changes do not reuse prior requests',async()=>{
  const calls=[];const client=createClient({fetch:async(url,options)=>{calls.push({url,options});return response({ok:true});}});
  await assert.rejects(client.platform('/auto/status'),/Connect/);client.setToken('synthetic-one');
  await client.platform('/auto/status');await client.request('/api/ai/status');client.setToken('synthetic-two');await client.platform('/auto/status');
  assert.equal(calls[0].options.headers.Authorization,'Bearer synthetic-one');assert.equal(calls[1].options.headers.Authorization,undefined);assert.equal(calls[2].options.headers.Authorization,'Bearer synthetic-two');
  await assert.rejects(client.request('https://example.com/api/v1/auto/status'),/Only local/);
  client.setToken('');assert.equal(client.hasToken(),false);
});
test('mutation failures are not retried, status and server reason are preserved',async()=>{
  let count=0;const client=createClient({fetch:async()=>{count++;return response({error:'Paper entries blocked.'},409);}});client.setToken('synthetic');
  await assert.rejects(client.platform('/trade-intents',{mode:'PAPER'}),error=>error.status===409&&error.message==='Paper entries blocked.');assert.equal(count,1);
});
test('invalid JSON fails clearly and releases the GET slot for recovery',async()=>{
  let broken=true;const client=createClient({fetch:async()=>broken?{ok:true,status:200,text:async()=>'<html>oops</html>'}:response({ready:true})});
  await assert.rejects(client.request('/api/ai/status'),/unreadable/);broken=false;assert.equal((await client.request('/api/ai/status')).ready,true);
});
