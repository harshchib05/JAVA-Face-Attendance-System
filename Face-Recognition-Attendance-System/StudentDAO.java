import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class StudentDAO {

    public int getTotalStudents() {
        String sql = "SELECT COUNT(*) FROM students";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            System.err.println("Error fetching total students: " + e.getMessage());
        }
        return 0;
    }

    public Object[][] getAllStudents() {
        String sql = "SELECT student_id, roll_no, name, department FROM students ORDER BY student_id ASC";
        List<Object[]> list = new ArrayList<>();
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Object[]{
                    rs.getInt("student_id"),
                    rs.getString("roll_no"),
                    rs.getString("name"),
                    rs.getString("department")
                });
            }
        } catch (SQLException e) {
            System.err.println("Error fetching students: " + e.getMessage());
        }
        return list.toArray(new Object[0][]);
    }
    
public boolean updateStudent(int studentId, String rollNo,
                             String name, String department) {
    String sql = "UPDATE students SET roll_no = ?, name = ?, department = ? "
               + "WHERE student_id = ?";

    try (Connection con = DBConnection.getConnection();
         PreparedStatement ps = con.prepareStatement(sql)) {

        ps.setString(1, rollNo);
        ps.setString(2, name);
        ps.setString(3, department);
        ps.setInt(4, studentId);

        return ps.executeUpdate() > 0;

    } catch (SQLException e) {
        System.err.println("Error updating student: " + e.getMessage());
        return false;
    }
}


    public boolean addStudent(String rollNo, String name, String department) {
        String sql = "INSERT INTO students (roll_no, name, department) VALUES (?, ?, ?)";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, rollNo);
            ps.setString(2, name);
            ps.setString(3, department);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error adding student: " + e.getMessage());
            return false;
        }
    }

    public boolean deleteStudent(int studentId) {
        String sql = "DELETE FROM students WHERE student_id = ?";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error deleting student: " + e.getMessage());
            return false;
        }
    }
}
