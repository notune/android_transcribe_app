#!/usr/bin/env python3
import json, subprocess, time
from pathlib import Path
import argparse
parser = argparse.ArgumentParser(description="Emulator-only streaming fault regressions")
parser.add_argument("--agent-device", required=True)
parser.add_argument("--serial", required=True)
parser.add_argument("--config", required=True)
parser.add_argument("--session", required=True)
parser.add_argument("--evidence-dir", default="/tmp/ovi-streaming-regressions")
parser.add_argument("--expected-tail", default="college",
    help="Word in the fixture after the forced failure, but before six seconds")
parser.add_argument("--expected-opening", default="Of course",
    help="Opening phrase in the fixture; must appear once, not twice")
args = parser.parse_args()
AGENT = args.agent_device
FLAGS = ["--platform", "android", "--serial", args.serial,
         "--config", args.config, "--session", args.session]
evidence = Path(args.evidence_dir)
evidence.mkdir(parents=True, exist_ok=True)
def ad(*args):
    r=subprocess.run([AGENT,*args,*FLAGS],text=True,capture_output=True)
    if r.returncode: raise RuntimeError(r.stdout+r.stderr)
    return r.stdout

def adb(*cmd,check=True):
    return subprocess.run(['adb','-s',args.serial,*cmd],text=True,capture_output=True,check=check)

def snap(name):
    raw=ad('snapshot','--json');(evidence/(name+'.json')).write_text(raw)
    return json.loads(raw)['data']['nodes']

def ident(nodes,suffix):
    return next(n for n in nodes if n.get('identifier','').endswith(':id/'+suffix))

def click_node(n):
    ad('click','@'+n['ref'])

def click_mic(nodes):
    n=ident(nodes,'ime_record_container');assert n['enabled'], 'Record is disabled'
    r=n['rect'];ad('click',str(r['x']+r['width']//2),str(r['y']+r['height']//2))

def focused(nodes):
    return next(n for n in nodes if n.get('editable') and n.get('focused'))

def field_text(nodes):
    n=focused(nodes)
    if n.get('hintShowing'): return ''
    return n.get('value',n.get('label',''))

def prepare_editor(seed):
    ad('open','com.google.android.contacts')
    nodes=snap('contact-open')
    fields=[n for n in nodes if n.get('editable') and 'Search' not in n.get('label','')]
    if not fields:
        click_node(next(n for n in nodes if n.get('label')=='Create contact'))
        nodes=snap('contact-new')
        fields=[n for n in nodes if n.get('editable')]
    field=fields[0]
    click_node(field)
    nodes=snap('contact-focus')
    field=next(n for n in nodes if n.get('editable') and n.get('focused'))
    ad('fill','@'+field['ref'],seed)
    adb('shell','ime set dev.notune.transcribe/.RustInputMethodService')
    nodes=snap('contact-filled')
    field=next(n for n in nodes if n.get('editable') and n.get('focused'))
    # Focus the field to show our IME after the fill helper switches input methods.
    r=field['rect'];ad('click',str(r['x']+r['width']//2),str(r['y']+r['height']//2))
    nodes=snap('contact-ready')
    ident(nodes,'ime_record_container')
    assert field_text(nodes)==seed, field_text(nodes)
    return nodes

def cycle(nodes, continuous, index):
    before=field_text(nodes)
    click_mic(nodes)
    time.sleep(6)
    nodes=snap('recording-'+str(index))
    during=field_text(nodes)
    if continuous: assert during!=before, (before,during)
    else: assert during==before, (before,during)
    click_mic(nodes)
    for i in range(10):
        time.sleep(0.4)
        nodes=snap('finished-'+str(index))
        mic=ident(nodes,'ime_record_container')
        if mic.get('enabled'): break
    else: raise AssertionError('Record remains disabled after finalization')
    after=field_text(nodes)
    assert after!=before, (before,after)
    assert after.startswith(before), (before,after)
    print('PASS cycle',index,'continuous='+str(continuous), 'during length',len(during), 'final length',len(after),'Record enabled',mic['enabled'],flush=True)
    return nodes

assert adb("shell", "getprop ro.kernel.qemu").stdout.strip() == "1",     "Refusing to change settings or enter test text on a physical phone"
assert adb("shell", "test -r /data/local/tmp/fake_mic.raw", check=False).returncode == 0,     "Install test audio first (mono, 16 kHz float32 little-endian)"
nodes = prepare_editor("Recovery:")
for index in [1, 2]:
    before = field_text(nodes)
    adb("logcat", "-c")
    nodes = cycle(nodes, False, index)
    result = field_text(nodes)
    logs = adb("logcat", "-d").stdout
    (evidence/("recovery-" + str(index) + ".log")).write_text(logs)
    assert "TEST ONLY injected stream failure after partial" in logs, "Fault APK is required"
    assert "retrying the full recording in batch" in logs
    assert args.expected_tail.lower() in result.lower(), "Later audio must survive recovery"
    assert result.count(args.expected_opening) == before.count(args.expected_opening) + 1
    print("PASS: full audio recovery; no duplicate prefix; Record enabled", flush=True)
print("ALL NATIVE STREAMING REGRESSIONS PASSED", flush=True)
