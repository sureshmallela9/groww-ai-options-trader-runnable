(function(root){
  'use strict';
  const validTime=value=>typeof value==='string'&&Number.isFinite(Date.parse(value));
  function pendingExits(positions=[],symbol){
    return positions.filter(p=>p.symbol===symbol&&p.state==='EXIT_PENDING'&&Number(p.remainingQuantity)>0).map(p=>{
      const recorded=(p.timeline||[]).filter(e=>e.state==='EXIT_PENDING'&&validTime(e.at)).map(e=>e.at).sort((a,b)=>Date.parse(a)-Date.parse(b));
      return {id:p.intentId,symbol:p.symbol,side:p.positionSide==='SHORT'?'BUY':'SELL',quantity:Number(p.remainingQuantity),
        requestedAt:validTime(p.exitRequestedAt)?p.exitRequestedAt:recorded[0]||'',
        incident:typeof p.incident==='string'?p.incident:'',label:'EXIT PENDING',isFill:false};
    });
  }
  const FILL_COLORS=Object.freeze({BUY:'#50a7ff',SELL:'#c59bff'});
  function drawFill(doc,svg,marker){
    const color=FILL_COLORS[marker.side];
    if(!color||!Number.isFinite(marker.x)||!Number.isFinite(marker.y))return null;
    const make=(tag,attrs,parent=svg)=>{const node=doc.createElementNS('http://www.w3.org/2000/svg',tag);for(const [key,value]of Object.entries(attrs))node.setAttribute(key,String(value));parent.append(node);return node;};
    const buy=marker.side==='BUY',x=marker.x,y=marker.y;
    const price=new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR'}).format(marker.price);
    const timestamp=new Date(marker.time),when=Number.isFinite(timestamp.getTime())?timestamp.toLocaleString('en-IN',{timeZone:'Asia/Kolkata'})+' IST':'Time not recorded';
    const description='Actual paper '+marker.side+' fill · '+price+' · '+when;
    const group=make('g',{'data-order-side':marker.side,role:'img','aria-label':description,tabindex:0});
    make('title',{},group).textContent=description;
    const base=y+(buy?13:-13),path='M '+x+' '+y+' L '+(x-8)+' '+base+' L '+(x+8)+' '+base+' Z';
    // The triangle tip stays at the fill coordinate supplied by the chart.
    make('path',{d:path,fill:color,stroke:'#09131e','stroke-width':5,'stroke-linejoin':'round'},group);
    make('path',{d:path,fill:color,stroke:'#f0f6ff','stroke-width':1.4,'stroke-linejoin':'round'},group);
    const labelX=Math.min(843,Math.max(87,x)),labelY=Math.min(241,Math.max(16,y+(buy?29:-22)));
    make('line',{x1:x,x2:labelX,y1:base,y2:labelY,stroke:color,'stroke-width':1,'stroke-dasharray':'2 2'},group);
    make('rect',{x:labelX-23,y:labelY-12,width:46,height:18,rx:4,fill:'#09131e',stroke:color,'stroke-width':1},group);
    make('text',{x:labelX,y:labelY+1,fill:color,'font-size':12,'font-weight':700,'text-anchor':'middle','font-family':'system-ui, sans-serif'},group).textContent=marker.side;
    return group;
  }
  const api={pendingExits,FILL_COLORS,drawFill};
  if(typeof module==='object'&&module.exports)module.exports=api;else root.PaperTradeMarkers=api;
})(globalThis);
