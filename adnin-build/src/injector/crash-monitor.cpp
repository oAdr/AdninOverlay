#include "crash-monitor.h"
#include <shellapi.h>
#include <shlobj.h>
#include <winternl.h>
#include <algorithm>
#include <array>
#include <cctype>
#include <cstring>
#include <cwchar>
#include <cwctype>
#include <iomanip>
#include <limits>
#include <sstream>
#include <string_view>
#include <utility>

namespace adnin {
namespace {
constexpr wchar_t worker_flag[] = L"--adnin-crash-watch";
constexpr std::size_t max_roots = 8, max_path = 2048, max_input = 131072, max_output = 65536;
constexpr std::uint64_t second = 10000000;
struct Handle {
  HANDLE value = nullptr;
  explicit Handle(HANDLE h = nullptr) : value(h) { }
  ~Handle() { if (value && value != INVALID_HANDLE_VALUE) CloseHandle(value); }
  Handle(const Handle&) = delete;
  Handle& operator=(const Handle&) = delete;
};
struct View {
  void* value;
  explicit View(void* p) : value(p) { }
  ~View() { if (value) UnmapViewOfFile(value); }
};
struct Wire {
  DWORD magic, size, pid, client, root_count;
  std::uint64_t created, observed;
  char runtime[24], payload_hash[65];
  wchar_t roots[max_roots][max_path], game_directory[max_path], error_file[max_path], test_output[max_path];
};
constexpr DWORD magic = 0x32435741;
bool valid(HANDLE h) { return h && h != INVALID_HANDLE_VALUE; }
bool decimal(std::wstring_view value, std::uint64_t& result) {
  result = 0;
  if (value.empty() || value.size() > 20) return false;
  for (wchar_t c : value) {
    if (c < L'0' || c > L'9' || result > (UINT64_MAX - (c - L'0')) / 10) return false;
    result = result * 10 + static_cast<unsigned>(c - L'0');
  }
  return true;
}
std::uint64_t now_filetime() {
  FILETIME now{}; GetSystemTimeAsFileTime(&now); return crash_detail::filetime(now);
}
std::wstring environment(const wchar_t* name) {
  DWORD count = GetEnvironmentVariableW(name, nullptr, 0);
  if (!count || count > max_path) return {};
  std::wstring value(count, L'\0');
  DWORD length = GetEnvironmentVariableW(name, value.data(), count);
  if (!length || length >= count) return {};
  value.resize(length); return value;
}
bool local_path(const std::filesystem::path& path) {
  const auto root = path.root_name().wstring();
  const auto text = path.wstring();
  return path.is_absolute() && root.size() == 2 && std::iswalpha(root[0]) && root[1] == L':'
      && text.size() < max_path && text.find_first_of(L"\r\n\0", 0, 3) == std::wstring::npos
      && text.find(L':', 2) == std::wstring::npos;
}
// Call only after validating the original path/directory. Extended Win32 paths
// avoid MAX_PATH without changing the validation rules or enabling device paths.
std::wstring win32_path(const std::filesystem::path& path) {
  auto value = path.lexically_normal().make_preferred().wstring();
  return value.starts_with(L"\\\\") ? L"\\\\?\\UNC\\" + value.substr(2) : L"\\\\?\\" + value;
}
bool ordinary_directory(const std::filesystem::path& path) {
  if (!local_path(path)) return false;
  auto part = path.lexically_normal();
  for (;;) {
    DWORD attributes = GetFileAttributesW(win32_path(part).c_str());
    if (attributes == INVALID_FILE_ATTRIBUTES || !(attributes & FILE_ATTRIBUTE_DIRECTORY)
        || (attributes & FILE_ATTRIBUTE_REPARSE_POINT)) return false;
    if (part == part.root_path()) break;
    auto parent = part.parent_path(); if (parent == part || parent.empty()) return false;
    part = std::move(parent);
  }
  return true;
}
void add_root(crash_detail::Context& context, std::filesystem::path path) {
  if (!ordinary_directory(path)) return;
  path = path.lexically_normal();
  for (const auto& known : context.roots) if (_wcsicmp(known.c_str(), path.c_str()) == 0) return;
  if (context.roots.size() < max_roots) context.roots.push_back(std::move(path));
}
std::wstring remote_text(HANDLE process, const UNICODE_STRING& text) {
  if (!text.Buffer || text.Length % sizeof(wchar_t) || text.Length > 65532
      || text.Length > text.MaximumLength) return {};
  std::wstring result(text.Length / sizeof(wchar_t), L'\0'); SIZE_T read = 0;
  if (!ReadProcessMemory(process, text.Buffer, result.data(), text.Length, &read) || read != text.Length) return {};
  if (result.find(L'\0') != std::wstring::npos) return {};
  return result;
}
// The public PEB/RTL_USER_PROCESS_PARAMETERS command-line layout is read-only.
// No environment block, memory dump, or entire command line is retained.
void game_paths(HANDLE target, crash_detail::Context& context) {
  using Query = NTSTATUS(NTAPI*)(HANDLE, PROCESSINFOCLASS, PVOID, ULONG, PULONG);
  const auto proc = GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "NtQueryInformationProcess");
  if (!proc) return;
  Query query = nullptr; static_assert(sizeof(query) == sizeof(proc));
  std::memcpy(&query, &proc, sizeof(query));
  PROCESS_BASIC_INFORMATION basic{};
  if (query(target, ProcessBasicInformation, &basic, sizeof(basic), nullptr) < 0 || !basic.PebBaseAddress) return;
  PEB peb{}; SIZE_T count = 0;
  if (!ReadProcessMemory(target, basic.PebBaseAddress, &peb, sizeof(peb), &count) || count != sizeof(peb)
      || !peb.ProcessParameters) return;
  RTL_USER_PROCESS_PARAMETERS parameters{};
  if (!ReadProcessMemory(target, peb.ProcessParameters, &parameters, sizeof(parameters), &count)
      || count != sizeof(parameters)) return;
  // x64 CURRENT_DIRECTORY begins at 0x38 in RTL_USER_PROCESS_PARAMETERS.
  // This optional hint fails closed if the bounded string/path is invalid.
  UNICODE_STRING cwd{};
  if (ReadProcessMemory(target, reinterpret_cast<const char*>(peb.ProcessParameters) + 0x38,
                        &cwd, sizeof(cwd), &count) && count == sizeof(cwd)) {
    auto value = remote_text(target, cwd);
    if (!value.empty()) { add_root(context, value); if (ordinary_directory(value)) context.game_directory = value; }
  }
  std::wstring command = remote_text(target, parameters.CommandLine);
  if (command.empty()) return;
  int argc = 0; wchar_t** argv = CommandLineToArgvW(command.c_str(), &argc);
  SecureZeroMemory(command.data(), command.size() * sizeof(wchar_t));
  if (!argv) return;
  for (int i = 1; i < argc; ++i) {
    const std::wstring_view arg(argv[i]);
    if (arg == L"--gameDir" && i + 1 < argc) {
      std::filesystem::path path(argv[++i]);
      if (ordinary_directory(path)) { context.game_directory = path.lexically_normal(); add_root(context, path); }
    } else if (arg.starts_with(L"-XX:ErrorFile=")) {
      std::filesystem::path path(std::wstring(arg.substr(14)));
      if (path.is_relative() && !context.game_directory.empty()) path = context.game_directory / path;
      if (local_path(path) && ordinary_directory(path.parent_path())) context.error_file = path.lexically_normal();
    }
  }
  for (int i = 0; i < argc; ++i) SecureZeroMemory(argv[i], std::wcslen(argv[i]) * sizeof(wchar_t));
  LocalFree(argv);
  if (!context.error_file.empty()) add_root(context, context.error_file.parent_path());
}
template<std::size_t N> bool copy(wchar_t (&to)[N], const std::filesystem::path& path) {
  const auto value = path.wstring(); if (value.size() >= N) return false;
  std::copy(value.begin(), value.end(), to); to[value.size()] = 0; return true;
}
template<std::size_t N> bool copy(char (&to)[N], const std::string& value) {
  if (value.size() >= N) return false;
  std::copy(value.begin(), value.end(), to); to[value.size()] = 0; return true;
}
std::string timestamp(std::uint64_t ticks) {
  FILETIME value{static_cast<DWORD>(ticks), static_cast<DWORD>(ticks >> 32)}; SYSTEMTIME utc{};
  if (!FileTimeToSystemTime(&value, &utc)) return "unavailable";
  std::ostringstream text;
  text << std::setfill('0') << std::setw(4) << utc.wYear << '-' << std::setw(2) << utc.wMonth << '-'
       << std::setw(2) << utc.wDay << 'T' << std::setw(2) << utc.wHour << ':' << std::setw(2) << utc.wMinute
       << ':' << std::setw(2) << utc.wSecond << '.' << std::setw(3) << utc.wMilliseconds << 'Z';
  return text.str();
}
std::string lower(std::string value) {
  for (char& c : value) if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
  return value;
}
bool sensitive(std::string_view line) {
  const auto text = lower(std::string(line));
  for (const char* word : {"token", "secret", "password", "session", "api", "authorization", "bearer",
                           "command", "username", "uuid", "http", "www.", "[chat]", "--", "botdenicker"})
    if (text.find(word) != std::string::npos) return true;
  return false;
}
bool safe_frame(std::string_view line) {
  if (!line.starts_with("at ") || line.size() > 300 || sensitive(line)) return false;
  const auto opening = line.find('(');
  if (opening == std::string_view::npos || opening <= 3 || line.back() != ')') return false;
  const auto member = line.substr(3, opening - 3);
  unsigned separators = 0;
  for (char c : member) {
    if (c == '/') { if (++separators > 1) return false; continue; }
    if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9')
        && std::string_view("._$<>").find(c) == std::string_view::npos) return false;
  }
  if (member.front() == '/' || member.back() == '/') return false;
  auto source = line.substr(opening + 1, line.size() - opening - 2);
  if (source == "Native Method" || source == "Unknown Source") return true;
  const auto colon = source.find(':');
  if (colon != std::string_view::npos) {
    const auto number = source.substr(colon + 1);
    if (number.empty() || number.size() > 9 || number.find_first_not_of("0123456789") != std::string_view::npos) return false;
    source = source.substr(0, colon);
  }
  if (source.empty() || source.size() > 120 || source == "." || source == "..") return false;
  // Only a source basename is exportable; never a drive, UNC or relative path.
  for (char c : source) if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9')
      && std::string_view("._$-").find(c) == std::string_view::npos) return false;
  return true;
}
bool read_file(const std::filesystem::path& path, const crash_detail::Context& context,
               std::uint64_t exited, std::string& contents, bool& truncated, bool game_candidate = false) {
  if (!local_path(path) || !ordinary_directory(path.parent_path())) return false;
  Handle file(CreateFileW(win32_path(path).c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                          nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OPEN_REPARSE_POINT, nullptr));
  if (!valid(file.value)) return false;
  BY_HANDLE_FILE_INFORMATION info{}; LARGE_INTEGER length{};
  if (!GetFileInformationByHandle(file.value, &info) || !GetFileSizeEx(file.value, &length)
      || (info.dwFileAttributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT))
      || length.QuadPart < 0 || length.QuadPart > (game_candidate ? 1024 * 1024 : 16 * 1024 * 1024)) return false;
  const auto modified = crash_detail::filetime(info.ftLastWriteTime);
  // Never adopt an old PID-reused file. JVM fatal logs are written at termination.
  if (modified < context.created || modified + 120 * second < exited || modified > exited + 3 * second) return false;
  if (game_candidate && (crash_detail::filetime(info.ftCreationTime) < context.observed || modified < context.observed)) return false;
  const DWORD wanted = static_cast<DWORD>(std::min<LONGLONG>(length.QuadPart, max_input));
  contents.resize(wanted); DWORD actual = 0;
  if (!ReadFile(file.value, contents.data(), wanted, &actual, nullptr) || actual != wanted) return false;
  truncated = static_cast<ULONGLONG>(length.QuadPart) > max_input;
  return true;
}
bool pid_evidence(std::string_view text, DWORD pid) {
  const auto end = text.substr(0, std::min<std::size_t>(text.size(), 16384));
  if (end.find("A fatal error has been detected by the Java Runtime Environment") == std::string_view::npos) return false;
  const std::string token = "pid=" + std::to_string(pid);
  std::size_t found = end.find(token);
  while (found != std::string_view::npos) {
    const auto after = found + token.size();
    if (after < end.size() && (end[after] == ',' || end[after] == ' ' || end[after] == '\r' || end[after] == '\n')) return true;
    found = end.find(token, after);
  }
  return false;
}
std::wstring configured_name(const crash_detail::Context& context) {
  auto configured = context.error_file.filename().wstring();
  const auto pid = std::to_wstring(context.pid);
  for (auto pos = configured.find(L"%p"); pos != std::wstring::npos; pos = configured.find(L"%p", pos + pid.size()))
    configured.replace(pos, 2, pid);
  return configured;
}
bool error_name(std::wstring_view name, const std::wstring& configured) {
  const auto pos = configured.find(L"%t");
  if (pos == std::wstring::npos) return name == configured;
  const auto suffix = configured.substr(pos + 2);
  if (!name.starts_with(configured.substr(0, pos)) || !name.ends_with(suffix)
      || name.size() <= pos + suffix.size() || name.size() > pos + suffix.size() + 64) return false;
  for (wchar_t c : name.substr(pos, name.size() - pos - suffix.size()))
    if ((c < L'0' || c > L'9') && c != L'-' && c != L'_') return false;
  return true;
}
std::string collect(const crash_detail::Context& context, std::uint64_t exited, bool& fatal, bool abnormal_code) {
  std::ostringstream report; unsigned accepted = 0;
  std::vector<std::filesystem::path> visited;
  const auto probe = [&](const std::filesystem::path& path) {
    if (accepted >= 4) return;
    const auto normalized = path.lexically_normal();
    for (const auto& known : visited) if (_wcsicmp(known.c_str(), normalized.c_str()) == 0) return;
    visited.push_back(normalized);
    std::string text; bool truncated = false;
    if (!read_file(normalized, context, exited, text, truncated) || !pid_evidence(text, context.pid)) return;
    fatal = true; ++accepted;
    report << "\nPID-matched JVM fatal log " << accepted << " (sanitized diagnostic excerpt"
           << (truncated ? "; source read capped" : "") << "):\n" << crash_detail::diagnostic_excerpt(text);
  };
  // Exact standard names must remain discoverable in large TEMP directories.
  for (const auto& root : context.roots) {
    probe(root / (L"hs_err_pid" + std::to_wstring(context.pid) + L".log"));
    if (accepted >= 4) break;
  }
  if (!context.error_file.empty()) {
    const auto configured = configured_name(context);
    const auto pos = configured.find(L"%t");
    if (configured.find_first_of(L"*?") == std::wstring::npos) {
      if (pos == std::wstring::npos) probe(context.error_file.parent_path() / configured);
      else if (configured.find(L"%t", pos + 2) == std::wstring::npos
               && ordinary_directory(context.error_file.parent_path())) {
        auto filename = configured; filename.replace(pos, 2, L"*");
        const auto pattern = context.error_file.parent_path() / filename;
        WIN32_FIND_DATAW data{}; HANDLE search = FindFirstFileW(win32_path(pattern).c_str(), &data);
        if (search != INVALID_HANDLE_VALUE) {
          unsigned examined = 0;
          do {
            if (++examined > 256 || accepted >= 4) break;
            if (!(data.dwFileAttributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT))
                && error_name(data.cFileName, configured)) probe(context.error_file.parent_path() / data.cFileName);
          } while (FindNextFileW(search, &data));
          FindClose(search);
        }
      }
    }
  }
  // Chat, properties, launch parameters, environment, profile names and arbitrary
  // client logs are intentionally not copied. A missing fatal file still leaves
  // the exact process/build/exit diagnosis available to the user.
  if (!fatal) report << "\nNo recent PID-matched JVM fatal log was found in the bounded search.\n";
  // Minecraft reports have no trustworthy PID. They can supplement an already
  // abnormal exit, but must never turn exit code zero into a crash diagnosis.
  const auto crash_directory = context.game_directory / L"crash-reports";
  if (abnormal_code && !context.game_directory.empty() && ordinary_directory(crash_directory)) {
    const auto pattern = crash_directory / L"*.txt";
    WIN32_FIND_DATAW data{}; HANDLE search = FindFirstFileW(win32_path(pattern).c_str(), &data);
    std::filesystem::path newest; std::uint64_t newest_time = 0;
    if (search != INVALID_HANDLE_VALUE) {
      unsigned examined = 0;
      do {
        if (++examined > 256) break;
        const auto modified = crash_detail::filetime(data.ftLastWriteTime);
        if ((data.dwFileAttributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT))
            || data.nFileSizeHigh || data.nFileSizeLow > 1024 * 1024
            || crash_detail::filetime(data.ftCreationTime) < context.observed || modified < context.observed
            || modified + 120 * second < exited || modified > exited + 3 * second || modified < newest_time) continue;
        newest = crash_directory / data.cFileName; newest_time = modified;
      } while (FindNextFileW(search, &data));
      FindClose(search);
    }
    std::string text; bool truncated = false;
    if (!newest.empty() && read_file(newest, context, exited, text, truncated, true)
        && std::string_view(text).substr(0, 4096).find("---- Minecraft Crash Report ----") != std::string_view::npos) {
      report << "\nGame-directory crash candidate; PID attribution unavailable.\n"
             << "Sanitized diagnostic excerpt" << (truncated ? " (source read capped)" : "") << ":\n"
             << crash_detail::diagnostic_excerpt(text);
    }
  }
  return report.str();
}
bool export_report(const std::string& contents, const crash_detail::Context& context,
                   std::uint64_t exited, const std::filesystem::path& test_output) {
  std::filesystem::path directory = test_output;
  const bool system_desktop = directory.empty();
  if (directory.empty()) {
    PWSTR desktop = nullptr;
    if (FAILED(SHGetKnownFolderPath(FOLDERID_Desktop, KF_FLAG_DEFAULT, nullptr, &desktop)) || !desktop) return false;
    directory = desktop; CoTaskMemFree(desktop);
  }
  if (!crash_detail::valid_destination(directory, system_desktop)) return false;
  const FILETIME exit_time{static_cast<DWORD>(exited), static_cast<DWORD>(exited >> 32)};
  SYSTEMTIME time{}; if (!FileTimeToSystemTime(&exit_time, &time)) return false;
  std::wostringstream name;
  name << L"Adnin-crash-" << std::setfill(L'0') << std::setw(4) << time.wYear << std::setw(2) << time.wMonth
       << std::setw(2) << time.wDay << L'-' << std::setw(2) << time.wHour << std::setw(2) << time.wMinute
       << std::setw(2) << time.wSecond << L'-' << std::setw(3) << time.wMilliseconds << L"Z-" << context.pid
       << L'-' << std::hex << context.created << L'-' << exited << L".log";
  const auto path = directory / name.str();
  const auto native_path = win32_path(path);
  Handle file(CreateFileW(native_path.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, nullptr));
  if (!valid(file.value)) {
    const DWORD error = GetLastError();
    return error == ERROR_FILE_EXISTS || error == ERROR_ALREADY_EXISTS;
  }
  DWORD written = 0; const auto size = static_cast<DWORD>(std::min(contents.size(), max_output));
  const bool complete = WriteFile(file.value, contents.data(), size, &written, nullptr) && written == size && FlushFileBuffers(file.value);
  if (!complete) { CloseHandle(file.value); file.value = nullptr; DeleteFileW(native_path.c_str()); }
  return complete;
}
bool valid_identity(const crash_detail::Context& context) {
  if (!context.pid || !context.created || context.observed < context.created
      || (context.client != ClientKind::Lunar && context.client != ClientKind::Badlion
          && context.client != ClientKind::Vanilla && context.client != ClientKind::Forge)
      || (context.runtime != "lunar" && context.runtime != "vanilla") || context.payload_hash.size() != 64) return false;
  for (char c : context.payload_hash) if (!std::isxdigit(static_cast<unsigned char>(c))) return false;
  return true;
}
}  // namespace

