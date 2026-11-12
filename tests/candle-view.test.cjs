const {test}=require('node:test'),assert=require('node:assert/strict');
const {aggregate,timeTicks,panEndIndex,bindPan,frameFor}=require('../backend/src/main/resources/static/candle-view.js');
const {candleModel,render}=require('../backend/src/main/resources/static/shortlist-charts.js');
const session=(day='2026-11-04',count=75)=>Array.from({length:count},(_,i)=>({timestamp:new Date(Date.parse(day+'T09:15:00+05:30')+i*300000).toISOString(),open:100+i,high:102+i,low:99+i,close:101+i,volume:100+i}));

test('15-minute aggregation preserves chronological OHLCV and ignores duplicate timestamps',()=>{
 const bars=session().slice(0,3),before=JSON.stringify(bars),result=aggregate([bars[2],bars[0],bars[1],bars[1]],'15m');
 assert.equal(result.length,1);assert.equal(result[0].open,100);assert.equal(result[0].high,104);assert.equal(result[0].low,99);assert.equal(result[0].close,103);assert.equal(result[0].volume,303);
 assert.equal(result[0].sourceCount,3);assert.equal(result[0].expectedCount,3);assert.equal(result[0].partial,false);assert.equal(JSON.stringify(bars),before);
 assert.equal(frameFor('1m').id,'5m');
});
test('missing source candles remain partial and sessions never aggregate across days',()=>{
 const full=session(),gapped=aggregate(full.filter((_,i)=>i!==1),'15m');assert.equal(gapped[0].partial,true);
 const days=aggregate([...full,...session('2026-11-05',10)],'1d');assert.equal(days.length,2);assert.equal(days[0].partial,false);assert.equal(days[1].partial,true);assert.equal(days[0].sourceCount,75);assert.equal(days[1].sourceCount,10);
 const hours=aggregate(full,'1h');assert.equal(hours.length,7);assert.equal(hours.at(-1).expectedCount,3);assert.equal(hours.at(-1).partial,false);assert.equal(Date.parse(hours.at(-1).endTimestamp),Date.parse('2026-11-04T15:30:00+05:30'));
});
test('timeframe markers retain actual fill prices/times and historical windows exclude later fills',()=>{
 const bars=aggregate(session(),'1h'),fills=[{side:'BUY',price:101.5,time:'2026-11-04T09:25:00+05:30'},{side:'SELL',price:120,time:'2026-11-04T14:59:00+05:30'}];
 const model=candleModel(bars,fills,3,[],{endTime:bars[2].timestamp});assert.deepEqual(model.markers.map(m=>m.side),['BUY']);assert.equal(model.markers[0].price,101.5);assert.equal(model.markers[0].time,fills[0].time);
 const daily=candleModel(aggregate(session(),'1d'),fills,75);assert.equal(daily.markers.length,2);assert.ok(daily.markers.every(m=>m.x===daily.candles[0].x));
});
test('axis ticks include IST times and dates rather than only two endpoints',()=>{
 const bars=candleModel(session(),[],20).candles,ticks=timeTicks(bars,'5m');assert.equal(ticks.length,5);assert.equal(ticks[0].top,'13:50');assert.match(ticks[0].bottom,/04 Nov/);assert.ok(ticks.every(t=>Number.isFinite(t.x)));
 assert.equal(timeTicks([], '1d').length,0);
});
function gestureHarness(){
 let model=candleModel(session(),[],20),commits=0;const moves=[],interaction={dragging:false},classes=new Set();
 const svg={classList:{add:c=>classes.add(c),remove:c=>classes.delete(c)},getBoundingClientRect:()=>({width:900}),focus(){this.focused=true;},setPointerCapture(id){this.capture=id;},hasPointerCapture(id){return this.capture===id;},releasePointerCapture(){this.capture=null;}};
 bindPan(svg,{interaction,getModel:()=>model,onPan:(end,latest)=>{moves.push(end);model=candleModel(session(),[],20,[],latest?{}:{endTime:end});},onCommit:()=>commits++});
 const event=(x=0,y=0)=>({pointerId:1,button:0,isPrimary:true,clientX:x,clientY:y,preventDefault(){this.prevented=true;}});
 return {svg,interaction,classes,event,moves,get model(){return model;},get commits(){return commits;}};
}
test('continuous dragging pans from the starting window without losing pointer capture',()=>{
 const h=gestureHarness();h.svg.onpointerdown(h.event());assert.equal(h.interaction.dragging,true);assert.equal(h.svg.focused,true);
 h.svg.onpointermove(h.event(80));assert.equal(h.model.endIndex,73);h.svg.onpointermove(h.event(160));assert.equal(h.model.endIndex,71);assert.equal(h.svg.capture,1);
 h.svg.onpointerup(h.event(160));assert.equal(h.interaction.dragging,false);assert.equal(h.svg.capture,null);assert.equal(h.commits,1);assert.equal(h.classes.has('is-dragging'),false);
 assert.equal(panEndIndex(75,20,75,1000),20);assert.equal(panEndIndex(75,20,20,-1000),75);
});
test('vertical touch movement, pointer cancellation and keyboard panning release interaction state',()=>{
 const h=gestureHarness();h.svg.onpointerdown(h.event());const vertical=h.event(2,70);h.svg.onpointermove(vertical);assert.equal(h.moves.length,0);assert.equal(vertical.prevented,undefined);
 h.svg.onpointercancel(h.event());assert.equal(h.interaction.dragging,false);assert.equal(h.commits,0);
 h.svg.onkeydown({key:'ArrowLeft',preventDefault(){}});assert.equal(h.model.endIndex,71);assert.equal(h.commits,1);
 h.svg.onkeydown({key:'End',preventDefault(){}});assert.equal(h.model.atLatest,true);
 h.svg.onpointerdown(h.event());h.svg.onpointermove(h.event(100));h.svg.onlostpointercapture();assert.equal(h.interaction.dragging,false);assert.equal(h.commits,3);
});
test('timeframe selection persists through refresh and never reduces the AI source count',()=>{
 class Element{constructor(tag){this.tag=tag;this.children=[];this.attributes={};this.dataset={};this._text='';this.classList={add(){}};}set textContent(v){this._text=String(v);this.children=[];}get textContent(){return this._text+' '+this.children.map(c=>c.textContent).join(' ');}append(...n){this.children.push(...n);}replaceChildren(...n){this.children=n;this._text='';}setAttribute(k,v){this.attributes[k]=v;}}
 const doc={createElement:t=>new Element(t),createElementNS:(_,t)=>new Element(t)},host=new Element('div');host.ownerDocument=doc;
 const data={stocks:[{symbol:'TEST',bars:session(),markers:[],aiCandleCount:75}]},views=new Map([['TEST',{day:'2026-11-04'}]]),nodes=e=>[e,...e.children.flatMap(nodes)];
 render(host,data,{limit:20,views});const select=nodes(host).find(n=>n.tag==='select');select.value='1h';select.onchange();assert.match(host.textContent,/1 hour chart/);assert.match(host.textContent,/AI received 75 candles/);assert.match(host.textContent,/AI RSI 14 \(5m\)/);
 render(host,data,{limit:20,views});assert.match(host.textContent,/1 hour chart/);assert.equal(data.stocks[0].bars.length,75);
});
