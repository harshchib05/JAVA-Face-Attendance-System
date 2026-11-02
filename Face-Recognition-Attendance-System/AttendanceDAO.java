import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class AttendanceDAO {

    public int getPresentTodayCount() {
        String sql = "SELECT COUNT(DISTINCT student_id) FROM attendance WHERE attendance_date = CURDATE() AND status = 'Present' AND check_out_time IS NOT NULL";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            System.err.println("Error getting present count: " + e.getMessage());
        }
        return 0;
    }

    public Object[][] getTodayAttendanceRecords() {
        String sql = "SELECT s.student_id, s.roll_no, s.name, a.attendance_date, a.check_in_time, a.check_out_time, " +
                     "COALESCE(a.status, 'Absent') AS status " +
                     "FROM students s " +
                     "LEFT JOIN attendance a ON s.student_id = a.student_id AND a.attendance_date = CURDATE() " +
                     "ORDER BY s.student_id ASC";
        List<Object[]> list = new ArrayList<>();
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Time time = rs.getTime("check_in_time");
                Time checkoutTime = rs.getTime("check_out_time");
                String timeStr = time != null ? time.toString() : "--:--";
                String checkoutStr = checkoutTime != null ? checkoutTime.toString() : "--:--";
                list.add(new Object[]{
                    rs.getString("roll_no"),
                    rs.getString("name"),
                    rs.getDate("attendance_date") != null ? rs.getDate("attendance_date").toString() : LocalDate.now().toString(),
                    timeStr,
                    checkoutStr,
                    rs.getString("status")
                });
            }
        } catch (SQLException e) {
            System.err.println("Error fetching attendance records: " + e.getMessage());
        }
        return list.toArray(new Object[0][]);
    }


    /**
     * Camera: first recognition checks in; before 40 minutes, do not check out;
     * at/after 40 minutes, set check-out time. Further recognition will not alter it.
     */
    public String processFaceRecognitionAttendance(String rollNo) {
        String findStudentSql = "SELECT student_id FROM students WHERE roll_no = ?";
        String findTodaySql = "SELECT check_in_time, check_out_time, " +
                "TIMESTAMPDIFF(MINUTE, TIMESTAMP(attendance_date, check_in_time), NOW()) AS minutes_elapsed " +
                "FROM attendance WHERE student_id = ? AND attendance_date = CURDATE() FOR UPDATE";
        String insertSql = "INSERT INTO attendance (student_id, attendance_date, check_in_time, check_out_time, status) " +
                "VALUES (?, CURDATE(), CURTIME(), NULL, 'Pending')";
        String startExistingSql = "UPDATE attendance SET check_in_time = CURTIME(), check_out_time = NULL, status = 'Pending' " +
                "WHERE student_id = ? AND attendance_date = CURDATE() AND check_in_time IS NULL";
        String checkoutSql = "UPDATE attendance SET check_out_time = CURTIME(), status = 'Present' " +
                "WHERE student_id = ? AND attendance_date = CURDATE() AND check_out_time IS NULL";
        try (Connection con = DBConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                int studentId;
                try (PreparedStatement ps = con.prepareStatement(findStudentSql)) {
                    ps.setString(1, rollNo);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            con.rollback();
                            return "ERROR: Student roll number was not found.";
                        }
                        studentId = rs.getInt("student_id");
                    }
                }

                try (PreparedStatement ps = con.prepareStatement(findTodaySql)) {
                    ps.setInt(1, studentId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            try (PreparedStatement insert = con.prepareStatement(insertSql)) {
                                insert.setInt(1, studentId);
                                insert.executeUpdate();
                            }
                            con.commit();
                            return "CHECKED_IN";
                        }

                        Time checkIn = rs.getTime("check_in_time");
                        Time checkOut = rs.getTime("check_out_time");
                        int minutes = rs.getInt("minutes_elapsed");

                        if (checkOut != null) {
                            con.commit();
                            return "ALREADY_CHECKED_OUT:" + checkOut;
                        }

                        if (checkIn == null) {
                            try (PreparedStatement update = con.prepareStatement(startExistingSql)) {
                                update.setInt(1, studentId);
                                update.executeUpdate();
                            }
                            con.commit();
                            return "CHECKED_IN";
                        }

                        if (minutes >= 40) {
                            try (PreparedStatement update = con.prepareStatement(checkoutSql)) {
                                update.setInt(1, studentId);
                                update.executeUpdate();
                            }
                            try (PreparedStatement timeQuery = con.prepareStatement(
                                    "SELECT check_out_time FROM attendance WHERE student_id = ? AND attendance_date = CURDATE()")) {
                                timeQuery.setInt(1, studentId);
                                try (ResultSet timeRs = timeQuery.executeQuery()) {
                                    String time = timeRs.next() && timeRs.getTime("check_out_time") != null
                                            ? timeRs.getTime("check_out_time").toString() : "--:--";
                                    con.commit();
                                    return "CHECKED_OUT:" + time;
                                }
                            }
                        }

                        con.commit();
                        int remaining = Math.max(1, 40 - minutes);
                        return "ALREADY_CHECKED_IN:" + remaining;
                    }
                }
            } catch (SQLException ex) {
                con.rollback();
                throw ex;
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.err.println("Error processing face attendance: " + e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    // Manual attendance is restricted to Absent; camera-based Present requires checkout.
    // This avoids bypassing the 40-minute rule using the manual attendance screen.
    public boolean markAttendanceByRollNo(String rollNo, String status) {
        if (!"Absent".equals(status)) return false;
        String sql = "INSERT INTO attendance (student_id, attendance_date, check_in_time, check_out_time, status) " +
                     "SELECT student_id, CURDATE(), NULL, NULL, 'Absent' FROM students WHERE roll_no = ? " +
                     "ON DUPLICATE KEY UPDATE status = IF(check_in_time IS NULL AND check_out_time IS NULL, 'Absent', status)";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, rollNo);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error marking manual absence: " + e.getMessage());
            return false;
        }
    }
}
