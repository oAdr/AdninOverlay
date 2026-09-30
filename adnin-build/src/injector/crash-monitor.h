#pragma once
#include "runtime-profile.h"
#include <windows.h>
#include <cstdint>
#include <filesystem>
#include <string>
#include <vector>

namespace adnin {
// Best effort only: never changes a successful injection into a failure.
bool start_crash_monitor(HANDLE target, ClientKind client, const RuntimeProfile& profile) noexcept;
// -1 means ordinary invocation. Internal mode must run before console/UI setup.
int crash_monitor_entry(int argc, wchar_t** argv) noexcept;

namespace crash_detail {
struct Context {
  DWORD pid = 0;
  std::uint64_t created = 0, observed = 0;
  ClientKind client = ClientKind::Unknown;
  std::string runtime, payload_hash;
  std::vector<std::filesystem::path> roots;
  std::filesystem::path game_directory, error_file;
};
std::uint64_t filetime(const FILETIME& value) noexcept;
Context capture(HANDLE target, ClientKind client, std::string runtime, std::string payload_hash);
bool same_process(HANDLE target, const Context& context) noexcept;
// Test seams use only owned child processes and temporary directories.
bool launch(HANDLE target, const Context& context, const std::filesystem::path& executable,
            const std::filesystem::path& test_output = {});
int wait_and_export(HANDLE target, const Context& context,
                    const std::filesystem::path& test_output = {}, DWORD settle_ms = 1500);
std::string diagnostic_excerpt(const std::string& text);
bool valid_destination(const std::filesystem::path& path, bool system_desktop);
}  // namespace crash_detail
}  // namespace adnin
