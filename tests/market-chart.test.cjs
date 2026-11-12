const {test}=require('node:test'),assert=require('node:assert/strict');
const {render}=require('../backend/src/main/resources/static/shortlist-charts.js');
class Element{
 constructor(tag){this.tag=tag;this.children=[];this.attributes={};this.style={};this.dataset={};this._text='';this.classList={add(){}};this.listeners={};}
 set textContent(v){this._text=String(v);this.children=[];}get textContent(){return this._text+' '+this.children.map(c=>c.textContent).join(' ');}
 append(...n){this.children.push(...n);}replaceChildren(...n){this.children=n;this._text='';}setAttribute(k,v){this.attributes[k]=v;}addEventListener(k,v){this.listeners[k]=v;}
 getBoundingClientRect(){return {left:0,top:0,width:960,height:390};}set innerHTML(v){throw Error('Unsafe HTML');}
}
const now=Date.parse('2026-11-06T06:00:00Z'),bars=Array.from({length:20},(_,i)=>({timestamp:new Date(Date.parse('2026-11-06T03:45:00Z')+i*300000).toISOString(),open:100,high:102,low:99,close:101,volume:100+i}));
function draw(depth){const host=new Element('div');host.ownerDocument={createElement:t=>new Element(t),createElementNS:(_,t)=>new Element(t)};const views=new Map();render(host,{stocks:[{symbol:'TEST',bars,marketDepth:depth,markers:[]}]},{now,limit:10,views,compact:true});const nodes=n=>[n,...n.children.flatMap(nodes)];return {host,views,nodes:()=>nodes(host)};}
test('overview candle chart contains volume, right scale and hover OHLC and retains compact mode after zoom',()=>{
 const h=draw(),svg=h.nodes().find(n=>n.tag==='svg');assert.equal(svg.attributes.viewBox,'0 0 960 390');assert.match(h.host.textContent,/Volume/);assert.match(h.host.textContent,/Price · INR/);assert.match(h.host.textContent,/Vol 119/);
 svg.listeners.pointermove({clientX:100,clientY:100});assert.match(h.host.textContent,/O 100\s+H 102\s+L 99\s+C 101/);assert.ok(h.nodes().some(n=>n.attributes.class==='candle-crosshair'));
 h.nodes().find(n=>n.tag==='button'&&n.textContent.trim()==='− Zoom out').onclick();assert.doesNotMatch(h.host.textContent,/News direction|Waiting for the first shortlist/);assert.match(h.host.textContent,/Showing 20 of 20/);
});
test('only supplied ratios show percentages, unavailable data never fabricates balance',()=>{
 const h=draw({available:true,buyPercent:70,sellPercent:30,buyQuantity:700,sellQuantity:300,receivedAt:new Date(now-1000).toISOString(),basis:'TOTAL_ORDER_QUANTITY',sessionOpen:true});
 assert.match(h.host.textContent,/Buying 70%/);assert.match(h.host.textContent,/Selling 30%/);assert.match(h.host.textContent,/not executed/);assert.match(h.host.textContent,/Latest received/);
 const absent=draw({available:false,message:'No totals'});assert.match(absent.host.textContent,/percentages unavailable/);assert.doesNotMatch(absent.host.textContent,/Buying 50/);
});
test('old depth is visibly stale and partial depth is clearly labelled',()=>{
 const h=draw({available:true,buyPercent:40,sellPercent:60,buyQuantity:4,sellQuantity:6,receivedAt:new Date(now-60000).toISOString(),basis:'VISIBLE_DEPTH',sessionOpen:false});
 assert.match(h.host.textContent,/Stale/);assert.match(h.host.textContent,/Visible depth only/);assert.match(h.host.textContent,/Market session closed/);
});
