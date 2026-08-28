// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <stddef.h>

#include "native_api.h"

constexpr int kHookSuccess = 0;
constexpr int kHookFailed = 1;

struct NativeSymbolResolver;

bool InitializeLsposedHookBackend(const NativeAPIEntries* entries);
bool EnsureLsposedMadviseGuard(const char* runtime_name = nullptr);
int InstallPltHook(void* base_addr, const char* symbol, void* hook_handler,
                   void** original);
int InstallInlineHook(void* target, void* replacement, void** original);
int RemoveInlineHook(void* target);
NativeSymbolResolver* NewNativeSymbolResolver(const char* path,
                                              void* base_addr);
void FreeNativeSymbolResolver(NativeSymbolResolver* resolver);
void* GetNativeBaseAddress(NativeSymbolResolver* resolver);
void* LookupNativeSymbol(NativeSymbolResolver* resolver, const char* name,
                         bool prefix, size_t* size);
// Returns the sole R_AARCH64_JUMP_SLOT for an exact dynamic symbol name.
// The lookup is read-only and fails closed for missing or ambiguous slots.
void** LookupNativePltSlot(NativeSymbolResolver* resolver, const char* name);
