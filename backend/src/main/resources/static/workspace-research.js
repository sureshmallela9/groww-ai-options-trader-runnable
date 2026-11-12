(function(root){
  'use strict';
  const $=id=>document.getElementById('research-'+id);let market=null,decision=null,job=null,epoch=0,inputVersion=0,timer=null,polling=false,visible=false;
  const api=(...args)=>root.WorkspaceAPI.platform(...args);
  function text(parent,tag,value){const node=document.createElement(tag);node.textContent=value;parent.append(node);return node;}
  function message(value,error=false){$('message').textContent=value;$('message').classList.toggle('error',error);}
  function on(id,action){$(id).onclick=async()=>{const button=$(id);if(button.dataset.busy)return;button.dataset.busy='true';button.disabled=true;message('Working…');try{await action();}catch(error){message(error.message,true);}finally{delete button.dataset.busy;button.disabled=!root.WorkspaceAPI.hasToken()||(id==='submit'&&(!decision||decision.action!=='BUY'))||(id==='scan'&&Boolean(job));}};}
  async function universe(refresh=false){const data=await api('/research/universe'+(refresh?'/refresh':''),refresh?{}:undefined);const select=$('symbol');select.replaceChildren();for(const row of data.constituents||[]){const option=text(select,'option',row.symbol+' · '+row.companyName);option.value=row.symbol;}message(data.constituents?.length?'Loaded '+data.constituents.length+' maintained stocks.':'No universe snapshot available. Choose Refresh stock list.');}
  function context(){if(!market)throw Error('Load and assess market context first.');return market;}
  function invalidate(){inputVersion++;market=null;decision=null;$('submit').disabled=true;$('proposal').textContent='Market inputs changed. Load market context and evaluate again.';}
  function showDecision(value){decision=value;$('confirm').checked=false;$('submit').disabled=value.action!=='BUY';$('proposal').replaceChildren();
    text($('proposal'),'h3',value.action+' · '+value.symbol);for(const reason of value.reasons||[])text($('proposal'),'p',reason);
    if(value.action==='BUY')text($('proposal'),'p',value.requestedQuantity+' shares · reference ₹'+value.referencePrice+' · stop ₹'+value.stopLoss+' · target ₹'+value.targetPrice);
    const raw=text($('proposal'),'details','');text(raw,'summary','Full decision evidence');text(raw,'pre',JSON.stringify(value,null,2));
  }
  async function poll(){
    if(!job||document.hidden||polling||!visible)return;polling=true;const version=epoch,id=job;let failed=false;try{const value=await api('/research/jobs/'+encodeURIComponent(id));if(version!==epoch)return;
      $('scan-result').textContent='Scan '+value.status+' · '+id;
      if(value.status==='FAILED'){job=null;message(value.error||'Scan failed.',true);}
      else if(value.status==='COMPLETED'){job=null;$('scan-result').textContent=value.shortlist?.length?'Shortlist: '+value.shortlist.map(row=>row.symbol+' ('+row.overallScore+')').join(', '):'No stock passed the configured checks.';if(value.selected)$('symbol').value=value.selected;message('Scan completed. Evaluate a shortlisted stock to review its evidence.');}
    }catch(error){failed=true;if(version===epoch){message(error.message+' Use Check scan to try this job again.',true);}}
    finally{polling=false;if(version===epoch)$('scan').disabled=Boolean(job);if(job&&visible&&(!failed||version!==epoch))timer=setTimeout(poll,2000);}
  }
  on('universe',()=>universe(true));on('load-universe',()=>universe());
  on('market',async()=>{invalidate();const revision=inputVersion;const index=await api('/market-data/candles/query',{symbol:'NIFTY',exchange:'NSE',interval:'5m',lookbackCandles:100});const sector=$('sector').value.trim();
    const sectorData=sector?await api('/market-data/candles/query',{symbol:sector,exchange:'NSE',interval:'5m',lookbackCandles:100}):null;
    const input={niftyBars:index.bars,sectorBars:sectorData?.bars||[],observedAt:new Date().toISOString(),advancers:$('advancers').value===''?null:Number($('advancers').value),decliners:$('decliners').value===''?null:Number($('decliners').value),scheduledEventRisk:$('event').checked};
    const result=await api('/research/market',input);if(revision!==inputVersion)throw Error('Market inputs changed during loading. Assess the current inputs again.');market=input;$('market-result').textContent=result.marketRegime+' · '+(result.reasons||[]).join(' ');message(result.tradingAllowed?'Market context is ready for research.':'Market checks currently block entry proposals.');});
  on('evaluate',async()=>{if(!$('symbol').value)throw Error('Load the stock list and select a stock first.');const revision=inputVersion,symbol=$('symbol').value;decision=null;$('submit').disabled=true;const result=await api('/research/evaluate',{symbol,exchange:'NSE',interval:'5m',market:context(),mode:'PAPER'});if(revision!==inputVersion||symbol!==$('symbol').value)throw Error('Research inputs changed. Evaluate the current selection again.');showDecision(result);message('Decision received. Review the reasons and the entry checks.');});
  on('scan',async()=>{const value=await api('/research/scan',{market:context()});job=value.jobId;epoch++;$('scan-result').textContent='Scan queued · '+job;timer=setTimeout(poll,1000);});
  on('check-scan',async()=>{if(!job)throw Error('Start a scan first.');clearTimeout(timer);await poll();});
  on('submit',async()=>{if(!decision||decision.action!=='BUY'||!$('confirm').checked)throw Error('Review and confirm a BUY paper proposal first.');
    const result=await api('/trade-intents',{decisionId:decision.decisionId,mode:'PAPER',confirmed:true},'POST',null,decision.decisionId);
    message('Paper intent '+result.state+'. See Positions for the recorded lifecycle.');$('confirm').checked=false;decision=null;await root.AutoPaperUI.refresh();});
  on('news-analyze',async()=>{let body;try{body=JSON.parse($('news').value);}catch{throw Error('Enter valid news JSON with sourced articles.');}$('news-result').textContent=JSON.stringify(await api('/research/news/analyze',body),null,2);message('News evidence received.');});
  on('news-collect',async()=>{$('news-result').textContent=JSON.stringify(await api('/research/news/collect',{}),null,2);message('Configured feed collection completed.');});
  for(const id of ['sector','advancers','decliners','event'])$(id).addEventListener('input',invalidate);
  $('symbol').addEventListener('change',()=>{inputVersion++;decision=null;$('submit').disabled=true;$('proposal').textContent='Evaluate the newly selected stock.';});
  root.WorkspaceResearch={enter(){visible=true;if(job){clearTimeout(timer);poll();}},leave(){visible=false;epoch++;clearTimeout(timer);},reset(){epoch++;clearTimeout(timer);job=null;invalidate();message('Workspace disconnected. Reconnect to use research.');}};
})(globalThis);
