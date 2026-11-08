import { useEffect, useState } from 'react';
import { Cloud, Heart, ListMusic, LockKeyhole, RefreshCw, UserRound } from 'lucide-react';
import { configured, requireCloud } from '../account/client';
import { useAccount, signIn, signUp, sendCode, verifyCode, changePassword, signOut } from '../account/auth';
import { useSync, enableSync, enqueue, synchronize, resolveConflict } from '../account/sync';
import './Account.css';
import CloudFiles from '../account/CloudFiles';
import { exportAccount, fileAction } from '../account/files';
export default function Account() {
  const account = useAccount();
  const sync = useSync();
  const [mode, setMode] = useState<'signin' | 'signup' | 'verify' | 'recover' | 'reset'>('signin');
  const [email, setEmail] = useState(''); const [password, setPassword] = useState('');
  const [name, setName] = useState(''); const [code, setCode] = useState('');
  const [deleting, setDeleting] = useState(false); const [confirmation, setConfirmation] = useState('');
  const [busy, setBusy] = useState(false); const [message, setMessage] = useState('');
  const [profile, setProfile] = useState(''); const [playlist, setPlaylist] = useState('');
  useEffect(() => { if (account.status === 'recovery') setMode('reset'); }, [account.status]);
  useEffect(() => {
    let active = true;
    if (account.user) void requireCloud().from('profiles').select('display_name').eq('id', account.user.id).single().then(({data}) => { if (active) setProfile(data?.display_name || ''); });
    else setProfile('');
    return () => { active = false; };
  }, [account.user]);
  async function run(action: () => Promise<void>) {
    setBusy(true); setMessage('');
    try { await action(); } catch (error) { setMessage(error instanceof Error ? error.message : 'The request failed. Please retry.'); }
    finally { setBusy(false); setPassword(''); }
  }
  const signedIn = !!account.user && ['authenticated', 'offline', 'refreshing'].includes(account.status) && mode !== 'reset';
  return <section className="account-page">
    <header className="account-hero"><span className="account-mark"><Cloud size={38}/></span><h1>Your music, anywhere</h1><p>One Hertix account for your library and playlists. Your local downloads stay on this device.</p></header>
    {!configured ? <div className="account-panel"><h2>Cloud setup required</h2><p>This build has no Supabase connection configured. Downloading and local playback remain available.</p><p>Follow docs/development-setup.md to configure the same project for desktop and Android.</p></div> : signedIn ? <>
      <div className="account-panel"><h2><UserRound/> {profile || 'Your account'}</h2><p>{account.user?.email}</p><p>{account.user?.email_confirmed_at ? 'Email verified' : 'Verification pending'} · {account.status}</p>
        <label>Display name<input maxLength={100} value={profile} onChange={e=>setProfile(e.target.value)}/></label>
        <button disabled={busy || account.status !== 'authenticated'} onClick={()=>void run(async()=>{ const {error}=await requireCloud().from('profiles').update({display_name:profile}).eq('id',account.user!.id); if(error) throw error; setMessage('Profile saved.'); })}>Save profile</button>
        <div className="account-actions"><button disabled={busy} onClick={()=>void run(()=>signOut())}>Sign out</button><button disabled={busy} onClick={()=>void run(async()=>{await signOut(true);setMessage('Other refresh sessions revoked. Existing access tokens expire according to your server policy.');})}>Sign out other sessions</button><button disabled={busy} onClick={()=>void run(exportAccount)}>Export account data</button><button disabled={busy} onClick={()=>setDeleting(true)}>Delete account</button><button disabled={busy} onClick={()=>{setEmail(account.user?.email||'');setMode('reset');}}>Change password</button></div>
      {deleting && <form onSubmit={e=>{e.preventDefault();void run(async()=>{await fileAction('delete-account',undefined,{confirmation,password});await requireCloud().auth.signOut({scope:'local'});setDeleting(false);setMessage('Account deleted. Local files remain.');});}}><p>Permanently delete your account, cloud files, playlists and metadata. Downloaded local files remain. This cannot be undone.</p><label>Type DELETE MY ACCOUNT<input value={confirmation} onChange={e=>setConfirmation(e.target.value)}/></label><label>Current password<input type="password" autoComplete="current-password" required value={password} onChange={e=>setPassword(e.target.value)}/></label><button disabled={busy||confirmation!=='DELETE MY ACCOUNT'}>Permanently delete account</button><button type="button" onClick={()=>{setDeleting(false);setPassword('');}}>Cancel</button></form>}
      </div>
      <div className="account-panel"><h2><RefreshCw/> Cloud &amp; Sync</h2><label className="account-check"><input type="checkbox" checked={sync.enabled} onChange={e=>enableSync(e.target.checked)}/> Enable metadata synchronization</label><p role="status">{sync.status}{sync.lastSync ? ` · Last confirmed ${new Date(sync.lastSync).toLocaleString()}` : ''}</p><p>Syncs library metadata, playlists, favorites and player preferences. Audio files are never uploaded automatically.</p><button disabled={busy || !sync.enabled} onClick={()=>void synchronize(true)}>Retry sync</button>
      {sync.queue.map(op=><div className="account-operation" key={op.operation_id}><p>{op.entity_type} · {op.status} · attempts {op.attempt_count}</p>{op.last_error && <p>{op.last_error}</p>}{op.status==='conflict' && <><button onClick={()=>resolveConflict(op.operation_id,false)}>Use remote edit</button><button onClick={()=>resolveConflict(op.operation_id,true)}>Apply my edit to latest version</button></>}</div>)}
      </div>
      <div className="account-panel"><h2><ListMusic/> Cloud playlists</h2><form onSubmit={e=>{e.preventDefault();void run(async()=>{enqueue('playlists',crypto.randomUUID(),{name:playlist.trim()});setPlaylist('');});}}><label>Playlist name<input required maxLength={200} value={playlist} onChange={e=>setPlaylist(e.target.value)}/></label><button disabled={busy||!playlist.trim()}>Create playlist</button></form>
        {(sync.rows.playlists||[]).filter(row=>!row.deleted_at).map(row=><div className="account-track" key={String(row.id)}><span>{String(row.name)}</span><button onClick={()=>void run(async()=>enqueue('playlists',String(row.id),{deleted_at:new Date().toISOString()}))}>Remove playlist</button></div>)}
      </div>
      <CloudFiles key={account.user!.id}/>
      <div className="account-panel"><h2>Cloud library</h2><p>Metadata alone does not make a song playable on this device.</p>{!(sync.rows.library_items||[]).length && <p>No synchronized tracks yet.</p>}{(sync.rows.library_items||[]).filter(row=>!row.deleted_at).map(row=><div className="account-track" key={String(row.id)}><span><strong>{String(row.title)}</strong><small>{String(row.artist||'Unknown artist')} · {row.cloud_object_id ? 'Cloud file' : 'Metadata only; file unavailable'}</small></span><button aria-label="Toggle favorite" onClick={()=>void run(async()=>enqueue('favorites',String(row.id),{active:!(sync.rows.favorites||[]).some(f=>f.library_item_id===row.id&&f.active)}))}><Heart size={20} fill={(sync.rows.favorites||[]).some(f=>f.library_item_id===row.id&&f.active)?'currentColor':'none'}/></button></div>)}</div>
    </> : <div className="account-panel account-form"><h2><LockKeyhole/> {mode==='signup'?'Create account':mode==='verify'?'Verify your email':mode==='recover'?'Recover your account':mode==='reset'?'Choose a new password':'Welcome to Hertix'}</h2>
      <form onSubmit={e=>{e.preventDefault(); void run(async()=>{
        if(mode==='signin') await signIn(email,password);
        if(mode==='signup') { await signUp(email,password,name);setMode('verify');setMessage('If registration is available, check your email for a verification code.'); }
        if(mode==='verify') await verifyCode(email,code,false);
        if(mode==='recover') { if(code) await verifyCode(email,code,true); else {await sendCode(email,true);setMessage('If this address can receive recovery mail, a code has been sent.');} }
        if(mode==='reset') {await changePassword(password);setMode('signin');setMessage('Password updated.');}
      });}}>
        {mode!=='reset' && <label>Email address<input type="email" autoComplete="email" required value={email} onChange={e=>setEmail(e.target.value)}/></label>}
        {mode==='signup' && <label>Display name<input required maxLength={100} autoComplete="nickname" value={name} onChange={e=>setName(e.target.value)}/></label>}
        {['signin','signup','reset'].includes(mode) && <label>Password<input type="password" autoComplete={mode==='signin'?'current-password':'new-password'} required minLength={mode==='signin'?1:12} value={password} onChange={e=>setPassword(e.target.value)}/></label>}
        {['verify','recover'].includes(mode) && <label>Email code<input inputMode="numeric" autoComplete="one-time-code" required={mode==='verify'} value={code} onChange={e=>setCode(e.target.value)}/></label>}
        <button className="account-primary" disabled={busy || account.status==='initializing'}>{busy?'Please wait…':mode==='signin'?'Sign in':mode==='signup'?'Create account':mode==='reset'?'Update password':mode==='recover'&&!code?'Send recovery code':'Verify code'}</button>
      </form>
      <div className="account-actions"><button disabled={busy} onClick={()=>{setMode(mode==='signin'?'signup':'signin');setCode('');setMessage('');}}>{mode==='signin'?'Create account':'Back to sign in'}</button><button disabled={busy} onClick={()=>{setMode('recover');setCode('');}}>Forgot password?</button>{mode==='verify'&&<button disabled={busy} onClick={()=>void run(async()=>{await sendCode(email,false);setMessage('If eligible, a new code has been sent.');})}>Resend code</button>}</div><p>Guest mode is always available. You can leave this page to keep using local features.</p>
    </div>}
    {(message||account.error) && <p className="account-message" role="status">{message||account.error}</p>}
  </section>;
}
