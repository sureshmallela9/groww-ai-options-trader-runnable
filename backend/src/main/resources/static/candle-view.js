(function(root){
  'use strict';
  const FIVE_MINUTES=300000,IST=19800000;
  const FRAMES=Object.freeze([
    {id:'5m',label:'5 minutes',minutes:5},{id:'15m',label:'15 minutes',minutes:15},
    {id:'30m',label:'30 minutes',minutes:30},{id:'1h',label:'1 hour',minutes:60},
    {id:'1d',label:'1 day',minutes:1440}
  ]);
  const frameFor=id=>FRAMES.find(frame=>frame.id===id)||FRAMES[0];
  const dayOf=value=>{const ms=typeof value==='number'?value:Date.parse(value);return Number.isFinite(ms)?new Date(ms+IST).toISOString().slice(0,10):null;};
  const validDay=value=>typeof value==='string'&&/^\d{4}-\d{2}-\d{2}$/.test(value)&&Number.isFinite(Date.parse(value+'T00:00:00Z'))&&new Date(value+'T00:00:00Z').toISOString().slice(0,10)===value;
  function dayWindow(bars=[],requestedDay,now=Date.now()){
    const today=dayOf(now),day=validDay(requestedDay)&&requestedDay<=today?requestedDay:today;
    const available=[...new Set(bars.filter(validBar).map(b=>dayOf(b.timestamp)).filter(d=>d<=today))].sort();
    return {today,day,available,previous:available.filter(d=>d<day).at(-1)||null,next:available.find(d=>d>day)||null,bars:bars.filter(b=>validBar(b)&&dayOf(b.timestamp)===day)};
  }
  const validBar=bar=>Number.isFinite(Date.parse(bar.timestamp))&&['open','high','low','close','volume'].every(key=>typeof bar[key]==='number'&&Number.isFinite(bar[key]))&&bar.low>0&&bar.high>=Math.max(bar.open,bar.close)&&bar.low<=Math.min(bar.open,bar.close)&&bar.volume>=0;
  function aggregate(bars=[],frameId='5m'){
    const frame=frameFor(frameId),unique=new Map();
    for(const bar of bars)if(validBar(bar))unique.set(Date.parse(bar.timestamp),bar);
    const source=[...unique].sort((a,b)=>a[0]-b[0]);
    if(frame.id==='5m')return source.map(([ms,bar])=>({...bar,endTimestamp:new Date(ms+FIVE_MINUTES).toISOString(),sourceCount:1,expectedCount:1,partial:false}));
    const groups=new Map();
    for(const [ms,bar]of source){
      const day=dayOf(ms),open=Date.parse(day+'T09:15:00+05:30'),close=Date.parse(day+'T15:30:00+05:30');
      const start=frame.id==='1d'?open:open+Math.floor((ms-open)/(frame.minutes*60000))*frame.minutes*60000;
      // Regular NSE session alignment is a display convention, never a trading calendar.
      const regular=ms>=open&&ms<close;
      const end=frame.id==='1d'?close:(regular?Math.min(close,start+frame.minutes*60000):start+frame.minutes*60000);
      const key=day+' '+start;
      if(!groups.has(key))groups.set(key,{timestamp:new Date(start).toISOString(),endTimestamp:new Date(end).toISOString(),open:bar.open,high:bar.high,low:bar.low,close:bar.close,volume:0,sourceCount:0,expectedCount:Math.max(1,(end-start)/FIVE_MINUTES),slots:new Set(),regular});
      const group=groups.get(key);if(ms+FIVE_MINUTES>Date.parse(group.endTimestamp))group.endTimestamp=new Date(ms+FIVE_MINUTES).toISOString();group.high=Math.max(group.high,bar.high);group.low=Math.min(group.low,bar.low);group.close=bar.close;group.volume+=bar.volume;group.sourceCount++;
      group.slots.add((ms-start)/FIVE_MINUTES);
    }
    return [...groups.values()].map(({slots,regular,...bar})=>({...bar,partial:!regular||bar.sourceCount!==bar.expectedCount||[...slots].some(slot=>!Number.isInteger(slot)||slot<0||slot>=bar.expectedCount)}));
  }
  function timeTicks(candles=[],frameId='5m'){
    if(!candles.length)return [];
    const indices=[...new Set(Array.from({length:Math.min(5,candles.length)},(_,i)=>Math.round(i*(candles.length-1)/Math.max(1,Math.min(5,candles.length)-1))))];
    return indices.map(index=>{
      const date=new Date(candles[index].timestamp),daily=frameId==='1d';
      return {x:candles[index].x,index,
        top:new Intl.DateTimeFormat('en-IN',{timeZone:'Asia/Kolkata',...(daily?{weekday:'short'}:{hour:'2-digit',minute:'2-digit',hour12:false})}).format(date),
        bottom:new Intl.DateTimeFormat('en-IN',{timeZone:'Asia/Kolkata',day:'2-digit',month:'short',...(daily?{year:'2-digit'}:{})}).format(date)};
    });
  }
  function panEndIndex(total,count,endIndex,delta){return Math.max(Math.min(count,total),Math.min(total,endIndex-Math.round(delta)));}
  function bindPan(svg,{interaction,getModel,onPan,onCommit}){
    let gesture=null;
    const finish=release=>{
      if(!gesture)return;const old=gesture;gesture=null;interaction.dragging=false;interaction.cancel=null;svg.classList.remove?.('is-dragging');
      if(release&&svg.hasPointerCapture?.(old.id))svg.releasePointerCapture(old.id);
      if(old.changed)onCommit();
    };
    svg.onpointerdown=event=>{
      if(gesture||event.isPrimary===false||(event.button!==undefined&&event.button!==0))return;
      const model=getModel();if(!model.candles.length||model.total<=model.candles.length)return;
      gesture={id:event.pointerId,x:event.clientX,y:event.clientY,model,width:svg.getBoundingClientRect().width*800/(svg.viewBox?.baseVal?.width||900),changed:false,lastEnd:model.endIndex};
      svg.focus?.({preventScroll:true});interaction.dragging=true;interaction.cancel=()=>finish(true);svg.setPointerCapture?.(event.pointerId);
    };
    svg.onpointermove=event=>{
      if(!gesture||gesture.id!==event.pointerId)return;
      const dx=event.clientX-gesture.x,dy=event.clientY-gesture.y;
      if(Math.abs(dx)<5||Math.abs(dx)<Math.abs(dy))return;
      event.preventDefault();svg.classList.add('is-dragging');
      const model=gesture.model,end=panEndIndex(model.total,model.candles.length,model.endIndex,dx*model.candles.length/Math.max(1,gesture.width));
      if(end===gesture.lastEnd)return;
      gesture.lastEnd=end;gesture.changed=true;onPan(model.timeline[end-1],end===model.total);
    };
    svg.onpointerup=event=>{if(gesture?.id===event.pointerId)finish(true);};
    svg.onpointercancel=event=>{if(gesture?.id===event.pointerId)finish(true);};
    svg.onlostpointercapture=()=>finish(false);
    svg.onkeydown=event=>{
      if(gesture||!['ArrowLeft','ArrowRight','Home','End'].includes(event.key))return;
      const model=getModel();if(!model.candles.length)return;
      event.preventDefault();const step=Math.max(1,Math.floor(model.candles.length/5));
      const end=event.key==='Home'?model.candles.length:event.key==='End'?model.total:panEndIndex(model.total,model.candles.length,model.endIndex,event.key==='ArrowLeft'?step:-step);
      if(end!==model.endIndex){onPan(model.timeline[end-1],end===model.total);onCommit();}
    };
  }
  const api={FRAMES,frameFor,dayOf,dayWindow,aggregate,timeTicks,panEndIndex,bindPan};
  if(typeof module==='object'&&module.exports)module.exports=api;else root.CandleView=api;
})(globalThis);
