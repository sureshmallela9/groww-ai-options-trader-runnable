(function(root) {
  'use strict';
  // One outstanding poll per page, including stop/restart and slow upstream calls.
  function createPoller({poll,onResult,onError,isVisible=()=>true,schedule=setTimeout,cancel=clearTimeout}) {
    let enabled=false, generation=0, timer=null, busy=false, failures=0;
    function later(version,delay) {
      if(enabled && version===generation) timer=schedule(()=>tick(version),delay);
    }
    async function tick(version) {
      if(!enabled || version!==generation) return;
      if(busy || !isVisible()) {later(version,5000);return;}
      busy=true; let delay=5000;
      try {
        const result=await poll();
        if(enabled && version===generation) {failures=0;onResult(result);}
      } catch(error) {
        if(enabled && version===generation) {
          failures++;delay=Math.max(5000,error.retryAfterMs || 5000*2**Math.min(failures-1,4));
          onError(error);
        }
      } finally {busy=false;later(version,delay);}
    }
    return {
      start() {enabled=true;generation++;failures=0;if(timer!==null)cancel(timer);tick(generation);},
      stop() {enabled=false;generation++;if(timer!==null)cancel(timer);timer=null;},
    };
  }
  if(typeof module==='object' && module.exports) {module.exports={createPoller};return;}
  const checkbox=document.getElementById('historyAuto');
  if(!checkbox)return;
  const note=document.getElementById('historyAutoStatus');
  function settings() {
    return {exchange:document.getElementById('historyExchange').value,
      segment:document.getElementById('historySegment').value,
      groww_symbol:document.getElementById('historySymbol').value.trim(),
      candle_interval:document.getElementById('historyInterval').value,
      analysis_prompt:'Analyze all completed candles available since 09:15 India time today. Explain early positive and negative signs, missing indicators and risks. Analysis only.'};
  }
  const poller=createPoller({
    isVisible:()=>!document.hidden,
    async poll() {
      const response=await fetch('/api/backtesting/analyze/session',{method:'POST',
        headers:{'Content-Type':'application/json'},body:JSON.stringify(settings())});
      const data=await response.json();
      if(!response.ok) {
        const error=Error(data.error || data.message || 'Automatic analysis request failed.');
        const retry=Number(response.headers.get('Retry-After'));
        if(Number.isFinite(retry) && retry>0)error.retryAfterMs=retry*1000;
        throw error;
      }
      return data;
    },
    onResult(result) {
      const source=result.source;
      note.textContent=(result.message || ((source?.analysisCandleCount ?? 0)+' completed candles · '+
        (result.reusedAnalysis?'unchanged; keeping the existing report':'analysis updated')))+
        ' · checked '+new Date(result.checkedAt).toLocaleTimeString()+ ' · checks every 5 seconds';
      if(source) {
        document.getElementById('historyStart').value=source.startTime.replace(' ','T').slice(0,16);
        document.getElementById('historyEnd').value=source.endTime.replace(' ','T').slice(0,16);
      }
      document.getElementById('aiMode').textContent=result.status;
      if(result.analysis)root.renderTradingReport(result);
      else document.getElementById('analysisBody').textContent=result.message;
    },
    onError(error) {note.textContent=error.message+' Automatic checks will retry with backoff.';}
  });
  function stop() {poller.stop();checkbox.checked=false;note.textContent='Automatic analysis stopped.';}
  checkbox.addEventListener('change',()=>{
    if(!checkbox.checked){stop();return;}
    note.textContent='Starting today’s analysis from 09:15 India time…';
    poller.start();
  });
  for(const id of ['historyExchange','historySegment','historySymbol','historyInterval'])
    document.getElementById(id).addEventListener('change',()=>{
      if(checkbox.checked){poller.stop();note.textContent='Loading the new selection…';poller.start();}
    });
  root.addEventListener('pagehide',stop);
  root.SessionAnalysisUI={stop};
})(globalThis);
