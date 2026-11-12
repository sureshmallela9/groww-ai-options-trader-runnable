(function(root){
  'use strict';const $=id=>document.getElementById(id);
  function analysisOptions(){const mode=$('research-analysis-mode').value;const options={mode,session_mode:$('research-session-mode').value,context_intervals:$('research-context-intervals').value.split(',').map(value=>value.trim()).filter(Boolean)};if(mode==='historical'&&$('research-analysis-cutoff').value)options.as_of=new Date($('research-analysis-cutoff').value).toISOString();return options;}
  function replayOptions(){return {initial_capital:Number($('replay-capital').value),holding_bars:Number($('replay-hold').value),fee_bps:Number($('replay-fee').value),slippage_bps:Number($('replay-slip').value),risk_per_trade_percent:Number($('replay-risk').value),stop_atr:Number($('replay-stop').value),target_r:Number($('replay-target').value),warmup_bars:Number($('replay-warmup').value)};}
  function example(){const payload=root.AnalysisReport.syntheticExample();$('workspace-replay-request').value=JSON.stringify(payload);$('aiRequest').value=JSON.stringify(payload);$('research-analysis-mode').value='historical';$('research-analysis-cutoff').value='';root.toast('Synthetic example loaded. Choose analysis or replay to run it.');}
  async function run(button,history){
    if(button.disabled)return;button.disabled=true;const message=$('workspace-replay-message');message.textContent=history?'Fetching the selected Groww history for research replay…':'Evaluating the supplied historical candles…';
    try{let payload;if(history){payload=root.growwHistoryRequest();payload.analysis_options={...analysisOptions(),mode:'historical'};delete payload.analysis_options.as_of;}else{try{payload=JSON.parse($('workspace-replay-request').value);}catch{throw Error('Paste a complete request with symbol, interval and at least 51 completed candles.');}payload.analysis_options={...(payload.analysis_options||{}),mode:'historical'};}
      payload.replay_options=replayOptions();const result=await root.WorkspaceAPI.request(history?'/api/backtesting/replay':'/api/ai/replay',{method:'POST',body:JSON.stringify(payload),timeoutMs:180000});
      if(result.report_type!=='replay')throw Error(result.error||result.analysis||'Replay did not complete. Check the API connection.');
      root.AnalysisReport.renderReplay($('workspace-replay-result'),result);message.textContent='Saved replay report '+result.report_id+'. No paper position or broker order was created.';root.WorkspaceReports.invalidate();
    }catch(error){message.textContent=error.message;}finally{button.disabled=false;}
  }
  $('research-analysis-mode').onchange=()=>{$('research-analysis-cutoff').disabled=$('research-analysis-mode').value==='live';};
  $('workspace-analysis-example').onclick=()=>{example();root.WorkspaceShell.openManualAnalysis();};
  $('workspace-replay-example').onclick=example;
  $('workspace-replay-copy').onclick=()=>{$('workspace-replay-request').value=$('aiRequest').value;};
  $('workspace-replay-run').onclick=()=>run($('workspace-replay-run'),false);
  $('workspace-replay-history').onclick=()=>run($('workspace-replay-history'),true);
  root.ResearchUI={analysisOptions};
})(globalThis);
