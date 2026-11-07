"""Dedicated emulator only: expanded exclusion, real Back input and failure release.

Uses shell UID via ADB, not an integrated Shizuku flow. Restores the exact DeviceConfig
override, app preferences, pause marker and accessibility settings in finally.
"""

import argparse
import json
import os
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--adb", required=True)
p.add_argument("--serial", required=True)
p.add_argument("--adb-server", default="tcp:5043")
p.add_argument("--output", type=pathlib.Path, required=True)
args = p.parse_args()
env = dict(os.environ, ADB_SERVER_SOCKET=args.adb_server)
cmd = [args.adb, "-s", args.serial]
pkg = "io.buildlee.onehand"
test = pkg + ".test"
key = "system_gesture_exclusion_limit_dp"
pause = "/data/user_de/0/" + pkg + "/no_backup/handle-priority-paused"
rows = []
instrument = None
args.output.mkdir(parents=True, exist_ok=True)


def adb(*a, data=None, check=True):
    return subprocess.run(
        cmd + list(a), input=data, capture_output=True, env=env, check=check, timeout=30
    )


def sh(*a):
    return adb("shell", *a).stdout.decode(errors="replace").strip()


def logs():
    return sh(
        "logcat",
        "-d",
        "-v",
        "brief",
        "-s",
        "OneHandExpandedProbe:I",
        "OneHand:I",
        "OneHandEdgeProbe:I",
        "HandlePriorityGuard:I",
    )


def regions():
    return [
        s.strip()
        for s in sh("dumpsys", "window", "displays").splitlines()
        if "mSystemGestureExclusion=" in s
    ]


def check(name, ok, **extra):
    row = dict(name=name, passed=bool(ok), **extra)
    rows.append(row)
    (args.output / "results.json").write_text(
        json.dumps(rows, indent=2), encoding="utf-8"
    )
    print(json.dumps(row), flush=True)
    if not ok:
        raise AssertionError(name)


def wait(condition, timeout=15):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        if condition():
            return
        time.sleep(0.15)
    raise AssertionError("Timed out; " + logs()[-1800:])


def counter():
    values = re.findall(r"back count=(\d+)", logs())
    return int(values[-1]) if values else 0


def gesture(phase, side, direction, y, app):
    old = counter()
    previous = logs()
    x, target = (0, 240) if side == "L" else (1079, 839)
    dy = {"H": 0, "U": -220, "D": 220}[direction]
    sh("input", "swipe", str(x), str(y), str(target), str(y + dy), "380")
    wait(lambda: counter() > old, 4)
    time.sleep(0.75)
    emitted = logs()[len(previous) :]
    received = len(
        re.findall("input=" + side + direction + " y=" + str(y) + " back=true", emitted)
    )
    received += len(
        re.findall(
            "gesture slot=" + side + "1-S-" + direction + " action=back ok=true",
            emitted,
        )
    )
    check(
        f"{phase}_{side}{direction}_{y}",
        counter() == old + 1 and (app is None or received == int(app)),
        callbacks=counter() - old,
        app_received=received,
    )


def close_instrument():
    global instrument
    if instrument is not None:
        if instrument.poll() is None:
            sh("am", "force-stop", pkg)
        output = instrument.communicate(timeout=10)[0].decode(errors="replace")
        (args.output / ("instrument-" + str(len(rows)) + ".txt")).write_text(
            output, encoding="utf-8"
        )
        instrument = None


