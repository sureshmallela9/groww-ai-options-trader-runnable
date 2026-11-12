const {test}=require('node:test'),assert=require('node:assert/strict');
const flow=require('../backend/src/main/resources/static/system-flow.js');
const now=Date.parse('2026-10-30T05:00:00Z');
const connection={state:'connected',updatedAt:'2026-10-30T05:00:00Z'};
const state=(phase='ANALYZING')=>({automation:{enabled:true,busy:true,state:phase,activity:{status:'PROCESSING',processing:true,phase,serverTime:'2026-10-30T05:00:00Z',phaseStartedAt:'2026-10-30T04:59:50Z',lastCheckedAt:'2026-10-30T04:59:50Z',recentStages:[]},flow:{configurationSource:'server',growwIndexPollMs:2500,automationCheckMs:7000}}});

test('flow separates the active Groww and AI stages and uses configured server timing',()=>{
  let view=flow.model(flow.snapshot(state(),connection),now);
  assert.equal(view.nodes.find(n=>n.key==='ai').active,true);assert.equal(view.nodes.find(n=>n.key==='groww').active,false);
  assert.match(view.timings.find(t=>t.label==='Groww index LTP feed').cadence,/2.5 seconds/);
  assert.match(view.timings.find(t=>t.label==='Automation rule checks').cadence,/7 seconds/);
  assert.match(view.timings.find(t=>t.label==='Browser automation status').condition,/does not create another/);
  view=flow.model(flow.snapshot(state('VERIFYING_DATA'),connection),now);assert.equal(view.nodes.find(n=>n.key==='groww').active,true);
});
test('entry gates and old source updates never appear as active downstream calls',()=>{
  const value=state();value.automation.scheduledEventRisk=true;
  let view=flow.model(flow.snapshot(value,connection),now);assert.equal(view.gateBlocked,true);
  assert.ok(view.nodes.every(n=>!n.active));
  view=flow.model(flow.snapshot(state(),connection),now+16000);
  assert.equal(view.status.kind,'UNKNOWN');assert.ok(view.nodes.every(n=>!n.active));
});
test('latest stock comparison does not get replaced by a final decision from an older scan',()=>{
  const value=state('NO_TRADE');value.automation.busy=false;value.automation.activity.status='WAITING';value.automation.activity.processing=false;
  value.automation.activity.lastSelectionAt='2026-10-30T04:59:50Z';value.automation.activity.lastDecisionAt='2026-10-30T04:50:00Z';
  value.automation.decision={action:'BUY',symbol:'OLD',reasons:['Old proposal']};value.automation.selection={selected:null,reasons:['No stock passed']};
  const view=flow.model(flow.snapshot(value,connection),now);
  assert.equal(view.resultTitle,'No qualifying stock');assert.deepEqual(view.resultReasons,['No stock passed']);
});
test('window payload is an allowlisted display snapshot without credentials or input candles',()=>{
  const value=state();value.token='secret1';value.automation.password='secret2';value.automation.flow.token='secret3';value.automation.activity.headers={Authorization:'secret4'};
  value.automation.selection={selected:{symbol:'TEST',score:80,token:'secret5'},candles:[{private:'rawbars'}],reasons:['Reason']};
  const encoded=JSON.stringify(flow.snapshot(value,{...connection,token:'secret6'}));
  assert.doesNotMatch(encoded,/secret[1-6]|rawbars|Authorization/);assert.match(encoded,/TEST/);
});
test('separate-window messages require the exact origin and window, and do not call APIs',()=>{
  let listener,navigated,acknowledged=false,opens=0;const sent=[];
  const child={closed:false,location:{origin:'http://localhost:1111'},focus(){},postMessage(data,origin){sent.push({data,origin});}};
  const win={location:{origin:'http://localhost:1111'},addEventListener(name,fn){listener=fn;},removeEventListener(){},open(url){opens++;assert.equal(url,'/system-flow.html');return child;}};
  const bridge=flow.createParentBridge(win,screen=>navigated=screen,()=>acknowledged=true);bridge.publish(state(),connection);assert.equal(bridge.open(),true);
  listener({origin:'https://other.example',source:child,data:{type:'TRADING_FLOW_READY'}});assert.equal(sent.length,0);
  listener({origin:win.location.origin,source:{},data:{type:'TRADING_FLOW_READY'}});assert.equal(sent.length,0);
  listener({origin:win.location.origin,source:child,data:{type:'TRADING_FLOW_READY'}});assert.equal(sent.length,1);assert.equal(sent[0].origin,win.location.origin);
  listener({origin:'https://other.example',source:child,data:{type:'TRADING_FLOW_ACK'}});assert.equal(acknowledged,false);
  listener({origin:win.location.origin,source:child,data:{type:'TRADING_FLOW_ACK'}});assert.equal(acknowledged,true);
  listener({origin:win.location.origin,source:child,data:{type:'TRADING_FLOW_NAVIGATE',screen:'orders'}});assert.equal(navigated,undefined);
  listener({origin:win.location.origin,source:child,data:{type:'TRADING_FLOW_NAVIGATE',screen:'analysis'}});assert.equal(navigated,'analysis');
  bridge.open();assert.equal(opens,1);child.closed=true;assert.equal(bridge.isOpen(),false);
});
test('rendered API details and reasons are inert text and never become executable markup',()=>{
  class Element{constructor(tag){this.tag=tag;this.children=[];this.dataset={};this._text='';}set textContent(v){this._text=String(v);this.children=[];}get textContent(){return this._text+' '+this.children.map(c=>c.textContent).join(' ');}set innerHTML(v){throw Error('No untrusted HTML');}append(...nodes){this.children.push(...nodes);}replaceChildren(...nodes){this._text='';this.children=nodes;}setAttribute(){}querySelector(){return null;}}
  const target=new Element('div');target.ownerDocument={createElement:tag=>new Element(tag)};
  const value=state();value.automation.selection={selected:{symbol:'TEST'},reasons:['<img src=x onerror=alert(1)>']};
  flow.render(target,flow.snapshot(value,connection),now);
  assert.match(target.textContent,/Groww market data/);assert.match(target.textContent,/POST \/api\/v1\/automation\/select/);
  assert.match(target.textContent,/<img src=x onerror=alert\(1\)>/);
});
