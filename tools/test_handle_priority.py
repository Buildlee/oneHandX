"""Destructive fault injection is restricted to an explicitly selected Android emulator.

Install the debug app/test APKs and launch OneHandX once before running. The fixture
temporarily changes this app's preferences and the emulator's accessibility services,
then restores the snapshots. Output belongs in a temporary evidence directory.
"""

import argparse, json, os, pathlib, re, shutil, subprocess, tempfile, time, xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--adb", default=shutil.which("adb"))
parser.add_argument(
    "--serial",
    required=True,
    help="Dedicated emulator serial; physical devices are rejected",
)
parser.add_argument("--adb-server", help="Optional ADB_SERVER_SOCKET, such as tcp:5043")
parser.add_argument("--output", type=pathlib.Path, help="Temporary evidence directory")
parser.add_argument(
    "--scenario",
    default="normal",
    choices=[
        "normal",
        "multi",
        "manual",
        "stall",
        "guard_die",
        "screen",
        "sparse_moves",
        "resume_ui",
        "resume_ui_dark",
    ],
)
args = parser.parse_args()
if not args.adb:
    parser.error("Provide --adb or put adb on PATH")
ROOT = args.output or pathlib.Path(tempfile.mkdtemp(prefix="onehand-priority-"))
ROOT.mkdir(parents=True, exist_ok=True)
CMD = [args.adb, "-s", args.serial]
ENV = dict(os.environ)
if args.adb_server:
    ENV["ADB_SERVER_SOCKET"] = args.adb_server
PKG = "io.buildlee.onehand"
TEST = PKG + ".test"
KEYS = [
    "back_gesture_inset_scale_left",
    "back_gesture_inset_scale_right",
    "enabled_accessibility_services",
    "accessibility_enabled",
]
rows = []
scenario = args.scenario
PAUSE = "/data/user_de/0/" + PKG + "/no_backup/handle-priority-paused"
instrument = None


def adb(*args, data=None):
    return subprocess.run(
        CMD + list(args),
        input=data,
        env=ENV,
        capture_output=True,
        check=True,
        timeout=30,
    ).stdout


def sh(*args):
    return adb("shell", *args).decode("utf-8", errors="replace").strip()


def get(key):
    return sh("settings", "get", "secure", key)


def check(name, ok, **extra):
    row = dict(name=name, passed=bool(ok), **extra)
    rows.append(row)
    print(json.dumps(row), flush=True)
    if not ok:
        raise AssertionError(name)


def logs():
    return sh(
        "logcat",
        "-d",
        "-v",
        "brief",
        "-s",
        "OneHand:I",
        "OneHandEdgeProbe:I",
        "HandlePriorityGuard:I",
        "OneHandPriorityTest:I",
    )


def wait_log(fragment, seconds=12):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        if fragment in logs():
            return
        time.sleep(0.2)
    raise AssertionError("missing log " + fragment + " " + logs()[-1500:])


def count():
    values = re.findall(r"back count=(\d+)", logs())
    return int(values[-1]) if values else 0


def assert_recovery_foreground():
    top = [
        line
        for line in sh("dumpsys", "activity", "activities").splitlines()
        if "ResumedActivity:" in line
    ]
    check(
        "recovery_entry_has_its_own_foreground",
        any(PKG + "/.RecoveryActivity" in line for line in top),
    )


