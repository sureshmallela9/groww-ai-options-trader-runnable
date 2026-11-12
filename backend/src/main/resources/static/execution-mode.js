(function(root){
  'use strict';
  function confirmedMode(status){return status?.automation?.mode==='REAL'?'REAL':'PAPER';}
  if(typeof module==='object'&&module.exports){module.exports={confirmedMode};return;}
  const $=id=>document.getElementById(id);if(!$('mode-real'))return;
  let latest=null,connected=false,checked=null,busy=false,version=0;
  const message=value=>$('real-preflight-message').textContent=value;
  const ready=()=>{$('real-mode-apply').disabled=busy||!checked||!$('real-ip-registered').checked||!$('real-money-confirm').checked;};
  function paint(){
    const mode=confirmedMode(latest),real=mode==='REAL';
    for(const id of ['mode-paper','mode-real']){const selected=(id==='mode-real')===real;$(id).setAttribute('aria-pressed',String(selected));$(id).classList.toggle('selected',selected);$(id).disabled=!connected||busy;}
    $('auto-mode').value=mode;
    const label=$('auto-confirm').closest('label');if(label){const text=[...label.childNodes].find(n=>n.nodeType===3);if(text)text.textContent=real?' Allow automatic REAL orders with my Groww funds under the configured rules':' Allow automatic simulated paper entries under the configured rules';}
    $('auto-exit').textContent=real?'Stop and exit real positions':'Stop and exit paper positions';
    const environment=$('shell-environment');if(environment)environment.textContent=real?'Real environment':'Paper environment';
    const environmentNote=document.querySelector('.sidebar-environment p');if(environmentNote)environmentNote.textContent=real?'Market data from Groww. Orders use real funds.':'Market data from Groww. Orders are simulated.';
    for(const badge of document.querySelectorAll('.auto-badge'))badge.textContent=real?'REAL · Broker orders enabled':'PAPER · Simulated orders';
    document.body.dataset.executionMode=mode;
  }
  root.AutoPaperUI.subscribe(value=>{const changed=confirmedMode(value)!==confirmedMode(latest);latest=value;if(changed){$('auto-confirm').checked=false;}paint();});
  root.AutoPaperUI.subscribeConnection(value=>{connected=value.state==='connected';if(!connected){version++;checked=null;}$('mode-real').disabled=!connected;$('mode-paper').disabled=!connected;paint();});
  async function select(mode){
    if(!connected||busy)return;busy=true;ready();paint();
    try{
      await root.WorkspaceAPI.platform('/auto/mode',mode==='REAL'?{mode,confirmed:true,ipRegistered:true,publicIp:checked.publicIp}:{mode},'PUT');
      $('auto-confirm').checked=false;root.DailyOrderReports?.invalidate();await (root.AutoPaperUI.modeChanged?root.AutoPaperUI.modeChanged():root.AutoPaperUI.refresh());$('realModeDialog').close();
      root.toast?.('Mode changed. Automation is paused; review and start it when ready.');
    }catch(error){message(error.message);root.toast?.(error.message);}finally{busy=false;ready();paint();}
  }
  $('mode-paper').onclick=()=>{if(confirmedMode(latest)!=='PAPER')select('PAPER');};
  $('mode-real').onclick=async()=>{
    if(!connected||busy||confirmedMode(latest)==='REAL')return;
    const request=++version;checked=null;$('real-ip-registered').checked=false;$('real-money-confirm').checked=false;ready();message('Checking broker account, open orders and public IP…');$('realModeDialog').showModal();
    try{const result=await root.WorkspaceAPI.platform('/execution/preflight',{});if(request!==version)return;checked=result;message('Public IP: '+result.publicIp+'. Account reads passed. Groww whitelist permission still needs your confirmation.');}
    catch(error){if(request===version)message('Real trading is blocked: '+error.message);}ready();
  };
  $('real-ip-registered').onchange=ready;$('real-money-confirm').onchange=ready;
  $('real-mode-apply').onclick=()=>select('REAL');
})(globalThis);
