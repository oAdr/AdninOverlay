"""Offline injector checks. Only preview windows and private cache roots are used.

No original DLL is executed and no game process is opened. All screenshots,
synthetic logs and cache fixtures stay under the supplied work directory.
"""
import argparse
import ctypes as C
from ctypes import wintypes as W
import hashlib
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import time
import unittest

parser = argparse.ArgumentParser(add_help=False)
parser.add_argument('--exe', type=Path, required=True)
parser.add_argument('--host', type=Path, required=True)
parser.add_argument('--dll', type=Path, required=True)
parser.add_argument('--compat-dll', type=Path)
parser.add_argument('--work', type=Path, required=True)
ARGS, REST = parser.parse_known_args()
ARGS.work = ARGS.work.resolve()
ARGS.work.mkdir(parents=True, exist_ok=True)
USER = C.WinDLL('user32', use_last_error=True)
KERNEL = C.WinDLL('kernel32', use_last_error=True)
GDI = C.WinDLL('gdi32', use_last_error=True)
# Match the native window's DPI awareness so bounds, screenshots and button
# coordinates use the same physical pixels on scaled Windows desktops.
USER.SetProcessDPIAware()
ENUM = C.WINFUNCTYPE(W.BOOL, W.HWND, W.LPARAM)
USER.EnumWindows.argtypes = [ENUM, W.LPARAM]
USER.GetWindowThreadProcessId.argtypes = [W.HWND, C.POINTER(W.DWORD)]
USER.GetWindowTextW.argtypes = [W.HWND, W.LPWSTR, C.c_int]
USER.GetClassNameW.argtypes = [W.HWND, W.LPWSTR, C.c_int]
USER.IsWindowVisible.argtypes = [W.HWND]
USER.GetDlgItem.argtypes = [W.HWND, C.c_int]
USER.GetDlgItem.restype = W.HWND
USER.SendMessageW.argtypes = [W.HWND, W.UINT, W.WPARAM, W.LPARAM]
USER.SendMessageW.restype = C.c_ssize_t
USER.PostMessageW.argtypes = [W.HWND, W.UINT, W.WPARAM, W.LPARAM]
USER.IsWindow.argtypes = [W.HWND]
USER.GetWindowLongPtrW.argtypes = [W.HWND, C.c_int]
USER.GetWindowLongPtrW.restype = C.c_ssize_t
USER.GetClassLongPtrW.argtypes = [W.HWND, C.c_int]
USER.GetClassLongPtrW.restype = C.c_ssize_t
USER.GetForegroundWindow.restype = W.HWND
USER.GetLayeredWindowAttributes.argtypes = [W.HWND, C.POINTER(W.DWORD), C.POINTER(W.BYTE), C.POINTER(W.DWORD)]
KERNEL.OpenProcess.argtypes = [W.DWORD, W.BOOL, W.DWORD]
KERNEL.OpenProcess.restype = W.HANDLE
KERNEL.CloseHandle.argtypes = [W.HANDLE]
KERNEL.GetSystemDirectoryW.argtypes = [W.LPWSTR, W.UINT]
PSAPI = C.WinDLL('psapi', use_last_error=True)
PSAPI.GetModuleFileNameExW.argtypes = [W.HANDLE, W.HMODULE, W.LPWSTR, W.DWORD]


def system_input_indicator(pid, hwnd, title, cls):
    # An unswitched private desktop can expose these OS input-language widgets
    # after BM_CLICK. Require their exact classes, empty titles, nonactivation,
    # and registration by the actual System32 InputSwitch.dll. Keep all other
    # windows, including unexpected dialogs and console/error windows, visible
    # to the one-application-window assertion. Inspect only our owned fixture.
    if cls not in ('UAC_InputIndicatorOverlayWnd', 'UAC Input Indicator') or title:
        return False
    if not USER.GetWindowLongPtrW(hwnd, -20) & 0x08000000:
        return False
    module = USER.GetClassLongPtrW(hwnd, -16)  # GCLP_HMODULE.
    if not module:
        return False
    process = KERNEL.OpenProcess(0x410, False, pid)  # QUERY_INFORMATION | VM_READ.
    if not process:
        return False
    try:
        registered, system = C.create_unicode_buffer(1024), C.create_unicode_buffer(1024)
        if not PSAPI.GetModuleFileNameExW(process, module, registered, 1024):
            return False
        if not KERNEL.GetSystemDirectoryW(system, 1024):
            return False
        return Path(registered.value) == Path(system.value) / 'InputSwitch.dll'
    finally:
        KERNEL.CloseHandle(process)


