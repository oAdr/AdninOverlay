#include "result-ui.h"
#include <dwmapi.h>
#include <shlobj.h>
#include <algorithm>
#include <cwchar>
#include <iomanip>
#include <sstream>
#include <fstream>

namespace adnin {
namespace {
constexpr COLORREF background = RGB(16, 19, 24);
constexpr COLORREF border = RGB(43, 49, 58);
constexpr COLORREF foreground = RGB(233, 238, 244);
constexpr COLORREF secondary = RGB(124, 135, 151);
constexpr UINT_PTR animation_timer = 1;
constexpr UINT animation_tick_ms = 16;
constexpr ULONGLONG fade_duration_ms = 200;
constexpr ULONGLONG success_hold_ms = 2000;
enum class Phase { appearing, visible, closing };

// Only a bounded public language code is shared with the in-game settings.
int configured_language() {
  wchar_t directory[32768]{};
  const DWORD size = GetEnvironmentVariableW(L"LOCALAPPDATA", directory, 32768);
  if (!size || size >= 32768) return 0;
  try {
    const auto path = std::filesystem::path(directory) / L"Adnin" / L"language.txt";
    if (!std::filesystem::is_regular_file(path) || std::filesystem::file_size(path) > 16) return 0;
    std::ifstream file(path, std::ios::binary);
    std::string value; std::getline(file, value);
    return value == "zh_CN" ? 1 : value == "zh_TW" ? 2 : 0;
  } catch (...) { return 0; }
}

const wchar_t* translated(const wchar_t* en, const wchar_t* cn, const wchar_t* tw) {
  static const int language = configured_language();
  return language == 1 ? cn : language == 2 ? tw : en;
}

struct WindowState {
  const ResultReport& report;
  std::filesystem::path export_directory;
  HFONT status_font = nullptr;
  HFONT button_font = nullptr;
  HFONT small_font = nullptr;
  int scale = 96;
  std::wstring feedback{};
  bool feedback_error = false;
  HWND countdown = nullptr;
  Phase phase = Phase::appearing;
  ULONGLONG phase_started = 0;
  BYTE alpha = 0;
  BYTE closing_alpha = 0;
  int countdown_seconds = 0;
  bool layered = true;
  bool keyboard_focus = false;
  int px(int value) const { return MulDiv(value, scale, 96); }
};

int CALLBACK found_font(const LOGFONTW*, const TEXTMETRICW*, DWORD, LPARAM found) {
  *reinterpret_cast<bool*>(found) = true;
  return 0;
}

const wchar_t* font_face(HDC screen, const wchar_t* preferred) {
  if (screen) {
    LOGFONTW query{};
    query.lfCharSet = DEFAULT_CHARSET;
    std::wcsncpy(query.lfFaceName, preferred, LF_FACESIZE - 1);
    bool found = false;
    EnumFontFamiliesExW(screen, &query, found_font, reinterpret_cast<LPARAM>(&found), 0);
    if (found) return preferred;
  }
  return L"Segoe UI";
}

HFONT ui_font(const WindowState& state, int size, int weight, const wchar_t* face) {
  HFONT font = CreateFontW(-state.px(size), 0, 0, 0, weight, FALSE, FALSE, FALSE,
                          DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                          CLEARTYPE_QUALITY, DEFAULT_PITCH, face);
  if (!font)
    font = CreateFontW(-state.px(size), 0, 0, 0, weight, FALSE, FALSE, FALSE,
                        DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                        CLEARTYPE_QUALITY, DEFAULT_PITCH, L"Segoe UI");
  return font;
}

void focus_cues(WindowState& state, HWND window, bool keyboard) {
  if (state.keyboard_focus == keyboard) return;
  state.keyboard_focus = keyboard;
  SendMessageW(window, WM_CHANGEUISTATE,
                MAKEWPARAM(keyboard ? UIS_CLEAR : UIS_SET, UISF_HIDEFOCUS), 0);
  if (const HWND close = GetDlgItem(window, close_button_id)) InvalidateRect(close, nullptr, FALSE);
}

void opacity(WindowState& state, HWND window, BYTE value) {
  state.alpha = value;
  if (state.layered && !SetLayeredWindowAttributes(window, 0, value, LWA_ALPHA)) {
    // A failed animation setup must never leave an invisible, unclosable window.
    SetWindowLongPtrW(window, GWL_EXSTYLE, GetWindowLongPtrW(window, GWL_EXSTYLE) & ~WS_EX_LAYERED);
    state.layered = false;
    state.alpha = 255;
  }
}

void countdown(WindowState& state, int seconds) {
  if (!state.countdown || state.countdown_seconds == seconds) return;
  state.countdown_seconds = seconds;
  const std::wstring label = seconds > 0 ? std::wstring(translated(L"Closing in ",L"",L""))
      + std::to_wstring(seconds) + translated(L"s",L" 秒后关闭",L" 秒後關閉") : L"";
  SetWindowTextW(state.countdown, label.c_str());
}

void begin_close(WindowState& state, HWND window) {
  if (state.phase == Phase::closing) return;
  state.phase = Phase::closing;
  state.phase_started = GetTickCount64();
  state.closing_alpha = state.alpha;
  countdown(state, 0);
  EnableWindow(GetDlgItem(window, close_button_id), FALSE);
  if (const HWND button = GetDlgItem(window, export_button_id)) EnableWindow(button, FALSE);
  if (!state.layered || !state.alpha || !SetTimer(window, animation_timer, animation_tick_ms, nullptr))
    DestroyWindow(window);
}

void animate(WindowState& state, HWND window) {
  const ULONGLONG now = GetTickCount64();
  const ULONGLONG elapsed = now - state.phase_started;
  if (state.phase == Phase::appearing) {
    const ULONGLONG progress = std::min(elapsed, fade_duration_ms);
    if (progress < fade_duration_ms)
      opacity(state, window, static_cast<BYTE>(progress * 255 / fade_duration_ms));
    if (progress == fade_duration_ms || !state.layered) {
      state.phase = Phase::visible;
      state.phase_started = now;
      if (state.report.status == 0) countdown(state, 2);
      else KillTimer(window, animation_timer);
      // Publish the fully visible state after its countdown label is ready.
      opacity(state, window, 255);
    }
  } else if (state.phase == Phase::visible) {
    if (state.report.status == 0) {
      if (elapsed >= success_hold_ms) begin_close(state, window);
      else countdown(state, static_cast<int>((success_hold_ms - elapsed + 999) / 1000));
    }
  } else {
    const ULONGLONG remaining = fade_duration_ms - std::min(elapsed, fade_duration_ms);
    opacity(state, window, static_cast<BYTE>(remaining * state.closing_alpha / fade_duration_ms));
    if (!remaining || !state.layered) DestroyWindow(window);
  }
}

std::string stamp(const SYSTEMTIME& value) {
  std::ostringstream text;
  text << std::setfill('0') << std::setw(4) << value.wYear << '-'
       << std::setw(2) << value.wMonth << '-' << std::setw(2) << value.wDay << 'T'
       << std::setw(2) << value.wHour << ':' << std::setw(2) << value.wMinute << ':'
       << std::setw(2) << value.wSecond << 'Z';
  return text.str();
}

bool export_log(WindowState& state) {
  std::filesystem::path directory = state.export_directory;
  if (directory.empty()) {
    PWSTR desktop = nullptr;
    const HRESULT result = SHGetKnownFolderPath(FOLDERID_Desktop, KF_FLAG_DEFAULT, nullptr, &desktop);
    if (FAILED(result) || !desktop) return false;
    directory = desktop;
    CoTaskMemFree(desktop);
  }
  SYSTEMTIME now{};
  GetSystemTime(&now);
  std::wostringstream name;
  name << L"Adnin-injection-failed-" << std::setfill(L'0') << std::setw(4) << now.wYear
       << std::setw(2) << now.wMonth << std::setw(2) << now.wDay << L'-'
       << std::setw(2) << now.wHour << std::setw(2) << now.wMinute << std::setw(2) << now.wSecond
       << L'-' << GetCurrentProcessId();
  const std::string contents = diagnostic_text(state.report);
  for (unsigned sequence = 0; sequence < 32; ++sequence) {
    const auto path = directory / (name.str() + (sequence ? L"-" + std::to_wstring(sequence) : L"") + L".log");
    HANDLE file = CreateFileW(path.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) {
      if (GetLastError() == ERROR_FILE_EXISTS || GetLastError() == ERROR_ALREADY_EXISTS) continue;
      return false;
    }
    DWORD written = 0;
    const bool ok = WriteFile(file, contents.data(), static_cast<DWORD>(contents.size()), &written, nullptr)
                    && written == contents.size() && FlushFileBuffers(file);
    CloseHandle(file);
    if (!ok) DeleteFileW(path.c_str());
    return ok;
  }
  return false;
}

void text(HDC dc, HFONT font, COLORREF color, const wchar_t* value, RECT box, UINT flags) {
  HGDIOBJ old = SelectObject(dc, font);
  SetBkMode(dc, TRANSPARENT);
  SetTextColor(dc, color);
  DrawTextW(dc, value, -1, &box, flags);
  SelectObject(dc, old);
}

void paint(WindowState& state, HWND window, HDC dc) {
  RECT client{};
  GetClientRect(window, &client);
  HBRUSH fill = CreateSolidBrush(background);
  FillRect(dc, &client, fill);
  DeleteObject(fill);
  HPEN outline = CreatePen(PS_SOLID, state.px(1), border);
  HGDIOBJ old_pen = SelectObject(dc, outline);
  HGDIOBJ old_brush = SelectObject(dc, GetStockObject(NULL_BRUSH));
  RoundRect(dc, 0, 0, client.right, client.bottom, state.px(20), state.px(20));
  SelectObject(dc, old_pen);
  SelectObject(dc, old_brush);
  DeleteObject(outline);

  const bool success = state.report.status == 0;
  const int cx = client.right / 2;
  const int cy = state.px(success ? 97 : 82);
  const int radius = state.px(25);
  const COLORREF accent = success ? RGB(82, 214, 149) : RGB(239, 120, 127);
  HBRUSH disk = CreateSolidBrush(success ? RGB(24, 43, 37) : RGB(46, 29, 34));
  HPEN ring = CreatePen(PS_SOLID, state.px(1), success ? RGB(47, 86, 69) : RGB(88, 46, 54));
  old_brush = SelectObject(dc, disk);
  old_pen = SelectObject(dc, ring);
  Ellipse(dc, cx - radius, cy - radius, cx + radius, cy + radius);
  SelectObject(dc, old_brush);
  SelectObject(dc, old_pen);
  DeleteObject(disk);
  DeleteObject(ring);
  LOGBRUSH stroke{BS_SOLID, accent, 0};
  HPEN symbol = ExtCreatePen(PS_GEOMETRIC | PS_SOLID | PS_ENDCAP_ROUND | PS_JOIN_ROUND,
                            static_cast<DWORD>(state.px(3)), &stroke, 0, nullptr);
  old_pen = SelectObject(dc, symbol);
  if (success) {
    POINT points[]{{cx - state.px(10), cy}, {cx - state.px(3), cy + state.px(7)},
                   {cx + state.px(11), cy - state.px(8)}};
    Polyline(dc, points, 3);
  } else {
    MoveToEx(dc, cx - state.px(7), cy - state.px(7), nullptr);
    LineTo(dc, cx + state.px(7), cy + state.px(7));
    MoveToEx(dc, cx + state.px(7), cy - state.px(7), nullptr);
    LineTo(dc, cx - state.px(7), cy + state.px(7));
  }
  SelectObject(dc, old_pen);
  DeleteObject(symbol);
  RECT label{0, state.px(success ? 137 : 122), client.right, state.px(success ? 193 : 178)};
  text(dc, state.status_font, success ? foreground : accent,
       success ? translated(L"success",L"成功",L"成功") : translated(L"failed",L"失败",L"失敗"), label,
       DT_CENTER | DT_SINGLELINE | DT_VCENTER | DT_NOPREFIX);
  if (state.report.preview) {
    RECT preview{state.px(20), state.px(15), state.px(150), state.px(35)};
    text(dc, state.small_font, secondary, L"preview", preview, DT_LEFT | DT_SINGLELINE | DT_NOPREFIX);
  }
  if (!state.feedback.empty()) {
    RECT feedback{state.px(20), state.px(235), client.right - state.px(20), state.px(266)};
    text(dc, state.small_font, state.feedback_error ? RGB(239, 120, 127) : secondary, state.feedback.c_str(), feedback,
         DT_CENTER | DT_SINGLELINE | DT_VCENTER | DT_END_ELLIPSIS | DT_NOPREFIX);
  }
}

void draw_button(WindowState& state, const DRAWITEMSTRUCT& item) {
  const bool close = item.CtlID == close_button_id;
  const bool pressed = (item.itemState & ODS_SELECTED) != 0;
  HBRUSH fill = CreateSolidBrush(close ? background : pressed ? RGB(48, 55, 67) : RGB(34, 40, 50));
  FillRect(item.hDC, &item.rcItem, fill);
  DeleteObject(fill);
  if (close) {
    HBRUSH dot = CreateSolidBrush(pressed ? RGB(221, 62, 54) : RGB(255, 95, 86));
    HPEN pen = CreatePen(PS_SOLID, state.px(1), pressed ? RGB(183, 50, 43) : RGB(224, 73, 65));
    HGDIOBJ old_pen = SelectObject(item.hDC, pen);
    HGDIOBJ old_brush = SelectObject(item.hDC, dot);
    const int cx = (item.rcItem.left + item.rcItem.right) / 2;
    const int cy = (item.rcItem.top + item.rcItem.bottom) / 2;
    const int radius = state.px(6);
    Ellipse(item.hDC, cx - radius, cy - radius, cx + radius + 1, cy + radius + 1);
    SelectObject(item.hDC, old_brush);
    SelectObject(item.hDC, old_pen);
    DeleteObject(dot);
    DeleteObject(pen);
  } else {
    HPEN pen = CreatePen(PS_SOLID, state.px(1), border);
    HGDIOBJ old_pen = SelectObject(item.hDC, pen);
    HGDIOBJ old_brush = SelectObject(item.hDC, GetStockObject(NULL_BRUSH));
    RoundRect(item.hDC, item.rcItem.left, item.rcItem.top, item.rcItem.right, item.rcItem.bottom,
              state.px(8), state.px(8));
    SelectObject(item.hDC, old_brush);
    SelectObject(item.hDC, old_pen);
    DeleteObject(pen);
    text(item.hDC, state.button_font, foreground, translated(L"Export log",L"导出日志",L"匯出紀錄"), item.rcItem,
         DT_CENTER | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
  }
  if (close && state.keyboard_focus && (item.itemState & (ODS_FOCUS | ODS_NOFOCUSRECT)) == ODS_FOCUS) {
    // A circular keyboard cue follows the control's shape. A mouse press must
    // never flash the stock white rectangle around this small red button.
    const int cx = (item.rcItem.left + item.rcItem.right) / 2;
    const int cy = (item.rcItem.top + item.rcItem.bottom) / 2;
    const int radius = state.px(10);
    HPEN cue = CreatePen(PS_SOLID, state.px(1), RGB(139, 161, 181));
    HGDIOBJ old_pen = SelectObject(item.hDC, cue);
    HGDIOBJ old_brush = SelectObject(item.hDC, GetStockObject(NULL_BRUSH));
    Ellipse(item.hDC, cx - radius, cy - radius, cx + radius + 1, cy + radius + 1);
    SelectObject(item.hDC, old_brush);
    SelectObject(item.hDC, old_pen);
    DeleteObject(cue);
  } else if (!close && (item.itemState & ODS_FOCUS) != 0) {
    RECT focus = item.rcItem;
    InflateRect(&focus, -state.px(4), -state.px(4));
    DrawFocusRect(item.hDC, &focus);
  }
}

LRESULT CALLBACK result_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
  auto* state = reinterpret_cast<WindowState*>(GetWindowLongPtrW(window, GWLP_USERDATA));
  if (message == WM_NCCREATE) {
    state = static_cast<WindowState*>(reinterpret_cast<CREATESTRUCTW*>(lparam)->lpCreateParams);
    SetWindowLongPtrW(window, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(state));
  }
  if (!state) return DefWindowProcW(window, message, wparam, lparam);
  switch (message) {
    case WM_PAINT: {
      PAINTSTRUCT paint_state{};
      HDC dc = BeginPaint(window, &paint_state);
      paint(*state, window, dc);
      EndPaint(window, &paint_state);
      return 0;
    }
    case WM_PRINTCLIENT:
      paint(*state, window, reinterpret_cast<HDC>(wparam));
      return 0;
    case WM_ERASEBKGND: return 1;
    case WM_DRAWITEM:
      draw_button(*state, *reinterpret_cast<DRAWITEMSTRUCT*>(lparam));
      return TRUE;
    case WM_CTLCOLORSTATIC: {
      const HDC dc = reinterpret_cast<HDC>(wparam);
      SetTextColor(dc, secondary);
      SetBkColor(dc, background);
      SetDCBrushColor(dc, background);
      return reinterpret_cast<LRESULT>(GetStockObject(DC_BRUSH));
    }
    case WM_TIMER:
      if (wparam == animation_timer) animate(*state, window);
      return 0;
    case WM_NCHITTEST: {
      POINT point{static_cast<short>(LOWORD(lparam)), static_cast<short>(HIWORD(lparam))};
      ScreenToClient(window, &point);
      RECT area{};
      GetClientRect(window, &area);
      if (point.y < state->px(42) && point.x < area.right - state->px(48)) return HTCAPTION;
      return HTCLIENT;
    }
    case WM_COMMAND:
      if (state->phase == Phase::closing || HIWORD(wparam) != BN_CLICKED) return 0;
      if (LOWORD(wparam) == close_button_id) {
        begin_close(*state, window);
      } else if (LOWORD(wparam) == export_button_id && state->report.status != 0) {
        bool ok = false;
        try { ok = export_log(*state); } catch (...) { ok = false; }
        state->feedback = ok ? (state->export_directory.empty()
                             ? translated(L"Log saved to Desktop",L"日志已保存到桌面",L"紀錄已儲存至桌面")
                             : translated(L"Log exported",L"日志已导出",L"紀錄已匯出"))
                             : translated(L"Export failed. Try again.",L"导出失败，请重试。",L"匯出失敗，請重試。");
        state->feedback_error = !ok;
        InvalidateRect(window, nullptr, FALSE);
      }
      return 0;
    case WM_SYSCOMMAND:
      if ((wparam & 0xfff0) == SC_CLOSE) { begin_close(*state, window); return 0; }
      return DefWindowProcW(window, message, wparam, lparam);
    case WM_CLOSE: begin_close(*state, window); return 0;
    case WM_DESTROY:
      KillTimer(window, animation_timer);
      PostQuitMessage(0);
      return 0;
    default: return DefWindowProcW(window, message, wparam, lparam);
  }
}
}  // namespace

std::string diagnostic_text(const ResultReport& report) {
  std::ostringstream log;
  log << "Adnin injector diagnostic\nScope: this attempt only\n"
      << "Started UTC: " << stamp(report.started_utc) << '\n'
      << "Preview: " << (report.preview ? "yes (no injection performed)" : "no") << '\n'
      << "Result: " << (report.status == 0 ? "success" : "failed") << '\n'
      << "Exit code: " << report.status << "\nWin32 error: " << report.system_error << '\n'
      << "Elapsed milliseconds: " << report.elapsed_ms << '\n';
  if (report.target_pid) log << "Target PID: " << *report.target_pid << '\n';
  if (report.timeout_ms) log << "Timeout milliseconds: " << report.timeout_ms << '\n';
  if (!report.selected_client.empty()) log << "Client: " << report.selected_client << '\n';
  if (!report.runtime_profile.empty()) log << "Runtime profile: " << report.runtime_profile << '\n';
  if (!report.selected_client.empty()) log << "Title version: "
      << (report.title_version_confirmed ? "1.8.9" : "unspecified; runtime checks required") << '\n';
  if (!report.failure.empty()) log << "Diagnostic: " << report.failure << '\n';
  if (report.runtime_checked)
    log << "Runtime flags: classes=" << report.runtime_classes << " pump="
        << (report.runtime_pump_required ? (report.runtime_pump ? "1" : "0") : "not required")
        << " hooks=" << report.runtime_hooks << " heartbeat=" << report.runtime_heartbeat << '\n';
  log << "Steps:\n";
  for (const auto& event : report.events) log << "- " << event << '\n';
  log << "No API keys, URLs, configuration, chat, process command lines or file paths were collected.\n";
  return log.str();
}

bool show_result_window(const ResultReport& report, const std::filesystem::path& export_directory) {
  SetProcessDPIAware();
  HINSTANCE instance = GetModuleHandleW(nullptr);
  WNDCLASSEXW cls{};
  cls.cbSize = sizeof(cls);
  cls.lpfnWndProc = result_proc;
  cls.hInstance = instance;
  cls.hCursor = LoadCursorW(nullptr, IDC_ARROW);
  cls.hIcon = static_cast<HICON>(LoadImageW(instance, MAKEINTRESOURCEW(201), IMAGE_ICON,
                                           GetSystemMetrics(SM_CXICON), GetSystemMetrics(SM_CYICON), LR_SHARED));
  cls.hIconSm = static_cast<HICON>(LoadImageW(instance, MAKEINTRESOURCEW(201), IMAGE_ICON,
                                             GetSystemMetrics(SM_CXSMICON), GetSystemMetrics(SM_CYSMICON), LR_SHARED));
  cls.lpszClassName = result_window_class;
  if (!RegisterClassExW(&cls) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) return false;
  WindowState state{report, export_directory};
  HDC screen = GetDC(nullptr);
  if (screen) state.scale = GetDeviceCaps(screen, LOGPIXELSY);
  state.scale = std::clamp(state.scale, 96, 384);
  const wchar_t* display_face = font_face(screen, L"Segoe UI Variable Display");
  const wchar_t* body_face = font_face(screen, L"Segoe UI Variable Text");
  state.status_font = ui_font(state, 32, FW_SEMIBOLD, display_face);
  state.button_font = ui_font(state, 13, FW_MEDIUM, body_face);
  state.small_font = ui_font(state, 12, FW_NORMAL, body_face);
  if (screen) ReleaseDC(nullptr, screen);
  RECT work{};
  SystemParametersInfoW(SPI_GETWORKAREA, 0, &work, 0);
  const int width = state.px(420), height = state.px(280);
  const std::wstring title = std::wstring(L"Adnin - ") + (report.preview ? L"preview - " : L"")
                             + (report.status == 0 ? translated(L"success",L"成功",L"成功") : translated(L"failed",L"失败",L"失敗"));
  HWND window = CreateWindowExW(WS_EX_APPWINDOW | WS_EX_LAYERED, result_window_class, title.c_str(),
                                WS_POPUP | WS_SYSMENU | WS_MINIMIZEBOX,
                                work.left + (work.right - work.left - width) / 2,
                                work.top + (work.bottom - work.top - height) / 2,
                                width, height, nullptr, nullptr, instance, &state);
  if (window) {
    const DWORD rounded = 2;
    DwmSetWindowAttribute(window, 33, &rounded, sizeof(rounded));
    HRGN shape = CreateRoundRectRgn(0, 0, width + 1, height + 1, state.px(20), state.px(20));
    if (shape && !SetWindowRgn(window, shape, FALSE)) DeleteObject(shape);
    CreateWindowExW(0, L"BUTTON", translated(L"Close",L"关闭",L"關閉"), WS_CHILD | WS_VISIBLE | WS_TABSTOP | BS_OWNERDRAW,
                    state.px(379), state.px(9), state.px(30), state.px(30), window,
                    reinterpret_cast<HMENU>(static_cast<INT_PTR>(close_button_id)), instance, nullptr);
    if (report.status != 0)
      CreateWindowExW(0, L"BUTTON", translated(L"Export log",L"导出日志",L"匯出紀錄"), WS_CHILD | WS_VISIBLE | WS_TABSTOP | BS_OWNERDRAW,
                      state.px(132), state.px(186), state.px(156), state.px(38), window,
                      reinterpret_cast<HMENU>(static_cast<INT_PTR>(export_button_id)), instance, nullptr);
    else {
      state.countdown = CreateWindowExW(0, L"STATIC", L"", WS_CHILD | WS_VISIBLE | SS_CENTER,
                                        state.px(20), state.px(205), state.px(380), state.px(25), window,
                                        reinterpret_cast<HMENU>(static_cast<INT_PTR>(countdown_label_id)),
                                        instance, nullptr);
      if (state.countdown)
        SendMessageW(state.countdown, WM_SETFONT, reinterpret_cast<WPARAM>(state.small_font), FALSE);
    }
    SendMessageW(window, WM_CHANGEUISTATE, MAKEWPARAM(UIS_SET, UISF_HIDEFOCUS), 0);
    opacity(state, window, 0);
    const bool timer_ready = SetTimer(window, animation_timer, animation_tick_ms, nullptr) != 0;
    if (!timer_ready || !state.layered) {
      opacity(state, window, 255);
      state.phase = Phase::visible;
      if (timer_ready && report.status == 0) countdown(state, 2);
      else KillTimer(window, animation_timer);
    }
    ShowWindow(window, SW_SHOWNORMAL);
    UpdateWindow(window);
    state.phase_started = GetTickCount64();
    MSG message{};
    BOOL message_status = 0;
    while ((message_status = GetMessageW(&message, nullptr, 0, 0)) > 0) {
      if (message.message == WM_KEYDOWN || message.message == WM_SYSKEYDOWN)
        focus_cues(state, window, true);
      else if (message.message == WM_LBUTTONDOWN || message.message == WM_RBUTTONDOWN
                 || message.message == WM_MBUTTONDOWN || message.message == WM_XBUTTONDOWN
                 || message.message == WM_NCLBUTTONDOWN)
        focus_cues(state, window, false);
      if (message.message == WM_KEYDOWN && message.wParam == VK_ESCAPE) {
        SendMessageW(window, WM_CLOSE, 0, 0);
        continue;
      }
      if (!IsDialogMessageW(window, &message)) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
      }
    }
    // Keep a failed message pump from leaving a window referring to this stack state.
    if (IsWindow(window)) DestroyWindow(window);
    if (message_status == -1) window = nullptr;
  }
  if (state.status_font) DeleteObject(state.status_font);
  if (state.button_font) DeleteObject(state.button_font);
  if (state.small_font) DeleteObject(state.small_font);
  return window != nullptr;
}
}  // namespace adnin
