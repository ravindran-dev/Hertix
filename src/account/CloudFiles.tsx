import { invoke, isTauri } from '@tauri-apps/api/core';
import { useAccount } from './auth';
import { useSync } from './sync';
import { useCallback, useEffect,useRef,useState } from 'react';
import { cloudFiles, storageUsage, uploadFile, downloadFile, fileAction, type CloudObject, type Usage } from './files';
export default function CloudFiles() {
  const owner=useAccount(s=>s.user?.id);
  const library=useSync(s=>s.rows.library_items||[]).filter(row=>!row.deleted_at);
  const [selected,setSelected]=useState<Set<string>>(new Set());
  const [available,setAvailable]=useState<Set<string>>(new Set());
  const [files,setFiles]=useState<CloudObject[]>([]);const [usage,setUsage]=useState<Usage>();
  const [message,setMessage]=useState('');const [busy,setBusy]=useState(false);const [progress,setProgress]=useState(0);
  const cancel=useRef<AbortController|null>(null);const input=useRef<HTMLInputElement>(null);const retry=useRef<CloudObject|undefined>(undefined);
  const refresh=useCallback(async()=>{const [files,usage]=await Promise.all([cloudFiles(),storageUsage()]);setFiles(files);setUsage(usage);
    if(isTauri() && owner){const result=new Set<string>();for(const file of files.filter(f=>f.status==='ready')){if(await invoke<boolean>('cloud_media_available',{owner,object:file.id,checksum:file.sha256,bytes:file.bytes}))result.add(file.id);}setAvailable(result);}}
  ,[owner]);
  useEffect(()=>{void refresh().catch(()=>setMessage('Cloud storage could not be loaded.'));return()=>cancel.current?.abort();},[refresh]);
  async function run(action:(signal:AbortSignal)=>Promise<void>){setBusy(true);setMessage('');setProgress(0);cancel.current=new AbortController();try{await action(cancel.current.signal);await refresh();setMessage(current=>current || 'Cloud operation confirmed.');}catch(e){setMessage(e instanceof Error?e.message:'Request failed');void refresh().catch(()=>{});}finally{setBusy(false);}}
  const batchKey=`ythub-download-batch:${owner}`;
  const pendingBatch=JSON.parse(localStorage.getItem(batchKey)||'[]') as string[];
  const missing=files.filter(f=>f.status==='ready'&&!available.has(f.id));
  async function batch(ids:Set<string>,signal:AbortSignal){
    const remaining=new Set(ids);localStorage.setItem(batchKey,JSON.stringify([...remaining]));
    let complete=0;let failed=0;
    for(const file of missing.filter(f=>ids.has(f.id))){if(signal.aborted)break;try{await downloadFile(file,signal,setProgress);complete++;remaining.delete(file.id);localStorage.setItem(batchKey,JSON.stringify([...remaining]));}catch{failed++;}}
    await refresh();setMessage(`${complete} downloaded · ${failed} failed`);
  }
  return <div className="account-panel"><h2>Your music on this device</h2><p>{library.length} account songs · {available.size} verified on this device · {missing.length} ready to download</p>
    <div className="account-actions">{pendingBatch.length>0&&<button disabled={busy} onClick={()=>void run(signal=>batch(new Set(pendingBatch),signal))}>Resume pending batch ({pendingBatch.length})</button>}<button disabled={busy||!missing.length} onClick={()=>void run(signal=>batch(new Set(missing.map(f=>f.id)),signal))}>Download all missing</button><button disabled={busy} onClick={()=>setSelected(new Set(missing.map(f=>f.id)))}>Select all</button><button onClick={()=>setSelected(new Set())}>Select none</button><button disabled={busy||!selected.size} onClick={()=>void run(signal=>batch(selected,signal))}>Download selected ({selected.size})</button></div>
    {library.filter(row=>!files.some(f=>f.id===row.cloud_object_id&&f.status==='ready')).map(row=><div className="account-track" key={String(row.id)}><span><strong>{String(row.title)}</strong><small>This song is in your account library, but its audio file is not stored in the cloud.</small></span></div>)}<p>{usage?`${(usage.used_bytes/1048576).toFixed(1)} MB reserved or stored / ${(usage.quota_bytes/1048576).toFixed(0)} MB`:'Loading storage usage…'}</p><p>Upload only music you own or are authorized to store. Downloads are saved as local files; deleting a cloud copy leaves local copies intact.</p>
    <input ref={input} type="file" accept=".mp3,.m4a,.flac,.ogg,.opus,.wav" hidden onChange={e=>{const file=e.target.files?.[0];if(file) void run(signal=>uploadFile(file,signal,setProgress,retry.current));e.target.value='';}}/>
    <button disabled={busy} onClick={()=>{retry.current=undefined;input.current?.click();}}>Upload selected audio file</button>
    {busy&&<><progress max={100} value={progress} aria-label="Transfer progress"/><button onClick={()=>cancel.current?.abort()}>Cancel transfer</button></>}
    {files.map(file=><div className="account-track" key={file.id}><span>{file.status==='ready'&&!available.has(file.id)&&<input type="checkbox" aria-label={`Select ${file.display_name}`} checked={selected.has(file.id)} onChange={e=>setSelected(current=>{const next=new Set(current);if(e.target.checked)next.add(file.id);else next.delete(file.id);return next;})}/>}<strong>{file.display_name}</strong><small>{(file.bytes/1048576).toFixed(1)} MB · {file.mime_type} · {file.status}</small></span><div className="account-actions">{available.has(file.id)?<span>Available on this device</span>:file.status==='ready'?<button disabled={busy} onClick={()=>void run(signal=>downloadFile(file,signal,setProgress))}>Download</button>:<button disabled={busy} onClick={()=>{retry.current=file;input.current?.click();}}>Retry upload</button>}<button disabled={busy} onClick={()=>{if(window.confirm('Delete this cloud copy? Downloaded local copies will remain.')) void run(async()=>{await fileAction('delete',file.id);});}}>Remove cloud copy</button></div></div>)}
    {message&&<p role="status">{message}</p>}
  </div>;
}
