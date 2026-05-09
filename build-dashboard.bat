@echo off
cd c:\Users\Admin\Desktop\HackathonPS
echo Building HackathonPS with integrated dashboard...
call mvn clean install -DskipTests
echo.
echo Build complete! Dashboard will be available at:
echo http://localhost:8080/dashboard
echo.
echo Or just access: http://localhost:8080
echo.
pause
