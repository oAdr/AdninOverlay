#pragma once
#include <windows.h>
#include <filesystem>
#include <optional>
#include <string>
#include <vector>

namespace adnin {
inline constexpr int export_button_id = 1001;
inline constexpr int close_button_id = 1002;
inline constexpr int countdown_label_id = 1003;
inline constexpr wchar_t result_window_class[] = L"AdninInjectionResult";

// Only values produced by this attempt are recorded. No command line, path,
// process title, API response, configuration file or chat text enters a report.
struct ResultReport {
  int status = 0;
  DWORD system_error = 0;
  bool preview = false;
  SYSTEMTIME started_utc{};
  ULONGLONG elapsed_ms = 0;
  std::optional<DWORD> target_pid;
  DWORD timeout_ms = 0;
  std::string selected_client;        // Fixed enum label, never a window title.
  std::string runtime_profile;        // Generated build-owned profile id.
  bool title_version_confirmed = false;
  bool runtime_checked = false;
  bool runtime_classes = false;
  bool runtime_pump = false;
  bool runtime_pump_required = true;
  bool runtime_hooks = false;
  bool runtime_heartbeat = false;
  std::string failure;                // A fixed injector diagnostic, never external text.
  std::vector<std::string> events;    // Fixed stage labels, never external text.
};

std::string diagnostic_text(const ResultReport& report);
// export_directory is used only by the isolated UI test harness. The injector
// leaves it empty so an explicit Export log click writes to the user's Desktop.
// Success stays fully visible for two seconds before fading out. Failure stays
// open until closed; all manual close paths share the same fade-out transition.
// Success does not activate its window or take keyboard focus from the game.
bool show_result_window(const ResultReport& report,
                        const std::filesystem::path& export_directory = {});
}  // namespace adnin
