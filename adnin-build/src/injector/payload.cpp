#include "payload.h"
#include <bcrypt.h>
#include <shlobj.h>
#include <array>
#include <algorithm>
#include <cstring>
#include <string>
#include <vector>

namespace adnin {
namespace {
class File {
 public:
  explicit File(HANDLE value = INVALID_HANDLE_VALUE) : value_(value) { }
  ~File() { if (value_ != INVALID_HANDLE_VALUE) CloseHandle(value_); }
  HANDLE get() const { return value_; }
  HANDLE release() { HANDLE result = value_; value_ = INVALID_HANDLE_VALUE; return result; }
 private:
  HANDLE value_;
};

std::string sha256(const unsigned char* data, DWORD size) {
  BCRYPT_ALG_HANDLE algorithm = nullptr;
  if (BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM, nullptr, 0) < 0)
    throw PayloadError("Cannot initialize payload verification");
  DWORD object_size = 0, returned = 0;
  if (BCryptGetProperty(algorithm, BCRYPT_OBJECT_LENGTH, reinterpret_cast<PUCHAR>(&object_size),
                         sizeof(object_size), &returned, 0) < 0 || !object_size) {
    BCryptCloseAlgorithmProvider(algorithm, 0);
    throw PayloadError("Cannot initialize payload verification");
  }
  std::vector<unsigned char> object(object_size);
  BCRYPT_HASH_HANDLE hash = nullptr;
  std::array<unsigned char, 32> digest{};
  NTSTATUS status = BCryptCreateHash(algorithm, &hash, object.data(), object_size, nullptr, 0, 0);
  if (status >= 0) status = BCryptHashData(hash, const_cast<PUCHAR>(data), size, 0);
  if (status >= 0) status = BCryptFinishHash(hash, digest.data(), static_cast<ULONG>(digest.size()), 0);
  if (hash) BCryptDestroyHash(hash);
  BCryptCloseAlgorithmProvider(algorithm, 0);
  if (status < 0) throw PayloadError("Payload verification failed");
  std::string text;
  text.reserve(64);
  constexpr char hex[] = "0123456789abcdef";
  for (unsigned char byte : digest) { text += hex[byte >> 4]; text += hex[byte & 15]; }
  return text;
}

void directory(const std::filesystem::path& path) {
  if (!CreateDirectoryW(path.c_str(), nullptr) && GetLastError() != ERROR_ALREADY_EXISTS)
    throw PayloadError("Cannot create the payload cache", GetLastError());
  const DWORD attributes = GetFileAttributesW(path.c_str());
  if (attributes == INVALID_FILE_ATTRIBUTES || !(attributes & FILE_ATTRIBUTE_DIRECTORY)
      || (attributes & FILE_ATTRIBUTE_REPARSE_POINT))
    throw PayloadError("The payload cache directory is not a regular directory");
}

bool identical(HANDLE file, const unsigned char* expected, DWORD size) {
  BY_HANDLE_FILE_INFORMATION info{};
  LARGE_INTEGER length{};
  if (!GetFileInformationByHandle(file, &info) || !GetFileSizeEx(file, &length)
      || (info.dwFileAttributes & (FILE_ATTRIBUTE_REPARSE_POINT | FILE_ATTRIBUTE_DIRECTORY))
      || length.QuadPart != size) return false;
  LARGE_INTEGER zero{};
  if (!SetFilePointerEx(file, zero, nullptr, FILE_BEGIN)) return false;
  std::array<unsigned char, 65536> buffer{};
  DWORD offset = 0;
  while (offset < size) {
    const DWORD wanted = std::min<DWORD>(static_cast<DWORD>(buffer.size()), size - offset);
    DWORD read = 0;
    if (!ReadFile(file, buffer.data(), wanted, &read, nullptr) || read != wanted
        || std::memcmp(buffer.data(), expected + offset, read) != 0) return false;
    offset += read;
  }
  return true;
}
}  // namespace

