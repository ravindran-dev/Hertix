vi.mock('@tauri-apps/api/core',()=>({isTauri:()=>false,convertFileSrc:(p:string)=>`asset://${p}`}));
vi.mock('../src/api',()=>({call:vi.fn(),desktop:true}));
import {beforeEach,afterEach,describe,it,expect,vi} from 'vitest';
import {useMusic} from '../src/stores/music';
import {startAudioService,seek} from '../src/audio/playback';
import {DEFAULT_EFFECTS} from '../src/audio/effects';
import {call} from '../src/api';
class Deck {
 src='';currentTime=0;duration=180;readyState=4;volume=1;playbackRate=1;preload='';crossOrigin='';
 play=vi.fn(async()=>{});pause=vi.fn();load=vi.fn();removeAttribute=vi.fn(()=>this.src='');addEventListener=vi.fn();
 ontimeupdate:(()=>void)|null=null;onended:(()=>void)|null=null;onloadedmetadata:(()=>void)|null=null;onerror:(()=>void)|null=null;
}
let decks:Deck[]=[];let stop:()=>void;
beforeEach(()=>{vi.useFakeTimers();decks=[];vi.stubGlobal('Audio',class extends Deck{constructor(){super();decks.push(this);}});vi.stubGlobal('AudioContext',undefined);vi.mocked(call).mockImplementation(async(_cmd,args)=>({path:args?.path,lyrics:null,artwork:null}));useMusic.setState({tracks:['Alpha','Beta','Gamma'].map((title,i)=>({id:String(i),path:`C:/Music/${title}.mp3`,title,artist:'',album:'',duration:180,added:0,favorite:false})),queue:[],index:0,request:0,playing:false,repeat:'off',volume:.8,settings:{...useMusic.getState().settings,...DEFAULT_EFFECTS,crossfade:4}});stop=startAudioService();});
afterEach(()=>{stop();vi.unstubAllGlobals();vi.useRealTimers();vi.clearAllMocks();});
async function playing(){useMusic.getState().play('0');await vi.advanceTimersByTimeAsync(0);}
async function fade(){await playing();decks[0].currentTime=176;decks[0].ontimeupdate?.();await vi.advanceTimersByTimeAsync(0);}
describe('streaming transitions',()=>{
 it('preloads the actual next file without starting it',async()=>{await playing();expect(decks[1].src).toContain('Beta.mp3');expect(decks[1].play).not.toHaveBeenCalled();expect(useMusic.getState().index).toBe(0);});
 it('crossfades with an equal-power mix and advances the shared active track once',async()=>{await fade();expect(useMusic.getState().index).toBe(1);expect(decks[1].play).toHaveBeenCalled();await vi.advanceTimersByTimeAsync(2000);expect(decks[0].volume).toBeCloseTo(.8/Math.sqrt(2),2);expect(decks[1].volume).toBeCloseTo(.8/Math.sqrt(2),2);await vi.advanceTimersByTimeAsync(2050);expect(decks[0].pause).toHaveBeenCalled();expect(decks[1].volume).toBeCloseTo(.8);expect(useMusic.getState().index).toBe(1);});
 it('pauses both decks during a transition and resumes only the current track',async()=>{await fade();await vi.advanceTimersByTimeAsync(1000);useMusic.getState().toggle();expect(decks.every(d=>d.pause.mock.calls.length>0)).toBe(true);expect(useMusic.getState().index).toBe(1);const count=decks[0].play.mock.calls.length;useMusic.getState().toggle();await vi.advanceTimersByTimeAsync(0);expect(decks[0].play.mock.calls.length).toBe(count);expect(decks[1].volume).toBe(.8);});
 it('cancels a fade when seeking and retains the current track',async()=>{await fade();seek(42);expect(decks[1].currentTime).toBe(42);expect(useMusic.getState().index).toBe(1);await vi.advanceTimersByTimeAsync(5000);expect(useMusic.getState().index).toBe(1);});
 it('uses the preloaded deck at end-of-track when crossfade is disabled',async()=>{useMusic.setState({settings:{...useMusic.getState().settings,crossfade:0}});await playing();decks[0].onended?.();await vi.advanceTimersByTimeAsync(0);expect(useMusic.getState().index).toBe(1);expect(decks[1].play).toHaveBeenCalled();});
 it('does not crossfade repeat-one or when automatic next-track is disabled',async()=>{useMusic.setState({repeat:'one'});await playing();decks[0].currentTime=179;decks[0].ontimeupdate?.();await vi.advanceTimersByTimeAsync(5000);expect(useMusic.getState().index).toBe(0);useMusic.setState({repeat:'off',settings:{...useMusic.getState().settings,autoNext:false}});await vi.advanceTimersByTimeAsync(0);decks[0].ontimeupdate?.();expect(useMusic.getState().index).toBe(0);});
 it('queue reordering preserves the current source and refreshes the next preload',async()=>{await playing();const loads=decks[0].load.mock.calls.length;useMusic.getState().move(1,2);await vi.advanceTimersByTimeAsync(0);expect(decks[0].load.mock.calls.length).toBe(loads);expect(decks[1].src).toContain('Gamma.mp3');});
});
