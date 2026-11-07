"""Real Shizuku replacement flow; strictly limited to the dedicated 1080x2400 emulator."""

import argparse
import json
import io
import os
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from PIL import Image

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--adb", required=True)
p.add_argument("--serial", required=True)
p.add_argument("--adb-server", default="tcp:5043")
p.add_argument("--output", type=pathlib.Path, required=True)
p.add_argument(
    "--scenario",
    choices=[
        "full",
        "guard_die",
        "shizuku_die",
        "dual_die",
        "trial_timeout",
        "conflict",
    ],
    default="full",
)
args = p.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
cmd = [args.adb, "-s", args.serial]
env = dict(os.environ, ADB_SERVER_SOCKET=args.adb_server)
pkg = "io.buildlee.onehand"
test = pkg + ".test"
key = "system_gesture_exclusion_limit_dp"
pause = "/data/user_de/0/" + pkg + "/no_backup/handle-priority-paused"
journal = "/data/user_de/0/" + pkg + "/no_backup/exclusion-budget/session.json"
rows = []
instrument = None


def adb(*a, data=None, check=True):
    return subprocess.run(
        cmd + list(a), input=data, env=env, capture_output=True, check=check, timeout=30
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
        "OneHand:I",
        "OneHandEdgeProbe:I",
        "OneHandReplacementTest:I",
        "OneHandBudgetGuard:I",
        "OneHandRecovery:I",
        "HandlePriorityGuard:I",
    )


def check(name, ok, **details):
    row = dict(name=name, passed=bool(ok), **details)
    rows.append(row)
    (args.output / "results.json").write_text(
        json.dumps(rows, indent=2), encoding="utf-8"
    )
    print(json.dumps(row), flush=True)
    if not ok:
        raise AssertionError(name + " " + logs()[-1800:])


def wait(predicate, seconds=15):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        if predicate():
            return
        time.sleep(0.15)
    raise AssertionError("Timeout " + logs()[-2400:])


def regions():
    return [
        line.strip()
        for line in sh("dumpsys", "window", "displays").splitlines()
        if "mSystemGestureExclusion=" in line
    ]


def budget():
    return sh("device_config", "get", "android", key)


def pending():
    return (
        adb("shell", "run-as", pkg, "test", "-f", journal, check=False).returncode == 0
    )


def count():
    values = re.findall(r"back count=(\d+)", logs())
    return int(values[-1]) if values else 0


def gesture(phase, side, direction, y, app):
    before = count()
    previous = logs()
    x, target = (0, 240) if side == "L" else (1079, 839)
    dy = {"H": 0, "U": -220, "D": 220}[direction]
    sh("input", "swipe", str(x), str(y), str(target), str(y + dy), "380")
    wait(lambda: count() > before, 4)
    time.sleep(0.4)
    emitted = logs()[len(previous) :]
    received = len(
        re.findall(
            "gesture slot=" + side + "1-S-" + direction + " action=back ok=true",
            emitted,
        )
    )
    check(
        phase + "_" + side + direction + "_" + str(y),
        count() == before + 1 and received == int(app),
        callbacks=count() - before,
        app_received=received,
    )


def request(name):
    sh("run-as", pkg, "touch", "cache/replacement-" + name)


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


def observer():
    sh("am", "start", "-W", "-n", test + "/" + pkg + ".EdgeInputProbeActivity")
    time.sleep(0.3)


def calibrate(phase):
    observer()
    for side in ["L", "R"]:
        gesture(phase, side, "H", 1200, True)
    previous = logs()
    request("confirm")
    wait(lambda: "confirmed" in logs()[len(previous) :])


def restore_entry(phase):
    sh("am", "start", "-W", "-n", pkg + "/.RecoveryActivity")
    time.sleep(0.7)
    tap_restore_button()
    wait(lambda: budget() == original_budget and not pending(), 20)
    wait(lambda: regions() and "SkRegion()" in regions()[0])
    check(phase + "_budget_and_windows_restored", True)


