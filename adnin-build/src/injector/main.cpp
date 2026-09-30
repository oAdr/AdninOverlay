#include <windows.h>
#include <tlhelp32.h>
#include <shellapi.h>
#include <cstdio>

#include <algorithm>
#include <array>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>
#include "adnin-runtimes.h"
#include "payload.h"
#include "result-ui.h"
#include "remote-lease.h"
#include "crash-monitor.h"

namespace {
class Handle {
 public:
  explicit Handle(HANDLE value = nullptr) noexcept : value_(value) {}
  ~Handle() {
    if (valid()) CloseHandle(value_);
  }
  Handle(const Handle&) = delete;
  Handle& operator=(const Handle&) = delete;
  HANDLE get() const noexcept { return value_; }
  bool valid() const noexcept { return value_ && value_ != INVALID_HANDLE_VALUE; }

 private:
  HANDLE value_;
};
struct Failure : std::runtime_error {
  int status;
  DWORD system_error;
  Failure(int code, const char* message, DWORD error = 0)
      : std::runtime_error(message), status(code), system_error(error) {}
};
[[noreturn]] void winfail(int code, const char* message) {
  const DWORD error = GetLastError();
  throw Failure(code, message, error);
}
class AttemptLock {
 public:
  AttemptLock(DWORD pid, bool required) {
    if (!required) return;
    const std::wstring name = L"Local\\Adnin.Inject." + std::to_wstring(pid);
    mutex_.emplace(CreateMutexW(nullptr, FALSE, name.c_str()));
    if (!mutex_->valid()) winfail(5, "Cannot lock the target injection attempt");
    const DWORD wait = WaitForSingleObject(mutex_->get(), 0);
    if (wait == WAIT_OBJECT_0 || wait == WAIT_ABANDONED) owned_ = true;
    else if (wait == WAIT_TIMEOUT) throw Failure(9, "Another Adnin injection is already running for this process");
    else winfail(5, "Cannot acquire the target injection lock");
  }
  ~AttemptLock() { if (owned_) ReleaseMutex(mutex_->get()); }
  AttemptLock(const AttemptLock&) = delete;
  AttemptLock& operator=(const AttemptLock&) = delete;
 private:
  std::optional<Handle> mutex_;
  bool owned_ = false;
};
std::wstring module_path(HMODULE module) {
  std::vector<wchar_t> buffer(32768);
  const DWORD length = GetModuleFileNameW(module, buffer.data(), static_cast<DWORD>(buffer.size()));
  if (!length || length >= buffer.size()) winfail(2, "Cannot resolve module path");
  return {buffer.data(), length};
}
DWORD number(const std::wstring& text) {
  if (text.empty() || text.find_first_not_of(L"0123456789") != std::wstring::npos)
    throw Failure(2, "Expected a positive decimal integer");
  unsigned long long value = 0;
  try {
    value = std::stoull(text);
  } catch (...) {
    throw Failure(2, "Integer out of range");
  }
  if (!value || value > MAXDWORD) throw Failure(2, "Integer out of range");
  return static_cast<DWORD>(value);
}
struct Options {
  std::optional<DWORD> pid;
  std::optional<std::filesystem::path> dll;
  adnin::ClientKind client = adnin::ClientKind::Auto;
  DWORD timeout_ms = 15000;
  bool inject = false;
  bool dry_run = false;
  bool interactive = false;
  std::optional<std::wstring> window_class;
  std::optional<bool> preview_success;
};
Options parse(int argc, wchar_t** argv) {
  Options options;
  options.interactive = argc == 1;
  options.inject = options.interactive;
  for (int i = 1; i < argc; ++i) {
    const std::wstring arg = argv[i];
    auto value = [&]() -> std::wstring {
      if (++i == argc) throw Failure(2, "Missing option value");
      return argv[i];
    };
    if (arg == L"--pid")
      options.pid = number(value());
    else if (arg == L"--dll")
      options.dll = value();
    else if (arg == L"--client") {
      const auto client = adnin::parse_client(value());
      if (!client) throw Failure(2, "Client must be auto, lunar, badlion or vanilla");
      options.client = *client;
    }
    else if (arg == L"--timeout-ms")
      options.timeout_ms = number(value());
    else if (arg == L"--window-class")
      options.window_class = value();
    else if (arg == L"--inject")
      options.inject = true;
    else if (arg == L"--dry-run")
      options.dry_run = true;
    else if (arg == L"--preview-ui") {
      const auto state = value();
      if (state != L"success" && state != L"failed") throw Failure(2, "Preview must be success or failed");
      options.preview_success = state == L"success";
    }
    else
      throw Failure(2, "Unknown option; use --help");
  }
  if (options.inject && options.dry_run) throw Failure(2, "--inject and --dry-run are mutually exclusive");
  if (options.pid && options.window_class) throw Failure(2, "Choose --pid or --window-class");
  if (options.timeout_ms > 300000) throw Failure(2, "Timeout must be 1..300000 milliseconds");
  if (options.window_class && options.window_class->empty()) throw Failure(2, "Empty window class");
  if (options.preview_success.has_value() && argc != 3)
    throw Failure(2, "--preview-ui accepts only success or failed and cannot be combined with injection options");
  return options;
}
struct WindowSearch {
  std::vector<std::wstring> classes;
  std::vector<DWORD> pids;
  bool lunar_only;
};
BOOL CALLBACK visit(HWND window, LPARAM context) {
  if (!IsWindowVisible(window)) return TRUE;
  std::array<wchar_t, 256> cls{};
  if (!GetClassNameW(window, cls.data(), static_cast<int>(cls.size()))) return TRUE;
  auto& result = *reinterpret_cast<WindowSearch*>(context);
  if (result.lunar_only) {
    std::array<wchar_t, 512> title{};
    if (!GetWindowTextW(window, title.data(), static_cast<int>(title.size())) ||
        std::wstring(title.data()).find(L"Lunar Client") != 0)
      return TRUE;
  }
  if (std::find(result.classes.begin(), result.classes.end(), cls.data()) != result.classes.end()) {
    DWORD pid = 0;
    GetWindowThreadProcessId(window, &pid);
    if (pid && std::find(result.pids.begin(), result.pids.end(), pid) == result.pids.end())
      result.pids.push_back(pid);
  }
  return TRUE;
}
DWORD discover_development_target(const Options& options) {
  if (options.pid) return *options.pid;
  // Verified at VA 0x14000436c, 0x140004374 and 0x14000437c in the input EXE.
  WindowSearch result{options.window_class ? std::vector<std::wstring>{*options.window_class}
                                           : std::vector<std::wstring>{L"LWJGL", L"GLFW30", L"GLFW32"},
                      {}, false};
  EnumWindows(visit, reinterpret_cast<LPARAM>(&result));
  if (result.pids.empty()) throw Failure(3, "No matching visible window; use --pid for an explicit target");
  if (result.pids.size() != 1) throw Failure(3, "Multiple matching processes; use --pid");
  return result.pids.front();
}
BOOL CALLBACK visit_candidates(HWND window, LPARAM context) {
  if (!IsWindowVisible(window)) return TRUE;
  std::array<wchar_t, 256> cls{};
  std::array<wchar_t, 512> title{};
  const int length = GetWindowTextLengthW(window);
  if (length <= 0 || length >= static_cast<int>(title.size()) ||
      !GetClassNameW(window, cls.data(), static_cast<int>(cls.size())) ||
      !GetWindowTextW(window, title.data(), static_cast<int>(title.size()))) return TRUE;
  DWORD pid = 0;
  GetWindowThreadProcessId(window, &pid);
  auto& candidates = *reinterpret_cast<std::vector<adnin::WindowCandidate>*>(context);
  candidates.push_back({pid, title.data(), cls.data(), true});
  return TRUE;
}
adnin::TargetChoice discover(const Options& options) {
  std::vector<adnin::WindowCandidate> candidates;
  if (!EnumWindows(visit_candidates, reinterpret_cast<LPARAM>(&candidates)))
    winfail(3, "Cannot enumerate game windows");
  std::optional<std::wstring_view> window_class;
  if (options.window_class) window_class = *options.window_class;
  const auto selected = adnin::choose_target(candidates, options.client, options.pid, window_class);
  switch (selected.error) {
    case adnin::TargetError::None: return selected;
    case adnin::TargetError::Ambiguous: throw Failure(3, "Multiple matching game processes; use --pid and --client");
    case adnin::TargetError::ClientMismatch: throw Failure(3, "The selected process does not match --client");
    case adnin::TargetError::UnsupportedVersion: throw Failure(3, "The selected game version is unsupported; Minecraft 1.8.9 is required");
    default: throw Failure(3, "No recognized visible Lunar, Badlion or Vanilla game window was found");
  }
}
const adnin::RuntimeProfile& runtime_profile(adnin::ClientKind client) {
  const auto kind = adnin::client_payload(client);
  const adnin::RuntimeProfile* found = nullptr;
  for (const auto& profile : ADNIN_RUNTIMES) if (kind && profile.kind == *kind) {
    if (found || !adnin::valid_runtime_profile(profile))
      throw Failure(4, "Embedded runtime metadata is invalid");
    found = &profile;
  }
  if (!found) throw Failure(4, "The selected client has no verified embedded runtime");
  return *found;
}
void validate_dll(const std::filesystem::path& path) {
  std::ifstream file(path, std::ios::binary);
  if (!file) throw Failure(4, "DLL file is missing or unreadable");
  file.seekg(0, std::ios::end);
  const auto size = file.tellg();
  file.seekg(0);
  IMAGE_DOS_HEADER dos{};
  file.read(reinterpret_cast<char*>(&dos), sizeof(dos));
  if (!file || dos.e_magic != IMAGE_DOS_SIGNATURE || dos.e_lfanew < static_cast<LONG>(sizeof(dos)) ||
      static_cast<std::streamoff>(dos.e_lfanew) + static_cast<std::streamoff>(sizeof(IMAGE_NT_HEADERS64)) >
          size)
    throw Failure(4, "Invalid PE DLL header");
  IMAGE_NT_HEADERS64 pe{};
  file.seekg(dos.e_lfanew);
  file.read(reinterpret_cast<char*>(&pe), sizeof(pe));
  if (!file || pe.Signature != IMAGE_NT_SIGNATURE || pe.FileHeader.Machine != IMAGE_FILE_MACHINE_AMD64 ||
      !(pe.FileHeader.Characteristics & IMAGE_FILE_DLL) ||
      pe.OptionalHeader.Magic != IMAGE_NT_OPTIONAL_HDR64_MAGIC)
    throw Failure(4, "DLL must be a native x64 PE DLL");
}
void validate_target(HANDLE process) {
  BOOL wow64 = FALSE;
  SYSTEM_INFO system{};
  GetNativeSystemInfo(&system);
  if (!IsWow64Process(process, &wow64)) winfail(5, "Cannot check target architecture");
  if (system.wProcessorArchitecture != PROCESSOR_ARCHITECTURE_AMD64 || wow64)
    throw Failure(5, "Only native x64 targets on x64 Windows are supported");
  if (WaitForSingleObject(process, 0) != WAIT_TIMEOUT) throw Failure(5, "Target process has exited");
}
std::optional<std::uintptr_t> target_module(DWORD pid, const std::filesystem::path& path,
                                          bool reject_other_copy = false) {
  HANDLE raw = INVALID_HANDLE_VALUE;
  for (int attempt = 0; attempt < 5; ++attempt) {
    raw = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, pid);
    if (raw != INVALID_HANDLE_VALUE || GetLastError() != ERROR_BAD_LENGTH) break;
    Sleep(1);
  }
  Handle snapshot(raw);
  if (!snapshot.valid()) winfail(5, "Cannot enumerate target modules");
  MODULEENTRY32W entry{};
  entry.dwSize = sizeof(entry);
  if (!Module32FirstW(snapshot.get(), &entry)) winfail(5, "Cannot read target module list");
  std::vector<adnin::LoadedModule> modules;
  do {
    modules.push_back({reinterpret_cast<std::uintptr_t>(entry.modBaseAddr), entry.szExePath, entry.szModule});
  } while (Module32NextW(snapshot.get(), &entry));
  if (GetLastError() != ERROR_NO_MORE_FILES) winfail(5, "Target module enumeration failed");
  const auto selected = adnin::choose_loaded_module(modules, path.wstring(), reject_other_copy);
  if (selected.conflict) throw Failure(9, "Another Adnin or previous ChatReader DLL is loaded. Restart the game before switching builds or clients");
  return selected.base;
}
template <class T>
T remote_value(HANDLE process, std::uintptr_t address) {
  T value{};
  SIZE_T read = 0;
  if (!ReadProcessMemory(process, reinterpret_cast<const void*>(address), &value, sizeof(value), &read) ||
      read != sizeof(value))
    winfail(8, "Cannot read DLL initialization status");
  return value;
}
std::uint64_t runtime_value(HANDLE process, std::uintptr_t base, const adnin::RuntimeCheck& check) {
  switch (check.width) {
    case 1: return remote_value<std::uint8_t>(process, base + check.rva);
    case 4: return remote_value<std::uint32_t>(process, base + check.rva);
    case 8: return remote_value<std::uint64_t>(process, base + check.rva);
    default: throw Failure(8, "Invalid runtime status width");
  }
}
void wait_for_runtime(HANDLE process, std::uintptr_t base, const adnin::RuntimeProfile& profile,
                      DWORD timeout_ms, adnin::ResultReport& report) {
  if (!adnin::valid_runtime_profile(profile)) throw Failure(8, "Invalid runtime verification profile");
  const auto dos = remote_value<IMAGE_DOS_HEADER>(process, base);
  if (dos.e_magic != IMAGE_DOS_SIGNATURE || dos.e_lfanew < 0 ||
      static_cast<std::uint32_t>(dos.e_lfanew) != profile.pe_header_offset)
    throw Failure(8, "Unrecognized Adnin image; initialization cannot be verified");
  const auto pe = remote_value<IMAGE_NT_HEADERS64>(process, base + profile.pe_header_offset);
  if (pe.Signature != IMAGE_NT_SIGNATURE || pe.FileHeader.Machine != IMAGE_FILE_MACHINE_AMD64 ||
      pe.OptionalHeader.Magic != IMAGE_NT_OPTIONAL_HDR64_MAGIC ||
      !(pe.FileHeader.Characteristics & IMAGE_FILE_DLL) || pe.OptionalHeader.SizeOfImage != profile.image_size ||
      remote_value<std::uint64_t>(process, base + profile.marker_rva) != profile.marker)
    throw Failure(8, "Old or mismatched runtime DLL is loaded. Restart the game and run the current Adnin executable");
  for (const auto& signature : profile.signatures) {
    std::vector<unsigned char> actual(signature.bytes.size());
    SIZE_T read = 0;
    if (!ReadProcessMemory(process, reinterpret_cast<const void*>(base + signature.rva),
                           actual.data(), actual.size(), &read) || read != actual.size())
      winfail(8, "Cannot verify runtime image signature");
    if (!std::equal(actual.begin(), actual.end(), signature.bytes.begin()))
      throw Failure(8, "Runtime image signature does not match the selected client");
  }
  const auto deadline = GetTickCount64() + timeout_ms;
  std::optional<std::uint64_t> previous_tick;
  bool classes = false, pump = false, hooks = false;
  report.runtime_checked = true;
  report.runtime_pump_required = !profile.pump.empty();
  report.events.emplace_back("Checking Adnin runtime initialization");
  do {
    validate_target(process);
    auto all_ready = [&](std::span<const adnin::RuntimeCheck> checks) {
      return std::all_of(checks.begin(), checks.end(), [&](const adnin::RuntimeCheck& check) {
        return runtime_value(process, base, check) != 0;
      });
    };
    classes = all_ready(profile.classes);
    pump = all_ready(profile.pump);
    hooks = all_ready(profile.hooks);
    const auto tick = runtime_value(process, base, {profile.heartbeat_rva, profile.heartbeat_width});
    report.runtime_classes = classes;
    report.runtime_pump = pump;
    report.runtime_hooks = hooks;
    if (classes && pump && hooks && previous_tick && tick > *previous_tick) {
      report.runtime_heartbeat = true;
      report.events.emplace_back("Runtime initialization and advancing heartbeat verified");
      std::cout << "Runtime ready: Minecraft/GUI resolved, hooks installed, runtime heartbeat advancing.\n";
      return;
    }
    previous_tick = tick;
    Sleep(50);
  } while (GetTickCount64() < deadline);
  std::cerr << "Initialization status: classes=" << classes << " pump=" << pump << " hooks=" << hooks << '\n';
  throw Failure(8, "DLL loaded but Minecraft/GUI initialization failed. Restart the game before retrying; /config is not ready");
}
LPTHREAD_START_ROUTINE remote_loader(DWORD pid) {
  const auto local = GetProcAddress(GetModuleHandleW(L"kernel32.dll"), "LoadLibraryW");
  if (!local) winfail(5, "LoadLibraryW export is unavailable");
  HMODULE owner = nullptr;
  if (!GetModuleHandleExW(
          GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
          reinterpret_cast<LPCWSTR>(local), &owner))
    winfail(5, "Cannot find loader module");
  const auto remote = target_module(pid, module_path(owner));
  if (!remote) throw Failure(5, "Loader module path differs in target");
  const auto offset = reinterpret_cast<std::uintptr_t>(local) - reinterpret_cast<std::uintptr_t>(owner);
  return reinterpret_cast<LPTHREAD_START_ROUTINE>(*remote + offset);
}
class RemotePath {
 public:
  RemotePath(HANDLE process, void* address) : process_(process), address_(address) {}
  ~RemotePath() {
    if (address_) VirtualFreeEx(process_, address_, 0, MEM_RELEASE);
  }
  RemotePath(const RemotePath&) = delete;
  RemotePath& operator=(const RemotePath&) = delete;
  void retain_for_running_thread() noexcept { address_ = nullptr; }

