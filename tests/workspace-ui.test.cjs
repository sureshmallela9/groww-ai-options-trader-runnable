const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const analysis=require('../backend/src/main/resources/static/analysis-view.js');
const shell=require('../backend/src/main/resources/static/workspace-shell.js');

test('navigation accepts known screens and has a safe default',()=>{
  for(const screen of ['overview','automation','analysis','positions','risk','tools'])assert.equal(shell.screenName(screen),screen);
  for(const value of ['__proto__','constructor','javascript:alert(1)','',undefined])assert.equal(shell.screenName(value),'overview');
  assert.equal(shell.humanState('CALENDAR_MISSING'),'Session calendar missing');
  assert.equal(shell.humanState('NO_TRADE'),'No qualifying trade');
});
test('analysis view preserves reasons, unavailable indicators and deterministic ranking',()=>{
  const candidates=[{symbol:'LOW',score:20},{symbol:'HIGH',score:90}];
  const result=analysis.model({decision:{action:'NO_TRADE',symbol:'TEST',signalConfidence:.82,reasons:['Market breadth is weak.'],analysis:{summary:'Mixed evidence.',indicators:{rsi_14:null},positive_reasons:['Volume increased.'],negative_reasons:['Price weakened.'],risk_notes:['Quotes are stale.']}},selection:{candidates}});
  assert.equal(result.action,'NO_TRADE');assert.equal(result.score,82);assert.equal(result.candidates[0].symbol,'HIGH');
  assert.equal(candidates[0].symbol,'LOW');assert.deepEqual(result.reasons,['Market breadth is weak.']);
  assert.equal(analysis.value(result.indicators.rsi_14),'Not ready');assert.equal(analysis.model({}).action,'WAITING');
});
test('readable analysis renders source text safely and avoids an entry plan for NO_TRADE',()=>{
  class Element{
    constructor(){this.children=[];this._text='';}
    set textContent(value){this._text=String(value);this.children=[];}get textContent(){return this._text+this.children.map(x=>x.textContent).join(' ');}
    set innerHTML(value){throw Error('Source text must not be inserted as HTML');}
    append(...elements){this.children.push(...elements);}replaceChildren(...elements){this._text='';this.children=elements;}
  }
  const original=global.document;global.document={createElement:()=>new Element()};
  try{const target=new Element();analysis.render(target,{state:'NO_TRADE',decision:{action:'NO_TRADE',symbol:'TEST',reasons:['Insufficient volume'],analysis:{summary:'Example',indicators:{rsi_14:55},positive_reasons:['<img src=x onerror=alert(1)>'],negative_reasons:[],risk_notes:['Small sample']}}});
    assert.match(target.textContent,/Positive signs/);assert.match(target.textContent,/Insufficient volume/);
    assert.match(target.textContent,/<img src=x onerror=alert\(1\)>/);assert.doesNotMatch(target.textContent,/Proposed paper entry/);
  }finally{global.document=original;}
});

test('a completed no-trade stock comparison replaces an older buy proposal and shows its real timestamp',()=>{
  const view=analysis.model({decision:{action:'BUY',symbol:'OLD',referencePrice:100},selection:{status:'NO_TRADE',observedAt:'2026-10-30T08:30:00Z',candidates:[],reasons:['NIFTY is sideways.']}});
  assert.equal(view.action,'NO_TRADE');assert.equal(view.hasDecision,false);assert.equal(view.completedSelection,true);
  assert.equal(view.asOf,'2026-10-30T08:30:00Z');assert.equal(view.summary,'NIFTY is sideways.');assert.equal(view.entry,undefined);
});

test('completed comparisons display no-trade and candidate-specific reasons instead of awaiting analysis',()=>{
  class Element{constructor(){this.children=[];this._text='';}set textContent(v){this._text=String(v);this.children=[];}get textContent(){return this._text+' '+this.children.map(e=>e.textContent).join(' ');}append(...v){this.children.push(...v);}replaceChildren(...v){this._text='';this.children=v;}}
  const original=global.document;global.document={createElement:()=>new Element()};
  try{const target=new Element();analysis.render(target,{selection:{status:'NO_TRADE',observedAt:'2026-10-30T08:30:00Z',newsShortlist:[{symbol:'M&M'}],candidates:[{symbol:'M&M',score:90,candidateStatus:'NO_TRADE',market:{marketRegime:'SIDEWAYS',reasons:['NIFTY trend is sideways; bullish confirmation is required.']}}],reasons:['No qualifying stock.']}});
    assert.match(target.textContent,/Stock comparison complete/);assert.match(target.textContent,/No trade/);assert.match(target.textContent,/News shortlist confirmation/);assert.match(target.textContent,/bullish confirmation is required/);
    assert.doesNotMatch(target.textContent,/Waiting for stock analysis|Awaiting analysis|Proposed paper entry/);
  }finally{global.document=original;}
});
test('workspace contains separate screens, a real-mode activation dialog and preserved research tools',()=>{
  const dir=path.join(__dirname,'../backend/src/main/resources/static');const html=fs.readFileSync(path.join(dir,'index.html'),'utf8');
  for(const name of ['overview','automation','analysis','positions','risk','tools'])assert.ok(html.includes('data-screen="'+name+'"'));
  for(const id of ['mode-paper','mode-real','realModeDialog','workspace-connect-dialog','workspace-readable-analysis'])assert.ok(html.includes('id="'+id+'"'));
  assert.match(html,/Switch to real trading/);assert.match(html,/real-ip-registered/);assert.match(html,/execution-mode.js/);assert.match(html,/aria-pressed="true" class="selected">Paper trading/);
  assert.ok(html.includes('id="research-sector"'));assert.ok(html.includes('href="/workspace/tools"'));
});

