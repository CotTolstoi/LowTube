"""Run bundled mpv Lua script against a deterministic mp API fixture.
Requires Python + lupa. No network, media or user files are accessed.
"""
from pathlib import Path
from lupa import LuaRuntime
source=(Path(__file__).resolve().parents[1]/'player/lowtube.lua').read_text()

def fixture(paused=False,video=True,enabled=True):
 lua=LuaRuntime(unpack_returned_tuples=True)
 lua.execute('''
 events, bindings, props, timers, commands, messages, hooks = {}, {}, {}, {}, {}, {}, {}
 now=0
 local mp={}
 function mp.get_property_native(k) return props[k] end
 function mp.set_property_native(k,v) props[k]=v end
 function mp.get_property_number(k,d) return tonumber(props[k]) or d end
 function mp.get_osd_size() return 1280,720 end
 function mp.get_mouse_pos() return mouse_x or 0,mouse_y or 0 end
 function mp.remove_key_binding(n) for k,v in pairs(binding_names or {}) do if v==n then bindings[k]=nil end end end
 function mp.create_osd_overlay() local o={};function o:update() overlay_text=self.data end;function o:remove() overlay_text=nil end;return o end
 package.preload['mp.assdraw']=function() return {ass_new=function() local a={text=''};function a:new_event() end;function a:append(s) self.text=self.text..s end;function a:draw_start() end;function a:draw_stop() end;function a:rect_cw() end;return a end} end
 function mp.get_time() return now end
 function mp.osd_message(s,t) last_osd=s end
 function mp.commandv(...) commands[#commands+1]={...} end
 function mp.register_script_message(k,f) messages[k]=f end
 function mp.command_native(c) native_command=c;return true end
 function mp.add_hook(n,p,f) hooks[n]=f end
 function mp.register_event(k,f) local old=events[k];events[k]=function(...) if old then old(...) end;f(...) end end
 function mp.add_key_binding(k,n,f) bindings[k]=f end
 function mp.add_forced_key_binding(k,n,f) bindings[k]=f;binding_names=binding_names or {};binding_names[k]=n end
 function mp.add_periodic_timer(interval,f)
   local t={active=true,fn=f};function t:kill() self.active=false end
   timers[#timers+1]=t;return t
 end
 function tick(seconds) now=now+seconds;for _,t in ipairs(timers) do if t.active then t.fn() end end end
 package.preload['mp.utils']=function() return {parse_json=function() return payload end,format_json=function(t) diagnostic_values=t;return '{}' end} end
 package.preload['mp']=function() return mp end
 package.preload['mp.options']=function() return {read_options=function(o,id) o.safe_seek=enabled end} end
 ''')
 g=lua.globals();g.enabled=enabled;g.props['pause']=paused;g.props['seeking']=True
 if video:g.props['current-tracks/video']=lua.table_from({'id':1})
 lua.execute(source)
 return lua,g

n=0
def check(ok,desc):
 global n
 assert ok,desc
 n+=1;print('PASS:',desc)

lua,g=fixture();g.events['seek']();check(g.props['pause'],'seeking freezes audio immediately')
g.tick(3);check(g.props['pause'],'time alone cannot release sound before video restart')
g.events['playback-restart']();g.tick(.1);check(g.props['pause'],'restart event cannot override seeking=true')
g.props['seeking']=False;g.tick(.1);check(not g.props['pause'],'ready video resumes originally playing media')
lua,g=fixture(paused=True);g.events['seek']();g.events['playback-restart']();g.props['seeking']=False;g.tick(.1);check(g.props['pause'],'original user pause is preserved')
lua,g=fixture();g.events['seek']();g.tick(.5);g.events['seek']();g.events['playback-restart']();g.props['seeking']=False;g.tick(.1);check(not g.props['pause'],'repeated seeks preserve the original resume intent')
lua,g=fixture(paused=True);g.events['seek']();g.events['playback-restart']();g.props['seeking']=False;g.props['pause']=False;g.events['seek']();g.events['playback-restart']();g.tick(.1);check(not g.props['pause'],'explicit resume followed immediately by seek overrides an old paused guard')
lua,g=fixture();g.events['seek']();g.tick(31);check(g.props['pause'] and 'too long' in g.last_osd,'timeout remains paused and explains how to resume')
lua,g=fixture();g.events['seek']();g.props['pause']=False;g.tick(.1);g.events['playback-restart']();g.tick(.1);check(not g.props['pause'],'manual resume cancels the guard')
lua,g=fixture();g.events['seek']();g.events['end-file']();g.props['pause']=True;g.events['playback-restart']();g.tick(40);check(g.props['pause'],'old file events cannot resume a new paused file')
lua,g=fixture(video=False);g.events['seek']();check(not g.props['pause'],'audio-only has no video wait')
lua,g=fixture(enabled=False);g.events['seek']();check(not g.props['pause'],'user can disable the guard')
g.bindings['F4']();g.bindings['MBTN_RIGHT']();check(len(g.commands)==2 and g.commands[1][1]=='script-message' and g.commands[1][2]=='lowtube-settings','both in-player settings shortcuts send the IPC event')
print('TOTAL:',n,'Lua checks passed')

