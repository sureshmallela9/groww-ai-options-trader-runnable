(function(root){
  'use strict';
  const list=value=>Array.isArray(value)?value.filter(item=>typeof item==='string'&&item.trim()):[];
  function model(automation={}){
    const selection=automation.selection||{};
    let decision=automation.decision&&typeof automation.decision==='object'?automation.decision:{};
    // An earlier proposal must not be presented as this comparison's decision.
    if(selection.status==='NO_TRADE'||(selection.selected?.symbol&&decision.symbol&&selection.selected.symbol!==decision.symbol)||
      (Number.isFinite(Date.parse(selection.observedAt))&&Number.isFinite(Date.parse(decision.createdAt))&&Date.parse(decision.createdAt)<Date.parse(selection.observedAt)))decision={};
    const analysis=decision.analysis&&typeof decision.analysis==='object'?decision.analysis:{};
    const completedSelection=Boolean(selection.status&&selection.observedAt);
    const contextCandidates=Array.isArray(selection.candidates)?selection.candidates:[];
    const allowed=Array.isArray(selection.candidateSymbols)?new Set(selection.candidateSymbols):null;
    const candidates=allowed?contextCandidates.filter(row=>allowed.has(row.symbol)):contextCandidates;
    const shortlist=(Array.isArray(selection.shortlist)?selection.shortlist:Array.isArray(selection.newsShortlist)?selection.newsShortlist:[]).map(item=>candidates.find(row=>row.symbol===item.symbol)).filter(Boolean);
    return {completedSelection,hasDecision:Boolean(decision.action),candidateCount:candidates.length,contextCoverage:selection.marketContextCoverage,shortlist,
      intradayRequired:selection.intradayRequired,intradayCheck:selection.selected?.intradayCheck,scanCoverage:selection.scanCoverage,screeningCoverage:selection.screeningCoverage, capitalPriceCheck:decision.capitalPriceCheck||selection.capitalPriceCheck, position:automation.activePosition,expectedPrices:decision.expectedPrices,selectionMode:decision.selectionMode||selection.selectionMode,
      mode:decision.mode,leverage:decision.mode==='REAL'?decision.realMaxLeverage:decision.paperLeverage,margin:decision.marginRequired,notional:decision.positionValue,stopPercent:decision.stopLossPercent,targetPercent:decision.targetProfitPercent,cashRisk:decision.cashRiskBudget,riskBudget:decision.riskBudget??decision.cashRiskBudget,riskExposureBase:decision.riskExposureBase,sizing:decision.sizing,
      signal:decision.analysisSignal,action:decision.action||(selection.status==='NO_TRADE'?'NO_TRADE':selection.selected?'CANDIDATE_SELECTED':'WAITING'),symbol:decision.symbol||selection.selected?.symbol||'',
      summary:analysis.summary||(selection.status==='NO_TRADE'?list(selection.reasons).join(' · '):'')||automation.message||'No completed stock analysis is available yet.',
      asOf:analysis.as_of||decision.candleTimestamp||selection.observedAt||'',trend:analysis.trend||'',
      context:analysis.analysis_context,indicators:analysis.indicators||{},positive:list(analysis.positive_reasons),negative:list(analysis.negative_reasons),
      risks:list(analysis.risk_notes),reasons:list(decision.reasons).length?list(decision.reasons):list(selection.reasons),
      quantity:decision.requestedQuantity,entry:decision.referencePrice,stop:decision.stopLoss,target:decision.targetPrice,
      score:Number.isFinite(decision.signalConfidence)?decision.signalConfidence*100:selection.selected?.score,
      market:decision.market||selection.selected?.market||{},sources:decision.knowledgeContext||[],news:decision.newsContext||[],
      candidates:candidates.slice().sort((a,b)=>(Number(b.score)||0)-(Number(a.score)||0)).slice(0,20)};
  }
  function intradayLabel(row){const check=row.intradayCheck||{};return {PASSED:'MIS preflight passed',REJECTED:'Intraday rejected',UNAVAILABLE:'Intraday check unavailable',PENDING:'Intraday check pending'}[check.status]||'Not checked';}
  function value(input,suffix=''){
    return typeof input==='number'&&Number.isFinite(input)?new Intl.NumberFormat('en-IN',{maximumFractionDigits:2}).format(input)+suffix:'Not ready';
  }
  function stamp(input){const time=Date.parse(input);return Number.isFinite(time)?new Intl.DateTimeFormat('en-IN',{timeZone:'Asia/Kolkata',day:'2-digit',month:'short',hour:'2-digit',minute:'2-digit'}).format(time)+' IST':'Awaiting completed candles';}
  function render(container,automation){
    const view=model(automation);container.replaceChildren();
    const text=(parent,tag,content,className)=>{const element=document.createElement(tag);element.textContent=content;if(className)element.className=className;parent.append(element);return element;};
    if(view.position){const p=view.position,card=text(container,'section','','source-card');text(card,'h2',(p.mode==='REAL'?'Real position':'Paper position')+' · '+p.symbol+' · '+(p.positionSide||'LONG'));
      text(card,'p','Entry ₹'+value(p.entryPrice)+' · Latest ₹'+value(p.lastPrice)+' · Stop ₹'+value(p.stopLoss)+' · Target ₹'+value(p.targetPrice));
      if(p.mode==='REAL')text(card,'p','Broker-checked order margin ₹'+value(p.marginRequired)+' · Cost reserve ₹'+value(p.marginCostReserve)+' · Exposure ceiling '+value(p.maxIntradayLeverage)+'× · Checked '+stamp(p.marginCheckedAt),'hint');
      else if(p.paperLeverage)text(card,'p','Paper margin assumption '+value(p.paperLeverage)+'× · Entry margin ₹'+value(p.marginRequired)+' · Exposure ₹'+value(p.positionValue),'hint');
      text(card,'p','Open P&L ₹'+value(p.unrealizedPnl)+' · '+(p.lastAiAction||'Waiting for AI update'));
      if(p.lastAiReceivedAt)text(card,'p','AI updated '+stamp(p.lastAiReceivedAt)+' · '+(p.lastAiAssessment||''));
      text(card,'p','Position snapshots are sent about every five seconds when data and services are available. The agreed stop and target remain fixed.','hint');}
    const header=text(container,'div','','analysis-head'),title=text(header,'div','');text(title,'p',view.completedSelection&&!view.hasDecision?'LATEST STOCK COMPARISON':'LATEST COMPLETED DECISION','eyebrow');
    text(title,'h2',view.symbol||(view.completedSelection?'Stock comparison complete':'Waiting for stock analysis'));text(title,'small',stamp(view.asOf),'muted');
    text(header,'span',view.action==='BUY'?'Buy / long proposal':view.action==='SELL'?'Sell / short proposal':view.action==='NO_TRADE'?'No trade':view.action==='CANDIDATE_SELECTED'?'Awaiting final decision':'Awaiting analysis','badge '+view.action.toLowerCase());
    if(view.signal)text(container,'p','Candle signal: '+view.signal.direction+' · '+view.signal.basis+' · Descriptive evidence, not a guaranteed price move.','info-note');
    const priceCheck=view.capitalPriceCheck;
    if(priceCheck?.enabled)text(container,'p','Capital share-price limit: cash capital ₹'+value(priceCheck.capital)+' · shares '+(priceCheck.sharePriceInclusive?'up to ₹':'strictly below ₹')+value(priceCheck.maxSharePrice)+'. Leverage does not raise this limit.','info-note');
    text(container,'p',view.summary,'analysis-summary');root.AnalysisReport?.renderContext(container,view.context);
    if(view.intradayRequired)text(container,'p','Intraday / MIS first: only candidates with a fresh successful Groww preflight can reach final selection. A one-share margin estimate does not guarantee 5× leverage. Exact quantity, current funds and risk are checked again before entry.','info-note');
    if(view.intradayCheck)text(container,'p',intradayLabel({intradayCheck:view.intradayCheck})+' · '+stamp(view.intradayCheck.checkedAt)+' · '+(view.intradayCheck.reason||''),'hint');
    if(view.selectionMode==='PRICE_FALLBACK')text(container,'p','Price fallback: fresh news had no directional candidate. This stock was considered from completed candles below the configured ceiling.','info-note');
    if(automation.state==='REJECTED'){let reason=automation.message||'The independent entry checks rejected this proposal.';try{const values=JSON.parse(reason);if(Array.isArray(values))reason=values.join(' · ');}catch{}text(container,'p','Entry rejected: '+reason,'info-note');}
    if(view.reasons.length){const box=text(container,'div','','info-note');text(box,'strong',view.action==='NO_TRADE'?'Why no entry was approved':'Decision checks');const ul=text(box,'ul','');view.reasons.forEach(reason=>text(ul,'li',reason));}
    if(Object.keys(view.indicators).length){
      const metrics=text(container,'div','','analysis-metrics');
      const definitions=[['Latest close',view.indicators.latest_close],['VWAP',view.indicators.vwap],['RSI 14',view.indicators.rsi_14],['MACD histogram',view.indicators.macd_histogram],['EMA 9',view.indicators.ema_9],['EMA 20',view.indicators.ema_20],['EMA 50',view.indicators.ema_50],['Relative volume',view.indicators.relative_volume_20,'×']];
      for(const [label,number,suffix]of definitions){const item=text(metrics,'div','');text(item,'span',label);text(item,'strong',value(number,suffix||''));}
      const reasonGrid=text(container,'div','','reason-grid');
      for(const [heading,items,tone]of [['Positive signs',view.positive,'positive'],['Negative signs',view.negative,'negative'],['Risks & limitations',view.risks,'amber']]){
        const card=text(reasonGrid,'section','','reason-card');text(card,'h3',heading,tone);
        if(items.length){const ul=text(card,'ul','');items.forEach(item=>text(ul,'li',item));}else text(card,'p','No additional signs reported.','hint');
      }
      text(container,'p','Trend: '+(view.trend||'Not classified')+' · Rule score: '+value(view.score,' / 100')+' · Rule scores are not probabilities of profit.','hint');
    }
    if(['BUY','SELL'].includes(view.action)){
      text(container,'h3',(view.mode==='REAL'?'Proposed real entry':'Proposed paper entry')+' · '+(view.action==='SELL'?'SELL / SHORT':'BUY / LONG'));const plan=text(container,'div','','trade-plan');
      for(const [label,number,prefix]of [['Quantity',view.quantity,''],['Reference price',view.entry,'₹'],['Stop-loss',view.stop,'₹'],['Target',view.target,'₹']]){
        const item=text(plan,'div','');text(item,'span',label);text(item,'strong',prefix+value(number));
      }
      text(container,'p','This is the AI proposal. Check Positions for the independently approved fill and current P&L.','hint');
      if(view.mode==='REAL')text(container,'p','Real exposure ceiling '+value(view.leverage)+'× · Proposed exposure ₹'+value(view.notional)+' · Per-trade risk budget ₹'+value(view.riskBudget)+'. Groww margin and available funds determine the approved whole-share quantity.','hint');
      else if(view.leverage)text(container,'p','Paper buying-power limit '+value(view.leverage)+'× · Margin ₹'+value(view.margin)+' · Exposure ₹'+value(view.notional)+' · Per-trade risk budget ₹'+value(view.riskBudget),'hint');
      if(view.sizing?.quantityLimits){const limits=view.sizing.quantityLimits;text(container,'p','Whole-share sizing limits: exposure '+value(limits.exposure)+' · planned margin and costs '+value(limits.planningMarginAndCosts)+' · stop risk '+value(limits.stopRisk)+'. Live Groww funds, margin, depth and remaining daily risk can reduce the quantity.','hint');}
      if(Number.isFinite(view.stopPercent))text(container,'p','Actual stop '+value(view.stopPercent)+'% · Actual target '+value(view.targetPercent)+'% of entry price. Price-band minimums, ATR and costs determine these distances.','hint');
      text(container,'p','Conditional risk plan with estimated costs; these targets are not guaranteed future prices.','hint');
    }
    if(view.shortlist.length){
      text(container,'h3',view.selectionMode==='NEWS_THEN_PRICE'?'News and affordable candle candidates':view.selectionMode==='PRICE_FALLBACK'?'Price fallback confirmation':'News shortlist confirmation');
      for(const item of view.shortlist){const card=text(container,'section','','source-card');text(card,'h3',item.symbol+' · '+(item.candidateStatus==='QUALIFIED'?'Qualified':item.candidateStatus==='PENDING_INTRADAY_CHECK'?'Awaiting MIS check':'Not qualified'));text(card,'p',intradayLabel(item)+(item.intradayCheck?.reason?' · '+item.intradayCheck.reason:''),'hint');
        if(item.capitalPriceCheck?.enabled)text(card,'p','Checked share price ₹'+value(item.capitalPriceCheck.checkedPrice)+' · capital price check '+(item.capitalPriceCheck.allowed?'passed':'rejected'),'hint');
        if(item.market?.marketRegime)text(card,'p','NIFTY regime: '+item.market.marketRegime.toLowerCase());
        const reasons=[...new Set([...list(item.reasons),...list(item.market?.reasons)])];const ul=text(card,'ul','');reasons.forEach(reason=>text(ul,'li',reason));}
    }
    if(view.scanCoverage){const coverage=view.scanCoverage; text(container,'p','NIFTY 50 histories: '+value(coverage.nifty50)+' · Other-stock histories analysed: '+value(coverage.otherStocks)+' · Broader registry outside NIFTY 50: '+value(coverage.otherUniverseSize)+'. Other-stock analysis covers a bounded price-screened batch, not the entire registry each cycle.','info-note');}
    if(view.screeningCoverage)text(container,'p','Other-stock prices available: '+value(view.screeningCoverage.otherPricesAvailable)+' · Within configured price limits: '+value(view.screeningCoverage.otherAffordableStocks)+' · Candle batch limit: '+value(view.screeningCoverage.otherBatchLimit),'hint');
    if(view.candidates.length){
      text(container,'h3','Candidate comparison');const wrap=text(container,'div','','candidate-table'),table=text(wrap,'table',''),head=text(table,'thead',''),row=text(head,'tr','');
      ['Stock','Universe','Direction','Industry','Rule score','Intraday / MIS','Result'].forEach(label=>text(row,'th',label));const body=text(table,'tbody','');
      for(const item of view.candidates){const tr=text(body,'tr','');[item.symbol,item.universeScope==='OTHER_STOCKS'?'Other stocks':'NIFTY 50',item.side==='SELL'?'SELL / SHORT':'BUY / LONG',item.sector||'—',value(item.score,' / 100'),intradayLabel(item),item.candidateStatus==='QUALIFIED'?'Qualified':item.candidateStatus==='PENDING_INTRADAY_CHECK'?'Awaiting MIS check':'Not qualified'].forEach(v=>text(tr,'td',v));}
      text(container,'p',view.contextCoverage?'Screened candidates shown; '+view.contextCoverage+' stocks provide market and industry context. Final quote and risk checks can still reject an entry.':'Highest five available rule scores shown. Final quote and risk checks can still reject a candidate.','hint');
    }
    if(view.sources.length){text(container,'h3','Source context');for(const source of view.sources){const card=text(container,'section','','source-card');text(card,'h3',source.title||source.source||'Indexed source');text(card,'p',source.excerpt||'');}}
    if(view.news.length){text(container,'h3','News considered');for(const article of view.news){const card=text(container,'section','','source-card');text(card,'h3',article.headline||'News item');text(card,'p',[article.source,article.sentiment,article.verification].filter(Boolean).join(' · '));}}
    if(!view.symbol)text(container,'p',view.completedSelection?'Compared '+view.candidateCount+' candidate stocks'+(view.contextCoverage?' with '+view.contextCoverage+' stocks of market context':'')+'. No entry proposal was produced for this comparison. Further scans remain subject to news, market and risk rules.':'Automatic stock analysis appears after the session and data checks pass. A NIFTY reference chart does not represent a selected stock.','hint');
  }
  const api={model,value,stamp,render,intradayLabel};if(typeof module==='object'&&module.exports)module.exports=api;else root.TradingAnalysisView=api;
})(globalThis);