class GUIThreadInfo(C.Structure):
    _fields_ = [('cbSize', W.DWORD), ('flags', W.DWORD), ('hwndActive', W.HWND),
                ('hwndFocus', W.HWND), ('hwndCapture', W.HWND), ('hwndMenuOwner', W.HWND),
                ('hwndMoveSize', W.HWND), ('hwndCaret', W.HWND), ('rcCaret', W.RECT)]


USER.GetGUIThreadInfo.argtypes = [W.DWORD, C.POINTER(GUIThreadInfo)]


def focused_control(hwnd):
    info = GUIThreadInfo(cbSize=C.sizeof(GUIThreadInfo))
    thread_id = USER.GetWindowThreadProcessId(hwnd, None)
    if not USER.GetGUIThreadInfo(thread_id, C.byref(info)):
        raise AssertionError('Could not inspect native keyboard focus')
    return info.hwndFocus


def window_text(hwnd):
    value = C.create_unicode_buffer(256)
    USER.GetWindowTextW(hwnd, value, len(value))
    return value.value


def alpha(hwnd):
    if not USER.IsWindow(hwnd): return None
    color, opacity, flags = W.DWORD(), W.BYTE(), W.DWORD()
    if not USER.GetLayeredWindowAttributes(hwnd, C.byref(color), C.byref(opacity), C.byref(flags)):
        if not USER.IsWindow(hwnd): return None
        raise AssertionError('Preview is not a layered window with inspectable opacity')
    if not flags.value & 2: raise AssertionError('Preview does not apply its alpha value')
    return opacity.value


def windows(pid):
    found = []
    @ENUM
    def visit(hwnd, _):
        owner = W.DWORD()
        USER.GetWindowThreadProcessId(hwnd, C.byref(owner))
        if owner.value == pid and USER.IsWindowVisible(hwnd):
            title, cls = C.create_unicode_buffer(512), C.create_unicode_buffer(128)
            USER.GetWindowTextW(hwnd, title, len(title))
            USER.GetClassNameW(hwnd, cls, len(cls))
            if not system_input_indicator(pid, hwnd, title.value, cls.value):
                found.append((hwnd, title.value, cls.value))
        return True
    USER.EnumWindows(visit, 0)
    return found


def wait_window(process):
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        found = windows(process.pid)
        if found: return found
        if process.poll() is not None: raise AssertionError('Preview exited before showing a window')
        time.sleep(0.03)
    raise AssertionError('Preview window did not appear')


def capture(hwnd, path):
    USER.GetWindowRect.argtypes = [W.HWND, C.POINTER(W.RECT)]
    USER.GetDC.argtypes = [W.HWND]
    USER.GetDC.restype = W.HDC
    USER.ReleaseDC.argtypes = [W.HWND, W.HDC]
    USER.PrintWindow.argtypes = [W.HWND, W.HDC, W.UINT]
    GDI.CreateCompatibleDC.argtypes = [W.HDC]
    GDI.CreateCompatibleDC.restype = W.HDC
    GDI.CreateDIBSection.argtypes = [W.HDC, C.c_void_p, W.UINT, C.POINTER(C.c_void_p), W.HANDLE, W.DWORD]
    GDI.CreateDIBSection.restype = W.HBITMAP
    GDI.SelectObject.argtypes = [W.HDC, W.HANDLE]
    GDI.SelectObject.restype = W.HANDLE
    GDI.DeleteObject.argtypes = [W.HANDLE]
    GDI.DeleteDC.argtypes = [W.HDC]
    rect = W.RECT()
    if not USER.GetWindowRect(hwnd, C.byref(rect)): raise AssertionError('Cannot read preview bounds')
    width, height = rect.right - rect.left, rect.bottom - rect.top
    info = C.create_string_buffer(struct.pack('<IiiHHIIiiII', 40, width, -height, 1, 32, 0, width * height * 4, 0, 0, 0, 0))
    screen = USER.GetDC(hwnd)
    dc = GDI.CreateCompatibleDC(screen)
    bits = C.c_void_p()
    bitmap = GDI.CreateDIBSection(screen, info, 0, C.byref(bits), None, 0)
    old = GDI.SelectObject(dc, bitmap)
    try:
        if not USER.PrintWindow(hwnd, dc, 2): raise AssertionError('Cannot render preview screenshot')
        pixels = C.string_at(bits, width * height * 4)
        header = struct.pack('<2sIHHI', b'BM', 54 + len(pixels), 0, 0, 54)
        header += struct.pack('<IiiHHIIiiII', 40, width, -height, 1, 32, 0, len(pixels), 0, 0, 0, 0)
        path.write_bytes(header + pixels)
    finally:
        GDI.SelectObject(dc, old)
        GDI.DeleteObject(bitmap)
        GDI.DeleteDC(dc)
        USER.ReleaseDC(hwnd, screen)


