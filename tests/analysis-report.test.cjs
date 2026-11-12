const {test}=require('node:test'),assert=require('node:assert/strict');
const report=require('../backend/src/main/resources/static/assets/analysis-report.js');
class Element{
  constructor(tag){this.tag=tag;this.children=[];this.attrs={};this.classList={add(){}};this._text='';}
  set textContent(value){this._text=String(value);this.children=[];}get textContent(){return this._text+this.children.map(row=>row.textContent).join(' ');}
  append(...items){this.children.push(...items);}replaceChildren(...items){this.children=items;this._text='';}setAttribute(key,value){this.attrs[key]=String(value);}
  set innerHTML(value){throw Error('Research evidence must be rendered as text.');}
}
function documentTest(run){const before=global.document;global.document={createElement:tag=>new Element(tag),createElementNS:(_,tag)=>new Element(tag)};try{run();}finally{global.document=before;}}
test('context displays partial evidence and never turns descriptive strength into probability',()=>documentTest(()=>{
  const parent=new Element('div');report.renderContext(parent,{data_quality:{status:'limited',freshness:'historical_not_live_verified',reasons:['<img src=x onerror=alert(1)>']},timeframe_alignment:'unconfirmed',timeframes:[{interval:'1h',bars:8,trend:'mixed',status:'partial',reason:'Needs full history'}],decision:{research_action:'WAIT',trend_strength:'unconfirmed',risk_level:'elevated',confirmation_needed:['Higher timeframe is incomplete'],invalidation:['New gaps require reassessment']},regime:{label:'transition'}});
  assert.match(parent.textContent,/WAIT/);assert.match(parent.textContent,/Needs full history/);assert.match(parent.textContent,/not probabilities/);assert.match(parent.textContent,/<img src=x onerror=alert\(1\)>/);
}));
test('replay handles zero trades without fabricated win rate and plots finite equity',()=>documentTest(()=>{
  const parent=new Element('div');report.renderReplay(parent,{symbol:'DEMO',metrics:{trades:0,net_pnl:0,max_drawdown_pct:0},parameters:{fee_bps:5,slippage_bps:5},trades:[],equity_curve:[{equity:10000},{equity:10000}],limitations:['Synthetic data']});
  assert.match(parent.textContent,/No trades met/);assert.match(parent.textContent,/Not available/);assert.match(parent.textContent,/no order or paper position/);
  const svg=parent.children[0].children.find(row=>row.tag==='svg');assert.ok(svg);assert.doesNotMatch(svg.children[0].attrs.d,/NaN|Infinity/);
}));
test('synthetic example is explicitly labelled, consistent OHLCV and spans usable higher frames',()=>{
  const example=report.syntheticExample();assert.equal(example.symbol,'DEMO_RESEARCH');assert.equal(example.bars.length,900);
  for(const bar of example.bars){assert.ok(bar.low<=bar.open&&bar.high>=bar.close);assert.ok(Number.isFinite(Date.parse(bar.timestamp)));}
  assert.equal(new Set(example.bars.map(row=>row.timestamp)).size,900);assert.equal(example.analysis_options.mode,'historical');
});
