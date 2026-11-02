-- Run only on a new empty database. Back up existing data first.
CREATE DATABASE IF NOT EXISTS face_attendance;
USE face_attendance;
CREATE TABLE IF NOT EXISTS students (
 student_id INT AUTO_INCREMENT PRIMARY KEY,
 roll_no VARCHAR(64) NOT NULL UNIQUE,
 name VARCHAR(150) NOT NULL,
 department VARCHAR(150),
 face_image_path VARCHAR(500)
);
CREATE TABLE IF NOT EXISTS attendance (
 attendance_id INT AUTO_INCREMENT PRIMARY KEY,
 student_id INT NOT NULL,
 attendance_date DATE NOT NULL,
 check_in_time TIME NULL,
 check_out_time TIME NULL,
 status VARCHAR(30) NOT NULL DEFAULT 'Pending',
 UNIQUE KEY unique_student_day (student_id, attendance_date),
 CONSTRAINT fk_attendance_student FOREIGN KEY (student_id) REFERENCES students(student_id)
);
