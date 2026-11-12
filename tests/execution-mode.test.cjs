const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const source=fs.readFileSync(require.resolve('../backend/src/main/resources/static/execution-mode.js'),'utf8');
function harness(platform){
 class Element{constructor(){this.checked=false;this.disabled=false;this.value='';this.childNodes=[{nodeType:3,textContent:''}];this.dataset={};this.attributes={};this.classList={toggle(){}};}setAttribute(k,v){this.attributes[k]=v;}closest(){return this;}showModal(){this.open=true;}close(){this.open=false;}}
 const elements=new Map(),get=id=>{if(!elements.has(id))elements.set(id,new Element());return elements.get(id);};let receive,connection,refreshes=0;
 const document={getElementById:get,querySelector:()=>null,querySelectorAll:()=>[],body:{dataset:{}}};
 const root={document,WorkspaceAPI:{platform},AutoPaperUI:{subscribe:f=>receive=f,subscribeConnection:f=>connection=f,refresh:async()=>refreshes++},DailyOrderReports:{invalidate(){}}};vm.createContext(root);vm.runInContext(source,root);
 return {get,document,receive:v=>receive(v),connection:v=>connection(v),get refreshes(){return refreshes;}};
}
test('real choice requires successful preflight and both UI confirmations; switching never starts automation',async()=>{
 const calls=[],h=harness(async(path,body,method)=>{calls.push({path,body,method});return {ready:true,publicIp:'203.0.113.10'};});
 h.connection({state:'connected'});h.receive({automation:{mode:'PAPER'}});await h.get('mode-real').onclick();assert.equal(h.get('realModeDialog').open,true);assert.equal(h.get('real-mode-apply').disabled,true);
 h.get('real-ip-registered').checked=true;h.get('real-ip-registered').onchange();assert.equal(h.get('real-mode-apply').disabled,true);
 h.get('real-money-confirm').checked=true;h.get('real-money-confirm').onchange();assert.equal(h.get('real-mode-apply').disabled,false);
 await h.get('real-mode-apply').onclick();assert.deepEqual(calls.map(c=>c.path),['/execution/preflight','/auto/mode']);assert.equal(calls[1].method,'PUT');assert.equal(calls[1].body.mode,'REAL');assert.equal(calls[1].body.publicIp,'203.0.113.10');assert.equal(h.refreshes,1);
 h.receive({automation:{mode:'REAL'}});assert.equal(h.get('mode-real').attributes['aria-pressed'],'true');assert.equal(h.document.body.dataset.executionMode,'REAL');assert.equal(h.get('auto-mode').value,'REAL');assert.equal(h.get('auto-confirm').checked,false);
});
test('failed preflight keeps real activation disabled and reports the actual blocker',async()=>{
 const h=harness(async()=>{throw Error('Broker token expired');});h.connection({state:'connected'});h.receive({automation:{mode:'PAPER'}});await h.get('mode-real').onclick();
 h.get('real-ip-registered').checked=true;h.get('real-money-confirm').checked=true;h.get('real-money-confirm').onchange();assert.equal(h.get('real-mode-apply').disabled,true);assert.match(h.get('real-preflight-message').textContent,/Broker token expired/);
});
