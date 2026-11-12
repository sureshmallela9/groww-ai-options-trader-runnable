const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const file='../backend/src/main/resources/static/daily-orders.js';
const {dayOf,validDay,buildDailyReport}=require(file);
const trade={intentId:'order-one',symbol:'TEST',positionSide:'LONG',state:'POSITION_CLOSED',quantity:5,filledQuantity:5,remainingQuantity:0,entryPrice:100,exitPrice:102,entryTime:'2026-11-02T06:30:00Z',exitTime:'2026-11-03T03:30:00Z',createdAt:'2026-11-02T06:29:59Z',realizedPnl:8,charges:2};
test('day boundaries always use IST, including timestamps near UTC midnight',()=>{
  assert.equal(dayOf('2026-11-02T19:00:00Z'),'2026-11-03');assert.equal(dayOf('2026-11-02T18:29:59Z'),'2026-11-02');
  assert.equal(dayOf(null),null);assert.equal(dayOf('broken'),null);assert.equal(validDay('2026-02-30'),false);
});
test('cross-day trades realize their net result and charges only on exit day',()=>{
  const entry=buildDailyReport([trade],'2026-11-02'),exit=buildDailyReport([trade],'2026-11-03');
  assert.equal(entry.entries,1);assert.equal(entry.exits,0);assert.equal(entry.realizedPnl,0);assert.equal(entry.charges,0);
  assert.equal(exit.entries,0);assert.equal(exit.exits,1);assert.equal(exit.realizedPnl,8);assert.equal(exit.charges,2);
  assert.deepEqual(exit.dates,['2026-11-03','2026-11-02']);
});
test('pending exits and rejected requests never become completed fills or realized P&L',()=>{
  const pending={...trade,state:'EXIT_PENDING',exitTime:null,exitPrice:null,remainingQuantity:5,realizedPnl:0,timeline:[{state:'EXIT_PENDING',at:'2026-11-03T04:00:00Z'},{state:'EXIT_PENDING',at:'2026-11-03T04:00:05Z'}]};
  const rejected={symbol:'OTHER',state:'REJECTED',sessionDate:'2026-11-03',filledQuantity:0};
  const report=buildDailyReport([pending,rejected],'2026-11-03');
  assert.equal(report.rows.length,2);assert.equal(report.entries,0);assert.equal(report.exits,0);assert.equal(report.pending,1);assert.equal(report.realizedPnl,0);
});
test('short results use saved accounting and missing results are flagged, never fabricated',()=>{
  const rows=[{...trade,positionSide:'SHORT',realizedPnl:-.1,charges:.2},{...trade,intentId:'two',realizedPnl:.2},{...trade,intentId:'three',realizedPnl:undefined,charges:undefined}];
  const report=buildDailyReport(rows,'2026-11-03');
  assert.equal(report.realizedPnl,.1);assert.equal(report.missingPnl,1);assert.equal(report.missingCharges,1);
  assert.equal(buildDailyReport([{symbol:'UNDATED'}],'2026-11-03').undated,1);
  assert.equal(buildDailyReport([trade],'2026-11-04').rows.length,0);
});
function browser(){
  class Element{constructor(tag='div'){this.tag=tag;this.children=[];this.value='';this.dataset={};this.attributes={};this._text='';}set textContent(v){this._text=String(v??'');this.children=[];}get textContent(){return this._text+this.children.map(n=>n.textContent).join(' ');}append(n){this.children.push(n);}replaceChildren(){this.children=[];this._text='';}setAttribute(k,v){this.attributes[k]=v;}removeAttribute(k){delete this.attributes[k];}}
  const elements={},get=id=>elements[id]??=new Element();let update,connection,resolveRequest;
  const context={Intl,Date,Set,Number,document:{getElementById:get,createElement:tag=>new Element(tag)},WorkspaceAPI:{request:()=>new Promise(resolve=>resolveRequest=resolve)},AutoPaperUI:{subscribe(fn){update=fn;},subscribeConnection(fn){connection=fn;}}};
  vm.createContext(context);vm.runInContext(fs.readFileSync(require.resolve(file),'utf8'),context);
  return {context,get,update:(value)=>update(value),connection:(value)=>connection(value),respond:(value)=>resolveRequest(value)};
}
test('daily view loads saved orders, filters dates, shows detail fields and clears on disconnect',async()=>{
  const b=browser(),work=b.context.DailyOrderReports.enter();b.respond({intents:[trade]});await work;
  assert.equal(b.get('orders-day').value,'2026-11-03');assert.match(b.get('daily-order-list').textContent,/TEST/);assert.match(b.get('daily-order-list').textContent,/Order details/);assert.match(b.get('orders-realized').textContent,/8\.00/);
  b.get('orders-day').value='2026-11-04';b.get('orders-day').onchange();assert.match(b.get('daily-order-list').textContent,/No order activity/);
  b.get('orders-day').value='2026-11-02';b.get('orders-day').onchange();assert.match(b.get('daily-order-list').textContent,/On 2026-11-03/);
  b.connection({state:'disconnected'});assert.doesNotMatch(b.get('daily-order-list').textContent,/TEST/);
});
test('a stale load cannot overwrite an update after leaving reports',async()=>{
  const b=browser(),work=b.context.DailyOrderReports.enter();b.context.DailyOrderReports.leave();b.respond({intents:[trade]});await work;
  assert.doesNotMatch(b.get('daily-order-list').textContent,/TEST/);
});
