from pathlib import Path
from lupa import LuaRuntime
lua=LuaRuntime(unpack_returned_tuples=True)
lua.execute('''
props={['time-pos']=12,['hwdec-current']='dxva2',['path']='secret URL',['cookies']='secret'}
events={};sample=nil
local mp={}
function mp.register_event(k,f) events[k]=f end
function mp.get_property_native(k) return props[k] end
function mp.add_periodic_timer(s,f) tick=f end
function mp.commandv(...) command={...} end
package.preload['mp']=function() return mp end
package.preload['mp.utils']=function() return {format_json=function(d) sample=d;return '{}' end} end
''')
lua.execute((Path(__file__).resolve().parents[1]/'sample.lua').read_text())
g=lua.globals();g.tick();assert not g.sample['loaded'] and not g.sample['ready']
g.events['file-loaded']();g.events['playback-restart']();g.tick();assert g.sample['loaded'] and g.sample['ready'] and g.sample['restarts']==1
assert g.sample['hwdec-current']=='dxva2' and g.sample['time-pos']==12
assert g.sample['path'] is None and g.sample['cookies'] is None
assert g.command[1]=='script-message' and g.command[2]=='lowtube-benchmark-sample'
g.events['end-file']();g.tick();assert g.sample['ended']
print('PASS: 6 sampler checks (lifecycle, readiness, actual decoder, privacy, IPC, EOF)')