def close_surround_pixels(hwnd, close, screenshot):
    """Count non-background pixels around (not inside) the red dot."""
    capture(hwnd, screenshot)
    bounds, button = W.RECT(), W.RECT()
    USER.GetWindowRect(hwnd, C.byref(bounds))
    USER.GetWindowRect(close, C.byref(button))
    data = screenshot.read_bytes()
    width = struct.unpack_from('<i', data, 18)[0]
    left, top = button.left - bounds.left, button.top - bounds.top
    right, bottom = button.right - bounds.left, button.bottom - bounds.top
    cx, cy = (left + right) // 2, (top + bottom) // 2
    radius = (right - left) * 8 // 30
    changed = 0
    for y in range(top, bottom):
        for x in range(left, right):
            if abs(x - cx) <= radius and abs(y - cy) <= radius: continue
            offset = 54 + (y * width + x) * 4
            if data[offset:offset + 3] != bytes((24, 19, 16)): changed += 1
    return changed


class InjectorTests(unittest.TestCase):
    def payload_cases(self):
        cases = [('lunar', ARGS.dll, 101)]
        if ARGS.compat_dll: cases.append(('vanilla', ARGS.compat_dll, 102))
        return cases

    def run_cli(self, *args):
        return subprocess.run([str(ARGS.exe), *args], capture_output=True, timeout=10,
                              text=True, encoding='utf8', errors='replace', creationflags=subprocess.CREATE_NO_WINDOW)

    def preview(self, exe, args, settle=True, locale=None):
        settings_root = ARGS.work / 'language-fixtures' / (locale if locale in ('en','zh_CN','zh_TW') else 'default')
        (settings_root / 'Adnin').mkdir(parents=True, exist_ok=True)
        if locale is not None:
            (settings_root / 'Adnin/language.txt').write_text(locale, encoding='ascii')
        environment = os.environ.copy()
        environment['LOCALAPPDATA'] = str(settings_root)
        process = subprocess.Popen([str(exe), *map(str, args)], stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL, creationflags=subprocess.CREATE_NO_WINDOW, env=environment)
        def cleanup():
            if process.poll() is None:
                process.kill()
                process.wait(timeout=5)
        self.addCleanup(cleanup)
        found = wait_window(process)
        self.assertEqual(len(found), 1)
        hwnd, title, cls = found[0]
        self.assertEqual(cls, 'AdninInjectionResult')
        self.assertIn('preview', title)
        if settle: self.wait_opaque(process, hwnd)
        return process, hwnd

    def test_localized_result_controls_use_only_explicit_language_preference(self):
        for locale, failed, export, close in (
                ('en', 'failed', 'Export log', 'Close'),
                ('zh_CN', '失败', '导出日志', '关闭'),
                ('zh_TW', '失敗', '匯出紀錄', '關閉')):
            with self.subTest(locale=locale):
                process, hwnd = self.preview(ARGS.exe, ['--preview-ui','failed'], locale=locale)
                self.assertIn(failed, window_text(hwnd))
                self.assertEqual(export, window_text(USER.GetDlgItem(hwnd, 1001)))
                self.assertEqual(close, window_text(USER.GetDlgItem(hwnd, 1002)))
                capture(hwnd, ARGS.work / ('preview-language-' + locale + '.bmp'))
                self.close(process, hwnd)
        process, hwnd = self.preview(ARGS.exe, ['--preview-ui','success'], locale='zh_CN')
        self.assertEqual('2 秒后关闭', window_text(USER.GetDlgItem(hwnd, 1003)))
        self.assertEqual(process.wait(timeout=5), 0)

    def wait_opaque(self, process, hwnd):
        deadline = time.monotonic() + 3
        samples = []
        while time.monotonic() < deadline:
            value = alpha(hwnd)
            self.assertIsNotNone(value, 'Preview disappeared during fade-in')
            samples.append(value)
            if value == 255: return samples
            self.assertIsNone(process.poll())
            time.sleep(0.005)
        self.fail('Preview did not finish fading in')

    def assert_fades_out(self, process, hwnd, trigger, maximum_alpha=255):
        started = time.monotonic()
        trigger()
        samples = []
        deadline = started + 3
        while time.monotonic() < deadline and process.poll() is None:
            value = alpha(hwnd)
            if value is not None: samples.append(value)
            time.sleep(0.005)
        self.assertEqual(process.wait(timeout=1), 0)
        self.assertTrue(any(0 < value < maximum_alpha for value in samples), samples)
        self.assertTrue(all(value <= maximum_alpha for value in samples), samples)
        self.assertTrue(all(later <= earlier for earlier, later in zip(samples, samples[1:])), samples)
        self.assertGreaterEqual(time.monotonic() - started, 0.14)
        return samples

    def close(self, process, hwnd):
        USER.PostMessageW(hwnd, 0x10, 0, 0)
        self.assertEqual(process.wait(timeout=8), 0)

    def test_windows_subsystem_and_embedded_resource_matches_dll(self):
        data = ARGS.exe.read_bytes()
        pe = struct.unpack_from('<I', data, 0x3c)[0]
        self.assertEqual(struct.unpack_from('<H', data, pe + 24 + 68)[0], 2)
        KERNEL.LoadLibraryExW.argtypes = [W.LPCWSTR, W.HANDLE, W.DWORD]
        KERNEL.LoadLibraryExW.restype = W.HMODULE
        KERNEL.FindResourceW.argtypes = [W.HMODULE, C.c_void_p, C.c_void_p]
        KERNEL.FindResourceW.restype = W.HRSRC
        KERNEL.LoadResource.argtypes = [W.HMODULE, W.HRSRC]
        KERNEL.LoadResource.restype = W.HGLOBAL
        KERNEL.SizeofResource.argtypes = [W.HMODULE, W.HRSRC]
        KERNEL.SizeofResource.restype = W.DWORD
        KERNEL.LockResource.argtypes = [W.HGLOBAL]
        KERNEL.LockResource.restype = C.c_void_p
        KERNEL.FreeLibrary.argtypes = [W.HMODULE]
        module = KERNEL.LoadLibraryExW(str(ARGS.exe), None, 0x22)  # DATAFILE | IMAGE_RESOURCE; no entry point.
        self.assertTrue(module)
        try:
            for client, dll, resource_id in self.payload_cases():
                with self.subTest(client=client):
                    resource = KERNEL.FindResourceW(module, resource_id, 10)
                    self.assertTrue(resource)
                    size = KERNEL.SizeofResource(module, resource)
                    pointer = KERNEL.LockResource(KERNEL.LoadResource(module, resource))
                    self.assertEqual(C.string_at(pointer, size), dll.read_bytes())
        finally: KERNEL.FreeLibrary(module)

    def test_cli_help_and_usage_preserve_output_and_status(self):
        result = self.run_cli('--help')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('--preview-ui success|failed', result.stdout)
        self.assertIn('--client auto|lunar|badlion|vanilla', result.stdout)
        self.assertIn('Exit codes:', result.stdout)
        for args in (('--pid', '0'), ('--inject', '--dry-run'), ('--preview-ui', 'invalid'),
                     ('--preview-ui', 'success', '--inject'), ('--timeout-ms', '300001'),
                     ('--client', 'unknown'), ('--client',)):
            with self.subTest(args=args):
                result = self.run_cli(*args)
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertTrue(result.stderr.strip())
        result = self.run_cli('--dll', str(ARGS.work / 'missing.dll'), '--pid', '4294967295', '--dry-run')
        self.assertEqual(result.returncode, 4)

    def test_actual_preview_does_not_need_adjacent_dll_or_show_console(self):
        with tempfile.TemporaryDirectory(prefix='single-exe-', dir=ARGS.work) as directory:
            executable = Path(directory) / 'Adnin.exe'
            executable.write_bytes(ARGS.exe.read_bytes())
            self.assertFalse((executable.parent / 'Adnin.dll').exists())
            for status in ('success', 'failed'):
                process, hwnd = self.preview(executable, ['--preview-ui', status])
                try:
                    self.assertEqual(bool(USER.GetDlgItem(hwnd, 1001)), status == 'failed')
                    self.assertEqual(bool(USER.GetWindowLongPtrW(hwnd, -20) & 0x08000000),
                                     status == 'success')  # WS_EX_NOACTIVATE only for passive success.
                    if status == 'success':
                        self.assertNotEqual(USER.GetForegroundWindow(), hwnd,
                                            'Success must not activate over another application')
                    capture(hwnd, ARGS.work / ('preview-' + status + '.bmp'))
                finally:
                    self.close(process, hwnd)

    def test_success_fades_in_holds_two_seconds_and_fades_out(self):
        process, hwnd = self.preview(ARGS.exe, ['--preview-ui', 'success'], settle=False)
        fade_in = self.wait_opaque(process, hwnd)
        self.assertTrue(any(0 < value < 255 for value in fade_in), fade_in)
        self.assertTrue(all(later >= earlier for earlier, later in zip(fade_in, fade_in[1:])), fade_in)
        fully_visible = time.monotonic()
        self.assertNotEqual(USER.GetForegroundWindow(), hwnd, 'Success fade-in must not take focus')
        label = USER.GetDlgItem(hwnd, 1003)
        self.assertTrue(label)
        self.assertEqual(window_text(label), 'Closing in 2s')
        self.assertFalse(USER.GetWindowLongPtrW(label, -16) & 0x10000)  # No tab stop.
        self.assertFalse(USER.GetDlgItem(hwnd, 1001))
        capture(hwnd, ARGS.work / 'preview-countdown-2.bmp')
        labels, fade_out, first_fade = set(), [], None
        deadline = fully_visible + 4
        while time.monotonic() < deadline and process.poll() is None:
            self.assertNotEqual(USER.GetForegroundWindow(), hwnd,
                                'Success hold/fade-out must not take focus')
            value = alpha(hwnd)
            if value is not None:
                labels.add(window_text(label))
                if value < 255:
                    if first_fade is None: first_fade = time.monotonic()
                    fade_out.append(value)
            time.sleep(0.005)
        self.assertEqual(process.wait(timeout=1), 0)
        self.assertIn('Closing in 2s', labels)
        self.assertIn('Closing in 1s', labels)
        self.assertIsNotNone(first_fade)
        # Polling and WM_TIMER have scheduling jitter, but the hold starts after
        # fade-in completes and must not collapse into the animation interval.
        self.assertGreaterEqual(first_fade - fully_visible, 1.90)
        self.assertLess(first_fade - fully_visible, 3.0)
        self.assertTrue(any(0 < value < 255 for value in fade_out), fade_out)
        self.assertTrue(all(later <= earlier for earlier, later in zip(fade_out, fade_out[1:])), fade_out)

    def test_failed_stays_open_and_red_close_button_fades(self):
        process, hwnd = self.preview(ARGS.exe, ['--preview-ui', 'failed'])
        self.assertFalse(USER.GetDlgItem(hwnd, 1003))
        close = USER.GetDlgItem(hwnd, 1002)
        self.assertEqual(window_text(close), 'Close')
        self.assertTrue(USER.GetWindowLongPtrW(close, -16) & 0x10000)  # Native keyboard tab stop.
        time.sleep(2.4)
        self.assertIsNone(process.poll())
        self.assertEqual(alpha(hwnd), 255)
        screenshot = ARGS.work / 'preview-red-close.bmp'
        capture(hwnd, screenshot)
        bounds, button = W.RECT(), W.RECT()
        USER.GetWindowRect(hwnd, C.byref(bounds))
        USER.GetWindowRect(close, C.byref(button))
        cx = (button.left + button.right) // 2 - bounds.left
        cy = (button.top + button.bottom) // 2 - bounds.top
        self.assertGreater(cx, (bounds.right - bounds.left) * 3 // 4)
        data = screenshot.read_bytes()
        width = struct.unpack_from('<i', data, 18)[0]
        blue, green, red = data[54 + (cy * width + cx) * 4:57 + (cy * width + cx) * 4]
        self.assertGreater(red, 220)
        self.assertLess(green, 130)
        self.assertLess(blue, 130)
        self.assert_fades_out(process, hwnd, lambda: USER.SendMessageW(close, 0xf5, 0, 0))

    def test_escape_alt_f4_and_keyboard_button_all_fade_out(self):
        for action in ('escape', 'alt-f4', 'space'):
            with self.subTest(action=action):
                process, hwnd = self.preview(ARGS.exe, ['--preview-ui', 'failed'])
                if action == 'escape':
                    trigger = lambda: USER.PostMessageW(hwnd, 0x100, 0x1b, 1)
                elif action == 'alt-f4':
                    trigger = lambda: USER.PostMessageW(hwnd, 0x104, 0x73, (1 << 29) | 1)
                else:
                    close = USER.GetDlgItem(hwnd, 1002)
                    def trigger():
                        USER.PostMessageW(close, 0x100, 0x20, 1)
                        USER.PostMessageW(close, 0x101, 0x20, (1 << 31) | (1 << 30) | 1)
                self.assert_fades_out(process, hwnd, trigger)

    def test_red_close_uses_keyboard_ring_without_mouse_focus_rectangle(self):
        process, hwnd = self.preview(ARGS.exe, ['--preview-ui', 'failed'])
        close = USER.GetDlgItem(hwnd, 1002)
        # Exercise real dialog navigation rather than setting an internal flag
        # or forcing WM_DRAWITEM. The first focused control may depend on Windows.
        for _ in range(3):
            USER.PostMessageW(hwnd, 0x100, 0x09, 1)  # Tab.
            deadline = time.monotonic() + 0.5
            while time.monotonic() < deadline:
                if focused_control(hwnd) == close: break
                time.sleep(0.005)
            if focused_control(hwnd) == close: break
        self.assertEqual(focused_control(hwnd), close)
        self.assertEqual(window_text(close), 'Close')
        self.assertGreater(close_surround_pixels(hwnd, close, ARGS.work / 'preview-keyboard-close-focus.bmp'), 10)
        button = W.RECT()
        USER.GetWindowRect(close, C.byref(button))
        point = ((button.bottom - button.top) // 2 << 16) | ((button.right - button.left) // 2)
        USER.PostMessageW(close, 0x201, 1, point)  # Hold left mouse button down.
        deadline = time.monotonic() + 1
        while time.monotonic() < deadline and not USER.SendMessageW(close, 0xf2, 0, 0) & 4:
            time.sleep(0.005)
        self.assertTrue(USER.SendMessageW(close, 0xf2, 0, 0) & 4)  # Native BST_PUSHED.
        self.assertEqual(focused_control(hwnd), close)
        self.assertEqual(close_surround_pixels(hwnd, close, ARGS.work / 'preview-mouse-close-focus.bmp'), 0)
        self.assert_fades_out(process, hwnd, lambda: USER.PostMessageW(close, 0x202, 0, point))

    def test_close_during_fade_in_uses_current_alpha_and_does_not_restart(self):
        process, hwnd = self.preview(ARGS.exe, ['--preview-ui', 'failed'], settle=False)
        deadline = time.monotonic() + 1
        while alpha(hwnd) < 48 and time.monotonic() < deadline: time.sleep(0.005)
        initial = alpha(hwnd)
        self.assertGreater(initial, 0)
        self.assertLess(initial, 230)
        started = time.monotonic()
        USER.SendMessageW(hwnd, 0x10, 0, 0)
        samples, repeated = [], False
        while process.poll() is None and time.monotonic() - started < 2:
            value = alpha(hwnd)
            if value is not None: samples.append(value)
            if not repeated and time.monotonic() - started >= 0.12:
                USER.SendMessageW(hwnd, 0x10, 0, 0)
                repeated = True
            time.sleep(0.005)
        self.assertEqual(process.wait(timeout=1), 0)
        self.assertTrue(repeated)
        self.assertTrue(any(0 < value < initial for value in samples), samples)
        self.assertTrue(all(value <= initial for value in samples), samples)
        self.assertLess(time.monotonic() - started, 0.31)

    def test_failure_export_stays_in_the_same_window(self):
        with tempfile.TemporaryDirectory(prefix='export-', dir=ARGS.work) as directory:
            process, hwnd = self.preview(ARGS.host, ['--ui', 'failed', directory])
            USER.SendMessageW(USER.GetDlgItem(hwnd, 1001), 0xf5, 0, 0)  # BM_CLICK
            files = list(Path(directory).glob('Adnin-injection-failed-*.log'))
            self.assertEqual(len(files), 1)
            text = files[0].read_text(encoding='utf8')
            self.assertIn('Preview: yes (no injection performed)', text)
            self.assertIn('Result: failed', text)
            self.assertIn('Exit code: 8', text)
            self.assertNotIn(directory, text)
            self.assertNotIn('http://', text)
            self.assertNotIn('https://', text)
            self.assertEqual(len(windows(process.pid)), 1)
            capture(hwnd, ARGS.work / 'preview-failed-exported.bmp')
            self.close(process, hwnd)

    def test_export_failure_has_no_second_window(self):
        process, hwnd = self.preview(ARGS.host, ['--ui', 'failed', ARGS.work / 'missing-parent' / 'export'])
        USER.SendMessageW(USER.GetDlgItem(hwnd, 1001), 0xf5, 0, 0)
        self.assertEqual(len(windows(process.pid)), 1)
        capture(hwnd, ARGS.work / 'preview-export-error.bmp')
        self.close(process, hwnd)

    def payload(self, root, *args):
        return subprocess.run([str(ARGS.host), '--payload', str(root), *args], capture_output=True,
                              text=True, timeout=10, creationflags=subprocess.CREATE_NO_WINDOW)

    def test_payload_cache_is_exact_and_reused(self):
        with tempfile.TemporaryDirectory(prefix='cache-', dir=ARGS.work) as root:
            original = ARGS.dll.read_bytes()
            path = Path(root) / 'Adnin' / 'payload' / hashlib.sha256(original).hexdigest() / 'Adnin.dll'
            self.assertEqual(self.payload(root).returncode, 0)
            self.assertEqual(path.read_bytes(), original)
            stamp = path.stat().st_mtime_ns
            self.assertEqual(self.payload(root).returncode, 0)
            self.assertEqual(path.stat().st_mtime_ns, stamp)
            self.assertFalse(list(path.parent.glob('*.tmp')))

    def test_corrupt_cache_is_rejected_without_overwrite(self):
        with tempfile.TemporaryDirectory(prefix='corrupt-', dir=ARGS.work) as root:
            digest = hashlib.sha256(ARGS.dll.read_bytes()).hexdigest()
            folder = Path(root) / 'Adnin' / 'payload' / digest
            folder.mkdir(parents=True)
            cache = folder / 'Adnin.dll'
            cache.write_bytes(b'damaged cache fixture')
            result = self.payload(root)
            self.assertEqual(result.returncode, 4)
            self.assertEqual(cache.read_bytes(), b'damaged cache fixture')
            self.assertIn('damaged', result.stderr)

    def test_read_lease_prevents_cache_replacement(self):
        for client, _, _ in self.payload_cases():
            with self.subTest(client=client), tempfile.TemporaryDirectory(prefix='lease-', dir=ARGS.work) as root:
                process = subprocess.Popen([str(ARGS.host), '--payload', root, '--hold', '--client', client], stdout=subprocess.PIPE,
                                           stderr=subprocess.PIPE, text=True, creationflags=subprocess.CREATE_NO_WINDOW)
                try:
                    self.assertEqual(process.stdout.readline().strip(), 'payload verified')
                    path = next((Path(root) / 'Adnin' / 'payload').glob('*/Adnin.dll'))
                    with self.assertRaises(PermissionError): path.write_bytes(b'replacement')
                    self.assertEqual(self.payload(root, '--client', client).returncode, 0)
                finally:
                    process.kill()
                    process.communicate(timeout=5)

    def test_multiple_runtime_cache_and_development_identity(self):
        if not ARGS.compat_dll: self.skipTest('Compatibility payload not supplied')
        with tempfile.TemporaryDirectory(prefix='multi-runtime-', dir=ARGS.work) as root:
            paths = []
            for client, dll, _ in self.payload_cases():
                with self.subTest(client=client):
                    original = dll.read_bytes()
                    path = Path(root) / 'Adnin' / 'payload' / hashlib.sha256(original).hexdigest() / 'Adnin.dll'
                    paths.append(path)
                    self.assertEqual(self.payload(root, '--client', client).returncode, 0)
                    self.assertEqual(path.read_bytes(), original)
                    stamp = path.stat().st_mtime_ns
                    self.assertEqual(self.payload(root, '--client', client).returncode, 0)
                    self.assertEqual(path.stat().st_mtime_ns, stamp)
                    verified = subprocess.run([str(ARGS.host), '--verify-payload', str(dll), '--client', client],
                                              capture_output=True, timeout=10, creationflags=subprocess.CREATE_NO_WINDOW)
                    self.assertEqual(verified.returncode, 0)
                    other = 'vanilla' if client == 'lunar' else 'lunar'
                    mismatched = subprocess.run([str(ARGS.host), '--verify-payload', str(dll), '--client', other],
                                                capture_output=True, timeout=10, creationflags=subprocess.CREATE_NO_WINDOW)
                    self.assertEqual(mismatched.returncode, 4, 'Wrong runtime must not pass the development override')
            self.assertNotEqual(paths[0], paths[1])
            self.assertEqual(paths[0].read_bytes(), ARGS.dll.read_bytes())
            paths[1].write_bytes(b'compatibility cache damage fixture')
            self.assertEqual(self.payload(root, '--client', 'vanilla').returncode, 4)
            self.assertEqual(paths[1].read_bytes(), b'compatibility cache damage fixture')
            self.assertEqual(self.payload(root, '--client', 'lunar').returncode, 0)
            self.assertFalse(list(Path(root).rglob('*.tmp')))

    def test_reparse_cache_directory_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix='junction-', dir=ARGS.work) as root:
            parent = Path(root)
            target = parent / 'outside'
            target.mkdir()
            junction = parent / 'Adnin'
            result = subprocess.run(['cmd.exe', '/c', 'mklink', '/J', str(junction), str(target)],
                                    capture_output=True, creationflags=subprocess.CREATE_NO_WINDOW)
            if result.returncode: self.skipTest('Junction creation unavailable')
            try:
                for client, _, _ in self.payload_cases():
                    self.assertEqual(self.payload(parent, '--client', client).returncode, 4)
                self.assertFalse(list(target.iterdir()))
            finally: os.rmdir(junction)

    def target_lease(self, policy, client):
        with tempfile.TemporaryDirectory(prefix='target-lease-', dir=ARGS.work) as root:
            target = subprocess.Popen([str(ARGS.host), '--wait'], stdout=subprocess.PIPE,
                                      stderr=subprocess.PIPE, text=True, creationflags=subprocess.CREATE_NO_WINDOW)
            try:
                self.assertEqual(target.stdout.readline().strip(), 'waiting')
                result = subprocess.run([str(ARGS.host), '--target-lease', root, str(target.pid), policy, '--client', client],
                                        capture_output=True, timeout=10, creationflags=subprocess.CREATE_NO_WINDOW)
                self.assertEqual(result.returncode, 0)
                # The source process is now gone. The target is a test host,
                # and no remote thread or DLL load was requested anywhere.
                self.assertIsNone(target.poll())
                path = next((Path(root) / 'Adnin' / 'payload').glob('*/Adnin.dll'))
                if policy == 'retain':
                    with self.assertRaises(PermissionError): path.write_bytes(b'replacement')
                else:
                    path.write_bytes(b'replacement after normal close')
                target.kill()
                target.communicate(timeout=5)
                path.write_bytes(b'replacement after target exit')
            finally:
                if target.poll() is None:
                    target.kill()
                    target.communicate(timeout=5)

    def test_target_lease_survives_source_exit_until_target_exits(self):
        for client, _, _ in self.payload_cases():
            with self.subTest(client=client): self.target_lease('retain', client)

    def test_target_lease_normal_close_releases_cache(self):
        for client, _, _ in self.payload_cases():
            with self.subTest(client=client): self.target_lease('close', client)


if __name__ == '__main__': unittest.main(argv=[sys.argv[0], *REST])
