-- Loaded explicitly by LowTube; automatic third-party script loading stays off.
local mp = require 'mp'
local options = require 'mp.options'
local opts = { safe_seek = true, language = 'en' }
options.read_options(opts, 'lowtube')
local labels = {
 en = {'Seeking — waiting for video…', 'Seek is taking too long. Playback remains paused; press Space to resume or try another position.', 'F4 / right-click: video settings'},
 uk = {'Перемотування — очікування відео…', 'Перемотування триває надто довго. Відтворення на паузі; натисни пробіл або обери іншу позицію.', 'F4 / права кнопка: параметри відео'},
 ru = {'Перемотка — ожидание видео…', 'Перемотка длится слишком долго. Воспроизведение на паузе; нажми пробел или выбери другую позицию.', 'F4 / правая кнопка: настройки видео'}
}
local text = labels[opts.language] or labels.en
local guard, timer
local function clear()
 if timer then timer:kill(); timer = nil end
 guard = nil
end
local function settings()
 mp.commandv('script-message', 'lowtube-settings')
end
mp.add_forced_key_binding('F4', 'lowtube-settings-key', settings)
mp.add_forced_key_binding('MBTN_RIGHT', 'lowtube-settings-mouse', settings)
-- mpv handles both streams and audio-clock synchronization. Hold user pause
-- during a seek so already buffered audio cannot run ahead of a blocked frame.
mp.register_event('seek', function()
 if not opts.safe_seek or not mp.get_property_native('current-tracks/video') then return end
 local resume = not mp.get_property_native('pause')
 if guard and not resume then resume = guard.resume end
 clear()
 guard = {resume = resume, started = mp.get_time(), ready = false}
 mp.set_property_native('pause', true)
 mp.osd_message(text[1], 2)
 timer = mp.add_periodic_timer(0.1, function()
  if not guard then return end
  if not mp.get_property_native('pause') then clear(); return end -- explicit user resume
  if guard.ready and not mp.get_property_native('seeking') then
   local resume_now = guard.resume
   clear()
   if resume_now then mp.set_property_native('pause', false) end
   mp.osd_message('', 0)
  elseif mp.get_time() - guard.started >= 30 then
   clear() -- fail paused, never release sound on a timeout
   mp.osd_message(text[2], 8)
  end
 end)
end)
mp.register_event('playback-restart', function() if guard then guard.ready = true end end)
mp.register_event('end-file', clear)
mp.register_event('shutdown', clear)
mp.register_event('file-loaded', function() mp.osd_message(text[3], 3) end)


-- Playback diagnostics: bounded sampling, no media identifiers or network calls.
local diagnostic = false
local diagnostic_active = false
local diagnostic_properties = {'time-pos','duration','video-format','video-codec','video-params/w','video-params/h','video-params/pixelformat','container-fps','estimated-vf-fps','hwdec','hwdec-current','hwdec-interop','current-vo','gpu-context','audio-codec','audio-params/samplerate','frame-drop-count','decoder-frame-drop-count','mistimed-frame-count','vo-delayed-frame-count','avsync','total-avsync-change','demuxer-cache-duration','demuxer-cache-state/fw-bytes','demuxer-cache-state/underrun','cache-speed','paused-for-cache','cache-buffering-state','pause','seeking','speed','display-fps','estimated-display-fps','video-bitrate','audio-bitrate'}
local function diagnostic_sample()
 if not diagnostic or not diagnostic_active then return end
 local values = {}
 for _, key in ipairs(diagnostic_properties) do
  local value = mp.get_property_native(key)
  if type(value) == 'number' or type(value) == 'boolean' or type(value) == 'string' then values[key] = value end
 end
 mp.commandv('script-message', 'lowtube-debug', require('mp.utils').format_json(values))
end
mp.add_periodic_timer(2, diagnostic_sample)
mp.register_event('file-loaded', function() diagnostic_active = true; diagnostic_sample() end)
mp.register_event('end-file', function() diagnostic_sample(); diagnostic_active = false end)
mp.add_key_binding('F8', 'lowtube-debug-marker', function()
 if diagnostic then mp.commandv('script-message', 'lowtube-debug-mark'); diagnostic_sample(); mp.osd_message('Playback diagnostic sample recorded', 2) end
end)

