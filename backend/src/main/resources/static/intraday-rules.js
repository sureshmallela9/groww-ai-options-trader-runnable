(function(root){
  'use strict';
  function bandRows(rules){let lower=0;return (rules.priceBands||[]).map(b=>{const row={range:b.belowPrice==null?'₹'+lower+' and above':'₹'+lower+' to below ₹'+b.belowPrice,stop:b.stopLossPercent,target:b.targetProfitPercent};if(b.belowPrice!=null)lower=b.belowPrice;return row;});}
  const defaultCapitalBands=()=>[{capitalLimit:10000,capitalLimitInclusive:false,maxSharePrice:200,sharePriceInclusive:false},{capitalLimit:20000,capitalLimitInclusive:true,maxSharePrice:500,sharePriceInclusive:false},{capitalLimit:null,capitalLimitInclusive:false,maxSharePrice:3000,sharePriceInclusive:true}];
  function capitalBand(rules,capital){return (rules.capitalPriceBands||defaultCapitalBands()).find(b=>b.capitalLimit==null||capital<b.capitalLimit||(b.capitalLimitInclusive&&capital===b.capitalLimit));}
  function capitalBandRows(rules){let lower=0,inclusive=true;return (rules.capitalPriceBands||defaultCapitalBands()).map(b=>{const range=(inclusive?'From ₹':'Above ₹')+lower+(b.capitalLimit==null?'':(b.capitalLimitInclusive?' through ₹':' to below ₹')+b.capitalLimit);if(b.capitalLimit!=null){lower=b.capitalLimit;inclusive=!b.capitalLimitInclusive;}return {range,price:(b.sharePriceInclusive?'Up to ₹':'Below ₹')+b.maxSharePrice};});}
  function readRules(form){
    const capitalRows=[...form.querySelectorAll('[data-capital-band]')];
    return {capitalPriceFilterEnabled:form.querySelector('[name=capitalPriceFilterEnabled]')?.checked??true,
      capitalPriceBands:capitalRows.length?capitalRows.map(row=>({
        capitalLimit:row.querySelector('[name=capitalLimit]')?Number(row.querySelector('[name=capitalLimit]').value):null,
        capitalLimitInclusive:Boolean(row.querySelector('[name=capitalLimitInclusive]')?.checked),
        maxSharePrice:Number(row.querySelector('[name=maxSharePrice]').value),
        sharePriceInclusive:Boolean(row.querySelector('[name=sharePriceInclusive]').checked)})):defaultCapitalBands(),
      fallbackEnabled:form.querySelector('[name=fallbackEnabled]').checked,
      fallbackMaxPrice:Number(form.querySelector('[name=fallbackMaxPrice]').value),
      paperLeverage:Number(form.querySelector('[name=paperLeverage]').value),
      realMaxLeverage:Number(form.querySelector('[name=realMaxLeverage]')?.value||1),
      priceBandsEnabled:form.querySelector('[name=priceBandsEnabled]').checked,
      priceBands:[...form.querySelectorAll('[data-price-band]')].map(row=>({
        belowPrice:row.querySelector('[name=belowPrice]')?Number(row.querySelector('[name=belowPrice]').value):null,
        stopLossPercent:Number(row.querySelector('[name=stopLossPercent]').value),
        targetProfitPercent:Number(row.querySelector('[name=targetProfitPercent]').value)}))};
  }
  if(typeof module==='object'&&module.exports){module.exports={bandRows,readRules,capitalBand,capitalBandRows,defaultCapitalBands};return;}
  const form=document.getElementById('intraday-rules-form');if(!form)return;
  const $=id=>document.getElementById(id);let dirty=false,latest=null;
  function text(parent,tag,value){const el=document.createElement(tag);el.textContent=value;parent.append(el);return el;}
  function fill(rules){
    for(const key of ['fallbackEnabled','priceBandsEnabled'])form.querySelector('[name='+key+']').checked=Boolean(rules[key]);
    for(const key of ['fallbackMaxPrice','paperLeverage'])form.querySelector('[name='+key+']').value=rules[key];
    form.querySelector('[name=realMaxLeverage]').value=String(rules.realMaxLeverage??1);
    form.querySelector('[name=capitalPriceFilterEnabled]').checked=rules.capitalPriceFilterEnabled??true;
    const capitalHost=$('capital-band-fields');capitalHost.replaceChildren();
    for(const [i,band]of (rules.capitalPriceBands||defaultCapitalBands()).entries()){
      const row=text(capitalHost,'fieldset','');row.dataset.capitalBand=String(i);row.className='source-card';text(row,'legend','Capital range '+(i+1));
      const grid=text(row,'div','');grid.className='form-grid';
      for(const [key,label]of [['capitalLimit','Cash capital upper boundary (INR)'],['maxSharePrice','Maximum share price (INR)']]){
        if(key==='capitalLimit'&&band[key]==null){text(grid,'p','Final range: all higher cash capital');continue;}
        const wrapper=text(grid,'label',label);wrapper.className='field-label';const input=document.createElement('input');Object.assign(input,{name:key,type:'number',min:'.01',max:'10000000',step:'.01',required:true,value:band[key]});wrapper.append(input);
      }
      for(const [key,label]of [['capitalLimitInclusive','Include capital equal to this boundary'],['sharePriceInclusive','Allow share price equal to this maximum']]){
        if(key==='capitalLimitInclusive'&&band.capitalLimit==null)continue;
        const wrapper=text(grid,'label','');wrapper.className='check';const input=document.createElement('input');input.type='checkbox';input.name=key;input.checked=Boolean(band[key]);wrapper.append(input,document.createTextNode(' '+label));
      }
    }
    const host=$('intraday-band-fields');host.replaceChildren();
    for(const [i,band]of (rules.priceBands||[]).entries()){
      const row=text(host,'div','');row.className='form-grid';row.dataset.priceBand=String(i);
      for(const [key,label,max]of [['belowPrice','Share price below (₹)',10000000],['stopLossPercent','Minimum stop (%)',5],['targetProfitPercent','Minimum target (%)',20]]){
        if(key==='belowPrice'&&band[key]==null){text(row,'p','Final band: all higher prices');continue;}
        const wrapper=text(row,'label',label);wrapper.className='field-label';const input=document.createElement('input');input.name=key;input.type='number';input.min='.01';input.max=String(max);input.step='.01';input.required=true;input.value=band[key];wrapper.append(input);
      }
    }
  }
  form.addEventListener('input',()=>{dirty=true;});
  root.AutoPaperUI.subscribe(value=>{
    latest=value.risk.intraday;if(!latest){$('intraday-rules-summary').textContent='Restart the updated backend to load these rules.';return;}
    const cap=value.risk.policy,summary=$('intraday-rules-summary');summary.replaceChildren();
    const real=value.automation?.mode==='REAL';
    const active=capitalBand(latest,cap.capital),supported=Array.isArray(latest.capitalPriceBands)&&typeof latest.capitalPriceFilterEnabled==='boolean';
    text(summary,'p',!supported?'Capital share-price limits are awaiting the updated backend. Restart Groww Trader to activate these defaults; the table below is a preview.':latest.capitalPriceFilterEnabled?'Capital share-price filter: cash capital ₹'+cap.capital.toLocaleString('en-IN')+' allows shares '+(active.sharePriceInclusive?'up to ₹':'strictly below ₹')+active.maxSharePrice.toLocaleString('en-IN')+'. Applies after primary analysis and again at entry; leverage does not raise this price limit.':'Capital share-price filter is off.');
    const capitalTable=text(summary,'table',''),capitalHead=text(capitalTable,'tr','');for(const label of ['Effective cash capital','Allowed share price'])text(capitalHead,'th',label);for(const band of capitalBandRows(latest)){const row=text(capitalTable,'tr','');text(row,'td',band.range);text(row,'td',band.price);}

    text(summary,'p',(latest.fallbackEnabled?'Fallback: maintained stocks strictly below ₹'+latest.fallbackMaxPrice+' when no news candidate qualifies and fresh news collection succeeds.':'Price fallback is off.')+' Effective risk per trade on configured intraday exposure: '+cap.riskPerTradePercent+'%.');
    if(real){
      text(summary,'p','Real intraday exposure ceiling: '+(latest.realMaxLeverage??1)+'×. Groww verifies the required MIS margin for the stock, side and quantity before submission.');
      text(summary,'p','Maximum configured real exposure ₹'+(Math.min(cap.capital,cap.maxPositionValue)*(latest.realMaxLeverage??1)).toLocaleString('en-IN')+'. Actual quantity is also limited by available funds, stop risk, costs and executable depth.');
    }else{
      text(summary,'p','Paper buying-power ceiling: '+latest.paperLeverage+'×. Maximum paper exposure ₹'+(Math.min(cap.capital,cap.maxPositionValue)*latest.paperLeverage).toLocaleString('en-IN')+'. Quantity is also limited by stop risk, costs and executable depth.');
      text(summary,'p','Saved real intraday ceiling: '+(latest.realMaxLeverage??1)+'×. It applies only to real entries, subject to Groww margin and funds checks.');
    }
    text(summary,'p','Dashboard limits and independent hard limits apply in both modes; the stricter value wins. Paper leverage and the real exposure ceiling are separate settings.');
    if(latest.priceBandsEnabled){const table=text(summary,'table',''),head=text(table,'tr','');for(const label of ['Share price','Minimum stop','Minimum target'])text(head,'th',label);for(const band of bandRows(latest)){const row=text(table,'tr','');for(const label of [band.range,band.stop+'%',band.target+'%'])text(row,'td',label);}}
    else text(summary,'p','Price bands are off; ATR rules apply.');
    if(!dirty)fill(latest);
  });
  $('intraday-load-defaults').onclick=async()=>{try{fill({...await root.WorkspaceAPI.platform('/risk/intraday/defaults'),realMaxLeverage:latest?.realMaxLeverage??1,capitalPriceFilterEnabled:latest?.capitalPriceFilterEnabled??true,capitalPriceBands:latest?.capitalPriceBands||defaultCapitalBands()});dirty=true;$('intraday-rules-message').textContent='Paper-testing defaults loaded for review. Save to apply them.';}catch(error){$('intraday-rules-message').textContent=error.message;}};
  form.onsubmit=async event=>{
    event.preventDefault();const button=event.submitter||form.querySelector('[type=submit]');button.disabled=true;
    try{if(!latest||!Array.isArray(latest.capitalPriceBands))throw Error('Restart and connect to the updated Groww Trader backend before saving these rules.');await root.AutoPaperUI.updateIntraday(readRules(form),$('intraday-admin-token').value);dirty=false;fill(latest);$('intraday-rules-message').textContent='Intraday rules saved for new entries. Existing positions keep their original stop and target. Saving does not switch execution mode or start automation.';}
    catch(error){$('intraday-rules-message').textContent=error.message;}
    finally{$('intraday-admin-token').value='';button.disabled=false;}
  };
})(globalThis);
