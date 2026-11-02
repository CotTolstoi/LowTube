"""Linux/Windows FFmpeg integration check: GOP concatenation equals full decode.
Hardware execution is a separate Windows test; this verifies boundaries/order.
"""
import json, subprocess, tempfile, pathlib, hashlib

def run(args):
    return subprocess.check_output(args, stderr=subprocess.PIPE)

with tempfile.TemporaryDirectory() as tmp:
    for fps, container, opengop in [('30','mp4',0), ('30000/1001','mkv',0), ('30','mp4',1)]:
        file=str(pathlib.Path(tmp)/('test.'+container))
        run(['ffmpeg','-v','error','-y','-f','lavfi','-i',f'testsrc2=size=160x96:rate={fps}',
             '-t','5.5','-c:v','libx264','-g','30','-keyint_min','30','-sc_threshold','0',
             '-x264-params',f'open-gop={opengop}',file])
        probe=json.loads(run(['ffprobe','-v','error','-select_streams','v:0','-show_frames',
            '-show_entries','frame=key_frame,best_effort_timestamp_time','-of','json',file]))
        frames=probe['frames']; starts=[i for i,f in enumerate(frames) if f['key_frame']]
        chunks=[]
        for n,start in enumerate(starts):
            count=(starts[n+1] if n+1<len(starts) else len(frames))-start
            data=run(['ffmpeg','-v','error','-xerror','-threads','1','-hwaccel','none',
                '-ss',frames[start]['best_effort_timestamp_time'],'-accurate_seek','-i',file,
                '-map','0:v:0','-an','-sn','-dn','-vf','format=yuv420p','-frames:v',str(count),
                '-fps_mode','passthrough','-threads','1','-f','rawvideo','-pix_fmt','yuv420p','pipe:1'])
            assert len(data)==count*160*96*3//2, (fps,start,len(data))
            chunks.append(data)
        full=run(['ffmpeg','-v','error','-i',file,'-map','0:v:0','-an',
                  '-fps_mode','passthrough','-f','rawvideo','-pix_fmt','yuv420p','pipe:1'])
        assert b''.join(chunks)==full, (fps,container,opengop,'frame mismatch')
        print('PASS',fps,container,'open_gop='+str(opengop),len(frames),'frames',hashlib.sha256(full).hexdigest())
