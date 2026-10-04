#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
"""On a dedicated arm64 emulator, exercise the real system installer from the debug app.

Usage: e2e-update-install.py <debug-apk> <signed-release-apk> <output-directory>
Seeds a verified public APK fixture, without network downloads. Changes install-source
permission for the debug app and installs the release APK through Android's confirmation.
Play Protect is scanned if requested; only an explicitly safe scan may be approved.
"""
import argparse, hashlib, json, os, re, subprocess, time, xml.etree.ElementTree as E
from pathlib import Path
parser=argparse.ArgumentParser(); parser.add_argument('app_apk'); parser.add_argument('update_apk'); parser.add_argument('out'); args=parser.parse_args()
out=Path(args.out).resolve(); out.mkdir(parents=True,exist_ok=True)
pkg='io.github.lewin671.localvoiceime.debug'; target='io.github.lewin671.localvoiceime'
def adb(*cmd,stdin=None):
    return subprocess.run(['adb','-s','emulator-5554',*cmd],input=stdin,text=True,capture_output=True,check=True).stdout

def snap(name):
    adb('shell','uiautomator','dump','--windows','/sdcard/update-probe.xml')
    adb('pull','/sdcard/update-probe.xml',str(out/(name+'.xml')))
    return list(E.parse(out/(name+'.xml')).iter('node'))
def texts(nodes): return [n.get('text') for n in nodes if n.get('text')]
def tap(nodes,label,home=False):
    n=next(n for n in nodes if n.get('text','').casefold()==label.casefold() or n.get('content-desc','').casefold()==label.casefold())
    l,t,r,b=map(int,re.findall(r'\d+',n.get('bounds')))
    if home:
        adb('shell','sh',stdin=f'input tap {(l+r)//2} {(t+b)//2}\ninput keyevent KEYCODE_HOME\n')
    else:
        adb('shell','input','tap',str((l+r)//2),str((t+b)//2))
source=Path(args.app_apk).resolve()
print(adb('install','-r','-g',str(source)),flush=True)
apk=Path(args.update_apk).resolve()
sdk=Path(os.environ.get('ANDROID_HOME', str(Path.home()/'Library/Android/sdk')))
if not sdk.is_dir(): sdk=Path.home()/'Android/Sdk'
build_tools=max((sdk/'build-tools').iterdir(), key=lambda p:tuple(int(v) for v in p.name.split('.') if v.isdigit()))
badging=subprocess.check_output([str(build_tools/'aapt2'),'dump','badging',str(apk)],text=True)
assert "package: name='"+target+"'" in badging, 'The update must be the production APK of this project'
expected_code=int(re.search(r"versionCode='(\d+)'",badging).group(1))
name='installer-test-arm64-v8a.apk'; sha=hashlib.sha256(apk.read_bytes()).hexdigest()
record={'tag':'v99.0.0','notes':'Public fixture for the system installer regression.', 'apk':{'name':name,'size':apk.stat().st_size,'sha256':sha}}
adb('shell','am','force-stop',pkg)
remote='/data/local/tmp/update-probe.apk'
adb('shell','mkdir','-p',str(Path(remote).parent)); adb('push',str(apk),remote)
adb('shell','run-as',pkg,'mkdir','-p','files/app-update/v99.0.0')
adb('shell',f"cat {remote} | run-as {pkg} sh -c 'cat > files/app-update/v99.0.0/{name}'")
adb('shell','rm','-f',remote)
adb('shell',f"run-as {pkg} sh -c 'cat > files/app-update/release.json'",stdin=json.dumps(record))
adb('shell',f"run-as {pkg} sh -c 'cat > files/app-update/v99.0.0/installed'",stdin=sha)
adb('shell','appops','set',pkg,'REQUEST_INSTALL_PACKAGES','deny')
adb('logcat','-c')
adb('shell','am','start','-W','-n',pkg+'/org.fcitx.fcitx5.android.ui.main.MainActivity')
time.sleep(2); adb('shell','input','swipe','500','1500','500','500','200'); time.sleep(1)
tap(snap('settings'),'App update'); time.sleep(2)
tap(snap('ready'),'Install'); time.sleep(3)
nodes=snap('after-install'); print('After Install:',texts(nodes),flush=True)
(out/'logcat.txt').write_text(adb('logcat','-d'))
assert any('Allow from this source' in t for t in texts(nodes)),texts(nodes)
# Deny permission, return, and ensure the app retains a clear retry action.
adb('shell','input','keyevent','KEYCODE_BACK'); time.sleep(2)
nodes=snap('permission-denied'); assert any(t.casefold()=='allow installation' for t in texts(nodes)),texts(nodes)
tap(nodes,'Allow installation'); time.sleep(2)
nodes=snap('permission-retry'); tap(nodes,'Allow from this source'); time.sleep(1)
adb('shell','input','keyevent','KEYCODE_BACK'); time.sleep(4)
nodes=snap('confirmation'); print('Confirmation:',texts(nodes),flush=True)
assert any('Local Voice IME' in t for t in texts(nodes)),texts(nodes)
# Cancelling keeps the package and permits a second attempt.
tap(nodes,'Cancel'); time.sleep(2)
nodes=snap('cancelled'); assert any(t.casefold()=='install' for t in texts(nodes)),texts(nodes)
tap(nodes,'Install',home=True); time.sleep(3)
nodes=snap('background')
assert 'Do you want to update this app?' not in texts(nodes),texts(nodes)
adb('shell','am','start','-W','-n',pkg+'/org.fcitx.fcitx5.android.ui.main.MainActivity')
time.sleep(3)
nodes=snap('retry-confirmation')
assert 'Do you want to update this app?' in texts(nodes),texts(nodes)
print('PASS: confirmation waits while hidden and opens on return',flush=True)
label='Update' if any(t.casefold()=='update' for t in texts(nodes)) else 'Install'
tap(nodes,label)
# Respect Play Protect: request a scan, and approve only its explicitly safe result.
for i in range(30):
    time.sleep(2)
    nodes=snap('install-result')
    labels=texts(nodes)
    if any(t.casefold()=='scan app' for t in labels):
        tap(nodes,'Scan app')
    elif 'This app looks safe' in labels:
        tap(nodes,'Install')
    installed=adb('shell','dumpsys','package',target)
    log=adb('logcat','-d')
    if f'versionCode={expected_code} ' in installed and 'Installer: status 0 ' in log:
        break
else:
    (out/'logcat.txt').write_text(log)
    raise AssertionError(('Installation did not finish',labels))
(out/'installed-version.txt').write_text('\n'.join(s for s in installed.splitlines() if 'versionCode=' in s or 'versionName=' in s))
print('PASS: permission denied/retry, foreground confirmation, cancellation/retry and system install completed',flush=True)
print((out/'installed-version.txt').read_text(),flush=True)
(out/'logcat.txt').write_text(log)
