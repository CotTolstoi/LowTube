local mp=require 'mp'
local utils=require 'mp.utils'
mp.add_periodic_timer(1,function()
 local data={}
 for _,k in ipairs({'time-pos','avsync','frame-drop-count','mistimed-frame-count','vo-delayed-frame-count','pause','paused-for-cache','hwdec-current','video-codec'}) do data[k]=mp.get_property_native(k) end
 mp.msg.warn('HYBRID_SAMPLE '..utils.format_json(data))
end)
