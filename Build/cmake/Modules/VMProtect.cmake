if(ANDROID)
  # VMProtect has no Android SDK; this header turns its markers into no-ops.
  set(vmprotect_ROOT "${CONTRIB_PATH}/VMProtectStub")
else()
  set(vmprotect_ROOT "${CONTRIB_PATH}/VMProtectWin")
endif()
include_directories("${vmprotect_ROOT}")
