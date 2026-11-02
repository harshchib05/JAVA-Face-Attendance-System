# Face Recognition Attendance Management System

Java Swing desktop application using JavaCV/OpenCV (Haar Cascade + LBPH) and MySQL/JDBC.

## Features
- Student registration and webcam face image capture
- Face recognition and attendance check-in
- Check-out after at least 40 minutes; then attendance becomes `Present`
- Same-day duplicate check-in/check-out prevention
- Attendance dashboard and CSV export

## Requirements
- Windows x64, webcam and camera permissions
- JDK 17+ with `java` and `javac` on PATH
- Local MySQL Server, running
- `face_attendance` database with `students` and `attendance` tables (see `schema.sql`)

## Setup (Windows)
1. Clone/download the repository and extract it fully.
2. Run `schema.sql` in MySQL Workbench **on a new empty database**. Do not run against existing data without backup.
3. Set your MySQL password in a local CMD window:
   ```cmd
   set FACE_ATTENDANCE_DB_PASSWORD=YOUR_MYSQL_PASSWORD
   Start_FaceAttend.bat
   ```
   The default MySQL username is `root`, host `localhost`, port `3306`. Adjust `DBConnection.java` if yours differs.
4. On later runs, set the environment variable again (or configure it in Windows User Environment Variables), then double-click `Start_FaceAttend.bat`.
5. Register students and capture face samples before recognition. The public repo intentionally excludes personal face images.

## Important privacy note
Never commit student face photos, database passwords or live student attendance data to a public repository. Obtain consent before collecting biometric face samples.

## Main files
- `FaceAttendanceDashboard.java`: Swing UI, webcam, detection/recognition
- `StudentDAO.java`: student records
- `AttendanceDAO.java`: 40-minute check-in/check-out and attendance records
- `AttendanceCsvExporter.java`: CSV reporting
- `DBConnection.java`: JDBC MySQL connection
- `haarcascade_frontalface_default.xml`: Haar classifier
- `lib/`: JavaCV, OpenCV, MySQL connector and native dependencies

## Validation
Java sources compiled successfully in a Linux environment with bundled JARs. Webcam capture, native Windows execution, and MySQL integration must be verified on a Windows PC. This repository is a source submission, not a hosted web app.