test('analysis compares only the explicit news candidates while retaining market coverage and short direction',()=>{
  const view=analysis.model({decision:{action:'SELL',symbol:'THREE',referencePrice:100,stopLoss:102,targetPrice:96},selection:{candidateSymbols:['ONE','TWO','THREE'],marketContextCoverage:50,candidates:[{symbol:'OUTSIDE',score:100},{symbol:'ONE',score:70},{symbol:'TWO',score:60},{symbol:'THREE',score:90}]}});
  assert.equal(view.action,'SELL');assert.equal(view.candidateCount,3);assert.equal(view.contextCoverage,50);
  assert.deepEqual(view.candidates.map(row=>row.symbol),['THREE','ONE','TWO']);assert.equal(view.stop,102);assert.equal(view.target,96);
});

test('real proposal is labelled with a broker-verified ceiling rather than paper margin',()=>{
 class Element{constructor(){this.children=[];this._text='';}set textContent(v){this._text=String(v);this.children=[];}get textContent(){return this._text+' '+this.children.map(n=>n.textContent).join(' ');}append(...n){this.children.push(...n);}replaceChildren(...n){this.children=n;this._text='';}}
 const previous=global.document;global.document={createElement:()=>new Element()};try{
  const host=new Element();analysis.render(host,{decision:{mode:'REAL',action:'BUY',symbol:'TEST',realMaxLeverage:5,paperLeverage:1,positionValue:5000,requestedQuantity:50,cashRiskBudget:50}});
  assert.match(host.textContent,/Proposed real entry/);assert.match(host.textContent,/Real exposure ceiling 5×/);assert.match(host.textContent,/Groww margin/);assert.doesNotMatch(host.textContent,/Paper buying-power|Proposed paper entry/);
 }finally{global.document=previous;}
});

test('daily loss display uses the effective percentage and ignores retired cash caps',()=>{
  assert.equal(shell.dailyLossLimit({policy:{capital:100000,dailyLossPercent:2,dailyLossAmount:250}}),2000);
  assert.equal(shell.dailyLossLimit({policy:{capital:1800,dailyLossPercent:2,dailyLossAmount:250}}),36);
  assert.equal(shell.dailyLossLimit({dailyLossLimit:1000,policy:{capital:100000,dailyLossPercent:2}}),1000);
  const dir=path.join(__dirname,'../backend/src/main/resources/static');
  const html=fs.readFileSync(path.join(dir,'index.html'),'utf8'),source=fs.readFileSync(path.join(dir,'workspace-shell.js'),'utf8');
  assert.doesNotMatch(html+source,/dailyLossAmount|₹250 maximum|maximum 250/);
  assert.match(html,/configured daily loss percentage/);
});

test('daily loss display uses configured exposure with separate paper and real multipliers',()=>{
  const risk={mode:'PAPER',policy:{capital:1800,maxPositionValue:1800,dailyLossPercent:2},intraday:{paperLeverage:5,realMaxLeverage:3}};
  assert.equal(shell.dailyLossBase(risk),9000);assert.equal(shell.dailyLossLimit(risk),180);
  risk.mode='REAL';assert.equal(shell.dailyLossBase(risk),5400);assert.equal(shell.dailyLossLimit(risk),108);
  risk.policy.maxPositionValue=1000;assert.equal(shell.dailyLossBase(risk),3000);assert.equal(shell.dailyLossLimit(risk),60);
  assert.equal(shell.dailyLossLimit({...risk,dailyLossLimit:50}),50);
  assert.equal(shell.dailyLossBase({...risk,dailyLossExposureBase:2500}),2500);
  const html=fs.readFileSync(path.join(__dirname,'../backend/src/main/resources/static/index.html'),'utf8');
  assert.match(html,/selected-mode leverage/);assert.match(html,/₹1,800 × 5 × 2% = ₹180/);
});

test('per-trade exposure risk shows the 45 rupee budget separately from cash',()=>{
  const risk={mode:'REAL',policy:{capital:1800,maxPositionValue:1800,riskPerTradePercent:.5},intraday:{paperLeverage:3,realMaxLeverage:5}};
  assert.equal(shell.perTradeRiskLimit(risk),45);risk.mode='PAPER';assert.equal(shell.perTradeRiskLimit(risk),27);
  assert.equal(shell.perTradeRiskLimit({...risk,riskPerTradeLimit:20}),20);
  const view=analysis.model({decision:{action:'BUY',mode:'REAL',symbol:'TEST',riskBudget:45,cashRiskBudget:9,riskExposureBase:9000,sizing:{quantityLimits:{exposure:54,stopRisk:45,planningMarginAndCosts:54}}}});
  assert.equal(view.riskBudget,45);assert.equal(view.cashRisk,9);assert.equal(view.sizing.quantityLimits.stopRisk,45);
});