-- One bounded IPC sample per five seconds (one second while the status screen is open).
-- Session IDs prevent late samples from the previous file changing the new resume point.
local progress_session, progress_active, status_visible = '', false, false
local last_progress, progress_ticks = nil, 0
local function progress_sample(send)
 if not progress_active or progress_session == '' then return end
 local p = {session=progress_session}
 for _,name in ipairs({'time-pos','duration','pause','paused-for-cache','seeking','cache-buffering-state','demuxer-cache-duration','video-codec','audio-codec','hwdec-current','video-params/w','video-params/h','container-fps','estimated-vf-fps','frame-drop-count','decoder-frame-drop-count','avsync','speed','volume','current-vo','gpu-context'}) do
  p[name] = mp.get_property_native(name)
 end
 if p['time-pos'] ~= nil then last_progress=p end
 if send and last_progress then mp.commandv('script-message','lowtube-progress',require('mp.utils').format_json(last_progress)) end
end
mp.add_periodic_timer(1,function()
 progress_ticks=progress_ticks+1
 progress_sample(status_visible or progress_ticks % 5 == 0)
end)
mp.register_script_message('lowtube-status-visible',function(value) status_visible=value=='yes';progress_sample(true) end)
-- Exactly one upcoming entry. Native mpv prefetch opens its main stream; external
-- audio is attached at on_load, before track selection. No second decoder/player.
local pending_next, restore_cache, transition_controls
local function cancel_next(ticket)
 if pending_next and (not ticket or pending_next.session==ticket) then
  pending_next=nil;transition_controls=nil
  mp.commandv('playlist-clear')
  mp.set_property_native('prefetch-playlist',false)
 end
end
mp.register_script_message('lowtube-cancel-next',function(ticket) cancel_next(ticket);mp.commandv('script-message','lowtube-next-cancelled',ticket) end)
mp.register_script_message('lowtube-stage',function(payload)
 local data=require('mp.utils').parse_json(payload)
 if not data or data.owner~=progress_session or not progress_active then
  if data then mp.commandv('script-message','lowtube-stage-error',data.session) end
  return
 end
 cancel_next()
 pending_next=data
 local result,err=mp.command_native({name='loadfile',url=data.url,flags='append',options=data.options})
 if result==nil and err then pending_next=nil;mp.commandv('script-message','lowtube-stage-error',data.session);return end
 mp.set_property_native('prefetch-playlist',true)
 mp.commandv('script-message','lowtube-stage-ready',data.session)
end)
mp.add_hook('on_unload',50,function()
 if pending_next then
  transition_controls={pause=mp.get_property_native('pause'),volume=mp.get_property_native('volume'),speed=mp.get_property_native('speed')}
 end
end)
mp.add_hook('on_load',50,function()
 if not pending_next or mp.get_property_native('path')~=pending_next.url then return end
 local data=pending_next;pending_next=nil;restore_cache=data
 if transition_controls then
  for name,value in pairs(transition_controls) do mp.set_property_native('file-local-options/'..name,value) end
  transition_controls=nil
 end
 progress_active=false;last_progress=nil;progress_session=data.session;progress_ticks=0
 mp.set_property_native('file-local-options/audio-files',data.audio~='' and {data.audio} or {})
 mp.set_property_native('prefetch-playlist',false)
 mp.commandv('script-message','lowtube-next-start',data.session)
end)
mp.register_event('file-loaded',function()
 if restore_cache then
  mp.set_property_native('demuxer-max-bytes',restore_cache.cache_mib*1024*1024)
  mp.set_property_native('demuxer-max-back-bytes',restore_cache.back_mib*1024*1024)
  restore_cache=nil
 end
 progress_active=true;progress_sample(true)
end)
mp.register_event('end-file',function() progress_sample(true);progress_active=false end)
mp.register_event('shutdown',function() progress_sample(true) end)

