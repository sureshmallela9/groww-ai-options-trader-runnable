const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync(require.resolve('../backend/src/main/resources/static/groww-api-audit.js'),'utf8');
const {today,range,validate,resultLabel}=require('../backend/src/main/resources/static/groww-api-audit.js');
test('audit dates use IST and inclusive ranges with strict validation',()=>{
 assert.equal(today(Date.parse('2026-11-11T18:30:00Z')),'2026-11-12');assert.deepEqual(range('7d','2026-11-12'),{from:'2026-11-06',to:'2026-11-12'});
 assert.deepEqual(range('yesterday','2026-01-01'),{from:'2025-12-31',to:'2025-12-31'});assert.equal(range('custom'),null);
 assert.ok(validate({from:'2026-02-30',to:'2026-03-01'}));assert.ok(validate({from:'2026-11-13',to:'2026-11-12'}));assert.ok(validate({from:'2026-01-01',to:'2026-11-12',symbol:'<script>'}));
 assert.equal(validate({from:'2026-01-01',to:'2026-01-02',symbol:'M&M'},'2026-11-12'),null);
 assert.equal(resultLabel({outcome:'BROKER_FAILURE',httpStatus:200,brokerCode:'GA005'}),'Groww failure · 200 · GA005');
});
class Element{
 constructor(tag='div'){this.tag=tag;this.children=[];this.value='';this._text='';this.attrs={};}
 set textContent(v){this._text=String(v);this.children=[];}get textContent(){return this._text+' '+this.children.map(n=>n.textContent).join(' ');}
 append(...nodes){this.children.push(...nodes);}replaceChildren(...nodes){this.children=nodes;this._text='';}setAttribute(k,v){this.attrs[k]=v;}removeAttribute(k){delete this.attrs[k];}click(){}
 set innerHTML(v){throw Error('Unsafe audit HTML');}
}
function harness(){const ids=new Map(),$=id=>{if(!ids.has(id))ids.set(id,new Element());return ids.get(id);};$('audit-preset').value='today';$('audit-category').value='ALL';$('audit-outcome').value='ALL';$('audit-auto').checked=true;
 let connection,timer;const requests=[];const context={document:{getElementById:$,createElement:t=>new Element(t),hidden:false},Intl,Date,URLSearchParams,URL,Blob,setTimeout:()=>{},setInterval:fn=>timer=fn,WorkspaceAPI:{platform:url=>new Promise((resolve,reject)=>requests.push({url,resolve,reject}))},AutoPaperUI:{subscribeConnection:fn=>connection=fn}};
 vm.runInNewContext(source,context);return {$,context,requests,connection:v=>connection({state:v}),refresh:()=>timer()};
}
const result=(more=false)=>({snapshotAt:new Date().toISOString(),totals:{calls:4,successful:3,failed:1,rateLimited:0,averageMs:123},coverage:{recordingStartedAt:'2026-11-12T05:00:00Z'},complete:true,records:[{startedAt:'2026-11-12T05:00:00Z',method:'GET',endpoint:'/v1/live-data/quote',purpose:'<img src=x onerror=alert(1)>',outcome:'SUCCESS',durationMs:123,request:{trading_symbol:'ITC'}}],hasMore:more,nextCursor:more?'page-two':undefined});
const tick=()=>new Promise(r=>setImmediate(r));
test('audit reads only its authenticated local report and renders broker text safely',async()=>{
 const h=harness();await h.context.GrowwApiAudit.enter();assert.equal(h.requests.length,0);h.connection('connected');assert.match(h.requests[0].url,/^\/reports\/groww-api\?/);h.requests[0].resolve(result());await tick();
 assert.match(h.$('audit-table').textContent,/<img src=x/);assert.match(h.$('audit-table').textContent,/ITC/);assert.equal(h.$('audit-calls').textContent.trim(),'4');assert.equal(h.$('audit-export').disabled,false);
 h.refresh();assert.equal(h.requests.length,2);assert.ok(h.requests.every(r=>r.url.startsWith('/reports/groww-api?')));
});
test('pagination keeps a snapshot and background refresh does not move an older page',async()=>{
 const h=harness();h.connection('connected');let pending=h.context.GrowwApiAudit.enter();h.requests[0].resolve(result(true));await pending;
 h.$('audit-next').onclick();assert.match(h.requests[1].url,/cursor=page-two/);assert.match(h.requests[1].url,/snapshotAt=/);h.requests[1].resolve(result());await tick();h.refresh();assert.equal(h.requests.length,2);assert.equal(h.$('audit-prev').disabled,false);
 h.$('audit-prev').onclick();assert.doesNotMatch(h.requests[2].url,/cursor=/);assert.match(h.requests[2].url,/snapshotAt=/);h.requests[2].resolve(result(true));await tick();assert.equal(h.$('audit-prev').disabled,true);
});
test('filter edits invalidate pending data and disconnect clears previous account records',async()=>{
 const h=harness();h.connection('connected');const pending=h.context.GrowwApiAudit.enter();h.$('audit-symbol').value='ITC';h.$('audit-symbol').oninput();h.requests[0].resolve(result());await pending;
 assert.equal(h.$('audit-calls').textContent.trim(),'');assert.equal(h.$('audit-export').disabled,true);h.refresh();assert.equal(h.requests.length,1);
 const reload=h.$('audit-filters').onsubmit({preventDefault(){}});assert.match(h.requests[1].url,/symbol=ITC/);h.connection('disconnected');h.requests[1].resolve(result());await tick();assert.equal(h.$('audit-calls').textContent.trim(),'—');assert.equal(h.$('audit-export').disabled,true);
});
test('leaving the audit page suppresses late responses and incomplete audits cannot export',async()=>{
 const h=harness();h.connection('connected');const pending=h.context.GrowwApiAudit.enter();h.context.GrowwApiAudit.leave();h.requests[0].resolve(result());await pending;h.refresh();assert.equal(h.requests.length,1);
 const again=h.context.GrowwApiAudit.enter();const bad=result();bad.complete=false;bad.coverage.unrecordedCallsSinceStart=2;h.requests[1].resolve(bad);await again;assert.equal(h.$('audit-export').disabled,true);assert.match(h.$('audit-coverage').textContent,/2 calls could not be saved/);
});
