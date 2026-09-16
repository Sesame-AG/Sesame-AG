#!/usr/bin/env python3
"""USB device regression: schedule once while asleep; optionally force-stop Alipay.
Requires the module, framework and executor to be active. Restores the wake-time field.
Usage: python3 tools/check_wakeup.py --output /absolute/evidence [--kill-host]
"""
import argparse
import datetime
import json
from pathlib import Path
import subprocess
import time

HOST = 'com.eg.android.AlipayGphone'
MODULE = 'io.github.aoguai.sesameag'
ROOT = f'/sdcard/Android/media/{HOST}/sesame-AG'

def adb(*args):
    return subprocess.check_output(['adb', *args], text=True, stderr=subprocess.STDOUT)

def field(config):
    return config['modelFieldsMap']['BaseModel']['wakenAtTimeList']

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--kill-host', action='store_true')
    parser.add_argument('--skip-farm-tasks', action='store_true', help='Temporarily isolate the farm daily-task RPC; restore afterward')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    paths = adb('shell', 'ls', f'{ROOT}/config/*/config_v2.json').splitlines()
    assert len(paths) == 1, 'Select one active account before testing'
    remote = paths[0]
    def read():
        return json.loads(adb('shell', 'cat', remote))
    def write(config, reload=True):
        local = args.output / 'config.json'
        local.write_text(json.dumps(config, ensure_ascii=False, indent=2))
        adb('push', str(local), remote)
        if reload:
            adb('shell', 'am', 'broadcast', '-a', HOST + '.sesame.restart', '-p', HOST,
                '--ez', 'configReload', 'true')
    original = read()
    (args.output / 'original-config.json').write_text(json.dumps(original, ensure_ascii=False, indent=2))
    original_value = field(original)['value']
    original_farm = original['modelFieldsMap']['AntFarm']['doFarmTask']['value']
    if args.skip_farm_tasks:
        original['modelFieldsMap']['AntFarm']['doFarmTask']['value'] = False
    trigger = int(adb('shell', 'date', '+%s').strip()) + 100
    token = datetime.datetime.fromtimestamp(trigger).strftime('%H%M%S')
    if token.endswith('00'):
        token = token[:4]
    field(original)['value'] = original_value + ',' + token
    (args.output / 'meta.json').write_text(json.dumps({'token': token, 'trigger': trigger, 'kill': args.kill_host}))
    completed = False
    try:
        write(original)
        time.sleep(12)
        alarms = adb('shell', 'dumpsys', 'alarm')
        (args.output / 'alarms.txt').write_text(alarms)
        assert any(MODULE in line and ('PERSISTENT_SCHEDULE_TRIGGER' in line or 'persistent-schedule-plan' in line)
                   for line in alarms.splitlines()), 'No module-owned broadcast alarm'
        store = json.loads(adb('shell', 'cat', f'{ROOT}/config/DataStore.json'))
        schedule = next(x for x in store['persistentSchedules'] if x['kind'] == 'GLOBAL_WAKEUP'
                        and json.loads(x['payloadJson']).get('waken_time') == token)
        schedule_id = schedule['id']
        (args.output / 'schedule.json').write_text(json.dumps(schedule, ensure_ascii=False, indent=2))
        adb('shell', 'input', 'keyevent', '3')
        adb('shell', 'input', 'keyevent', '223')
        (args.output / 'power-before.txt').write_text(adb('shell', 'dumpsys', 'power'))
        if args.kill_host:
            adb('shell', 'am', 'force-stop', HOST)
            assert subprocess.run(['adb', 'shell', 'pidof', HOST], capture_output=True).returncode == 1
        deadline = time.monotonic() + 600
        while time.monotonic() < deadline:
            time.sleep(5)
            log = adb('shell', 'cat', f'{ROOT}/log/record.log')
            (args.output / 'record.log').write_text(log)
            if log.rfind('offline entered') > log.rfind('本次执行触发'):
                raise AssertionError('Workflow paused by host; resolve the reported verification before retrying')
            matches = [line for line in log.splitlines() if '本次执行触发' in line and token in line]
            if args.kill_host and f'初始化完成，处理持久调度唤醒任务[{schedule_id}]' in log:
                # Cold startup may replace the old schedule while initializing the new session.
                after_init = log.split(f'初始化完成，处理持久调度唤醒任务[{schedule_id}]', 1)[1]
                matches = [line for line in after_init.splitlines() if '本次执行触发' in line]
            if matches:
                print('\n'.join(matches), flush=True)
                if not args.kill_host:
                    assert 'ALARM_WAKEUP' in '\n'.join(matches), 'Wrong execution trigger'
                (args.output / 'power.txt').write_text(adb('shell', 'dumpsys', 'power'))
                (args.output / 'runtime.log').write_text(adb('shell', 'cat', f'{ROOT}/log/runtime.log'))
                completed = True
                print('PASS: scheduled task entered execution', flush=True)
                return
        raise AssertionError('No scheduled task execution before deadline')
    finally:
        current = read()
        field(current)['value'] = original_value
        if args.skip_farm_tasks:
            current['modelFieldsMap']['AntFarm']['doFarmTask']['value'] = original_farm
        log = adb('shell', 'cat', f'{ROOT}/log/record.log')
        paused = log.rfind('offline entered') > log.rfind('本次执行触发')
        write(current, reload=not completed and not paused)
        restored = read()
        assert field(restored)['value'] == original_value
        assert not args.skip_farm_tasks or restored['modelFieldsMap']['AntFarm']['doFarmTask']['value'] == original_farm
        print('Restored original wake times', flush=True)

if __name__ == '__main__':
    main()
