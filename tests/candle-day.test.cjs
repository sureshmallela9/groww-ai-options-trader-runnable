const {test}=require('node:test'),assert=require('node:assert/strict');
const {dayOf,dayWindow,aggregate}=require('../backend/src/main/resources/static/candle-view.js');
const {render}=require('../backend/src/main/resources/static/shortlist-charts.js');
const session=day=>Array.from({length:75},(_,i)=>({timestamp:new Date(Date.parse(day+'T09:15:00+05:30')+i*300000).toISOString(),open:100,high:102,low:99,close:101,volume:1000}));
const now=Date.parse('2026-11-05T10:00:00+05:30');
test('day boundaries use IST and empty today never falls back to yesterday',()=>{
 assert.equal(dayOf('2026-11-04T18:29:59Z'),'2026-11-04');assert.equal(dayOf('2026-11-04T18:30:00Z'),'2026-11-05');assert.equal(dayOf('bad'),null);
 const days=dayWindow(session('2026-11-04'),undefined,now);assert.equal(days.day,'2026-11-05');assert.equal(days.bars.length,0);assert.equal(days.previous,'2026-11-04');assert.equal(days.next,null);
 assert.equal(dayWindow([], '2026-02-30',now).day,'2026-11-05');assert.equal(dayWindow([], '2026-11-06',now).day,'2026-11-05');
});
test('date navigation skips missing days and aggregation keeps one session',()=>{
 const bars=[...session('2026-11-02'),...session('2026-11-04'),...session('2026-11-05')],before=JSON.stringify(bars),view=dayWindow(bars,'2026-11-04',now);
 assert.equal(view.previous,'2026-11-02');assert.equal(view.next,'2026-11-05');assert.equal(view.bars.length,75);assert.equal(aggregate(view.bars,'1d').length,1);assert.equal(JSON.stringify(bars),before);
});
class Element{constructor(tag){this.tag=tag;this.children=[];this.attributes={};this.dataset={};this._text='';this.classList={add(){}};}set textContent(v){this._text=String(v);this.children=[];}get textContent(){return this._text+' '+this.children.map(c=>c.textContent).join(' ');}append(...n){this.children.push(...n);}replaceChildren(...n){this.children=n;this._text='';}setAttribute(k,v){this.attributes[k]=v;}}
function harness(){const doc={createElement:t=>new Element(t),createElementNS:(_,t)=>new Element(t)},host=new Element('div');host.ownerDocument=doc;const nodes=e=>[e,...e.children.flatMap(nodes)];return{host,nodes,find:(tag,label)=>nodes(host).find(n=>n.tag===tag&&(n.attributes['aria-label']===label||n.textContent.trim()===label))};}
test('previous day, timeframe, zoom and refresh retain date; Today returns to truthful empty chart',()=>{
 const h=harness(),views=new Map(),data={stocks:[{symbol:'TEST',bars:[...session('2026-11-02'),...session('2026-11-04')],aiCandleCount:150,markers:[]}]};
 render(h.host,data,{now,limit:20,views});assert.match(h.host.textContent,/No completed candles for 05 Nov 2026/);
 h.find('button','← Previous day').onclick();assert.equal(views.get('TEST').day,'2026-11-04');assert.match(h.host.textContent,/Showing 20 of 75/);
 h.find('button','+ Zoom in').onclick();assert.match(h.host.textContent,/Showing 10 of 75/);
 const frame=h.find('select','TEST candle timeframe');frame.value='15m';frame.onchange();assert.equal(views.get('TEST').day,'2026-11-04');assert.match(h.host.textContent,/of 25 candles/);
 render(h.host,data,{now,limit:20,views});assert.match(h.host.textContent,/04 Nov 2026 · 15 minutes chart/);assert.match(h.host.textContent,/AI received 150/);
 h.find('button','← Previous day').onclick();assert.equal(views.get('TEST').day,'2026-11-02');assert.equal(h.find('button','← Previous day').disabled,true);
 h.find('button','Next day →').onclick();assert.equal(views.get('TEST').day,'2026-11-04');
 h.find('button','Today').onclick();assert.equal(views.get('TEST').day,undefined);assert.match(h.host.textContent,/No completed candles for 05 Nov 2026/);
 render(h.host,data,{now:Date.parse('2026-11-06T10:00:00+05:30'),views});assert.match(h.host.textContent,/No completed candles for 06 Nov 2026/);
});
test('fills from a later day cannot appear at the end of a historical session',()=>{
 const h=harness(),data={stocks:[{symbol:'TEST',bars:session('2026-11-04'),markers:[{side:'BUY',time:'2026-11-04T09:25:00+05:30',price:100},{side:'SELL',time:'2026-11-05T09:25:00+05:30',price:101}]}]};
 render(h.host,data,{now,limit:0,views:new Map([['TEST',{day:'2026-11-04'}]])});assert.match(h.host.textContent,/1 completed fill\(s\) in view/);
 const date=h.find('input','TEST trading date');date.value='2026-11-03';date.onchange();assert.match(h.host.textContent,/No completed candles for 03 Nov 2026/);
});
