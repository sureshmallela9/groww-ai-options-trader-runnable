(function(root){
  'use strict';
  const label=value=>({MIXED_DATES:'Different observation dates',SUPPORTIVE:'Positive US context',CAUTIOUS:'Cautious US context',MIXED:'Mixed US context',UNAVAILABLE:'Unavailable',READY:'Available',PARTIAL:'Partial data',NEWS_UNAVAILABLE:'Stock-news evidence unavailable',NO_POSITIVE_NEWS:'No positive stock-news setup',NO_DIRECTIONAL_NEWS:'No directional stock-news setup'}[value]||value);
  const time=value=>Number.isFinite(Date.parse(value))?new Date(value).toLocaleString('en-IN',{timeZone:'Asia/Kolkata'})+' IST':'Unavailable';
  function render(target,report){
    const doc=target.ownerDocument||document;
    const add=(parent,tag,value,cls)=>{const el=doc.createElement(tag);if(cls)el.className=cls;if(value!==undefined)el.textContent=String(value);parent.append(el);return el;};
    const section=title=>{const el=add(target,'section',undefined,'explain');add(el,'h3',title);return el;};
    const items=(el,values)=>{const list=add(el,'ul');for(const value of values||[])add(list,'li',value);};
    const link=(el,url,label)=>{try{const u=new URL(url);if(!['https:','http:'].includes(u.protocol))return;const a=add(el,'a',label);a.href=u.href;a.target='_blank';a.rel='noopener noreferrer';}catch{}};
    target.replaceChildren();add(target,'h2','Daily analysis · '+report.reportDate);add(target,'p','Updated '+time(report.generatedAt)+' · '+label(report.status),'hint');
    const summary=section('Research summary');items(summary,report.executiveSummary);
    const us=report.usMarket||{},global=section('US market closing results · '+label(us.riskTone||'UNAVAILABLE'));
    add(global,'p','Daily closing observations, not live quotes. Each index can have a different publication date.','hint');
    const table=add(add(global,'div',undefined,'tablewrap'),'table'),head=add(add(table,'thead'),'tr');for(const value of ['Market','Close','Change','Observation date','Data / source'])add(head,'th',value);
    const body=add(table,'tbody');for(const value of us.observations||[]){const row=add(body,'tr');add(row,'td',value.name);add(row,'td',Number(value.close).toLocaleString('en-IN',{maximumFractionDigits:2}));const change=add(row,'td',Number(value.changePercent).toFixed(2)+'%');change.className=value.series==='VIXCLS'?'amber':value.changePercent>=0?'positive':'negative';add(row,'td',value.asOf);const source=add(row,'td',value.stale?'Stale · ':'Published close · ');link(source,value.sourceUrl,'Source');}
    if(!(us.observations||[]).length)add(global,'p','US market results are not available yet. Missing values are not treated as zero or a neutral market.','info-note');
    items(global,us.interpretations);if(us.source)add(global,'p',us.source,'hint');
    if(us.providerErrors?.length)add(global,'p','Unavailable series: '+us.providerErrors.map(e=>e.series||e.error).join(', '),'hint');
    const plan=report.newsPlan||{},news=section('News lookback and stock shortlist');add(news,'p','Window: '+(plan.lookbackUsed==='TODAY'?'Today, IST':'Previous seven days · no today stock news available')+' · '+label(plan.status||'Unavailable'));
    items(news,plan.reasons);
    add(news,'p','Up to three evidenced stocks enter the automation shortlist. Positive news can propose BUY / LONG; negative news can propose SELL / SHORT in paper mode. Candles and risk rules must confirm.','hint');
    for(const [label,values]of [['Automation shortlist · up to three stocks',plan.candidates||[]],['Other negative news watchlist',(plan.bearishCandidates||[]).filter(row=>!(plan.candidates||[]).some(item=>item.symbol===row.symbol))]]){
      add(news,'h3',label);if(!values.length)add(news,'p','None available from the current evidence.','hint');
      for(const stock of values){const card=add(news,'div',undefined,'source-card');add(card,'h3',stock.symbol+' · '+stock.newsBias);add(card,'p','News heuristic score: '+Number(stock.newsScore).toFixed(2)+' · not a probability or order instruction.','hint');
        items(card,stock.reasons);
        for(const item of stock.headlines||[]){add(card,'p',item.headline);add(card,'small',[item.source,time(item.publishedAt),item.verification].filter(Boolean).join(' · '),'muted');if(item.sentimentEvidence?.length)add(card,'p','News cues: '+item.sentimentEvidence.map(e=>e.term+' ('+e.effect.toLowerCase()+')').join(', '),'hint');link(card,item.sourceUrl,' Read original source');}
      }
    }
    const result=section('Candle confirmation and final response'),selection=report.latestCandleSelection,decision=report.latestFinalDecision;
    if(selection){add(result,'p',selection.selected?'Candle-confirmed candidate: '+selection.selected.symbol:'No stock passed candle confirmation.');items(result,selection.reasons);}else add(result,'p','No completed candle-confirmation result is recorded today. A news candidate alone cannot authorize an entry.');
    if(selection?.breadth)add(result,'p','Breadth at the comparison: '+selection.breadth.advancers+' advancing, '+selection.breadth.decliners+' declining, '+selection.breadth.unchanged+' unchanged · '+selection.breadth.coverage+' stocks covered.','hint');
    if(selection?.candidates?.length){const allowed=new Set((selection.newsShortlist||[]).map(n=>n.symbol));const rows=selection.candidates.filter(n=>!allowed.size||allowed.has(n.symbol)).slice().sort((a,b)=>b.score-a.score).slice(0,5);for(const candidate of rows){add(result,'h3',candidate.symbol+' · '+candidate.candidateStatus+' · rule score '+Number(candidate.score).toFixed(1));items(result,candidate.reasons);}}
    if(decision){add(result,'p','Final response: '+decision.action+' · '+decision.symbol+(decision.analysisSignal?' · '+decision.analysisSignal.direction:''));items(result,decision.reasons);if(decision.analysis){add(result,'h3','Positive candle evidence');items(result,decision.analysis.positive_reasons);add(result,'h3','Negative candle evidence');items(result,decision.analysis.negative_reasons);add(result,'h3','Technical risk notes');items(result,decision.analysis.risk_notes);}}else add(result,'p','No final entry decision is recorded for this comparison.','hint');
    const checklist=section('Indian-market confirmation checklist');items(checklist,report.indianMarketChecklist);
    const headlines=section('Available headlines dated today');if(!report.todayHeadlines?.length)add(headlines,'p','No headlines dated today are present in available records. The shortlist uses the stated fallback window.','hint');
    for(const item of report.todayHeadlines||[]){const card=add(headlines,'div',undefined,'source-card');add(card,'h3',item.headline);add(card,'p',[item.source,time(item.publishedAt),item.sentiment].filter(Boolean).join(' · '));link(card,item.sourceUrl,'Source');}
    const limitations=section('Evidence limits and execution rules');items(limitations,report.limitations);
    const raw=add(target,'details');add(raw,'summary','Full saved report');add(raw,'pre',JSON.stringify(report,null,2));
  }
  if(typeof module==='object'&&module.exports){module.exports={render,time};return;}
  if(!document.body.hasAttribute('data-workspace'))return;
  const $=id=>document.getElementById(id);let active=false,generation=0,busy=false,connected=false;
  const today=()=>{const parts=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Kolkata',year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(new Date());const p=Object.fromEntries(parts.map(x=>[x.type,x.value]));return p.year+'-'+p.month+'-'+p.day;};
  $('news-date').value=today();
  async function refresh(){
    if(!active||busy||!connected||document.hidden)return;const version=generation;busy=true;$('news-message').textContent='Loading the saved daily report…';
    try{
      const day=$('news-date').value;if(!/^\d{4}-\d{2}-\d{2}$/.test(day))throw Error('Choose a valid report date.');
      const [listing,report]=await Promise.all([root.WorkspaceAPI.platform('/research/news/daily-reports'),root.WorkspaceAPI.platform('/research/news/daily-reports/'+day)]);
      if(version!==generation)return;
      const select=$('news-history');select.replaceChildren();const placeholder=document.createElement('option');placeholder.value='';placeholder.textContent='Choose a saved day';select.append(placeholder);
      for(const saved of listing.reports||[]){const option=document.createElement('option');option.value=saved.reportDate;option.textContent=saved.reportDate+' · '+saved.status;select.append(option);}select.value=day;
      render($('news-report'),report);$('news-message').textContent='Saved report loaded. Refreshing this view does not trigger a stock entry. News worker: '+listing.newsRefreshSeconds+' seconds; US data cache: '+listing.usRefreshSeconds+' seconds.';
    }catch(error){if(version===generation){$('news-report').replaceChildren();$('news-message').textContent=error.message;}}
    finally{busy=false;if(version!==generation&&active&&connected)refresh();}
  }
  $('news-refresh').onclick=refresh;$('news-date').onchange=()=>{generation++;refresh();};$('news-history').onchange=()=>{if($('news-history').value){$('news-date').value=$('news-history').value;generation++;refresh();}};
  root.WorkspaceNews={enter(){active=true;generation++;refresh();},leave(){active=false;generation++;},connection(value){connected=value.state==='connected';if(active&&connected)refresh();else if(active&&!connected)$('news-message').textContent='Connect the workspace to load the saved daily report.';}};
  setInterval(refresh,60000);
})(globalThis);