def start_probe():
    global instrument
    sh("settings", "delete", "secure", "enabled_accessibility_services")
    time.sleep(0.5)
    close_instrument()
    sh("am", "force-stop", pkg)
    sh("am", "force-stop", test)
    sh(
        "run-as",
        pkg,
        "rm",
        "-f",
        pause,
        pause + ".bak",
        pause + ".new",
        "cache/expanded-probe-stop",
        "cache/expanded-probe-stall",
    )
    root = ET.Element("map")
    for k, v in {
        "master_enabled": True,
        "handle_priority": True,
        "hide_on_home": False,
        "long_enabled": False,
        "vibrate": False,
    }.items():
        ET.SubElement(root, "boolean", name=k, value=str(v).lower())
    for side in ["L", "R"]:
        ET.SubElement(root, "int", name="handle_count_" + side, value="1")
        ET.SubElement(root, "int", name="h_" + side + "1_pos", value="50")
        for direction in ["H", "U", "D"]:
            ET.SubElement(
                root, "string", name="act_" + side + "1-S-" + direction
            ).text = "back"
    adb(
        "shell",
        "run-as",
        pkg,
        "tee",
        "shared_prefs/onehand.xml",
        data=ET.tostring(root, encoding="utf-8", xml_declaration=True),
    )
    adb("logcat", "-c")
    instrument = subprocess.Popen(
        cmd
        + [
            "shell",
            "am",
            "instrument",
            "-w",
            "-e",
            "expandedProbe",
            "true",
            test + "/" + pkg + ".SmokeInstrumentation",
        ],
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    sh("am", "start", "-W", "-n", pkg + "/.MainActivity")
    sh("am", "start", "-W", "-n", test + "/" + pkg + ".EdgeInputProbeActivity")
    sh(
        "settings",
        "put",
        "secure",
        "enabled_accessibility_services",
        pkg + "/" + pkg + ".EdgeService",
    )
    sh("settings", "put", "secure", "accessibility_enabled", "1")
    wait(lambda: "windows ready" in logs())
    wait(lambda: regions() and "SkRegion()" not in regions()[0])


assert sh("getprop", "ro.kernel.qemu") == "1", "Physical devices rejected"
assert sh("am", "get-current-user") == "0"
assert re.findall(r"\d+x\d+", sh("wm", "size"))[-1:] == ["1080x2400"]
assert re.findall(r"\d+", sh("wm", "density"))[-1:] == ["420"]
keys = [
    "enabled_accessibility_services",
    "accessibility_enabled",
    "back_gesture_inset_scale_left",
    "back_gesture_inset_scale_right",
]
saved = {k: sh("settings", "get", "secure", k) for k in keys}
original = adb("exec-out", "run-as", pkg, "cat", "shared_prefs/onehand.xml").stdout
old_limit = sh("device_config", "get", "android", key)
markers = {}
for suffix in ["", ".bak", ".new"]:
    value = adb("exec-out", "run-as", pkg, "cat", pause + suffix, check=False)
    if value.returncode == 0:
        markers[suffix] = value.stdout
try:
    sh("input", "keyevent", "KEYCODE_WAKEUP")
    sh("wm", "dismiss-keyguard")
    sh("device_config", "put", "android", key, "200")
    start_probe()
    check(
        "baseline_200",
        "system_gesture_exclusion_limit_dp=200" in sh("dumpsys", "window", "constants"),
        regions=regions(),
    )
    time.sleep(2)
    (args.output / "baseline-systemui.txt").write_text(
        sh("dumpsys", "activity", "service", "com.android.systemui/.SystemUIService"),
        encoding="utf-8",
    )
    for y in [400, 1200, 2000]:
        for side in ["L", "R"]:
            gesture("baseline", side, "H", y, None)
    sh("device_config", "put", "android", key, "1000")
    wait(lambda: any("90," in s for s in regions()))
    check(
        "expanded_actual_region",
        "system_gesture_exclusion_limit_dp=1000"
        in sh("dumpsys", "window", "constants"),
        regions=regions(),
    )
    for y in [400, 1200, 2000]:
        for side in ["L", "R"]:
            for direction in ["H", "U", "D"]:
                gesture("expanded", side, direction, y, True)
    (args.output / "expanded-window.png").write_bytes(
        adb("exec-out", "screencap", "-p").stdout
    )
    sh("am", "force-stop", pkg)
    close_instrument()
    wait(lambda: bool(regions()) and "SkRegion()" in regions()[0])
    for y in [400, 1200, 2000]:
        for side in ["L", "R"]:
            gesture("after_stop", side, "H", y, False)
    check(
        "system_back_works_with_raised_budget",
        "system_gesture_exclusion_limit_dp=1000"
        in sh("dumpsys", "window", "constants"),
    )
    start_probe()
    sh("run-as", pkg, "touch", "cache/expanded-probe-stall")
    wait(lambda: "main blocked" in logs())
    began = time.monotonic()
    wait(lambda: bool(regions()) and "SkRegion()" in regions()[0], 10)
    check(
        "stall_releases_expanded_windows",
        True,
        seconds=round(time.monotonic() - began, 2),
    )
    check(
        "stall_persists_pause",
        adb("shell", "run-as", pkg, "test", "-f", pause, check=False).returncode == 0,
    )
    close_instrument()
    for y in [400, 1200, 2000]:
        for side in ["L", "R"]:
            gesture("after_stall", side, "H", y, False)
finally:
    (args.output / "last-input.log").write_text(logs(), encoding="utf-8")
    sh("settings", "delete", "secure", "enabled_accessibility_services")
    time.sleep(0.5)
    close_instrument()
    sh("am", "force-stop", pkg)
    sh("am", "force-stop", test)
    adb("shell", "run-as", pkg, "tee", "shared_prefs/onehand.xml", data=original)
    sh(
        "run-as",
        pkg,
        "rm",
        "-f",
        pause,
        pause + ".bak",
        pause + ".new",
        "cache/expanded-probe-stop",
        "cache/expanded-probe-stall",
    )
    for suffix, value in markers.items():
        adb("shell", "run-as", pkg, "tee", pause + suffix, data=value)
    if old_limit == "null":
        sh("device_config", "delete", "android", key)
    else:
        sh("device_config", "put", "android", key, old_limit)
    for k, v in saved.items():
        if v == "null":
            sh("settings", "delete", "secure", k)
        else:
            sh("settings", "put", "secure", k, v)
    check(
        "original_state_restored",
        sh("device_config", "get", "android", key) == old_limit
        and all(sh("settings", "get", "secure", k) == v for k, v in saved.items())
        and adb("exec-out", "run-as", pkg, "cat", "shared_prefs/onehand.xml").stdout
        == original,
    )