lua,g=fixture();g.props['time-pos']=12.5;g.props['path']='PRIVATE';g.props['hwdec-current']='dxva2-copy'
g.events['file-loaded']();g.tick(2);check(len(g.commands)==0,'diagnostics disabled by default')
g.payload=lua.table_from({'debug':True,'safe_seek':True,'language':'en','audio':'','url':'https://example.invalid','options':lua.table()})
g.messages['lowtube-load']('{}');g.events['file-loaded']()
check(g.commands[len(g.commands)][2]=='lowtube-debug','diagnostic payload sent to IPC')
check(g.diagnostic_values['time-pos']==12.5 and g.diagnostic_values['hwdec-current']=='dxva2-copy','diagnostics include playback position and actual decoder')
check(g.diagnostic_values['path'] is None,'diagnostics exclude media path')
g.bindings['F8']();check(g.commands[len(g.commands)-1][2]=='lowtube-debug-mark','F8 emits distinct user marker')
g.events['end-file']();count=len(g.commands);g.tick(2);check(len(g.commands)==count,'diagnostic sampling stops while idle')
print('TOTAL:',n,'Lua checks passed including diagnostics')

# Quick menu tests (actual shipped Lua).
lua,g=fixture();g.messages['lowtube-menu-toggle']()
check('Resolution' in g.overlay_text and 'Advanced settings' in g.overlay_text,'quick menu renders required settings')
g.bindings['ENTER']();check('2160p' in g.overlay_text,'resolution submenu contains 4K')
g.bindings['ENTER']();check(g.commands[len(g.commands)][2]=='lowtube-quick' and g.commands[len(g.commands)][3]=='height' and g.commands[len(g.commands)][4]=='144','resolution choice emits a validated host request')
count=len(g.commands);g.bindings['ENTER']();check(len(g.commands)==count,'busy menu suppresses duplicate changes')
g.payload=lua.table_from({'height':144,'h264':False,'fps':False,'language':'uk','busy':False});g.messages['lowtube-menu-state']('{}')
g.bindings['F4']();check(g.commands[len(g.commands)][2]=='lowtube-settings' and g.overlay_text is None,'F4 closes quick menu and opens advanced settings')
g.messages['lowtube-menu-toggle']();g.bindings['MBTN_RIGHT']();check(g.commands[len(g.commands)][2]=='lowtube-settings','right click opens advanced settings from menu')
g.messages['lowtube-menu-toggle']();g.bindings['ESC']();check(g.overlay_text is None and not g.timers[len(g.timers)].active,'closing menu removes overlay and stops redraw timer')
print('TOTAL:',n,'Lua checks passed')

lua,g=fixture();g.props['time-pos']=123;g.props['duration']=600;g.props['path']='PRIVATE_STREAM_URL'
g.payload=lua.table_from({'session':'session-a','debug':False,'safe_seek':True,'language':'en','audio':'','url':'PRIVATE_STREAM_URL','options':lua.table()})
g.messages['lowtube-load']('{}');g.events['file-loaded']()
check(g.commands[len(g.commands)][2]=='lowtube-progress' and g.diagnostic_values['session']=='session-a','progress is tied to a playback session')
check(g.diagnostic_values['path'] is None and g.diagnostic_values['url'] is None,'progress omits stream URLs')
start=len(g.commands);g.tick(1);check(len(g.commands)==start,'hidden status does not emit a sample each second')
for _ in range(4):g.tick(1)
check(len(g.commands)==start+1,'hidden status sends a five-second checkpoint')
g.messages['lowtube-status-visible']('yes');start=len(g.commands);g.tick(1)
check(len(g.commands)==start+1,'open status screen enables one-second sampling')
g.props['time-pos']=200;g.bindings['q']()
check(g.diagnostic_values['time-pos']==200,'closing video sends a final position before stop')
g.events['end-file']();start=len(g.commands);g.tick(20)
check(len(g.commands)==start,'idle player sends no playback samples')
g.payload['session']='session-b';g.messages['lowtube-load']('{}');start=len(g.commands);g.tick(5)
check(len(g.commands)==start,'replacement waits for file-loaded before sampling')
g.props['time-pos']=0;g.events['file-loaded']()
check(g.diagnostic_values['session']=='session-b' and g.diagnostic_values['time-pos']==0,'new file starts with fresh session and position')
print('TOTAL:',n,'Lua checks passed including resume telemetry')