def tap_restore_button():
    # Locate the native green button in the screenshot, without disturbing accessibility.
    # Its Y changes when a longer error message reflows the recovery page.
    screenshot = Image.open(
        io.BytesIO(adb("exec-out", "screencap", "-p").stdout)
    ).convert("RGB")
    x = screenshot.width // 4
    ys = [
        y
        for y in range(100, screenshot.height - 100)
        if screenshot.getpixel((x, y)) == (27, 103, 82)
    ]
    assert ys and max(ys) - min(ys) > 80, "Recovery button not visible"
    sh("input", "tap", str(screenshot.width // 2), str((min(ys) + max(ys)) // 2))


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
        *[
            f"cache/replacement-{n}"
            for n in ["confirm", "restart", "stall", "close", "zero-left", "full-left"]
        ],
    )
    root = ET.Element("map")
    for k, v in {
        "master_enabled": True,
        "hide_on_home": False,
        "long_enabled": False,
        "vibrate": False,
    }.items():
        ET.SubElement(root, "boolean", name=k, value=str(v).lower())
    for side in ["L", "R"]:
        ET.SubElement(root, "int", name="handle_count_" + side, value="1")
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
            "replacementFlow",
            "true",
            test + "/" + pkg + ".SmokeInstrumentation",
        ],
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    sh("am", "start", "-W", "-n", pkg + "/.MainActivity")
    sh(
        "settings",
        "put",
        "secure",
        "enabled_accessibility_services",
        pkg + "/" + pkg + ".EdgeService",
    )
    sh("settings", "put", "secure", "accessibility_enabled", "1")
    wait(lambda: "trial ready" in logs() or "permission requested" in logs(), 25)
    if "permission requested" in logs() and "trial ready" not in logs():
        sh("uiautomator", "dump", "/data/local/tmp/replacement-permission.xml")
        tree = ET.fromstring(sh("cat", "/data/local/tmp/replacement-permission.xml"))
        allow = next(
            node
            for node in tree.iter("node")
            if node.get("text") in ["允许", "始终允许", "Allow", "Allow all the time"]
        )
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", allow.get("bounds")))
        sh("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    wait(lambda: "trial ready" in logs(), 25)
    wait(lambda: regions() and "SkRegion()" not in regions()[0])
    check("trial_uses_real_shizuku_budget", int(budget()) > 200, regions=regions())
    (args.output / ("replacement-page-" + str(len(rows)) + ".png")).write_bytes(
        adb("exec-out", "screencap", "-p").stdout
    )
    if args.scenario != "trial_timeout":
        calibrate("calibration")


def fault_scenario():
    target = budget()
    if args.scenario == "trial_timeout":
        wait(
            lambda: budget() == original_budget
            and regions()
            and "SkRegion()" in regions()[0],
            70,
        )
        check("unconfirmed_trial_expires_without_fallback", True)
    elif args.scenario == "conflict":
        sh("device_config", "put", "android", key, "1100")
        sh("am", "start", "-W", "-n", pkg + "/.RecoveryActivity")
        time.sleep(0.7)
        tap_restore_button()
        wait(lambda: regions() and "SkRegion()" in regions()[0])
        time.sleep(2)
        check("unrelated_budget_not_overwritten", budget() == "1100" and pending())
        sh("device_config", "put", "android", key, target)
    else:
        victims = []
        if args.scenario in ["guard_die", "dual_die"]:
            processes = [
                line.split(None, 1)
                for line in sh("ps", "-A", "-o", "PID,NAME").splitlines()[1:]
            ]
            victims.append(
                next(
                    fields[0]
                    for fields in processes
                    if len(fields) == 2 and fields[1] == pkg + ":budget_guard"
                )
            )
        if args.scenario in ["shizuku_die", "dual_die"]:
            victims.append(sh("pidof", "shizuku_server"))
        assert all(value.isdigit() for value in victims)
        sh("kill", *victims)
        wait(lambda: regions() and "SkRegion()" in regions()[0], 12)
        if args.scenario in ["guard_die", "dual_die"]:
            check(
                "dead_guard_preserves_recovery_journal",
                budget() == target and pending(),
            )
        else:
            time.sleep(5)
            automatically_restored = budget() == original_budget
            check(
                "server_loss_pauses_windows_and_preserves_recovery",
                automatically_restored or pending(),
                budget_restored=automatically_restored,
            )
    observer()
    for y in [400, 1200, 2000]:
        for side in ["L", "R"]:
            gesture(args.scenario + "_system_back", side, "H", y, False)
    if args.scenario in ["shizuku_die", "dual_die"]:
        apk = sh("pm", "path", "moe.shizuku.privileged.api").removeprefix("package:")
        sh(apk.removesuffix("/base.apk") + "/lib/x86_64/libshizuku.so")
        time.sleep(1)
    restore_entry(args.scenario + "_recovery")


assert sh("getprop", "ro.kernel.qemu") == "1", "Physical devices rejected"
assert re.findall(r"\d+x\d+", sh("wm", "size"))[-1:] == ["1080x2400"]
assert re.findall(r"\d+", sh("wm", "density"))[-1:] == ["420"]
assert sh("am", "get-current-user") == "0"
assert not pending(), "Existing budget journal must be recovered first"
original_budget = budget()
keys = [
    "enabled_accessibility_services",
    "accessibility_enabled",
    "back_gesture_inset_scale_left",
    "back_gesture_inset_scale_right",
]
saved = {k: sh("settings", "get", "secure", k) for k in keys}
server_initially_running = (
    adb("shell", "pidof", "shizuku_server", check=False).returncode == 0
)
prefs = adb("exec-out", "run-as", pkg, "cat", "shared_prefs/onehand.xml").stdout
markers = {}
for suffix in ["", ".bak", ".new"]:
    record = adb("exec-out", "run-as", pkg, "cat", pause + suffix, check=False)
    if record.returncode == 0:
        markers[suffix] = record.stdout
try:
    sh("input", "keyevent", "KEYCODE_WAKEUP")
    sh("wm", "dismiss-keyguard")
    start_probe()
    if args.scenario != "full":
        fault_scenario()
        raise SystemExit(0)
    for y in [400, 1200, 2000]:
        for side in ["L", "R"]:
            for direction in ["H", "U", "D"]:
                gesture("replacement", side, direction, y, True)
    request("zero-left")
    wait(lambda: regions() and "(0," not in regions()[0] and "(1017," in regions()[0])
    check("zero_left_removes_only_left_window", True)
    gesture("zero_left_native_back", "L", "H", 1200, False)
    before = count()
    previous = logs()
    sh("input", "swipe", "99", "1200", "339", "1200", "380")
    time.sleep(0.8)
    check(
        "zero_left_does_not_create_inset_handle",
        count() == before and "gesture slot=L1" not in logs()[len(previous) :],
    )
    request("full-left")
    wait(lambda: regions() and "(0," in regions()[0])
    gesture("restored_full_left", "L", "H", 1200, True)
    pid = sh("pidof", pkg)
    restore_entry("manual")
    check("manual_keeps_accessibility_process", sh("pidof", pkg) == pid)
    observer()
    for side in ["L", "R"]:
        gesture("manual_system_back", side, "H", 1200, False)
    previous = logs()
    request("restart")
    wait(lambda: "trial ready" in logs()[len(previous) :], 25)
    calibrate("reenabled_without_accessibility_toggle")
    sh("am", "force-stop", pkg)
    close_instrument()
    wait(
        lambda: budget() == original_budget
        and regions()
        and "SkRegion()" in regions()[0],
        12,
    )
    check("process_death_restores_budget_automatically", True)
    for side in ["L", "R"]:
        gesture("after_death", side, "H", 1200, False)
    restore_entry("after_death_journal")
    start_probe()
    request("stall")
    wait(lambda: "main blocked" in logs())
    began = time.monotonic()
    wait(
        lambda: budget() == original_budget
        and regions()
        and "SkRegion()" in regions()[0],
        12,
    )
    check(
        "blocked_main_restores_budget_and_windows",
        True,
        seconds=round(time.monotonic() - began, 2),
    )
    close_instrument()
    for y in [400, 1200, 2000]:
        for side in ["L", "R"]:
            gesture("after_stall", side, "H", y, False)
    restore_entry("after_stall_journal")
finally:
    (args.output / "last-input.log").write_text(logs(), encoding="utf-8")
    sh("settings", "delete", "secure", "enabled_accessibility_services")
    time.sleep(0.4)
    close_instrument()
    sh("am", "force-stop", pkg)
    sh("am", "force-stop", test)
    if (
        server_initially_running
        and adb("shell", "pidof", "shizuku_server", check=False).returncode != 0
    ):
        apk = sh("pm", "path", "moe.shizuku.privileged.api").removeprefix("package:")
        sh(apk.removesuffix("/base.apk") + "/lib/x86_64/libshizuku.so")
    # A failed check still restores the exact original override, never an invented default.
    if budget() != original_budget:
        if original_budget == "null":
            sh("device_config", "delete", "android", key)
        else:
            sh("device_config", "put", "android", key, original_budget)
    if pending():
        record = json.loads(adb("exec-out", "run-as", pkg, "cat", journal).stdout)
        if record.get("original") == (
            None if original_budget == "null" else original_budget
        ):
            sh("run-as", pkg, "rm", "-f", journal, journal + ".bak", journal + ".new")
    adb("shell", "run-as", pkg, "tee", "shared_prefs/onehand.xml", data=prefs)
    sh(
        "run-as",
        pkg,
        "rm",
        "-f",
        pause,
        pause + ".bak",
        pause + ".new",
        *[
            f"cache/replacement-{n}"
            for n in ["confirm", "restart", "stall", "close", "zero-left", "full-left"]
        ],
    )
    for suffix, value in markers.items():
        adb("shell", "run-as", pkg, "tee", pause + suffix, data=value)
    for k, v in saved.items():
        if v == "null":
            sh("settings", "delete", "secure", k)
        else:
            sh("settings", "put", "secure", k, v)
    check(
        "original_state_restored",
        budget() == original_budget
        and not pending()
        and all(sh("settings", "get", "secure", k) == v for k, v in saved.items())
        and adb("exec-out", "run-as", pkg, "cat", "shared_prefs/onehand.xml").stdout
        == prefs,
    )
