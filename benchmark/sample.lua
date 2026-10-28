local mp=require 'mp'
local utils=require 'mp.utils'
local active=false
local ready=false
local ended=false
local restarts=0
local keys={'time-pos','duration','container-fps','estimated-vf-fps','video-codec','video-format','video-params/w','video-params/h','video-params/pixelformat','hwdec-current','current-vo','gpu-context','frame-drop-count','decoder-frame-drop-count','mistimed-frame-count','vo-delayed-frame-count','avsync','total-avsync-change','paused-for-cache','demuxer-cache-duration','cache-buffering-state','speed','pause','video-bitrate','audio-bitrate'}
mp.register_event('file-loaded',function() active=true end)
mp.register_event('playback-restart',function() ready=true;restarts=restarts+1 end)
mp.register_event('end-file',function() ended=true end)
mp.add_periodic_timer(1,function()
 local data={loaded=active,ready=ready,ended=ended,restarts=restarts}
 for _,key in ipairs(keys) do
  local value=mp.get_property_native(key)
  if type(value)=='number' or type(value)=='string' or type(value)=='boolean' then data[key]=value end
 end
 mp.commandv('script-message','lowtube-benchmark-sample',utils.format_json(data))
end)
