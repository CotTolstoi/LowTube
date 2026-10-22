"""Offline export fixture: Python + yt-dlp. No real browser profile is used."""
import json,os,sqlite3,subprocess,sys,tempfile
from pathlib import Path
with tempfile.TemporaryDirectory() as directory:
 p=Path(directory);db=sqlite3.connect(p/'cookies.sqlite')
 db.execute('CREATE TABLE moz_cookies (host TEXT,name TEXT,value TEXT,path TEXT,expiry INTEGER,isSecure INTEGER,isHttpOnly INTEGER,originAttributes TEXT)')
 db.execute('INSERT INTO moz_cookies VALUES (?,?,?,?,?,?,?,?)',('.youtube.com','TEST_SESSION','FAKE_VALUE','/',2000000000,1,1,''));db.commit();db.close()
 fixture=p/'fixture.json';fixture.write_text(json.dumps(dict(id='session-export',title='session-export',extractor='generic',webpage_url='https://www.youtube.com/',url='https://www.youtube.com/',ext='mp4')))
 result=subprocess.run([sys.executable,'-m','yt_dlp','--ignore-config','--no-plugin-dirs','--no-js-runtimes','--cookies-from-browser','firefox:'+str(p),'--cookies',str(p/'export.txt'),'--load-info-json',str(fixture),'--skip-download','--quiet','--no-warnings'],capture_output=True,text=True,env={**os.environ,'HTTP_PROXY':'http://127.0.0.1:1','HTTPS_PROXY':'http://127.0.0.1:1'},timeout=30)
 assert result.returncode==0,result.stderr
 assert 'FAKE_VALUE' in (p/'export.txt').read_text()
 print('PASS: synthetic Firefox cookie export with an unreachable network proxy')
