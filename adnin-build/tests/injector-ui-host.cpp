// Isolated UI/cache host. It contains no process discovery or injection code.
#include "result-ui.h"
#include "payload.h"
#include "remote-lease.h"
#include "adnin-runtimes.h"
#include <iostream>
#include <string>

static const adnin::RuntimeProfile* profile_for(int argc, wchar_t** argv) {
  auto kind = adnin::PayloadKind::Lunar;
  for (int i = 1; i < argc; ++i) if (std::wstring(argv[i]) == L"--client") {
    if (++i == argc) return nullptr;
    const auto client = adnin::parse_client(argv[i]);
    if (!client) return nullptr;
    const auto selected = adnin::client_payload(*client);
    if (!selected) return nullptr;
    kind = *selected;
  }
  for (const auto& profile : ADNIN_RUNTIMES) if (profile.kind == kind) return &profile;
  return nullptr;
}

int wmain(int argc, wchar_t** argv) {
  if (argc == 2 && std::wstring(argv[1]) == L"--wait") {
    std::cout << "waiting" << std::endl;
    Sleep(30000);
    return 0;
  }
  if (argc >= 5 && std::wstring(argv[1]) == L"--target-lease") {
    try {
      const auto* profile = profile_for(argc, argv);
      if (!profile) return 2;
      auto payload = adnin::embedded_payload(*profile, argv[2]);
      HANDLE target = OpenProcess(PROCESS_DUP_HANDLE, FALSE, static_cast<DWORD>(std::stoul(argv[3])));
      if (!target) return 5;
      bool ok = false;
      {
        adnin::RemotePayloadLease lease(target, payload.handle());
        ok = lease.valid();
        if (ok && std::wstring(argv[4]) == L"retain") lease.retain_for_running_thread();
        else if (ok) ok = lease.close();
      }
      CloseHandle(target);
      return ok ? 0 : 5;
    } catch (...) { return 4; }
  }
  if (argc >= 3 && std::wstring(argv[1]) == L"--payload") {
    try {
      const auto* profile = profile_for(argc, argv);
      if (!profile) return 2;
      auto payload = adnin::embedded_payload(*profile, argv[2]);
      std::cout << "payload verified" << std::endl;
      for (int i = 3; i < argc; ++i) if (std::wstring(argv[i]) == L"--hold") Sleep(30000);
      return 0;
    } catch (const adnin::PayloadError& error) {
      std::cerr << error.what() << '\n';
      return 4;
    }
  }
  if (argc >= 3 && std::wstring(argv[1]) == L"--verify-payload") {
    try {
      const auto* profile = profile_for(argc, argv);
      if (!profile) return 2;
      auto payload = adnin::verified_payload_file(*profile, argv[2]);
      std::cout << "development payload verified" << std::endl;
      return 0;
    } catch (const adnin::PayloadError& error) {
      std::cerr << error.what() << '\n';
      return 4;
    }
  }
  if (argc != 4 || std::wstring(argv[1]) != L"--ui") return 2;
  adnin::ResultReport report;
  GetSystemTime(&report.started_utc);
  report.preview = true;
  report.status = std::wstring(argv[2]) == L"success" ? 0 : 8;
  report.failure = report.status ? "Preview failure: no injection was performed" : "";
  report.events.emplace_back("Isolated preview; no target, API, configuration or chat accessed");
  return adnin::show_result_window(report, argv[3]) ? 0 : 2;
}
