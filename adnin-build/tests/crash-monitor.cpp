// All target processes and logs are owned fixtures. No real game is inspected,
// stopped, injected, or used to exercise crash handling.
#include "crash-monitor.h"
#include <windows.h>
#include <algorithm>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <stdexcept>
#include <thread>
#include <vector>

namespace {
unsigned checks = 0;
void check(bool ok, const char* why) {
  ++checks; if (!ok) throw std::runtime_error(why);
}
std::wstring executable() {
  std::wstring path(32768, L'\0');
  auto n = GetModuleFileNameW(nullptr, path.data(), static_cast<DWORD>(path.size()));
  check(n && n < path.size(), "fixture executable path"); path.resize(n); return path;
}
std::wstring quote(const std::filesystem::path& path) { return L"\"" + path.wstring() + L"\""; }
std::filesystem::path fixture_path(const std::filesystem::path& path) {
  return L"\\\\?\\" + path.lexically_normal().make_preferred().wstring();
}
struct Child {
  HANDLE process = nullptr, event = nullptr;
  DWORD pid = 0;
  Child(const std::filesystem::path& directory, DWORD code) {
    SECURITY_ATTRIBUTES security{sizeof(security), nullptr, TRUE};
    event = CreateEventW(&security, TRUE, FALSE, nullptr);
    HANDLE ready = CreateEventW(&security, TRUE, FALSE, nullptr);
    check(event != nullptr, "create fixture exit event");
    auto command = quote(executable()) + L" --owned-child " + std::to_wstring(reinterpret_cast<std::uintptr_t>(event))
        + L" " + std::to_wstring(code) + L" " + std::to_wstring(reinterpret_cast<std::uintptr_t>(ready)) + L" --gameDir " + quote(directory)
        + L" -XX:ErrorFile=" + quote(directory / L"fatal-%p-%t.log") + L" --accessToken test-secret";
    STARTUPINFOW startup{}; startup.cb = sizeof(startup); PROCESS_INFORMATION info{};
    check(CreateProcessW(executable().c_str(), command.data(), nullptr, nullptr, TRUE, CREATE_NO_WINDOW,
                         nullptr, directory.c_str(), &startup, &info), "launch owned fixture child");
    CloseHandle(info.hThread); process = info.hProcess; pid = info.dwProcessId;
    check(WaitForSingleObject(ready, 10000) == WAIT_OBJECT_0, "owned fixture initialized"); CloseHandle(ready);
  }
  ~Child() { if (event) { SetEvent(event); CloseHandle(event); } if (process) { WaitForSingleObject(process, 10000); CloseHandle(process); } }
  void finish() { SetEvent(event); check(WaitForSingleObject(process, 10000) == WAIT_OBJECT_0, "owned child exits naturally"); }
};
using adnin::crash_detail::Context;
Context context(const Child& child, const std::filesystem::path& directory) {
  auto c = adnin::crash_detail::capture(child.process, adnin::ClientKind::Lunar, "lunar", std::string(64, 'a'));
  check(c.pid == child.pid && adnin::crash_detail::same_process(child.process, c), "capture exact owned process identity");
  check(std::filesystem::equivalent(c.game_directory, directory), "read only target game directory selector");
  check(c.error_file.filename() == L"fatal-%p-%t.log", "capture bounded ErrorFile selector");
  c.roots = {directory}; c.game_directory = directory; return c;
}
std::vector<std::filesystem::path> reports(const std::filesystem::path& directory) {
  std::vector<std::filesystem::path> found;
  for (const auto& entry : std::filesystem::directory_iterator(fixture_path(directory)))
    if (entry.is_regular_file() && entry.path().filename().wstring().starts_with(L"Adnin-crash-")) found.push_back(entry.path());
  return found;
}
std::string read(const std::filesystem::path& path) {
  const auto native = path.wstring().starts_with(L"\\\\?\\") ? path : fixture_path(path);
  std::ifstream in(native, std::ios::binary); return {std::istreambuf_iterator<char>(in), {}};
}
std::filesystem::path fatal(const std::filesystem::path& root, DWORD pid, bool custom = false) {
  const auto path = root / (custom ? L"fatal-" + std::to_wstring(pid) + L"-2026-09-30.log"
                                : L"hs_err_pid" + std::to_wstring(pid) + L".log");
  std::ofstream file(fixture_path(path), std::ios::binary);
  file << "# A fatal error has been detected by the Java Runtime Environment:\n"
       << "# EXCEPTION_ACCESS_VIOLATION (0xc0000005) at pc=0x1000, pid=" << pid << ", tid=2\n"
       << "# Problematic frame:\n# C  [Adnin.dll+0x1000]\n"
       << "at fixture.Crash.fail(Crash.java:12)\n"
       << "api_key=test-key\nAuthorization: Bearer test-secret\n"
       << "Command Line: --accessToken test-secret\n"
       << "[CHAT] private chat message\n"
       << "Bot URL: https://example.invalid/api/users?q=fixture\n"
       << "Username: fixture-player\nUUID: 12345678-1234-4123-8123-123456789abc\n";
  return path;
}
void old_file(const std::filesystem::path& path, std::uint64_t ticks) {
  HANDLE file = CreateFileW(fixture_path(path).c_str(), FILE_WRITE_ATTRIBUTES, 0, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
  check(file != INVALID_HANDLE_VALUE, "open owned fixture timestamp");
  FILETIME stamp{static_cast<DWORD>(ticks), static_cast<DWORD>(ticks >> 32)};
  const bool ok = SetFileTime(file, &stamp, nullptr, &stamp) != FALSE; CloseHandle(file);
  check(ok, "set owned fixture timestamps");
}
std::filesystem::path game_crash(const std::filesystem::path& root, const std::wstring& name) {
  const auto directory = root / L"crash-reports"; std::filesystem::create_directories(fixture_path(directory));
  const auto path = directory / name; std::ofstream file(fixture_path(path), std::ios::binary);
  file << "---- Minecraft Crash Report ----\njava.lang.NullPointerException: fixture-private-message\n"
       << "at fixture.Game.tick(Game.java:25)\n"
       << "at fixture.Game.unsafe(C:/Users/fixture-private/Game.java:26)\n"
       << "api_key=test-key\nAuthorization: Bearer test-secret\n";
  return path;
}
void privacy_tests(const std::filesystem::path& root) {
  const auto text = adnin::crash_detail::diagnostic_excerpt(
      "at fixture.Good.source(Good.java:12)\n"
      "at java.base/java.lang.Thread.run(Thread.java:834)\n"
      "at fixture.Good.nativeCall(Native Method)\n"
      "at fixture.Good.unknown(Unknown Source)\n"
      "at fixture.Bad.a(C:/Users/fixture-private/File.java:12)\n"
      "at fixture.Bad.b(C:\\Users\\fixture-private\\File.java:12)\n"
      "at fixture.Bad.c(\\\\fixture-server\\share\\File.java:12)\n"
      "at fixture.Bad.d(../fixture-private/File.java:12)\n"
      "at fixture.Bad.e(/home/fixture-private/File.java:12)\n"
      "at fixture.Bad.f(File.java:12:34)\n"
      "at fixture.Bad.g(File.java:test-secret)\n");
  check(text.find("fixture.Good.source") != std::string::npos, "source basename frame retained");
  check(text.find("java.base/java.lang.Thread") != std::string::npos, "safe Java module frame retained");
  check(text.find("Native Method") != std::string::npos && text.find("Unknown Source") != std::string::npos, "special source descriptions retained");
  check(text.find("fixture.Bad") == std::string::npos && text.find("fixture-private") == std::string::npos, "all path and malformed source frames rejected");
  check(adnin::crash_detail::valid_destination(root, false), "local test destination allowed");
  const std::filesystem::path unc(L"\\\\fixture-server\\profiles\\fixture\\Desktop");
  check(adnin::crash_detail::valid_destination(unc, true), "system Known Folder UNC destination accepted syntactically");
  check(!adnin::crash_detail::valid_destination(unc, false), "ordinary test destination cannot use UNC");
  check(!adnin::crash_detail::valid_destination(L"\\\\?\\C:\\fixture", true), "device namespace rejected");
  check(!adnin::crash_detail::valid_destination(L"\\\\fixture-server\\profiles\\..\\Desktop", true), "UNC traversal rejected");
  check(!adnin::crash_detail::valid_destination(L"\\\\fixture-server", true), "incomplete UNC rejected");
}
void long_path_tests(const std::filesystem::path& root) {
  const auto prefix = root.wstring().size() + 1;
  check(prefix < 220, "long-path fixture parent remains below CWD limit");
  const auto folder = root / std::wstring(220 - prefix, L'x');
  std::filesystem::create_directory(folder);
  { Child child(folder, 5); auto c = context(child, folder); child.finish();
    const int status = adnin::crash_detail::wait_and_export(child.process, c, folder, 0);
    if (status != 0) std::cerr << "Long output fixture: directory characters=" << folder.wstring().size()
                             << ", monitor status=" << status << ", win32=" << GetLastError() << '\n';
    check(status == 0 && reports(folder).size() == 1, "report path beyond MAX_PATH is supported");
    check(read(reports(folder)[0]).find("cause unconfirmed") != std::string::npos, "long report contains completed diagnostic data");
    check(adnin::crash_detail::wait_and_export(child.process, c, folder, 0) == 0 && reports(folder).size() == 1,
          "long report path retains sequential exit deduplication"); }
  { Child child(folder, 6); auto c = context(child, folder);
    const auto deep = folder / std::wstring(60, L'd');
    std::filesystem::create_directory(fixture_path(deep));
    check(deep.wstring().size() > MAX_PATH, "fatal fixture directory itself exceeds MAX_PATH");
    c.roots = {deep}; c.game_directory = deep; c.error_file = deep / L"fatal-%p-%t.log";
    fatal(deep, child.pid); fatal(deep, child.pid, true); game_crash(deep, L"crash-long-candidate.txt"); child.finish();
    check(adnin::crash_detail::wait_and_export(child.process, c, folder, 0) == 0 && reports(folder).size() == 2,
          "long source directories preserve base report export");
    std::string text;
    for (const auto& path : reports(folder)) {
      const auto content = read(path);
      if (content.find("Process ID: " + std::to_string(child.pid) + "\n") != std::string::npos) text = content;
    }
    check(text.find("PID-matched JVM fatal log 2") != std::string::npos, "long exact and timestamp source paths both collected");
    check(text.find("Game-directory crash candidate; PID attribution unavailable.") != std::string::npos,
          "long game crash directory discovery and read both supported"); }
}
void wait_report(const std::filesystem::path& root) {
  for (unsigned i = 0; i < 100 && reports(root).empty(); ++i) Sleep(50);
  check(reports(root).size() == 1, "hidden worker exported exactly one report");
}
void basic_tests(const std::filesystem::path& root) {
  auto one = root / L"normal"; std::filesystem::create_directory(one);
  { Child child(one, 0); auto c = context(child, one); child.finish();
    check(adnin::crash_detail::wait_and_export(child.process, c, one, 0) == 0, "normal exit monitor succeeds");
    check(reports(one).empty(), "normal exit has no report"); }
  auto two = root / L"abnormal"; std::filesystem::create_directory(two);
  { Child child(two, 0xc0000005); auto c = context(child, two);
    auto wrong = c; wrong.created++;
    check(adnin::crash_detail::wait_and_export(child.process, wrong, two, 0) == 2, "creation mismatch rejected before waiting");
    wrong = c; wrong.pid++;
    check(!adnin::crash_detail::same_process(child.process, wrong), "PID mismatch rejected");
    child.finish();
    check(adnin::crash_detail::wait_and_export(child.process, c, two, 0) == 0, "nonzero exit exports without fatal file");
    auto found = reports(two); check(found.size() == 1, "one basic abnormal report");
    auto data = read(found[0]);
    check(data.find("0xc0000005") != std::string::npos && data.find("cause unconfirmed") != std::string::npos, "facts and attribution limit");
    check(data.find("No recent PID-matched") != std::string::npos, "unavailable log explained");
    check(adnin::crash_detail::wait_and_export(child.process, c, two, 0) == 0 && reports(two).size() == 1, "same exited process exports once across sequential calls");
    check(read(found[0]) == data, "sequential duplicate never overwrites the first report"); }
  { Child child(two, 9); auto c = context(child, two); child.finish();
    check(adnin::crash_detail::wait_and_export(child.process, c, two, 0) == 0 && reports(two).size() == 2, "distinct process exit produces its own report"); }
  auto three = root / L"exit259"; std::filesystem::create_directory(three);
  { Child child(three, 259); auto c = context(child, three); child.finish();
    check(adnin::crash_detail::wait_and_export(child.process, c, three, 0) == 0 && reports(three).size() == 1, "259 after signaled handle is an exited process"); }
}
void evidence_tests(const std::filesystem::path& root) {
  auto folder = root / L"fatal"; std::filesystem::create_directory(folder);
  { Child child(folder, 0); auto c = context(child, folder); fatal(folder, child.pid, true); child.finish();
    check(adnin::crash_detail::wait_and_export(child.process, c, folder, 0) == 0, "zero exit plus fresh matching fatal evidence exports");
    auto files = reports(folder); check(files.size() == 1, "fatal evidence triggers report");
    auto text = read(files[0]);
    check(text.find("PID-matched JVM fatal evidence: yes") != std::string::npos, "ErrorFile PID-matched source associated");
    check(text.find("adnin.dll+0x1000") != std::string::npos && text.find("fixture.Crash.fail") != std::string::npos, "safe diagnostic frames retained");
    for (const char* secret : {"test-key", "test-secret", "fixture-player", "private chat message", "example.invalid", "12345678-1234"})
      check(text.find(secret) == std::string::npos, "private fixture data excluded"); }
  auto stale = root / L"stale"; std::filesystem::create_directory(stale);
  { Child child(stale, 0); auto c = context(child, stale); auto path = fatal(stale, child.pid);
    HANDLE file = CreateFileW(path.c_str(), FILE_WRITE_ATTRIBUTES, 0, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    check(file != INVALID_HANDLE_VALUE, "open fixture for old timestamp");
    auto old = c.created - 1000000000ULL; FILETIME stamp{static_cast<DWORD>(old), static_cast<DWORD>(old >> 32)};
    check(SetFileTime(file, nullptr, nullptr, &stamp), "set old fixture timestamp"); CloseHandle(file);
    fatal(stale, child.pid + 1); child.finish();
    check(adnin::crash_detail::wait_and_export(child.process, c, stale, 0) == 0 && reports(stale).empty(), "old and unrelated PID evidence ignored"); }
  auto wrong = root / L"wrong-content"; std::filesystem::create_directory(wrong);
  { Child child(wrong, 0); auto c = context(child, wrong);
    auto file = fatal(wrong, child.pid + 1); std::filesystem::rename(file, wrong / (L"hs_err_pid" + std::to_wstring(child.pid) + L".log"));
    child.finish(); check(adnin::crash_detail::wait_and_export(child.process, c, wrong, 0) == 0 && reports(wrong).empty(), "filename alone cannot associate another PID"); }
  auto huge = root / L"oversize"; std::filesystem::create_directory(huge);
  { Child child(huge, 1); auto c = context(child, huge); auto path = fatal(huge, child.pid);
    std::filesystem::resize_file(path, 17 * 1024 * 1024); child.finish();
    check(adnin::crash_detail::wait_and_export(child.process, c, huge, 0) == 0 && reports(huge).size() == 1, "oversize evidence cannot block base report");
    check(read(reports(huge)[0]).size() < 65536, "bounded exported size"); }
  for (bool custom : {false, true}) {
    auto crowded = root / (custom ? L"crowded-custom" : L"crowded-standard"); std::filesystem::create_directory(crowded);
    for (unsigned i = 0; i < 320; ++i) std::ofstream(crowded / (L"a-irrelevant-" + std::to_wstring(i) + L".log"));
    Child child(crowded, 0); auto c = context(child, crowded); const auto source = fatal(crowded, child.pid);
    if (custom) {
      c.error_file = crowded / L"z-configured-%p.log";
      std::filesystem::rename(source, crowded / (L"z-configured-" + std::to_wstring(child.pid) + L".log"));
    }
    child.finish();
    check(adnin::crash_detail::wait_and_export(child.process, c, crowded, 0) == 0 && reports(crowded).size() == 1,
          "known fatal name found beyond irrelevant directory enumeration limit");
  }
  auto timed = root / L"crowded-timestamp"; std::filesystem::create_directory(timed);
  for (unsigned i = 0; i < 320; ++i) std::ofstream(timed / (L"a-irrelevant-" + std::to_wstring(i) + L".log"));
  { Child child(timed, 0); auto c = context(child, timed); fatal(timed, child.pid, true); child.finish();
    check(adnin::crash_detail::wait_and_export(child.process, c, timed, 0) == 0 && reports(timed).size() == 1,
          "timestamp template searches its specific bounded filename pattern"); }
}
void game_report_tests(const std::filesystem::path& root) {
  auto fresh = root / L"game-fresh"; std::filesystem::create_directory(fresh);
  { Child child(fresh, 1); auto c = context(child, fresh); game_crash(fresh, L"crash-fresh.txt"); child.finish();
    check(adnin::crash_detail::wait_and_export(child.process, c, fresh, 0) == 0 && reports(fresh).size() == 1, "fresh game crash supplements abnormal exit");
    const auto text = read(reports(fresh)[0]);
    check(text.find("Game-directory crash candidate; PID attribution unavailable.") != std::string::npos, "game candidate clearly disclaims process attribution");
    check(text.find("java.lang.NullPointerException") != std::string::npos && text.find("fixture.Game.tick") != std::string::npos, "allowlisted Java exception and safe frame included");
    check(text.find("fixture-private") == std::string::npos && text.find("test-key") == std::string::npos && text.find("test-secret") == std::string::npos, "candidate paths credentials and exception messages excluded");
    check(text.find("PID-matched JVM fatal evidence: no") != std::string::npos, "game candidate is not claimed as PID fatal evidence"); }
  auto normal = root / L"game-normal"; std::filesystem::create_directory(normal);
  { Child child(normal, 0); auto c = context(child, normal); game_crash(normal, L"crash-fresh.txt"); child.finish();
    check(adnin::crash_detail::wait_and_export(child.process, c, normal, 0) == 0 && reports(normal).empty(), "game candidate alone cannot turn normal exit into crash"); }
  auto stale = root / L"game-stale"; std::filesystem::create_directory(stale);
  { Child child(stale, 1); auto c = context(child, stale); auto path = game_crash(stale, L"crash-old.txt");
    old_file(path, c.observed - 1000000000ULL); child.finish();
    check(adnin::crash_detail::wait_and_export(child.process, c, stale, 0) == 0 && reports(stale).size() == 1, "old game candidate does not block base report");
    check(read(reports(stale)[0]).find("Game-directory crash candidate") == std::string::npos, "old game candidate omitted"); }
  auto huge = root / L"game-huge"; std::filesystem::create_directory(huge);
  { Child child(huge, 1); auto c = context(child, huge); auto path = game_crash(huge, L"crash-large.txt");
    std::filesystem::resize_file(path, 2 * 1024 * 1024); child.finish();
    check(adnin::crash_detail::wait_and_export(child.process, c, huge, 0) == 0 && reports(huge).size() == 1, "oversize game candidate cannot block base report");
    check(read(reports(huge)[0]).find("Game-directory crash candidate") == std::string::npos, "oversize game candidate omitted"); }
}
void worker_tests(const std::filesystem::path& root) {
  auto folder = root / L"worker"; std::filesystem::create_directory(folder);
  { Child child(folder, 1); auto c = context(child, folder);
    check(adnin::crash_detail::launch(child.process, c, executable(), folder), "spawn same executable hidden monitor");
    Sleep(150);
    check(adnin::crash_detail::launch(child.process, c, executable(), folder), "second hidden launch is safely accepted");
    Sleep(150); child.finish(); wait_report(folder); Sleep(1700);
    check(reports(folder).size() == 1, "concurrent duplicate watcher exits without duplicate export");
    const auto original = read(reports(folder)[0]);
    check(adnin::crash_detail::launch(child.process, c, executable(), folder), "late hidden worker launch accepted after first export");
    Sleep(2500);
    check(reports(folder).size() == 1 && read(reports(folder)[0]) == original, "late hidden worker does not duplicate or overwrite exported exit"); }
  for (DWORD code : {DWORD(0), DWORD(7)}) {
    auto detached = root / (code ? L"parent-exit-abnormal" : L"parent-exit-normal"); std::filesystem::create_directory(detached);
    Child child(detached, code);
    auto command = quote(executable()) + L" --owned-launcher " + std::to_wstring(child.pid) + L" " + quote(detached);
    STARTUPINFOW startup{}; startup.cb = sizeof(startup); PROCESS_INFORMATION launcher{};
    check(CreateProcessW(executable().c_str(), command.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW,
                         nullptr, detached.c_str(), &startup, &launcher), "spawn short-lived owned injector parent");
    CloseHandle(launcher.hThread);
    check(WaitForSingleObject(launcher.hProcess, 10000) == WAIT_OBJECT_0, "parent exits before game fixture");
    DWORD result = 1; GetExitCodeProcess(launcher.hProcess, &result); CloseHandle(launcher.hProcess);
    check(result == 0, "short-lived parent launched watcher successfully");
    child.finish();
    if (code) wait_report(detached);
    else { Sleep(2000); check(reports(detached).empty(), "detached watcher keeps normal exit silent"); }
  }
}
}  // namespace
int wmain(int argc, wchar_t** argv) {
  int mode = adnin::crash_monitor_entry(argc, argv); if (mode >= 0) return mode;
  if (argc >= 5 && std::wstring(argv[1]) == L"--owned-child") {
    HANDLE event = reinterpret_cast<HANDLE>(static_cast<std::uintptr_t>(std::stoull(argv[2])));
    HANDLE ready = reinterpret_cast<HANDLE>(static_cast<std::uintptr_t>(std::stoull(argv[4])));
    SetEvent(ready); CloseHandle(ready);
    WaitForSingleObject(event, 30000); CloseHandle(event); ExitProcess(static_cast<DWORD>(std::stoull(argv[3])));
  }
  if (argc == 4 && std::wstring(argv[1]) == L"--owned-launcher") {
    HANDLE target = OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ | SYNCHRONIZE, FALSE, static_cast<DWORD>(std::stoul(argv[2])));
    if (!target) return 2;
    auto c = adnin::crash_detail::capture(target, adnin::ClientKind::Lunar, "lunar", std::string(64, 'a'));
    c.roots = {std::filesystem::path(argv[3])}; c.game_directory = argv[3];
    bool ok = adnin::crash_detail::launch(target, c, executable(), argv[3]); CloseHandle(target); return ok ? 0 : 2;
  }
  if (argc != 2) return 2;
  auto root = std::filesystem::absolute(argv[1]) / (L"fixture-" + std::to_wstring(GetCurrentProcessId()));
  std::filesystem::create_directories(root);
  try { privacy_tests(root); long_path_tests(root); basic_tests(root); evidence_tests(root); game_report_tests(root); worker_tests(root); }
  catch (const std::exception& failure) { std::cerr << "FAIL: " << failure.what() << '\n'; return 1; }
  std::cout << "CrashMonitorTest: " << checks << " checks passed; owned children and temporary fixtures only\n";
  return 0;
}