 private:
  HANDLE process_;  // The process owner outlives this allocation.
  void* address_;
};
int execute(const Options& options, adnin::ResultReport& report) {
  report.timeout_ms = options.timeout_ms;
  adnin::TargetChoice target;
  std::filesystem::path dll;
  // An explicit --dll is the development/test path. It still has to match the
  // selected generated payload and pass that payload's complete handshake.
  if (options.dll) {
    dll = std::filesystem::absolute(*options.dll).lexically_normal();
    validate_dll(dll);
    target = {discover_development_target(options),
              options.client == adnin::ClientKind::Auto ? adnin::ClientKind::Lunar : options.client,
              adnin::TitleVersion::Unspecified, adnin::TargetError::None};
  } else {
    report.events.emplace_back("Identifying a recognized game window and checking its advertised version");
    target = discover(options);
  }
  const auto& profile = runtime_profile(target.client);
  report.selected_client = adnin::client_name(target.client);
  report.runtime_profile = profile.id;
  report.title_version_confirmed = target.version == adnin::TitleVersion::Supported;
  const DWORD pid = target.pid;
  report.target_pid = pid;
  if (pid == GetCurrentProcessId()) throw Failure(5, "Self-injection is not supported");
  const DWORD read_rights = PROCESS_QUERY_INFORMATION | PROCESS_VM_READ | SYNCHRONIZE;
  const DWORD write_rights = PROCESS_CREATE_THREAD | PROCESS_VM_OPERATION | PROCESS_VM_WRITE | PROCESS_DUP_HANDLE;
  Handle process(OpenProcess(read_rights | (options.inject ? write_rights : 0), FALSE, pid));
  if (!process.valid()) winfail(5, "Cannot open target process");
  validate_target(process.get());
  report.events.emplace_back("Target access, architecture and liveness verified");
  AttemptLock attempt(pid, options.inject);
  std::optional<adnin::PayloadFile> payload;
  if (!options.dll) {
    report.events.emplace_back("Verifying embedded payload and its content-addressed cache");
    payload.emplace(adnin::embedded_payload(profile));
    dll = payload->path();
    report.events.emplace_back("Embedded payload cache verified");
  } else {
    payload.emplace(adnin::verified_payload_file(profile, dll));
    report.events.emplace_back("Development payload matches the selected runtime and is read-locked");
  }
  report.events.emplace_back("Validating native x64 DLL header");
  validate_dll(dll);
  const auto loader = remote_loader(pid);
  auto loaded = target_module(pid, dll, true);
  const bool already_loaded = loaded.has_value();
  report.events.emplace_back("Loader and duplicate-module checks completed");
  std::wcout << L"pid=" << pid << L" dll=" << dll.wstring() << L" mode="
             << (options.inject ? L"inject" : L"dry-run") << L" already_loaded=" << already_loaded << L'\n';
  if (!options.inject) {
    report.events.emplace_back("Dry-run completed without loading a DLL");
    std::cout << "Dry-run completed. No DLL was loaded; use --inject to load it.\n";
    return 0;
  }
  if (already_loaded) {
    report.events.emplace_back("Matching DLL already loaded; no second load attempted");
    std::cout << "DLL is already loaded in this process.\n";
    wait_for_runtime(process.get(), *loaded, profile, options.timeout_ms, report);
    report.events.emplace_back(adnin::start_crash_monitor(process.get(), target.client, profile)
        ? "Background crash diagnostics requested" : "Background crash diagnostics unavailable; injection remains ready");
    return 0;
  }
  const auto dll_text = dll.wstring();
  const SIZE_T bytes = (dll_text.size() + 1) * sizeof(wchar_t);
  void* address = VirtualAllocEx(process.get(), nullptr, bytes, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE);
  if (!address) winfail(5, "Remote path allocation failed");
  RemotePath allocation(process.get(), address);
  SIZE_T written = 0;
  if (!WriteProcessMemory(process.get(), address, dll_text.c_str(), bytes, &written) || written != bytes)
    winfail(5, "Remote path write failed");
  adnin::RemotePayloadLease payload_lease(process.get(), payload ? payload->handle() : nullptr);
  if (!payload_lease.valid())
    throw Failure(5, "Cannot protect the verified payload during remote loading", payload_lease.error());
  Handle thread(CreateRemoteThread(process.get(), nullptr, 0, loader, address, 0, nullptr));
  if (!thread.valid()) winfail(5, "Remote loader thread creation failed");
  report.events.emplace_back("LoadLibraryW thread started");
  const DWORD wait = WaitForSingleObject(thread.get(), options.timeout_ms);
  if (wait != WAIT_OBJECT_0) {
    // The remote thread may still read this path. Never free it or terminate the thread here.
    allocation.retain_for_running_thread();
    payload_lease.retain_for_running_thread();
    report.events.emplace_back("Unresolved load: remote path and any verified payload lease retained until target exit");
    if (wait == WAIT_TIMEOUT)
      throw Failure(6, "Loader timed out; result is unknown. Remote path retained until process exit");
    winfail(5, "Loader wait failed; remote path retained until process exit");
  }
  if (!payload_lease.close())
    throw Failure(5, "Loader finished but its protected payload lease could not be closed", payload_lease.error());
  // A thread exit code is only 32 bits; checking the module list avoids truncating an x64 HMODULE.
  loaded = target_module(pid, dll, true);
  if (!loaded)
    throw Failure(7, "LoadLibraryW completed but DLL is absent from target modules");
  report.events.emplace_back("DLL presence in the target module list verified");
  wait_for_runtime(process.get(), *loaded, profile, options.timeout_ms, report);
  report.events.emplace_back(adnin::start_crash_monitor(process.get(), target.client, profile)
      ? "Background crash diagnostics requested" : "Background crash diagnostics unavailable; injection remains ready");
  return 0;
}
}  // namespace
int application_main(int argc, wchar_t** argv) {
  const bool interactive = argc == 1;
  if (argc == 2 && std::wstring(argv[1]) == L"--help") {
    std::cout << "Adnin [--client auto|lunar|badlion|vanilla] [--pid N | --window-class NAME] [--dll PATH]\n"
                 "  [--dry-run | --inject] [--timeout-ms 1..300000]\n"
                 "Adnin --preview-ui success|failed\n"
                 "No arguments / double-click: select one recognized Lunar, Badlion or Vanilla 1.8.9 game window.\n"
                 "With arguments: dry-run unless --inject is specified.\n"
                 "The matching embedded runtime is verified and extracted to a content-addressed local cache.\n"
                 "Explicit different game versions, unknown windows and ambiguous targets are rejected.\n"
                 "--dll is a verified development path; --client selects its profile (default lunar).\n"
                 "Preview only simulates the result window. It does not extract a DLL or access a target.\n"
                 "Every runtime requires Minecraft/GUI initialization, installed hooks and an advancing heartbeat.\n"
                 "Exit codes: 0 success, 2 usage, 3 target discovery, 4 DLL, 5 process/API, 6 timeout, 7 "
                 "load failure, 8 runtime not ready/old DLL, 9 conflicting DLL copy.\n";
    return 0;
  }
  int status = 0;
  std::string failure;
  adnin::ResultReport report;
  GetSystemTime(&report.started_utc);
  const auto started = GetTickCount64();
  try {
    const auto options = parse(argc, argv);
    if (options.preview_success.has_value()) {
      report.preview = true;
      report.status = *options.preview_success ? 0 : 8;
      report.failure = *options.preview_success ? "" : "Preview failure: no injection was performed";
      report.events.emplace_back("Preview only; no DLL or target process was accessed");
      return adnin::show_result_window(report) ? 0 : 2;
    }
    report.events.emplace_back("Command options validated");
    status = execute(options, report);
  } catch (const Failure& error) {
    failure = std::string(error.what()) + " (win32=" + std::to_string(error.system_error) + ")";
    report.failure = error.what();
    report.system_error = error.system_error;
    status = error.status;
  } catch (const adnin::PayloadError& error) {
    report.failure = error.what();
    report.system_error = error.system_error;
    failure = std::string(error.what()) + " (win32=" + std::to_string(error.system_error) + ")";
    status = 4;
  } catch (const std::exception&) {
    // Generic filesystem exception text can contain paths. Never collect it.
    failure = "Unexpected injector error";
    report.failure = failure;
    status = 2;
  }
  report.status = status;
  report.elapsed_ms = GetTickCount64() - started;
  if (!failure.empty()) std::cerr << failure << '\n';
  if (interactive) adnin::show_result_window(report);
  return status;
}