namespace crash_detail {
std::uint64_t filetime(const FILETIME& value) noexcept {
  return static_cast<std::uint64_t>(value.dwHighDateTime) << 32 | value.dwLowDateTime;
}
bool valid_destination(const std::filesystem::path& path, bool system_desktop) {
  if (local_path(path)) return true;
  const auto value = path.wstring();
  if (!system_desktop || !path.is_absolute() || value.size() >= max_path || !value.starts_with(L"\\\\")
      || value.starts_with(L"\\\\?\\") || value.starts_with(L"\\\\.\\")
      || value.find_first_of(L"\r\n\0:", 0, 4) != std::wstring::npos) return false;
  const auto share = value.find(L'\\', 2);
  if (share == std::wstring::npos || share == 2 || share + 1 == value.size()) return false;
  for (const auto& part : path.relative_path()) if (part == L"." || part == L"..") return false;
  return true;
}
bool same_process(HANDLE target, const Context& context) noexcept {
  FILETIME created{}, exited{}, kernel{}, user{};
  return valid(target) && GetProcessId(target) == context.pid
      && GetProcessTimes(target, &created, &exited, &kernel, &user) && filetime(created) == context.created;
}
Context capture(HANDLE target, ClientKind client, std::string runtime, std::string payload_hash) {
  Context context; FILETIME created{}, exited{}, kernel{}, user{};
  if (!GetProcessTimes(target, &created, &exited, &kernel, &user)) return context;
  context.pid = GetProcessId(target); context.created = filetime(created); context.observed = now_filetime();
  context.client = client; context.runtime = std::move(runtime); context.payload_hash = std::move(payload_hash);
  game_paths(target, context);
  add_root(context, environment(L"TEMP")); add_root(context, environment(L"TMP"));
  return context;
}
std::string diagnostic_excerpt(const std::string& text) {
  std::istringstream input(text); std::string line, output; unsigned frames = 0;
  while (std::getline(input, line) && output.size() < 12288) {
    if (line.size() > 4096 || sensitive(line)) continue;
    auto start = line.find_first_not_of(" \t#\r");
    if (start == std::string::npos) continue;
    line.erase(0, start); while (!line.empty() && (line.back() == '\r' || line.back() == ' ')) line.pop_back();
    for (const char* reason : {"EXCEPTION_ACCESS_VIOLATION", "EXCEPTION_STACK_OVERFLOW", "EXCEPTION_ILLEGAL_INSTRUCTION",
                               "EXCEPTION_INT_DIVIDE_BY_ZERO", "EXCEPTION_FLT", "OutOfMemoryError", "Native memory allocation"})
      if (line.find(reason) != std::string::npos && output.find(reason) == std::string::npos) output += std::string("Condition: ") + reason + "\n";
    for (const char* exception : {"java.lang.NullPointerException", "java.lang.IllegalStateException", "java.lang.RuntimeException",
                                  "java.lang.StackOverflowError", "java.lang.ArrayIndexOutOfBoundsException", "java.lang.NoClassDefFoundError",
                                  "java.lang.LinkageError", "java.util.ConcurrentModificationException"})
      if (line.find(exception) != std::string::npos && output.find(exception) == std::string::npos)
        output += std::string("Java exception: ") + exception + "\n";
    if (frames < 64 && safe_frame(line)) { output += line + '\n'; ++frames; }
    // Keep only trusted module names and a hexadecimal offset, never its path.
    if (line.starts_with("C  [") || line.starts_with("V  [")) {
      auto bracket = line.find('['), plus = line.find('+', bracket), close = line.find(']', bracket);
      if (plus == std::string::npos || close == std::string::npos || close < plus) continue;
      auto module = lower(line.substr(bracket + 1, plus - bracket - 1));
      const std::array<std::string_view, 10> known{"adnin.dll", "adninvanilla.dll", "jvm.dll", "java.dll", "lwjgl64.dll",
          "ntdll.dll", "kernel32.dll", "kernelbase.dll", "nvoglv64.dll", "atio6axx.dll"};
      auto offset = line.substr(plus + 1, close - plus - 1);
      if (std::find(known.begin(), known.end(), module) == known.end() || offset.size() > 18 || !offset.starts_with("0x")) continue;
      if (offset.find_first_not_of("0123456789abcdefABCDEF", 2) != std::string::npos) continue;
      output += "Native frame: " + module + "+" + offset + '\n';
    }
  }
  return output.empty() ? "No allowlisted diagnostic frames in the bounded excerpt.\n" : output;
}
int wait_and_export(HANDLE target, const Context& context, const std::filesystem::path& test_output, DWORD settle_ms) {
  if (!valid_identity(context) || !same_process(target, context)) return 2;
  const std::wstring name = L"Local\\Adnin.CrashWatch." + std::to_wstring(context.pid) + L"." + std::to_wstring(context.created);
  Handle mutex(CreateMutexW(nullptr, FALSE, name.c_str())); if (!valid(mutex.value)) return 2;
  DWORD lock = WaitForSingleObject(mutex.value, 0);
  if (lock == WAIT_TIMEOUT) return 0;
  if (lock != WAIT_OBJECT_0 && lock != WAIT_ABANDONED) return 2;
  struct Release { HANDLE handle; ~Release() { ReleaseMutex(handle); } } release{mutex.value};
  // One kernel wait, no polling. Exit code 259 is valid after this is signaled.
  if (WaitForSingleObject(target, INFINITE) != WAIT_OBJECT_0 || !same_process(target, context)) return 2;
  DWORD code = 0; FILETIME created{}, exited{}, kernel{}, user{};
  if (!GetExitCodeProcess(target, &code) || !GetProcessTimes(target, &created, &exited, &kernel, &user)) return 2;
  if (settle_ms) Sleep(std::min<DWORD>(settle_ms, 2000));
  bool fatal = false; const auto extra = collect(context, filetime(exited), fatal, code != 0);
  if (code == 0 && !fatal) return 0;
  std::ostringstream log;
  log << "Adnin automatic crash diagnostics\nFormat: 1\nResult: Abnormal exit / cause unconfirmed\n"
      << "This report does not establish that Adnin caused the exit.\n"
      << "Client: " << client_name(context.client) << "\nRuntime profile: " << context.runtime
      << "\nPayload SHA-256: " << context.payload_hash << "\nProcess ID: " << context.pid
      << "\nProcess started (UTC): " << timestamp(context.created) << "\nMonitoring began (UTC): " << timestamp(context.observed)
      << "\nProcess exited (UTC): " << timestamp(filetime(exited)) << "\nExit code: 0x"
      << std::hex << std::setw(8) << std::setfill('0') << code << std::dec << " (" << code << ")\n"
      << "PID-matched JVM fatal evidence: " << (fatal ? "yes" : "no") << '\n'
      << "Privacy: diagnostic excerpts only; no memory dump, chat, command line, account data, credentials, URLs or upload.\n"
      << "Old/arbitrary client logs and configuration files are omitted; any game-directory candidate is explicitly unconfirmed.\n" << extra;
  return export_report(log.str(), context, filetime(exited), test_output) ? 0 : 3;
}
bool launch(HANDLE target, const Context& context, const std::filesystem::path& executable, const std::filesystem::path& test_output) {
  if (!valid_identity(context) || !same_process(target, context)) return false;
  Wire wire{}; wire.magic = magic; wire.size = sizeof(wire); wire.pid = context.pid;
  wire.client = static_cast<DWORD>(context.client); wire.created = context.created; wire.observed = context.observed;
  if (!copy(wire.runtime, context.runtime) || !copy(wire.payload_hash, context.payload_hash)
      || !copy(wire.game_directory, context.game_directory) || !copy(wire.error_file, context.error_file)
      || !copy(wire.test_output, test_output)) return false;
  for (const auto& root : context.roots) {
    if (wire.root_count == max_roots) break;
    if (!copy(wire.roots[wire.root_count++], root)) return false;
  }
  Handle process, mapping;
  if (!DuplicateHandle(GetCurrentProcess(), target, GetCurrentProcess(), &process.value,
                       SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION, TRUE, 0)) return false;
  Handle original(CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0, sizeof(wire), nullptr));
  if (!valid(original.value)) return false;
  { View view(MapViewOfFile(original.value, FILE_MAP_WRITE, 0, 0, sizeof(wire)));
    if (!view.value) return false;
    std::memcpy(view.value, &wire, sizeof(wire)); }
  if (!DuplicateHandle(GetCurrentProcess(), original.value, GetCurrentProcess(), &mapping.value, FILE_MAP_READ, TRUE, 0)) return false;
  SIZE_T bytes = 0; InitializeProcThreadAttributeList(nullptr, 1, 0, &bytes);
  std::vector<unsigned char> storage(bytes);
  auto attributes = reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(storage.data());
  if (!InitializeProcThreadAttributeList(attributes, 1, 0, &bytes)) return false;
  struct Delete { LPPROC_THREAD_ATTRIBUTE_LIST value; ~Delete() { DeleteProcThreadAttributeList(value); } } remove{attributes};
  HANDLE handles[]{process.value, mapping.value};
  if (!UpdateProcThreadAttribute(attributes, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, handles, sizeof(handles), nullptr, nullptr)) return false;
  STARTUPINFOEXW startup{}; startup.StartupInfo.cb = sizeof(startup);
  startup.StartupInfo.dwFlags = STARTF_USESHOWWINDOW; startup.StartupInfo.wShowWindow = SW_HIDE;
  startup.lpAttributeList = attributes;
  std::wstring command = L"\"" + executable.wstring() + L"\" " + worker_flag + L" "
      + std::to_wstring(reinterpret_cast<std::uintptr_t>(process.value)) + L" "
      + std::to_wstring(reinterpret_cast<std::uintptr_t>(mapping.value));
  PROCESS_INFORMATION child{};
  if (!CreateProcessW(executable.c_str(), command.data(), nullptr, nullptr, TRUE,
                      EXTENDED_STARTUPINFO_PRESENT | CREATE_NO_WINDOW, nullptr, nullptr, &startup.StartupInfo, &child)) return false;
  CloseHandle(child.hThread); CloseHandle(child.hProcess); return true;
}
}  // namespace crash_detail