# Prefetch, cancellation and transition ownership use the actual Lua handlers.
lua,g=fixture();g.props['time-pos']=580;g.props['duration']=600;g.props['path']='stream-a';g.props['volume']=37;g.props['speed']=1.25;g.props['pause']=False
first=lua.table_from({'session':'a','debug':False,'safe_seek':True,'language':'en','audio':'audio-a','url':'stream-a','options':lua.table()})
g.payload=first;g.messages['lowtube-load']('{}');g.events['file-loaded']()
next_item=lua.table_from({'owner':'a','session':'b','audio':'audio-b','url':'stream-b','cache_mib':64,'back_mib':4,'options':lua.table_from({'vid':'auto'})})
g.payload=next_item;g.messages['lowtube-stage']('{}')
check(g.native_command['flags']=='append' and g.native_command['url']=='stream-b','prefetch appends exactly the requested next video')
check(g.props['prefetch-playlist'] is True,'native prefetch is enabled near the end')
check(g.props['audio-files'][1]=='audio-a','staging never replaces currently playing audio')
check(g.commands[len(g.commands)][2]=='lowtube-stage-ready' and g.commands[len(g.commands)][3]=='b','stage acknowledgement identifies the exact request')
g.hooks['on_unload']();g.events['end-file']();g.props['path']='stream-b';g.hooks['on_load']()
check(g.commands[len(g.commands)][2]=='lowtube-next-start' and g.commands[len(g.commands)][3]=='b','transition announces new session before playback samples')
check(g.props['file-local-options/audio-files'][1]=='audio-b','next video receives its own audio stream')
check(g.props['file-local-options/volume']==37 and g.props['file-local-options/speed']==1.25 and g.props['file-local-options/pause'] is False,'transition preserves current controls across file-local option resets')
check(g.props['prefetch-playlist'] is False,'transition does not prefetch an unrequested third video')
g.props['time-pos']=0;g.events['file-loaded']()
check(g.props['demuxer-max-bytes']==64*1024*1024 and g.props['demuxer-max-back-bytes']==4*1024*1024,'new active video restores the user buffer limits')
check(g.diagnostic_values['session']=='b','next video progress belongs to the new session')
# A late cancellation of A must not remove an already running B.
g.messages['lowtube-cancel-next']('b')
check(g.commands[len(g.commands)][2]=='lowtube-next-cancelled','late cancellation acknowledges without stopping current video')
next_item['owner']='wrong';next_item['session']='stale';g.payload=next_item;g.messages['lowtube-stage']('{}')
check(g.commands[len(g.commands)][2]=='lowtube-stage-error' and g.props['prefetch-playlist'] is False,'stale owner cannot queue a stream')
next_item['owner']='b';next_item['session']='c';next_item['url']='stream-c';g.messages['lowtube-stage']('{}')
g.messages['lowtube-cancel-next']('unrelated')
check(g.props['prefetch-playlist'] is True,'unrelated cancellation does not discard another request')
g.messages['lowtube-cancel-next']('c')
check(g.props['prefetch-playlist'] is False and g.commands[len(g.commands)-1][1]=='playlist-clear','matching cancellation removes queued native entry')
start=len(g.commands);g.props['path']='stream-c';g.hooks['on_load']();check(len(g.commands)==start,'cancelled entry cannot adopt a new host session')
next_item['session']='d';g.messages['lowtube-stage']('{}');g.bindings['q']()
check(g.props['prefetch-playlist'] is False,'user Stop cancels native prefetch')
print('TOTAL:',n,'Lua checks passed including native playlist prefetch')
