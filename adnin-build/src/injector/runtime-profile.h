#pragma once
#include <windows.h>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>

namespace adnin {
enum class PayloadKind { Lunar, Vanilla, Forge };
// Keep Unknown's historical numeric value stable for crash-monitor records;
// Forge has its own named-class/SRG payload.
enum class ClientKind { Auto, Lunar, Badlion, Vanilla, Unknown, Forge };
enum class TitleVersion { Unspecified, Supported, Unsupported };

struct RuntimeCheck { std::uint32_t rva; std::uint8_t width; };
struct RuntimeSignature { std::uint32_t rva; std::span<const unsigned char> bytes; };
// Generated descriptors contain only build-owned constants, never user input.
struct RuntimeProfile {
  PayloadKind kind;
  const char* id;
  WORD resource_id;
  const char* sha256;
  std::uint32_t payload_size;
  std::uint32_t pe_header_offset;
  std::uint32_t image_size;
  std::uint32_t marker_rva;
  std::uint64_t marker;
  std::span<const RuntimeSignature> signatures;
  std::span<const RuntimeCheck> classes;
  std::span<const RuntimeCheck> pump;
  std::span<const RuntimeCheck> hooks;
  std::uint32_t heartbeat_rva;
  std::uint8_t heartbeat_width;
};

bool valid_runtime_profile(const RuntimeProfile& profile) noexcept;
bool known_payload_module(std::wstring_view module_name) noexcept;
struct LoadedModule {
  std::uintptr_t base;
  std::wstring path;
  std::wstring name;
};
struct ModuleChoice { std::optional<std::uintptr_t> base; bool conflict = false; };
ModuleChoice choose_loaded_module(std::span<const LoadedModule> modules, std::wstring_view path,
                                 bool reject_other_copy);
const char* client_name(ClientKind kind) noexcept;
std::optional<ClientKind> parse_client(std::wstring_view text) noexcept;
std::optional<PayloadKind> client_payload(ClientKind kind) noexcept;

struct WindowCandidate {
  DWORD pid;
  std::wstring title;
  std::wstring window_class;
  bool visible;
  // Set by the injector after a bounded, read-only process command-line
  // probe. Tests and library callers may leave this false.
  bool forge_process = false;
};
struct WindowIdentity { ClientKind client; TitleVersion version; };
WindowIdentity classify_window(std::wstring_view title, std::wstring_view window_class);
enum class TargetError { None, NotFound, Ambiguous, ClientMismatch, UnsupportedVersion };
struct TargetChoice {
  DWORD pid = 0;
  ClientKind client = ClientKind::Unknown;
  TitleVersion version = TitleVersion::Unspecified;
  TargetError error = TargetError::NotFound;
};
TargetChoice choose_target(std::span<const WindowCandidate> windows, ClientKind requested,
                          std::optional<DWORD> pid = {},
                          std::optional<std::wstring_view> window_class = {});
}  // namespace adnin
