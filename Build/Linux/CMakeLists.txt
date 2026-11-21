include(App)
include(Network)
include(Rendering)
include(ClientShared)

include_directories(.)
include_directories(../ClientBase)
include_directories(../ClientShared)

list(APPEND HEADERS ConsoleShell.h)
list(APPEND SOURCES ConsoleShell.cpp)
list(APPEND HEADERS FunctionMarshaller.h)
list(APPEND SOURCES FunctionMarshaller.cpp)
list(APPEND HEADERS LinuxApp.h)
list(APPEND SOURCES LinuxApp.cpp)
list(APPEND HEADERS RobloxView.h)
list(APPEND SOURCES RobloxView.cpp)
list(APPEND HEADERS UserInput.h)
list(APPEND SOURCES UserInput.cpp)
list(APPEND SOURCES main.cpp)

# Shared with the other players.
list(APPEND SOURCES ../RobloxMac/UserInputUtil.cpp)
list(APPEND SOURCES ../ClientShared/SDLGameController.cpp)
list(APPEND SOURCES ../ClientBase/RenderSettingsItem.cpp)

add_library(LinuxShell OBJECT ${SOURCES} ${HEADERS})

add_subdirectory(fmod_sdl)