-- Keep the idle process, not the previous stream or its demuxer buffer.
local function stop_idle()
 local stopped_session=progress_session
 progress_sample(true);progress_active=false;progress_session='';last_progress=nil;restore_cache=nil
 clear()
 -- Identify the closed session before playlist changes can produce late EOF/errors.
 mp.commandv('script-message','lowtube-stopped',stopped_session)
 cancel_next();mp.commandv('stop')
end
mp.register_script_message('lowtube-stop',stop_idle)
mp.add_forced_key_binding('CLOSE_WIN', 'lowtube-close', stop_idle)
mp.add_forced_key_binding('q', 'lowtube-stop', stop_idle)
mp.register_script_message('lowtube-load', function(payload)
 local data = require('mp.utils').parse_json(payload)
 if not data then return end
 cancel_next();restore_cache=nil
 progress_sample(true);progress_active=false;last_progress=nil;progress_session=data.session or '';progress_ticks=0
 clear()
 diagnostic = data.debug == true
 opts.safe_seek = data.safe_seek
 opts.language = data.language
 text = labels[opts.language] or labels.en
 mp.set_property_native('audio-files', data.audio ~= '' and {data.audio} or {})
 local result, err = mp.command_native({name='loadfile', url=data.url, flags='replace', options=data.options})
 if result == nil and err then mp.commandv('script-message', 'lowtube-load-error') end
end)

