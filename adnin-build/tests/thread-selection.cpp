// Run the assembled DLL's real thread picker against a small JNI fixture.
// No JVM, game, remote process, chat text, or configuration is accessed.
#include <windows.h>
#include <array>
#include <cstdarg>
#include <cstdint>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <initializer_list>
#include <vector>
#include <utility>

using Object = void*;
using Method = void*;
struct Env { void** functions; };
enum Id : std::uintptr_t { All = 1, Keys, Iterator, HasNext, Next, Name };
static std::vector<const char*> names;
static std::size_t cursor;
static Object object(std::uintptr_t n) { return reinterpret_cast<Object>(n); }
static Object find_class(Env*, const char*) { return object(100); }
static Method method(Env*, Object, const char* name, const char*) {
  for (auto [text, id] : {std::pair{"getAllStackTraces", All}, {"keySet", Keys},
                          {"iterator", Iterator}, {"hasNext", HasNext}, {"next", Next}, {"getName", Name}})
    if (std::strcmp(name, text) == 0) return object(id);
  return nullptr;
}
static Object call_object(Env*, Object receiver, Method id, va_list) {
  switch (reinterpret_cast<std::uintptr_t>(id)) {
    case All: return object(101);
    case Keys: return object(102);
    case Iterator: cursor = 0; return object(103);
    case Next: return const_cast<char*>(names.at(cursor++));
    case Name: return receiver;
    default: std::abort();
  }
}
static unsigned char call_boolean(Env*, Object, Method, va_list) { return cursor < names.size(); }
static Object get_class(Env*, Object) { return object(104); }
static void delete_ref(Env*, Object) {}
static unsigned char exception_check(Env*) { return 0; }
static void clear_exception(Env*) {}
static const char* chars(Env*, Object string, unsigned char*) { return static_cast<const char*>(string); }
static void release_chars(Env*, Object, const char*) {}

int wmain(int argc, wchar_t** argv) {
  if (argc != 2) return 2;
  // A local process with no JVM cannot install any Minecraft hooks. Retain the
  // module until process exit, since its worker owns its own lifetime.
  auto dll = LoadLibraryW(argv[1]);
  if (!dll) return 3;
  std::array<void*, 234> table{};
  table[6] = reinterpret_cast<void*>(find_class);
  table[17] = reinterpret_cast<void*>(clear_exception);
  table[23] = reinterpret_cast<void*>(delete_ref);
  table[31] = reinterpret_cast<void*>(get_class);
  table[33] = reinterpret_cast<void*>(method);
  table[35] = reinterpret_cast<void*>(call_object);
  table[38] = reinterpret_cast<void*>(call_boolean);
  table[113] = reinterpret_cast<void*>(method);
  table[115] = reinterpret_cast<void*>(call_object);
  table[169] = reinterpret_cast<void*>(chars);
  table[170] = reinterpret_cast<void*>(release_chars);
  table[228] = reinterpret_cast<void*>(exception_check);
  Env env{table.data()};
  auto pick = reinterpret_cast<Object(*)(Env*)>(reinterpret_cast<std::uintptr_t>(dll) + 0x3d0b0);
  auto check = [&](const char* label, std::initializer_list<const char*> threads, const char* expected) {
    names = threads;
    auto actual = static_cast<const char*>(pick(&env));
    const bool ok = (!actual && !expected) || (actual && expected && std::strcmp(actual, expected) == 0);
    std::printf("%s: %s (selected %s)\n", label, ok ? "PASS" : "FAIL", actual ? actual : "none");
    return ok;
  };
  bool ok = check("HTTP worker precedes game", {"HttpClient-2-SelectorManager", "Client thread"}, "Client thread");
  ok &= check("Netty precedes renderer", {"Netty Local Client IO #0", "Render thread"}, "Render thread");
  ok &= check("No game thread", {"HttpClient-1-SelectorManager", "Client background worker"}, nullptr);
  ok &= check("Game first", {"Client thread", "HttpClient-2-SelectorManager"}, "Client thread");
  ok &= check("Empty thread list", {}, nullptr);
  return ok ? 0 : 1;
}
