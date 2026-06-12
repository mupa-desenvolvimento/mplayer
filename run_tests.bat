@echo off
echo Running MPlayer Unit and Integration Tests...
call gradlew.bat testModernDebugUnitTest --info
pause
