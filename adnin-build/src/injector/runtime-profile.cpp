#include "runtime-profile.h"
#include <algorithm>
#include <cstring>
#include <vector>

namespace adnin {
namespace {
bool in_image(std::uint32_t rva, std::size_t width, std::uint32_t image_size) noexcept {
  return width && width <= image_size && rva <= image_size - width;
}
bool valid_width(std::uint8_t width) noexcept { return width == 1 || width == 4 || width == 8; }
wchar_t lower(wchar_t c) noexcept { return c >= L'A' && c <= L'Z' ? c + (L'a' - L'A') : c; }
bool equal(std::wstring_view a, std::wstring_view b) noexcept {
  return a.size() == b.size() && std::equal(a.begin(), a.end(), b.begin(),
      [](wchar_t x, wchar_t y) { return lower(x) == lower(y); });
}
bool prefix(std::wstring_view text, std::wstring_view name) noexcept {
  if (text.size() < name.size() || !equal(text.substr(0, name.size()), name)) return false;
  if (text.size() == name.size()) return true;
  const wchar_t next = text[name.size()];
  return next == L' ' || next == L'(' || next == L'[' || next == L'-' || next == L'|';
}
bool digit(wchar_t c) noexcept { return c >= L'0' && c <= L'9'; }
bool word(wchar_t c) noexcept { return digit(c) || (lower(c) >= L'a' && lower(c) <= L'z') || c == L'_'; }
TitleVersion title_version(std::wstring_view title) noexcept {
  bool supported = false, other_version = false;
  for (std::size_t i = 0; i + 2 < title.size(); ++i) {
    if (digit(title[i]) && (i == 0 || !word(title[i - 1]))) {
      std::size_t dot = i;
      while (dot < title.size() && digit(title[dot])) ++dot;
      if (dot + 1 < title.size() && title[dot] == L'.' && digit(title[dot + 1])) other_version = true;
    }
    if (title[i] != L'1' || title[i + 1] != L'.' || !digit(title[i + 2]) ||
        (i && (word(title[i - 1]) || title[i - 1] == L'.'))) continue;
    std::size_t end = i + 2;
    while (end < title.size() && (digit(title[end]) || title[end] == L'.')) ++end;
    // Reject version suffixes as well as 1.8, 1.8.8, 1.8.90 and modern clients.
    if (title.substr(i, end - i) != L"1.8.9" || (end < title.size() && word(title[end])))
      return TitleVersion::Unsupported;
    supported = true;
    i = end - 1;
  }
  return supported ? TitleVersion::Supported : other_version ? TitleVersion::Unsupported : TitleVersion::Unspecified;
}
}  // namespace

bool valid_runtime_profile(const RuntimeProfile& p) noexcept {
  if ((p.kind != PayloadKind::Lunar && p.kind != PayloadKind::Vanilla) || !p.id || !*p.id ||
      !p.resource_id || !p.sha256 || std::strlen(p.sha256) != 64 || !p.payload_size ||
      !p.image_size || p.pe_header_offset < sizeof(IMAGE_DOS_HEADER) ||
      !in_image(p.pe_header_offset, sizeof(IMAGE_NT_HEADERS64), p.image_size) ||
      !p.marker || !in_image(p.marker_rva, sizeof(p.marker), p.image_size) ||
      p.classes.empty() || p.hooks.empty() || !p.heartbeat_rva ||
      !valid_width(p.heartbeat_width) || !in_image(p.heartbeat_rva, p.heartbeat_width, p.image_size)) return false;
  for (const char* c = p.sha256; *c; ++c)
    if (!((*c >= '0' && *c <= '9') || (*c >= 'a' && *c <= 'f'))) return false;
  for (const auto checks : {p.classes, p.pump, p.hooks})
    for (const auto& check : checks)
      if (!valid_width(check.width) || !in_image(check.rva, check.width, p.image_size)) return false;
  for (const auto& signature : p.signatures)
    if (!in_image(signature.rva, signature.bytes.size(), p.image_size)) return false;
  return true;
}

bool known_payload_module(std::wstring_view name) noexcept {
  if (equal(name, L"ChatReaderLunar.dll") || equal(name, L"ChatReader.dll")) return true;
  return name.size() >= 9 && equal(name.substr(0, 5), L"Adnin") && equal(name.substr(name.size() - 4), L".dll");
}
ModuleChoice choose_loaded_module(std::span<const LoadedModule> modules, std::wstring_view path,
                                 bool reject_other_copy) {
  ModuleChoice choice;
  for (const auto& module : modules) {
    if (equal(module.path, path)) choice.base = module.base;
    else if (reject_other_copy && known_payload_module(module.name)) choice.conflict = true;
  }
  return choice;
}
const char* client_name(ClientKind kind) noexcept {
  switch (kind) {
    case ClientKind::Auto: return "auto";
    case ClientKind::Lunar: return "lunar";
    case ClientKind::Badlion: return "badlion";
    case ClientKind::Vanilla: return "vanilla";
    default: return "unknown";
  }
}
std::optional<ClientKind> parse_client(std::wstring_view text) noexcept {
  if (text == L"auto") return ClientKind::Auto;
  if (text == L"lunar") return ClientKind::Lunar;
  if (text == L"badlion") return ClientKind::Badlion;
  if (text == L"vanilla") return ClientKind::Vanilla;
  return {};
}
std::optional<PayloadKind> client_payload(ClientKind kind) noexcept {
  if (kind == ClientKind::Lunar) return PayloadKind::Lunar;
  if (kind == ClientKind::Badlion || kind == ClientKind::Vanilla) return PayloadKind::Vanilla;
  return {};
}
WindowIdentity classify_window(std::wstring_view title, std::wstring_view window_class) {
  if (window_class != L"LWJGL" && window_class != L"GLFW30" && window_class != L"GLFW32")
    return {ClientKind::Unknown, TitleVersion::Unspecified};
  std::wstring lowered(title);
  std::transform(lowered.begin(), lowered.end(), lowered.begin(), lower);
  if (lowered.find(L"launcher") != std::wstring::npos)
    return {ClientKind::Unknown, TitleVersion::Unspecified};
  ClientKind client = ClientKind::Unknown;
  if (prefix(title, L"Lunar Client")) client = ClientKind::Lunar;
  else if (prefix(title, L"Badlion Client") || prefix(title, L"Badlion Minecraft Client"))
    client = ClientKind::Badlion;
  else if (prefix(title, L"Minecraft")) {
    client = lowered.find(L"badlion client") != std::wstring::npos ||
             lowered.find(L"badlion minecraft client") != std::wstring::npos
        ? ClientKind::Badlion : ClientKind::Vanilla;
  }
  return {client, client == ClientKind::Unknown ? TitleVersion::Unspecified : title_version(title)};
}

TargetChoice choose_target(std::span<const WindowCandidate> windows, ClientKind requested,
                          std::optional<DWORD> pid, std::optional<std::wstring_view> window_class) {
  std::vector<TargetChoice> matches;
  std::vector<DWORD> unsupported_pids;
  bool mismatch = false, unsupported = false;
  for (const auto& window : windows) {
    if (!window.visible || !window.pid || (pid && window.pid != *pid) ||
        (window_class && window.window_class != *window_class)) continue;
    const auto identity = classify_window(window.title, window.window_class);
    if (identity.client == ClientKind::Unknown) continue;
    if (requested != ClientKind::Auto && requested != identity.client) { mismatch = true; continue; }
    if (identity.version == TitleVersion::Unsupported) {
      unsupported = true; unsupported_pids.push_back(window.pid); continue;
    }
    const auto existing = std::find_if(matches.begin(), matches.end(),
        [&](const TargetChoice& value) { return value.pid == window.pid; });
    if (existing == matches.end()) matches.push_back({window.pid, identity.client, identity.version, TargetError::None});
    else if (existing->client != identity.client) return {0, ClientKind::Unknown, {}, TargetError::Ambiguous};
    else if (identity.version == TitleVersion::Supported) existing->version = identity.version;
  }
  if (pid && unsupported) return {0, ClientKind::Unknown, {}, TargetError::UnsupportedVersion};
  if (pid && mismatch) return {0, ClientKind::Unknown, {}, TargetError::ClientMismatch};
  matches.erase(std::remove_if(matches.begin(), matches.end(), [&](const TargetChoice& choice) {
    return std::find(unsupported_pids.begin(), unsupported_pids.end(), choice.pid) != unsupported_pids.end();
  }), matches.end());
  if (matches.size() > 1) return {0, ClientKind::Unknown, {}, TargetError::Ambiguous};
  if (!matches.empty()) return matches.front();
  return {0, ClientKind::Unknown, {}, unsupported ? TargetError::UnsupportedVersion
      : mismatch ? TargetError::ClientMismatch : TargetError::NotFound};
}
}  // namespace adnin
