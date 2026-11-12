const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const {createClient}=require('../backend/src/main/resources/static/workspace-api.js');
const source=fs.readFileSync(require.resolve('../backend/src/main/resources/static/automatic-paper.js'),'utf8');
function harness(fetcher,flowWindowOpen=false){
  class Element{constructor(tag='div'){this.tag=tag;this.children=[];this.value='';this.dataset={};this.attributes={};this.classList={toggle(){}};this._text='';}
    set textContent(value){this._text=String(value);this.children=[];}get textContent(){return this._text+this.children.map(x=>x.textContent).join(' ');}
    append(...nodes){this.children.push(...nodes);}replaceChildren(...nodes){this._text='';this.children=nodes;}setAttribute(k,v){this.attributes[k]=String(v);}hasAttribute(k){return Object.hasOwn(this.attributes,k);}}
  const elements={},get=id=>elements[id]??=new Element();
  const document={hidden:false,getElementById:get,createElement:tag=>new Element(tag),createElementNS:(_,tag)=>new Element(tag),head:new Element(),body:new Element()};
  const client=createClient({fetch:fetcher});const context={document,SystemFlowWindow:{isOpen:()=>flowWindowOpen},WorkspaceAPI:client,Intl,Date,Number,Set,Math,console,setInterval(){}};vm.createContext(context);vm.runInContext(source,context);
  return {ui:context.AutoPaperUI,get,document};
}
const response=(body,status=200)=>({ok:status===200,status,headers:{get:()=>null},text:async()=>JSON.stringify(body)});
function state(label){return {automation:{state:'STOPPED',message:label,enabled:false},portfolio:{positions:[],realizedPnl:0,unrealizedPnl:0,netPnl:0},risk:{policy:{capital:10000,riskPerTradePercent:.5,dailyLossPercent:2,maxTradesPerDay:5},calendar:{verified:false}}};}

test('failed manual-pause changes restore the server-confirmed checkbox and allow later refreshes',async()=>{
  let paused=true;
  const {ui,get}=harness(async url=>{
    if(url.endsWith('/event-risk'))return response({error:'Setting could not be saved'},503);
    const result=state('confirmed');result.automation.scheduledEventRisk=paused;return response(result);
  });
  await ui.connect('synthetic-token');assert.equal(get('auto-event').checked,true);
  get('auto-event').checked=false;await get('auto-event').onchange();
  assert.equal(get('auto-event').checked,true);assert.match(get('auto-event-help').textContent,/last confirmed/);
  paused=false;await ui.refresh();assert.equal(get('auto-event').checked,false);
});
test('authorization failure suspends status polling, reconnect recovers and disconnect removes credentials',async()=>{
  let status=401,calls=0;const {ui,get}=harness(async()=>{calls++;return response(status===200?state('recovered'):{error:'Token expired'},status);});const states=[];ui.subscribeConnection(value=>states.push(value.state));
  await ui.connect('synthetic-old');assert.equal(states.at(-1),'error');const failedCalls=calls;await ui.refresh();assert.equal(calls,failedCalls);
  status=200;await ui.connect('synthetic-new');assert.equal(states.at(-1),'connected');assert.match(get('auto-status').textContent,/recovered/);
  ui.disconnect();assert.equal(states.at(-1),'disconnected');const disconnectedCalls=calls;await ui.refresh();assert.equal(calls,disconnectedCalls);
});
test('a late response from an old connection never replaces the newer connection',async()=>{
  let release;const delivered=[];const {ui}=harness(async(url,options)=>{const old=options.headers.Authorization==='Bearer synthetic-old';if(old)await new Promise(resolve=>release=resolve);return response(state(old?'old response':'new response'));});
  ui.subscribe(value=>delivered.push(value.automation.message));const first=ui.connect('synthetic-old'),second=ui.connect('synthetic-new');release();await Promise.all([first,second]);
  assert.deepEqual(delivered,['new response']);
});

test('a hidden workspace shares status with an open flow window without loading its chart',async()=>{
  const calls=[];const active=harness(async url=>{calls.push(url);const result=state('Live flow feed');result.automation.referenceSymbol='NIFTY';return response(result);},true);
  active.document.hidden=true;await active.ui.connect('synthetic-flow');
  assert.equal(calls.length,1);assert.match(calls[0],/auto\/status/);assert.doesNotMatch(calls.join(' '),/auto\/chart/);
  let closedCalls=0;const closed=harness(async()=>{closedCalls++;return response(state('Hidden'));},false);
  closed.document.hidden=true;await closed.ui.connect('synthetic-flow');assert.equal(closedCalls,0);
});

test('late paper status is discarded after a confirmed mode change and the next refresh uses real mode',async()=>{
  let mode='PAPER',delay=false,release;const seen=[];
  const {ui}=harness(async()=>{const captured=mode;if(delay){delay=false;await new Promise(resolve=>release=resolve);}const value=state(captured);value.automation.mode=captured;value.portfolio.mode=captured;return response(value);});
  ui.subscribe(value=>seen.push(value.automation.mode));await ui.connect('synthetic-mode-token');assert.equal(seen.at(-1),'PAPER');
  delay=true;const old=ui.refresh();mode='REAL';const changed=ui.modeChanged();release();await Promise.all([old,changed]);
  assert.deepEqual(seen,['PAPER','REAL']);
});