-- Small settings menu. No timers or drawing while closed.
local menu_state = {height=720,h264=true,fps=true,language='en',busy=false}
local menu_open, menu_page, menu_index, menu_timer = false, 'main', 1, nil
local menu_overlay, menu_rows, menu_rect
local words = {
 en={'Resolution (maximum)','H.264 only','Up to 30 FPS','Playback speed','Always on top','Borderless window','Advanced settings','Back','On','Off','Applying…'},
 uk={'Роздільна здатність (максимум)','Лише H.264','До 30 кадрів/с','Швидкість відтворення','Поверх інших вікон','Вікно без рамки','Розширені налаштування','Назад','Увімк.','Вимк.','Застосування…'},
 ru={'Разрешение (максимум)','Только H.264','До 30 кадров/с','Скорость воспроизведения','Поверх других окон','Окно без рамки','Расширенные настройки','Назад','Вкл.','Выкл.','Применение…'}
}
local function rows()
 local t=words[menu_state.language] or words.en
 if menu_page=='height' or menu_page=='speed' then
  local result={}
  local values=menu_page=='height' and {144,240,360,480,720,1080,1440,2160} or {0.25,0.5,0.75,1,1.25,1.5,1.75,2}
  local current=menu_page=='height' and menu_state.height or mp.get_property_number('speed',1)
  for _,v in ipairs(values) do result[#result+1]={label=tostring(v)..(menu_page=='height' and 'p' or 'x'),value=current==v and '✓' or '',key=menu_page,argument=tostring(v)} end
  result[#result+1]={label='‹ '..t[8],page='main'}
  return result
 end
 local function boolean(n,key,value) return {label=t[n],value=value and t[9] or t[10],key=key,argument=tostring(not value)} end
 return {
  {label=t[1],value=tostring(menu_state.height)..'p  ›',page='height'},
  boolean(2,'h264',menu_state.h264),boolean(3,'fps',menu_state.fps),
  {label=t[4],value=tostring(mp.get_property_number('speed',1))..'x  ›',page='speed'},
  boolean(5,'ontop',mp.get_property_native('ontop')==true),
  boolean(6,'borderless',mp.get_property_native('border')==false),
  {label=t[7],advanced=true}
 }
end
local function draw_menu()
 if not menu_open then return end
 local w,h=mp.get_osd_size();if not w or not h or h<=0 then return end
 local width=w*720/h;local mw=math.min(460,width-20)
 menu_rows=rows();local x=width-mw-10;local y=720-95-#menu_rows*38
 menu_rect={x=x,y=y,w=mw,h=#menu_rows*38,scale=720/h}
 if not menu_overlay then menu_overlay=mp.create_osd_overlay('ass-events');menu_overlay.z=2000 end
 local a=require('mp.assdraw').ass_new()
 local function box(bx,by,bw,bh,color)
  a:new_event();a:append('{\\an7\\pos(0,0)\\bord0\\shad0\\1c&H'..color..'&\\1a&H18&}')
  a:draw_start();a:rect_cw(bx,by,bx+bw,by+bh);a:draw_stop()
 end
 local function label(px,py,text,align)
  a:new_event();a:append(string.format('{\\an%d\\pos(%f,%f)\\fnSegoe UI\\fs18\\bord0\\shad0\\1c&HFFFFFF&}',align,px,py));a:append(text)
 end
 box(x,y,mw,#menu_rows*38,'202020')
 for i,row in ipairs(menu_rows) do
  local ry=y+(i-1)*38
  if i==menu_index then box(x+2,ry+1,mw-4,36,'484848') end
  label(x+14,ry+9,row.label,7);label(x+mw-14,ry+9,row.value or '',9)
 end
 if menu_state.busy then
  box(x,y,mw,#menu_rows*38,'202020');label(x+14,y+15,(words[menu_state.language] or words.en)[11],7)
 end
 menu_overlay.res_x=width;menu_overlay.res_y=720;menu_overlay.data=a.text;menu_overlay:update()
end
local function close_menu()
 menu_open=false
 if menu_timer then menu_timer:kill();menu_timer=nil end
 if menu_overlay then menu_overlay:remove() end
 for _,k in ipairs({'click','esc','up','down','enter','right','f4','move','double'}) do mp.remove_key_binding('lowtube-menu-'..k) end
 mp.commandv('script-message-to','lowtube_osc','osc-visibility','auto')
end
local function activate_menu(index)
 if menu_state.busy then return end
 local row=menu_rows and menu_rows[index];if not row then return end
 if row.page then menu_page=row.page;menu_index=1;draw_menu()
 elseif row.advanced then close_menu();settings()
 elseif row.key then
  menu_state.busy=true;draw_menu()
  mp.commandv('script-message','lowtube-quick',row.key,row.argument)
 end
end
local function mouse_row()
 if not menu_rect then return nil end
 local x,y=mp.get_mouse_pos();x=x*menu_rect.scale;y=y*menu_rect.scale
 if x<menu_rect.x or x>menu_rect.x+menu_rect.w or y<menu_rect.y or y>=menu_rect.y+menu_rect.h then return nil end
 return math.floor((y-menu_rect.y)/38)+1
end
mp.register_script_message('lowtube-menu-toggle',function()
 if menu_open then close_menu();return end
 menu_open=true;menu_page='main';menu_index=1
 mp.commandv('script-message-to','lowtube_osc','osc-visibility','never')
 mp.add_forced_key_binding('MBTN_LEFT','lowtube-menu-click',function() local i=mouse_row();if i then activate_menu(i) else close_menu() end end)
 mp.add_forced_key_binding('MBTN_LEFT_DBL','lowtube-menu-double',function() end)
 mp.add_forced_key_binding('ESC','lowtube-menu-esc',close_menu)
 mp.add_forced_key_binding('UP','lowtube-menu-up',function() menu_index=math.max(1,menu_index-1);draw_menu() end,{repeatable=true})
 mp.add_forced_key_binding('DOWN','lowtube-menu-down',function() menu_index=math.min(#rows(),menu_index+1);draw_menu() end,{repeatable=true})
 mp.add_forced_key_binding('ENTER','lowtube-menu-enter',function() activate_menu(menu_index) end)
 mp.add_forced_key_binding('MBTN_RIGHT','lowtube-menu-right',function() close_menu();settings() end)
 mp.add_forced_key_binding('F4','lowtube-menu-f4',function() close_menu();settings() end)
 mp.add_forced_key_binding('MOUSE_MOVE','lowtube-menu-move',function() local i=mouse_row();if i and i~=menu_index then menu_index=i;draw_menu() end end)
 draw_menu();menu_timer=mp.add_periodic_timer(0.25,draw_menu)
end)
mp.register_script_message('lowtube-menu-state',function(payload)
 local value=require('mp.utils').parse_json(payload)
 if value then menu_state=value;draw_menu() end
end)
mp.register_event('end-file',close_menu)
mp.register_event('shutdown',close_menu)
