@echo off
setlocal

rem These three values are replaced by the Spring Boot service per run.
set MODEL_DIR=%~dp0..
set YAML_PATH=
set SWMM_INP=

cd /d "%MODEL_DIR%\Release"
set "PATH=%MODEL_DIR%\anuga_env;%PATH%"

Project1.exe "%YAML_PATH%" "%SWMM_INP%"
set "MODEL_EXIT_CODE=%ERRORLEVEL%"

if "%MODEL_EXIT_CODE%"=="0" (
    echo [SUCCESS] Model execution completed successfully.
) else (
    echo [ERROR] Model execution failed with error code %MODEL_EXIT_CODE%.
)

exit /b %MODEL_EXIT_CODE%
