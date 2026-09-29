#include <windows.h>

#include <iostream>
int wmain(int argc, wchar_t** argv) {
  const wchar_t* name = argc > 1 ? argv[1] : L"LunarRebuildTestWindow";
  WNDCLASSW cls{};
  cls.lpfnWndProc = DefWindowProcW;
  cls.hInstance = GetModuleHandleW(nullptr);
  cls.lpszClassName = name;
  if (!RegisterClassW(&cls)) return 1;
  HWND window = CreateWindowW(name, L"Controlled Lunar rebuild test", WS_OVERLAPPEDWINDOW, 0, 0, 200, 100,
                              nullptr, nullptr, cls.hInstance, nullptr);
  if (!window) return 2;
  // No visible helper windows during automated tests; explicit PID is the primary test path.
  if (argc > 2) ShowWindow(window, SW_SHOWNOACTIVATE);
  std::cout << GetCurrentProcessId() << std::endl;
  const ULONGLONG deadline = GetTickCount64() + 60000;
  while (GetTickCount64() < deadline) {
    MSG message{};
    while (PeekMessageW(&message, nullptr, 0, 0, PM_REMOVE)) {
      TranslateMessage(&message);
      DispatchMessageW(&message);
    }
    Sleep(10);
  }
  DestroyWindow(window);
  return 0;
}
