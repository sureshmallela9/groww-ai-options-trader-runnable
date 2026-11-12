const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const {today,presetRange,validateRange,csv}=require('../backend/src/main/resources/static/profit-loss.js');
test('inclusive ranges use IST and calendar month arithmetic across leap dates',()=>{
 assert.equal(today(Date.parse('2026-11-05T18:30:00Z')),'2026-11-06');
 assert.deepEqual(presetRange('10d','2026-11-06'),{from:'2026-10-28',to:'2026-11-06',groupBy:'DAY'});
 assert.deepEqual(presetRange('10m','2026-11-06'),{from:'2026-01-07',to:'2026-11-06',groupBy:'MONTH'});
 assert.equal(presetRange('1y','2024-02-29').from,'2023-03-01');assert.equal(presetRange('1y','2026-03-31').from,'2025-04-01');
 assert.equal(presetRange('ytd','2026-11-06').from,'2026-01-01');assert.equal(presetRange('custom'),null);
});
test('invalid dates and future ranges are refused and CSV text cannot become a spreadsheet formula',()=>{
 assert.match(validateRange('2026-02-30','2026-03-01','2026-11-06'),/valid/);assert.match(validateRange('2026-11-05','2026-11-04','2026-11-06'),/before/);assert.match(validateRange('2026-11-06','2026-11-07','2026-11-06'),/future/);
 const output=csv({mode:'PAPER',groups:[{period:'=1+1',from:'2026-01-01',to:'2026-01-01',net:-10,charges:2}]});assert.ok(output.startsWith('\uFEFF'));assert.match(output,/"'=1\+1"/);assert.match(output,/"-10"/);
});
class Element{
 constructor(tag='div'){this.tag=tag;this.children=[];this.style={};this.value='';this._text='';this.attrs={};}
 set textContent(v){this._text=String(v);this.children=[];}get textContent(){return this._text+' '+this.children.map(n=>n.textContent).join(' ');}
 append(...n){this.children.push(...n);}replaceChildren(...n){this.children=n;this._text='';}setAttribute(k,v){this.attrs[k]=v;}removeAttribute(k){delete this.attrs[k];}
 set innerHTML(v){throw Error('Unsafe HTML');}
}
function harness(){const ids=new Map(),$=id=>{if(!ids.has(id))ids.set(id,new Element());return ids.get(id);};$('pnl-preset').value='10d';$('pnl-mode').value='CURRENT';const pending=[];let connection,status;
 const context={document:{getElementById:$,createElement:t=>new Element(t)},Intl,Date,URLSearchParams,URL,Blob,setTimeout,WorkspaceAPI:{platform:url=>new Promise(resolve=>pending.push({url,resolve}))},AutoPaperUI:{subscribeConnection:fn=>{connection=fn;},subscribe:fn=>{status=fn;}}};
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../backend/src/main/resources/static/profit-loss.js'),'utf8'),context);
 return {$,pending,context,connection:v=>connection(v),status:v=>status(v)};
}
const report=mode=>({mode,from:'2026-11-01',to:'2026-11-06',groupBy:'DAY',asOf:'2026-11-06T05:00:00Z',totals:{profit:10,loss:0,net:10,charges:2,realizedTrades:1},groups:[],trades:[],complete:true});
test('paper/real report requests are separate and late responses cannot cross modes or disconnect',async()=>{
 const h=harness();h.connection({state:'connected'});const first=h.context.ProfitLossReports.enter();assert.match(h.pending[0].url,/mode=PAPER/);
 h.status({automation:{mode:'REAL'}});assert.match(h.pending[1].url,/mode=REAL/);h.pending[1].resolve(report('REAL'));await new Promise(r=>setImmediate(r));
 assert.match(h.$('pnl-range-label').textContent,/REAL/);h.pending[0].resolve(report('PAPER'));await first;assert.match(h.$('pnl-range-label').textContent,/REAL/);
 const third=h.context.ProfitLossReports.enter();h.connection({state:'disconnected'});h.pending[2].resolve(report('REAL'));await third;assert.equal(h.$('pnl-net').textContent.trim(),'—');assert.equal(h.$('pnl-export').disabled,true);
});
test('changing custom dates cancels an in-flight report and does not send new requests until applied',async()=>{
 const h=harness();h.connection({state:'connected'});const request=h.context.ProfitLossReports.enter();h.$('pnl-from').value='2026-01-01';h.$('pnl-from').onchange();h.pending[0].resolve(report('PAPER'));await request;
 assert.equal(h.pending.length,1);assert.equal(h.$('pnl-range-label').textContent.trim(),'');assert.equal(h.$('pnl-preset').value,'custom');assert.equal(h.$('pnl-export').disabled,true);
});
