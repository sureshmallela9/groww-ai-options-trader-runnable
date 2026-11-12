(function(root){
  'use strict';
  const routes=Object.freeze({
    overview:{title:'Overview',description:'Your account, market context and next action at a glance.'},
    automation:{title:'Automation',description:'Control the workflow and see exactly what is happening.'},
    news:{title:'News',description:'Daily news research, US closing context and the Indian-stock watchlist.'},
    analysis:{title:'AI Analysis',description:'Understand the latest decision, evidence and trade proposal.'},
    audit:{title:'Groww API audit',description:'Review saved Groww requests, results and response times.'},
    reports:{title:'Reports',description:'Review daily order details, realized results and saved AI analysis.'},
    positions:{title:'Positions',description:'Track paper fills, open exposure and completed trades.'},
    risk:{title:'Risk & settings',description:'Review the limits that apply to every managed entry.'},
    tools:{title:'Research tools',description:'Assess market context, scan stocks and review a paper proposal.'}
  });
  const known=name=>Object.hasOwn(routes,name);
  function pathFor(name,id){
    if(!known(name))return '/workspace/overview';
    const detail=id==null?'':String(id);
    if(detail&&((name==='reports'&&/^[1-9]\d{0,14}$/.test(detail))||(name==='positions'&&/^[A-Za-z0-9_-]{1,100}$/.test(detail))))return '/workspace/'+name+'/'+encodeURIComponent(detail);
    return '/workspace/'+name;
  }
  function resolve(path,hash=''){
    if(hash&&known(hash.slice(1)))return {name:hash.slice(1),id:null};
    const alias={'/':'overview','/index.html':'overview','/paper-workspace.html':'analysis','/research-tools.html':'tools','/workspace':'overview','/workspace/':'overview'};
    if(Object.hasOwn(alias,path))return {name:alias[path],id:null};
    const parts=path.replace(/\/$/,'').split('/');
    if(parts.length===3&&parts[1]==='workspace'&&known(parts[2]))return {name:parts[2],id:null};
    if(parts.length===4&&parts[1]==='workspace'&&['reports','positions'].includes(parts[2])&&pathFor(parts[2],parts[3])==='/workspace/'+parts[2]+'/'+parts[3])return {name:parts[2],id:parts[3]};
    return {name:'not-found',id:null};
  }
  function createRouter({location,history,onChange}){
    let current=null;
    function commit(route,mode){
      const url=route.name==='not-found'?location.pathname:pathFor(route.name,route.id);
      if(mode==='replace')history.replaceState(null,'',url);
      else if(mode==='push'&&url!==location.pathname+location.search+location.hash)history.pushState(null,'',url);
      const changed=!current||current.name!==route.name||current.id!==route.id;
      current=route;onChange({...route},changed);
    }
    return {start(){commit(resolve(location.pathname,location.hash),'replace');},
      navigate(name,id=null,{replace=false}={}){commit(known(name)?resolve(pathFor(name,id)):{name:'not-found',id:null},replace?'replace':'push');},
      sync(){commit(resolve(location.pathname,location.hash),location.hash&&known(location.hash.slice(1))?'replace':'none');},
      current(){return current?{...current}:null;}};
  }
  const api={routes,pathFor,resolve,createRouter};
  if(typeof module==='object'&&module.exports)module.exports=api;else root.TradingRoutes=api;
})(globalThis);
