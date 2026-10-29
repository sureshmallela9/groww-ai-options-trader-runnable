const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const {chartModel}=require('../backend/src/main/resources/static/automatic-paper.js');
test('chart positions buy and sell markers at their recorded times and prices',()=>{
  const model=chartModel([{timestamp:'2026-10-28T04:00:00Z',close:100},{timestamp:'2026-10-28T04:05:00Z',close:102}],
    [{side:'BUY',time:'2026-10-28T04:01:00Z',price:100.2},{side:'SELL',time:'2026-10-28T04:04:00Z',price:101.8}]);
  assert.equal(model.markers.length,2);assert.ok(model.markers[0].x<model.markers[1].x);
  assert.ok(model.markers[0].y>model.markers[1].y);
  for(const point of model.points.concat(model.markers))assert.ok(Number.isFinite(point.x)&&Number.isFinite(point.y));
  assert.equal(chartModel([],[]).markers.length,0);
});
test('flat price histories and fills without history have finite chart coordinates',()=>{
  const model=chartModel([],[{side:'BUY',time:'2026-10-28T04:00:00Z',price:100}]);
  assert.ok(Number.isFinite(model.markers[0].x)&&Number.isFinite(model.markers[0].y));
  assert.equal(chartModel([],[{side:'PENDING',time:'bad',price:100}]).markers.length,0);
});
test('connected dashboard renders net P&L, position dots and coloured fill markers',async()=>{
  class Element {
    constructor(tag='div'){this.tag=tag;this.children=[];this.attributes={};this.value='';this.classList={toggle(){}};this._text='';}
    set textContent(value){this._text=String(value);this.children=[];}
    get textContent(){return this._text+this.children.map(e=>e.textContent).join('');}
    append(...elements){this.children.push(...elements);}replaceChildren(...elements){this._text='';this.children=elements;}
    setAttribute(k,v){this.attributes[k]=String(v);}
  }
  const elements={},get=id=>elements[id]??=new Element();
  const document={hidden:false,getElementById:get,createElement:tag=>new Element(tag),createElementNS:(_,tag)=>new Element(tag),head:new Element(),body:new Element()};
  const row={symbol:'TEST',state:'POSITION_CLOSED',entryPrice:100,exitPrice:103,realizedPnl:2.8,filledQuantity:1,remainingQuantity:0};
  const context={document,Intl,Date,Number,Set,Math,console,setInterval(){},async fetch(url){return {ok:true,async json(){
    if(url.includes('/auto/chart'))return {bars:[{timestamp:'2026-10-28T04:00:00Z',close:100}],markers:[{side:'BUY',time:'2026-10-28T04:00:00Z',price:100},{side:'SELL',time:'2026-10-28T04:05:00Z',price:103}]};
    return {automation:{enabled:true,state:'ENTRY_WINDOW_CLOSED',referenceSymbol:'NIFTY'},portfolio:{positions:[row],realizedPnl:2.8,unrealizedPnl:0,netPnl:2.8},risk:{policy:{capital:10000,riskPerTradePercent:.5,dailyLossPercent:2,maxTradesPerDay:5},calendar:{verified:true,sessionOpen:true,entryAllowed:false,reason:'New entries stopped at 14:57 IST.',open:'09:15',entryEndsAt:'14:57',nextEntrySession:'2026-10-29 09:15 IST'}}};
  }};}};
  vm.createContext(context);vm.runInContext(fs.readFileSync(require.resolve('../backend/src/main/resources/static/automatic-paper.js'),'utf8'),context);
  await context.AutoPaperUI.connect('synthetic-test-token');
  assert.match(get('auto-net').textContent,/2\.80/);
  assert.match(get('auto-rules').textContent,/New entries stopped at 14:57 IST/);
  assert.match(get('auto-rules').textContent,/2026-10-29 09:15 IST/);
  assert.match(get('auto-chart-symbol').textContent,/NIFTY · reference index/);
  assert.match(get('auto-positions').textContent,/SELL \/ CLOSED/);
  const circles=get('auto-chart').children.filter(e=>e.tag==='circle');assert.equal(circles.length,2);
  assert.equal(circles[0].attributes.fill,'#38d9a9');assert.equal(circles[1].attributes.fill,'#ff7185');
});
