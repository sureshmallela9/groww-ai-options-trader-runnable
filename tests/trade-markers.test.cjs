const {test}=require('node:test'),assert=require('node:assert/strict');
const {pendingExits,drawFill,FILL_COLORS}=require('../backend/src/main/resources/static/trade-markers.js');
const {candleModel,render}=require('../backend/src/main/resources/static/shortlist-charts.js');
const {chartModel}=require('../backend/src/main/resources/static/automatic-paper.js');
const request='2026-11-02T06:27:09Z';
const position={intentId:'paper-one',symbol:'TEST',state:'EXIT_PENDING',positionSide:'LONG',filledQuantity:5,remainingQuantity:5,entryPrice:100,
  timeline:[{state:'POSITION_OPEN',at:'2026-11-02T06:00:00Z'},{state:'EXIT_PENDING',at:request},{state:'EXIT_PENDING',at:'2026-11-02T06:28:00Z'}],incident:'INSUFFICIENT_EXIT_DEPTH'};
const bars=[{timestamp:'2026-11-02T06:25:00Z',open:100,high:101,low:98,close:99,volume:1000},{timestamp:'2026-11-02T06:30:00Z',open:99,high:100,low:97,close:98,volume:1000}];
test('pending long exit has an amber request annotation without inventing a fill or price',()=>{
 const pending=pendingExits([position],'TEST');assert.equal(pending.length,1);assert.equal(pending[0].side,'SELL');assert.equal(pending[0].requestedAt,request);assert.equal(pending[0].isFill,false);assert.equal(pending[0].price,undefined);
 const candles=candleModel(bars,[],75,pending);assert.equal(candles.markers.length,0);assert.equal(candles.pending.length,1);assert.ok(Number.isFinite(candles.pending[0].x));
 const line=chartModel(bars,[],900,260,pending);assert.equal(line.markers.length,0);assert.equal(line.pending.length,1);assert.ok(Number.isFinite(line.pending[0].x));
});
test('short exits use BUY-cover and pending flags disappear when closed',()=>{
 assert.equal(pendingExits([{...position,positionSide:'SHORT'}],'TEST')[0].side,'BUY');
 assert.equal(pendingExits([{...position,state:'POSITION_CLOSED',remainingQuantity:0}],'TEST').length,0);
 assert.equal(pendingExits([position],'OTHER').length,0);
});
test('unknown request time stays unknown and does not fabricate a chart timestamp',()=>{
 const pending=pendingExits([{...position,timeline:[]}],'TEST');assert.equal(pending[0].requestedAt,'');assert.equal(candleModel(bars,[],75,pending).pending.length,0);
});
test('completed fills remain separate from pending requests in both chart models',()=>{
 const pending=pendingExits([position],'TEST'),fills=[{side:'BUY',price:100,time:'2026-11-02T06:25:01Z'}];
 assert.equal(candleModel(bars,fills,75,pending).markers.length,1);assert.equal(chartModel(bars,fills,900,260,pending).markers.length,1);
});
class Element{constructor(tag){this.tag=tag;this.children=[];this.attributes={};this.dataset={};this._text='';this.classList={add(){}};}set textContent(v){this._text=String(v);this.children=[];}get textContent(){return this._text+' '+this.children.map(c=>c.textContent).join(' ');}set innerHTML(v){throw Error('Untrusted HTML');}append(...nodes){this.children.push(...nodes);}replaceChildren(...nodes){this._text='';this.children=nodes;}setAttribute(k,v){this.attributes[k]=v;}}

test('order fills use blue/purple labelled triangles distinct from candles and pending requests',()=>{
 const doc={createElementNS:(_,t)=>new Element(t)},svg=new Element('svg');
 for(const side of ['BUY','SELL'])drawFill(doc,svg,{side,x:100,y:100,price:101,time:'2026-11-02T06:25:00Z'});
 assert.deepEqual(svg.children.map(n=>n.attributes['data-order-side']),['BUY','SELL']);
 assert.equal(svg.children[0].children[2].attributes.fill,FILL_COLORS.BUY);
 assert.equal(svg.children[1].children[2].attributes.fill,FILL_COLORS.SELL);
 assert.match(svg.children[0].children[2].attributes.d,/113/);assert.match(svg.children[1].children[2].attributes.d,/87/);
 assert.match(svg.children[0].attributes['aria-label'],/Actual paper BUY fill/);assert.match(svg.children[1].textContent,/SELL/);
 for(const color of Object.values(FILL_COLORS))assert.ok(!['#38d9a9','#ff7185','#f6c56f'].includes(color));
 assert.equal(drawFill(doc,svg,{side:'PENDING',x:100,y:100}),null);
});

test('zoom and history navigation keep candle inputs intact and exclude later fills from older views',()=>{
 const history=Array.from({length:80},(_,i)=>({timestamp:new Date(Date.parse('2026-11-02T03:45:00Z')+i*300000).toISOString(),open:100,high:102,low:99,close:101,volume:1000}));
 const fills=[{side:'BUY',time:history[5].timestamp,price:100},{side:'SELL',time:history[79].timestamp,price:101}];
 const original=JSON.stringify(history),latest=candleModel(history,fills,20),older=candleModel(history,fills,20,[],{endTime:history[19].timestamp});
 assert.equal(latest.candles.length,20);assert.equal(latest.atLatest,true);assert.equal(older.atOldest,true);assert.equal(older.atLatest,false);
 assert.deepEqual(older.markers.map(m=>m.side),['BUY']);assert.equal(older.total,80);assert.equal(JSON.stringify(history),original);
 const anchored=candleModel([...history,{...history[79],timestamp:'2026-11-02T12:00:00Z'}],fills,20,[],{endTime:older.to});assert.equal(anchored.to,older.to);
 const doc={createElement:t=>new Element(t),createElementNS:(_,t)=>new Element(t)},host=new Element('div');host.ownerDocument=doc;
 const nodes=e=>[e,...e.children.flatMap(nodes)];
 render(host,{stocks:[{symbol:'TEST',bars:history,markers:fills,aiCandleCount:80}]},{limit:20,views:new Map([['TEST',{day:'2026-11-02'}]])});
 const button=label=>nodes(host).find(n=>n.tag==='button'&&n.textContent.trim()===label);
 button('+ Zoom in').onclick();assert.match(host.textContent,/Showing 10 of 80 candles/);assert.match(host.textContent,/AI received 80 candles/);
 button('← Older').onclick();assert.match(host.textContent,/Historical candle view/);
 button('Reset view').onclick();assert.match(host.textContent,/Showing 20 of 80 candles/);assert.match(host.textContent,/Latest completed candles/);
 assert.equal(JSON.stringify(history),original);
});
test('pending status renders a visible banner and flag rather than a SELL fill',()=>{
 const doc={createElement:t=>new Element(t),createElementNS:(_,t)=>new Element(t)},host=new Element('div');host.ownerDocument=doc;
 render(host,{state:'READY',positions:[position],stocks:[{symbol:'TEST',bars,markers:[],aiCandleCount:2}]},{views:new Map([['TEST',{day:'2026-11-02'}]])});
 const nodes=e=>[e,...e.children.flatMap(nodes)];assert.match(host.textContent,/Exit pending · SELL 5/);assert.match(host.textContent,/No exit fill recorded/);assert.equal(nodes(host).filter(e=>e.tag==='circle').length,0);
 assert.equal(nodes(host).filter(e=>e.tag==='path'&&e.attributes.stroke==='#f6c56f').length,1);
});