bool start_crash_monitor(HANDLE target, ClientKind client, const RuntimeProfile& profile) noexcept {
  try {
    std::wstring executable(32768, L'\0');
    DWORD length = GetModuleFileNameW(nullptr, executable.data(), static_cast<DWORD>(executable.size()));
    if (!length || length >= executable.size()) return false;
    executable.resize(length);
    return crash_detail::launch(target, crash_detail::capture(target, client, profile.id, profile.sha256), executable);
  } catch (...) { return false; }
}
int crash_monitor_entry(int argc, wchar_t** argv) noexcept {
  if (argc < 2 || std::wstring_view(argv[1]) != worker_flag) return -1;
  try {
    std::uint64_t process_value = 0, mapping_value = 0;
    if (argc != 4 || !decimal(argv[2], process_value) || !decimal(argv[3], mapping_value) || !process_value || !mapping_value) return 2;
    Handle process(reinterpret_cast<HANDLE>(static_cast<std::uintptr_t>(process_value)));
    Handle mapping(reinterpret_cast<HANDLE>(static_cast<std::uintptr_t>(mapping_value)));
    View view(MapViewOfFile(mapping.value, FILE_MAP_READ, 0, 0, sizeof(Wire))); if (!view.value) return 2;
    Wire wire{}; std::memcpy(&wire, view.value, sizeof(wire));
    if (wire.magic != magic || wire.size != sizeof(wire) || wire.root_count > max_roots
        || wire.runtime[23] || wire.payload_hash[64] || wire.game_directory[max_path-1]
        || wire.error_file[max_path-1] || wire.test_output[max_path-1]) return 2;
    crash_detail::Context context; context.pid = wire.pid; context.created = wire.created; context.observed = wire.observed;
    context.client = static_cast<ClientKind>(wire.client); context.runtime = wire.runtime; context.payload_hash = wire.payload_hash;
    context.game_directory = wire.game_directory; context.error_file = wire.error_file;
    for (DWORD i = 0; i < wire.root_count; ++i) {
      if (wire.roots[i][max_path-1]) return 2;
      if (ordinary_directory(wire.roots[i])) context.roots.emplace_back(wire.roots[i]);
    }
    return crash_detail::wait_and_export(process.value, context, wire.test_output);
  } catch (...) { return 2; }
}
}  // namespace adnin