def click_text(label):
    for _ in range(4):
        sh("uiautomator", "dump", "/data/local/tmp/priority-settings.xml")
        root = ET.fromstring(
            adb("shell", "cat", "/data/local/tmp/priority-settings.xml")
        )

        def find(node, clickable=None):
            if node.get("clickable") == "true":
                clickable = node
            if node.get("text") == label:
                return clickable if clickable is not None else node
            for child in node:
                item = find(child, clickable)
                if item is not None:
                    return item

        item = find(root)
        if item is not None:
            a, b, c, d = map(int, re.findall(r"\d+", item.get("bounds")))
            sh("input", "tap", str((a + c) // 2), str((b + d) // 2))
            time.sleep(0.5)
            return
        sh("input", "swipe", "540", "1900", "540", "700", "350")
    raise AssertionError("missing UI " + label)


def gesture(side, direction, y):
    start = count()
    before = len(logs())
    x, target = (0, 240) if side == "L" else (1079, 839)
    dy = {"H": 0, "U": -300, "D": 300}[direction]
    sh("input", "swipe", str(x), str(y), str(target), str(y + dy), "400")
    # Software-rendered emulators may dispatch Back after the shell input command returns.
    deadline = time.monotonic() + 4
    while count() == start and time.monotonic() < deadline:
        time.sleep(0.15)
    time.sleep(0.3)  # Also catch a duplicate callback from competing gesture owners.
    emitted = logs()[before:]
    check(
        "single_back_" + side + direction + "_" + str(y),
        count() == start + 1,
        previous=start,
        current=count(),
        app_log=emitted,
    )
    return emitted


if sh("getprop", "ro.kernel.qemu") != "1":
    raise SystemExit(
        "Physical devices are not permitted for these fault-injection fixtures"
    )
if (
    sh("am", "get-current-user") != "0"
    or re.findall(r"\d+x\d+", sh("wm", "size"))[-1:] != ["1080x2400"]
    or re.findall(r"\d+", sh("wm", "density"))[-1:] != ["420"]
):
    raise SystemExit(
        "This coordinate fixture requires user 0 and a 1080x2400 / 420dpi emulator"
    )
saved = {key: get(key) for key in KEYS}
prefs = adb("exec-out", "run-as", PKG, "cat", "shared_prefs/onehand.xml")
old_marker = subprocess.run(
    CMD + ["exec-out", "run-as", PKG, "cat", PAUSE], env=ENV, capture_output=True
)
marker = old_marker.stdout if old_marker.returncode == 0 else None
try:
    sh("input", "keyevent", "KEYCODE_WAKEUP")
    sh("wm", "dismiss-keyguard")
    # Always create a real disabled -> enabled transition, including after an interrupted test.
    sh("settings", "delete", "secure", "enabled_accessibility_services")
    time.sleep(0.3)
    sh("am", "force-stop", PKG)
    sh("am", "force-stop", TEST)
    sh("run-as", PKG, "rm", "-f", PAUSE, PAUSE + ".bak", PAUSE + ".new")
    fixture = ET.Element("map")
    ET.SubElement(fixture, "int", name="model", value="2")
    ET.SubElement(
        fixture, "int", name="theme", value="2" if scenario.endswith("_dark") else "1"
    )
    for key, value in [
        ("master_enabled", "true"),
        ("handle_priority", "true"),
        ("long_enabled", "false"),
        ("hide_on_home", "false"),
        ("vibrate", "false"),
    ]:
        ET.SubElement(fixture, "boolean", name=key, value=value)
    for side in ["L", "R"]:
        total = 3 if scenario == "multi" else 1
        ET.SubElement(fixture, "int", name="handle_count_" + side, value=str(total))
        for i in range(1, total + 1):
            ET.SubElement(
                fixture,
                "int",
                name="h_" + side + str(i) + "_pos",
                value=str([10, 50, 90][i - 1] if total == 3 else 50),
            )
            for direction in ["H", "U", "D"]:
                ET.SubElement(
                    fixture, "string", name="act_" + side + str(i) + "-S-" + direction
                ).text = "back"
    adb(
        "shell",
        "run-as",
        PKG,
        "tee",
        "shared_prefs/onehand.xml",
        data=ET.tostring(fixture, encoding="utf-8", xml_declaration=True),
    )
    adb("logcat", "-c")
    if scenario == "manual" or scenario.startswith("resume_ui"):
        # Keep the recovery process alive with a cached OFF preference, then enable in the main process.
        requested = next(n for n in fixture if n.get("name") == "handle_priority")
        requested.set("value", "false")
        adb(
            "shell",
            "run-as",
            PKG,
            "tee",
            "shared_prefs/onehand.xml",
            data=ET.tostring(fixture, encoding="utf-8", xml_declaration=True),
        )
        sh("am", "start", "-W", "-n", PKG + "/.RecoveryActivity")
        time.sleep(0.5)
        assert_recovery_foreground()
        sh("uiautomator", "dump", "/data/local/tmp/priority-recovery.xml")
        root = ET.fromstring(
            adb("shell", "cat", "/data/local/tmp/priority-recovery.xml")
        )
        node = next(
            n
            for n in root.iter("node")
            if n.get("class") == "android.widget.Button"
            and n.get("text") == "恢复系统手势"
        )
        a, b, c, d = map(int, re.findall(r"\d+", node.get("bounds")))
        button = ((a + c) // 2, (b + d) // 2)
        requested.set("value", "true")
        adb(
            "shell",
            "run-as",
            PKG,
            "tee",
            "shared_prefs/onehand.xml",
            data=ET.tostring(fixture, encoding="utf-8", xml_declaration=True),
        )
    if scenario == "stall":
        sh("settings", "delete", "secure", "enabled_accessibility_services")
        instrument = subprocess.Popen(
            CMD
            + [
                "shell",
                "am",
                "instrument",
                "-w",
                "-e",
                "priorityStall",
                "true",
                TEST + "/" + PKG + ".SmokeInstrumentation",
            ],
            env=ENV,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        wait_log("waiting for priority guard")
        sh("am", "start", "-W", "-n", TEST + "/" + PKG + ".EdgeInputProbeActivity")
    if scenario != "stall":
        sh("am", "start", "-W", "-n", PKG + "/.MainActivity")
    sh(
        "settings",
        "put",
        "secure",
        "enabled_accessibility_services",
        PKG + "/" + PKG + ".EdgeService",
    )
    sh("settings", "put", "secure", "accessibility_enabled", "1")
    time.sleep(1.5)
    if scenario == "stall":
        before = [
            s.strip()
            for s in sh("dumpsys", "window", "displays").splitlines()
            if "mSystemGestureExclusion=" in s
        ]
        check(
            "exclusion_present_before_stall",
            bool(before) and "SkRegion()" not in before[0],
            regions=before,
        )
        wait_log("main-thread-blocked")
        start = time.monotonic()
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            regions = [
                s.strip()
                for s in sh("dumpsys", "window", "displays").splitlines()
                if "mSystemGestureExclusion=" in s
            ]
            paused = (
                subprocess.run(
                    CMD + ["shell", "run-as", PKG, "test", "-f", PAUSE],
                    env=ENV,
                    capture_output=True,
                ).returncode
                == 0
            )
            if regions and "SkRegion()" in regions[0] and paused:
                break
            time.sleep(0.2)
        check(
            "stalled_main_releases_exclusion",
            bool(regions) and "SkRegion()" in regions[0],
            seconds=round(time.monotonic() - start, 2),
            regions=regions,
        )
        check(
            "fault_persists_safety_pause",
            subprocess.run(
                CMD + ["shell", "run-as", PKG, "test", "-f", PAUSE], env=ENV
            ).returncode
            == 0,
        )
        output = instrument.communicate(timeout=8)[0].decode(errors="replace")
        (ROOT / "stall-instrumentation.txt").write_text(output, encoding="utf-8")
        sh("am", "start", "-W", "-n", TEST + "/" + PKG + ".EdgeInputProbeActivity")
        time.sleep(0.5)
        for side in ["L", "R"]:
            gesture(side, "H", 1200)
        raise SystemExit(0)
    sh("am", "start", "-W", "-n", TEST + "/" + PKG + ".EdgeInputProbeActivity")
    deadline = time.monotonic() + 12
    while True:
        regions = [
            s.strip()
            for s in sh("dumpsys", "window", "displays").splitlines()
            if "mSystemGestureExclusion=" in s
        ]
        if regions and "SkRegion()" not in regions[0]:
            break
        if time.monotonic() >= deadline:
            (ROOT / "startup-accessibility.txt").write_text(
                sh("dumpsys", "accessibility"), encoding="utf-8"
            )
            (ROOT / "startup-log.txt").write_text(logs(), encoding="utf-8")
            break
        time.sleep(0.2)
    check(
        "system_grants_nonempty_exclusion",
        bool(regions) and "SkRegion()" not in regions[0],
        regions=regions,
    )
    expected_regions = list(regions)
    if scenario == "sparse_moves":
        for side, x, target in [("L", 0, 240), ("R", 1079, 839)]:
            before = count()
            for action, point in [
                ("DOWN", x),
                ("MOVE", x + (20 if side == "L" else -20)),
                ("UP", target),
            ]:
                sh("input", "motionevent", action, str(point), "1189")
            time.sleep(0.7)
            check(
                "sparse_move_commits_final_up_" + side,
                count() == before + 1,
                previous=before,
                current=count(),
            )
            before = count()
            for action, point in [
                ("DOWN", x),
                ("MOVE", target),
                ("UP", x + (4 if side == "L" else -4)),
            ]:
                sh("input", "motionevent", action, str(point), "1189")
            time.sleep(0.7)
            check(
                "final_up_at_edge_cancels_" + side,
                count() == before,
                previous=before,
                current=count(),
            )
        raise SystemExit(0)
    if scenario in ("guard_die", "screen"):
        if scenario == "guard_die":
            processes = [
                line.split(maxsplit=1)
                for line in sh("ps", "-A", "-o", "PID,ARGS").splitlines()
            ]
            pid = next(pid for pid, name in processes if name == PKG + ":recovery")
            sh("run-as", PKG, "kill", pid)
        else:
            sh("input", "keyevent", "KEYCODE_POWER")
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            regions = [
                s.strip()
                for s in sh("dumpsys", "window", "displays").splitlines()
                if "mSystemGestureExclusion=" in s
            ]
            if regions and "SkRegion()" in regions[0]:
                break
            time.sleep(0.2)
        check(
            scenario + "_releases_exclusion",
            bool(regions) and "SkRegion()" in regions[0],
            regions=regions,
        )
        if scenario == "screen":
            sh("input", "keyevent", "KEYCODE_WAKEUP")
            sh("wm", "dismiss-keyguard")
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                regions = [
                    s.strip()
                    for s in sh("dumpsys", "window", "displays").splitlines()
                    if "mSystemGestureExclusion=" in s
                ]
                if regions == expected_regions:
                    break
                time.sleep(0.2)
            check(
                "screen_resume_rechecks_and_recreates_windows",
                regions == expected_regions,
                regions=regions,
            )
        for side in ["L", "R"]:
            emitted = gesture(side, "H", 1189)
            check(
                scenario + "_input_owner_" + side,
                ("gesture slot=" in emitted) == (scenario == "screen"),
            )
        raise SystemExit(0)
    if scenario == "manual" or scenario.startswith("resume_ui"):
        sh("am", "start", "-W", "-n", PKG + "/.RecoveryActivity")
        time.sleep(0.5)
        assert_recovery_foreground()
        sh("input", "tap", str(button[0]), str(button[1]))
        started = time.monotonic()
        while True:
            regions = [
                s.strip()
                for s in sh("dumpsys", "window", "displays").splitlines()
                if "mSystemGestureExclusion=" in s
            ]
            paused = (
                subprocess.run(
                    CMD + ["shell", "run-as", PKG, "test", "-f", PAUSE],
                    env=ENV,
                    capture_output=True,
                ).returncode
                == 0
            )
            if regions and "SkRegion()" in regions[0] and paused:
                break
            if time.monotonic() - started > 6:
                (ROOT / "manual-recovery-failure.png").write_bytes(
                    adb("exec-out", "screencap", "-p")
                )
                break
            time.sleep(0.2)
        check(
            "manual_recovery_releases_exclusion",
            bool(regions) and "SkRegion()" in regions[0],
            regions=regions,
            seconds=round(time.monotonic() - started, 2),
        )
        check(
            "manual_recovery_persists_pause",
            subprocess.run(
                CMD + ["shell", "run-as", PKG, "test", "-f", PAUSE], env=ENV
            ).returncode
            == 0,
        )
        (ROOT / "manual-recovery.png").write_bytes(adb("exec-out", "screencap", "-p"))
        sh("am", "start", "-W", "-n", TEST + "/" + PKG + ".EdgeInputProbeActivity")
        time.sleep(0.5)
        for side in ["L", "R"]:
            gesture(side, "H", 1200)
        if scenario.startswith("resume_ui"):
            sh("am", "start", "-W", "-n", PKG + "/.MainActivity")
            time.sleep(0.7)
            click_text("设置")
            click_text("系统手势共存")
            (ROOT / (scenario + "-paused.png")).write_bytes(
                adb("exec-out", "screencap", "-p")
            )
            click_text("手柄优先（实验）")
            # UIAutomator briefly suppresses other accessibility services. Wait for
            # its disconnect and the actual exclusion grant, not a fixed delay.
            deadline = time.monotonic() + 12
            while True:
                regions = [
                    s.strip()
                    for s in sh("dumpsys", "window", "displays").splitlines()
                    if "mSystemGestureExclusion=" in s
                ]
                if regions == expected_regions or time.monotonic() >= deadline:
                    break
                time.sleep(0.2)
            check(
                "user_can_reenable_after_pause",
                regions == expected_regions,
                regions=regions,
            )
            (ROOT / (scenario + "-enabled.png")).write_bytes(
                adb("exec-out", "screencap", "-p")
            )
            sh("am", "start", "-W", "-n", TEST + "/" + PKG + ".EdgeInputProbeActivity")
            time.sleep(0.5)
            for side in ["L", "R"]:
                emitted = gesture(side, "H", 1189)
                check(
                    "reenabled_app_receives_" + side,
                    "gesture slot=" + side + "1-S-H action=back ok=true" in emitted,
                )
        raise SystemExit(0)
    centers = {}
    for side in ["L", "R"]:
        heights = []
        for i in range(1, 4 if scenario == "multi" else 2):
            attached = re.findall(
                r"handle attached id=" + side + str(i) + r" (\d+)x(\d+) y=(\d+)", logs()
            )
            check("handle_has_budget_" + side + str(i), bool(attached))
            width, height, y = map(int, attached[-1])
            centers[side] = y + height // 2
            heights.append(height)
            for direction in ["H", "U", "D"]:
                emitted = gesture(side, direction, centers[side])
                check(
                    "app_receives_" + side + str(i) + direction,
                    "gesture slot="
                    + side
                    + str(i)
                    + "-S-"
                    + direction
                    + " action=back ok=true"
                    in emitted,
                )
        check("total_height_limited_" + side, sum(heights) <= 525, heights=heights)
    for side in ["L", "R"]:
        emitted = gesture(side, "H", 100 if scenario == "multi" else 250)
        check("outside_handle_uses_system_" + side, "gesture slot=" not in emitted)
    sh("am", "force-stop", PKG)
    time.sleep(0.8)
    regions = [
        s.strip()
        for s in sh("dumpsys", "window", "displays").splitlines()
        if "mSystemGestureExclusion=" in s
    ]
    check(
        "exclusion_removed_after_force_stop",
        bool(regions) and "SkRegion()" in regions[0],
        regions=regions,
    )
    for side in ["L", "R"]:
        emitted = gesture(side, "H", centers[side])
        check("system_back_restored_" + side, "gesture slot=" not in emitted)
    check(
        "no_system_gesture_setting_changes", all(get(k) == saved[k] for k in KEYS[:2])
    )
finally:
    sh("settings", "delete", "secure", "enabled_accessibility_services")
    time.sleep(0.3)
    if instrument is not None and instrument.poll() is None:
        sh("am", "force-stop", PKG)
        instrument.communicate(timeout=8)
    sh("am", "force-stop", PKG)
    sh("am", "force-stop", TEST)
    adb("shell", "run-as", PKG, "tee", "shared_prefs/onehand.xml", data=prefs)
    sh("run-as", PKG, "rm", "-f", PAUSE, PAUSE + ".bak", PAUSE + ".new")
    if marker is not None:
        adb("shell", "run-as", PKG, "tee", PAUSE, data=marker)
    for key, value in saved.items():
        if value == "null":
            sh("settings", "delete", "secure", key)
        else:
            sh("settings", "put", "secure", key, value)
    check("original_settings_restored", all(get(k) == v for k, v in saved.items()))
    (ROOT / ("exclusion-" + scenario + "-results.json")).write_text(
        json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8"
    )