PayloadFile verified_payload_file(const RuntimeProfile& profile, const std::filesystem::path& path) {
  if (!valid_runtime_profile(profile)) throw PayloadError("Embedded runtime metadata is invalid");
  File file(CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING,
                       FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OPEN_REPARSE_POINT, nullptr));
  if (file.get() == INVALID_HANDLE_VALUE) throw PayloadError("Cannot open the development payload", GetLastError());
  BY_HANDLE_FILE_INFORMATION info{};
  LARGE_INTEGER length{};
  if (!GetFileInformationByHandle(file.get(), &info) || !GetFileSizeEx(file.get(), &length) ||
      (info.dwFileAttributes & (FILE_ATTRIBUTE_REPARSE_POINT | FILE_ATTRIBUTE_DIRECTORY)) ||
      length.QuadPart != profile.payload_size)
    throw PayloadError("Development payload does not match the selected runtime");
  std::vector<unsigned char> bytes(profile.payload_size);
  DWORD count = 0;
  if (!ReadFile(file.get(), bytes.data(), profile.payload_size, &count, nullptr) || count != profile.payload_size ||
      sha256(bytes.data(), profile.payload_size) != profile.sha256)
    throw PayloadError("Development payload does not match the selected runtime");
  return PayloadFile(path, file.release());
}

PayloadFile embedded_payload(const RuntimeProfile& profile, const std::filesystem::path& test_root) {
  if (!valid_runtime_profile(profile)) throw PayloadError("Embedded runtime metadata is invalid");
  HMODULE module = GetModuleHandleW(nullptr);
  HRSRC resource = FindResourceW(module, MAKEINTRESOURCEW(profile.resource_id), RT_RCDATA);
  if (!resource) throw PayloadError("Embedded payload resource is unavailable", GetLastError());
  const DWORD size = SizeofResource(module, resource);
  HGLOBAL loaded = LoadResource(module, resource);
  const auto* data = static_cast<const unsigned char*>(LockResource(loaded));
  if (!data || size != profile.payload_size || sha256(data, size) != profile.sha256)
    throw PayloadError("Embedded payload integrity check failed");

  std::filesystem::path cache = test_root;
  if (cache.empty()) {
    PWSTR local = nullptr;
    const HRESULT location = SHGetKnownFolderPath(FOLDERID_LocalAppData, KF_FLAG_DEFAULT, nullptr, &local);
    if (FAILED(location) || !local) throw PayloadError("Cannot locate the local payload cache");
    cache = local;
    CoTaskMemFree(local);
  }
  cache /= L"Adnin"; directory(cache);
  cache /= L"payload"; directory(cache);
  cache /= std::wstring(profile.sha256, profile.sha256 + 64); directory(cache);
  const auto path = cache / L"Adnin.dll";
  File existing(CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING,
                            FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OPEN_REPARSE_POINT, nullptr));
  if (existing.get() != INVALID_HANDLE_VALUE) {
    if (!identical(existing.get(), data, size))
      throw PayloadError("Cached payload is damaged. Close the game and remove the damaged Adnin cache before retrying");
    return PayloadFile(path, existing.release());
  }
  if (GetLastError() != ERROR_FILE_NOT_FOUND)
    throw PayloadError("Cannot verify the existing payload cache; no file was overwritten", GetLastError());

  // Write a private CREATE_NEW temporary file, verify it, then publish without
  // replacement. Concurrent or loaded cache copies are never overwritten.
  const auto staging = cache / (L"payload-" + std::to_wstring(GetCurrentProcessId()) + L"-"
                                + std::to_wstring(GetTickCount64()) + L".tmp");
  bool temporary_exists = false;
  try {
    {
      File output(CreateFileW(staging.c_str(), GENERIC_READ | GENERIC_WRITE, 0, nullptr, CREATE_NEW,
                              FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OPEN_REPARSE_POINT, nullptr));
      if (output.get() == INVALID_HANDLE_VALUE) throw PayloadError("Cannot create a temporary payload", GetLastError());
      temporary_exists = true;
      DWORD written = 0;
      if (!WriteFile(output.get(), data, size, &written, nullptr) || written != size
          || !FlushFileBuffers(output.get()) || !identical(output.get(), data, size))
        throw PayloadError("Cannot write or verify the payload cache", GetLastError());
    }
    if (!MoveFileExW(staging.c_str(), path.c_str(), MOVEFILE_WRITE_THROUGH)) {
      const DWORD error = GetLastError();
      if (error != ERROR_ALREADY_EXISTS && error != ERROR_FILE_EXISTS)
        throw PayloadError("Cannot publish the payload cache; no file was overwritten", error);
      DeleteFileW(staging.c_str());
    }
    temporary_exists = false;
  } catch (...) {
    if (temporary_exists) DeleteFileW(staging.c_str());
    throw;
  }
  File verified(CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING,
                            FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OPEN_REPARSE_POINT, nullptr));
  if (verified.get() == INVALID_HANDLE_VALUE || !identical(verified.get(), data, size))
    throw PayloadError("Published payload verification failed; no cached file was overwritten", GetLastError());
  return PayloadFile(path, verified.release());
}
}  // namespace adnin
