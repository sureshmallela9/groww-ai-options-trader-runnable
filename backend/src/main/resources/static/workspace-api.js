(function(root){
  'use strict';
  function createClient({fetch:fetcher,timeout=25000}={}){
    let token='',generation=0;const pending=new Map();
    async function send(path,options={}){
      if(!/^\/api\//.test(path)||path.includes('://'))throw Error('Only local application API paths are allowed.');
      if(path.startsWith('/api/v1/')&&!token)throw Error('Connect the workspace first.');
      const headers={'Content-Type':'application/json',...options.headers};
      if(path.startsWith('/api/v1/'))headers.Authorization='Bearer '+token;
      const {timeoutMs,...requestOptions}=options;
      const wait=timeoutMs??(/^\/api\/(backtesting\/(analyze|run)|v1\/research\/(evaluate|news\/collect))$/.test(path)?180000:timeout);
      let response;try{response=await fetcher(path,{...requestOptions,headers,signal:options.signal||root.AbortSignal?.timeout?.(wait)});}
      catch(error){if(['TimeoutError','AbortError'].includes(error.name))throw Error('The request timed out. A server operation may still finish; check its status before retrying.');throw error;}
      let value;try{value=JSON.parse(await response.text());}catch{throw Object.assign(Error('The service returned an unreadable response.'),{status:response.status});}
      if(!response.ok||value?.error){
        const message=value?.error||value?.detail||value?.message;
        throw Object.assign(Error(typeof message==='string'?message:'The service could not complete the request (HTTP '+response.status+').'),{status:response.status,retryAfterMs:1000*Number(response.headers?.get?.('Retry-After')||0)});
      }
      return value;
    }
    function request(path,options={}){
      const method=(options.method||'GET').toUpperCase();
      if(method!=='GET')return send(path,options); // Mutations are never deduplicated or retried.
      const key=generation+' '+path;
      if(pending.has(key))return pending.get(key);
      const work=send(path,options).finally(()=>pending.delete(key));pending.set(key,work);return work;
    }
    return {request,invalidateReads(){generation++;pending.clear();},setToken(value){token=String(value||'').trim();generation++;pending.clear();},hasToken(){return Boolean(token);},
      platform(path,body,method,admin,key){return request('/api/v1'+path,{method:method||(body===undefined?'GET':'POST'),body:body===undefined?undefined:JSON.stringify(body),headers:{...(admin?{'X-Admin-Token':admin}:{}),...(key?{'Idempotency-Key':key}:{})}});}};
  }
  if(typeof module==='object'&&module.exports){module.exports={createClient};return;}
  root.WorkspaceAPI=createClient({fetch:(...args)=>root.fetch(...args)});
})(globalThis);
