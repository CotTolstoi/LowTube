"""Real mpv smoke test. Requires mpv, ffmpeg and Python; uses synthetic local media.
Run: python test_mpv_reuse.py /path/to/mpv (Unix IPC host).
No YouTube session or network is used. Windows UI/hardware needs a separate test.
"""
import json,os,socket,subprocess,sys,tempfile,time
from pathlib import Path
with tempfile.TemporaryDirectory() as tmp:
 tmp=Path(tmp);media=tmp/'sample.mp4';audio=tmp/'audio.m4a';ipc=tmp/'ipc'
 subprocess.run(['ffmpeg','-v','error','-y','-f','lavfi','-i','testsrc2=size=320x180:rate=24','-f','lavfi','-i','sine=frequency=440','-t','12','-c:v','mpeg4','-c:a','aac',str(media)],check=True)
 subprocess.run(['ffmpeg','-v','error','-y','-i',str(media),'-vn','-c:a','copy',str(audio)],check=True)
 script=Path(__file__).resolve().parents[1]/'player/lowtube.lua'
 log=open(tmp/'mpv.log','w+')
 client,child=socket.socketpair();client.settimeout(8)
 p=subprocess.Popen([sys.argv[1] if len(sys.argv)>1 else 'mpv','--no-config','--load-scripts=no','--ytdl=no','--idle=yes','--force-window=no','--terminal=no','--vo=null','--ao=null','--audio-display=no','--script='+str(script),'--input-ipc-client=fd://'+str(child.fileno())],stdout=log,stderr=log,pass_fds=(child.fileno(),))
 child.close()
 try:
  f=client.makefile('rwb',buffering=0);request=0
  def cmd(*args):
   global request
   request+=1;f.write((json.dumps(dict(command=args,request_id=request))+'\n').encode())
   while True:
    msg=json.loads(f.readline())
    if msg.get('request_id')==request:
     assert msg['error']=='success',msg
     return msg.get('data')
  def load(video,sound='',pos=0,paused=True,vid='auto'):
   payload=dict(url=str(video),audio=str(sound),options={'start':str(pos),'pause':'yes' if paused else 'no','volume':'37','speed':'1.25','vid':vid,'hwdec':'no','cache-secs':'20','demuxer-max-bytes':'16MiB','demuxer-max-back-bytes':'4MiB','cache-pause-wait':'1'},safe_seek=True,language='en')
   cmd('script-message','lowtube-load',json.dumps(payload))
   while True:
    msg=json.loads(f.readline())
    if msg.get('event')=='end-file' and msg.get('reason')=='error':raise AssertionError(msg)
    if msg.get('event')=='file-loaded':break
   time.sleep(.15)
  assert cmd('get_property','idle-active') is True
  pid=p.pid
  load(media,audio,pos=2)
  assert cmd('get_property','pause') is True
  assert abs(cmd('get_property','time-pos')-2)<.3
  tracks=cmd('get_property','track-list');assert any(t.get('external') and t['type']=='audio' for t in tracks),tracks
  load(media,pos=5)
  assert p.pid==pid and p.poll() is None
  assert abs(cmd('get_property','time-pos')-5)<.3
  assert cmd('get_property','volume')==37
  assert cmd('get_property','speed')==1.25
  assert not any(t.get('external') for t in cmd('get_property','track-list'))
  load(audio,vid='no');assert not any(t['type']=='video' for t in cmd('get_property','track-list'))
  cmd('stop');time.sleep(.15)
  assert cmd('get_property','idle-active') is True and p.poll() is None
  load(media,pos=3,paused=False);assert cmd('get_property','pause') is False
  assert any(t['type']=='video' for t in cmd('get_property','track-list'))
  cmd('keypress','q');time.sleep(.15);assert cmd('get_property','idle-active') is True and p.poll() is None
  print('PASS: same mpv PID; idle/load/reload; position/pause/volume/speed; external audio cleared; audio-only/video; q stops to idle')
 finally:
  p.terminate();p.wait(timeout=10);log.seek(0);text=log.read();log.close()
  if 'stack traceback' in text or 'Lua error' in text:print(text);raise AssertionError('Lua error')