// A GUI subsystem entry point prevents a console flash on double-click. CLI
// calls retain inherited pipes, or attach to an existing parent console only.
int WINAPI wWinMain(HINSTANCE, HINSTANCE, PWSTR, int) {
  int argc = 0;
  wchar_t** argv = CommandLineToArgvW(GetCommandLineW(), &argc);
  if (!argv) return 2;
  const int monitor = adnin::crash_monitor_entry(argc, argv);
  if (monitor >= 0) { LocalFree(argv); return monitor; }
  const bool preview = argc > 1 && std::wstring(argv[1]) == L"--preview-ui";
  if (argc > 1 && !preview) {
    auto usable = [](DWORD id) {
      HANDLE handle = GetStdHandle(id);
      return handle && handle != INVALID_HANDLE_VALUE && GetFileType(handle) != FILE_TYPE_UNKNOWN;
    };
    const bool output = usable(STD_OUTPUT_HANDLE), error = usable(STD_ERROR_HANDLE);
    if ((!output || !error) && AttachConsole(ATTACH_PARENT_PROCESS)) {
      if (!output) { FILE* stream = std::freopen("CONOUT$", "w", stdout); (void)stream; }
      if (!error) { FILE* stream = std::freopen("CONOUT$", "w", stderr); (void)stream; }
      std::ios::sync_with_stdio(true);
    }
  }
  const int result = application_main(argc, argv);
  LocalFree(argv);
  return result;
}
