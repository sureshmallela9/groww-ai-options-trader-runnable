const {test}=require('node:test'),assert=require('node:assert/strict');
const {candleModel,signal,render,COLORS}=require('../backend/src/main/resources/static/shortlist-charts.js');
const bars=Array.from({length:750},(_,i)=>({timestamp:new Date(Date.parse('2026-10-20T04:00:00Z')+i*300000).toISOString(),open:100,high:102,low:99,close:i%2?99.5:101,volume:1000}));
test('range affects the chart only; all source candles remain available',()=>{
  const model=candleModel(bars,[],75);assert.equal(model.total,750);assert.equal(model.candles.length,75);assert.equal(bars.length,750);
  assert.equal(candleModel(bars,[],0).candles.length,750);
  assert.equal(model.candles[0].color,COLORS.BEARISH);
  for(const c of model.candles)for(const k of ['x','top','bottom','highY','lowY','width'])assert.ok(Number.isFinite(c[k]));
});
test('only actual BUY and SELL fills within the displayed window create dots',()=>{
  const model=candleModel(bars,[{side:'BUY',time:bars.at(-1).timestamp,price:100},{side:'SELL',time:bars.at(-2).timestamp,price:101},{side:'BUY',time:bars[0].timestamp,price:100},{side:'BULLISH',time:bars.at(-1).timestamp,price:100}]);
  assert.deepEqual(model.markers.map(m=>m.side),['BUY','SELL']);
});
test('missing, stale and paused AI evidence cannot appear as fresh bullish signals',()=>{
  const now=Date.parse('2026-11-02T05:10:00Z');const stock={analysis:{trend:'bullish',as_of:'2026-11-02T05:05:00Z'},aiReceivedAt:'2026-11-02T05:09:59Z'};
  assert.equal(signal(stock,now),'BULLISH');assert.equal(signal(stock,now,false),'STALE');assert.equal(signal(stock,now+31000),'STALE');assert.equal(signal({},now),'WAITING');
});
test('a real fill after the latest completed candle appears immediately without inventing a candle',()=>{
  const time=new Date(Date.parse(bars.at(-1).timestamp)+360000).toISOString();
  const model=candleModel(bars,[{side:'BUY',time,price:105}]);
  assert.equal(model.markers.length,1);assert.equal(model.candles.length,75);
  assert.ok(model.markers[0].x>model.candles.at(-1).x);assert.ok(model.markers[0].x<900);
  assert.ok(model.markers[0].y>=0&&model.markers[0].y<270);
});
class Element{constructor(tag){this.tag=tag;this.children=[];this.attributes={};this.dataset={};this._text='';this.classList={add(){}};}set textContent(v){this._text=String(v);this.children=[];}get textContent(){return this._text+' '+this.children.map(c=>c.textContent).join(' ');}set innerHTML(v){throw Error('No untrusted HTML');}append(...nodes){this.children.push(...nodes);}replaceChildren(...nodes){this._text='';this.children=nodes;}setAttribute(k,v){this.attributes[k]=v;}}
test('three stock cards show OHLCV, received count and inert source text',()=>{
  const doc={createElement:tag=>new Element(tag),createElementNS:(_,tag)=>new Element(tag)},host=new Element('div');host.ownerDocument=doc;
  const now=Date.parse('2026-11-02T05:10:00Z');
  render(host,{state:'READY',automationEnabled:true,stocks:['ONE','TWO','<script>bad</script>'].map(symbol=>({symbol,bars,aiCandleCount:750,aiReceivedAt:'2026-11-02T05:09:59Z',analysis:{trend:'bullish',as_of:'2026-11-02T05:05:00Z'}}))},{now});
  const nodes=n=>[n,...n.children.flatMap(nodes)];
  assert.equal(nodes(host).filter(n=>n.tag==='svg').length,3);assert.match(host.textContent,/AI received 750 candles/);assert.match(host.textContent,/Volume/);assert.match(host.textContent,/<script>bad<\/script>/);assert.equal(nodes(host).filter(n=>n.tag==='script').length,0);
  assert.match(host.textContent,/Download all candles/);
});
