#pragma once
#include <windows.h>

namespace adnin {
// Keep the verified file locked even if an unresolved remote loader outlives
// this injector. Only the embedded-payload path creates this duplicate.
class RemotePayloadLease {
 public:
  RemotePayloadLease(HANDLE process, HANDLE file) noexcept : process_(process) {
    if (file && !DuplicateHandle(GetCurrentProcess(), file, process_, &remote_, 0, FALSE, DUPLICATE_SAME_ACCESS))
      error_ = GetLastError();
  }
  ~RemotePayloadLease() { close(); }
  RemotePayloadLease(const RemotePayloadLease&) = delete;
  RemotePayloadLease& operator=(const RemotePayloadLease&) = delete;
  bool valid() const noexcept { return error_ == ERROR_SUCCESS; }
  DWORD error() const noexcept { return error_; }
  void retain_for_running_thread() noexcept { remote_ = nullptr; }
  bool close() noexcept {
    if (!remote_) return true;
    HANDLE local = nullptr;
    const BOOL ok = DuplicateHandle(process_, remote_, GetCurrentProcess(), &local, 0, FALSE,
                                     DUPLICATE_SAME_ACCESS | DUPLICATE_CLOSE_SOURCE);
    if (!ok) error_ = GetLastError();
    // DUPLICATE_CLOSE_SOURCE closes the source even when duplication fails.
    remote_ = nullptr;
    if (local) CloseHandle(local);
    return ok != FALSE;
  }
 private:
  HANDLE process_;
  HANDLE remote_ = nullptr;
  DWORD error_ = ERROR_SUCCESS;
};
}  // namespace adnin
