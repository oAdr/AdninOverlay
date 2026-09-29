#pragma once
#include <windows.h>
#include <filesystem>
#include <stdexcept>
#include "runtime-profile.h"

namespace adnin {
struct PayloadError : std::runtime_error {
  DWORD system_error;
  explicit PayloadError(const char* message, DWORD error = 0)
      : std::runtime_error(message), system_error(error) { }
};

class PayloadFile {
 public:
  PayloadFile(std::filesystem::path path, HANDLE file) : path_(std::move(path)), file_(file) { }
  ~PayloadFile() { if (file_ != INVALID_HANDLE_VALUE) CloseHandle(file_); }
  PayloadFile(const PayloadFile&) = delete;
  PayloadFile& operator=(const PayloadFile&) = delete;
  PayloadFile(PayloadFile&& other) noexcept : path_(std::move(other.path_)), file_(other.file_) {
    other.file_ = INVALID_HANDLE_VALUE;
  }
  const std::filesystem::path& path() const { return path_; }
  HANDLE handle() const { return file_; }
 private:
  std::filesystem::path path_;
  HANDLE file_;
};

// The open read lease prevents replacement between verification and loading.
// The optional root is supplied only by the isolated native test host.
// Production calls leave it empty and resolve FOLDERID_LocalAppData.
PayloadFile embedded_payload(const RuntimeProfile& profile, const std::filesystem::path& test_root = {});
// Explicit --dll development paths use the same hash and read-lease guarantees.
PayloadFile verified_payload_file(const RuntimeProfile& profile, const std::filesystem::path& path);
}  // namespace adnin
