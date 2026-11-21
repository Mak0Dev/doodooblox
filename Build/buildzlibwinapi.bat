@echo off
rem Rebuilds zlib_winapi.lib (stdcall / ZLIB_WINAPI zlib 1.2.8) used by
rem graphics3D-based links (RCCService, WindowsClient, ...). Contribs ships
rem only a cdecl zlib.lib, but G3D compiles with ZLIB_WINAPI and references
rem _uncompress@16 / _inflate@8 / ... style symbols.
rem Requires VS2012 (v110) toolchain at the default path.
setlocal
set ZLIB_SRC=%~dp0..\Contribs\windows\x86\zlib\zlib-1.2.8
set ZLIB_LIB=%~dp0..\Contribs\windows\x86\zlib\zlib-1.2.8\lib
set ZLIB_OBJ=%TEMP%\zlib_winapi_objs

call "C:\Program Files (x86)\Microsoft Visual Studio 11.0\VC\bin\vcvars32.bat" >NUL
if not exist "%ZLIB_OBJ%" mkdir "%ZLIB_OBJ%"

cl /nologo /MD /O2 /GL- /DZLIB_WINAPI /DWIN32 /D_WINDOWS /DNDEBUG /c /Fo%ZLIB_OBJ%\ /I%ZLIB_SRC%\ ^
  %ZLIB_SRC%\adler32.c %ZLIB_SRC%\compress.c %ZLIB_SRC%\crc32.c %ZLIB_SRC%\deflate.c ^
  %ZLIB_SRC%\gzclose.c %ZLIB_SRC%\gzlib.c %ZLIB_SRC%\gzread.c %ZLIB_SRC%\gzwrite.c ^
  %ZLIB_SRC%\infback.c %ZLIB_SRC%\inffast.c %ZLIB_SRC%\inflate.c %ZLIB_SRC%\inftrees.c ^
  %ZLIB_SRC%\trees.c %ZLIB_SRC%\uncompr.c %ZLIB_SRC%\zutil.c || exit /b 1

lib /nologo /OUT:%ZLIB_LIB%\zlib_winapi.lib %ZLIB_OBJ%\*.obj || exit /b 1
rmdir /s /q "%ZLIB_OBJ%"
echo Built %ZLIB_LIB%\zlib_winapi.lib
endlocal
