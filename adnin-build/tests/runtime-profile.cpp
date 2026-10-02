// Pure target/descriptor policy. No process inspection, DLL load or settings.
#include "runtime-profile.h"
#include <array>
#include <cstdio>
#include <cstdlib>
#include <vector>

namespace {
unsigned checks = 0;
void check(bool value, const char* why) {
  ++checks;
  if (!value) { std::fprintf(stderr, "FAIL: %s\n", why); std::exit(1); }
}
using adnin::ClientKind;
using adnin::TargetError;
using adnin::TitleVersion;
constexpr wchar_t observed_badlion_title[] = L"Badlion Minecraft Client v4.4.4-f8775e4-PRODUCTION4 (1.8.9)";
adnin::WindowCandidate window(DWORD pid, const wchar_t* title, const wchar_t* cls = L"LWJGL",
                              bool visible = true, bool forge_process = false) {
  return {pid, title, cls, visible, forge_process};
}
void identity_tests() {
  for (const wchar_t* title : {L"Lunar Client (1.8.9)", L"Lunar Client 1.8.9", L"lunar client (1.8.9-master)"}) {
    const auto id = adnin::classify_window(title, L"LWJGL");
    check(id.client == ClientKind::Lunar && id.version == TitleVersion::Supported, "Known Lunar 1.8.9 title");
  }
  for (const wchar_t* title : {L"Badlion Client 1.8.9", L"Badlion Client (1.8.9)", L"Minecraft 1.8.9 | Badlion Client"}) {
    const auto id = adnin::classify_window(title, L"LWJGL");
    check(id.client == ClientKind::Badlion && id.version == TitleVersion::Supported, "Known Badlion 1.8.9 title");
  }
  for (const wchar_t* cls : {L"LWJGL", L"GLFW30", L"GLFW32"}) {
    const auto id = adnin::classify_window(observed_badlion_title, cls);
    check(id.client == ClientKind::Badlion && id.version == TitleVersion::Supported,
          "Observed Badlion Minecraft Client title is recognized on each supported game-window class");
  }
  for (const wchar_t* title : {
      L"bAdLiOn mInEcRaFt cLiEnT v4.4.4-f8775e4-PRODUCTION4 (1.8.9)",
      L"Minecraft 1.8.9 | Badlion Minecraft Client v4.4.4-f8775e4-PRODUCTION4",
      L"minecraft 1.8.9 | BADLION MINECRAFT CLIENT v4.4.4-f8775e4-PRODUCTION4"}) {
    const auto id = adnin::classify_window(title, L"LWJGL");
    check(id.client == ClientKind::Badlion && id.version == TitleVersion::Supported,
          "Badlion long brand and Minecraft suffix are case-insensitive; client v4 is not the game version");
  }
  for (const wchar_t* title : {
      L"Badlion Minecraft Client v4.4.4-f8775e4-PRODUCTION4 (1.20)",
      L"Badlion Minecraft Client v4.4.4-f8775e4-PRODUCTION4 (1.8.8)",
      L"Minecraft 1.20 | Badlion Minecraft Client v4.4.4-f8775e4-PRODUCTION4"}) {
    const auto id = adnin::classify_window(title, L"LWJGL");
    check(id.client == ClientKind::Badlion && id.version == TitleVersion::Unsupported,
          "Recognizing the Badlion long brand cannot bypass explicit unsupported Minecraft versions");
  }
  for (const wchar_t* title : {
      L"Badlion Minecraft ClientFake v4.4.4 (1.8.9)",
      L"Badlion Minecraft ClientLauncher (1.8.9)",
      L"Badlion Minecraft Client Launcher (1.8.9)",
      L"Minecraft 1.8.9 | Badlion Minecraft Client Launcher"})
    check(adnin::classify_window(title, L"LWJGL").client == ClientKind::Unknown,
          "Badlion long-brand fakes and launcher windows remain excluded");
  check(adnin::classify_window(observed_badlion_title, L"Other").client == ClientKind::Unknown,
        "Observed Badlion title still requires a recognized game-window class");
  const auto vanilla = adnin::classify_window(L"Minecraft 1.8.9", L"LWJGL");
  check(vanilla.client == ClientKind::Vanilla && vanilla.version == TitleVersion::Supported, "Vanilla 1.8.9 title");
  for (const wchar_t* title : {L"Forge 1.8.9", L"Minecraft 1.8.9 Forge", L"Minecraft 1.8.9 | Forge 11.15.1.2318"}) {
    const auto id = adnin::classify_window(title, L"LWJGL");
    check(id.client == ClientKind::Forge && id.version == TitleVersion::Supported,
          "Forge 1.8.9 titles use the compatible Forge profile");
  }
  const auto token_login = adnin::classify_window(L"TokenLogin 2.1", L"LWJGL");
  check(token_login.client == ClientKind::Forge && token_login.version == TitleVersion::Unspecified,
        "Known Forge distribution with a custom TokenLogin title is accepted for runtime handshake");
  for (const wchar_t* title : {L"Forge 1.20", L"Minecraft 1.8.8 Forge", L"Forge 1.8.90"}) {
    const auto id = adnin::classify_window(title, L"LWJGL");
    check(id.client == ClientKind::Forge && id.version == TitleVersion::Unsupported,
          "Forge titles cannot bypass the supported-version check");
  }
  for (const wchar_t* title : {L"Minecraft 1.8", L"Minecraft 1.8.8", L"Minecraft 1.8.90", L"Minecraft 1.21.1", L"Minecraft 26.1", L"Minecraft 2.0", L"Lunar Client (1.7.10)", L"Badlion Client 1.20", L"Minecraft 1.8.9x"})
    check(adnin::classify_window(title, L"LWJGL").version == TitleVersion::Unsupported, "Other explicit versions rejected");
  for (const wchar_t* title : {L"Java", L"LWJGL", L"MinecraftLauncher", L"Minecraft Launcher", L"Lunar Client Launcher", L"Other game 1.8.9", L"Badlion ClientFake 1.8.9"})
    check(adnin::classify_window(title, L"LWJGL").client == ClientKind::Unknown, "Unknown apps and launchers rejected");
  check(adnin::classify_window(L"Minecraft 1.8.9", L"Other").client == ClientKind::Unknown, "Title alone does not identify a game window");
  check(adnin::classify_window(L"TokenLogin Launcher", L"LWJGL").client == ClientKind::Unknown,
        "TokenLogin launchers remain excluded");
  for (const wchar_t* cls : {L"LWJGL", L"GLFW30", L"GLFW32"})
    check(adnin::classify_window(L"Lunar Client", cls).version == TitleVersion::Unspecified, "Missing version still requires DLL handshake");
  check(adnin::client_payload(ClientKind::Badlion) == adnin::PayloadKind::Vanilla, "Badlion uses verified Vanilla payload");
  check(adnin::client_payload(ClientKind::Forge) == adnin::PayloadKind::Forge,
        "Forge uses its own named-class/SRG payload");
  check(adnin::client_payload(ClientKind::Lunar) == adnin::PayloadKind::Lunar, "Lunar keeps its own payload");
  check(!adnin::client_payload(ClientKind::Unknown) && !adnin::client_payload(ClientKind::Auto), "Unknown client cannot choose a payload");
  for (const wchar_t* name : {L"auto", L"lunar", L"badlion", L"vanilla", L"forge"}) check(adnin::parse_client(name).has_value(), "Supported CLI client");
  for (const wchar_t* name : {L"", L"java", L"../vanilla"}) check(!adnin::parse_client(name), "Unknown CLI client rejected");
}
void selection_tests() {
  std::vector<adnin::WindowCandidate> candidates{window(10, L"Minecraft 1.8.9")};
  auto choice = adnin::choose_target(candidates, ClientKind::Auto);
  check(choice.pid == 10 && choice.client == ClientKind::Vanilla && choice.error == TargetError::None, "One known game selected");
  candidates.push_back(window(10, L"Minecraft 1.8.9 - Multiplayer"));
  check(adnin::choose_target(candidates, ClientKind::Auto).pid == 10, "Duplicate windows deduplicate by PID");
  candidates.push_back(window(20, L"Badlion Client 1.8.9"));
  check(adnin::choose_target(candidates, ClientKind::Auto).error == TargetError::Ambiguous, "Two games never choose the first PID");
  check(adnin::choose_target(candidates, ClientKind::Badlion).pid == 20, "Explicit client resolves different-client ambiguity");
  check(adnin::choose_target(candidates, ClientKind::Auto, 20).client == ClientKind::Badlion, "Explicit PID is still classified");
  check(adnin::choose_target(candidates, ClientKind::Lunar, 20).error == TargetError::ClientMismatch, "PID cannot bypass client mismatch");
  check(adnin::choose_target(candidates, ClientKind::Forge, 10).pid == 10 &&
        adnin::choose_target(candidates, ClientKind::Forge, 10).client == ClientKind::Forge,
        "Explicit Forge profile can select a generic Minecraft 1.8.9 title");
  candidates = {window(11, L"TokenLogin 2.1")};
  check(adnin::choose_target(candidates, ClientKind::Auto).pid == 11 &&
        adnin::choose_target(candidates, ClientKind::Auto).client == ClientKind::Forge,
        "Auto mode selects the known custom-title Forge window");
  candidates = {window(12, L"Minecraft 1.8.9", L"LWJGL", true, true)};
  check(adnin::choose_target(candidates, ClientKind::Auto).pid == 12 &&
        adnin::choose_target(candidates, ClientKind::Auto).client == ClientKind::Forge,
        "Forge process marker upgrades a generic Minecraft title in auto mode");
  candidates = {window(13, L"Custom Forge HUD", L"LWJGL", true, true)};
  check(adnin::choose_target(candidates, ClientKind::Auto).pid == 13 &&
        adnin::choose_target(candidates, ClientKind::Auto).client == ClientKind::Forge &&
        adnin::choose_target(candidates, ClientKind::Auto).version == TitleVersion::Unspecified,
        "Forge process marker accepts a custom title and defers version to runtime handshake");
  check(adnin::choose_target(candidates, ClientKind::Auto, 99).error == TargetError::NotFound, "Unknown PID rejected");
  check(adnin::choose_target(candidates, ClientKind::Auto, {}, L"Unknown").error == TargetError::NotFound, "Custom class does not bypass game classification");
  candidates = {window(1, L"Minecraft 1.8.9", L"LWJGL", false), window(2, L"Java")};
  check(adnin::choose_target(candidates, ClientKind::Auto).error == TargetError::NotFound, "Hidden and broad Java windows are not candidates");
  candidates = {window(1, L"Minecraft 1.21"), window(2, L"Lunar Client (1.8.9)")};
  check(adnin::choose_target(candidates, ClientKind::Auto).pid == 2, "Other-version process cannot replace sole supported candidate");
  check(adnin::choose_target(candidates, ClientKind::Auto, 1).error == TargetError::UnsupportedVersion, "Explicit old/new version PID rejected");
  candidates.push_back(window(2, L"Lunar Client (1.20)"));
  check(adnin::choose_target(candidates, ClientKind::Auto).error == TargetError::UnsupportedVersion, "Conflicting version in same PID rejects that candidate");
  candidates = {window(1, L"Minecraft 1.8.9"), window(1, L"Badlion Client 1.8.9")};
  check(adnin::choose_target(candidates, ClientKind::Auto).error == TargetError::Ambiguous, "Contradictory client identities in one PID rejected");
  for (const wchar_t* name : {L"Adnin.dll", L"AdninVanilla.dll", L"ADNIN-LUNAR.DLL", L"ChatReader.dll", L"ChatReaderLunar.dll"})
    check(adnin::known_payload_module(name), "Known cross-runtime/old payload name conflicts");
  for (const wchar_t* name : {L"jvm.dll", L"lwjgl64.dll", L"Adnin.txt", L"OtherAdnin.dll"})
    check(!adnin::known_payload_module(name), "Unrelated module is not an Adnin conflict");
  std::vector<adnin::LoadedModule> modules{{0x1000, L"C:\\fixture\\Adnin.dll", L"Adnin.dll"},
      {0x2000, L"C:\\other\\AdninVanilla.dll", L"AdninVanilla.dll"}};
  auto loaded = adnin::choose_loaded_module(modules, L"C:\\fixture\\Adnin.dll", true);
  check(loaded.base == 0x1000 && loaded.conflict, "Exact path first cannot hide later conflicting runtime");
  std::swap(modules[0], modules[1]);
  loaded = adnin::choose_loaded_module(modules, L"c:\\FIXTURE\\ADNIN.DLL", true);
  check(loaded.base == 0x1000 && loaded.conflict, "Conflict first and case-insensitive exact path have same result");
  check(!adnin::choose_loaded_module(modules, L"C:\\fixture\\Adnin.dll", false).conflict, "System loader lookup does not apply payload conflict rule");
  modules.erase(modules.begin());
  loaded = adnin::choose_loaded_module(modules, L"C:\\fixture\\Adnin.dll", true);
  check(loaded.base == 0x1000 && !loaded.conflict, "Single matching payload can proceed to handshake");
  check(!adnin::choose_loaded_module(modules, L"C:\\absent\\Adnin.dll", true).base, "Missing exact module is not treated as loaded");
}
void descriptor_tests() {
  const std::array<adnin::RuntimeCheck, 1> classes{{{0x1000, 8}}}, hooks{{{0x1010, 1}}};
  const std::array<unsigned char, 3> signature_bytes{0xeb, 0x17, 0x90};
  const std::array<adnin::RuntimeSignature, 1> signatures{{{0x1100, signature_bytes}}};
  const adnin::RuntimeProfile valid{adnin::PayloadKind::Lunar, "fixture", 101,
      "0000000000000000000000000000000000000000000000000000000000000000", 0x1800, 0x110, 0x2000, 0x1f00,
      0x3130424e494e4441ull, signatures, classes, {}, hooks, 0x1018, 8};
  check(adnin::valid_runtime_profile(valid), "Validated descriptor with optional empty pump");
  auto changed = valid; changed.kind = adnin::PayloadKind::Vanilla;
  check(adnin::valid_runtime_profile(changed), "Same schema supports compatible runtime");
  changed = valid; changed.classes = {}; check(!adnin::valid_runtime_profile(changed), "Classes must be verified");
  changed = valid; changed.hooks = {}; check(!adnin::valid_runtime_profile(changed), "Hooks must be verified");
  changed = valid; changed.heartbeat_rva = 0; check(!adnin::valid_runtime_profile(changed), "Heartbeat cannot be omitted");
  changed = valid; changed.heartbeat_width = 2; check(!adnin::valid_runtime_profile(changed), "Invalid heartbeat width rejected");
  changed = valid; changed.heartbeat_rva = 0xffffffffu; check(!adnin::valid_runtime_profile(changed), "Overflowing heartbeat rejected");
  changed = valid; changed.marker_rva = 0x1ff9; check(!adnin::valid_runtime_profile(changed), "Marker must fit entirely inside image");
  changed = valid; changed.marker = 0; check(!adnin::valid_runtime_profile(changed), "Marker required");
  changed = valid; changed.pe_header_offset = 0x2000; check(!adnin::valid_runtime_profile(changed), "PE header cannot lie outside image");
  changed = valid; changed.pe_header_offset = 1; check(!adnin::valid_runtime_profile(changed), "PE offset cannot overlap DOS header");
  changed = valid; changed.sha256 = "xyz"; check(!adnin::valid_runtime_profile(changed), "Invalid digest rejected");
  changed = valid; changed.payload_size = 0; check(!adnin::valid_runtime_profile(changed), "Payload size required");
  changed = valid; changed.resource_id = 0; check(!adnin::valid_runtime_profile(changed), "Resource id required");
  const std::array<adnin::RuntimeCheck, 1> out_of_range{{{0xffffffffu, 8}}}, bad_width{{{0x1000, 2}}};
  changed = valid; changed.classes = out_of_range; check(!adnin::valid_runtime_profile(changed), "Class RVAs validated without wraparound");
  changed = valid; changed.hooks = bad_width; check(!adnin::valid_runtime_profile(changed), "Hook status width validated");
  changed = valid; changed.pump = out_of_range; check(!adnin::valid_runtime_profile(changed), "Optional pump RVAs validated if present");
  const std::array<adnin::RuntimeSignature, 1> bad_signature{{{0x1fff, signature_bytes}}}, empty_signature{{{0x1100, {}}}};
  changed = valid; changed.signatures = bad_signature; check(!adnin::valid_runtime_profile(changed), "Signature must fit inside image");
  changed = valid; changed.signatures = empty_signature; check(!adnin::valid_runtime_profile(changed), "Empty signature rejected");
}
void badlion_selection_tests() {
  std::vector<adnin::WindowCandidate> candidates{window(30, observed_badlion_title)};
  auto selected = adnin::choose_target(candidates, ClientKind::Auto);
  check(selected.pid == 30 && selected.client == ClientKind::Badlion &&
        selected.version == TitleVersion::Supported && selected.error == TargetError::None,
        "Auto selects the observed Badlion title as supported 1.8.9");
  selected = adnin::choose_target(candidates, ClientKind::Badlion);
  check(selected.pid == 30 && selected.error == TargetError::None,
        "Explicit Badlion client selects the observed window");
  selected = adnin::choose_target(candidates, ClientKind::Auto, 30);
  check(selected.pid == 30 && selected.client == ClientKind::Badlion && selected.error == TargetError::None,
        "Explicit PID keeps the observed Badlion client identity");
  check(adnin::choose_target(candidates, ClientKind::Vanilla, 30).error == TargetError::ClientMismatch,
        "Observed Badlion PID cannot bypass an explicit different client");
  candidates.push_back(window(30, L"Minecraft 1.8.9 | Badlion Minecraft Client v4.4.4-f8775e4-PRODUCTION4"));
  check(adnin::choose_target(candidates, ClientKind::Auto).pid == 30,
        "Both long-brand window forms in one PID deduplicate to one Badlion target");
  candidates.push_back(window(40, observed_badlion_title));
  check(adnin::choose_target(candidates, ClientKind::Auto).error == TargetError::Ambiguous,
        "Two observed Badlion processes stay ambiguous in auto mode");
  check(adnin::choose_target(candidates, ClientKind::Badlion).error == TargetError::Ambiguous,
        "Client choice alone cannot resolve two Badlion processes");
  check(adnin::choose_target(candidates, ClientKind::Badlion, 40).pid == 40,
        "Explicit Badlion PID resolves same-client process ambiguity");
  candidates = {window(30, observed_badlion_title), window(50, L"Lunar Client (1.8.9)")};
  check(adnin::choose_target(candidates, ClientKind::Auto).error == TargetError::Ambiguous,
        "Observed Badlion and existing Lunar windows remain ambiguous together");
  check(adnin::choose_target(candidates, ClientKind::Badlion).pid == 30,
        "Explicit Badlion choice selects its process beside Lunar");
  check(adnin::choose_target(candidates, ClientKind::Lunar).pid == 50,
        "Existing Lunar choice is unchanged beside the observed Badlion window");
  candidates = {window(30, L"Badlion Minecraft Client v4.4.4-f8775e4-PRODUCTION4 (1.20)")};
  check(adnin::choose_target(candidates, ClientKind::Badlion, 30).error == TargetError::UnsupportedVersion,
        "Explicit Badlion PID cannot bypass the unsupported game version");
  candidates = {window(30, observed_badlion_title, L"LWJGL", false)};
  check(adnin::choose_target(candidates, ClientKind::Auto).error == TargetError::NotFound,
        "Observed Badlion title is still excluded when its window is hidden");
}
}  // namespace
int main() {
  identity_tests(); selection_tests(); descriptor_tests(); badlion_selection_tests();
  std::printf("RuntimeProfileTest: %u checks passed; no process access, DLL execution, network or settings\n", checks);
}
