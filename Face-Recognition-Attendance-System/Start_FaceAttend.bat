@echo off
setlocal
cd /d "%~dp0"
title Face Recognition Attendance System
echo Starting Face Recognition Attendance System...
where java >nul 2>&1 || (echo ERROR: Java not found. Install JDK 17+ and add Java to PATH.& pause & exit /b 1)
where javac >nul 2>&1 || (echo ERROR: Java compiler not found. Install a JDK, not just JRE.& pause & exit /b 1)
echo Compiling Java source files...
javac -cp ".;lib\*" *.java
if errorlevel 1 (echo Compilation failed.& pause & exit /b 1)
echo Launching dashboard... Ensure local MySQL service is running.
java --enable-native-access=ALL-UNNAMED -cp ".;lib\*" FaceAttendanceDashboard
if errorlevel 1 (echo Application exited with an error.& pause)
endlocal
